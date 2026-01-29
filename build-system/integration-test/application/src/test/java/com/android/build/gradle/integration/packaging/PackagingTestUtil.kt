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

package com.android.build.gradle.integration.packaging

import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GeneratesAar
import com.android.build.gradle.integration.common.fixture.project.GeneratesApk
import com.android.build.gradle.integration.common.output.AbstractAndroidArchiveSubject

internal data class StringWithContent(val name: String, val content: String)

internal fun String.withContent(content: String) = StringWithContent(this, content)

/**
 * Checks the DEBUG apk has the specific list of x86 jni libraries. The list must be exhaustive.
 *
 * @param itemList a list of items that must be present in the android archive. The list can either contain [String] to just validate
 *   presence, or [StringWithContent] to validate presence and content.
 */
internal fun GeneratesApk.checkApkJniLibs(vararg itemList: Any) {
  checkApkJniLibsForAbi("x86", *itemList)
}

/**
 * Checks the DEBUG apk has the specific list of jni libraries, for a given abi. The list must be exhaustive.
 *
 * @param abi the abi to check
 * @param itemList a list of items that must be present in the android archive. The list can either contain [String] to just validate
 *   presence, or [StringWithContent] to validate presence and content.
 */
internal fun GeneratesApk.checkApkJniLibsForAbi(abi: String, vararg itemList: Any) {
  assertApk(ApkSelector.DEBUG) { checkJniContent(abi, *itemList) }
}

/**
 * Checks the DEBUG test apk has the specific list of x86 jni libraries. The list must be exhaustive.
 *
 * @param itemList a list of items that must be present in the android archive. The list can either contain [String] to just validate
 *   presence, or [StringWithContent] to validate presence and content.
 */
internal fun GeneratesApk.checkTestApkJniLibs(vararg itemList: Any) {
  assertApk(ApkSelector.ANDROIDTEST_DEBUG) { checkJniContent("x86", *itemList) }
}

/**
 * Checks the DEBUG aar has the specific list of x86 jni libraries. The list must be exhaustive.
 *
 * @param itemList a list of items that must be present in the android archive. The list can either contain [String] to just validate
 *   presence, or [StringWithContent] to validate presence and content.
 */
internal fun GeneratesAar.checkAarJniLibs(vararg itemList: Any) {
  this.assertAar(AarSelector.DEBUG) { checkJniContent("x86", *itemList) }
}

/**
 * Checks the android archive has the specific list of jni libraries, for a given abi. The list must be exhaustive.
 *
 * @param this@checkAar the project
 * @param abi the abi to check
 * @param itemList a list of items that must be present in the android archive. The list can either contain [String] to just validate
 *   presence, or [StringWithContent] to validate presence and content.
 */
internal fun AbstractAndroidArchiveSubject<*, *>.checkJniContent(abi: String, vararg itemList: Any) {
  jniLibs().abi(abi) {
    if (itemList.isEmpty()) {
      isEmpty()
    } else {
      val itemsWithContent = itemList.mapNotNull { it as? StringWithContent }
      val itemNames =
        itemList.map {
          when (it) {
            is StringWithContent -> it.name
            is String -> it
            else -> throw RuntimeException("Unexpected type in itemList: ${it.javaClass}")
          }
        }

      // check the list
      containsExactly(itemNames)
      for (item in itemsWithContent) {
        bytesOf(item.name).isEqualTo(item.content.toByteArray())
      }
    }
  }
}
