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
@file:Suppress("DEPRECATION")

package com.android.build.gradle.integration.kotlin

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.impl.DirectoryEntry
import com.android.build.api.variant.impl.FlatSourceDirectoriesForJavaImpl
import com.android.build.api.variant.impl.FlatSourceDirectoriesImpl
import com.android.build.gradle.api.SourceKind
import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyApplicationCallback
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test

/**
 * Test to simulate how KAPT and KSP are interacting with each other. This should also cover when other code generators are in the picture.
 */
class SimulatedKaptAndKspCompilationTest {

  @get:Rule
  val project =
    GradleRule.from {
      androidApplication {
        applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
        files {
          // generate some user code that will access all the generated code both in
          // java and kotlin, and KAPT and KSP.
          add(
            "src/main/kotlin/com/foo/bar/app/MyClass.kt",
            """
            package com.foo.bar.app

            import com.kapt.MyKaptClass
            import com.ksp.MyJavaKspClass
            import com.ksp.MyKotlinKspClass
            import com.kotlingen.MyKotlinClass
            import com.javagen.MyJavaClass

            class MyClass {
                fun someFunctionUsingGeneratedAPIs() {
                    MyJavaKspClass.someFunctionUsingGeneratedAPIs()
                    MyKotlinKspClass().someFunctionUsingGeneratedAPIs()
                    MyKotlinClass().someFunctionUsingGeneratedAPIs()
                    MyJavaClass.someFunctionUsingGeneratedAPIs()
                    MyKaptClass.someFunctionUsingGeneratedAPIs()
                    SomeUtil().someFunctionUsingGeneratedAPIs()
                }
            }
            """
              .trimIndent(),
          )
          // generate some user code that will have access to all the generated java code.
          add(
            "src/main/java/com/foo/bar/app/SomeUtil.java",
            """
            package com.foo.bar.app;

            import com.kapt.MyKaptClass;
            import com.ksp.MyJavaKspClass;
            import com.javagen.MyJavaClass;

            class SomeUtil {
                public void someFunctionUsingGeneratedAPIs() {
                    MyKaptClass.someFunctionUsingGeneratedAPIs();
                    MyJavaKspClass.someFunctionUsingGeneratedAPIs();
                    MyJavaClass.someFunctionUsingGeneratedAPIs();
                }
            }
            """
              .trimIndent(),
          )
        }
        pluginCallbacks += MyAppCallback::class.java
        pluginCallbacks += MyAppLegacyCallBack::class.java
      }
      gradleProperties { add(BooleanOption.USE_NEW_DSL, false) }
    }

  // Old variant API usage, this is mimicking the exact code the Jetbrains' KAPT plugin does
  // to plugin into AGP.
  class MyAppLegacyCallBack : LegacyApplicationCallback {
    override fun handleExtension(project: Project, extension: BaseAppModuleExtension) {
      extension.applicationVariants.all { variant ->

        // a fake KAPT provider that uses the exact APIs the KAPT task is using.
        // It imports all JAVA sources from the old variant API and register itself
        // as an APT output. It must be able to see the KSP output as KSP generates
        // java stubs that need to be processed by kapt.
        val outputDir = project.layout.buildDirectory.dir("build/generated/source/kapt/${variant.name}")
        val kaptProvider =
          project.tasks.register("generate${variant.name}KAPTSources", FakeKAPTTask::class.java) { task ->
            task.packageName.set("com.kapt")
            task.outputDir.set(outputDir)
            // filter itself out, this is what the old KAPT does.
            task.sources.set(
              variant.getSourceFolders(SourceKind.JAVA).filterNot { it.dir.absolutePath == outputDir.get().asFile.absolutePath }
            )
          }
        variant.registerExternalAptJavaOutput(project.fileTree(outputDir).builtBy(kaptProvider))
      }
    }
  }

  class MyAppCallback : ApplicationComponentCallback {
    override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
      androidComponents.onVariants { variant ->

        // A kotlin generating task which is is importing all java and kotlin source folders.
        val kotlinGenTaskProvider =
          project.tasks.register("generate${variant.name}KotlinSources", KotlinSourceGeneratingTask::class.java) { task ->
            task.packageName.set("com.kotlingen")
            task.sourceFiles.set(variant.sources.kotlin!!.static)
            task.sourceFiles.addAll(variant.sources.java!!.static)
          }
        variant.sources.kotlin!!.addGeneratedSourceDirectory(kotlinGenTaskProvider, KotlinSourceGeneratingTask::outputDir)

        // A java source generating task which is importing all static java directories.
        val javaGenTaskProvider =
          project.tasks.register("generate${variant.name}JavaSources", JavaSourceGeneratingTask::class.java) { task ->
            task.packageName.set("com.javagen")
            task.sourceFiles.set(variant.sources.java!!.static)
          }
        variant.sources.java!!.addGeneratedSourceDirectory(javaGenTaskProvider, JavaSourceGeneratingTask::outputDir)

        // This is the fake KSP task. it uses dedicated APIs to import its dependencies
        // which basically is all kotlin and java sources, static or generated but
        // excluding KAPT and KSP (itself) output.
        val kspTaskProvider =
          project.tasks.register("generate${variant.name}KspSources", FakeKspTask::class.java) { task ->
            task.packageName.set("com.ksp")
            task.javaOutputDir.set(project.layout.buildDirectory.dir("build/generated/source/ksp/java/${variant.name}"))
            task.kotlinOutputDir.set(project.layout.buildDirectory.dir("build/generated/source/ksp/kotlin/${variant.name}"))
            task.sourceFiles.set(variant.sources.kotlin!!.all)
            task.sourceFiles.addAll((variant.sources.java!! as FlatSourceDirectoriesForJavaImpl).allButKspAndKaptGenerators())
          }
        (variant.sources.java!! as FlatSourceDirectoriesImpl).addGeneratedSourceDirectory(
          kspTaskProvider,
          FakeKspTask::javaOutputDir,
          DirectoryEntry.Kind.KSP,
        )
        (variant.sources.java!! as FlatSourceDirectoriesImpl).addGeneratedSourceDirectory(
          kspTaskProvider,
          FakeKspTask::kotlinOutputDir,
          DirectoryEntry.Kind.KSP,
        )

        // create the Linter task which will want to see ALL java and kotlin static
        // and generated sources.
        project.tasks.register("lint${variant.name}Sources", KotlinSourceLinter::class.java) { task ->
          task.outputDir.set(project.layout.buildDirectory.dir("build/intermediates/linter/${variant.name}"))
          task.sourceFiles.set(variant.sources.kotlin!!.all)
          task.sourceFiles.addAll(variant.sources.java!!.all)
        }
      }
    }
  }

  @Test
  fun ensureSuccessfulCompilation() {
    val gradleBuild = project.build
    val result =
      gradleBuild.executor.withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.OFF).run("assembleDebug", "lintdebugSources")
    Truth.assertThat(result.failedTasks).isEmpty()
    gradleBuild.androidApplication(":app").assertApk(ApkSelector.DEBUG) {
      classes()
        .containsAtLeast(
          "com/foo/bar/app/MyClass",
          "com/foo/bar/app/SomeUtil",
          "com/kapt/MyKaptClass",
          "com/ksp/MyJavaKspClass",
          "com/ksp/MyKotlinKspClass",
          "com/kotlingen/MyKotlinClass",
          "com/javagen/MyJavaClass",
        )
    }
  }
}

/**
 * This is the fake KSP task that gets injected with all the sources including generated ones, but it will NOT see KAPT and itself's output.
 *
 * KSP can see folder project/app/src/main/kotlin KSP can see folder project/app/src/main/java KSP can see folder
 * project/app/src/debug/kotlin KSP can see folder project/app/src/debug/java KSP can see folder
 * project/app/build/generated/kotlin/generatedebugKotlinSources KSP can see folder project/app/src/main/java KSP can see folder
 * project/app/src/debug/java KSP can see folder project/app/build/generated/java/generatedebugJavaSources
 */
abstract class FakeKspTask : DefaultTask() {
  @get:Input abstract val packageName: Property<String>
  @get:OutputDirectory abstract val javaOutputDir: DirectoryProperty
  @get:OutputDirectory abstract val kotlinOutputDir: DirectoryProperty
  @get:InputFiles abstract val sourceFiles: ListProperty<Directory>

  @TaskAction
  fun generate() {
    System.err.println("KSP Generator running")
    sourceFiles.get().forEach { directory -> System.err.println("KSP can see folder ${directory.asFile.absolutePath}") }
    if (sourceFiles.get().size != 8) {
      throw RuntimeException("KSP was expecting 8 source folders, got ${sourceFiles.get().size}")
    }
    val javaOutputFolder = File(javaOutputDir.get().asFile, packageName.get().replace('.', File.separatorChar))
    javaOutputFolder.mkdirs()
    File(javaOutputFolder, "MyJavaKspClass.java")
      .writeText(
        """
                package ${packageName.get()};
                public class MyJavaKspClass {
                    public static void someFunctionUsingGeneratedAPIs() {
                        System.err.println("Hello world !");
                    }
                }
            """
          .trimIndent()
      )

    val kotlinOutputFolder = File(kotlinOutputDir.get().asFile, packageName.get().replace('.', File.separatorChar))
    kotlinOutputFolder.mkdirs()
    File(kotlinOutputFolder, "MyKotlinKspClass.kt")
      .writeText(
        """
                package ${packageName.get()};
                class MyKotlinKspClass {
                    fun someFunctionUsingGeneratedAPIs() {
                        System.err.println("Hello world !")
                    }
                }
            """
          .trimIndent()
      )
  }
}

/**
 * This is random Kotlin code generator that only uses the static sources as inputs. such a task is meant to act like databinding or build
 * configs. It will not see any java or kotlin source code generating task
 *
 * Kotlin Generator can see project/app/src/main/kotlin Kotlin Generator can see project/app/src/main/java Kotlin Generator can see
 * project/app/src/debug/kotlin Kotlin Generator can see project/app/src/debug/java Kotlin Generator can see project/app/src/main/java
 * Kotlin Generator can see project/app/src/debug/java
 */
abstract class KotlinSourceGeneratingTask : DefaultTask() {
  @get:Input abstract val packageName: Property<String>
  @get:OutputDirectory abstract val outputDir: DirectoryProperty
  @get:InputFiles abstract val sourceFiles: ListProperty<Directory>

  @TaskAction
  fun generate() {
    sourceFiles.get().forEach { directory -> System.err.println("Kotlin Generator can see ${directory.asFile.absolutePath}") }
    if (sourceFiles.get().size != 6) {
      throw RuntimeException("Kotlin generator was expecting 6 folders, got ${sourceFiles.get().size}")
    }
    val outputFolder = File(outputDir.get().asFile, packageName.get().replace('.', File.separatorChar))
    outputFolder.mkdirs()
    File(outputFolder, "MyKotlinClass.kt")
      .writeText(
        """
                package ${packageName.get()}
                class MyKotlinClass {
                    fun someFunctionUsingGeneratedAPIs() {
                        System.err.println("Hello world !")
                    }
                }
            """
          .trimIndent()
      )
  }
}

/**
 * This is a fake linter task that wants to see all code including the KSP and KAPT generated code.
 *
 * Kotlin/Java Linter can see project/app/src/main/kotlin Kotlin/Java Linter can see project/app/src/main/java Kotlin/Java Linter can see
 * project/app/src/debug/kotlin Kotlin/Java Linter can see project/app/src/debug/java Kotlin/Java Linter can see
 * project/app/build/generated/kotlin/generatedebugKotlinSources Kotlin/Java Linter can see project/app/src/main/java Kotlin/Java Linter can
 * see project/app/src/debug/java Kotlin/Java Linter can see project/app/build/generated/java/generatedebugJavaSources Kotlin/Java Linter
 * can see project/app/build/build/generated/source/kapt/debug Kotlin/Java Linter can see
 * project/app/build/build/generated/source/ksp/java/debug Kotlin/Java Linter can see
 * project/app/build/build/generated/source/ksp/kotlin/debug
 */
abstract class KotlinSourceLinter : DefaultTask() {

  @get:OutputDirectory abstract val outputDir: DirectoryProperty
  @get:InputFiles abstract val sourceFiles: ListProperty<Directory>

  @TaskAction
  fun generate() {
    sourceFiles.get().forEach { directory -> System.err.println("Kotlin/Java Linter can see ${directory.asFile.absolutePath}") }
    if (sourceFiles.get().size != 11) {
      throw RuntimeException("Linter task was expecting 10 source folders, got ${sourceFiles.get().size}")
    }
    File(outputDir.get().asFile, "lint.result").writeText("Success !")
  }
}

/**
 * This is random Java code generator that only uses the static sources as inputs. such a task is meant to act like databinding or build
 * configs.
 *
 * Java Generator can see app/src/main/java Java Generator can see app/src/debug/java
 */
abstract class JavaSourceGeneratingTask : DefaultTask() {
  @get:Input abstract val packageName: Property<String>
  @get:OutputDirectory abstract val outputDir: DirectoryProperty
  @get:InputFiles abstract val sourceFiles: ListProperty<Directory>

  @TaskAction
  fun generate() {
    sourceFiles.get().forEach { directory -> System.err.println("Java Generator can see ${directory.asFile.absolutePath}") }
    if (sourceFiles.get().size != 2) {
      throw RuntimeException("Linter task was expecting 10 source folders, got ${sourceFiles.get().size}")
    }
    val outputFolder = File(outputDir.get().asFile, packageName.get().replace('.', File.separatorChar))
    outputFolder.mkdirs()
    File(outputFolder, "MyJavaClass.java")
      .writeText(
        """
                package ${packageName.get()};
                public class MyJavaClass {
                    public static void someFunctionUsingGeneratedAPIs() {
                        System.err.println("Hello world !");
                    }
                }
            """
          .trimIndent()
      )
  }
}

/**
 * This is the fake KAPT task which gets injected with all the source including the generated sources
 *
 * KAPT can see project/app/src/main/java/com/foo/bar/app/SomeUtil.java KAPT can see
 * project/app/build/generated/java/generatedebugJavaSources/com/javagen/MyJavaClass.java KAPT can see
 * project/app/build/build/generated/source/ksp/java/debug/com/ksp/MyKspClass.java
 */
abstract class FakeKAPTTask : DefaultTask() {
  @get:Input abstract val packageName: Property<String>
  @get:OutputDirectory abstract val outputDir: DirectoryProperty
  @get:InputFiles abstract val sources: ListProperty<FileTree>

  @TaskAction
  fun generate() {
    System.err.println("KAPT running")
    sources.get().forEach { fileTree -> fileTree.files.forEach { file -> System.err.println("KAPT can see ${file.absolutePath}") } }
    val outputFolder = File(outputDir.get().asFile, packageName.get().replace('.', File.separatorChar))
    outputFolder.mkdirs()
    File(outputFolder, "MyKaptClass.java")
      .writeText(
        """
                package ${packageName.get()};
                public class MyKaptClass {
                    public static void someFunctionUsingGeneratedAPIs() {
                        System.err.println("Hello world !");
                    }
                }
            """
          .trimIndent()
      )
  }
}
