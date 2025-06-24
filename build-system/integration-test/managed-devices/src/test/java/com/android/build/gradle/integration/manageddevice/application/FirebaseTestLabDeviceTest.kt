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

package com.android.build.gradle.integration.manageddevice.application

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.AppPlugin
import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
import com.android.build.gradle.integration.common.fixture.project.AndroidApplicationProject
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType.PluginTypeWithExtension
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.truth.ScannerSubject.Companion.assertThat
import com.android.build.gradle.integration.manageddevice.utils.simpleProject
import com.google.firebase.testlab.gradle.TestLabGradlePluginExtension
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test
import java.io.File

class FirebaseTestLabDeviceTest {

    @get:Rule
    val rule: GradleRule = GradleRule.from {
        simpleProject()
        androidApplication {
            applyPlugin(FirebaseTestLabPlugin) {
                managedDevices.create("myFtlDevice1") {
                    it.device = "Pixel2"
                    it.apiLevel = 29
                }
                managedDevices.create("myFtlDevice2") {
                    it.device = "Pixel3"
                    it.apiLevel = 30
                    it.orientation = "landscape"
                    it.locale = "en-US"
                }
                managedDevices.create("myFtlDevice3") {
                    it.device = "Pixel2"
                    it.apiLevel = 29
                }
            }
            pluginCallbacks += PrintDslValueCallback::class.java
            pluginCallbacks += CreateGMDGroupCallback::class.java
            pluginCallbacks += RemoveFTLDevice3Callback::class.java
        }
    }

    private val project: AndroidApplicationProject
        get() = rule.build.androidApplication()

    private val executor: GradleTaskExecutor
        get() = rule.build.executor.withEnableInfoLogging(false)

    object FirebaseTestLabPlugin: PluginTypeWithExtension<TestLabGradlePluginExtension>(
        id = "com.google.firebase.testlab",
        version = "+",
        artifact = "com.google.firebase.testlab:testlab-gradle-plugin",
        hasMarker = false,
        extensionName = "firebaseTestLab",
        extensionType = TestLabGradlePluginExtension::class.java)

    class PrintDslValueCallback : GenericCallback {
        override fun handleProject(project: Project) {
            project.tasks.register("printDslProperties") {
                it.notCompatibleWithConfigurationCache("test helper task")
                it.doLast {
                    val firebaseTestLab = project.extensions.getByName("firebaseTestLab") as TestLabGradlePluginExtension
                    println("orientation = " + firebaseTestLab.managedDevices.getByName("myFtlDevice2").orientation)
                    println("serviceAccountCredentials = " + firebaseTestLab.serviceAccountCredentials.asFile.orNull?.name)
                    println("grantedPermissions = " + firebaseTestLab.testOptions.fixture.grantedPermissions)
                    println("extraDeviceFiles = " + firebaseTestLab.testOptions.fixture.extraDeviceFiles.get())
                    println("networkProfile = " + firebaseTestLab.testOptions.fixture.networkProfile)
                    println("cloudStorageBucket = " + firebaseTestLab.testOptions.results.cloudStorageBucket)
                    println("resultsHistoryName = " + firebaseTestLab.testOptions.results.resultsHistoryName)
                    println("directoriesToPull = " + firebaseTestLab.testOptions.results.directoriesToPull.get())
                }
            }
        }
    }

    class CreateGMDGroupCallback : GenericCallback {
        override fun handleProject(project: Project) {
            project.plugins.withType(AppPlugin::class.java) {
                val componentsExtension =
                    project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
                componentsExtension.finalizeDsl {
                    it.testOptions.managedDevices {
                        groups.create("ftlDevices") {
                            it.targetDevices.add(allDevices.getByName("myFtlDevice1"))
                            it.targetDevices.add(allDevices.getByName("myFtlDevice2"))
                        }
                    }
                }
            }
        }
    }

    class RemoveFTLDevice3Callback : GenericCallback {
        override fun handleProject(project: Project) {
            project.plugins.withType(AppPlugin::class.java) {
                val componentsExtension =
                    project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
                val ftlExtension =
                    project.extensions.getByType(TestLabGradlePluginExtension::class.java)
                componentsExtension.finalizeDsl {
                    ftlExtension.managedDevices.remove(
                        ftlExtension.managedDevices.getByName("myFtlDevice3"))
                }
            }
        }
    }

    @Test
    fun ftlManagedDeviceTasks() {
        val result = executor.run("tasks")
        result.stdout.use {
            assertThat(it).contains("myFtlDevice1Check")
            assertThat(it).contains("myFtlDevice1DebugAndroidTest")
            assertThat(it).contains("myFtlDevice2Check")
            assertThat(it).contains("myFtlDevice2DebugAndroidTest")
        }
    }

    @Test
    fun dsl() {
        project.reconfigure {
            reconfigurePlugin(FirebaseTestLabPlugin) {
                serviceAccountCredentials.set(File("test.json"))
                testOptions {
                    fixture {
                        grantedPermissions = "none"
                        extraDeviceFiles.put("/sdcard/Android/data/com.example.myapplication/myAdditionalText.txt", "app/myAdditionalText.txt")
                        networkProfile = "LTE"
                    }
                    results {
                        cloudStorageBucket = "my_example_custom_bucket"
                        resultsHistoryName = "MyCustomHistoryName"
                        directoriesToPull.addAll("/sdcard/Android/data/com.example.myapplication")
                    }
                }
            }
        }

        val result = executor.run(":app:printDslProperties")
        result.stdout.use {
            assertThat(it).contains("orientation = LANDSCAPE")
            assertThat(it).contains("serviceAccountCredentials = test.json")
            assertThat(it).contains("grantedPermissions = NONE")
            assertThat(it).contains("extraDeviceFiles = {/sdcard/Android/data/com.example.myapplication/myAdditionalText.txt=app/myAdditionalText.txt}")
            assertThat(it).contains("networkProfile = LTE")
            assertThat(it).contains("cloudStorageBucket = my_example_custom_bucket")
            assertThat(it).contains("resultsHistoryName = MyCustomHistoryName")
            assertThat(it).contains("directoriesToPull = [/sdcard/Android/data/com.example.myapplication]")
        }
    }

    @Test
    fun managedDevicesAddsAllDevices() {
        val result = executor.run("tasks")
        result.stdout.use {
            assertThat(it).contains("ftlDevicesGroupCheck")
            assertThat(it).contains("ftlDevicesGroupDebugAndroidTest")
        }
    }

    @Test
    fun managedDevicesRemovesAllDevices() {
        val result = executor.run("tasks")
        // b/c stdout is a scanner, we have to start over every time we search for something
        // that does not exist.
        result.stdout.use {
            assertThat(it).doesNotContain("myFtlDevice3Check")
        }
        result.stdout.use {
            assertThat(it).doesNotContain("myFtlDevice3DebugAndroidTest")
        }
        result.stdout.use {
            assertThat(it).contains("myFtlDevice1Check")
            assertThat(it).contains("myFtlDevice1DebugAndroidTest")
            assertThat(it).contains("myFtlDevice2Check")
            assertThat(it).contains("myFtlDevice2DebugAndroidTest")
        }
    }
}
