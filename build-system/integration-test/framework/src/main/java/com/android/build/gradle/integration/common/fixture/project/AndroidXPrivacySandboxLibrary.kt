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

import com.android.build.api.dsl.LibraryExtension
import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification
import com.android.build.gradle.integration.common.fixture.dsl.DslProxy
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
import com.android.build.gradle.integration.common.truth.ApkSubject
import com.android.testutils.apk.Apk
import java.nio.file.Path

/*
 * Support for AndroidX Privacy Sandbox Library in the [GradleRule] fixture
 *
 * Under the hood this is an Android Library so the APIs is mostly the same.
 */

/**
 * Implementation of [AndroidProjectDefinition] for [LibraryExtension]
 *
 * @param path the Gradle path of the project
 * @param createMinimumProject whether to initialized default values on required properties
 */
internal class AndroidXPrivacySandboxLibraryDefinitionImpl(
    path: String,
    createMinimumProject: Boolean,
): AndroidProjectDefinitionImpl<LibraryExtension>(path) {
    init {
        applyPlugin(PluginType.ANDROIDX_PRIVACY_SANDBOX_LIBRARY)
    }

    override val android: LibraryExtension =
        DslProxy.createProxy(
            LibraryExtension::class.java,
            contentHolder,
        ).also {
            if (createMinimumProject) {
                initDefaultValues(it)
            }
        }
}

/**
 * Specialized interface for Androidx Privacy Sandbox library [AndroidProject] to use in the test
 */
interface AndroidXPrivacySandboxLibraryProject: AndroidProject<AndroidProjectDefinition<LibraryExtension>>,
    GeneratesApk, GeneratesAar

/**
 * Implementation of [AndroidXPrivacySandboxLibraryProject]
 */
internal class AndroidXPrivacySandboxLibraryImpl(
    location: Path,
    projectDefinition: AndroidProjectDefinition<LibraryExtension>,
    namespace: String,
) : AndroidProjectImpl<AndroidProjectDefinition<LibraryExtension>>(
    location,
    projectDefinition,
    namespace,
), AndroidXPrivacySandboxLibraryProject, GeneratesAar by GeneratesAarDelegate(location) {
    private val apkDelegate = GeneratesApkDelegate(location)

    override fun <R> withApk(apkSelector: ApkSelector, action: Apk.() -> R): R{
        if ((apkSelector as ApkSelectorImp).testSuite == null) {
            error("Querying a non test APK from a library project.")
        }
        return apkDelegate.withApk(apkSelector, action)
    }

    override fun assertApk(apkSelector: ApkSelector, action: ApkSubject.() -> Unit) {
        if ((apkSelector as ApkSelectorImp).testSuite == null) {
            error("Querying a non test APK from a library project.")
        }
        apkDelegate.assertApk(apkSelector, action)
    }

    override fun hasApk(apkSelector: ApkSelector): Boolean {
        if ((apkSelector as ApkSelectorImp).testSuite == null) {
            error("Querying a non test APK from a library project.")
        }
        return apkDelegate.hasApk(apkSelector)
    }

    override fun getReversibleInstance(projectModification: TemporaryProjectModification): AndroidLibraryProject =
        ReversibleAndroidXPrivacySandboxLibrary(this, projectModification)
}

/**
 * Reversible version of [AndroidXPrivacySandboxLibraryProject]
 */
internal class ReversibleAndroidXPrivacySandboxLibrary(
    parentProject: AndroidXPrivacySandboxLibraryProject,
    projectModification: TemporaryProjectModification
) : ReversibleAndroidProject<AndroidXPrivacySandboxLibraryProject, AndroidProjectDefinition<LibraryExtension>>(
    parentProject,
    projectModification
), AndroidLibraryProject,
    GeneratesApk by GeneratesApkFromParentDelegate(parentProject),
    GeneratesAar by GeneratesAarFromParentDelegate(parentProject)
