/*
 * Copyright (C) 2025 The Android Open Source Project
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
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.utils.FileUtils
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class KotlinMultiplatformAndroidAnnotationsZipTest {

    @get:Rule
    val project = GradleTestProjectBuilder()
        .fromTestProject("kotlinMultiplatform")
        .disableBuiltInKotlin()
        .create()

    @Before
    fun setUp() {
        TestFileUtils.appendToFile(
            project.getSubproject("kmpFirstLib").ktsBuildFile,
            """
                kotlin.androidLibrary {
                   withJava()
                }
                dependencies {
                    add("commonMainImplementation", "androidx.annotation:annotation:1.1.0")
                }
            """.trimIndent())

        FileUtils.writeToFile(
            project.getSubproject("kmpFirstLib")
                .file("src/commonMain/java/ViewCompatShims.java"),
            """
                package com.example.kmpfirstlib;

                import androidx.annotation.IntDef;

                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                public class ViewCompatShims {
                    @IntDef({
                            IMPORTANT_FOR_CONTENT_CAPTURE_AUTO,
                            IMPORTANT_FOR_CONTENT_CAPTURE_YES,
                            IMPORTANT_FOR_CONTENT_CAPTURE_NO,
                            IMPORTANT_FOR_CONTENT_CAPTURE_YES_EXCLUDE_DESCENDANTS,
                            IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS,
                    })
                    @Retention(RetentionPolicy.SOURCE)
                    private @interface ImportantForContentCapture {}

                    public static final int IMPORTANT_FOR_CONTENT_CAPTURE_AUTO = 0x0;
                    public static final int IMPORTANT_FOR_CONTENT_CAPTURE_YES = 0x1;
                    public static final int IMPORTANT_FOR_CONTENT_CAPTURE_NO = 0x2;
                    public static final int IMPORTANT_FOR_CONTENT_CAPTURE_YES_EXCLUDE_DESCENDANTS = 0x4;
                    public static final int IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS = 0x8;

                    public static void setImportantForContentCapture(String s,
                            @ImportantForContentCapture int mode) {
                            System.out.println(s + mode);
                    }
                }
            """.trimIndent()
        )
    }

    @Test
    fun testExtractAnnotationsTaskRuns() {
        val result = project.executor()
            .withFailOnWarning(false) // b/455891987
            .run(":kmpFirstLib:bundleAndroidMainAar")
        Truth.assertThat(result.didWorkTasks).containsAtLeastElementsIn(
            listOf(
                ":kmpFirstLib:extractAndroidMainAnnotations"
            )
        )
    }

    @Test
    fun testLibraryAarContents() {
        project.executor()
            .withFailOnWarning(false) // b/455891987
            .run(":kmpFirstLib:bundleAndroidMainAar")

        project.getSubproject("kmpFirstLib").assertAar(AarSelector.NO_BUILD_TYPE) {
            contains("annotations.zip")
        }
    }
}
