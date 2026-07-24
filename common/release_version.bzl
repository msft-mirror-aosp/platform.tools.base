#buildifier: disable=module-docstring
BASE_VERSION = "32.4.0-alpha07"
BUILD_VERSION = "9.4.0-alpha07"
COMMANDLINE_TOOLS_VERSION = "23.0-alpha01"

# This is used to define
# - for Android Studio RC/Stable builds when IS_AGP_RELEASE_BRANCH
#   (see below) is false: the "latest known" version of AGP and
#   related artifacts;
# - for Android Studio Nightly builds, the version of AGP offered in
#   the New Project Wizard.
# See `AgpVersions` and `AgpReleaseBranchProvider` for implementation
# details.
LAST_STABLE_BUILD_VERSION = "9.3.1"

# This is to discriminate between release cycles with or without AGP.
# Should be "true" on studio-main, and "false" on stabilization
# branches except if we are going to release AGP from that branch.
IS_AGP_RELEASE_BRANCH = "true"
