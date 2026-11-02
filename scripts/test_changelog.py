import datetime
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

import changelog


ROOT = Path(__file__).resolve().parents[1]
EN = "# Changelog\n\n## [Unreleased]\n\n- Retry failed tasks.\n\n## [1.0.0] - 2026-10-20\n\n- Initial release.\n"
ZH = "# 更新日志\n\n## [Unreleased]\n\n- 重试失败的任务。\n\n## [1.0.0] - 2026-10-20\n\n- 首次发布。\n"


class ChangelogFixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.directory = self.root / "docs/changelog"
        self.directory.mkdir(parents=True)
        self.write(EN, ZH)

    def write(self, en, zh):
        for locale, source in (("en", en), ("zh-CN", zh)):
            (self.directory / f"{locale}.md").write_text(source, encoding="utf-8")


class ChangelogTest(ChangelogFixture):
    def test_archive_preserves_history_and_leaves_empty_unreleased(self):
        changelog.archive(self.root, "1.1.0", datetime.date(2026, 11, 2))
        for locale, original in (("en", EN), ("zh-CN", ZH)):
            actual = (self.directory / f"{locale}.md").read_text(encoding="utf-8")
            self.assertEqual(actual, original.replace(
                "## [Unreleased]\n", "## [Unreleased]\n\n## [1.1.0] - 2026-11-02\n", 1))
        self.assertEqual(changelog.notes(self.root, "1.1.0"),
                         "## 简体中文\n\n- 重试失败的任务。\n\n## English\n\n- Retry failed tasks.\n")

    def test_checks_and_release_notes_do_not_modify_files_or_include_pending_entries(self):
        changelog.check(self.root, "1.1.0")
        self.assertEqual(changelog.notes(self.root, "1.0.0"),
                         "## 简体中文\n\n- 首次发布。\n\n## English\n\n- Initial release.\n")
        self.assertEqual((self.directory / "en.md").read_text(), EN)
        self.assertEqual((self.directory / "zh-CN.md").read_text(), ZH)

    def test_archive_only_replaces_the_section_heading(self):
        en = EN.replace("# Changelog", "# Changelog for ## [Unreleased]")
        self.write(en, ZH)
        changelog.archive(self.root, "1.1.0", datetime.date(2026, 11, 2))
        self.assertEqual((self.directory / "en.md").read_text(), en.replace(
            "\n## [Unreleased]\n", "\n## [Unreleased]\n\n## [1.1.0] - 2026-11-02\n", 1))

    def test_invalid_notes_never_partially_archive(self):
        invalid = [
            ZH.replace("- 重试失败的任务。", "  - 嵌套条目"),
            ZH.replace("- 重试失败的任务。", "- \n"),
            ZH.replace("- 重试失败的任务。", "正文不是条目"),
            ZH.replace("- 重试失败的任务。", "- 一个条目\n- 多出一个条目"),
            ZH.replace("## [Unreleased]", "## [1.0.1] - 2026-11-01"),
            ZH.replace("## [1.0.0]", "## [Unreleased]"),
            ZH.replace("## [1.0.0] - 2026-10-20", "## [Unreleased]"),
            ZH.replace("2026-10-20", "2026-02-30"),
            ZH.replace("2026-10-20", "2026-10-21"),
            ZH + "\n## [1.0.0] - 2026-10-20\n\n- 重复版本。\n",
            ZH + "\n## [1.0.1] - 2026-10-21\n\n- 顺序错误。\n",
        ]
        for source in invalid:
            with self.subTest(source=source):
                self.write(EN, source)
                with self.assertRaises(ValueError):
                    changelog.archive(self.root, "1.1.0")
                self.assertEqual((self.directory / "en.md").read_text(), EN)
                self.assertEqual((self.directory / "zh-CN.md").read_text(), source)

    def test_rejects_empty_pending_notes_duplicate_or_older_version(self):
        for version in ("1.0.0", "0.9.0", "v1.1.0", "1.1", "01.1.0"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                changelog.archive(self.root, version)
        self.write(EN.replace("- Retry failed tasks.\n", ""), ZH.replace("- 重试失败的任务。\n", ""))
        changelog.check(self.root)
        with self.assertRaises(ValueError):
            changelog.archive(self.root, "1.1.0")

    def test_missing_locale_or_release_is_an_error(self):
        with self.assertRaises(ValueError):
            changelog.notes(self.root, "9.9.9")
        (self.directory / "zh-CN.md").unlink()
        with self.assertRaises(OSError):
            changelog.archive(self.root, "1.1.0")
        self.assertEqual((self.directory / "en.md").read_text(), EN)


class ReleaseTest(ChangelogFixture):
    def setUp(self):
        super().setUp()
        # Use real Git only in a disposable repository; GitHub is a local recorder.
        shutil.copy(ROOT / "release.sh", self.root)
        (self.root / "scripts").mkdir()
        shutil.copy(ROOT / "scripts/changelog.py", self.root / "scripts")
        (self.root / "android/app").mkdir(parents=True)
        (self.root / "android/app/build.gradle.kts").write_text('versionCode = 1\nversionName = "1.0.0"\n')
        (self.root / "desktop/src-tauri").mkdir(parents=True)
        (self.root / "desktop/src-tauri/tauri.conf.json").write_text('{\n  "version": "1.0.0"\n}\n')
        self.bin = self.root / ".git/test-bin"
        self.run_command("git", "init", "-q", "-b", "main")
        self.run_command("git", "config", "user.name", "Release test")
        self.run_command("git", "config", "user.email", "release@example.invalid")
        self.run_command("git", "config", "commit.gpgsign", "false")
        self.run_command("git", "config", "core.hooksPath", str(self.root / ".git/test-hooks"))
        self.run_command("git", "add", ".")
        self.run_command("git", "commit", "-qm", "fixture")
        self.remote = self.root / ".git/test-remote.git"
        self.run_command("git", "init", "--bare", "-q", str(self.remote))
        self.run_command("git", "remote", "add", "origin", str(self.remote))
        self.run_command("git", "push", "-qu", "origin", "main")
        self.bin.mkdir()
        gh = self.bin / "gh"
        gh.write_text('''#!/usr/bin/env python3
import pathlib, sys
args = sys.argv[1:]
if args == ["auth", "status"]:
    sys.exit(0)
assert args[:4] == ["release", "create", "v1.1.0", "--title"], args
assert "--generate-notes" not in args, args
notes = pathlib.Path(args[args.index("--notes-file") + 1]).read_text()
pathlib.Path(".git/published-notes").write_text(notes)
''')
        gh.chmod(0o755)

    def run_command(self, *args, **kwargs):
        return subprocess.run(args, cwd=self.root, text=True, capture_output=True, check=True, **kwargs)

    def release(self, answer="y\n"):
        env = {**os.environ, "PATH": f"{self.bin}{os.pathsep}{os.environ['PATH']}"}
        return subprocess.run(["bash", "release.sh", "1.1.0"], cwd=self.root,
                              input=answer, text=True, capture_output=True, env=env)

    def test_release_commits_archived_notes_and_publishes_the_same_content(self):
        result = self.release()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('versionName = "1.1.0"', self.run_command("git", "show", "v1.1.0:android/app/build.gradle.kts").stdout)
        self.assertIn('"version": "1.1.0"', self.run_command("git", "show", "v1.1.0:desktop/src-tauri/tauri.conf.json").stdout)
        for locale in ("en", "zh-CN"):
            self.assertIn("## [Unreleased]\n\n## [1.1.0] - ", self.run_command("git", "show", f"v1.1.0:docs/changelog/{locale}.md").stdout)
        self.assertEqual((self.root / ".git/published-notes").read_text(), changelog.notes(self.root, "1.1.0"))
        self.assertEqual(self.run_command("git", "--git-dir", str(self.remote), "rev-parse", "refs/tags/v1.1.0").stdout,
                         self.run_command("git", "rev-parse", "HEAD").stdout)
        self.assertEqual(self.run_command("git", "status", "--porcelain").stdout, "")

    def test_rejected_confirmation_leaves_files_and_tags_untouched(self):
        result = self.release("n\n")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.run_command("git", "status", "--porcelain").stdout, "")
        self.assertEqual(self.run_command("git", "tag").stdout, "")
        self.assertFalse((self.root / ".git/published-notes").exists())

    def test_invalid_notes_stop_release_before_version_changes(self):
        self.write(EN, ZH.replace("- 重试失败的任务。", "- 一\n- 二"))
        self.run_command("git", "commit", "-qam", "invalid fixture")
        result = self.release()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.run_command("git", "status", "--porcelain").stdout, "")
        self.assertEqual(self.run_command("git", "tag").stdout, "")
        self.assertFalse((self.root / ".git/published-notes").exists())


if __name__ == "__main__":
    unittest.main()
