/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.idea.wizard.template.impl

import com.android.ide.common.repository.AgpVersion
import com.android.sdklib.AndroidMajorVersion
import com.android.sdklib.AndroidVersion

// TODO(b/419624430): This is duplicated from
// android-npw/src/com/android/tools/idea/npw/module/recipes/sharedMacros.kt,
// because the NPW and template-impl modules don't have a common module for shared code, but ideally
// both should be replaced by Gradle project model.

fun compileSdk(androidVersion: AndroidVersion, agpVersion: AgpVersion): String {
  val isNewAGP = agpVersion.compareIgnoringQualifiers("7.0.0") >= 0
  // TODO(b/409390818): Include minor version when AGP supports it
  val apiLevelMajor = androidVersion.androidApiLevel.majorVersion

  return when {
    isNewAGP && androidVersion.isPreview -> "compileSdkPreview \"${androidVersion.apiStringWithExtension}\""
    isNewAGP -> "compileSdk $apiLevelMajor"
    androidVersion.isPreview -> "compileSdkVersion \"${androidVersion.apiStringWithExtension}\""
    else -> "compileSdkVersion $apiLevelMajor"
  }
}

fun minSdk(androidVersion: AndroidMajorVersion, agpVersion: AgpVersion): String =
  toAndroidFieldVersion("minSdk", androidVersion, agpVersion)

fun targetSdk(androidVersion: AndroidMajorVersion, agpVersion: AgpVersion): String =
  toAndroidFieldVersion("targetSdk", androidVersion, agpVersion)

fun toAndroidFieldVersion(fieldNameBase: String, androidVersion: AndroidMajorVersion, agpVersion: AgpVersion): String {
  val isNewAGP = agpVersion.compareIgnoringQualifiers("7.0.0") >= 0
  val fieldName =
    when {
      isNewAGP && androidVersion.isPreview -> "${fieldNameBase}Preview"
      isNewAGP -> fieldNameBase
      else -> "${fieldNameBase}Version"
    }
  val fieldValue = if (androidVersion.isPreview) "\"${androidVersion.apiString}\"" else androidVersion.apiString
  return "$fieldName $fieldValue"
}
