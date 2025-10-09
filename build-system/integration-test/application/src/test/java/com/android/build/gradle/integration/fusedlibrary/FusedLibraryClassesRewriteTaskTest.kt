/*
 * Copyright (C) 2022 The Android Open Source Project
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
import com.android.build.gradle.internal.fusedlibrary.FusedLibraryInternalArtifactType.CLASSES_WITH_REWRITTEN_R_CLASS_REFS
import com.android.build.gradle.internal.fusedlibrary.FusedLibraryInternalArtifactType.FUSED_R_CLASS
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth.assertThat
import org.jetbrains.kotlin.gradle.utils.`is`
import org.junit.Rule
import org.junit.Test
import java.net.URLClassLoader
import kotlin.test.assertFailsWith

internal class FusedLibraryClassesRewriteTaskTest {
    @get:Rule
    val rule = GradleRule.from {
        androidLibrary(":androidLib1") {
            android {
                namespace = "com.example.androidLib1"
            }
            files {
                add(
                    "src/main/res/values/strings.xml",
                    //language=xml
                    """
                        <resources>
                            <string name="androidlib1_str">A string from androidLib1</string>
                        </resources>
                    """.trimIndent()
                )
                add("src/main/layout/main_activity.xml", "<root></root>")
            }
        }
        androidLibrary(":androidLib2") {
            android {
                namespace = "com.example.androidLib2"
            }
            files {
                add(
                    "src/main/res/values/strings.xml",
                    //language=xml
                    """
                        <resources>
                            <string name="androidlib2_str">A string from androidLib2</string>
                        </resources>
                    """.trimIndent()
                )
                add(
                    "src/main/java/com/example/androidLib2/MyClass.java",
                    // language=JAVA
                    """
                        package com.example.androidLib2;
                        public class MyClass {
                            public static void methodUsingNamespacedResource() {
                                // The below resource references have definitions that will be
                                // included in the fused library, so they're R class references
                                // will be rewriten to the fused library R class.
                                int string1 = com.example.androidLib1.R.string.androidlib1_str;
                                int string2 = com.example.androidLib2.R.string.androidlib2_str;

                                // The below resource references have definitions that will
                                // not be included in the fused library, so their R class reference
                                // will be untouched.
                                int string3 = com.example.dependencyLib3.R.string.dependencyLib3_str;
                                int styleable = com.example.dependencyLib3.R.styleable.ActionBarLayout_android_layout_gravity;
                                int editTextStyleAttrFromdependencyLib3 = com.example.dependencyLib3.R.attr.editTextStyle;
                            }
                        }
                    """.trimIndent()
                )
            }
            dependencies {
                implementation(project(":androidLib1"))
                implementation(project(":dependencyLib3"))
            }
        }
        androidLibrary(":dependencyLib3") {
            android {
                namespace = "com.example.dependencyLib3"
            }
            files {
                add(
                    "src/main/res/values/values.xml",
                    """
                        <?xml version="1.0" encoding="utf-8"?>
                        <resources>
                            <declare-styleable name="ActionBarLayout">
                                <attr name="android:layout_gravity"/>
                            </declare-styleable>
                            <attr format="reference" name="editTextStyle"/>
                        </resources>
                    """.trimIndent()
                )
                add(
                    "src/main/res/values/strings.xml",
                    """
                        <?xml version="1.0" encoding="utf-8"?>
                        <resources>
                            <string name="dependencyLib3_str">A string from dependencyLib3</string>
                        </resources>
                    """.trimIndent()
                )
            }
        }
        fusedLibrary(":fusedLib1") {
            androidFusedLibrary {
                namespace = "com.example.fusedLib1"
            }
            dependencies {
                include(project(":androidLib1"))
                include(project(":androidLib2"))
            }
        }
        gradleProperties {
            add(BooleanOption.FUSED_LIBRARY_SUPPORT, true)
        }
    }

    @Test
    fun rewritesUnderFusedRClass() {
        val build = rule.build
        val fusedLibrary = build.fusedLibrary(":fusedLib1")
        val dependencyLib3 = build.androidLibrary(":dependencyLib3")

        build.executor.run(":dependencyLib3:assembleRelease")
        build.executor.run(":fusedLib1:rewriteClasses")

        val rewrittenClasses = fusedLibrary.resolve(CLASSES_WITH_REWRITTEN_R_CLASS_REFS)
                .resolve("single/rewriteClasses")
        val fusedLibraryRjar = fusedLibrary.resolve(FUSED_R_CLASS)
            .resolve("single/rewriteClasses/R.jar")
        val dependencyLib3RJar = dependencyLib3
            .resolve(InternalArtifactType.COMPILE_R_CLASS_JAR)
            .resolve("release/generateReleaseRFile/R.jar")

        URLClassLoader(
            arrayOf(
                rewrittenClasses.toUri().toURL(),
                fusedLibraryRjar.toUri().toURL(),
                dependencyLib3RJar.toUri().toURL()
            ),
            null
        ).use { classLoader ->
            // Check fused library R class generated and contains fields.
            val fusedLibraryRStringsClass =
                classLoader.loadClass("com.example.fusedLib1.R\$string")
            val fusedLibraryRClassStringFieldNames =
                (fusedLibraryRStringsClass.declaredFields).map { it.name }
                    // Ignore Jacoco instrumentation injected by studio-coverage.
                    .minus("\$jacocoData")
            assertThat(fusedLibraryRClassStringFieldNames)
                .containsExactly(
                    // Resources packaged in the fused library we expect to reference the fused
                    // library R class.
                    "androidlib1_str", "androidlib2_str")
            val dependencyLib3RStringsClass =
                classLoader.loadClass("com.example.dependencyLib3.R\$string")
            val dependencyLib3RClassStringFieldNames =
                (dependencyLib3RStringsClass.declaredFields).map { it.name }
                    // Ignore Jacoco instrumentation injected by studio-coverage.
                    .minus("\$jacocoData")
            assertThat(dependencyLib3RClassStringFieldNames).containsExactly("dependencyLib3_str")
            assertThat(fusedLibraryRClassStringFieldNames)
                .doesNotContain(
                    // Resources not packaged in the fused library that reference their dependency
                    // R class.
                    "dependencyLib3_str")

            // Check that R class references use the fused library R Class
            val myClass = classLoader.loadClass("com.example.androidLib2.MyClass")
            val method = myClass.getMethod("methodUsingNamespacedResource")
            method.invoke(null)
        }

        URLClassLoader(
            arrayOf(
                rewrittenClasses.toUri().toURL(),
                fusedLibraryRjar.toUri().toURL(),
                // This time, don't add the dependencyLib3 R.jar to the classpath.
            ),
            null
        ).also { classLoader ->
            val exception = assertFailsWith<Exception>() {
                val myClass = classLoader.loadClass("com.example.androidLib2.MyClass")
                val method = myClass.getMethod("methodUsingNamespacedResource")
                method.invoke(null)
            }
            assertThat(exception.cause).`is`(ClassNotFoundException::class.java)
            assertThat(exception.cause?.message).isEqualTo("com/example/dependencyLib3/R\$string")
        }
    }
}
