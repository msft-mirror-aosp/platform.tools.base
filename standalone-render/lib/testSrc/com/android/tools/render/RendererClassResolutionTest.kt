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

package com.android.tools.render

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.resources.ResourceType
import com.android.tools.rendering.classloading.ClassTransform
import com.android.tools.res.ids.ResourceIdManager
import com.android.tools.res.ids.ResourceIdManagerBase
import com.android.tools.res.ids.ResourceIdManagerModelModule
import com.android.tools.res.ids.apk.ApkResourceIdManager
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class RendererClassResolutionTest {
  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun testMapCompiledIdsToRClass() {
    val apkIdManager = ApkResourceIdManager()

    val classLoader = DummyRId::class.java.classLoader
    DummyRId.custom_text = 0

    val classLoaderManager = StandaloneModuleClassLoaderManager(emptyList(), emptyList())
    val resolver = RClassResourceResolver(classLoaderManager)

    // This should run without throwing any exceptions
    resolver.mapCompiledIdsToRClass(
      className = DummyRId::class.java.name,
      pkg = "com.android.tools.render",
      classLoader = classLoader,
      apkIdManager = apkIdManager,
    )

    // It remains 0 because the apkIdManager is empty
    assertTrue(DummyRId.custom_text == 0)
  }

  @Test
  fun testResolveClassesFromDirectory() {
    val dir = tempFolder.newFolder("dummy_r_classes")
    val packageDir = File(dir, "com/example").apply { mkdirs() }
    File(packageDir, "R.class").writeText("dummy bytecode")
    File(packageDir, "R\$id.class").writeText("dummy inner bytecode")

    val parsedBytecodes = mutableListOf<ByteArray>()
    val parser =
      object : ResourceIdManager.RClassParser {
        override fun parseBytecode(rClass: ByteArray, rClassProvider: (String) -> ByteArray) {
          parsedBytecodes.add(rClass)
        }

        override fun parseUsingReflection(rClass: Class<*>) {}
      }

    val rClassPackages = mutableSetOf<String>()
    val apkIdManager = ApkResourceIdManager()

    val classLoaderManager = StandaloneModuleClassLoaderManager(emptyList(), emptyList())
    val resolver = RClassResourceResolver(classLoaderManager)

    resolver.resolveClassesFromDirectory(
      file = dir,
      classLoader = this::class.java.classLoader,
      parser = parser,
      apkIdManager = apkIdManager,
      rClassPackages = rClassPackages,
    )

    assertTrue(parsedBytecodes.isNotEmpty())
  }

  @Test
  fun testResolveClassesFromJar() {
    val jarFile = tempFolder.newFile("dummy.jar")
    ZipOutputStream(FileOutputStream(jarFile)).use { zos ->
      zos.putNextEntry(ZipEntry("com/example/R.class"))
      zos.write("dummy bytecode".toByteArray())
      zos.closeEntry()

      zos.putNextEntry(ZipEntry("com/example/R\$id.class"))
      zos.write("dummy inner bytecode".toByteArray())
      zos.closeEntry()
    }

    val parsedBytecodes = mutableListOf<ByteArray>()
    val parser =
      object : ResourceIdManager.RClassParser {
        override fun parseBytecode(rClass: ByteArray, rClassProvider: (String) -> ByteArray) {
          parsedBytecodes.add(rClass)
        }

        override fun parseUsingReflection(rClass: Class<*>) {}
      }

    val rClassPackages = mutableSetOf<String>()
    val apkIdManager = ApkResourceIdManager()

    val classLoaderManager = StandaloneModuleClassLoaderManager(emptyList(), emptyList())
    val resolver = RClassResourceResolver(classLoaderManager)

    resolver.resolveClassesFromJar(
      file = jarFile,
      path = jarFile.absolutePath,
      classLoader = this::class.java.classLoader,
      parser = parser,
      apkIdManager = apkIdManager,
      rClassPackages = rClassPackages,
    )

    assertTrue(parsedBytecodes.isNotEmpty())
    assertTrue(rClassPackages.contains("com.example"))
  }

  @Test
  fun testResolve_loadsCompiledIdsFromRClassDirectoryAndJar() {
    // Regression test for b/567993824: ResourceIdBinaryParser requests inner classes by simple name ("R$id"), which must be
    // resolved relative to the R class package. Otherwise the inner class bytes are empty, parsing fails silently and no
    // compiled IDs are registered (previously masked by ViewLoader's per-RenderTask resetCompiledIds).
    val appDir = tempFolder.newFolder("app_r")
    compileJavaRClass(appDir, "com.example.app", "public static final int app_id = 0x7f010042;")
    val libDir = tempFolder.newFolder("lib_r")
    compileJavaRClass(libDir, "com.example.lib", "public static final int lib_id = 0x7f010043;")
    val libJar = tempFolder.newFile("lib_r.jar")
    jarDirectory(libDir, libJar)

    val apkIdManager = ApkResourceIdManager()
    val resourceIdManager =
      StandaloneResourceIdManager(apkIdManager, ResourceIdManagerBase(ResourceIdManagerModelModule.noNamespacingApp(true), true))
    val rClassPaths = listOf(appDir.absolutePath, libJar.absolutePath)
    val classLoaderManager = StandaloneModuleClassLoaderManager(rClassPaths, emptyList())
    try {
      val packages = RClassResourceResolver(classLoaderManager).resolve(rClassPaths, resourceIdManager, apkIdManager)
      assertTrue(packages.containsAll(listOf("com.example.app", "com.example.lib")))

      val appRef = ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.ID, "app_id")
      assertEquals(0x7f010042, resourceIdManager.getCompiledId(appRef))
      assertEquals(appRef, resourceIdManager.findById(0x7f010042))

      val libRef = ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.ID, "lib_id")
      assertEquals(0x7f010043, resourceIdManager.getCompiledId(libRef))
      assertEquals(libRef, resourceIdManager.findById(0x7f010043))

      // Per-RenderTask resets (ViewLoader.loadAndParseRClassSilently) must not drop the IDs loaded at bootstrap.
      resourceIdManager.resetCompiledIds {}
      assertEquals(0x7f010042, resourceIdManager.getCompiledId(appRef))
      assertEquals(libRef, resourceIdManager.findById(0x7f010043))
    } finally {
      classLoaderManager.close()
    }
  }

  @Test
  fun testInitializeLibraryResourceIds_runsOncePerClassLoaderAndToleratesLinkageErrors() {
    // R.class does not list R$attr in InnerClasses while R$attr claims R as its outer class, so Class.getSimpleName() throws
    // IncompatibleClassChangeError (b/516260743). Initialization must not rely on simpleName and must handle LinkageError.
    val mismatchedJar = tempFolder.newFile("mismatched_r.jar")
    ZipOutputStream(FileOutputStream(mismatchedJar)).use { zos ->
      zos.putNextEntry(ZipEntry("com/example/mismatch/R.class"))
      zos.write(generateClassBytes("com/example/mismatch/R", innerClasses = emptyList(), fields = emptyList()))
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("com/example/mismatch/R\$attr.class"))
      zos.write(
        generateClassBytes(
          internalName = "com/example/mismatch/R\$attr",
          innerClasses = listOf(Triple("com/example/mismatch/R\$attr", "com/example/mismatch/R", "attr")),
          fields = listOf(Triple("mismatched_attr", Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, 0)),
        )
      )
      zos.closeEntry()
    }

    val bootstrapper =
      RenderEnvironmentBootstrapper(
        fontsPath = null,
        resourceApkPath = null,
        namespace = "com.example.mismatch",
        classPath = listOf(mismatchedJar.absolutePath),
        projectClassPath = emptyList(),
        layoutlibPath = "",
        rClassJars = listOf(mismatchedJar.absolutePath),
      )
    bootstrapper.bootstrap().use { renderer ->
      val module = renderer.module
      val packages = module.dependencies.getResourcePackageNames(true)
      assertTrue(packages.contains("com.example.mismatch"))

      val privateLoader = renderer.module.environment.moduleClassLoaderManager.getPrivate(this::class.java.classLoader).classLoader
      var resourceLookupCount = 0
      val countingLoader =
        object : ClassLoader(privateLoader) {
          override fun getResource(name: String): URL? {
            if (name.startsWith("com/example/mismatch/R$")) resourceLookupCount++
            return super.getResource(name)
          }
        }

      module.initializeLibraryResourceIds(countingLoader, packages, module.resourceIdManager)
      val firstCallLookups = resourceLookupCount
      assertTrue(firstCallLookups > 0)

      val rAttr = countingLoader.loadClass("com.example.mismatch.R\$attr")
      assertThrows(IncompatibleClassChangeError::class.java) { rAttr.simpleName }
      val attrId = rAttr.getDeclaredField("mismatched_attr").getInt(null)
      assertNotEquals(0, attrId)
      assertEquals(
        ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.ATTR, "mismatched_attr"),
        module.resourceIdManager.findById(attrId),
      )

      // Second call on the same ClassLoader is a no-op (b/567993824).
      module.initializeLibraryResourceIds(countingLoader, packages, module.resourceIdManager)
      assertEquals(firstCallLookups, resourceLookupCount)

      // The shared DefaultModuleClassLoader is marked as initialized by the ClassLoaderProvider.
      val sharedRef =
        module
          .getClassLoaderProvider(false)
          .getClassLoader(this::class.java.classLoader, ClassTransform.identity, ClassTransform.identity, Runnable {})
      assertTrue((sharedRef.classLoader as DefaultModuleClassLoader).rClassesInitialized)
      val sharedAttrId = sharedRef.classLoader.loadClass("com.example.mismatch.R\$attr").getDeclaredField("mismatched_attr").getInt(null)
      assertNotEquals(0, sharedAttrId)
    }
  }

  private fun generateClassBytes(
    internalName: String,
    innerClasses: List<Triple<String, String, String>>,
    fields: List<Triple<String, Int, Int>>,
  ): ByteArray {
    val cw = ClassWriter(0)
    cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null)
    for ((innerInternal, outerInternal, simpleName) in innerClasses) {
      cw.visitInnerClass(innerInternal, outerInternal, simpleName, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL)
    }
    for ((fieldName, access, value) in fields) {
      cw.visitField(access, fieldName, "I", null, value).visitEnd()
    }
    val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
    mv.visitCode()
    mv.visitVarInsn(Opcodes.ALOAD, 0)
    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
    mv.visitInsn(Opcodes.RETURN)
    mv.visitMaxs(1, 1)
    mv.visitEnd()
    cw.visitEnd()
    return cw.toByteArray()
  }

  private fun compileJavaRClass(outputDir: File, pkg: String, idFieldsSource: String) {
    val srcDir = tempFolder.newFolder()
    val pkgDir = File(srcDir, pkg.replace('.', File.separatorChar)).apply { mkdirs() }
    val javaFile =
      File(pkgDir, "R.java").apply {
        writeText(
          """
          package $pkg;
          public final class R {
            public static final class id {
              $idFieldsSource
            }
          }
          """
            .trimIndent()
        )
      }
    val compiler = ToolProvider.getSystemJavaCompiler()
    assertEquals(0, compiler.run(null, null, null, "-d", outputDir.absolutePath, javaFile.absolutePath))
  }

  private fun jarDirectory(sourceDir: File, targetJar: File) {
    ZipOutputStream(FileOutputStream(targetJar)).use { zos ->
      sourceDir
        .walkTopDown()
        .filter { it.isFile }
        .forEach { file ->
          zos.putNextEntry(ZipEntry(file.relativeTo(sourceDir).path.replace(File.separatorChar, '/')))
          zos.write(file.readBytes())
          zos.closeEntry()
        }
    }
  }
}

class DummyRId {
  companion object {
    @JvmField var custom_text: Int = 0
  }
}
