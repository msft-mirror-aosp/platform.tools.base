/*
 * Copyright (C) 2019 The Android Open Source Project
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
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_LIB_PATH
import kotlin.test.assertFailsWith
import org.gradle.api.internal.tasks.TaskDependencyResolveException
import org.gradle.tooling.BuildException
import org.junit.Rule
import org.junit.Test

class MisplacedMissingDimensionStrategyTest {

  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication { dependencies { implementation(project(DEFAULT_LIB_PATH)) } }
      androidLibrary {
        android {
          defaultConfig {
            missingDimensionStrategy("libdim", "foo")
            flavorDimensions += "libdim"

            productFlavors {
              create("foo") { it.dimension = "libdim" }
              create("bar") { it.dimension = "libdim" }
            }
          }
        }
      }
    }

  @Test
  fun checkCorrectError() {
    val build = rule.build
    val exception = assertFailsWith(BuildException::class) { build.executor.run(":app:assembleDebug") }

    exception.checkCause(TaskDependencyResolveException::class.java)
  }
}

/**
 * Context: b/460094802. This test is verifying the fix of the wrong behavior of variant attributes matching when using ProductFlavors'
 * MissingDimensionStrategy. When there is a dimension mismatch between ":app" and ":lib", and ":app" specifies missingDimensionStrategy we
 * end up prioritizing matching a ProductFlavor with same name as the consumer's.
 */
class MisplacedMissingDimensionStrategyWrongBehaviorTest {

  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication {
        android {
          flavorDimensions += "color"

          productFlavors {
            create("foo") {
              it.dimension = "color"
              it.isDefault = true
              // This here will fail build (as expected). Because the
              // missingDimensionStrategy doesn't list any of the flavors that exist
              // in the library, the build should fail as there is an
              // ambiguous match of variant in the dependency on the library.
              it.missingDimensionStrategy("colorLib", "wrong")
            }
            create("loo") {}
          }
        }
        dependencies { implementation(project(DEFAULT_LIB_PATH)) }
      }
      androidLibrary {
        android {
          flavorDimensions += "colorLib"

          productFlavors {
            create("foo") { it.isDefault = true }
            create("loo") { it.dimension = "colorLib" }
          }
        }
      }
    }

  @Test
  fun checkCorrectError() {
    val build = rule.build
    val exception = build.executor.expectFailure().run(":app:assembleFooDebug").exception
    exception?.checkCause(TaskDependencyResolveException::class.java)
  }
}

fun <T : Throwable> Exception.checkCause(causeClass: Class<T>) {
  val eName = causeClass.name
  var theCause: Throwable? = cause
  while (theCause != null) {
    // must compare fqcn as the actual class is coming via RMI and is not going to match the
    // one that is loaded in the test.
    if (theCause.javaClass.name == eName) {
      return
    }

    theCause = theCause.cause
  }

  throw RuntimeException("Not true that cause is of type $causeClass", this)
}
