"""Regression and failure checks for the release SDK gate; no Android tools needed."""
from pathlib import Path
import unittest

from apk_metadata import parse_badging


class ApkMetadataTest(unittest.TestCase):
    # Actual build-tools 35.0.0 output from master CI 41311292752,
    # Android job 124862054474. Only public package/version/SDK lines retained.
    fixture = (Path(__file__).parent / "fixtures/build-tools-35-badging.txt").read_text()

    def test_actual_build_tools_35_output(self):
        self.assertEqual(parse_badging(self.fixture), {
            "package": "top.alanvampuke.dininglog.debug",
            "versionCode": 5,
            "versionName": "0.5.0",
        })

    def test_legacy_sdk_field(self):
        self.assertEqual(parse_badging(self.fixture.replace("minSdkVersion", "sdkVersion")),
                         parse_badging(self.fixture))

    def test_production_identity_remains_distinct_from_debug(self):
        production = self.fixture.replace("dininglog.debug", "dininglog")
        self.assertEqual(parse_badging(production)["package"], "top.alanvampuke.dininglog")
        self.assertNotEqual(parse_badging(production), parse_badging(self.fixture))

    def test_wrong_minimum_or_target_sdk_is_rejected(self):
        for field, expected, wrong in [("minSdkVersion", "26", "25"),
                                       ("minSdkVersion", "26", "27"),
                                       ("targetSdkVersion", "35", "34"),
                                       ("targetSdkVersion", "35", "36")]:
            with self.subTest(field=field, value=wrong), self.assertRaises(RuntimeError):
                parse_badging(self.fixture.replace(f"{field}:'{expected}'", f"{field}:'{wrong}'"))

    def test_missing_or_unknown_sdk_fields_are_rejected(self):
        for line in ["minSdkVersion:'26'", "targetSdkVersion:'35'"]:
            with self.subTest(line=line), self.assertRaises(RuntimeError):
                parse_badging(self.fixture.replace(line, ""))
        with self.assertRaises(RuntimeError):
            parse_badging(self.fixture.replace("minSdkVersion", "minimumSdk"))

    def test_duplicate_or_conflicting_sdk_fields_are_rejected(self):
        for extra in ["sdkVersion:'26'", "sdkVersion:'25'", "minSdkVersion:'26'",
                      "targetSdkVersion:'35'", "targetSdkVersion:'34'"]:
            with self.subTest(extra=extra), self.assertRaises(RuntimeError):
                parse_badging(self.fixture + extra + "\n")

    def test_missing_or_duplicate_package_version_is_rejected(self):
        package_line = self.fixture.splitlines()[0]
        for invalid in [self.fixture.replace(package_line, ""),
                        self.fixture + package_line + "\n",
                        self.fixture.replace("versionCode='5'", "versionCode='bad'")]:
            with self.subTest(invalid=invalid), self.assertRaises(RuntimeError):
                parse_badging(invalid)


if __name__ == "__main__":
    unittest.main()
