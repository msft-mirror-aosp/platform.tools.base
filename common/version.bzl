"""AGP and Android Studio base version definitions and validation."""

load(
    ":release_version.bzl",
    _AGP_COMPATIBLE_GRADLE_VERSIONS = "AGP_COMPATIBLE_GRADLE_VERSIONS",
    _GRADLE_LATEST_VERSION = "GRADLE_LATEST_VERSION",
    _IS_AGP_RELEASE_BRANCH = "IS_AGP_RELEASE_BRANCH",
    _LAST_STABLE_BUILD_VERSION = "LAST_STABLE_BUILD_VERSION",
    _RELEASE_BASE_VERSION = "BASE_VERSION",
    _RELEASE_BUILD_VERSION = "BUILD_VERSION",
    _RELEASE_COMMANDLINE_TOOLS_VERSION = "COMMANDLINE_TOOLS_VERSION",
)

def _version_key(version):
    parts = [int(p) for p in version.split("-")[0].split(".") if p.isdigit()]
    return parts + [0] * max(0, 3 - len(parts))

def _resolve_agp_compatible_gradle_versions(versions, dev_major_minor, gradle_latest_version):
    dev_entries = [k for k, v in versions.items() if v == "DEV"]
    if dev_entries != [dev_major_minor]:
        fail(
            "Expected AGP_COMPATIBLE_GRADLE_VERSIONS in tools/base/common/release_version.bzl " +
            "to map only current BUILD_VERSION major.minor (%s) to \"DEV\", but found \"DEV\" " % dev_major_minor +
            "entries: %s. When bumping BUILD_VERSION to a new minor release, pin the previous " % dev_entries +
            "major.minor to its minimum Gradle version and add \"%s\": \"DEV\"." % dev_major_minor,
        )
    resolved = {}
    prev_agp = None
    prev_gradle = None
    for k, v in versions.items():
        resolved_gradle = gradle_latest_version if v == "DEV" else v
        agp_key = _version_key(k)
        gradle_key = _version_key(resolved_gradle)
        if prev_agp != None and agp_key <= prev_agp[1]:
            fail(
                "AGP_COMPATIBLE_GRADLE_VERSIONS keys in tools/base/common/release_version.bzl " +
                "must be strictly increasing, but %s followed %s." % (k, prev_agp[0]),
            )
        if prev_gradle != None and gradle_key < prev_gradle[1]:
            fail(
                "AGP_COMPATIBLE_GRADLE_VERSIONS values in tools/base/common/release_version.bzl " +
                "must be monotonically non-decreasing, but %s (%s) is lower than %s (%s)." % (
                    k,
                    resolved_gradle,
                    prev_gradle[0],
                    prev_gradle[2],
                ),
            )
        prev_agp = (k, agp_key)
        prev_gradle = (k, gradle_key, resolved_gradle)
        resolved[k] = resolved_gradle
    return resolved

LAST_STABLE_BUILD_VERSION = _LAST_STABLE_BUILD_VERSION
IS_AGP_RELEASE_BRANCH = _IS_AGP_RELEASE_BRANCH
GRADLE_LATEST_VERSION = _GRADLE_LATEST_VERSION

DEV_AGP_MAJOR_MINOR = ".".join(_RELEASE_BUILD_VERSION.split("-")[0].split(".")[:2])
RESOLVED_AGP_COMPATIBLE_GRADLE_VERSIONS = _resolve_agp_compatible_gradle_versions(
    _AGP_COMPATIBLE_GRADLE_VERSIONS,
    DEV_AGP_MAJOR_MINOR,
    _GRADLE_LATEST_VERSION,
)

DEV_BASE_VERSION = _RELEASE_BASE_VERSION.split("-")[0] + "-dev"
RELEASE_BASE_VERSION = _RELEASE_BASE_VERSION if _IS_AGP_RELEASE_BRANCH == "true" else DEV_BASE_VERSION
BASE_VERSION = select({
    "//tools/base/bazel:release": RELEASE_BASE_VERSION,
    "//conditions:default": DEV_BASE_VERSION,
})

DEV_BUILD_VERSION = _RELEASE_BUILD_VERSION.split("-")[0] + "-dev"
RELEASE_BUILD_VERSION = _RELEASE_BUILD_VERSION if _IS_AGP_RELEASE_BRANCH == "true" else DEV_BUILD_VERSION
BUILD_VERSION = select({
    "//tools/base/bazel:release": RELEASE_BUILD_VERSION,
    "//conditions:default": DEV_BUILD_VERSION,
})

DEV_COMMANDLINE_TOOLS_VERSION = _RELEASE_COMMANDLINE_TOOLS_VERSION.split("-")[0] + "-dev"
RELEASE_COMMANDLINE_TOOLS_VERSION = _RELEASE_COMMANDLINE_TOOLS_VERSION if _IS_AGP_RELEASE_BRANCH == "true" else DEV_COMMANDLINE_TOOLS_VERSION
COMMANDLINE_TOOLS_VERSION = select({
    "//tools/base/bazel:release": RELEASE_COMMANDLINE_TOOLS_VERSION,
    "//conditions:default": DEV_COMMANDLINE_TOOLS_VERSION,
})
