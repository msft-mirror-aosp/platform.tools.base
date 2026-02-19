/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.build.gradle.integration.publishing

import com.android.build.gradle.integration.common.fixture.model.normalizeVersionsOfCommonDependencies
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThat
import java.nio.file.Path
import kotlin.io.path.readText
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.junit.Rule
import org.junit.Test

/** Test expected publishing output when AGP is used in Kotlin MPP projects. */
class KotlinMultiplatformPublishingTest {

  @get:Rule
  val rule =
    GradleRule.from {
      androidKotlinMultiplatformLibrary(":lib") {
        applyPlugin(PluginType.MAVEN_PUBLISH)
        pluginCallbacks += Callback::class.java

        android {
          namespace = "com.example.lib"
          minSdk = 24
        }

        group = "com.example"
        version = "0.1.2"
      }
    }

  class Callback : GenericCallback {
    override fun handleProject(project: Project) {
      val publishing =
        project.extensions.findByType(PublishingExtension::class.java)
          ?: throw RuntimeException("Could not find extension of type PublishingExtension")

      publishing.apply {
        repositories {
          it.maven {
            it.url = project.uri(project.projectDir.resolve("build/testRepo"))
            it.name = "buildDir"
          }
        }
      }

      project.extensions.getByType(KotlinMultiplatformExtension::class.java)
    }
  }

  @Test
  fun testKotlinMultiplatform() {
    val build = rule.build
    val lib = build.kotlinMultiplatformLibrary(":lib")

    build.executor
      .withFailOnWarning(false) // b/455891987
      .run("publishAllPublicationsToBuildDirRepository")

    val mainModule = lib.buildDir.resolve("testRepo/com/example/lib/0.1.2/lib-0.1.2.module")
    val androidModule = lib.buildDir.resolve("testRepo/com/example/lib-android/0.1.2/lib-android-0.1.2.module")

    assertThat(normalizeModuleFile(mainModule)).isEqualTo(getExpectedFile("lib.module"))
    assertThat(normalizeModuleFile(androidModule)).isEqualTo(getExpectedFile("lib-android.module"))
  }

  private fun getExpectedFile(fileName: String): String {
    return KotlinMultiplatformPublishingTest::class.java.let { klass ->
      klass.getResourceAsStream("${klass.simpleName}/$fileName")!!.reader().use { it.readText().trim() }
    }
  }

  private fun normalizeModuleFile(path: Path): String {
    val original = path.readText().trim()
    return original
      .normalizeVersionsOfCommonDependencies()
      .replace(Regex("\"sha512\": \".*\""), "\"sha512\": \"{DIGEST}\"")
      .replace(Regex("\"sha256\": \".*\""), "\"sha256\": \"{DIGEST}\"")
      .replace(Regex("\"sha1\": \".*\""), "\"sha1\": \"{DIGEST}\"")
      .replace(Regex("\"md5\": \".*\""), "\"md5\": \"{DIGEST}\"")
      .replace(Regex("\"size\": .*,"), "\"size\": {SIZE},")
  }
}
