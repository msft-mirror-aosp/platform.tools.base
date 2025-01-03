/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.build.gradle.integration.api

import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyApplicationCallback
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText

/**
 * Regression test for b/237783279
 */
class SourceSetsMixedApiUseTest {

    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            android {
                namespace = "com.example.api.use"
                defaultConfig.applicationId = "com.example.api.use"
            }
            pluginCallbacks += MyCallback::class.java
        }
    }

    class MyCallback: LegacyApplicationCallback {
        override fun handleExtension(
            project: Project,
            extension: BaseAppModuleExtension
        ) {
            // Eager / global registration (for comparison with the variant API registration)
            val generatedJniLib = project.tasks.register(
                "mainGenerateCustomJniLib",
                GenerateJniTask::class.java
            ) { task ->
                task.outputDirectory.set(project.layout.buildDirectory.dir("generatedJniLib/main"))
                task.fileName.set("main-sourceset-generated.so")
            }

            val variantSourceSet = extension.sourceSets.getByName("main")
            val outputDir = generatedJniLib.flatMap(GenerateJniTask::outputDirectory)
            variantSourceSet.jniLibs.srcDir(outputDir)
            project.tasks.named("preBuild").configure { task -> task.dependsOn(outputDir) }

            // Variant API registration (regression test for b/237783279)
            extension.applicationVariants.all { variant ->
                val generatedJniLib = project.tasks.register(
                    variant.getName() + "GenerateCustomJniLib",
                    GenerateJniTask::class.java
                ) { task ->
                    task.outputDirectory.set(project.layout.buildDirectory.dir("generatedJniLib/" + variant.getDirName()));
                    task.fileName.set(variant.getName() + "-sourceset-generated.so");
                }

                val variantSourceSet = extension.sourceSets.getByName(variant.getName());
                val outputDir = generatedJniLib.flatMap(GenerateJniTask::outputDirectory);
                variantSourceSet.jniLibs.srcDir(outputDir);
                variant.getPreBuildProvider().configure { task -> task.dependsOn(outputDir) }
            }

            // After evaluation late initialization
            project.afterEvaluate {
                val variantSourceSet = extension.sourceSets.getByName("main");
                val lateFolder = project.layout.buildDirectory.dir("lateDefinedJavaSources").get().asFile;
                variantSourceSet.java.srcDir(lateFolder);
                try {
                    val dir = project.layout.buildDirectory.dir("lateDefinedJavaSources/com/example/generated").get().asFile
                    dir.mkdirs();
                    File(dir, "Test.java").writeText("package com.example.generated;\n\nclass Test { }")
                } catch (ignored: IOException) { }
            }
        }
    }

    @Test
    fun sourceRegistrationShouldBeRepresented() {
        val build = rule.build
        build.executor
            .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
            .run(":app:assembleDebug")
        build.androidApplication().assertApk(ApkSelector.DEBUG) {
            containsFile("lib/armeabi-v7a/main-sourceset-generated.so")
            containsFile("lib/armeabi-v7a/debug-sourceset-generated.so")
            containsClass("Lcom/example/generated/Test;")
        }
    }
}

/** Task to  generate a placeholder JNI lib */
abstract class GenerateJniTask: DefaultTask() {

    @get:Input
    abstract val fileName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val myLib = outputDirectory.get().asFile.toPath().resolve("armeabi-v7a").resolve(fileName.get())
        myLib.createParentDirectories()
        myLib.writeText(fileName.get() + " contents")
    }
}
