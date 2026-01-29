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

package com.android.build.gradle.integration.api

import com.android.build.api.artifact.MultipleArtifact
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts.Scope
import com.android.build.api.variant.TestAndroidComponentsExtension
import com.android.build.api.variant.impl.capitalizeFirstChar
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyApplicationCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.TestComponentCallback
import com.android.build.gradle.integration.common.output.JarSubject
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class PreAndPostCompilationCodeVisibilityTest {

  @get:Rule
  val project =
    GradleRule.from {
      androidApplication {
        android {}
        dependencies {
          api(project(":lib"))
          testImplementation("junit:junit:4.12")
          androidTestImplementation("junit:junit:4.12")
        }
        pluginCallbacks += LegacyCallback::class.java
        pluginCallbacks += AddPostCompilationCallback::class.java
      }
      androidLibrary { pluginCallbacks += AddPostCompilationCallback::class.java }
      androidTest(":test") {
        android { targetProjectPath = ":app" }
        pluginCallbacks += CheckVisibilityCallback::class.java
      }
      gradleProperties { add(BooleanOption.USE_NEW_DSL, false) }
    }

  open class LegacyCallback : LegacyApplicationCallback {

    override fun handleExtension(project: Project, extension: BaseAppModuleExtension) {}
  }

  open class AddPostCompilationCallback : GenericCallback {

    override fun handleProject(project: Project) {
      val extension = project.extensions.getByType(AndroidComponentsExtension::class.java)

      extension.onVariants(extension.selector().withBuildType("debug")) { variant ->
        val preGeneratorTask =
          project.tasks.register(
            "preCompilation${variant.name.capitalizeFirstChar()}Task",
            AddPreAndPostCompilationCodeGeneratorTask::class.java,
          ) { task ->
            if (extension is ApplicationAndroidComponentsExtension) {
              task.packageName.set("com/android/test/pre")
            } else {
              task.packageName.set("com/android/test/lib/pre")
            }
          }

        variant.artifacts.use(preGeneratorTask).wiredWith { it.outputDir }.toAppendTo(MultipleArtifact.PRE_COMPILATION_CLASSES)

        val postGeneratorTask =
          project.tasks.register(
            "postCompilation${variant.name.capitalizeFirstChar()}Task",
            AddPreAndPostCompilationCodeGeneratorTask::class.java,
          ) { task ->
            if (extension is ApplicationAndroidComponentsExtension) {
              task.packageName.set("com/android/test/post")
            } else {
              task.packageName.set("com/android/test/lib/post")
            }
          }

        variant.artifacts
          .forScope(Scope.PROJECT)
          .use(postGeneratorTask)
          .toGet(
            ScopedArtifact.POST_COMPILATION_CLASSES,
            AddPreAndPostCompilationCodeGeneratorTask::jars,
            AddPreAndPostCompilationCodeGeneratorTask::dirs,
          )

        variant.artifacts
          .forScope(Scope.PROJECT)
          .use(postGeneratorTask)
          .toAppend(ScopedArtifact.CLASSES, AddPreAndPostCompilationCodeGeneratorTask::outputDir)
      }
    }
  }

  open class CheckVisibilityCallback : TestComponentCallback {

    override fun handleExtension(project: Project, androidComponents: TestAndroidComponentsExtension) {
      androidComponents.onVariants { variant ->
        project.tasks.register("verifyVisibility${variant.name.capitalizeFirstChar()}Task", CheckVisibilityTask::class.java) { task ->
          task.newApiCompileClasspath.from(variant.compileClasspath)
          task.newApiRuntimeConfiguration.from(variant.runtimeConfiguration)
        }
      }
    }
  }

  @Test
  fun testVisibility() {
    val build = project.build
    build.subProject(":test").files.update("build.gradle") {
      append(
        """
        android {
            applicationVariants.all { variant ->
                def variantName = variant.name.capitalize()
                tasks.getByName("verifyVisibility" + variantName +"Task").configure { task ->
                  task.oldApiRuntimeConfiguration.from(variant.runtimeConfiguration)
                }
            }
        }
        """
          .trimIndent()
      )
    }
    val result = build.executor.run(":test:verifyVisibilityDebugTask", "assemble")
    Truth.assertThat(result.failedTasks).isEmpty()

    val appClasses =
      build
        .subProject(":app")
        .resolve(InternalArtifactType.COMPILE_APP_CLASSES_JAR)
        .resolve("debug")
        .resolve("bundleDebugClassesToCompileJar")
        .resolve("classes.jar")

    JarSubject.assertThat(appClasses) {
      classes().containsAtLeast("com/android/test/post/GeneratorUtils", "com/android/test/pre/GeneratorUtils")
    }

    val libClasses =
      build
        .subProject(":lib")
        .resolve(InternalArtifactType.COMPILE_LIBRARY_CLASSES_JAR)
        .resolve("debug")
        .resolve("bundleLibCompileToJarDebug/")
        .resolve("classes.jar")

    JarSubject.assertThat(libClasses) {
      classes().containsAtLeast("com/android/test/lib/post/GeneratorUtils", "com/android/test/lib/pre/GeneratorUtils")
    }
  }
}

abstract class CheckVisibilityTask : DefaultTask() {

  @get:InputFiles @get:Classpath abstract val newApiCompileClasspath: ConfigurableFileCollection

  @get:InputFiles @get:Classpath abstract val newApiRuntimeConfiguration: ConfigurableFileCollection

  @get:InputFiles @get:Classpath abstract val oldApiRuntimeConfiguration: ConfigurableFileCollection

  @TaskAction
  fun verify() {

    println("Compile Classpath ")
    val compileClasspath = newApiCompileClasspath.files
    compileClasspath.forEach { println(it.absolutePath) }
    println("End of Compile Classpath")

    println("New API ")
    newApiRuntimeConfiguration.files.forEach { println(it.absolutePath) }
    println("End of New API")

    println("Old API ")
    oldApiRuntimeConfiguration.files.forEach { println(it.absolutePath) }
    println("End of Old API")

    if (oldApiRuntimeConfiguration.files.map { it.absolutePath } == newApiRuntimeConfiguration.files.map { it.absolutePath }) {
      println("Success, same content")
    } else {
      throw RuntimeException("The old and new Variant API are not returning the same content")
    }

    // assert that we are seeing the right dependency jars in the compile classpath.
    // just check for file presence, the content will be checked in the test method.
    val appClasses =
      compileClasspath.firstOrNull() { it.absolutePath.contains(InternalArtifactType.COMPILE_APP_CLASSES_JAR.getFolderName()) }
    if (appClasses == null) {
      throw RuntimeException("Application code not present on test module classpath")
    }
    val libClasses =
      compileClasspath.firstOrNull { it.absolutePath.contains(InternalArtifactType.COMPILE_LIBRARY_CLASSES_JAR.getFolderName()) }
    if (libClasses == null) {
      throw RuntimeException("Library code not present on test module classpath")
    }
  }
}

abstract class AddPreAndPostCompilationCodeGeneratorTask : DefaultTask() {

  companion object {
    internal const val CLIENT_INTERFACE_INTERNAL_NAME = "com/android/tools/test/ClientInterface"
  }

  @get:Input abstract val packageName: Property<String>

  @get:InputFiles @get:Classpath abstract val jars: ListProperty<RegularFile>

  @get:InputFiles @get:Classpath abstract val dirs: ListProperty<Directory>

  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @TaskAction
  fun generate() {
    val packageDir = File(outputDir.get().asFile, packageName.get())
    packageDir.mkdirs()
    File(packageDir, "GeneratorUtils.class").writeBytes(generateGeneratorUtilsWithMethod(packageName.get()))
    println("Class File written at ${packageDir.absolutePath}")
  }

  /**
   * Generates the bytecode for the GeneratorUtils class using ASM.
   *
   * The equivalent Java source is now:
   * ```java
   * package com.foo.utils;
   *
   * // The import is crucial for the method signature and call
   * import com.android.tools.build.test.ClientInterface;
   *
   * public class GeneratorUtils {
   * // Default public constructor, added automatically by javac
   * public GeneratorUtils() {}
   *
   * // The modified static method
   * public static void someFunction(ClientInterface client) {
   * client.someFunction();
   * }
   * }
   * ```
   *
   * @param packageName The package prefix (e.g., "com/foo/utils/").
   * @return A ByteArray containing the bytes of the generated .class file.
   */
  fun generateGeneratorUtilsWithMethod(packageName: String): ByteArray {
    // Define internal names for types used in the method
    val SOME_FUNCTION_DESCRIPTOR = "(L${CLIENT_INTERFACE_INTERNAL_NAME};)V"

    // 1. Create a ClassWriter.
    // COMPUTE_FRAMES tells ASM to automatically compute stack map frames.
    val classWriter = ClassWriter(ClassWriter.COMPUTE_FRAMES)

    // 2. Define the class header for com.foo.utils.GeneratorUtils
    classWriter.visit(
      Opcodes.V1_8, // Java 8 bytecode version
      Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER, // public class
      "${packageName}/GeneratorUtils", // Internal name (slashes instead of dots)
      null, // No generic signature
      "java/lang/Object", // Superclass
      null, // No interfaces
    )

    // 3. Create the default public constructor <init>()
    val constructorVisitor: MethodVisitor =
      classWriter.visitMethod(
        Opcodes.ACC_PUBLIC, // public
        "<init>", // Constructor name
        "()V", // Descriptor: no arguments, returns void
        null,
        null,
      )
    constructorVisitor.visitCode()
    constructorVisitor.visitVarInsn(Opcodes.ALOAD, 0) // load `this`
    constructorVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
    constructorVisitor.visitInsn(Opcodes.RETURN)
    constructorVisitor.visitMaxs(0, 0) // Let ASM compute max stack and locals
    constructorVisitor.visitEnd()

    // 4. Create the public static method someFunction(ClientInterface client)
    val methodVisitor: MethodVisitor =
      classWriter.visitMethod(
        Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC, // public static
        "someFunction",
        SOME_FUNCTION_DESCRIPTOR, // <-- UPDATED: Takes LClientInterface; returns void
        null,
        null,
      )
    methodVisitor.visitCode()

    // --- Start of new method body: client.someMethod(); ---

    // Instruction 1: Load the first parameter (ClientInterface client) from local variable 0.
    // For a static method, arguments start at index 0.
    methodVisitor.visitVarInsn(Opcodes.ALOAD, 0)

    // Instruction 2: Call the instance method 'someFunction()' on the object currently on the stack
    // (client).
    methodVisitor.visitMethodInsn(
      Opcodes.INVOKEINTERFACE,
      CLIENT_INTERFACE_INTERNAL_NAME, // Owner class
      "someFunction", // Method name
      "()V", // Descriptor of the method being called (no args, returns void)
      true,
    )

    // End of method body: return void.
    methodVisitor.visitInsn(Opcodes.RETURN)
    // --- End of new method body ---

    // Let ASM compute max stack and locals (Max Stack: 1, Max Locals: 1)
    methodVisitor.visitMaxs(0, 0)
    methodVisitor.visitEnd()

    // 5. Finalize the class
    classWriter.visitEnd()

    return classWriter.toByteArray()
  }
}
