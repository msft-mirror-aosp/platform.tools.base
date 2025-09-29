#buildifier: disable=module-docstring
BASE_VERSION = "32.0.0-alpha09"
BUILD_VERSION = "9.0.0-alpha09"
COMMANDLINE_TOOLS_VERSION = "21.0-alpha01"

# These are used for nightly releases
LAST_STABLE_BUILD_VERSION = "8.13.0"

# This is to discriminate between release cycles with or without AGP.
# Should be "true" on studio-main, and "false" on stabilization
# branches except if we are going to release AGP from that branch.
IS_AGP_RELEASE_BRANCH = "true"
