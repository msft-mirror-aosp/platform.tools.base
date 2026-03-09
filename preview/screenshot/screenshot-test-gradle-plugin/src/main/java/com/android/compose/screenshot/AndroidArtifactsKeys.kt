/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.compose.screenshot

/**
 * Constants for internal AGP classes and artifact types accessed via reflection.
 *
 * This class was added to refactor hardcoded string literals into constants, making artifact queries strictly bounded and addressing code
 * review feedback.
 *
 * NOTE: These are internal values of AGP and should be removed once Compose Preview Screenshot testing is fully integrated with the
 * TestSuite API.
 */
object AndroidArtifactsKeys {
  const val R_CLASS_JAR = "r-class-jar"
  const val ANDROID_RES = "android-res"
  const val ANDROID_CLASSES = "android-classes"
}
