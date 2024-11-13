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

import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectFiles
import com.android.build.gradle.integration.common.truth.AarSubject
import com.android.build.gradle.integration.common.truth.ApkSubject
import com.android.testutils.apk.Aar
import com.android.testutils.apk.Apk
import java.nio.file.Path

/**
 * a version of [AndroidProject] that can reverses the changes made during a test.
 *
 * Returned by [ReversibleGradleBuild] when used with [GradleBuild.withReversibleModifications]
 */
internal class ReversibleAndroidProject(
    override val parentProject: AndroidProject<*>,
    projectModification: TemporaryProjectModification,
) : ReversibleGradleProject(
    parentProject,
    projectModification,
), AndroidProject<CommonExtension<*,*,*,*,*,*>> {

    override val namespace: String
        get() = parentProject.namespace

    override val files: AndroidProjectFiles =
        ReversibleAndroidProjectFiles(parentProject.namespace, projectModification)

    override fun <R> withApk(apkSelector: ApkSelector, action: Apk.() -> R): R =
        parentProject.withApk(apkSelector, action)

    override fun assertApk(apkSelector: ApkSelector, action: ApkSubject.() -> Unit) {
        parentProject.assertApk(apkSelector, action)
    }

    override fun hasApk(apkSelector: ApkSelector): Boolean = parentProject.hasApk(apkSelector)

    override fun <R> withAar(aarSelector: AarSelector, action: Aar.() -> R): R =
        parentProject.withAar(aarSelector, action)

    override fun assertAar(aarSelector: AarSelector, action: AarSubject.() -> Unit) {
        parentProject.assertAar(aarSelector, action)
    }

    override fun hasAar(aarSelector: AarSelector): Boolean = parentProject.hasAar(aarSelector)

    override fun getIntermediateFile(vararg paths: String?): Path = parentProject.getIntermediateFile(*paths)

    override val intermediatesDir: Path
        get() = parentProject.intermediatesDir

    override val outputsDir: Path
        get() = parentProject.outputsDir

    override fun reconfigure(
        buildFileOnly: Boolean,
        action: AndroidProjectDefinition<CommonExtension<*, *, *, *, *, *>>.() -> Unit
    ) {
        throw UnsupportedOperationException("reconfigure not supported inside a modification block")
    }
}

internal class ReversibleAndroidProjectFiles(
    override val namespace: String,
    projectModification: TemporaryProjectModification
): ReversibleProjectFiles(projectModification), AndroidProjectFiles {
    override val namespaceAsPath: String
        get() = namespaceAsPath.replace('.', '/')
}


