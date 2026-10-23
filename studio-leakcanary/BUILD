# Copyright (C) 2026 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

load("@bazel_skylib//rules:write_file.bzl", "write_file")
load("@rules_android//rules:rules.bzl", "android_library")
load("//tools/base/bazel:maven.bzl", "custom_maven_library", "maven_repository")
load("//tools/base/bazel:robolectric.bzl", "robolectric_test")
load(":release_version.bzl", "STUDIO_LEAKCANARY_VERSION_DEV", "STUDIO_LEAKCANARY_VERSION_RELEASE")

# Switch between dev and release versions based on the build config
STUDIO_LEAKCANARY_VERSION = select({
    "//tools/base/bazel:release": STUDIO_LEAKCANARY_VERSION_RELEASE,
    "//conditions:default": STUDIO_LEAKCANARY_VERSION_DEV,
})

# Makes LibraryVersion.java. Always the release version, also in dev builds: Gradle version
# order puts "1.1.0-dev" below "1.1.0", so a dev string would fail Studio's minimum check.
write_file(
    name = "library_version_java",
    out = "LibraryVersion.java",
    content = [
        "package com.android.tools.studio.leakcanary;",
        "",
        "/** Made by the build from release_version.bzl. Do not edit. */",
        "final class LibraryVersion {",
        "    static final String VERSION = \"%s\";" % STUDIO_LEAKCANARY_VERSION_RELEASE,
        "",
        "    private LibraryVersion() {}",
        "}",
        "",
    ],
)

# Compiles the Android code and resources into an AAR
android_library(
    name = "studio-leakcanary_lib",
    srcs = glob([
        "src/main/java/com/android/tools/studio/leakcanary/*.java",
    ]) + [
        # Java classes made from leakcanary_data.proto. They are packed into the AAR.
        "//tools/base/transport/proto:leakcanary_data_java_lite_srcjar",
        # The library version, made from release_version.bzl.
        ":library_version_java",
    ],
    custom_package = "com.android.tools.studio.leakcanary",
    javacopts = [
        "-source",
        "8",
        "-target",
        "8",
        # Skip Error Prone checks for the generated proto code only
        "-XepExcludedPaths:.*/com/android/tools/profiler/proto/LeakCanary\\.java",
    ],
    manifest = "src/main/AndroidManifest.xml",
    # Consumer R8/ProGuard rules, packaged into the AAR as proguard.txt.
    proguard_specs = ["proguard-rules.pro"],
    visibility = ["//visibility:public"],
    deps = [
        # Dependency on the STUB, so it goes into the POM
        "//tools/base/studio-leakcanary/lib:com.squareup.leakcanary.leakcanary-android",
        # Needed by the generated proto classes.
        "@maven//:com.google.protobuf.protobuf-javalite",
    ],
)

# Defines the Maven artifact details and generates the POM
custom_maven_library(
    name = "studio-leakcanary",
    coordinates = "com.android.tools.studio.leakcanary:leakcanary",
    description = "Android Studio Profiler integration for LeakCanary",
    files = select({
        "//tools/base/bazel:release": {
            ":studio-leakcanary_lib.aar": "leakcanary-" + STUDIO_LEAKCANARY_VERSION_RELEASE + ".aar",
        },
        "//conditions:default": {
            ":studio-leakcanary_lib.aar": "leakcanary-" + STUDIO_LEAKCANARY_VERSION_DEV + ".aar",
        },
    }),
    notice = "LICENSE",
    template_pom = "pom.template",
    version = STUDIO_LEAKCANARY_VERSION,
    visibility = ["//visibility:public"],
    deps = [
        "//tools/base/studio-leakcanary/lib:com.squareup.leakcanary.leakcanary-android",
        # Listed in the POM, so apps download it automatically.
        "@maven//:com.google.protobuf.protobuf-javalite",
    ],
)

# Creates the ZIP file containing the AAR and POM for GMaven
maven_repository(
    name = "studio_leakcanary_gmaven_repo",
    artifacts = [
        ":studio-leakcanary",
    ],
    include_transitive_deps = False,
    visibility = ["//visibility:public"],
)

# Test target for the studio-leakcanary library
robolectric_test(
    name = "studio-leakcanary_tests",
    srcs = glob(["testSrc/main/java/**/*.java"]),
    custom_package = "com.android.tools.studio.leakcanary",
    data = ["proguard-rules.pro"],
    test_class = "com.android.tools.studio.leakcanary.StudioLeakCanaryTestSuite",
    deps = [
        ":studio-leakcanary_lib",
        "//prebuilts/studio/sdk:platforms/latest_jar",
        "//tools/base/fakeandroid",
        "//tools/base/testutils:tools.testutils",
        "@maven//:com.google.protobuf.protobuf-javalite",
        "@maven//:com.google.truth.truth",
        "@maven//:junit.junit",
        "@maven//:org.mockito.mockito-core",
        "@maven//:org.robolectric.robolectric",
    ],
)
