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

package com.android.build.gradle.integration.multiplatform.v2

import com.android.build.gradle.integration.common.fixture.GradleTestProjectBuilder
import com.android.build.gradle.integration.common.output.AarSubject
import com.android.build.gradle.integration.common.output.JarSubject
import com.android.build.gradle.integration.common.output.ZipSubject
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.testutils.truth.PathSubject
import com.android.testutils.truth.ZipFileSubject
import com.android.utils.FileUtils
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class KotlinMultiplatformResourcesTest {

    @get:Rule
    val project = GradleTestProjectBuilder()
        .fromTestProject("kotlinMultiplatform")
        .create()

    @Before
    fun setUp() {
        TestFileUtils.appendToFile(
            project.getSubproject("kmpFirstLib").ktsBuildFile,
            """
                kotlin.androidLibrary {
                    experimentalProperties["android.experimental.kmp.enableAndroidResources"] = true
                }
            """.trimIndent()
        )

        FileUtils.writeToFile(
            project.getSubproject("kmpFirstLib").file("src/androidMain/res/values/strings.xml"),
            """
                <resources>
                    <string name="kmp_lib_string">lib string</string>
                </resources>
            """.trimIndent()
        )

        FileUtils.writeToFile(
            project.getSubproject("kmpFirstLib")
                .file("src/androidMain/kotlin/com/example/kmpfirstlib/UseR.kt"),
            """
                package com.example.kmpfirstlib;

                class UseR {
                    fun getStringResourceValue() = R.string.kmp_lib_string
                }
            """.trimIndent()
        )
    }

    @Test
    fun testKmpLibraryResourceTasksExecuted() {
        val result = project.executor().run(":kmpFirstLib:assemble")
        Truth.assertThat(result.didWorkTasks).containsAtLeastElementsIn(
            listOf(
                ":kmpFirstLib:packageAndroidMainResources",
                ":kmpFirstLib:parseAndroidMainLocalResources",
                ":kmpFirstLib:generateAndroidMainRFile"
            )
        )
    }

    @Test
    fun testLibraryAarContents() {
        project.executor().run(":kmpFirstLib:assemble")

        val aarPath = project.getSubproject("kmpFirstLib")
            .getOutputFile("aar", "kmpFirstLib.aar")
            .toPath()

        AarSubject.assertThat(aarPath) {
            textSymbolFile().contains("int string kmp_lib_string 0x0")

            androidResources().textFile("values/values.xml").isEqualTo(
                """
                    <?xml version="1.0" encoding="utf-8"?>
                    <resources>
                        <string name="kmp_lib_string">lib string</string>
                    </resources>
                """.trimIndent()
            )
        }
    }

    @Test
    fun checkResourcesArtifacts() {
        project.executor().run(":kmpFirstLib:assemble")

        val lib = project.getSubproject(":kmpFirstLib")

        val rDef = lib.getIntermediateFile("local_only_symbol_list", "androidMain", "parseAndroidMainLocalResources", "R-def.txt")
        PathSubject.assertThat(rDef).contains("string kmp_lib_string")

        val compileR = lib.getIntermediateFile("compile_r_class_jar", "androidMain", "generateAndroidMainRFile", "R.jar")
        PathSubject.assertThat(compileR).exists()

        val compileRTxt = lib.getIntermediateFile("compile_symbol_list", "androidMain", "generateAndroidMainRFile", "R.txt")
        PathSubject.assertThat(compileRTxt).exists()
        PathSubject.assertThat(compileRTxt).contains("int string kmp_lib_string 0x0")
    }

    @Test
    fun testRclassPackagedInCompileClassesJar() {
        project.executor().run(":kmpFirstLib:bundleAndroidMainClassesToCompileJar")

        val classesJar = project.getSubproject(":kmpFirstLib")
            .getIntermediateFile(
                InternalArtifactType.COMPILE_LIBRARY_CLASSES_JAR.getFolderName()
                        + "/androidMain/bundleAndroidMainClassesToCompileJar/classes.jar"
            )
        JarSubject.assertThat(classesJar) {
            classes().containsAtLeast(
                "com/example/kmpfirstlib/UseR",
                "com/example/kmpfirstlib/R",
                "com/example/kmpfirstlib/R\$string"
            )
        }
    }

    @Test
    fun testAppConsumeKmpResource() {
        FileUtils.writeToFile(
            project.getSubproject("app")
                .file("src/main/java/com/example/app/UseKmpResource.java"),
            """
                package com.example.app;

                public class UseKmpResource {
                    public static int getKmpLibStringValue() {
                        return com.example.kmpfirstlib.R.string.kmp_lib_string;
                    }
                }
            """.trimIndent()
        )

        project.executor().run(":app:assembleDebug")
    }
}
