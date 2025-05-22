load("@rules_java//java:defs.bzl", "java_library")
load("//tools/base/bazel:android.bzl", "dex_library")
load("//tools/base/bazel:kotlin.bzl", "kotlin_library")

kexter_pkg = "//tools/base/kexter:__pkg__"

kotlin_library(
    name = "main_jar",
    srcs = glob([
        "main/src/**/*.kt",
    ]),
    coverage_baseline_enabled = False,
    visibility = [],
)

dex_library(
    name = "main_dex",
    flags = ["--min-api 26"],
    jars = [
        ":main_jar",
    ],
    visibility = [kexter_pkg],
)

kotlin_library(
    name = "other_jar",
    srcs = glob([
        "other/src/**/*.kt",
    ]),
    coverage_baseline_enabled = False,
    visibility = [],
)

# 'other_dex' is used in `MultiDexTest` to test that
# nothing breaks when multiple dex files are parsed
# in a single application.
dex_library(
    name = "other_dex",
    flags = ["--min-api 26"],
    jars = [
        ":other_jar",
    ],
    visibility = [kexter_pkg],
)

# Parameter so R8/D8 generates Dex container (v41)
ENABLE_DEX_CONTAINER_FORMAT = "--wrapper_script_flag=--jvm_flag=-Dcom.android.tools.r8.dexContainerExperiment"

dex_library(
    name = "main_container_dex",
    flags = [
        ENABLE_DEX_CONTAINER_FORMAT,
        "--min-api 36",
    ],
    jars = [
        ":main_jar",
    ],
    visibility = [kexter_pkg],
)

java_library(
    name = "container_jar",
    srcs = glob([
        "container/src/**/*.java",
    ]),
    visibility = [],
)

dex_library(
    name = "container_dex",
    flags = [
        ENABLE_DEX_CONTAINER_FORMAT,
        "--min-api 36",
    ],
    jars = [
        ":container_jar",
    ],
    visibility = [kexter_pkg],
)
