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

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts.Scope
import com.android.build.api.variant.impl.capitalizeFirstChar
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.google.common.truth.Truth
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.Project
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
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Test that uses the POST_COMPILATION_CLASSES artifact to generate a class "GeneratorUtils".
 *
 * Eventually the GeneratorUtils will be used by all the tests of the 2 modules to ensure visibility.
 *
 * The test runs with different scopes for consuming the POST_COMPILATION_CLASSES and for producing in the CLASSES artifact.
 */
@RunWith(Parameterized::class)
class AddScopedPostCompileCodeGeneratorTest(private val consumingScope: Scope, private val producingScope: Scope) {

  companion object {
    @Parameterized.Parameters(name = "consumingScope={0}, producingScope={1}")
    @JvmStatic
    fun parameters() =
      listOf(
        arrayOf(Scope.PROJECT, Scope.ALL),
        arrayOf(Scope.PROJECT, Scope.PROJECT),
        arrayOf(Scope.ALL, Scope.ALL),
        arrayOf(Scope.ALL, Scope.PROJECT),
      )

    fun generateKotlinClass(className: String, postCompilationGeneratedPackageName: String = "com.android.test.utils") =
      // language=Kotlin
      """
                package com.foo.bar

                import ${AddScopedPostCompileCodeGeneratorTask.CLIENT_INTERFACE_INTERNAL_NAME.replace('/', '.')}
                // import ${postCompilationGeneratedPackageName}.GeneratorUtils
                import org.junit.Test

                class ${className}: ClientInterface {
                    override fun someFunction() {
                        println("Success from kotlin !")
                    }

                    @Test
                    fun someTest() {
                        // GeneratorUtils.someFunction(this)
                    }
                }
            """
        .trimIndent()

    fun generateJavaClass(className: String, postCompilationGeneratedPackageName: String = "com.android.test.utils") =
      // language=Java
      """
                package com.foo.bar;
                import ${
                AddScopedPostCompileCodeGeneratorTask.CLIENT_INTERFACE_INTERNAL_NAME.replace(
                    '/',
                    '.',
                )
            };
                // import ${postCompilationGeneratedPackageName}.GeneratorUtils;
                import org.junit.Test;

                public class $className implements ClientInterface {
                    public void someFunction() {
                        System.err.println("Success from Java !");
                    }

                    @Test
                    public void someOtherTest() {
                        // GeneratorUtils.someFunction(this);
                    }
                }
            """
        .trimIndent()
  }

  @get:Rule
  val project =
    GradleRule.from {
      gradleProperties {
        add("CONSUMING_SCOPE", consumingScope.name)
        add("PRODUCING_SCOPE", producingScope.name)
      }
      androidApplication {
        dependencies {
          api(project(":lib"))
          testImplementation("junit:junit:4.12")
          androidTestImplementation("junit:junit:4.12")
        }
        files {
          // add a few tests in both kotlin and java that will eventually get
          // wired up using the generated code.
          add("src/test/kotlin/com/foo/bar/MyKotlinTestClass.kt", generateKotlinClass("MyKotlinTestClass"))
          add("src/test/java/com/foo/bar/MyJavaTestClass.java", generateJavaClass("MyJavaTestClass"))

          // sane for android test.
          add("src/androidTest/kotlin/com/foo/bar/MyAndroidTestClass.kt", generateKotlinClass("MyAndroidTestClass"))
          add("src/androidTest/java/com/foo/bar/MyAndroidJavaTestClass.kt", generateKotlinClass("MyAndroidJavaTestClass"))
        }
        pluginCallbacks += AddPostCompilationCallback::class.java
      }
      androidLibrary {
        files {
          // add an interface that will be used by the bytecode generated class
          add(
            "src/main/kotlin/${AddScopedPostCompileCodeGeneratorTask.CLIENT_INTERFACE_INTERNAL_NAME}.kt",
            """
            package com.android.tools.test

            interface ClientInterface {
                fun someFunction()
            }
            """
              .trimIndent(),
          )

          add("src/test/kotlin/com/foo/bar/MyKotlinTestClass.kt", generateKotlinClass("MyKotlinTestClass", "com.android.test.lib.utils"))
          add("src/test/java/com/foo/bar/MyJavaTestClass.java", generateJavaClass("MyJavaTestClass", "com.android.test.lib.utils"))
        }
        dependencies { testImplementation("junit:junit:4.12") }
        pluginCallbacks += AddPostCompilationCallback::class.java
      }
    }

  open class AddPostCompilationCallback : GenericCallback {

    override fun handleProject(project: Project) {
      val extension = project.extensions.getByType(AndroidComponentsExtension::class.java)
      val consumingScope = project.providers.gradleProperty("CONSUMING_SCOPE").map(Scope::valueOf)
      val producingScope = project.providers.gradleProperty("PRODUCING_SCOPE").map(Scope::valueOf)

      extension.onVariants(extension.selector().withBuildType("debug")) { variant ->
        val generatorTask =
          project.tasks.register(
            "postCompilation${variant.name.capitalizeFirstChar()}Task",
            AddScopedPostCompileCodeGeneratorTask::class.java,
          ) { task ->
            if (extension is ApplicationAndroidComponentsExtension) {
              task.packageName.set("com/android/test/utils")
            } else {
              task.packageName.set("com/android/test/lib/utils")
            }
            // the all scope only really applies to app module.
            task.allScope.set(consumingScope.get() == Scope.ALL && project.name == "app")
          }

        // consume the POST_COMPILATION_CLASSES in the current consuming scope.
        variant.artifacts
          .forScope(consumingScope.get())
          .use(generatorTask)
          .toGet(
            ScopedArtifact.POST_COMPILATION_CLASSES,
            AddScopedPostCompileCodeGeneratorTask::jars,
            AddScopedPostCompileCodeGeneratorTask::dirs,
          )

        // and produce in the right scope.
        variant.artifacts
          .forScope(producingScope.get())
          .use(generatorTask)
          .toAppend(ScopedArtifact.CLASSES, AddScopedPostCompileCodeGeneratorTask::outputDir)
      }
    }
  }

  @Test
  fun testBuildCorrectness() {
    val gradleBuild = project.build
    val tasks = mutableListOf("assembleDebug", "testDebugUnitTest", ":app:compileDebugAndroidTestJavaWithJavac")

    val buildResult = gradleBuild.executor.run(*tasks.toTypedArray())

    // check executed classes
    Truth.assertThat(buildResult.failedTasks).isEmpty()
    Truth.assertThat(buildResult.didWorkTasks)
      .containsAtLeast(":app:testDebugUnitTest", ":lib:testDebugUnitTest", ":app:compileDebugAndroidTestKotlin")

    gradleBuild.androidApplication().assertApk(ApkSelector.DEBUG) {
      classes()
        .containsAtLeast(
          "com/android/test/lib/utils/GeneratorUtils",
          "com/android/test/utils/GeneratorUtils",
          AddScopedPostCompileCodeGeneratorTask.CLIENT_INTERFACE_INTERNAL_NAME,
        )
    }
  }
}

abstract class AddScopedPostCompileCodeGeneratorTask : DefaultTask() {

  companion object {
    internal const val CLIENT_INTERFACE_INTERNAL_NAME = "com/android/tools/test/ClientInterface"
  }

  @get:Input abstract val packageName: Property<String>

  @get:InputFiles @get:Classpath abstract val jars: ListProperty<RegularFile>

  @get:InputFiles @get:Classpath abstract val dirs: ListProperty<Directory>

  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @get:Input abstract val allScope: Property<Boolean>

  @TaskAction
  fun generate() {
    // when we are supposed to be injected with all the dependencies, ensure that incoming
    // jars is not empty.
    if (allScope.get() && jars.get().isEmpty()) {
      throw RuntimeException("Cannot find the dependencies")
    }
    // conversely, when not injected with all dependencies, we should not have any jars.
    if (!allScope.get() && jars.get().isNotEmpty()) {
      throw RuntimeException("Unexpected dependencies present.")
    }
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
    val functionDescriptor = "(L${CLIENT_INTERFACE_INTERNAL_NAME};)V"

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
        functionDescriptor, // <-- UPDATED: Takes LClientInterface; returns void
        null,
        null,
      )
    methodVisitor.visitCode()

    // --- Start of new method body: client.someMethod(); ---

    // Instruction 1: Load the first parameter (ClientInterface client) from local variable 0.
    // For a static method, arguments start at index 0.
    methodVisitor.visitVarInsn(Opcodes.ALOAD, 0)

    // Instruction 2: Call the instance method 'someFunction()' on the object currently on the stack (client).
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
