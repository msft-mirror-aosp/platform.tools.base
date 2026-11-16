#buildifier: disable=module-docstring
# Minimum Kotlin/KGP version supported by AGP. AGP compiles against and ships Kotlin libraries
# (kotlin-stdlib, kotlin-gradle-plugin, etc.) at this version via tools/base/bazel/maven/artifacts.bzl
# so AGP remains binary-compatible with projects on the minimum supported KGP.
MINIMUM_BUILT_IN_KOTLIN_VERSION = "2.2.10"

# Default Kotlin Gradle plugin version recommended/applied by AGP (published via Gradle 'prefer'
# constraint).
DEFAULT_BUILT_IN_KOTLIN_VERSION = "2.4.20"

# Kotlin Gradle plugin version used by Gradle to compile and build AGP itself (decoupled per
# b/574329887).
KOTLIN_VERSION_FOR_AGP_BUILD = "2.4.20"
