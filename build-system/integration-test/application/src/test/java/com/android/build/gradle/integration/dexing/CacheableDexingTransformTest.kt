/*
 * Copyright (C) 2023 The Android Open Source Project
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
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CacheableDexingTransformTest {

  @get:Rule val buildCacheDir = TemporaryFolder()

  @get:Rule val rule1 = GradleRule.from(folderName = "projectCopy1") { createProject() }

  @get:Rule val rule2 = GradleRule.from(folderName = "projectCopy2") { createProject() }

  private fun GradleBuildDefinition.createProject() {
    androidApplication {
      android { defaultConfig.minSdk = 24 }
      dependencies { implementation(project(":lib")) }
    }
    androidLibrary {
      files.add(
        "src/main/java/com/example/lib/JavaClassWithNestedClass.java",
        // language=java
        """
        package com.example.lib;
        public class JavaClassWithNestedClass {
            public class NestedClass {
                // This line will be changed later
            }
        }
        """
          .trimIndent(),
      )
    }
  }

  @Test
  fun `Bug 266599585 - test incremental build after cache hit`() {
    val build1 =
      rule1.build {
        // this must be done here, otherwise the temporary folder has not been prepared
        settings { enableLocalCache(buildCacheDir.root.toPath()) }
      }
    build1.executor.withArgument("--build-cache").run(":app:mergeLibDexDebug").apply {
      assertTask(":app:mergeLibDexDebug").didWork()
      assertOutputContains("Running dexing transform non-incrementally")
    }

    // Building the same project from a different location should get a cache hit
    val build2 =
      rule2.build {
        // this must be done here, otherwise the temporary folder has not been prepared
        settings { enableLocalCache(buildCacheDir.root.toPath()) }
      }
    val result2 =
      build2.executor.withArgument("--build-cache").run(":app:mergeLibDexDebug").apply {
        assertTask(":app:mergeLibDexDebug").wasFromCache()
        assertOutputDoesNotContain("Running dexing transform")
      }

    // Make a change to a nested class (regression test for bug 266599585)
    build2
      .androidLibrary()
      .files
      .update("src/main/java/com/example/lib/JavaClassWithNestedClass.java")
      .searchAndReplace("// This line will be changed later", "public void newMethodInNestedClass() { }")

    // The next build after cache hit should be incremental
    build2.executor.withArgument("--build-cache").run(":app:mergeLibDexDebug").apply {
      assertTask(":app:mergeLibDexDebug").didWork()
      assertOutputContains("Running dexing transform incrementally")
    }
  }
}
