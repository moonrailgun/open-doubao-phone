#!/usr/bin/env python3
"""Fetch pinned voice inputs, build ARM64 JNI, and stage assets for both APKs."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.voice'
RELEASE = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/'
INPUTS = {
    'sherpa-onnx-v1.13.8.tar.gz': ('https://github.com/k2-fsa/sherpa-onnx/archive/refs/tags/v1.13.8.tar.gz', 'b0374cc56dbc186d442ae73d5de743bb092470b640c4c50ce7b029044c0c4fa8'),
    'onnxruntime-android-1.28.2.zip': ('https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-1.28.2.zip', '01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d'),
    'sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2': (RELEASE + 'asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2', '7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e'),
    'sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01.tar.bz2': (RELEASE + 'kws-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01.tar.bz2', 'b2f7c89690dc8ce4c6ed6afeab7cd800c36ad1421fb6b6302b4a4b194cf7f35f'),
    'vits-piper-zh_CN-xiao_ya-medium.tar.bz2': (RELEASE + 'tts-models/vits-piper-zh_CN-xiao_ya-medium.tar.bz2', '9396a3dffbb95b037acaa18094500f58d0db9a7c4f2689554e2539717cf0db65'),
    'silero_vad.onnx': (RELEASE + 'asr-models/silero_vad.onnx', '9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6'),
}


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def fetch():
    CACHE.mkdir(exist_ok=True)
    for name, (url, expected) in INPUTS.items():
        path = CACHE / name
        if not path.exists():
            temporary = path.with_suffix(path.suffix + '.partial')
            print('Downloading', name, flush=True)
            urllib.request.urlretrieve(url, temporary)
            if sha256(temporary) != expected:
                raise RuntimeError('Checksum mismatch: ' + name)
            temporary.replace(path)
        if sha256(path) != expected:
            raise RuntimeError('Checksum mismatch: ' + name)
        if name.endswith('.zip'):
            target = CACHE / 'onnxruntime-1.28.2'
            if not target.exists():
                with zipfile.ZipFile(path) as archive:
                    archive.extractall(target)
        elif '.tar.' in name:
            directory = 'sherpa-onnx-1.13.8' if name.startswith('sherpa-onnx-v') else name.split('.tar.')[0]
            if not (CACHE / directory).exists():
                with tarfile.open(path) as archive:
                    archive.extractall(CACHE, filter='data')


def copy(source, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    if source.is_dir():
        shutil.copytree(source, target, dirs_exist_ok=True)
    else:
        shutil.copy2(source, target)


def stage_assets():
    assistant = CACHE / 'assistant-assets/voice'
    asr = CACHE / 'sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17'
    for name in ('model.int8.onnx', 'tokens.txt', 'LICENSE'):
        copy(asr / name, assistant / 'asr' / name)
    copy(asr / 'test_wavs/zh.wav', CACHE / 'test-assets/zh.wav')
    copy(CACHE / 'silero_vad.onnx', assistant / 'silero_vad.onnx')
    kws = CACHE / 'sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01'
    for name in ('encoder', 'decoder', 'joiner'):
        copy(kws / f'{name}-epoch-12-avg-2-chunk-16-left-64.int8.onnx', assistant / 'kws' / (name + '.onnx'))
    copy(kws / 'tokens.txt', assistant / 'kws/tokens.txt')
    tokens = {line.split()[0] for line in (kws / 'tokens.txt').read_text().splitlines()}
    keywords = (ROOT / 'app/src/main/assets/voice/kws/keywords.txt').read_text().splitlines()
    phrases = set()
    for line in keywords:
        pronunciation, phrase = line.split('@')
        assert set(pronunciation.split()) <= tokens, 'Unknown KWS token: ' + line
        phrases.add(phrase)
    assert len(keywords) == 4 and phrases == {'豆馅你好', '你好豆馅', '豆馅同志', '同志你好'}
    tts = CACHE / 'vits-piper-zh_CN-xiao_ya-medium'
    # Remove the previous generated model so upgrades do not package both voices.
    if (CACHE / 'tts-assets/kokoro').exists():
        shutil.rmtree(CACHE / 'tts-assets/kokoro')
    for name in ('zh_CN-xiao_ya-medium.onnx', 'tokens.txt', 'lexicon.txt', 'MODEL_CARD', 'date.fst', 'number.fst', 'phone.fst'):
        copy(tts / name, CACHE / 'tts-assets/xiao_ya' / name)
    for assets in ('assistant-assets', 'tts-assets'):
        copy(ROOT / 'third_party/sherpa-onnx/licenses', CACHE / assets / 'licenses')
        copy(ROOT / 'third_party/sherpa-onnx/LICENSE', CACHE / assets / 'licenses/sherpa-onnx-LICENSE')


def bundle_sources():
    """Ship this alongside the TTS APK, including the native dependencies actually built."""
    source = CACHE / 'sherpa-onnx-1.13.8'
    dependencies = source / 'build-tts-arm64-v8a/_deps'
    if not (dependencies / 'espeak_ng-src/COPYING').is_file():
        raise RuntimeError('Build the native TTS runtime before exporting its source')
    target = CACHE / 'voice-corresponding-source.tar.gz'
    def keep(info):
        parts = Path(info.name).parts
        if any(part in ('.git', '.gradle', '.voice', '.kotlin', '__pycache__') or
               part == 'build' or part.startswith('build-') for part in parts):
            return None
        if info.name.endswith(('local.properties', '.jks', '.keystore')):
            return None
        return info
    with tarfile.open(target, 'w:gz') as archive:
        archive.add(ROOT, arcname='android', filter=keep)
        archive.add(source, arcname='native/sherpa-onnx-1.13.8', filter=keep)
        for dependency in sorted(dependencies.glob('*-src')):
            archive.add(dependency, arcname='native/dependencies/' + dependency.name, filter=keep)
    print('Corresponding source:', target, sha256(target), flush=True)


def build(tts):
    ndk = Path(os.environ.get('ANDROID_NDK', str(Path.home() / 'Library/Android/sdk/ndk/28.2.13676358')))
    if not (ndk / 'build/cmake/android.toolchain.cmake').is_file():
        raise RuntimeError('Set ANDROID_NDK to an installed Android NDK (tested: 28.2.13676358)')
    source = CACHE / 'sherpa-onnx-1.13.8'
    build_dir = source / ('build-tts-arm64-v8a' if tts else 'build-android-arm64-v8a')
    ort = CACHE / 'onnxruntime-1.28.2'
    env = dict(os.environ, SHERPA_ONNXRUNTIME_LIB_DIR=str(ort / 'jni/arm64-v8a'),
               SHERPA_ONNXRUNTIME_INCLUDE_DIR=str(ort / 'headers'))
    flags = {
        'CMAKE_TOOLCHAIN_FILE': ndk / 'build/cmake/android.toolchain.cmake',
        'ANDROID_ABI': 'arm64-v8a', 'ANDROID_PLATFORM': 'android-30',
        'CMAKE_BUILD_TYPE': 'Release', 'BUILD_SHARED_LIBS': 'ON',
        'SHERPA_ONNX_ENABLE_TTS': 'ON' if tts else 'OFF',
        'SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION': 'OFF', 'SHERPA_ONNX_ENABLE_JNI': 'ON',
        'SHERPA_ONNX_ENABLE_BINARY': 'OFF', 'SHERPA_ONNX_ENABLE_C_API': 'OFF',
        'SHERPA_ONNX_ENABLE_PYTHON': 'OFF', 'SHERPA_ONNX_ENABLE_TESTS': 'OFF',
        'SHERPA_ONNX_ENABLE_CHECK': 'OFF', 'SHERPA_ONNX_ENABLE_PORTAUDIO': 'OFF',
        'SHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY': 'OFF',
        'BUILD_PIPER_PHONMIZE_EXE': 'OFF', 'BUILD_PIPER_PHONMIZE_TESTS': 'OFF',
        'BUILD_ESPEAK_NG_EXE': 'OFF', 'BUILD_ESPEAK_NG_TESTS': 'OFF',
        'CMAKE_INSTALL_PREFIX': build_dir / 'install',
    }
    subprocess.run(['cmake', '-S', str(source), '-B', str(build_dir)] +
                   [f'-D{key}={value}' for key, value in flags.items()], env=env, check=True)
    subprocess.run(['cmake', '--build', str(build_dir), '--parallel', '4'], check=True)
    subprocess.run(['cmake', '--install', str(build_dir), '--strip'], check=True)
    target = CACHE / ('tts-jni' if tts else 'assistant-jni') / 'arm64-v8a'
    for library in (build_dir / 'install/lib').glob('*.so'):
        copy(library, target / library.name)
    copy(ort / 'jni/arm64-v8a/libonnxruntime.so', target / 'libonnxruntime.so')
    if not tts:
        assert 'SHERPA_ONNX_ENABLE_TTS:BOOL=OFF' in (build_dir / 'CMakeCache.txt').read_text()
    assert (target / 'libsherpa-onnx-jni.so').stat().st_size > 0


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assets-only', action='store_true')
    parser.add_argument('--source-bundle', action='store_true', help='Also export APK and native source for redistribution')
    args = parser.parse_args()
    fetch()
    stage_assets()
    if not args.assets_only:
        build(False)
        build(True)
    if args.source_bundle:
        bundle_sources()
    print('Voice inputs prepared in', CACHE)
