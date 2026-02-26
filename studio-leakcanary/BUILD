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

load("@rules_android//rules:rules.bzl", "android_library")
load("//tools/base/bazel:maven.bzl", "custom_maven_library", "maven_repository")
load(":release_version.bzl", "STUDIO_LEAKCANARY_VERSION_DEV", "STUDIO_LEAKCANARY_VERSION_RELEASE")

# Switch between dev and release versions based on the build config
STUDIO_LEAKCANARY_VERSION = select({
    "//tools/base/bazel:release": STUDIO_LEAKCANARY_VERSION_RELEASE,
    "//conditions:default": STUDIO_LEAKCANARY_VERSION_DEV,
})

# Compiles the Android code and resources into an AAR
android_library(
    name = "studio-leakcanary_lib",
    srcs = glob([
        "src/main/java/com/android/tools/studio/leakcanary/*.java",
    ]),
    custom_package = "com.android.tools.studio.leakcanary",
    javacopts = [
        "-source",
        "8",
        "-target",
        "8",
    ],
    manifest = "src/main/AndroidManifest.xml",
    visibility = ["//visibility:public"],
    deps = [
        # Dependency on the STUB, so it goes into the POM
        "//tools/base/studio-leakcanary/lib:com.squareup.leakcanary.leakcanary-android",
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
