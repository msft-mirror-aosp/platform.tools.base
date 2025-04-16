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

package com.android.compose.screenshot.tasks

import org.gradle.api.NonExtensible
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

/**
 * Provides all the input properties needed to run the Screenshot test engine.
 */
@NonExtensible
interface PreviewScreenshotTestEngineInput {
    @get:InputFiles
    @get:Classpath
    val mainProjectClassDirs: ListProperty<Directory>

    @get:InputFiles
    @get:Classpath
    val mainProjectJars: ListProperty<RegularFile>

    @get:InputFiles
    @get:Classpath
    val mainRuntimeClassDirs: ListProperty<Directory>

    @get:InputFiles
    @get:Classpath
    val mainRuntimeJars: ListProperty<RegularFile>

    @get:InputFiles
    @get:Classpath
    val testProjectClassDirs: ListProperty<Directory>

    @get:InputFiles
    @get:Classpath
    val testProjectJars: ListProperty<RegularFile>

    @get:InputFiles
    @get:Classpath
    val testRuntimeClassDirs: ListProperty<Directory>

    @get:InputFiles
    @get:Classpath
    val testRuntimeJars: ListProperty<RegularFile>

    @get:InputFiles // Using InputFiles to allow nonexistent reference image directory.
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    val referenceImageDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    val sdkFontsDir: DirectoryProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    val resourceApkFile: RegularFileProperty

    @get:Input
    val namespace: Property<String>

    @get:InputFiles
    @get:Classpath
    val layoutlibDataDir: ConfigurableFileCollection

    @get:Input
    val threshold: Property<Float>

    @get:OutputDirectory
    val previewImageOutputDir: DirectoryProperty

    @get:OutputDirectory
    val diffImageOutputDir: DirectoryProperty
}
