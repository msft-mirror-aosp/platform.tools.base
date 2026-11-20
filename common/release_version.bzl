#buildifier: disable=module-docstring
BASE_VERSION = "32.6.0-alpha06"
BUILD_VERSION = "9.6.0-alpha06"
COMMANDLINE_TOOLS_VERSION = "25.0-alpha01"

# This is used to define
# - for Android Studio RC/Stable builds when IS_AGP_RELEASE_BRANCH
#   (see below) is false: the "latest known" version of AGP and
#   related artifacts;
# - for Android Studio Nightly builds, the version of AGP offered in
#   the New Project Wizard.
# See `AgpVersions` and `AgpReleaseBranchProvider` for implementation
# details.
LAST_STABLE_BUILD_VERSION = "9.5.2"

# The default/minimum Gradle version supported by the in-development
# AGP version (BUILD_VERSION). Keep in sync with
# SdkConstants.GRADLE_LATEST_VERSION and
# tools/base/build-system/supported-versions.properties.
GRADLE_LATEST_VERSION = "9.8.2"

# Mapping of AGP major.minor versions to their minimum required
# Gradle version, in strictly increasing AGP order and monotonically
# non-decreasing Gradle version order.
# - Entries earlier than 7.0 are not needed because Android Studio
#   supports AGP 7.0.0 and later; entries below
#   SdkConstants.GRADLE_PLUGIN_MINIMUM_FORCED_UPGRADE_VERSION can be
#   pruned as Studio drops older AGP versions.
# - Exactly one entry must map to "DEV", matching the major.minor of
#   BUILD_VERSION above. When bumping BUILD_VERSION to a new minor
#   release, pin the previous major.minor to its minimum Gradle
#   version and add "<new_major.minor>": "DEV".
# - Note: //tools/gradle-recipes/version_mappings.txt must also list the
#   new major.minor (checked by //tools/gradle-recipes:convert_tool_tests);
#   it drives recipe tests and the GitHub recipes publishing workflow.
AGP_COMPATIBLE_GRADLE_VERSIONS = {
    "7.0": "7.0.2",
    "7.1": "7.2",
    "7.2": "7.3.3",
    "7.3": "7.4",
    "7.4": "7.5",
    "8.0": "8.0",
    "8.1": "8.0",
    "8.2": "8.2",
    "8.3": "8.4",
    "8.4": "8.6",
    "8.5": "8.7",
    "8.6": "8.7",
    "8.7": "8.9",
    "8.8": "8.10.2",
    "8.9": "8.11.1",
    "8.10": "8.11.1",
    "8.11": "8.13",
    "8.12": "8.13",
    "8.13": "8.13",
    "9.0": "9.1.0",
    "9.1": "9.3.1",
    "9.2": "9.4.1",
    "9.3": "9.5.0",
    "9.4": "9.6.0",
    "9.5": "9.6.0",
    "9.6": "DEV",
}

# This is to discriminate between release cycles with or without AGP.
# Should be "true" on studio-main, and "false" on stabilization
# branches except if we are going to release AGP from that branch.
IS_AGP_RELEASE_BRANCH = "true"
