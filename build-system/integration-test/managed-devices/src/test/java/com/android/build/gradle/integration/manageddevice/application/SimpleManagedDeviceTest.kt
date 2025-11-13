package com.android.build.gradle.integration.manageddevice.application

import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.manageddevice.utils.CustomAndroidSdkRule
import com.android.build.gradle.integration.manageddevice.utils.CustomAndroidSdkRule.Companion.withCustomAndroidSdk
import com.android.build.gradle.integration.manageddevice.utils.CustomAndroidSdkRule.Companion.withCustomSdkDir
import com.android.build.gradle.integration.manageddevice.utils.simpleGMDProject
import com.android.build.gradle.options.IntegerOption
import com.android.testutils.truth.PathSubject.assertThat
import com.android.utils.FileUtils
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.pathString

class SimpleManagedDeviceTest {

    @get:Rule
    val customAndroidSdkRule = CustomAndroidSdkRule()

    @get:Rule
    val rule = GradleRule.configure()
        .withCustomSdkDir(customAndroidSdkRule)
        .from { simpleGMDProject() }

    private val executor: GradleTaskExecutor
        get() = rule.build.executor
            .withCustomAndroidSdk(customAndroidSdkRule)
            .withEnableInfoLogging(false)

    private fun assertTestReportExists() {
        val reportDir = FileUtils.join(
            rule.build.androidApplication().buildDir.pathString,
            "reports",
            "androidTests",
            "managedDevice",
            "debug",
            "device1")
        assertThat(File(reportDir, "index.html")).exists()
        assertThat(File(reportDir, "com.example.android.kotlin.html")).exists()
        assertThat(File(reportDir, "com.example.android.kotlin.ExampleInstrumentedTest.html"))
            .exists()

        val mergedTestReportDir = FileUtils.join(
            rule.build.androidApplication().buildDir.pathString,
            "reports",
            "androidTests",
            "managedDevice",
            "debug",
            "allDevices")
        assertThat(File(mergedTestReportDir, "index.html")).exists()
        assertThat(File(mergedTestReportDir, "com.example.android.kotlin.html")).exists()
        assertThat(File(mergedTestReportDir,
            "com.example.android.kotlin.ExampleInstrumentedTest.html")).exists()
    }

    @Test
    fun runBasicManagedDevice() {
        val result = executor.withEnableInfoLogging(true)
            .run(":app:device1DebugAndroidTest")

        assertTestReportExists()

        result.assertOutputContains(
            "Execute com.example.android.kotlin.ExampleInstrumentedTest.useAppContext: PASSED")
    }

    @Test
    fun runBasicManagedDeviceWithSharding() {
        val result = executor
            .with(IntegerOption.MANAGED_DEVICE_SHARD_POOL_SIZE, 2)
            .run(":app:device1DebugAndroidTest")

        assertTestReportExists()

        result.assertOutputContains("tests on device1_0")
        result.assertOutputContains("tests on device1_1")
    }
}
