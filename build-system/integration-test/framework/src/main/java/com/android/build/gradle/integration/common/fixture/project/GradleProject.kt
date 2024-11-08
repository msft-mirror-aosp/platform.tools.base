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

package com.android.build.gradle.integration.common.fixture.project

import com.android.SdkConstants
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.GradleTestProject.ApkType
import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.DirectAndroidProjectFilesImpl
import com.android.build.gradle.integration.common.fixture.project.builder.DirectGradleProjectFilesImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFiles
import com.android.testutils.apk.Apk
import com.android.utils.FileUtils
import java.io.File
import java.nio.file.Path

/**
 * a subproject part of a [GradleBuild]
 */
interface GradleProject: TemporaryProjectModification.FileProvider {

    val files: GradleProjectFiles

    fun getApk(
        apk: ApkType,
        vararg dimensions: String,
    ): Apk

    /** Return a File under the intermediates directory from Android plugins.  */
    fun getIntermediateFile(vararg paths: String?): File

    /** Return the output directory from Android plugins.  */
    val intermediatesDir: File
}

/**
 * a subproject part of a [GradleBuild], specifically for projects with Android plugins.
 */
interface AndroidProject: GradleProject {
    val namespace: String
    override val files: AndroidProjectFiles
}

/**
 * Default implementation of [GradleProject]
 */
internal open class GradleProjectImpl(
    private val projectDir: Path,
): GradleProject {

    override val files: GradleProjectFiles = DirectGradleProjectFilesImpl(projectDir)

    override fun getApk(
        apk: GradleTestProject.ApkType,
        vararg dimensions: String
    ): Apk {
        throw RuntimeException("TODO")
    }

    override fun file(path: String): File? {
        return projectDir.resolve(path).toFile()
    }

    override fun getIntermediateFile(vararg paths: String?): File {
        return FileUtils.join(intermediatesDir, *paths)
    }

    override val intermediatesDir: File
        get() = projectDir.resolve("build/${SdkConstants.FD_INTERMEDIATES}").toFile()
}

/**
 * Default implementation of [AndroidProject]
 */
internal class AndroidProjectImpl(
    location: Path,
    override val namespace: String
): GradleProjectImpl(location), AndroidProject {

    override val files: AndroidProjectFiles = DirectAndroidProjectFilesImpl(location, namespace)
}


