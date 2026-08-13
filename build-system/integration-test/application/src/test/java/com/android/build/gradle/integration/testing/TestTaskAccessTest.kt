/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.android.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on’ an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.build.gradle.integration.testing

import com.android.build.api.dsl.AgpTestSuite
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.HasHostTests
import com.android.build.api.variant.HasTestSuites
import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.options.BooleanOption
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test

class TestTaskAccessTest {

  @get:Rule
  val rule =
    GradleRule.configure().from {
      gradleProperties { add(BooleanOption.TEST_SUITE_SUPPORT, true) }
      androidApplication {
        pluginCallbacks += TestTaskAccessCallback::class.java
        android {
          testOptions.suites.create("first", AgpTestSuite::class.java) { suite ->
            suite.useJunitEngine.apply { includeEngines.add("[engine:toy-junit-engine-for-tests]") }
            suite.assets {}
            suite.targetVariants.add("debug")
            suite.targets.apply { create("t1") {} }
          }
        }
        files {
          add(
            "src/androidTest/java/com/example/DummyTest.java",
            """
            package com.example;
            import org.junit.Test;
            public class DummyTest {
                @Test
                public void test() {}
            }
            """
              .trimIndent(),
          )
        }
      }
    }

  class TestTaskAccessCallback : ApplicationComponentCallback {
    override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
      androidComponents.onVariants(androidComponents.selector().all()) { variant ->
        // 1. Test DeviceTest (androidTest)
        variant.androidTest?.configureTestTask { task -> task.description = "Custom description set by configureTestTask" }
        variant.androidTest?.withTestTaskProvider { taskProvider ->
          taskProvider.configure { task -> task.group = "Custom group set by withTestTaskProvider" }
        }

        // 2. Test HostTest (unitTest)
        val hostTest = (variant as? HasHostTests)?.hostTests["UnitTest"]
        hostTest?.configureTestTask { task -> task.description = "Custom description set by configureTestTask on UnitTest" }
        hostTest?.withTestTaskProvider { taskProvider ->
          taskProvider.configure { task -> task.group = "Custom group set by withTestTaskProvider on UnitTest" }
        }

        // 3. Test TestSuite
        val suite = (variant as? HasTestSuites)?.suites?.get("first")
        suite?.configureTestTasks { _ -> description = "Custom description set by configureTestTasks on TestSuite" }
        suite?.withTestTaskProviders { _ -> configure { task -> task.group = "Custom group set by withTestTaskProviders on TestSuite" } }
      }

      project.tasks.register("verifyDeviceTestTaskConfig") { verify ->
        verify.dependsOn("connectedDebugAndroidTest")
        verify.dependsOn("testDebugUnitTest")
        verify.dependsOn("testFirstT1DebugTestSuite")
      }

      project.gradle.taskGraph.whenReady {
        val connectedTask = project.tasks.getByName("connectedDebugAndroidTest")
        println("TASK_DESCRIPTION: " + connectedTask.description)
        println("TASK_GROUP: " + connectedTask.group)

        val unitTestTask = project.tasks.getByName("testDebugUnitTest")
        println("UNIT_TEST_DESCRIPTION: " + unitTestTask.description)
        println("UNIT_TEST_GROUP: " + unitTestTask.group)

        val suiteTask = project.tasks.getByName("testFirstT1DebugTestSuite")
        println("SUITE_TEST_DESCRIPTION: " + suiteTask.description)
        println("SUITE_TEST_GROUP: " + suiteTask.group)
      }
    }
  }

  @Suppress("DEPRECATION")
  @Test
  fun testTestTaskAccess() {
    val build = rule.build
    val result =
      build.executor
        .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.OFF)
        .withArgument("--dry-run")
        .run(":app:verifyDeviceTestTaskConfig")

    // Verify DeviceTest
    ScannerSubject.assertThat(result.stdout).contains("TASK_DESCRIPTION: Custom description set by configureTestTask")
    ScannerSubject.assertThat(result.stdout).contains("TASK_GROUP: Custom group set by withTestTaskProvider")

    // Verify HostTest
    ScannerSubject.assertThat(result.stdout).contains("UNIT_TEST_DESCRIPTION: Custom description set by configureTestTask on UnitTest")
    ScannerSubject.assertThat(result.stdout).contains("UNIT_TEST_GROUP: Custom group set by withTestTaskProvider on UnitTest")

    // Verify TestSuite
    ScannerSubject.assertThat(result.stdout).contains("SUITE_TEST_DESCRIPTION: Custom description set by configureTestTasks on TestSuite")
    ScannerSubject.assertThat(result.stdout).contains("SUITE_TEST_GROUP: Custom group set by withTestTaskProviders on TestSuite")
  }
}

abstract class VerifyTask : DefaultTask() {
  @get:Input abstract val taskDescription: Property<String>

  @get:Input abstract val taskGroup: Property<String>

  @TaskAction
  fun run() {
    println("TASK_DESCRIPTION: " + taskDescription.get())
    println("TASK_GROUP: " + taskGroup.get())
  }
}
