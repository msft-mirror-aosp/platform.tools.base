"""
Holds the protobuf version used by the studio-leakcanary library.
"""

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

# The protoc version that makes the library's proto classes. Apps get the
# protobuf-javalite version from bazel/maven/artifacts.bzl (in the POM). That
# version must not be lower than this one.
# This file is also loaded as a Java properties file from Gradle build files.
STUDIO_LEAKCANARY_PROTOBUF_VERSION = "3.25.5"
