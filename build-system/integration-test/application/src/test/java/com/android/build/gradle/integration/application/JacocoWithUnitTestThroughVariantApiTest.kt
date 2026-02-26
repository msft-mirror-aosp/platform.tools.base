/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.build.gradle.integration.application

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.HostTestBuilder
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.google.common.truth.Truth.assertThat
import kotlin.io.path.exists
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

class JacocoWithUnitTestThroughVariantApiTest {

  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication {
        android {
          namespace = "com.example.helloworld"
          compileSdk = GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION

          files.add(
            "src/main/java/com/example/helloworld/HelloWorld.java",
            // language=java
            """
            package com.example.helloworld;

            public class HelloWorld {
                public void foo() {}
            }
            """
              .trimIndent(),
          )

          files.add(
            "src/test/java/com/example/helloworld/HelloWorldTest.java",
            // language=java
            """
            package com.example.helloworld;

            import org.junit.Test;

            public class HelloWorldTest {
                @Test
                public void testFoo() {
                    new HelloWorld().foo();
                }
            }
            """
              .trimIndent(),
          )
        }
        dependencies { testImplementation("junit:junit:4.13.2") }
        pluginCallbacks += EnableUnitTestCoverageCallback::class.java
      }
    }

  class EnableUnitTestCoverageCallback : ApplicationComponentCallback {
    override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
      androidComponents.beforeVariants(androidComponents.selector().withBuildType("debug")) {
        it.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enableCodeCoverage = true
      }
    }
  }

  @Test
  fun `test expected report contents`() {
    val build = rule.build
    build.executor.run(":app:createDebugUnitTestCoverageReport")
    val generatedCoverageReport = build.androidApplication().buildDir.resolve("reports/coverage/test/debug/index.html")
    assertThat(generatedCoverageReport.exists()).isTrue()
  }
}
