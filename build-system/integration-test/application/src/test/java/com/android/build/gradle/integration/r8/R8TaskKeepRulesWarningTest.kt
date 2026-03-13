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

package com.android.build.gradle.integration.r8

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_APP_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_FEATURE_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_LIB_PATH
import com.android.build.gradle.options.BooleanOption
import java.io.File
import org.junit.Rule
import org.junit.Test

class R8TaskKeepRulesWarningTest {

  @get:Rule
  val rule =
    GradleRule.from {
      gradleProperties { add(BooleanOption.R8_GRADUAL_API, true) }
      androidApplication {
        android {
          defaultConfig.minSdk = 24
          buildTypes {
            named("release") {
              it.isMinifyEnabled = true
              it.optimization {
                enable = true
                packageScope.add("com.example.app.*")
              }
            }
          }
          dynamicFeatures.add(DEFAULT_FEATURE_PATH)
          dependencies { implementation(project(DEFAULT_LIB_PATH)) }
        }
      }
      androidLibrary { android { defaultConfig.minSdk = 24 } }
      androidFeature {
          android {
            defaultConfig.minSdk = 24
            dependencies { implementation(project(DEFAULT_APP_PATH)) }
          }
        }
        .files {
          add("src/main/java/com/example/app/HelloWorld.kt", "package com.example.app\nclass HelloWorld { fun method() {} }")
          add("src/main/java/com/example/other/Other.kt", "package com.example.other\nclass Other { fun method() {} }")
        }
    }

  @Test
  fun `test gradual R8 warning for pro extension`() {
    val build =
      rule.build {
        androidApplication {
          files {
            add("src/main/keepRules/rules.pro", "-keep class com.example.app.HelloWorld { *; }")
            add("src/main/keepRules/rules.pgcfg", "-keep class com.example.app.HelloWorld { *; }")
          }
        }
      }
    val result = build.executor.expectFailure().run(":app:minifyReleaseWithR8")
    result.assertErrorContains("Use .keep extensions for keepRules source folders. ")
    result.assertErrorContains("- src${File.separatorChar}main${File.separatorChar}keepRules has rules.pgcfg, rules.pro")
  }

  @Test
  fun `test R8 warning for libraries`() {
    val build =
      rule.build {
        androidLibrary {
          files {
            add("src/main/keepRules/rules.pro", "-keep class com.example.app.HelloWorld { *; }")
            add("src/main/keepRules/rules.pgcfg", "-keep class com.example.app.HelloWorld { *; }")
          }
        }
      }
    val result = build.executor.expectFailure().run(":app:minifyReleaseWithR8")
    result.assertErrorContains("Use .keep extensions for keepRules source folders. ")
    result.assertErrorContains("- src${File.separatorChar}main${File.separatorChar}keepRules has rules.pgcfg, rules.pro")
  }

  @Test
  fun `test R8 warning for dynamic features`() {
    val build =
      rule.build {
        androidFeature {
          files {
            add("src/main/keepRules/rules.pro", "-keep class com.example.app.HelloWorld { *; }")
            add("src/main/keepRules/rules.pgcfg", "-keep class com.example.app.HelloWorld { *; }")
          }
        }
      }
    val result = build.executor.expectFailure().run(":app:minifyReleaseWithR8")
    result.assertErrorContains("Use .keep extensions for keepRules source folders. ")
    result.assertErrorContains("- src${File.separatorChar}main${File.separatorChar}keepRules has rules.pgcfg, rules.pro")
  }
}
