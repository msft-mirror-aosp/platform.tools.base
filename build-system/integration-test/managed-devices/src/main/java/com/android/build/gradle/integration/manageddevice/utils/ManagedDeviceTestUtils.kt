package com.android.build.gradle.integration.manageddevice.utils

import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.utils.disableBuiltInKotlin
import com.android.build.gradle.options.BooleanOption

fun GradleBuildDefinition.simpleGMDProject() {
    simpleProject()
    androidApplication {
        addManagedDevice("device1")
    }
    disableBuiltInKotlin()
}

fun GradleBuildDefinition.simpleProject() {
    androidApplication {
        applyPlugin(PluginType.KOTLIN_ANDROID)
        android {
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

fun AndroidProjectDefinition<out CommonExtension>.addManagedDevice(deviceName: String) {
    android {
        testOptions.managedDevices {
            localDevices.create(deviceName) {
                it.device = "Pixel 2"
                it.sdkVersion = System.getProperty("sdk.repo.sysimage.apiLevel").toInt()
                it.systemImageSource = System.getProperty("sdk.repo.sysimage.source")
                it.require64Bit = true
            }
        }
    }
}
