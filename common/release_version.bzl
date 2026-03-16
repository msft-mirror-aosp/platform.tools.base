#buildifier: disable=module-docstring
BASE_VERSION = "32.2.0-alpha04"
BUILD_VERSION = "9.2.0-alpha04"
COMMANDLINE_TOOLS_VERSION = "21.0-alpha01"

# These are used for nightly releases
LAST_STABLE_BUILD_VERSION = "9.1.0"

# This is to discriminate between release cycles with or without AGP.
# Should be "true" on studio-main, and "false" on stabilization
# branches except if we are going to release AGP from that branch.
IS_AGP_RELEASE_BRANCH = "true"
