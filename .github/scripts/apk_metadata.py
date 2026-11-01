"""Parse aapt/aapt2 badging while rejecting missing or conflicting SDK fields."""
import re


def parse_badging(badging):
    packages = re.findall(
        r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",
        badging,
        re.M,
    )
    if len(packages) != 1:
        raise RuntimeError("APK has no unique readable package/version metadata")
    # build-tools 35 aapt2 prints minSdkVersion; older aapt prints sdkVersion.
    # Both describe the same manifest value. Multiple/conflicting fields fail.
    minimum = re.findall(r"^(?:minSdkVersion|sdkVersion):'(\d+)'$", badging, re.M)
    target = re.findall(r"^targetSdkVersion:'(\d+)'$", badging, re.M)
    if minimum != ["26"] or target != ["35"]:
        raise RuntimeError("APK SDK levels differ from the reviewed configuration")
    package, version_code, version_name = packages[0]
    return {"package": package, "versionCode": int(version_code), "versionName": version_name}
