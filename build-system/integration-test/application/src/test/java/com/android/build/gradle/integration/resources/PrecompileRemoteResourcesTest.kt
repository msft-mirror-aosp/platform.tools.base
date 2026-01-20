/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.build.gradle.integration.resources

import com.android.SdkConstants
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.MinimalSubProject
import com.android.build.gradle.integration.common.fixture.app.MultiModuleTestProject
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThatApk
import com.android.build.gradle.integration.common.truth.TruthHelper.assertWithMessage
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.shrinker.DummyContent
import com.android.build.gradle.internal.scope.InternalArtifactType.MERGED_RES
import com.android.build.gradle.internal.scope.getOutputDir
import com.android.build.gradle.options.BooleanOption
import com.android.builder.internal.aapt.v2.Aapt2RenamingConventions
import com.android.testutils.MavenRepoGenerator
import com.android.testutils.apk.Apk
import com.android.testutils.generateAarWithContent
import com.android.tools.build.apkzlib.zip.ZFile
import com.android.utils.FileUtils
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class PrecompileRemoteResourcesTest {

    private val mavenRepo = MavenRepoGenerator(
        listOf(
            MavenRepoGenerator.Library(
                "com.precompileRemoteResourcesTest.dependencyOfBrokenPublishedLib:lib:1.0",
                "aar",
                generateAarWithContent(
                    packageName = "com.precompileRemoteResourcesTest.dependencyOfBrokenPublishedLib",
                    resources = mapOf(
                        "layout/layout.xml" to """
                    <?xml version="1.0" encoding="utf-8"?>
                    <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                        android:orientation="vertical"
                        android:layout_width="match_parent"
                        android:layout_height="match_parent">

                        <TextView
                            android:layout_width="match_parent"
                            android:layout_height="match_parent"
                            android:text="@string/doesnt_exist"
                            />

                    </LinearLayout>
                """.trimIndent().toByteArray() )
                )
            ),
        )
    )

    private val publishedLib =
        MinimalSubProject.lib("com.precompileRemoteResourcesTest.publishedLib")
        .withFile(
            "src/main/res/values-v28/strings.xml",
            """<resources>
                        <string name="foo">publishedLib</string>
                        <string name="my_version_name">1.0</string>
                    </resources>""".trimIndent()
        )
        .withFile(
            "src/main/res/layout/layout_random_name.xml",
            """<?xml version="1.0" encoding="utf-8"?>
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent">
                    <TextView
                        android:layout_x="10px"
                        android:layout_y="110px"
                        android:text="test"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content" />
                    <EditText
                        android:layout_x="150px"
                        android:layout_y="100px"
                        android:width="150px"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content" />
                </LinearLayout>
                        """.trimIndent()
        )
        .withFile(
            "src/main/res/drawable-v26/ic_launcher_background.xml",
            """<?xml version="1.0" encoding="utf-8"?>
                <layer-list xmlns:android="http://schemas.android.com/apk/res/android">
                     <item
                      android:drawable="@drawable/button"/>
                </layer-list>
            """.trimIndent()
        )
        .withFile(
            "src/main/res/drawable-v26/button.xml",
            """<?xml version="1.0" encoding="utf-8"?>
                <vector xmlns:android="http://schemas.android.com/apk/res/android"
                    android:width="108dp"
                    android:height="108dp"
                    android:viewportWidth="108"
                    android:viewportHeight="108">
                </vector>
            """.trimIndent()
        )
        .withFile(
            "src/main/AndroidManifest.xml",
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    android:versionName="@com.precompileRemoteResourcesTest.publishedLib:string/my_version_name">
                </manifest>""".trimIndent()
        )

    private val localLib = MinimalSubProject.lib("com.example.localLib")
        .appendToBuild(
            """
                dependencies { implementation(project.dependencyFactory.create(null, "publishedLib-release", null, null, "aar")) }
            """.trimIndent()
        )
        .withFile(
            "src/main/res/values/strings.xml",
            """<resources>
                        <string name="from_published_lib">@string/foo</string>
                    </resources>"""
        )
        .withFile(
            "src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
            """<?xml version="1.0" encoding="utf-8"?>
                <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
                    <background android:drawable="@drawable/ic_launcher_background" />
                </adaptive-icon>
            """.trimIndent()
        )

    private val app = MinimalSubProject.app("com.example.app")
        .appendToBuild(
            """
                dependencies { implementation(project.dependencyFactory.create(null, "publishedLib-release", null, null, "aar")) }
                android {
                    buildTypes {
                        release {
                            shrinkResources = true
                            minifyEnabled true
                        }
                    }
                }
            """.trimIndent()
        )
        .withFile(
            "src/main/res/values/strings.xml",
            """<resources>
                        <string name="mystring">@string/foo</string>
                    </resources>"""
        )
        .withFile(
            "src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
            """<?xml version="1.0" encoding="utf-8"?>
                <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
                    <background android:drawable="@drawable/ic_launcher_background" />
                </adaptive-icon>
            """.trimIndent()
        )
        .withFile(
            "src/main/AndroidManifest.xml",
            """<?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application android:label="app_name" android:icon="@mipmap/ic_launcher">
                        <activity android:name="MainActivity"
                                  android:label="app_name"
                                  android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />
                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>""".trimIndent()
        )

    private val testApp =
        MultiModuleTestProject.builder()
            .subproject(":publishedLib", publishedLib)
            .subproject(":localLib", localLib)
            .subproject(":app", app)
            .build()

    @get:Rule
    val project =
        GradleTestProject.builder().fromTestApp(testApp).withAdditionalMavenRepo(mavenRepo).create()

    // ensure that the cache output of any previous run of this test has been cleared
    private fun clearPreviousTransformOutput() {
        val transformCacheDir = project.location.testLocation.gradleCacheDir

        if (!transformCacheDir.exists() || !transformCacheDir.isDirectory) {
            return
        }
        for (subdirectory in transformCacheDir.listFiles()!!) {
            if (subdirectory.isDirectory) {
                FileUtils.deleteRecursivelyIfExists(subdirectory.resolve("com.precompileRemoteResourcesTest.publishedLib"))
            }
        }
    }

    @Before
    fun setUp() {
        project.settingsFile.appendText(
                """
                    dependencyResolutionManagement {
                        repositories {
                            flatDir { dirs 'publishedLib/build/outputs/aar/' }
                        }
                    }

                """.trimIndent()
        )
        clearPreviousTransformOutput()
    }

    @Test
    fun checkAppBuild() {
        project.executor().run(":publishedLib:assembleRelease")
        project.executor().run(":app:assembleDebug")

        checkAarResourcesCompilerTransformOutput()
        checkValuesResourcedAreMerged()

        checkAarResourcesAddedToApk(project.getSubproject("app").getApk(GradleTestProject.ApkType.DEBUG))
    }

    @Test
    fun checkLocalLibBuild() {
        project.executor().run(":publishedLib:assembleRelease")
        val result = project.executor().run(":localLib:assembleRelease")

        assertThat(result.getTask(":localLib:verifyReleaseResources").didWork()).isTrue()
    }

    @Test
    fun testIntegrationWithResourceShrinker() {
        project.executor().run(":publishedLib:assembleRelease")
        project.executor().run(":app:assembleRelease")

        val compressed = InternalArtifactType.SHRUNK_RESOURCES_PROTO_FORMAT
            .getOutputDir(project.getSubproject(":app").buildDir)
            .resolve("release/minifyReleaseWithR8/shrunk-resources-proto-format-release.ap_")

        assertThat(compressed.isFile).isTrue()

        ZFile.openReadOnly(compressed).use {
            assertThat(it.get("res/layout/layout_random_name.xml")).isNull()
            assertThat(it.get("res/drawable-anydpi-v26/button.xml")!!.read()).isNotEqualTo(
                DummyContent.TINY_BINARY_XML
            )
            assertThat(it.get("res/drawable-v26/ic_launcher_background.xml")!!.read()).isNotEqualTo(
                DummyContent.TINY_BINARY_XML
            )
        }
    }

    @Test
    fun testLinkingErrorFromPrecompiledResource() {
        val lib = project.getSubproject(":localLib")
        project.executor()
            .with(BooleanOption.PRECOMPILE_DEPENDENCIES_RESOURCES, true)
            .run(":publishedLib:assembleRelease")

        val newContents = lib.buildFile
            .readText()
            .replace(
                "dependencies {",
                "dependencies {\nimplementation(\"com.precompileRemoteResourcesTest.dependencyOfBrokenPublishedLib:lib:1.0\") \n"
            )
        lib.buildFile.writeText(newContents)

        val result = project.executor().expectFailure()
            .run(":localLib:assembleRelease")
        result.assertErrorContains(
            listOf("transformed", "lib-1.0", "res", "layout", "layout.xml").joinToString(separator = File.separator)
        )
    }

    // TODO: find a better way to check the output of the transform
    private fun checkAarResourcesCompilerTransformOutput() {
        val transformCacheDir = project.location.testLocation.gradleCacheDir

        assertThat(transformCacheDir.exists()).isTrue()
        assertThat(transformCacheDir.isDirectory).isTrue()

        var outputDir: File? = null
        for (subdirectory in transformCacheDir.listFiles()!!) {
            if (subdirectory.isDirectory) {
                val windowsPlatformPrefix = if (SdkConstants.currentPlatform() == SdkConstants.PLATFORM_WINDOWS) "workspace/" else ""
                val outputDirCandidate =
                    File(subdirectory, "${windowsPlatformPrefix}transformed/com.precompileRemoteResourcesTest.publishedLib")
                if (outputDirCandidate.exists() && outputDirCandidate.isDirectory) {
                    assertWithMessage("Found more than one directory that could contain the output of the transform").that(
                        outputDir
                    ).isNull()
                    outputDir = outputDirCandidate
                }
            }
        }
        // values resources shouldn't be here
        assertThat(outputDir).isNotNull()
        assertThat(outputDir!!.listFiles()).hasLength(3)
        assertThat(outputDir.listFiles()!!.map { file -> file.name }.toSortedSet()).containsExactlyElementsIn(
            listOf(
                Aapt2RenamingConventions.compilationRename(
                    File(File("layout"), "layout_random_name.xml")
                ),
                Aapt2RenamingConventions.compilationRename(
                    File(File("drawable-v26"), "ic_launcher_background.xml")
                ),
                Aapt2RenamingConventions.compilationRename(
                    File(File("drawable-anydpi-v26"), "button.xml")
                )
            )
        )
    }

    private fun checkValuesResourcedAreMerged() {
        val mergedResDir =
            File(MERGED_RES.getOutputDir(project.getSubproject(":app").buildDir), "debug" + File.separator + "mergeDebugResources")
        assertThat(mergedResDir.listFiles()).hasLength(3)
        assertThat(mergedResDir.listFiles()!!.map { file -> file.name }.toSortedSet()).containsExactlyElementsIn(
            listOf(
                Aapt2RenamingConventions.compilationRename(
                    File(File("mipmap-anydpi-v26"), "ic_launcher.xml")
                ),
                Aapt2RenamingConventions.compilationRename(
                    File(File("values-v28"), "values-v28.xml")
                ),
                Aapt2RenamingConventions.compilationRename(
                    File(File("values"), "values.xml")
                )
            )
        )
    }

    private fun checkAarResourcesAddedToApk(apk: Apk) {
        assertThatApk(apk).containsResource("layout/layout_random_name.xml")
        assertThatApk(apk).containsResource("drawable-v26/ic_launcher_background.xml")
    }
}
