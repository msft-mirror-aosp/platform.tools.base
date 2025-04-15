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

import com.android.SdkConstants
import com.android.SdkConstants.FN_NAVIGATION_JSON
import com.android.build.api.artifact.SingleArtifact
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.MinimalSubProject
import com.android.build.gradle.integration.common.fixture.app.MultiModuleTestProject
import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.testutils.truth.PathSubject
import com.android.utils.FileUtils
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class NavigationPlaceholderTest {

    private val fooPlaceholder = "\${foo}"
    private val hostPlaceholder = "\${host}"
    private val schemePlaceholder = "\${scheme}"
    private val appIdPlaceholder = "\${applicationId}"

    private val app =
        MinimalSubProject.app("com.example.app")
            .appendToBuild(
                """
                    android {
                        defaultConfig {
                            manifestPlaceholders =
                                [
                                    foo: "appFoo",
                                    scheme: "appScheme",
                                    host: "app.example.com",
                                ]
                        }
                    }
                """.trimIndent()
            )
            .withFile(
                "src/main/AndroidManifest.xml",
                """
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                        <application>
                            <activity android:name="MyActivity">
                                <nav-graph android:value="@navigation/nav_app"/>
                                <nav-graph android:value="@navigation/nav_lib"/>
                            </activity>
                            <meta-data
                                android:name="app"
                                android:value="$fooPlaceholder"/>
                        </application>
                    </manifest>
                """.trimMargin()
            )
            .withFile(
                "src/main/res/navigation/nav_app.xml",
                """
                    <navigation xmlns:app="http://schemas.android.com/apk/res-auto">
                        <deepLink
                            app:uri="$schemePlaceholder://$hostPlaceholder/$appIdPlaceholder"/>
                    </navigation>
                """.trimIndent()
            )

    private val lib =
        MinimalSubProject.lib("com.example.lib")
            .appendToBuild(
                """
                    android {
                        defaultConfig {
                            manifestPlaceholders =
                                [
                                    foo: "libFoo",
                                    host: "lib.example.com",
                                    scheme: "libScheme",
                                ]
                        }
                    }
                """.trimIndent()
            )
            .withFile(
                "src/main/AndroidManifest.xml",
                """
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                        <application>
                            <meta-data
                                android:name="lib"
                                android:value="$fooPlaceholder"/>
                        </application>
                    </manifest>
                """.trimMargin()
            )
            .withFile(
                "src/main/res/navigation/nav_lib.xml",
                """
                    <navigation xmlns:app="http://schemas.android.com/apk/res-auto">
                        <deepLink
                            app:uri="$schemePlaceholder://$hostPlaceholder/$appIdPlaceholder"/>
                    </navigation>
                """.trimIndent()
            )

    @get:Rule
    val project =
        GradleTestProject.builder()
            .fromTestApp(
                MultiModuleTestProject.builder()
                    .subproject(":app", app)
                    .subproject(":lib", lib)
                    .dependency(app, lib)
                    .dependency(app, "androidx.navigation:navigation-fragment:2.5.2")
                    .dependency(lib, "androidx.navigation:navigation-fragment:2.5.2")
                    .build()
            )
            .create()

    @Before
    fun setUp() {
        TestFileUtils.appendToFile(
            project.gradlePropertiesFile,
            """
                android.useAndroidX=true
            """.trimIndent()
        )
    }


    @Test
    fun testNavigationPlaceholders() {
        project.executor().run(":app:assembleDebug")
        val mergedManifest =
            project.file(
                "app/build/${SdkConstants.FD_INTERMEDIATES}/${SingleArtifact.MERGED_MANIFEST.getFolderName()}/debug/processDebugMainManifest/AndroidManifest.xml"
            )

        PathSubject.assertThat(mergedManifest)
            .contentWithUnixLineSeparatorsIsExactly(
                getExpectedMergedManifestContent(
                    expectedLibScheme = "libScheme",
                    expectedLibHost = "lib.example.com",
                    expectedMetaDataLibValue = "libFoo"
                )
            )
        val navigationJsonLib =
            project.file(
                "lib/build/${SdkConstants.FD_INTERMEDIATES}/navigation_json/debug/extractDeepLinksDebug/navigation.json"
            )
        PathSubject.assertThat(navigationJsonLib).contains("\"libScheme\"")
        PathSubject.assertThat(navigationJsonLib).contains("\"path\": \"/$appIdPlaceholder\"")
        PathSubject.assertThat(navigationJsonLib).contains("\"host\": \"lib.example.com\"")

        val navigationJson =
            project.file(
                "app/build/${SdkConstants.FD_INTERMEDIATES}/navigation_json/debug/extractDeepLinksDebug/navigation.json"
            )
        PathSubject.assertThat(navigationJson).contains("\"appScheme\"")
        PathSubject.assertThat(navigationJson).contains("\"path\": \"/com.example.app\"")
        PathSubject.assertThat(navigationJson).contains("\"host\": \"app.example.com\"")

        // navigation lib when mergeResources the library - having all substitutions except applicationId
        val navigationLib =
            project.file(
                "lib/build/${SdkConstants.FD_GENERATED}/updated_navigation_xml/debug/navigation/nav_lib.xml"
            )
        PathSubject.assertThat(navigationLib).contains("app:uri=\"libScheme://lib.example.com/$appIdPlaceholder\" />")

        // app navigation - all substitutions are done including applicationId
        val navigation =
            project.file(
                "app/build/${SdkConstants.FD_GENERATED}/updated_navigation_xml/debug/navigation/nav_app.xml"
            )
        PathSubject.assertThat(navigation).contains("app:uri=\"appScheme://app.example.com/com.example.app\" />")

        // lib navigation we collect during app mergeResources - all substitutions are done including applicationId
        val navigationLibFromApp =
            project.file(
                "app/build/${SdkConstants.FD_GENERATED}/updated_navigation_xml/debug/navigation/nav_lib.xml"
            )
        PathSubject.assertThat(navigationLibFromApp).contains("app:uri=\"libScheme://lib.example.com/com.example.app\" />")
    }

    @Test
    fun testNavigationPlaceholders_withoutLibManifestPlaceholders() {
        TestFileUtils.searchAndReplace(
            project.getSubproject("lib").buildFile,
            """
                |        manifestPlaceholders =
                |            [
                |                foo: "libFoo",
                |                host: "lib.example.com",
                |                scheme: "libScheme",
                |            ]
            """.trimMargin(),
            ""
        )
        project.executor().run(":app:assembleDebug")
        val mergedManifest =
            project.file(
                "app/build/${SdkConstants.FD_INTERMEDIATES}/${SingleArtifact.MERGED_MANIFEST.getFolderName()}/debug/processDebugMainManifest/AndroidManifest.xml"
            )

        PathSubject.assertThat(mergedManifest)
            .contentWithUnixLineSeparatorsIsExactly(
                getExpectedMergedManifestContent(
                    expectedLibScheme = "appScheme",
                    expectedLibHost = "app.example.com",
                    expectedMetaDataLibValue = "appFoo"
                )
            )
        // does not have variables to substitute placeholder in lib xml when merging lib resources
        val navigationLib =
            project.file(
                "lib/build/${SdkConstants.FD_GENERATED}/updated_navigation_xml/debug/navigation/nav_lib.xml"
            )
        PathSubject.assertThat(navigationLib).contains("app:uri=\"$schemePlaceholder://$hostPlaceholder/$appIdPlaceholder\" />")


        val navigation =
            project.file(
                "app/build/${SdkConstants.FD_GENERATED}/updated_navigation_xml/debug/navigation/nav_app.xml"
            )
        PathSubject.assertThat(navigation).contains("app:uri=\"appScheme://app.example.com/com.example.app\" />")

        val navigationLibFromApp =
            project.file(
                "app/build/${SdkConstants.FD_GENERATED}/updated_navigation_xml/debug/navigation/nav_lib.xml"
            )
        PathSubject.assertThat(navigationLibFromApp).contains("app:uri=\"appScheme://app.example.com/com.example.app\" />")
    }

    /**
     * Similar to [testNavigationPlaceholders], but we first build an AAR from lib. Regression test
     * for Issue 184874605.
     */
    @Test
    fun testNavigationPlaceholders_withAarDependency() {
        // Add a directory and build.gradle file for the AAR.
        val libAarDir = File(project.projectDir, "lib-aar").also { it.mkdirs() }
        File(libAarDir, "build.gradle").writeText(
            """
                configurations.maybeCreate("default")
                artifacts.add("default", file('lib.aar'))
            """.trimIndent()
        )
        // Build AAR, check that it has expected navigation.json entry, and copy it to libAarDir.
        project.executor().run(":lib:assembleDebug")
        project.getSubproject("lib").assertAar(AarSelector.DEBUG) {
            textFile(FN_NAVIGATION_JSON).contains("\"libScheme\"")
            textFile(FN_NAVIGATION_JSON).contains("\"path\": \"/$appIdPlaceholder\"")
            textFile(FN_NAVIGATION_JSON).contains("\"host\": \"lib.example.com\"")
        }
        val aarPath = project.getSubproject("lib").getAarLocationForCopy(AarSelector.DEBUG)
        FileUtils.copyFile(aarPath.toFile(), File(libAarDir, "lib.aar"))

        // Update the app's build.gradle and the settings.gradle.
        TestFileUtils.searchAndReplace(
            project.getSubproject("app").buildFile,
            "implementation project(':lib')",
            "implementation project(':lib-aar')",
        )
        TestFileUtils.appendToFile(project.settingsFile, "include ':lib-aar'")

        // Finally, create the app merged manifest and check its contents.
        project.executor().run(":app:assembleDebug")
        val mergedManifest =
            project.file(
                "app/build/${SdkConstants.FD_INTERMEDIATES}/${SingleArtifact.MERGED_MANIFEST.getFolderName()}/debug/processDebugMainManifest/AndroidManifest.xml"
            )
        PathSubject.assertThat(mergedManifest)
            .contentWithUnixLineSeparatorsIsExactly(
                getExpectedMergedManifestContent(
                    expectedLibScheme = "libScheme",
                    expectedLibHost = "lib.example.com",
                    expectedMetaDataLibValue = "libFoo"
                )
            )
        val navigation =
            project.file(
                "app/build/${SdkConstants.FD_GENERATED}/updated_navigation_xml/debug/navigation/nav_app.xml"
            )
        PathSubject.assertThat(navigation).exists()
        PathSubject.assertThat(navigation).contains("app:uri=\"appScheme://app.example.com/com.example.app\" />")

        val navigationLibFromApp =
            project.file(
                "app/build/${SdkConstants.FD_GENERATED}/updated_navigation_xml/debug/navigation/nav_lib.xml"
            )
        PathSubject.assertThat(navigationLibFromApp).exists()
        PathSubject.assertThat(navigationLibFromApp).contains("app:uri=\"libScheme://lib.example.com/com.example.app\" />")

    }

    // b/206665657 this test is to ensure that the correct error is thrown when a non-XML file is
    // present in the navigation folder
    @Test
    fun testNonXmlFile() {
        val txtFile =
            project.getSubproject("app").file("src/main/res/navigation/text_file.txt")
        txtFile.createNewFile()
        txtFile.writeText("text")

        val result = project.executor().expectFailure().run(":app:processDebugNavigationResources")
        ScannerSubject.assertThat(result.stderr).contains("The file name must end with .xml")
    }

    private fun getExpectedMergedManifestContent(
        expectedLibScheme: String,
        expectedLibHost: String,
        expectedMetaDataLibValue: String
    ): String =
        """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.app"
                android:versionCode="1" >

                <uses-sdk
                    android:minSdkVersion="14"
                    android:targetSdkVersion="14" />

                <application
                    android:appComponentFactory="androidx.core.app.CoreComponentFactory"
                    android:debuggable="true"
                    android:extractNativeLibs="true" >
                    <activity android:name="com.example.app.MyActivity" >
                        <intent-filter>
                            <action android:name="android.intent.action.VIEW" />

                            <category android:name="android.intent.category.DEFAULT" />
                            <category android:name="android.intent.category.BROWSABLE" />

                            <data android:scheme="appScheme" />
                            <data android:host="app.example.com" />
                            <data android:path="/com.example.app" />
                        </intent-filter>
                        <intent-filter>
                            <action android:name="android.intent.action.VIEW" />

                            <category android:name="android.intent.category.DEFAULT" />
                            <category android:name="android.intent.category.BROWSABLE" />

                            <data android:scheme="$expectedLibScheme" />
                            <data android:host="$expectedLibHost" />
                            <data android:path="/com.example.app" />
                        </intent-filter>
                    </activity>

                    <meta-data
                        android:name="app"
                        android:value="appFoo" />
                    <meta-data
                        android:name="lib"
                        android:value="$expectedMetaDataLibValue" />

                    <uses-library
                        android:name="androidx.window.extensions"
                        android:required="false" />
                    <uses-library
                        android:name="androidx.window.sidecar"
                        android:required="false" />
                </application>

            </manifest>
        """.trimIndent()
}
