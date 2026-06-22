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

package com.android.build.gradle.integration.connected.application

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.connected.utils.getEmulator
import com.android.build.gradle.integration.utp.applyAndroidTestConfiguration
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Connected test verifying that running connectedAndroidTest on the base app module succeeds when a dynamic feature module defines a
 * ContentProvider (b/257765153).
 */
@RunWith(Parameterized::class)
class DynamicFeatureContentProviderConnectedTest(val runWithBuiltInPlatform: Boolean) {

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "runWithBuiltInPlatform={0}")
    fun parameters(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
  }

  @Rule @JvmField val EMULATOR: ExternalResource = getEmulator()

  @get:Rule
  val rule =
    GradleRule.configure().from {
      applyAndroidTestConfiguration(runWithBuiltInPlatform = runWithBuiltInPlatform)
      androidApplication { android { dynamicFeatures.add(":feature") } }
      androidFeature {
        files {
          update("src/main/AndroidManifest.xml")
            .searchAndReplace("<dist:on-demand />", "<dist:install-time />")
            .searchAndReplace(
              "</manifest>",
              """
                  <application>
                      <provider
                          android:name="com.example.android.kotlin.feature.ContentProviderInDynamicFeature"
                          android:authorities="com.example.android.kotlin.feature.provider" />
                  </application>
              </manifest>
              """
                .trimIndent(),
            )
          add(
            "src/main/java/com/example/android/kotlin/feature/ContentProviderInDynamicFeature.kt",
            """
            package com.example.android.kotlin.feature

            import android.content.ContentProvider
            import android.content.ContentValues
            import android.database.Cursor
            import android.net.Uri

            class ContentProviderInDynamicFeature : ContentProvider() {
                override fun onCreate(): Boolean = true
                override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
                override fun getType(uri: Uri): String? = null
                override fun insert(uri: Uri, values: ContentValues?): Uri? = null
                override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
                override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
            }
            """
              .trimIndent(),
          )
        }
      }
    }

  @Test
  fun testConnectedAndroidTestSucceedsWithContentProviderInDynamicFeature() {
    rule.build.executor.run(":app:connectedAndroidTest")
  }
}
