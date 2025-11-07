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

import com.android.build.api.dsl.Device
import com.android.build.api.instrumentation.manageddevice.DeviceSetupConfigureAction
import com.android.build.api.instrumentation.manageddevice.DeviceSetupInput
import com.android.build.api.instrumentation.manageddevice.DeviceSetupTaskAction
import com.android.build.api.instrumentation.manageddevice.DeviceTestRunConfigureAction
import com.android.build.api.instrumentation.manageddevice.DeviceTestRunInput
import com.android.build.api.instrumentation.manageddevice.DeviceTestRunParameters
import com.android.build.api.instrumentation.manageddevice.DeviceTestRunTaskAction
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.truth.ScannerSubject.Companion.assertThat
import com.android.build.gradle.integration.manageddevice.utils.simpleProject
import com.android.build.gradle.internal.utils.setDisallowChanges
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import com.android.utils.FileUtils
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.inject.Inject

class ManagedDeviceExtensionTest {

    interface MyCustomDevice : Device

    open class MyCustomDeviceImpl(val deviceName: String) : MyCustomDevice {
        override fun getName() = deviceName
    }

    abstract class SetupInput : DeviceSetupInput {
        @get:Input
        abstract val deviceName: Property<String>
    }

    abstract class SetupConfigAction : DeviceSetupConfigureAction<MyCustomDevice, SetupInput> {
        @get:Inject
        abstract val objectFactory: ObjectFactory

        override fun configureTaskInput(deviceDSL: MyCustomDevice): SetupInput {
            return objectFactory.newInstance<SetupInput>(SetupInput::class.java).apply {
                deviceName.setDisallowChanges(deviceDSL.name)
            }
        }
    }

    open class SetupTaskAction : DeviceSetupTaskAction<SetupInput> {
        override fun setup(setupInput: SetupInput, outputDir: Directory) {
            outputDir.file("deviceName.txt").asFile.printWriter(Charsets.UTF_8).use { writer ->
                writer.println(setupInput.deviceName.get())
            }
        }
    }

    abstract class TestRunInput : DeviceTestRunInput

    abstract class TestRunConfigAction : DeviceTestRunConfigureAction<MyCustomDevice, TestRunInput> {
        @get:Inject
        abstract val objectFactory: ObjectFactory

        override fun configureTaskInput(deviceDSL: MyCustomDevice): TestRunInput {
            return objectFactory.newInstance<TestRunInput>(TestRunInput::class.java)
        }
    }

    open class TestRunTaskAction : DeviceTestRunTaskAction<TestRunInput> {
        override fun runTests(params: DeviceTestRunParameters<TestRunInput>): Boolean {
            params.testRunData.outputDirectory.file("TEST-" + params.testRunData.deviceName + ".xml").asFile.printWriter(
                Charsets.UTF_8
            ).use { writer ->
                writer.println(
                    //language=xml
                    """
<?xml version='1.0' encoding='UTF-8' ?>
<testsuite name="com.example.android.kotlin.ExampleInstrumentedTest" tests="2" failures="0" errors="0" skipped="0" time="0.969" timestamp="2022-12-12T22:38:18" hostname="localhost">
    <properties>
        <property name="device" value="localDevice" />
        <property name="flavor" value="" />
        <property name="project" value=":app" />
    </properties>
    <testcase name="useAppContext2" classname="com.example.android.kotlin.ExampleInstrumentedTest" time="0.004" />
    <testcase name="useAppContext" classname="com.example.android.kotlin.ExampleInstrumentedTest" time="0.0" />
</testsuite>
                    """.trimIndent()
                )
            }
            return true
        }
    }

    @get:Rule
    val rule = GradleRule.from {
        simpleProject()
        rootProject {
            buildscript {
                classpath(localJar("myCustomGmdClasses") {
                    addClasses(
                        MyCustomDevice::class.java,
                        MyCustomDeviceImpl::class.java,
                        ManagedDeviceExtensionTest::class.java,
                        SetupConfigAction::class.java,
                        SetupInput::class.java,
                        SetupTaskAction::class.java,
                        TestRunConfigAction::class.java,
                        TestRunInput::class.java,
                        TestRunTaskAction::class.java,
                    )
                })
            }
        }
        androidApplication {
            android.testOptions.managedDevices {
                allDevices.create("myCustomDevice", MyCustomDevice::class.java) {}
            }
            pluginCallbacks += AddCustomGMDCallback::class.java
        }
        androidApplication(":emptyAppProject") {
            android.testOptions.managedDevices {
                allDevices.create("myCustomDevice", MyCustomDevice::class.java) {}
            }
            pluginCallbacks += AddCustomGMDCallback::class.java
        }
        gradleProperties {
            // TODO(b/458859093) remove when test is fixed to not require this.
            add(BooleanOption.ENABLE_APP_COMPILE_TIME_R_CLASS, false)
        }
    }

    class AddCustomGMDCallback : ApplicationComponentCallback {
        override fun handleExtension(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            androidComponents.managedDeviceRegistry.registerDeviceType(MyCustomDevice::class.java) {
                dslImplementationClass = MyCustomDeviceImpl::class.java
                setSetupActions(
                    SetupConfigAction::class.java,
                    SetupTaskAction::class.java
                )
                setTestRunActions(
                    TestRunConfigAction::class.java,
                    TestRunTaskAction::class.java
                )
            }
        }
    }

    private val executor: GradleTaskExecutor
        get() = rule.build.executor

    @Test
    fun runCustomManagedDevice() {
        executor.run(":app:myCustomDeviceCheck")

        val project = rule.build.androidApplication()

        val setupDir = FileUtils.join(
            project.buildDir.toFile(),
            "managedDeviceSetupResults",
            "myCustomDevice"
        )
        assertThat(File(setupDir, "deviceName.txt")).contains("myCustomDevice")

        val reportDir = FileUtils.join(
            project.buildDir.toFile(),
            "reports",
            "androidTests",
            "managedDevice",
            "debug",
            "myCustomDevice"
        )
        assertThat(File(reportDir, "index.html")).exists()
        assertThat(File(reportDir, "com.example.android.kotlin.html")).exists()
        assertThat(
            File(
                reportDir,
                "com.example.android.kotlin.ExampleInstrumentedTest.html"
            )
        ).exists()

        val mergedTestReportDir = FileUtils.join(
            project.buildDir.toFile(),
            "reports",
            "androidTests",
            "managedDevice",
            "debug",
            "allDevices"
        )
        assertThat(File(mergedTestReportDir, "index.html")).exists()
        assertThat(File(mergedTestReportDir, "com.example.android.kotlin.html")).exists()
        assertThat(
            File(
                mergedTestReportDir,
                "com.example.android.kotlin.ExampleInstrumentedTest.html"
            )
        ).exists()
    }

    @Test
    fun runCustomManagedDeviceWithNoTests() {
        val project = rule.build.androidApplication(":emptyAppProject")

        val result = executor
            // TODO(b/439843451) - Opt back into `android.enableAppCompileTimeRClass`
            .with(BooleanOption.ENABLE_APP_COMPILE_TIME_R_CLASS, false)
            .run(":emptyAppProject:myCustomDeviceCheck")

        result.stdout.use {
            assertThat(it).contains("No tests found, nothing to do.")
        }

        val setupDir = FileUtils.join(
            project.buildDir.toFile(),
            "managedDeviceSetupResults",
            "myCustomDevice"
        )
        assertThat(File(setupDir, "deviceName.txt")).contains("myCustomDevice")

        val reportDir = FileUtils.join(
            project.buildDir.toFile(),
            "reports",
            "androidTests",
            "managedDevice",
            "debug",
            "myCustomDevice"
        )
        assertThat(File(reportDir, "index.html")).exists()

        val mergedTestReportDir = FileUtils.join(
            project.buildDir.toFile(),
            "reports",
            "androidTests",
            "managedDevice",
            "debug",
            "allDevices"
        )
        assertThat(File(mergedTestReportDir, "index.html")).exists()
    }
}
