@rem This script performs a 'bazel build' to act as a canary check
@rem for Android Build Launchcontrol releases. AB Builders running newer
@rem releases run this script to verify our CI integration has no regressions.
@rem We 'build' instead of 'test' to reduce our load on RBE. If building works,
@rem we can be highly confident testing works since all tests are executed
@rem remotely.
%~dp0\ci studio-win-canary