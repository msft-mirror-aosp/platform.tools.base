/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.ui.inspector.deploy

/**
 * The ABIs the native agent is built for, see `android_cc_binary` in //tools/base/bazel:android.bzl. Shared by the tests of the core and of
 * the standalone CLI, which check that every ABI is packaged.
 */
val SUPPORTED_ABIS = listOf("x86", "x86_64", "armeabi-v7a", "arm64-v8a")
