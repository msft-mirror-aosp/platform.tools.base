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

package com.android.build.gradle.integration.dependencies

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * Regression test for b/549199023.
 *
 * Checks that sdk-common includes the Bouncy Castle BOM in its <dependencyManagement>, ensuring all transitive org.bouncycastle components
 * resolve in lockstep across subprojects.
 */
class BouncyCastleBomAlignmentTest {

  @get:Rule
  val rule = GradleRule.from {
    androidApplication {
      android {
        enableKotlin = false
      }
      dependencies {
        implementation("com.android.tools:sdk-common:+")
        implementation(project(":lib"))
      }
    }
    androidLibrary {
      dependencies {
        implementation("org.bouncycastle:bcutil-jdk18on")
      }
    }
  }

  @Test
  fun testBouncyCastleBomAlignsTransitiveDependencies() {
    val result = rule.build.executor.run(":app:dependencies", "--configuration", "debugRuntimeClasspath")

    var bcutilVersion: String? = null
    var bcpkixVersion: String? = null
    var bcprovVersion: String? = null

    // Matches resolved dependency lines in both "group:name:version" and "group:name -> version" formats,
    // capturing the resolved version string to verify lockstep version alignment without hardcoding specific versions.
    val bcutilRegex = Regex("""org\.bouncycastle:bcutil-jdk18on.*?(?:->\s*|:)(\S+)""")
    val bcpkixRegex = Regex("""org\.bouncycastle:bcpkix-jdk18on.*?(?:->\s*|:)(\S+)""")
    val bcprovRegex = Regex("""org\.bouncycastle:bcprov-jdk18on.*?(?:->\s*|:)(\S+)""")

    result.processOutput { line ->
      bcutilRegex.find(line)?.let { bcutilVersion = it.groupValues[1] }
      bcpkixRegex.find(line)?.let { bcpkixVersion = it.groupValues[1] }
      bcprovRegex.find(line)?.let { bcprovVersion = it.groupValues[1] }
    }

    assertThat(bcutilVersion).isNotNull()
    assertThat(bcpkixVersion).isNotNull()
    assertThat(bcprovVersion).isNotNull()
    assertThat(bcutilVersion).isEqualTo(bcpkixVersion)
    assertThat(bcutilVersion).isEqualTo(bcprovVersion)
  }
}
