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

package com.android.build.gradle.integration.dexing

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import org.junit.Rule
import org.junit.Test

class MultiDexWarningTest {

  @get:Rule var rule = GradleRule.from {}

  val warning =
    """
    The multidex library is included as a dependency, but it is not needed for apps
    with minSdk >= 21. Please remove dependency 'androidx.multidex:multidex:2.0.1' from ':app'.
    See https://developer.android.com/build/multidex for more information.
    """
      .trimIndent()

  @Test
  fun `test warning is present for unnecessary multidex`() {
    val build =
      rule.build {
        androidApplication {
          android.defaultConfig.minSdk = 21

          dependencies { implementation("androidx.multidex:multidex:2.0.1") }
        }
      }

    build.executor.run(":app:assembleDebug").assertOutputContains(warning)
  }

  @Test
  fun `test warning is not present for legacy minSdk `() {
    val build =
      rule.build {
        androidApplication {
          android.defaultConfig.minSdk = 20

          dependencies { implementation("androidx.multidex:multidex:2.0.1") }
        }
      }

    build.executor.run(":app:assembleDebug").assertOutputDoesNotContain(warning)
  }
}
