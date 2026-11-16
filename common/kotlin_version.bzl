#buildifier: disable=module-docstring
# Kotlin/KGP version that tools/base & AGP compile against (via tools/base/bazel/maven/artifacts.bzl).
# This is also AGP's minimum supported KGP version (reject("(,2.2.10)")), so bumping it is a
# user-facing minimum bump.
KOTLIN_VERSION_AGP_COMPILES_AGAINST = "2.2.10"

# Default Kotlin Gradle plugin version recommended/applied by AGP (published via Gradle 'prefer'
# constraint).
KOTLIN_VERSION_AGP_DEFAULTS_TO = "2.4.20"

# Kotlin Gradle plugin version used by Gradle (tools/buildSrc) to compile and build AGP itself
# (decoupled per b/574329887). Bazel uses its own kotlinc prebuilt.
KGP_VERSION_USED_TO_COMPILE_AGP = "2.4.20"
