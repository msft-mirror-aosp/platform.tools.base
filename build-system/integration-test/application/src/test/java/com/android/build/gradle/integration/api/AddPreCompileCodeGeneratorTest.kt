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
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.LibraryComponentCallback
import com.google.common.truth.Truth
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File

class AddPreCompileCodeGeneratorTest {

    companion object {
        fun generateKotlinFunction(packageName: String) =
            """
                package com.foo.bar.app
                class MyClass {
                    fun someFunctionUsingGeneratedAPIs() {
                        ${packageName}.GeneratorUtils.someFunction()
                    }
                }
            """.trimIndent()

        fun generateJavaFunction(packageName: String) =
            """
                package com.foo.bar;
                public class MyClass {
                    void someFunctionUsingGeneratedAPIs() {
                        ${packageName}.GeneratorUtils.someFunction();
                    }
                }
            """.trimIndent()
    }

    @get:Rule
    val project = GradleRule.configure().from {
        androidApplication {
            files {
                add("src/main/kotlin/com/foo/bar/app/MyClass.kt",
                    generateKotlinFunction("com.foo.utils.app")
                    )
                add("src/main/java/com/foo/bar/app/MyClass.java",
                    generateJavaFunction("com.foo.utils.app"))
            }
            pluginCallbacks += MyAppCallback::class.java
        }
        androidLibrary {
            files {
                add("src/main/kotlin/com/foo/bar/MyClass.kt",
                    generateKotlinFunction("com.foo.utils"))
            }
            pluginCallbacks += MyLibraryCallback::class.java
        }
        androidLibrary(path=":javaLib") {
            files {
                add("src/main/java/com/foo/bar/MyClass.java",
                    generateJavaFunction("com.foo.utils")
                )
            }
            pluginCallbacks += MyLibraryCallback::class.java
        }
    }

    open class AbstractCallBack {

        fun abstractRegistration(
            project: Project,
            androidComponents: AndroidComponentsExtension<*,*,*>,
            packageName: String
        ) {
            androidComponents.onVariants { variant ->
                val taskProvider = project.tasks.register<AddPreCompileGeneratedCodeTask>(
                    "generate${variant.name}Bytecodes",
                    AddPreCompileGeneratedCodeTask::class.java
                ) {
                    it.packageName.set(packageName)
                }
                variant.artifacts
                    .use<AddPreCompileGeneratedCodeTask>(taskProvider)
                    .wiredWith(AddPreCompileGeneratedCodeTask::outputDir)
                    .toAppendTo(MultipleArtifact.PRE_COMPILATION_CLASSES)
            }
        }
    }

    class MyAppCallback: AbstractCallBack(), ApplicationComponentCallback {
        override fun handleExtension(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            abstractRegistration(project, androidComponents, "com/foo/utils/app/")
        }
    }

    class MyLibraryCallback: AbstractCallBack(), LibraryComponentCallback {
        override fun handleExtension(
            project: Project,
            androidComponents: LibraryAndroidComponentsExtension
        ) {
            abstractRegistration(project, androidComponents, "com/foo/utils/")
        }
    }

    @Test
    fun ensureSuccessfulCompilation() {
        val gradleBuild = project.build
        val result = gradleBuild.executor.run("assembleDebug")
        Truth.assertThat(result.failedTasks).isEmpty()
        gradleBuild.androidLibrary(":lib").assertAar(AarSelector.DEBUG) {
            classes().contains("com/foo/utils/GeneratorUtils")
        }
        gradleBuild.androidLibrary(":javaLib").assertAar(AarSelector.DEBUG) {
            classes().contains("com/foo/utils/GeneratorUtils")
        }
        gradleBuild.androidApplication(":app").assertApk(ApkSelector.DEBUG) {
            classes().contains("com/foo/utils/app/GeneratorUtils")
        }
    }
}

/** Task to  generate a .class file that will be used during main module compilation */
abstract class AddPreCompileGeneratedCodeTask: DefaultTask() {

    @get:Input
    abstract val packageName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val packageDir = File(outputDir.get().asFile, packageName.get())
        packageDir.mkdirs()
        File(packageDir, "GeneratorUtils.class").writeBytes(
            generateGeneratorUtilsWithMethod(packageName.get())
        )
        println("Class File written at ${packageDir.absolutePath}")
    }
        /**
         * Generates the bytecode for the GeneratorUtils class using ASM.
         *
         * The equivalent Java source is:
         * ```java
         * package com.foo.utils;
         *
         * public class GeneratorUtils {
         *     // Default public constructor, added automatically by javac
         *     public GeneratorUtils() {}
         *
         *     // The requested static method
         *     public static void someFunction() {
         *     }
         * }
         * ```
         *
         * @return A ByteArray containing the bytes of the generated .class file.
         */
        fun generateGeneratorUtilsWithMethod(packageName:String): ByteArray {
            // 1. Create a ClassWriter.
            // COMPUTE_FRAMES tells ASM to automatically compute stack map frames.
            val classWriter = ClassWriter(ClassWriter.COMPUTE_FRAMES)

            // 2. Define the class header for com.foo.utils.GeneratorUtils
            classWriter.visit(
                Opcodes.V1_8, // Java 8 bytecode version
                Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER, // public class
                "${packageName}GeneratorUtils", // Internal name (slashes instead of dots)
                null, // No generic signature
                "java/lang/Object", // Superclass
                null  // No interfaces
            )

            // 3. Create the default public constructor <init>()
            val constructorVisitor = classWriter.visitMethod(
                Opcodes.ACC_PUBLIC, // public
                "<init>",           // Constructor name
                "()V",              // Descriptor: no arguments, returns void
                null,
                null
            )
            constructorVisitor.visitCode()
            constructorVisitor.visitVarInsn(Opcodes.ALOAD, 0) // load `this`
            constructorVisitor.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/lang/Object",
                "<init>",
                "()V",
                false
            )
            constructorVisitor.visitInsn(Opcodes.RETURN)
            constructorVisitor.visitMaxs(0, 0) // Let ASM compute max stack and locals
            constructorVisitor.visitEnd()

            // 4. Create the public static method someFunction()
            val methodVisitor = classWriter.visitMethod(
                Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC, // public static
                "someFunction",
                "()V", // Descriptor: no arguments, returns void
                null,
                null
            )
            methodVisitor.visitCode()
            // The method body is empty, so we just need to return.
            methodVisitor.visitInsn(Opcodes.RETURN)
            methodVisitor.visitMaxs(0, 0) // Let ASM compute max stack and locals
            methodVisitor.visitEnd()

            // 5. Finalize the class
            classWriter.visitEnd()

            return classWriter.toByteArray()
        }
}
