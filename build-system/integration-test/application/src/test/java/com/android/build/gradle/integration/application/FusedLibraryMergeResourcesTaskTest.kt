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
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.internal.fusedlibrary.FusedLibraryConstants
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth.assertThat
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.io.path.readLines

@RunWith(Parameterized::class)
class FusedLibraryMergeResourcesTaskTest(private val publicationOnlyMode: Boolean) {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

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
                pluginCallbacks += AndroidLibraryConfigureMavenPublishCallback::class.java
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
                    //language=xml
                    """
                        <resources>
                            <string name="string_from_android_lib_2">androidLib2</string>
                            <string name="string_overridden">androidLib2</string>
                        </resources>
                    """.trimIndent())
                }
            }
            fusedLibrary(":fusedLib1") {
                pluginCallbacks += FusedLibraryConfigureMavenPublishCallback::class.java
                androidFusedLibrary {
                    namespace = "com.example.fusedLib1"
                    minSdk {
                        version = release(1)
                    }
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
                add(BooleanOption.FUSED_LIBRARY_PUBLICATION_ONLY_MODE, publicationOnlyMode)
                add(BooleanOption.USE_ANDROID_X, true)
            }
        }

    @Test
    fun testMerge() {
        val build = rule.build
        build.executor.run(":fusedLib1:assemble")

        build.fusedLibrary(":fusedLib1").assertAar(AarSelector.NO_BUILD_TYPE) {
            androidResources {
                resourceAsText("values/values.xml").isEqualTo(
                    //language=xml
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <resources>
                        <attr format="reference" name="editTextStyle"/>
                        <string name="string_from_android_lib_2">androidLib2</string>
                        <string name="string_from_android_lib_3">androidLib3</string>
                        <string name="string_from_remote_lib">Remote String</string>
                        <string name="string_overridden">androidLib2</string>
                        <declare-styleable name="ActionBarLayout">
                            <attr name="android:layout_gravity"/>
                        </declare-styleable>
                    </resources>
                """.trimIndent()
                )
                resourceAsText("layout/layout.xml").isEqualTo(
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
    }

    @Test
    fun testAppResourceMergingWithFusedLib() {
        val build = rule.build
        val app = build.androidApplication()

        build.executor.run(":fusedLib1:assemble")
        val aarFile = build.fusedLibrary(":fusedLib1").getAarLocationForCopy(AarSelector.NO_BUILD_TYPE)

        app.reconfigure {
            dependencies {
                implementation(files(aarFile))
            }
        }

        build.executor.run(":app:assembleDebug")
        app.assertApk(ApkSelector.DEBUG) {
            androidResources().containsExactly("layout/layout.xml")
        }

        val incrementalMergedResDir = app
            .resolve(InternalArtifactType.MERGED_RES_INCREMENTAL_FOLDER)
            .resolve("debug/mergeDebugResources/merged.dir")
        assertThat(
            incrementalMergedResDir.resolve("values/values.xml").readLines().map(String::trim)
        ).containsExactly(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
            "<resources>",
                "<attr format=\"reference\" name=\"editTextStyle\"/>",
                "<string name=\"string_from_android_lib_2\">androidLib2</string>",
                "<string name=\"string_from_android_lib_3\">androidLib3</string>",
                "<string name=\"string_from_app\">app</string>",
                "<string name=\"string_from_remote_lib\">Remote String</string>",
                "<string name=\"string_overridden\">app</string>",
                "<declare-styleable name=\"ActionBarLayout\">",
                    "<attr name=\"android:layout_gravity\"/>",
                "</declare-styleable>",
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

        if (!publicationOnlyMode) {
            build.executor.run(":app:assembleDebug", ":fusedLib1:assemble")
        } else {
            val failure = build.executor.expectFailure().run(":app:assembleDebug", ":fusedLib1:assemble")
            failure.assertErrorContains("No matching variant of project :fusedLib1 was found.")
            return
        }

        build.fusedLibrary(":fusedLib1").assertAar(AarSelector.NO_BUILD_TYPE) {
            textSymbolFile().isEqualTo(
                """
                    int attr editTextStyle 0x0
                    int id androidlib3_textview 0x0
                    int id string_from_android_lib_1 0x0
                    int layout layout 0x0
                    int string string_from_android_lib_2 0x0
                    int string string_from_android_lib_3 0x0
                    int string string_overridden 0x0
                    int[] styleable ActionBarLayout { 0x0 }
                    int styleable ActionBarLayout_android_layout_gravity 0
                """.trimIndent())
        }

        build.androidApplication().assertApk(ApkSelector.DEBUG) {
            classes().apply {
                classDefinition("com/example/fusedLib1/R\$string").fields().containsExactly(
                    "string_from_android_lib_2",
                    "string_from_android_lib_3",
                    "string_overridden"
                )
            }
        }
    }

    @Test
    fun testPublishedFusedLibraryAarResourcesAccessibleFromApp() {
        val build = rule.build {
            androidApplication {
                dependencies {
                    implementation("company:my-fused-library:1.0")
                }
            }
        }
        build.executor.run(
            "publishReleasePublicationToMyrepoRepository",
        )
        build.reconfigureSettings {
            addRepository("repo")
        }

        val newFolder = temporaryFolder.newFolder()
        val rTxtFile = newFolder.resolve("R.txt")
        ZipFile(build.fusedLibrary(":fusedLib1").buildDir.resolve("outputs/aar/fusedLib1.aar").toFile()).use {
            val rTxtInputStream = it.getInputStream(it.getEntry("R.txt"))
            Files.copy(rTxtInputStream, rTxtFile.toPath())
        }

        assertThat(rTxtFile.readText()).isEqualTo(
            """
                    int attr editTextStyle 0x0
                    int id androidlib3_textview 0x0
                    int id string_from_android_lib_1 0x0
                    int layout layout 0x0
                    int string string_from_android_lib_2 0x0
                    int string string_from_android_lib_3 0x0
                    int string string_overridden 0x0
                    int[] styleable ActionBarLayout { 0x0 }
                    int styleable ActionBarLayout_android_layout_gravity 0

                    """.trimIndent()
        )

        build.executor.run(":app:assembleDebug")

        build.androidApplication().assertApk(ApkSelector.DEBUG) {
            classes().apply {
                classDefinition("com/example/fusedLib1/R\$string").fields().containsExactly(
                    "string_from_android_lib_2",
                    "string_from_android_lib_3",
                    "string_overridden"
                )
            }
        }
    }

    private class FusedLibraryConfigureMavenPublishCallback: GenericCallback {
        override fun handleProject(project: Project) {
            project.plugins.apply("maven-publish")

            val publishing = project.extensions.findByType(PublishingExtension::class.java)
                ?: throw RuntimeException("Could not find extension of type PublishingExtension")
            publishing.apply {
                publications.create("release", MavenPublication::class.java) {
                    it.groupId = "company"
                    it.artifactId = "my-fused-library"
                    it.version = "1.0"
                    it.from(project.components.getByName(
                        FusedLibraryConstants.FUSED_LIBRARY_PUBLICATION_COMPONENT_NAME))
                }
                repositories {
                    it.maven {
                        it.name = "myrepo"
                        it.url = project.uri(project.layout.projectDirectory.asFile.parentFile.resolve("repo"))
                    }
                }
            }
        }
    }

    private class AndroidLibraryConfigureMavenPublishCallback : GenericCallback {

        override fun handleProject(project: Project) {
            project.plugins.apply("maven-publish")

            val publishing = project.extensions.findByType(PublishingExtension::class.java)
                ?: throw RuntimeException("Could not find extension of type PublishingExtension")
            publishing.apply {
                publications.create("release", MavenPublication::class.java)
                repositories {
                    it.maven {
                        it.name = "myrepo"
                        it.url =
                            project.uri(project.layout.projectDirectory.asFile.parentFile.resolve("repo"))
                    }
                }
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters
        fun publicationOnlyMode() = listOf(true, false)
    }
}
