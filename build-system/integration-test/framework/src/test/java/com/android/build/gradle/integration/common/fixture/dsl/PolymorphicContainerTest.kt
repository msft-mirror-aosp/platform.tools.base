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

package com.android.build.gradle.integration.common.fixture.dsl

import com.android.build.api.dsl.ManagedDevices
import com.android.build.api.dsl.ManagedVirtualDevice
import com.android.build.gradle.integration.common.fixture.project.builder.GroovyBuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.KtsBuildWriter
import com.google.common.truth.Truth
import org.junit.Test

class PolymorphicContainerTest {

    private val dslRecorder = DefaultDslRecorder()

    @Test
    fun create() {
        dslRecorder.runNestedBlock("managedDevices", listOf(), ManagedDevices::class.java) {
            allDevices.create("foo", ManagedVirtualDevice::class.java) {
                it.sdkVersion = 35
            }
        }

        val groovy = GroovyBuildWriter()
        dslRecorder.writeContent(groovy)
        Truth.assertThat(groovy.toString()).isEqualTo("""
            managedDevices {
              allDevices.create('foo', com.android.build.api.dsl.ManagedVirtualDevice) {
                sdkVersion = 35
              }
            }

        """.trimIndent())
    }

    @Test
    fun createKts() {
        dslRecorder.runNestedBlock("managedDevices", listOf(), ManagedDevices::class.java) {
            allDevices.create("foo", ManagedVirtualDevice::class.java) {
                it.sdkVersion = 35
            }
        }

        val kts = KtsBuildWriter()
        dslRecorder.writeContent(kts)
        Truth.assertThat(kts.toString()).isEqualTo("""
            managedDevices {
              allDevices.create("foo", com.android.build.api.dsl.ManagedVirtualDevice::class.java) {
                sdkVersion = 35
              }
            }

        """.trimIndent())
    }

    @Test
    fun named() {
        dslRecorder.runNestedBlock("managedDevices", listOf(), ManagedDevices::class.java) {
            allDevices.named("foo") {
                // nothing in call in Device as it's all read-only
            }
        }

        val groovy = GroovyBuildWriter()
        dslRecorder.writeContent(groovy)
        Truth.assertThat(groovy.toString()).isEqualTo("""
            managedDevices {
              allDevices.named('foo') {
              }
            }

        """.trimIndent())
    }


    @Test
    fun allAction() {
        dslRecorder.runNestedBlock("managedDevices", listOf(), ManagedDevices::class.java) {
            allDevices.all {
                // nothing in call in Device as it's all read-only
            }
        }

        val groovy = GroovyBuildWriter()
        dslRecorder.writeContent(groovy)
        Truth.assertThat(groovy.toString()).isEqualTo("""
            managedDevices {
              allDevices.all {
              }
            }

        """.trimIndent())
    }
}
