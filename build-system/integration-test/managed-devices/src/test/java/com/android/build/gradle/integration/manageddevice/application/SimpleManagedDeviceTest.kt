package com.android.build.gradle.integration.manageddevice.application

import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.truth.ScannerSubject.Companion.assertThat
import com.android.build.gradle.integration.manageddevice.utils.CustomAndroidSdkRule
import com.android.build.gradle.options.BooleanOption
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
        .from {
            androidApplication {
                applyPlugin(PluginType.KOTLIN_ANDROID)
                android {
                    testOptions.managedDevices {
                        localDevices.create("device1") {
                            it.device = "Pixel 2"
                            it.sdkVersion = customAndroidSdkRule.systemImageApiLevel.toInt()
                            it.systemImageSource = customAndroidSdkRule.systemImageSource
                            it.require64Bit = true
                        }
                    }
                    defaultConfig {
                        minSdk = 21
                        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                    }
                    dependencies {
                        androidTestImplementation("androidx.test:core:1.4.0-alpha06")
                        androidTestImplementation("androidx.test.ext:junit:1.1.3-alpha02")
                        androidTestImplementation("androidx.test:monitor:1.4.0-alpha06")
                        androidTestImplementation("androidx.test:rules:1.4.0-alpha06")
                        androidTestImplementation("androidx.test:runner:1.4.0-alpha06")
                    }
                }
                kotlin {
                    jvmToolchain(17)
                }
                files {
                    add(
                        "src/androidTest/java/com/example/android/kotlin/InstrumentedTest.kt",
                        //language=kotlin
                        """
                        package com.example.android.kotlin

                        import androidx.test.ext.junit.runners.AndroidJUnit4
                        import org.junit.Test
                        import org.junit.runner.RunWith

                        @RunWith(AndroidJUnit4::class)
                        class ExampleInstrumentedTest {
                            @Test
                            fun useAppContext() {}
                        }
                        """.trimIndent()
                    )
                }
            }
            gradleProperties {
                add(BooleanOption.USE_ANDROID_X, true)
            }
        }

    private val executor: GradleTaskExecutor
        get() = customAndroidSdkRule.run { rule.executorWithCustomAndroidSdk() }

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

    private fun assertUtpLogExist() {
        val outputDir = FileUtils.join(
            rule.build.androidApplication().buildDir.pathString,
            "outputs",
            "androidTest-results",
            "managedDevice",
            "debug",
            "device1")
        assertThat(File(outputDir, "utp.0.log")).exists()
        assertThat(File(outputDir, "utp.0.log")).contains(
            "INFO: Execute com.example.android.kotlin.ExampleInstrumentedTest.useAppContext: PASSED")
    }

    @Test
    fun runBasicManagedDevice() {
        executor.run(":app:device1DebugAndroidTest")

        assertTestReportExists()
        assertUtpLogExist()
    }

    @Test
    fun runBasicManagedDeviceWithSharding() {
        val result = executor
            .with(IntegerOption.MANAGED_DEVICE_SHARD_POOL_SIZE, 2)
            .run(":app:device1DebugAndroidTest")

        assertTestReportExists()
        result.stdout.use {
            assertThat(it).contains("tests on device1_0")
        }
        result.stdout.use {
            assertThat(it).contains("tests on device1_1")
        }
    }
}
