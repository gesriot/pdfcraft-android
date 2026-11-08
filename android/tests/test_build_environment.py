"""Check child-process temp paths without installing Rust or the Android SDK."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ANDROID = Path(__file__).resolve().parents[1]


class BuildEnvironmentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="pdfcraft build test ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.build = self.root / "build"
        self.tools = self.root / "tools"
        self.tools.mkdir()
        self.env = os.environ.copy()
        self.env.update(
            PDFCRAFT_BUILD_ROOT=str(self.build),
            TMPDIR=str(self.root / "inherited-tmp"),
            PATH=str(self.tools) + os.pathsep + self.env["PATH"],
        )
        Path(self.env["TMPDIR"]).mkdir()

    def executable(self, path, body):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f"#!{sys.executable}\n" + body)
        path.chmod(0o755)
        return path

    def test_direct_cargo_uses_build_storage_for_temporary_files(self):
        self.executable(self.build / "cargo/bin/rustup", "pass\n")
        self.executable(
            self.build / "cargo/bin/cargo",
            "import json, os, pathlib, sys\n"
            "print(json.dumps([os.environ['TMPDIR'], "
            "pathlib.Path(os.environ['TMPDIR']).is_dir(), sys.argv[1:]]))\n",
        )
        result = subprocess.run(
            ["sh", str(ANDROID / "cargo-local.sh"), "--version"],
            env=self.env, capture_output=True, text=True, check=True,
        )
        self.assertEqual(
            json.loads(result.stdout),
            [str(self.build / "tmp"), True, ["+1.95.0", "--version"]],
        )

    def test_build_passes_writable_temp_directory_to_each_stage(self):
        project = self.root / "project"
        (project / "android").mkdir(parents=True)
        for name in ["build-local.sh", "clang-android.sh"]:
            shutil.copy2(ANDROID / name, project / "android" / name)
        noop = self.executable(self.tools / "noop", "pass\n")
        self.env.update(ANDROID_AAPT2=str(noop), ANDROID_AR=str(noop), ANDROID_STRIP=str(noop))
        self.executable(
            self.tools / "timeout",
            "import json, os, pathlib, sys\n"
            "root = pathlib.Path(os.environ['PDFCRAFT_BUILD_ROOT'])\n"
            "with (root / 'stages.jsonl').open('a') as log:\n"
            "    log.write(json.dumps([os.environ['TMPDIR'], "
            "pathlib.Path(os.environ['TMPDIR']).is_dir(), sys.argv[2:]]) + '\\n')\n"
            "if 'build' in sys.argv:\n"
            "    library = root / 'target-linux/aarch64-linux-android/release/libpdfcraft_android_bridge.so'\n"
            "    library.parent.mkdir(parents=True, exist_ok=True)\n"
            "    library.touch()\n",
        )
        for variant in ["debug", "release"]:
            with self.subTest(variant=variant):
                self.env["PDFCRAFT_BUILD_TYPE"] = variant
                log = self.build / "stages.jsonl"
                log.unlink(missing_ok=True)
                subprocess.run(
                    ["sh", str(project / "android/build-local.sh")],
                    env=self.env, capture_output=True, text=True, check=True,
                )
                stages = [json.loads(line) for line in log.read_text().splitlines()]
                self.assertEqual(len(stages), 3)
                for temp_dir, exists, _args in stages:
                    self.assertEqual(temp_dir, str(self.build / "tmp"))
                    self.assertTrue(exists)
                self.assertIn(f":app:assemble{variant.title()}", stages[-1][2])


if __name__ == "__main__":
    unittest.main()
