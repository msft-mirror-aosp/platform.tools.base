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

package com.android.build.gradle.integration.kotlin

import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.TestUtils
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.junit.Rule
import org.junit.Test

class BuiltInKotlinForTestFixturesTest {

    @get:Rule
    val rule = GradleRule.from {
        androidLibrary {
            applyPlugin(PluginType.KOTLIN_ANDROID)

            android {
                defaultConfig.minSdk = 21
            }
            kotlin {
                jvmToolchain(17)
            }
        }
    }

    /**
     * Include dependency on "androidx.compose.ui:ui-tooling-preview:1.6.5" as a regression test for
     * b/338512598
     */
    @Test
    fun testModuleAndExternalDependencies() {
        val build = rule.build {
            enableTestFixturesKotlinSupport()
            androidLibrary {
                dependencies {
                    testFixturesImplementation("androidx.compose.ui:ui-tooling-preview:1.6.5")
                    testFixturesImplementation(project(":lib2"))
                }
                files.add(
                    "src/testFixtures/kotlin/LibTestFixtureFoo.kt",
                    //language=kotlin
                    """
                        package com.foo.library

                        import com.foo.library.two.LibTwoClass
                        import androidx.compose.ui.tooling.preview.Preview

                        class LibTestFixtureFoo
                    """.trimIndent()
                )
            }
            androidLibrary(":lib2") {
                applyPlugin(PluginType.KOTLIN_ANDROID)
                kotlin {
                    jvmToolchain(17)
                }
                files.add(
                    "src/main/kotlin/LibTwoClass.kt",
                    //language=kotlin
                    """
                        package com.foo.library.two
                        class LibTwoClass
                    """.trimIndent()
                )
            }
        }

        build.executor
            .with(BooleanOption.USE_ANDROID_X, true)
            .run(":lib:assembleDebugTestFixtures")

        build.androidLibrary().assertAar(AarSelector.DEBUG.forTestFixtures()) {
            mainJar().classes().containsExactly("com/foo/library/LibTestFixtureFoo")
        }
    }

    @Test
    fun testInternalModifierAccessible() {
        val build = rule.build {
            enableTestFixturesKotlinSupport()
            androidLibrary {
                files {
                    add(
                        "src/testFixtures/kotlin/LibTestFixtureFoo.kt",
                        //language=kotlin
                        """
                            package com.foo.library
                            class LibTestFixtureFoo {
                                init { LibFoo().bar() }
                            }
                        """.trimIndent()
                    )
                    add(
                        "src/main/java/LibFoo.kt",
                        //language=kotlin
                        """
                            package com.foo.library
                            class LibFoo {
                                internal fun bar() {}
                            }
                        """.trimIndent()
                    )
                }
            }
        }

        build.executor.run(":lib:assembleDebugTestFixtures")
    }

    @Test
    fun testLowKotlinVersion() {
        val build = rule.build {
            enableTestFixturesKotlinSupport()
            androidLibrary {
                replaceAppliedPlugin(PluginType.KOTLIN_ANDROID, "1.8.10")
            }
        }
        val result = build.executor.expectFailure().run(":lib:assembleDebugTestFixtures")
        result.assertErrorContains(
            "The current Kotlin Gradle plugin version (1.8.10) is below the required"
        )
    }

    @Test
    fun testLowKotlinVersionWithNoBuiltInKotlinSupport() {
        val build = rule.build {
            androidLibrary {
                replaceAppliedPlugin(PluginType.KOTLIN_ANDROID, "1.8.10")
                android {
                    testFixtures.enable = true
                }
            }
        }
        // We expect no build failure in this case.
        // Set failOnWarning to false because Gradle warns about deprecated feature(s) used by KGP 1.8.10.
        build.executor
            .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
            .withFailOnWarning(false)
            .run(":lib:assembleDebugTestFixtures")
    }

    // Regression test for b/364331837
    @Test
    fun testJvmTarget() {
        val build = rule.build {
            enableTestFixturesKotlinSupport()
            androidLibrary {
                // remove the jvmtoolchain setting
                resetKotlinDsl()
                kotlin {
                    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
                }

                // Add a simple kotlin source file so that kotlin compilation task does work.
                files.add(
                    "src/testFixtures/kotlin/LibTestFixtureFoo.kt",
                    //language=kotlin
                    """
                        package com.foo.library
                        class LibTestFixtureFoo {}
                    """.trimIndent()
                )

                pluginCallbacks += JvmTargetCallback::class.java
            }
        }

        ScannerSubject.assertThat(
            build.executor.run(":lib:compileDebugTestFixturesKotlin").stdout
        ).contains("My jvmTarget: 17")

        // Then check that setting jvmTarget on the task overrides the compilerOptions DSL
        ScannerSubject.assertThat(
            build.executor
                .withArgument("-PsetViaTask=true")
                .run(":lib:compileDebugTestFixturesKotlin")
                .stdout
        ).contains("My jvmTarget: 21")
    }

    class JvmTargetCallback: GenericCallback {
        override fun handleProject(project: Project) {
            val setViaTask = project.providers.gradleProperty("setViaTask")

            project.afterEvaluate {
                project.tasks.named("compileDebugTestFixturesKotlin") {
                    it.doLast { task ->
                        task as KotlinCompile
                        val jvmTarget = task.compilerOptions.jvmTarget.get().target
                        println("My jvmTarget: $jvmTarget")
                    }
                }
            }

            if (setViaTask.orNull == "true") {
                project.tasks.withType(KotlinCompile::class.java).configureEach {
                    it.compilerOptions {
                        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                    }
                }
            }
        }
    }

    private fun GradleBuildDefinition.enableTestFixturesKotlinSupport() {
        androidLibrary {
            android {
                testFixtures.enable = true
            }
            dependencies {
                testFixturesImplementation("org.jetbrains.kotlin:kotlin-stdlib:${TestUtils.KOTLIN_VERSION_FOR_TESTS}")
            }
        }

        gradleProperties {
            add(BooleanOption.ENABLE_TEST_FIXTURES_KOTLIN_SUPPORT, true)
        }
    }
}
