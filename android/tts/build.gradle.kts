plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

tasks.named("preBuild") {
    doFirst {
        for (path in listOf("tts-assets/kokoro/model.int8.onnx", "tts-assets/kokoro/voices.bin",
            "tts-jni/arm64-v8a/libsherpa-onnx-jni.so", "tts-jni/arm64-v8a/libonnxruntime.so")) {
            check(file("../.voice/$path").isFile) { "Missing $path; run python3 tools/prepare_voice.py from android/" }
        }
    }
}

android {
    namespace = "com.openodoubao.tts"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.openodoubao.tts"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    sourceSets.getByName("main") {
        java.srcDir("../third_party/sherpa-onnx/tts")
        assets.srcDir("../.voice/tts-assets")
        jniLibs.srcDir("../.voice/tts-jni")
    }
    androidResources { noCompress += listOf("onnx", "bin") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // sherpa JNI looks up the concrete invoke(float[]): Integer callback method.
        freeCompilerArgs += "-Xlambdas=class"
    }
}
