"""Exercise cold Maven Wrapper downloads offline, including integrity failures."""

import hashlib
import io
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[1]


class WrapperDownloadTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="fixflow-wrapper-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bin = self.root / "tools"
        self.bin.mkdir()
        for name in ("uname", "dirname", "basename", "tr", "mktemp", "mkdir", "cp",
                     "rm", "mv", "sha256sum", "tar"):
            self.bin.joinpath(name).symlink_to(shutil.which(name))
        self.bin.joinpath("curl").write_text("""#!/bin/sh
while [ "$#" -gt 0 ]; do
  case "$1" in
    -o) output=$2; shift 2 ;;
    https://*) url=$1; shift ;;
    *) shift ;;
  esac
done
cp "$FIXFLOW_WRAPPER_ARCHIVES/${url##*/}" "$output"
""")
        self.bin.joinpath("curl").chmod(0o755)
        name = "apache-maven-test"
        self.zip = self.root / (name + "-bin.zip")
        self.tar = self.root / (name + "-bin.tar.gz")
        executable = b"#!/bin/sh\nprintf 'VERIFIED_MAVEN_STARTED\\n'\n"
        entry = name + "/bin/mvn"
        with zipfile.ZipFile(self.zip, "w") as archive:
            info = zipfile.ZipInfo(entry)
            info.external_attr = 0o100755 << 16
            archive.writestr(info, executable)
        with tarfile.open(self.tar, "w:gz") as archive:
            info = tarfile.TarInfo(entry)
            info.size, info.mode = len(executable), 0o755
            archive.addfile(info, io.BytesIO(executable))
        settings = self.root / ".mvn/wrapper/maven-wrapper.properties"
        settings.parent.mkdir(parents=True)
        settings.write_text(
            "distributionUrl=https://example.invalid/" + self.zip.name + "\n"
            "distributionSha256Sum=" + hashlib.sha256(self.zip.read_bytes()).hexdigest() + "\n"
        )
        shutil.copyfile(ROOT / "mvnw", self.root / "mvnw")
        self.env = dict(os.environ, PATH=str(self.bin),
                        MAVEN_USER_HOME=str(self.root / "fresh-cache"),
                        FIXFLOW_WRAPPER_ARCHIVES=str(self.root))
        for variable in ("MVNW_REPOURL", "MVNW_USERNAME", "MVNW_PASSWORD", "MVNW_VERBOSE"):
            self.env.pop(variable, None)

    def run_wrapper(self):
        return subprocess.run(["/bin/sh", "./mvnw", "--version"], cwd=self.root,
                              env=self.env, text=True, capture_output=True, timeout=20)

    def test_without_unzip_tar_download_is_rejected_by_zip_checksum(self):
        result = self.run_wrapper()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Failed to validate Maven distribution SHA-256", result.stderr)
        self.assertNotIn("VERIFIED_MAVEN_STARTED", result.stdout)

    def test_with_unzip_zip_is_verified_and_executed_from_empty_cache(self):
        self.bin.joinpath("unzip").symlink_to(shutil.which("unzip"))
        result = self.run_wrapper()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("VERIFIED_MAVEN_STARTED", result.stdout)

    def test_corrupted_zip_is_rejected_before_extraction_or_execution(self):
        self.bin.joinpath("unzip").symlink_to(shutil.which("unzip"))
        self.zip.write_bytes(b"corrupted download")
        result = self.run_wrapper()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Failed to validate Maven distribution SHA-256", result.stderr)
        self.assertNotIn("VERIFIED_MAVEN_STARTED", result.stdout)
        self.assertFalse(any(self.root.joinpath("fresh-cache").rglob("mvn")))


if __name__ == "__main__":
    unittest.main(verbosity=2)
