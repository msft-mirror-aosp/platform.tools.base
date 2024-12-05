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
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.io.File
import kotlin.io.path.readBytes

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

        val classesBytes = librarySubproject.withAar(AarSelector.DEBUG) {
            getEntryAsZip("classes.jar").use { classes ->
                classes.getEntryAsFile("com/example/library/BuildConfig.class").readBytes()
            }
        }

        val classNode = ClassNode(Opcodes.ASM9)
        ClassReader(classesBytes).accept(classNode, 0)
        assertThat(classNode.methods.map { it.name }).containsExactly("<init>", "<clinit>")
        assertThat(classNode.fields.map { it.name }).containsExactly(
            "DEBUG",
            "LIBRARY_PACKAGE_NAME",
            "BUILD_TYPE"
        )
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

        build.androidLibrary(":library").withAar(AarSelector.RELEASE) {
            getEntryAsZip("classes.jar").use { classesJar ->
                val classNode = ClassNode(Opcodes.ASM9)
                ClassReader(classesJar.getEntry("com/example/Foo.class").readBytes()).accept(
                    classNode,
                    0
                )
                assertThat(classNode.methods.map { it.name }).containsExactly("<init>")
                assertThat(classNode.fields).isEmpty()
                assertThat(classesJar.getEntry("com/example/Bar.class")).isNull()
            }
        }
    }

    @Test
    fun aarContainsAllowedRootDirectories() {
        val build = rule.build
        build.executor.run(":library:assembleDebug")
        build.androidLibrary(":library").assertAar(AarSelector.DEBUG) {
            containsFile("/AndroidManifest.xml")
            containsFile("/R.txt")
            containsFile("/classes.jar")
            containsFile("/res/values/values.xml")
            containsFile("META-INF/com/android/build/gradle/aar-metadata.properties")
            // Regression test for b/232117952
            doesNotContain("/values/")
        }
    }
}
