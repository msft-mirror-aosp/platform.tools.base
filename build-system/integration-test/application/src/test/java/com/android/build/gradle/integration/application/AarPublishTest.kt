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

import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.output.ZipSubject
import org.junit.Rule
import org.junit.Test
import java.io.File

/*
* Tests to verify that AARs produced from library modules in build/output/aar are in a state
* which can be published
* e.g. to a public repository like Maven, AAR contains expected file structure.
*/
class AarPublishTest {

    @get:Rule
    val rule = GradleRule.from {
        androidLibrary(":library") {
            android {
                namespace = "com.example.library"
                buildTypes {
                    named("debug") {
                        it.isTestCoverageEnabled = true
                    }
                }
            }
            files {
                add("src/main/res/values/strings.xml",
                    //language=xml
                    """
                        <resources>
                            <string name="one">Some string</string>
                        </resources>
                    """.trimIndent()
                )
            }
        }
    }

    /* Test to verify that AARs do not include Jacoco dependencies when published. */
    @Test
    fun canPublishLibraryAarWithCoverageEnabled() {
        val build = rule.build {
            androidLibrary(":library") {
                android {
                    buildFeatures {
                        buildConfig = true
                    }
                }
            }
        }
        val librarySubproject = build.androidLibrary(":library")

        build.executor.run("library:assembleDebug")

        librarySubproject.assertAar(AarSelector.DEBUG) {
            mainJar {
                classes().classDefinition("com/example/library/BuildConfig") {
                    methods().containsExactly("<init>", "<clinit>")
                    fields().containsExactly(
                        "DEBUG",
                        "LIBRARY_PACKAGE_NAME",
                        "BUILD_TYPE"
                    )
                }
            }
        }
    }

    @Test
    fun canPublishMinifiedLibraryAarWithCoverageEnabled() {
        val build = rule.build {
            androidLibrary(":library") {
                android {
                    buildTypes {
                        named("release") {
                            it.isMinifyEnabled = true
                            it.proguardFiles += File("proguard-rules.pro")
                        }
                    }
                }
                files {
                    add(
                        "src/main/java/com/example/Foo.java",
                        //language=java
                        """
                            package com.example;
                            public class Foo { }
                        """.trimIndent()
                    )
                    add(
                        "src/main/java/com/example/Bar.java",
                        //language=java
                        """
                            package com.example;
                            public class Bar { }
                        """.trimIndent()
                    )
                    add(
                        "proguard-rules.pro",
                        """
                            -keep class com.example.Foo {
                              <init>();
                            }
                        """.trimIndent()
                    )
                }
            }
        }

        build.executor.run("library:assembleRelease")

        build.androidLibrary(":library").assertAar(AarSelector.RELEASE) {
            mainJar {
                classes {
                    classDefinition("com/example/Foo") {
                        methods().containsExactly("<init>")
                        fields().isEmpty()
                    }

                    containsExactly("com/example/Foo")
                }
            }
        }
    }

    @Test
    fun aarContainsAllowedRootDirectories() {
        val build = rule.build
        build.executor.run(":library:assembleDebug")
        build.androidLibrary(":library").assertAar(AarSelector.DEBUG) {
            // Regression test for b/232117952 : this should not contain values/
            containsExactly(
                "AndroidManifest.xml",
                "R.txt",
                "classes.jar",
                "res/",
                "META-INF/"
            )
        }
    }
}
