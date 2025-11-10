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

import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.TestUtils
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class BuiltInKotlinForTestFixturesTest(private val builtInKotlin: Boolean) {

    companion object {

        @Parameterized.Parameters(name = "builtInKotlin_{0}")
        @JvmStatic
        fun parameters() = listOf(true, false)
    }

    @get:Rule
    val rule = GradleRule.from {
        androidLibrary {
            if (!builtInKotlin) {
                @Suppress("DEPRECATION")
                applyPlugin(PluginType.KOTLIN_ANDROID)
            }

            android {
                defaultConfig.minSdk = 21
            }
            kotlin {
                jvmToolchain(17)
            }
        }
        if (!builtInKotlin) {
            gradleProperties {
                add(BooleanOption.USE_NEW_DSL, false)
                add(BooleanOption.BUILT_IN_KOTLIN, false)
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
                if (!builtInKotlin) {
                    @Suppress("DEPRECATION")
                    applyPlugin(PluginType.KOTLIN_ANDROID)
                }
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
    fun `test internal methods in main component are accessible from test fixtures`() {
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

    /** Regression test for b/450568272. */
    @Test
    fun `test internal methods in test fixtures are accessible from tests`() {
        val build = rule.build {
            enableTestFixturesKotlinSupport()
            androidLibrary {
                files {
                    add(
                        "src/testFixtures/kotlin/ExampleTestFixtureClass.kt",
                        """
                        package com.example.lib
                        class ExampleTestFixtureClass {
                            internal fun internalMethodInTestFixture() {}
                        }
                        """.trimIndent()
                    )
                    add(
                        "src/test/kotlin/ExampleUnitTest.kt",
                        """
                        package com.example.lib
                        class ExampleUnitTest {
                            fun test() {
                                ExampleTestFixtureClass().internalMethodInTestFixture()
                            }
                        }
                        """.trimIndent()
                    )
                }
            }
        }

        build.executor.run(":lib:compileDebugUnitTestKotlin")
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
                    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
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
        ).contains("My jvmTarget: 11")

        // Then check that setting jvmTarget on the task overrides the compilerOptions DSL.
        // And because it does not match the jvmTarget for JavaCompile, the build should fail.
        build.executor.expectFailure()
            .withArgument("-PsetViaTask=true")
            .run(":lib:compileDebugTestFixturesKotlin")
            .assertErrorContains("Inconsistent JVM targets between Java and Kotlin compile tasks: 11 and 17.")
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
                        jvmTarget.set(JvmTarget.JVM_17)
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
