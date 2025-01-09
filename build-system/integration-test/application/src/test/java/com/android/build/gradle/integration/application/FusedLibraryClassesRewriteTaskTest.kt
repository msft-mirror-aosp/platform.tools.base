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

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.internal.fusedlibrary.FusedLibraryInternalArtifactType
import com.android.build.gradle.internal.fusedlibrary.FusedLibraryInternalArtifactType.CLASSES_WITH_REWRITTEN_R_CLASS_REFS
import com.android.build.gradle.internal.fusedlibrary.FusedLibraryInternalArtifactType.FUSED_R_CLASS
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import java.net.URLClassLoader

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
                                int string1 = com.example.androidLib1.R.string.androidlib1_str;
                                int string2 = com.example.androidLib2.R.string.androidlib2_str;
                            }
                        }
                    """.trimIndent()
                )
            }
            dependencies {
                implementation(project(":androidLib1"))
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

        build.executor.run(":fusedLib1:rewriteClasses")

        val rewrittenClasses = fusedLibrary.resolve(CLASSES_WITH_REWRITTEN_R_CLASS_REFS)
                .resolve("single/rewriteClasses")
                .toFile()
        val fusedLibraryRjar = fusedLibrary.resolve(FUSED_R_CLASS)
            .resolve("single/rewriteClasses/R.jar")
            .toFile()

        URLClassLoader(
            arrayOf(rewrittenClasses.toURI().toURL(), fusedLibraryRjar.toURI().toURL()),
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
                .containsExactly("androidlib1_str", "androidlib2_str")

            // Check that R class references use the fused library R Class
            try {
                val myClass = classLoader.loadClass("com.example.androidLib2.MyClass")
                val method = myClass.getMethod("methodUsingNamespacedResource")
                method.invoke(null)
            } catch (e: Exception) {
                throw AssertionError(
                    "Failed to resolve fused library R class reference", e
                )
            }
        }
    }
}
