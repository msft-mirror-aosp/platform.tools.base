/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.project.ApkSelector.Companion.ANDROIDTEST_DEBUG
import com.android.build.gradle.integration.common.fixture.project.ApkSelector.Companion.DEBUG
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.internal.scope.InternalArtifactType.JAVAC
import com.android.testutils.truth.PathSubject.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * This class provides test coverage for using custom values for namespace, testNamespace,
 * applicationId, testApplicationId, and various combinations of these.
 */
class NamespaceAndApplicationIdTest {

    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            android {
                namespace = "com.example.app"
                buildFeatures {
                    buildConfig = true
                }
            }
            files {
                add(
                    "src/main/res/values/values.xml",
                    //language=xml
                    """
                        <resources>
                            <string name="app_string">hello</string>
                        </resources>""".trimIndent()
                )
                add(
                    "src/main/java/com/example/app/MyClass.java",
                    //language=java
                    """
                        package com.example.app;

                        import com.example.app.BuildConfig;

                        public class MyClass {
                            void test() {
                                int r = R.string.app_string;
                            }
                        }""".trimIndent()
                )
                add(
                    "src/androidTest/res/values/values.xml",
                    //language=xml
                    """
                        <resources>
                            <string name="test_string">hi</string>
                        </resources>""".trimIndent()
                )
                add(
                    "src/androidTest/java/com/example/app/test/MyTestClass.java",
                    //language=java
                    """
                        package com.example.app.test;

                        import com.example.app.BuildConfig;

                        public class MyTestClass {
                            void test() {
                                int app_r = com.example.app.R.string.app_string;
                                int test_r = com.example.app.test.R.string.test_string;
                            }
                        }""".trimMargin()
                )
            }
        }
        androidTest {
            android {
                namespace = "com.example.test"
                targetProjectPath = ":app"
                buildFeatures {
                    buildConfig = true
                }
            }
            files {
                add(
                    "src/main/res/values/values.xml",
                    //language=xml
                    """
                        <resources>
                            <string name="app_string">hello</string>
                        </resources>""".trimIndent()
                )
                add(
                    "src/main/java/com/example/test/MyClass.java",
                    //language=java
                    """
                        package com.example.test;

                        import com.example.test.BuildConfig;

                        public class MyClass {
                            void test() {
                                int r = R.string.app_string;
                            }
                        }""".trimIndent()
                )
            }
        }
    }

    @Test
    fun testDefault() {
        val build = rule.build
        val app = build.androidApplication()

        build.executor.run("app:assembleDebug", "app:assembleAndroidTest")

        app.assertApk(DEBUG) {
            applicationId().isEqualTo("com.example.app")
        }
        app.assertApk(ANDROIDTEST_DEBUG) {
            applicationId().isEqualTo("com.example.app.test")
        }
        assertThat(
            app.resolve(JAVAC)
                .resolve("debugAndroidTest/compileDebugAndroidTestJavaWithJavac/classes/com/example/app/test/BuildConfig.class")
        ).isFile()
    }

    @Test
    fun testDefaultForTestModule() {
        val build = rule.build
        build.executor.run(":test:assembleDebug")
        build.androidTest().assertApk(DEBUG) {
            applicationId().isEqualTo("com.example.test")
        }
    }

    @Test
    fun testCustomApplicationId() {
        val build = rule.build {
            androidApplication {
                android.defaultConfig.applicationId = "com.example.applicationId"
            }
        }
        val app = build.androidApplication()

        build.executor.run(":app:assembleDebug", ":app:assembleAndroidTest")

        app.assertApk(DEBUG) {
            applicationId().isEqualTo("com.example.applicationId")
        }
        app.assertApk(ANDROIDTEST_DEBUG) {
            applicationId().isEqualTo("com.example.applicationId.test")
        }
        assertThat(
            app.resolve(JAVAC)
                .resolve("debugAndroidTest/compileDebugAndroidTestJavaWithJavac/classes/com/example/app/test/BuildConfig.class")
        ).isFile()
    }

    @Test
    fun testCustomTestApplicationId() {
        val build = rule.build {
            androidApplication {
                android.defaultConfig.testApplicationId = "com.example.testApplicationId"
            }
        }
        val app = build.androidApplication()

        build.executor.run(":app:assembleDebug", ":app:assembleAndroidTest")

        app.assertApk(DEBUG) {
            applicationId().isEqualTo("com.example.app")
        }
        app.assertApk(ANDROIDTEST_DEBUG) {
            applicationId().isEqualTo("com.example.testApplicationId")
        }
        assertThat(
            app.resolve(JAVAC)
                .resolve("debugAndroidTest/compileDebugAndroidTestJavaWithJavac/classes/com/example/app/test/BuildConfig.class")
        ).isFile()
    }

    @Test
    fun testCustomApplicationIdAndTestApplicationId() {
        val build = rule.build {
            androidApplication {
                android {
                    defaultConfig {
                        applicationId = "com.example.applicationId"
                        testApplicationId = "com.example.testApplicationId"
                    }
                }
            }
        }
        val app = build.androidApplication()

        build.executor.run(":app:assembleDebug", ":app:assembleAndroidTest")

        app.assertApk(DEBUG) {
            applicationId().isEqualTo("com.example.applicationId")
        }
        app.assertApk(ANDROIDTEST_DEBUG) {
            applicationId().isEqualTo("com.example.testApplicationId")
        }

        assertThat(
            app.resolve(JAVAC)
                .resolve("debugAndroidTest/compileDebugAndroidTestJavaWithJavac/classes/com/example/app/test/BuildConfig.class")
        ).isFile()
    }

    @Test
    fun testCustomNamespace() {
        val build = rule.build {
            androidApplication {
                android.namespace = "com.example.namespace"
                files {
                    // Update the R and BuildConfig class namespaces in MyClass.java and MyTestClass.java
                    update("src/main/java/com/example/app/MyClass.java")
                        .searchAndReplace("R", "com.example.namespace.R")
                        .searchAndReplace("com.example.app.BuildConfig", "com.example.namespace.BuildConfig")
                    update("src/androidTest/java/com/example/app/test/MyTestClass.java")
                        .searchAndReplace("com.example.app.R", "com.example.namespace.R")
                        .searchAndReplace("com.example.app.test.R", "com.example.namespace.test.R")
                        .searchAndReplace("com.example.app.BuildConfig", "com.example.namespace.BuildConfig")
                }
            }
        }
        val app = build.androidApplication()

        build.executor.run(":app:assembleDebug", ":app:assembleAndroidTest")

        app.assertApk(DEBUG) {
            applicationId().isEqualTo("com.example.namespace")
        }
        app.assertApk(ANDROIDTEST_DEBUG) {
            applicationId().isEqualTo("com.example.namespace.test")
        }
        assertThat(
            app.resolve(JAVAC)
                .resolve("debugAndroidTest/compileDebugAndroidTestJavaWithJavac/classes/com/example/namespace/test/BuildConfig.class")
        ).isFile()
    }

    @Test
    fun testCustomNamespaceForTestModule() {
        val build = rule.build {
            androidTest {
                android.namespace = "com.example.namespace"
                files {
                    // Update the R and BuildConfig class namespaces in MyClass.java
                    update("src/main/java/com/example/test/MyClass.java")
                        .searchAndReplace("R", "com.example.namespace.R")
                        .searchAndReplace("com.example.test.BuildConfig", "com.example.namespace.BuildConfig")
                }
            }
        }

        build.executor.run(":test:assembleDebug")
        build.androidTest().assertApk(DEBUG) {
            applicationId().isEqualTo("com.example.namespace")
        }
    }

    @Test
    fun testCustomTestNamespace() {
        val build = rule.build {
            androidApplication {
                android.testNamespace = "com.example.testNamespace"
                files {
                    // Update the test R class namespaces in MyTestClass.java
                    update("src/androidTest/java/com/example/app/test/MyTestClass.java")
                        .searchAndReplace("com.example.app.test.R", "com.example.testNamespace.R")
                }
            }
        }
        val app = build.androidApplication()

        build.executor.run(":app:assembleDebug", ":app:assembleAndroidTest")

        app.assertApk(DEBUG) {
            applicationId().isEqualTo("com.example.app")
        }
        app.assertApk(ANDROIDTEST_DEBUG) {
            applicationId().isEqualTo("com.example.app.test")
        }
        assertThat(
            app.resolve(JAVAC)
                .resolve("debugAndroidTest/compileDebugAndroidTestJavaWithJavac/classes/com/example/testNamespace/BuildConfig.class")
        ).isFile()
    }

    @Test
    fun testCustomNamespaceAndTestNamespace() {
        val build = rule.build {
            androidApplication {
                android {
                    namespace = "com.example.namespace"
                    testNamespace = "com.example.testNamespace"
                }
                files {
                    // Update the R and BuildConfig class namespaces in MyClass.java and MyTestClass.java
                    update("src/main/java/com/example/app/MyClass.java")
                        .searchAndReplace("R", "com.example.namespace.R")
                        .searchAndReplace("com.example.app.BuildConfig", "com.example.namespace.BuildConfig")

                    update("src/androidTest/java/com/example/app/test/MyTestClass.java")
                        .searchAndReplace("com.example.app.R", "com.example.namespace.R")
                        .searchAndReplace("com.example.app.test.R", "com.example.testNamespace.R")
                        .searchAndReplace("com.example.app.BuildConfig", "com.example.namespace.BuildConfig")
                }
            }
        }
        val app = build.androidApplication()

        build.executor.run(":app:assembleDebug", ":app:assembleAndroidTest")

        app.assertApk(DEBUG) {
            applicationId().isEqualTo("com.example.namespace")
        }
        app.assertApk(ANDROIDTEST_DEBUG) {
            applicationId().isEqualTo("com.example.namespace.test")
        }
        assertThat(
            app.resolve(JAVAC)
                .resolve("debugAndroidTest/compileDebugAndroidTestJavaWithJavac/classes/com/example/testNamespace/BuildConfig.class")
        ).isFile()
    }

    @Test
    fun testCustomEverything() {
        val build = rule.build {
            androidApplication {
                android {
                    namespace = "com.example.namespace"
                    testNamespace = "com.example.testNamespace"
                    defaultConfig {
                        applicationId = "com.example.applicationId"
                        testApplicationId = "com.example.testApplicationId"
                    }
                }
                files {
                    // Update the R and BuildConfig class namespaces in MyClass.java and MyTestClass.java
                    update("src/main/java/com/example/app/MyClass.java")
                        .searchAndReplace("R", "com.example.namespace.R")
                        .searchAndReplace("com.example.app.BuildConfig", "com.example.namespace.BuildConfig")

                    update("src/androidTest/java/com/example/app/test/MyTestClass.java")
                        .searchAndReplace("com.example.app.R", "com.example.namespace.R")
                        .searchAndReplace("com.example.app.test.R", "com.example.testNamespace.R")
                        .searchAndReplace("com.example.app.BuildConfig", "com.example.namespace.BuildConfig")
                }
            }
        }

        val app = build.androidApplication()

        build.executor.run(":app:assembleDebug", ":app:assembleAndroidTest")

        app.assertApk(DEBUG) {
            applicationId().isEqualTo("com.example.applicationId")
        }
        app.assertApk(ANDROIDTEST_DEBUG) {
            applicationId().isEqualTo("com.example.testApplicationId")
        }
        assertThat(
            app.resolve(JAVAC)
                .resolve("debugAndroidTest/compileDebugAndroidTestJavaWithJavac/classes/com/example/testNamespace/BuildConfig.class")
        ).isFile()
    }

    @Test
    fun testErrorWhenTestNamespaceEqualsNamespace() {
        val build = rule.build {
            androidApplication {
                android {
                    namespace = "com.example.app"
                    testNamespace = "com.example.app"
                }
            }
        }

        // We don't expect an error if not building a test component
        build.executor.run(":app:assembleDebug")
        val result = build.executor.expectFailure().run(":app:assembleAndroidTest")
        ScannerSubject.assertThat(result.stderr).contains(
            "namespace and testNamespace have the same value (\"com.example.app\"), which is not allowed."
        )
    }
}
