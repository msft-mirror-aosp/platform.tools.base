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

package com.android.build.gradle.integration.library

import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.prebuilts.BasicBuilds.Companion.HELLO_WORLD_LIBRARY
import com.android.build.gradle.options.BooleanOption
import com.android.repository.Revision
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

/**
 * @param api - if test assumes stable api in use, this is passed.
 * @param codeName - if test assumes a preview of sdk is in use, this is passed.
 * @param minAgpVersion - given scenario specified by [api] and [codeName] this is expected minAgpVersion passed into aar metadata.
 */
@RunWith(value = Parameterized::class)
class MinAgpVersionAutoEncodingTest(val api: String?, val codeName: String?, val minAgpVersion: String) {

  private val apiRevision: Revision?
    get() = api?.let { Revision.parseRevision(it) }

  @get:Rule val rule = GradleRule.from(configAction = HELLO_WORLD_LIBRARY)

  companion object {
    @JvmStatic
    @Parameters(name = "api={0},codename={1},minAgpVersion={2}")
    fun parameters(): Collection<Array<String?>> =
      listOf(
        arrayOf("32", null, "1.0.0"),
        arrayOf("33", null, "7.2.0"),
        arrayOf("34", null, "8.1.1"),
        arrayOf("35", null, "8.6.0"),
        arrayOf("36", null, "8.9.1"),
        arrayOf("36.1", null, "8.13.0"),
        arrayOf(null, "Baklava", "8.13.0"),
      )
  }

  @Test
  fun test() {
    val api = apiRevision
    val preview = codeName
    require((api == null) != (preview == null)) { "Valid test scenario requires either API or Preview name as non-null." }

    val build =
      if (api != null) {
        rule.build {
          androidLibrary {
            android {
              defaultConfig {
                aarMetadata {
                  minCompileSdk { version = release(api.major) { api.minor.takeIf { it > 0 }.let { this.minorApiLevel = it } } }
                }
              }
            }
          }
        }
      } else {
        rule.build { androidLibrary { android { defaultConfig { compileSdk { version = preview(preview!!) } } } } }
      }

    build.executor.with(BooleanOption.AUTO_ENCODE_MINIMUM_AGP_VERSION_IN_AAR_METADATA, true).run(":lib:assembleDebug")

    build.androidLibrary().assertAar(AarSelector.DEBUG) { aarMetadata { this.minAgpVersion().isEqualTo(minAgpVersion) } }
  }
}
