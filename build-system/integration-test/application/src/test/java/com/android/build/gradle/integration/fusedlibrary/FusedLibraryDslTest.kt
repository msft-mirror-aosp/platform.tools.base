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

package com.android.build.gradle.integration.fusedlibrary

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.options.BooleanOption
import org.junit.Rule
import org.junit.Test

class FusedLibraryDslTest {

    @get:Rule
    val rule = GradleRule.configure().from {
        fusedLibrary(":$FUSED_LIB_PROJECT_NAME") {
            androidFusedLibrary {
                namespace = null
            }
        }
        gradleProperties {
            add(BooleanOption.FUSED_LIBRARY_SUPPORT, true)
        }
    }

    @Test
    fun addingRequiredOptionsToAssemble() {
        val build = rule.build
        build.executor.expectFailure().run(":$FUSED_LIB_PROJECT_NAME:assemble").also {
            it.assertErrorContains(
                """Namespace is not defined.

     Please add the `namespace` field to the :fusedLib build file.

     For example:
     ```
     androidFusedLibrary {
         namespace = "com.example.mylibrary"
     }
     ```"""
            )
        }
        build.fusedLibrary(":$FUSED_LIB_PROJECT_NAME").reconfigure {
            androidFusedLibrary {
                namespace = "com.example.myfusedlib"
            }
        }
        build.executor.expectFailure().run(":$FUSED_LIB_PROJECT_NAME:assemble").also {
            it.assertErrorContains(
                """Minimum Sdk is not defined.

     Please configure `minSdk` in the `androidFusedLibrary`:fusedLib build file.

     For example:
     ```
     androidFusedLibrary {
         minSdk {
             version = release(34)
         }
     }
     ```"""
            )
        }
        build.fusedLibrary(":$FUSED_LIB_PROJECT_NAME").reconfigure {
            androidFusedLibrary {
                minSdk {
                    version = release(34)
                }
            }
        }
        build.executor.run(":$FUSED_LIB_PROJECT_NAME:assemble")
    }

    companion object {
        const val FUSED_LIB_PROJECT_NAME = "fusedLib"
    }
}
