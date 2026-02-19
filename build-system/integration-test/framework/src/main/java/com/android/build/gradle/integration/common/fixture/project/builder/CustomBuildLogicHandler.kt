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

package com.android.build.gradle.integration.common.fixture.project.builder

import com.android.build.gradle.integration.common.fixture.project.builder.bytecode.ReferenceFinderVisitor
import com.android.build.gradle.integration.common.fixture.project.plugins.AndroidKotlinMultiplatformLibraryCallbackPlugin
import com.android.build.gradle.integration.common.fixture.project.plugins.AndroidKotlinMultiplatformLibraryComponentCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationCallbackPlugin
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.DynamicFeatureCallbackPlugin
import com.android.build.gradle.integration.common.fixture.project.plugins.DynamicFeatureComponentCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallbackPlugin
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericComponentCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericComponentCallbackPlugin
import com.android.build.gradle.integration.common.fixture.project.plugins.KotlinMultiplatformCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.KotlinMultiplatformCallbackPlugin
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyApplicationCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyApplicationCallbackPlugin
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyLibraryCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyLibraryCallbackPlugin
import com.android.build.gradle.integration.common.fixture.project.plugins.LibraryCallbackPlugin
import com.android.build.gradle.integration.common.fixture.project.plugins.LibraryComponentCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.PluginCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.TestCallbackPlugin
import com.android.build.gradle.integration.common.fixture.project.plugins.TestComponentCallback
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.reflect.KClass
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassReader.SKIP_DEBUG
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_SUPER
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.DUP
import org.objectweb.asm.Opcodes.INVOKESPECIAL
import org.objectweb.asm.Opcodes.INVOKEVIRTUAL
import org.objectweb.asm.Opcodes.NEW
import org.objectweb.asm.Opcodes.RETURN

/**
 * Handles custom build logic for [GradleRule] projects.
 *
 * This will create a single `build-logic.jar` with all the necessary classes to run the callbacks set in the projects.
 */
class CustomBuildLogicHandler(path: Path) : AutoCloseable {

  private val zipOutputStream: ZipOutputStream = ZipOutputStream(BufferedOutputStream(Files.newOutputStream(path)))

  private val writtenClasses = mutableSetOf<String>()
  private val basePluginClasses = mutableSetOf<Class<*>>()

  private data class PluginData(
    val callbackClass: KClass<out PluginCallback>,
    val pluginClass: KClass<*>,
    val extensionTypeClassName: String?,
  )

  companion object {
    /** Data linking plugin callbacks, plugin classes, and extension types. */
    private val PLUGGING_MAPPING: List<PluginData> =
      listOf(
        // component extension callbacks
        PluginData(
          ApplicationComponentCallback::class,
          ApplicationCallbackPlugin::class,
          "com/android/build/api/variant/ApplicationAndroidComponentsExtension",
        ),
        PluginData(
          LibraryComponentCallback::class,
          LibraryCallbackPlugin::class,
          "com/android/build/api/variant/LibraryAndroidComponentsExtension",
        ),
        PluginData(
          DynamicFeatureComponentCallback::class,
          DynamicFeatureCallbackPlugin::class,
          "com/android/build/api/variant/DynamicFeatureAndroidComponentsExtension",
        ),
        PluginData(TestComponentCallback::class, TestCallbackPlugin::class, "com/android/build/api/variant/TestAndroidComponentsExtension"),
        PluginData(
          GenericComponentCallback::class,
          GenericComponentCallbackPlugin::class,
          "com/android/build/api/variant/AndroidComponentsExtension",
        ),

        // legacy DSL callbacks
        PluginData(
          LegacyApplicationCallback::class,
          LegacyApplicationCallbackPlugin::class,
          "com/android/build/gradle/internal/dsl/BaseAppModuleExtension",
        ),
        PluginData(LegacyLibraryCallback::class, LegacyLibraryCallbackPlugin::class, "com/android/build/gradle/LibraryExtension"),

        // KMP callbacks
        PluginData(
          KotlinMultiplatformCallback::class,
          KotlinMultiplatformCallbackPlugin::class,
          "org/jetbrains/kotlin/gradle/dsl/KotlinMultiplatformExtension",
        ),
        PluginData(
          AndroidKotlinMultiplatformLibraryComponentCallback::class,
          AndroidKotlinMultiplatformLibraryCallbackPlugin::class,
          "com/android/build/api/variant/KotlinMultiplatformAndroidComponentsExtension",
        ),

        // generic plugin with no extension
        PluginData(GenericCallback::class, GenericCallbackPlugin::class, null),
      )
  }

  /**
   * Adds a new callback to the jar
   *
   * returns the name of the plugin that needs to be applied
   */
  fun addCallback(callbackClass: Class<out PluginCallback>): String {
    // write the callback class
    zipOutputStream.writeWithReferences(callbackClass)

    // get the information about the required classes (Plugin class, parent callback class, etc...)
    val pluginData = getPluginData(callbackClass)

    val callbackBinaryName = callbackClass.binaryName
    val basePluginBinaryName = pluginData.pluginClass.java.binaryName

    // then create a custom plugin for this callback
    val newPluginBinaryName = "${callbackBinaryName}_Plugin"
    val newPluginClassName = "${callbackClass.typeName}_Plugin"

    val newPluginClass =
      if (pluginData.extensionTypeClassName != null) {
        writePluginClass(newPluginBinaryName, basePluginBinaryName, callbackBinaryName, pluginData.extensionTypeClassName)
      } else {
        writeGenericPluginClass(newPluginBinaryName, basePluginBinaryName, callbackBinaryName)
      }

    zipOutputStream.write(newPluginBinaryName, newPluginClass)

    // write the property file so that the plugin can be applied with the plugin block
    val pluginId = zipOutputStream.writePluginProperties(newPluginClassName)

    // need to record the plugin class as the custom plugin is written manually
    // and we don't inspect its references.
    basePluginClasses += pluginData.pluginClass.java

    return pluginId
  }

  override fun close() {
    // also include the base plugin classes
    for (pluginClass in basePluginClasses) {
      zipOutputStream.writeWithReferences(pluginClass)
    }
    zipOutputStream.close()
  }

  /** Returns information about the plugin as a [PluginData] based on the type of the callback */
  private fun getPluginData(callbackClass: Class<out PluginCallback>): PluginData {
    for (pluginData in PLUGGING_MAPPING) {
      if (pluginData.callbackClass.java.isAssignableFrom(callbackClass)) {
        return pluginData
      }
    }
    throw RuntimeException("Unsupported PluginCallback: ${callbackClass.typeName}")
  }

  /**
   * Writes the custom plugin task from scratch using ASM.
   *
   * The class will extend a base case based on the type of the extension. It will only implement the `handleComponents` method.
   *
   * @param className the (binary) name of the class to generate
   * @param baseClassName the (binary) name of the plugin class to extend
   * @param callbackName the (binary) name of the callback to call in `handleExtension`
   * @param extensionType the (binary) name of the extension handled in the callback
   */
  private fun writePluginClass(className: String, baseClassName: String, callbackName: String, extensionType: String): ByteArray {
    val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
    writer.visit(Opcodes.V11, ACC_PUBLIC + ACC_SUPER, className, null, baseClassName, arrayOf())
    val constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)
    constructor.visitCode()
    constructor.visitVarInsn(ALOAD, 0)
    constructor.visitMethodInsn(INVOKESPECIAL, baseClassName, "<init>", "()V", false)
    constructor.visitInsn(RETURN)
    constructor.visitEnd()

    val method = writer.visitMethod(ACC_PUBLIC, "handleExtension", "(Lorg/gradle/api/Project;L${extensionType};)V", null, null)
    method.visitCode()
    method.visitTypeInsn(NEW, callbackName)
    method.visitInsn(DUP)
    method.visitMethodInsn(INVOKESPECIAL, callbackName, "<init>", "()V", false)
    method.visitVarInsn(ALOAD, 1)
    method.visitVarInsn(ALOAD, 2)
    method.visitMethodInsn(INVOKEVIRTUAL, callbackName, "handleExtension", "(Lorg/gradle/api/Project;L${extensionType};)V", false)
    method.visitInsn(RETURN)
    method.visitEnd()

    writer.visitEnd()
    return writer.toByteArray()
  }

  /**
   * Writes the custom plugin task from scratch using ASM, for generic plugins that don't handle any extension.
   *
   * @param className the (binary) name of the class to generate
   * @param baseClassName the (binary) name of the plugin class to extend
   * @param callbackName the (binary) name of the callback to call in `handleProject`
   */
  private fun writeGenericPluginClass(className: String, baseClassName: String, callbackName: String): ByteArray {
    val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
    writer.visit(Opcodes.V11, ACC_PUBLIC + ACC_SUPER, className, null, baseClassName, arrayOf())
    val constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)
    constructor.visitCode()
    constructor.visitVarInsn(ALOAD, 0)
    constructor.visitMethodInsn(INVOKESPECIAL, baseClassName, "<init>", "()V", false)
    constructor.visitInsn(RETURN)
    constructor.visitEnd()

    val method = writer.visitMethod(ACC_PUBLIC, "handleProject", "(Lorg/gradle/api/Project;)V", null, null)
    method.visitCode()
    method.visitTypeInsn(NEW, callbackName)
    method.visitInsn(DUP)
    method.visitMethodInsn(INVOKESPECIAL, callbackName, "<init>", "()V", false)
    method.visitVarInsn(ALOAD, 1)
    method.visitMethodInsn(INVOKEVIRTUAL, callbackName, "handleProject", "(Lorg/gradle/api/Project;)V", false)
    method.visitInsn(RETURN)
    method.visitEnd()

    writer.visitEnd()
    return writer.toByteArray()
  }

  /**
   * Writes a class to the jar, including all the other classes references by its bytecode
   *
   * This will use ASM to look at the class byte code and record any references to other classes. These references are them written to the
   * jar too, recursively searching for their own references.
   *
   * The references are only classes in the com.android.build.gradle.integration.* package in order to exclude java/kotlin/gradle/AGP
   * classes
   */
  private fun ZipOutputStream.writeWithReferences(theClass: Class<*>) {
    if (writtenClasses.contains(theClass.binaryName)) {
      return
    }

    val content = theClass.toBytes()
    content?.let {
      val entry = ZipEntry(theClass.typeName.replace('.', '/') + ".class")
      putNextEntry(entry)
      write(content)
      closeEntry()
    }

    writtenClasses += theClass.typeName.replace('.', '/')

    writeReferences(theClass)

    // write nested classes as well
    for (nestedClass in theClass.nestMembers) {
      if (nestedClass == theClass) continue
      writeWithReferences(nestedClass)
    }
  }

  /** Writes a single class to the jar file. */
  private fun ZipOutputStream.write(classBinaryName: String, content: ByteArray) {
    val entry = ZipEntry("$classBinaryName.class")
    putNextEntry(entry)
    write(content)
    closeEntry()

    writtenClasses += classBinaryName
  }

  /** Search the provided class for references to other classes and write those to the jar. */
  private fun writeReferences(theClass: Class<*>) {
    // search for the references inside this class
    val references = findReferences(theClass)

    // write each of these classes and their references
    for (ref in references) {
      // written is updated recursively so we need to keep checking it on each iteration
      if (!writtenClasses.contains(ref)) {
        zipOutputStream.writeWithReferences(theClass.classLoader.loadClass(ref.replace('/', '.')))
      }
    }
  }

  /**
   * Searches the class bytecode and returns a list of classes referenced in the bytecode.
   *
   * The references are only classes in the com.android.build.gradle.integration.* package in order to exclude java/kotlin/gradle/AGP
   * classes
   */
  private fun findReferences(theClass: Class<*>): Set<String> {
    val reader = ClassReader(theClass.toBytes())
    val visitor = ReferenceFinderVisitor()

    reader.accept(visitor, SKIP_DEBUG)

    return visitor.references.toSet()
  }

  /** Loads the class content from disk and returns it as a ByteArray */
  private fun Class<*>.toBytes(): ByteArray? {
    val resPath = typeName.replace('.', '/') + ".class"
    val resources = classLoader.getResource(resPath)
    return resources?.readBytes()
  }

  private val Class<*>.binaryName: String
    get() = typeName.replace('.', '/')

  private fun ZipOutputStream.writePluginProperties(pluginClassName: String): String {
    val pluginId = pluginClassName.replace('$', '_')
    val entry = ZipEntry("META-INF/gradle-plugins/$pluginId.properties")
    putNextEntry(entry)
    write("implementation-class=$pluginClassName".toByteArray())
    closeEntry()

    return pluginId
  }
}
