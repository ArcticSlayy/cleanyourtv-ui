"""Execute unchanged production Images/Tmdb Java using real loopback HTTP/files.

Run: JAVA_HOME=<JDK 17-21> ANDROID_HOME=<SDK> python3 -m unittest discover \
  -s tests -p test_artwork_cache.py -v
ImageIO replaces Android decoding (no Android codec/pixel-format equivalence claim).
Only unused UI boundaries are faked. No dependencies or external requests.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "tests/artwork_cache"


class ArtworkCache(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        home = os.environ.get("JAVA_HOME")
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        if not home or not sdk:
            raise unittest.SkipTest("requires JAVA_HOME (JDK 17-21) and ANDROID_HOME")
        cls.java = str(Path(home) / "bin/java")
        javac = str(Path(home) / "bin/javac")
        jars = list((Path(sdk) / "platforms").glob("android-*/android.jar"))
        if not jars:
            raise AssertionError("Android platform jar missing")
        android = max(jars, key=lambda p: int(p.parent.name.split("-")[1]))
        work = ROOT / "work/artwork-cache-tests"
        work.mkdir(parents=True, exist_ok=True)
        cls.tmp = tempfile.TemporaryDirectory(dir=work)
        cls.addClassCleanup(cls.tmp.cleanup)
        cls.work = Path(cls.tmp.name)
        prod, runtime = cls.work / "production", cls.work / "runtime"
        prod.mkdir()
        runtime.mkdir()
        src = ROOT / "launcher-ui/app/src/io/cleanyourtv/ui"
        cls.command([javac, "-nowarn", "-encoding", "UTF-8", "-source", "8", "-target", "8",
                     "-cp", str(android), "-d", str(prod), str(src / "Images.java"),
                     str(src / "Tmdb.java"), *map(str, (FIXTURES / "compile").rglob("*.java"))])
        cls.cp = os.pathsep.join(map(str, (runtime, prod, android)))
        cls.command([javac, "-nowarn", "-encoding", "UTF-8", "-cp", cls.cp, "-d", str(runtime),
                     *map(str, (FIXTURES / "runtime").rglob("*.java")), str(FIXTURES / "ArtworkCacheTest.java")])

    @staticmethod
    def command(args):
        result = subprocess.run(args, cwd=ROOT, capture_output=True, text=True,
                                encoding="utf-8", timeout=60)
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)
        return result.stdout

    def behavior(self, name):
        output = self.command([self.java, "-Djava.awt.headless=true", "-Djava.security.manager=allow", "-ea", "-cp", self.cp,
                               "io.cleanyourtv.ui.ArtworkCacheTest", name, str(self.work)])
        self.assertIn("PASS " + name, output)

    def test_close_delimited_unknown_length_promotes(self):
        self.behavior("transport_close_valid")

    def test_chunked_unknown_length_promotes(self):
        self.behavior("transport_chunked_valid")

    def test_truncated_chunked_keeps_stale(self):
        self.behavior("transport_chunked_truncated")

    def test_partial206_range_keeps_stale_and_disconnects(self):
        self.behavior("partial206_range")

    def test_partial206_without_range_keeps_stale_and_disconnects(self):
        self.behavior("partial206_no_range")

    def test_partial200_range_keeps_stale_and_disconnects(self):
        self.behavior("partial200_range")

    def test_warm_init_during_store(self):
        self.behavior("init_store")

    def test_warm_init_during_prune(self):
        self.behavior("init_prune")

    def test_cleanup_denial_keeps_new_bitmap(self):
        self.behavior("cleanup_failure")

    def test_prune_cannot_race_promotion(self):
        self.behavior("prune_refresh")

    def test_fresh_cache_makes_no_request(self):
        self.behavior("fresh")

    def test_valid_refresh_replaces_stale(self):
        self.behavior("valid_refresh")

    def test_empty_success_keeps_stale(self):
        self.behavior("empty")

    def test_interrupted_download_keeps_stale(self):
        self.behavior("interrupted_truncated")

    def test_interrupted_decodable_prefix_keeps_stale(self):
        self.behavior("interrupted_decodable")

    def test_network_failure_uses_stale(self):
        self.behavior("stale_offline")

    def test_missing_cache_network_failure(self):
        self.behavior("missing_offline")

    def test_corrupt_cache_network_failure(self):
        self.behavior("corrupt_offline")

    def test_missing_cache_valid_download(self):
        self.behavior("missing_refresh")

    def test_corrupt_cache_valid_download(self):
        self.behavior("corrupt_refresh")

    def test_no_disk_still_returns_download(self):
        self.behavior("no_disk")

    def test_create_failure_keeps_stale_and_new_bitmap(self):
        self.behavior("create_failure")

    def test_malformed_success_keeps_stale(self):
        self.behavior("malformed")

    def test_promotion_failure_keeps_stale_and_new_bitmap(self):
        self.behavior("promotion_failure")

    def test_write_failure_keeps_stale_and_new_bitmap(self):
        self.behavior("write_failure")


if __name__ == "__main__":
    unittest.main()
