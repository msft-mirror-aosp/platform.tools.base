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
import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.output.JarSubject
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.testutils.truth.PathSubject
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
                kotlin.android {
                    androidResources {
                        enable = true
                    }
                }
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
    fun testKmpLibraryAarContents() {
        val result = executor().run(":kmpFirstLib:bundleAndroidMainAar")
        Truth.assertThat(result.didWorkTasks).containsAtLeastElementsIn(
            listOf(
                ":kmpFirstLib:packageAndroidMainResources",
                ":kmpFirstLib:parseAndroidMainLocalResources",
                ":kmpFirstLib:generateAndroidMainRFile"
            )
        )

        project.getSubproject("kmpFirstLib").assertAar(AarSelector.NO_BUILD_TYPE) {
            textSymbolFile().contains("int string kmp_lib_string 0x0")
            publicResFile().isEmpty() // just testing its existance in the AAR

            androidResources().containsExactly(
                listOf("drawable-nodpi-v4/image.png", "values/values.xml")
            )
            androidResources().resourceAsText("values/values.xml").isEqualTo(
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
        executor().run(":kmpFirstLib:assemble")

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
        executor().run(":kmpFirstLib:bundleAndroidMainClassesToCompileJar")

        val classesJar = project.getSubproject(":kmpFirstLib")
            .getIntermediateFile(
                InternalArtifactType.COMPILE_LIBRARY_CLASSES_JAR.getFolderName()
                        + "/androidMain/bundleAndroidMainClassesToCompileJar/classes.jar"
            )
        JarSubject.assertThat(classesJar) {
            classes().containsExactly(
                "com/example/kmpfirstlib/UseR",
                "com/example/kmpfirstlib/KmpAndroidFirstLibJavaClass",
                "com/example/kmpfirstlib/KmpCommonFirstLibClass",
                "com/example/kmpfirstlib/KmpAndroidFirstLibClass",
                "com/example/kmpfirstlib/KmpAndroidActivity",
                "com/example/kmpfirstlib/R",
                "com/example/kmpfirstlib/R\$string",
                "com/example/kmpfirstlib/R\$drawable"
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

        val result = executor().run(":app:assembleDebug")
        project.getSubproject("app").assertApk(ApkSelector.DEBUG) {
            androidResources().containsExactly(
                "drawable-nodpi-v4/image.png"
            )
        }

        Truth.assertThat(result.didWorkTasks).containsAtLeastElementsIn(
            listOf(":kmpFirstLib:compileAndroidMainLibraryResources")
        )
    }

    private fun executor() = project.executor().withFailOnWarning(false) // b/455891987
}
