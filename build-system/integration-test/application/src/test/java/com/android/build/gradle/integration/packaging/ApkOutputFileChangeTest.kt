/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.build.gradle.integration.packaging

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.GradleTestProject.Companion.builder
import com.android.build.gradle.integration.common.fixture.app.HelloWorldApp
import com.android.build.gradle.integration.common.truth.ApkSubject
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.apk.Apk
import java.io.IOException
import org.junit.Rule
import org.junit.Test

/** Test to verify that the APK is packaged correctly when there is a change in the APK output file name. */
class ApkOutputFileChangeTest {
  @JvmField @Rule var project: GradleTestProject = builder().fromTestApp(HelloWorldApp.forPlugin("com.android.application")).create()

  @Test
  @Throws(Exception::class)
  fun testOutputFileNameChange() {
    // Run the first build
    var result =
      project
        .executor() // although we don't need USE_NEW_DSL here for correctness, having the same options
        // might be important in terms of testing the regression in b/64703619 linked below.
        .with(BooleanOption.USE_NEW_DSL, false)
        .run("assembleDebug")
    result.assertTask(":packageDebug").didWork()
    assertCorrectApk(project.getApk(GradleTestProject.ApkType.DEBUG))

    // Modify the output file name
    TestFileUtils.appendToFile(
      project.buildFile,
      ("android {\n" +
        "    android.applicationVariants.all { variant ->\n" +
        "        variant.outputs.all {\n" +
        "            outputFileName = 'foo.apk'\n" +
        "        }\n" +
        "    }\n" +
        "}\n"),
    )

    // Run the second build, check that the new APK is generated correctly (regression test for
    // https://issuetracker.google.com/issues/64703619)
    result = project.executor().with(BooleanOption.USE_NEW_DSL, false).run("assembleDebug")
    result.assertTask(":packageDebug").didWork()
    assertCorrectApk(project.getApkByFileName(GradleTestProject.ApkType.DEBUG, "foo.apk"))
  }

  companion object {
    @Throws(IOException::class)
    private fun assertCorrectApk(apk: Apk) {
      ApkSubject.assertThat(apk).exists()
      ApkSubject.assertThat(apk).contains("META-INF/MANIFEST.MF")
      ApkSubject.assertThat(apk).contains("res/layout/main.xml")
      ApkSubject.assertThat(apk).contains("AndroidManifest.xml")
      ApkSubject.assertThat(apk).contains("classes.dex")
      ApkSubject.assertThat(apk).contains("resources.arsc")
    }
  }
}
