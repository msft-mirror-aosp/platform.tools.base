load("//tools/base/bazel:kotlin.bzl", "kotlin_library")
load("//tools/base/bazel:merge_archives.bzl", "merge_jars")
load("//tools/base/bazel:utils.bzl", "replace_manifest")

# AndroidX lint check from
# https://cs.android.com/search?q=lintPublish%20file:compose%2F%20file:build.gradle&ss=androidx%2Fplatform%2Fframeworks%2Fsupport&start=1
#
# and published at https://maven.google.com/web/index.html?q=-lint

COMPOSE_LINT = [
    "@maven//:androidx.compose.animation.animation-core-lint",
    "@maven//:androidx.compose.animation.animation-lint",
    "@maven//:androidx.compose.foundation.foundation-lint",
    "@maven//:androidx.compose.material.material-lint",
    "@maven//:androidx.compose.material3.material3-lint",
    "@maven//:androidx.compose.runtime.runtime-lint",
    "@maven//:androidx.compose.runtime.runtime-saveable-lint",
    "@maven//:androidx.compose.ui.ui-graphics-lint",
    "@maven//:androidx.compose.ui.ui-lint",
    "@maven//:androidx.compose.ui.ui-test-manifest-lint",
    "@maven//:androidx.compose.ui.ui-text-lint",
]

java_binary(
    name = "print_compose_lint_issues",
    main_class = "com.android.tools.lint.checks.androidx.ComposeDesktopIssueRegistryKt",
    visibility = ["//visibility:public"],
    runtime_deps = [":compose-desktop-registry"],
)

kotlin_library(
    name = "compose-desktop-registry",
    srcs = ["ComposeDesktopIssueRegistry.kt"],
    lint_enabled = False,
    deps = COMPOSE_LINT + ["//tools/base/lint:tools.lint-api"],
)

merge_jars(
    name = "androidx-checks-merged",
    out = "androidx-checks-merged.jar",
    allow_duplicates = True,
    jars = COMPOSE_LINT + [":compose-desktop-registry"],
)

replace_manifest(
    name = "compose-desktop-checks",
    manifest = "MANIFEST.MF",
    original_jar = ":androidx-checks-merged",
    visibility = ["//visibility:public"],
)
