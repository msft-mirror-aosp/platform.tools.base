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

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.TestUtils
import com.android.utils.FileUtils
import org.junit.Rule
import org.junit.Test
import java.util.Scanner

/** Tests the error message rewriting logic.  */
class MessageRewriteTest {

    @get:Rule
    val project = GradleTestProject.builder().fromTestProject("flavoredlib").create()

    @Test
    fun invalidAppLayoutFile() {
        project.executor().run("assembleDebug")
        project.getSubproject(":app").mainResDir.resolve("layout/main.xml").let {
            it.writeText(it.readText().replace("</LinearLayout>", ""))
            TestUtils.waitForFileSystemTick()
        }
        project.executor()
            .with(BooleanOption.IDE_INVOKED_FROM_IDE, true)
            .expectFailure()
            .run("assembleF1Debug")
            .let { result ->
                val path = FileUtils.join("app", "src", "main", "res", "layout", "main.xml")
                checkPathInOutput(path, result.stdout)
            }
    }

    @Test
    fun nonExistentResourceReferenceInAppLayout() {
        project.getSubproject(":app").mainResDir.resolve("layout/main.xml").let {
            it.writeText(it.readText().replace("@string/app_string", "@string/agloe"))
            TestUtils.waitForFileSystemTick()
        }
        project.executor().expectFailure().run("assembleDebug").let { result ->
            val path = FileUtils.join("app", "src", "main", "res", "layout", "main.xml")
            checkPathInOutput(path, result.stdout)
        }
    }

    @Test
    fun nonExistentResourceReferenceInAppValues() {
        project.getSubproject(":app").mainResDir.resolve("values/strings.xml").let {
            it.writeText(it.readText().replace("string", ""))
            TestUtils.waitForFileSystemTick()
        }
        project.executor().expectFailure().run("assembleDebug").let { result ->
            val path = FileUtils.join("app", "src", "main", "res", "values", "strings.xml")
            checkPathInOutput(path, result.stderr)
        }
    }

    @Test
    fun nonExistentResourceReferenceInLibLayout() {
        project.getSubproject(":lib").projectDir.resolve("src/flavor1/res/layout/lib_main.xml").let {
            it.writeText(it.readText().replace("@string/lib_string", "@string/agloe"))
            TestUtils.waitForFileSystemTick()
        }
        project.executor().expectFailure().run("assembleDebug").let { result ->
            // b/206624424 - Errors in libraries currently (and incorrectly) rewrite as
            // the packaged res for full builds and merged intermediate filepaths for incremental
            // builds.
            val path = FileUtils.join("lib", "build", "intermediates", "packaged_res",
                "flavor1Debug", "packageFlavor1DebugResources", "layout", "lib_main.xml")
            checkPathInOutput(path, result.stdout)
        }
    }

    private fun checkPathInOutput(path: String, output: Scanner) =
        output.use { out -> ScannerSubject.assertThat(out).contains(path) }
}
