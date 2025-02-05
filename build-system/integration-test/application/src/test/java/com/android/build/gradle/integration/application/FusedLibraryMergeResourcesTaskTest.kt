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

import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.truth.ApkSubject
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth.assertThat
import org.gradle.api.JavaVersion
import org.junit.Rule
import org.junit.Test
import kotlin.io.path.readLines

class FusedLibraryMergeResourcesTaskTest {

    @get:Rule
    val rule = GradleRule.configure()
        .withMavenRepository {
            aar("com.remotedep.remoteaar")
                .addResource(
                    "values/strings.xml",
                    // language=XML
                    """
                        <?xml version="1.0" encoding="utf-8"?>
                        <resources>
                            <string name="string_from_remote_lib">Remote String</string>
                        </resources>
                    """.trimIndent())
        }.from {
            androidLibrary(":androidLib1") {
                android {
                    namespace = "com.example.androidLib1"
                }
                files.add(
                    "src/main/res/values/strings.xml",
                    //language=xml
                    """
                        <resources>
                            <string name="string_from_android_lib_1">androidLib1</string>
                            <string name="string_overridden">androidLib1</string>
                        </resources>
                    """.trimIndent())
            }
            androidLibrary(":androidLib3") {
                android {
                    namespace = "com.example.androidLib3"
                }
                files {
                    add(
                        "src/main/res/values/strings.xml",
                        //language=xml
                        """
                            <resources>
                                <string name="string_from_android_lib_3">androidLib3</string>
                                <string name="string_overridden">androidLib3</string>
                            </resources>
                        """.trimIndent())
                    add(
                        "src/main/res/layout/layout.xml",
                        //language=xml
                        """
                            <?xml version="1.0" encoding="utf-8"?>
                            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                                android:layout_width="match_parent"
                                android:layout_height="match_parent">

                                <TextView
                                    android:id="@+id/androidlib3_textview"
                                    android:layout_width="match_parent"
                                    android:layout_height="wrap_content"
                                    android:layout_weight="1"
                                    android:text="TextView" />
                                <TextView
                                    android:id="@+id/string_from_android_lib_1"
                                    android:layout_width="match_parent"
                                    android:layout_height="wrap_content"
                                    android:layout_weight="1"
                                    android:text="TextView" />
                            </LinearLayout>
                        """.trimIndent())
                }
                dependencies {
                    implementation(project(":androidLib1"))
                }
            }
            androidLibrary(":androidLib2") {
                android {
                    namespace = "com.example.androidLib2"
                }
                files.add(
                    "src/main/res/values/strings.xml",
                    //language=xml
                    """
                        <resources>
                            <string name="string_from_android_lib_2">androidLib2</string>
                            <string name="string_overridden">androidLib2</string>
                        </resources>
                    """.trimIndent())
            }
            fusedLibrary(":fusedLib1") {
                androidFusedLibrary {
                    namespace = "com.example.fusedLib1"
                    minSdk = 1
                }
                dependencies {
                    include(project(":androidLib2"))
                    include(project(":androidLib3"))
                    include("com.remotedep.remoteaar:remoteaar:1.0")
                }
            }
            androidApplication {
                applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)

                android {
                    namespace = "com.example.app"
                    defaultConfig {
                        minSdk = 19
                        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                    }
                    compileOptions {
                        sourceCompatibility = JavaVersion.VERSION_1_8
                        targetCompatibility = JavaVersion.VERSION_1_8
                    }
                }
                kotlin {
                    compilerOptions {
                        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
                    }
                }
                files.add(
                    "src/main/res/values/strings.xml",
                    //language=xml
                    """
                        <resources>
                        <string name="string_from_app">app</string>
                        <string name="string_overridden">app</string>
                        </resources>
                    """.trimIndent()
                )
                // Add a dependency on the fused library aar in the test if needed.
            }
            gradleProperties {
                add(BooleanOption.FUSED_LIBRARY_SUPPORT, true)
                add(BooleanOption.USE_ANDROID_X, true)
            }
        }

    @Test
    fun testMerge() {
        val build = rule.build
        build.executor.run(":fusedLib1:assemble")

        build.fusedLibrary(":fusedLib1").assertAar(AarSelector.NO_BUILD_TYPE) {
            androidResourceAsText("values/values.xml").isEqualTo(
                //language=xml
                """
                    <?xml version="1.0" encoding="utf-8"?>
                    <resources>
                        <string name="string_from_android_lib_2">androidLib2</string>
                        <string name="string_from_android_lib_3">androidLib3</string>
                        <string name="string_from_remote_lib">Remote String</string>
                        <string name="string_overridden">androidLib2</string>
                    </resources>
                """.trimIndent()
            )
            androidResourceAsText("layout/layout.xml").isEqualTo(
                //language=xml
                """
                    <?xml version="1.0" encoding="utf-8"?>
                    <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                        android:layout_width="match_parent"
                        android:layout_height="match_parent">

                        <TextView
                            android:id="@+id/androidlib3_textview"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:text="TextView" />
                        <TextView
                            android:id="@+id/string_from_android_lib_1"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:text="TextView" />
                    </LinearLayout>
                """.trimIndent()
            )
        }
    }

    @Test
    fun testAppResourceMergingWithFusedLib() {
        val build = rule.build
        val app = build.androidApplication()

        build.executor.run(":fusedLib1:assemble")
        val aarFile = build.fusedLibrary(":fusedLib1").getAarFile(AarSelector.NO_BUILD_TYPE)

        app.reconfigure {
            dependencies {
                implementation(files(aarFile))
            }
        }

        build.executor.run(":app:assembleDebug")
        app.assertApk(ApkSelector.DEBUG) {
            contains("/res/layout/layout.xml")
        }

        val incrementalMergedResDir = app
            .resolve(InternalArtifactType.MERGED_RES_INCREMENTAL_FOLDER)
            .resolve("debug/mergeDebugResources/merged.dir")
        assertThat(
            incrementalMergedResDir.resolve("values/values.xml").readLines().map(String::trim)
        ).containsExactly(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
            "<resources>",
            "<string name=\"string_from_android_lib_2\">androidLib2</string>",
            "<string name=\"string_from_android_lib_3\">androidLib3</string>",
            "<string name=\"string_from_app\">app</string>",
            "<string name=\"string_from_remote_lib\">Remote String</string>",
            "<string name=\"string_overridden\">app</string>",
            "</resources>"
        )
    }

    @Test
    fun testFusedLibraryResourcesAccessibleFromApp() {
        val build = rule.build {
            androidApplication {
                dependencies {
                    implementation(project(":fusedLib1"))
                }
            }
        }

        build.executor.run(":app:assembleDebug", ":fusedLib1:assemble")

        build.fusedLibrary(":fusedLib1").assertAar(AarSelector.NO_BUILD_TYPE) {
            textSymbolFile().isEqualTo(
                """
                    int id androidlib3_textview 0x0
                    int id string_from_android_lib_1 0x0
                    int layout layout 0x0
                    int string string_from_android_lib_2 0x0
                    int string string_from_android_lib_3 0x0
                    int string string_overridden 0x0

                """.trimIndent())
        }

        build.androidApplication().withApk(ApkSelector.DEBUG) {
            ApkSubject.assertThat(this).hasClass("Lcom/example/fusedLib1/R\$string;")

            val classes = mainDexFile.get().classes
            val rClassStrings =
                classes.get("Lcom/example/fusedLib1/R\$string;")?.fields

            assertThat(rClassStrings?.map { it.name }).containsExactly(
                "string_from_android_lib_2",
                "string_from_android_lib_3",
                "string_overridden"
            )
        }
    }
}
