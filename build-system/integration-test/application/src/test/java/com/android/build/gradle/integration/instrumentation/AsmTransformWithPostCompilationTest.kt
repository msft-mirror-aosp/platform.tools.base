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

package com.android.build.gradle.integration.instrumentation

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts.Scope
import com.android.build.api.variant.impl.capitalizeFirstChar
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.internal.utils.setDisallowChanges
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
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Test that combines having a POST_COMPILATION_CLASSES generator with registered ASM visitors
 * both the PROJECT and ALL scope.
 */

@RunWith(Parameterized::class)
class AsmTransformWithPostCompilationTest(
    val instrumentationScope: InstrumentationScope
) {
    companion object {
        @Parameterized.Parameters(name = "scope_{0}")
        @JvmStatic
        fun params() = listOf(
            arrayOf(InstrumentationScope.ALL),
            arrayOf(InstrumentationScope.PROJECT)
        )
    }

    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            dependencies {
                implementation(project(":lib"))
            }
            files {
                add("src/main/kotlin/com/android/test/SomeAppClass.kt",
                    """
                    package com.android.test
                    import com.android.lib.SomeLibClass
                    class SomeAppClass {
                        fun f1(p: SomeLibClass) {
                        }
                    }
                """.trimIndent())
            }
            // register the right callback depending on the tested scope.
            pluginCallbacks += if (instrumentationScope == InstrumentationScope.ALL) {
                AllScopeInstrumentationCallback::class.java
            } else {
                ProjectScopeInstrumentationCallback::class.java
            }
        }
        androidLibrary {
            android {

            }
            files {
                // This interface will be used by the post-compilation tasks to generate a new
                // class : GeneratorUtils
                add("src/main/kotlin/com/android/tools/test/ClientInterface.kt",
                    """
                        package com.android.tools.test
                        interface ClientInterface {
                        }
                    """.trimIndent())
                // This interface will be used by the ASM instrumentation to add. It will get
                // added to both SomeAppClass and SomeLibClass types.
                add("src/main/kotlin/com/android/tools/test/InstrumentedInterface.kt",
                    """
                        package com.android.tools.test
                        interface InstrumentedInterface {
                        }
                    """.trimIndent()
                )
                // A class that will get instrumented.
                add("src/main/kotlin/com/android/lib/SomeLibClass.kt",
                    """
                        package com.android.lib
                        class SomeLibClass {
                            fun f2() {
                            }
                        }
                    """.trimIndent())
            }
        }
    }

    open class AddPostCompilationCallback(
        val instrumentationScope: InstrumentationScope
    ): GenericCallback {

        override fun handleProject(project: Project) {
            val extension = project.extensions
                .getByType(AndroidComponentsExtension::class.java)

            extension
                .onVariants(extension.selector().withBuildType("debug")) {  variant ->

                    // Register the ASM instrumentation on the tested scope.
                    variant.instrumentation.transformClassesWith(
                        InterfaceAddingClassVisitorFactory::class.java,
                        instrumentationScope
                    ) { params ->
                        // these are the types to instrument, note that when the scope if PROJECT,
                        // the SomeLibClass will not be visited.
                        params.classesToInstrument.setDisallowChanges(
                            listOf(
                                "com.android.test.SomeAppClass",
                                "com.android.test.utils.GeneratorUtils",
                            )
                        )
                        // add this interface to the 2 types above.
                        params.interfaceInternalName.setDisallowChanges(
                            "com/android/tools/test/InstrumentedInterface"
                        )
                    }

                    // Register the post compilation bytecode generator.
                    val generatorTask = project.tasks
                        .register(
                            "postCompilation${variant.name.capitalizeFirstChar()}Task",
                            AddPostCompilationCodeGeneratorTask::class.java
                        ) { task ->
                            task.packageName.set("com/android/test/utils")
                        }

                    // inject the final CLASSES for the project, not that the generator actually
                    // uses them in this test but that's to ensure that any generator needing the
                    // compiled CLASSES to generate the bytecode does not generate a circular
                    // dependency.
                    variant.artifacts.forScope(Scope.PROJECT)
                        .use(generatorTask)
                        .toGet(
                            ScopedArtifact.POST_COMPILATION_CLASSES,
                            AddPostCompilationCodeGeneratorTask::jars,
                            AddPostCompilationCodeGeneratorTask::dirs
                        )

                    variant.artifacts.forScope(Scope.PROJECT)
                        .use(generatorTask)
                        .toAppend(ScopedArtifact.CLASSES, AddPostCompilationCodeGeneratorTask::outputDir)
                }
        }
    }

    open class AllScopeInstrumentationCallback(): AddPostCompilationCallback(InstrumentationScope.ALL)
    open class ProjectScopeInstrumentationCallback(): AddPostCompilationCallback(InstrumentationScope.PROJECT)

    @Test
    fun testPostCompilationGeneratorSeesTransformedClasses() {
        val project = rule.build
        val buildResult = project.executor.run("assembleDebug")
        Truth.assertThat(buildResult.failedTasks).isEmpty()
        // check that the APK contains all the expected types.
        project.androidApplication().assertApk(ApkSelector.DEBUG) {
            classes().containsAtLeast(
                "com/android/lib/SomeLibClass",
                "com/android/test/SomeAppClass",
                AddPostCompilationCodeGeneratorTask.CLIENT_INTERFACE_INTERNAL_NAME,
                "com/android/tools/test/InstrumentedInterface",
                "com/android/test/utils/GeneratorUtils",
            )
            // and check that SomeAppClass is always instrumented.
            classes().classDefinition("com/android/test/SomeAppClass")
                .interfaces().containsExactly("Lcom/android/tools/test/InstrumentedInterface;")
        }
    }
}

abstract class AddPostCompilationCodeGeneratorTask: DefaultTask() {

    companion object {
        internal const val CLIENT_INTERFACE_INTERNAL_NAME = "com/android/tools/test/ClientInterface"
    }

    @get:Input
    abstract val packageName: Property<String>

    @get:InputFiles
    @get:Classpath
    abstract val jars: ListProperty<RegularFile>

    @get:InputFiles
    @get:Classpath
    abstract val dirs: ListProperty<Directory>

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
    fun generateGeneratorUtilsWithMethod(packageName:String): ByteArray {
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
            null  // No interfaces
        )

        // 3. Create the default public constructor <init>()
        val constructorVisitor: MethodVisitor = classWriter.visitMethod(
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

        // 4. Create the public static method someFunction(ClientInterface client)
        val methodVisitor: MethodVisitor = classWriter.visitMethod(
            Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC, // public static
            "someFunction",
            SOME_FUNCTION_DESCRIPTOR, // <-- UPDATED: Takes LClientInterface; returns void
            null,
            null
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
            "someFunction",                   // Method name
            "()V",                          // Descriptor of the method being called (no args, returns void)
            true
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

abstract class InterfaceAddingClassVisitorFactory :
    AsmClassVisitorFactory<InterfaceAddingClassVisitorFactory.Params> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        return InterfaceAddingClassVisitor(
            parameters.get().interfaceInternalName.get(),
            instrumentationContext.apiVersion.get(),
            nextClassVisitor
        )
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        println("Requested for ${classData.className}")
        return parameters.get().classesToInstrument.get()
            .contains(classData.className)
    }

    interface Params : InstrumentationParameters {
        @get:Input
        val classesToInstrument: ListProperty<String>

        @get:Input
        val interfaceInternalName: Property<String>
    }
}

class InterfaceAddingClassVisitor(
    private val interfaceInternalName: String,
    apiVersion: Int,
    cv: ClassVisitor
) : ClassVisitor(apiVersion, cv) {
    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        super.visit(
            version,
            access,
            name,
            signature,
            superName,
            if (interfaces == null) arrayOf(interfaceInternalName)
            else arrayOf(
                *interfaces,
                interfaceInternalName
            )
        )
    }
}

