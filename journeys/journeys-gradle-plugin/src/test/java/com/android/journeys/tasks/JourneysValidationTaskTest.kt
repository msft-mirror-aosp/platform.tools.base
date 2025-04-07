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

package com.android.journeys.tasks

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertFailsWith

class JourneysValidationTaskTest {

    @get:Rule
    val tempDirRule = TemporaryFolder()

    private lateinit var project: Project
    private lateinit var task: JourneysValidationTask

    @Before
    fun setUp() {
        project = ProjectBuilder.builder().withProjectDir(tempDirRule.newFolder()).build()
        task = project.tasks.create("validateJourneysTest", JourneysValidationTask::class.java)

        task.resultsDir.set(project.layout.projectDirectory.dir("results"))
    }

    @Test
    fun testJourneysWithMissingApk() {
        val apkDir = project.layout.projectDirectory.dir("apkDir")
        apkDir.asFile.mkdirs()
        task.apkDirectories.add(apkDir)

        assertFailsWith<GradleException>("No apk found to run journeysTest.") {
            task.executeTests()
        }
    }

    @Test
    fun testJourneysWithMultipleApksWithoutUniversalApk() {
        val apkDir = project.layout.projectDirectory.dir("apkDir")
        apkDir.asFile.mkdirs()
        File(apkDir.asFile, "app1.apk").createNewFile()
        File(apkDir.asFile, "app2.apk").createNewFile()

        task.apkDirectories.add(project.layout.projectDirectory.dir("apkDir"))

        assertFailsWith<GradleException>("Could not find Universal APK for journeys.") {
            task.executeTests()
        }
    }
}
