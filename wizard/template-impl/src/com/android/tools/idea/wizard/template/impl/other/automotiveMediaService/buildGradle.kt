/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.tools.idea.wizard.template.impl.other.automotiveMediaService

import com.android.ide.common.repository.AgpVersion
import com.android.sdklib.AndroidMajorVersion
import com.android.sdklib.AndroidVersion
import com.android.tools.idea.wizard.template.common.AGP_VERSION_WITH_BUILT_IN_KOTLIN
import com.android.tools.idea.wizard.template.getMaterialComponentName
import com.android.tools.idea.wizard.template.impl.compileSdk
import com.android.tools.idea.wizard.template.impl.minSdk
import com.android.tools.idea.wizard.template.impl.targetSdk
import com.android.tools.idea.wizard.template.renderIf

fun buildGradle(
  agpVersion: AgpVersion,
  packageName: String,
  buildApi: AndroidVersion,
  generateKotlin: Boolean,
  minApi: AndroidMajorVersion,
  targetApi: AndroidMajorVersion,
  useAndroidX: Boolean,
): String {
  return """
plugins {
    id 'com.android.library'
    ${renderIf(generateKotlin && agpVersion < AGP_VERSION_WITH_BUILT_IN_KOTLIN) {"    id 'org.jetbrains.kotlin.android'"}}
}
android {
    namespace '$packageName'
    ${compileSdk(buildApi, agpVersion)}

    defaultConfig {
        ${minSdk(minApi, agpVersion)}
        ${targetSdk(targetApi, agpVersion)}

        testInstrumentationRunner "${getMaterialComponentName("android.support.test.runner.AndroidJUnitRunner", useAndroidX)}"
    }
}

"""
}
