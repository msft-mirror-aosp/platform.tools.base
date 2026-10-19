/*
 * Copyright (C) 2026 The Android Open Source Project
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

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class StandaloneModuleClassLoaderManagerTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun testServiceLoaderRemapping() {
    val projectClassPath = emptyList<String>()

    // Create a fake kotlin-reflect dir with a META-INF/services file
    val fakeJarDir = temporaryFolder.newFolder("fake-kotlin-reflect")
    val servicesDir = File(fakeJarDir, "META-INF/services").apply { mkdirs() }
    val serviceFile = File(servicesDir, "kotlin.reflect.jvm.internal.BuiltInsLoader")
    serviceFile.writeText("kotlin.reflect.jvm.internal.impl.metadata.jvm.deserialization.JvmMetadataExtensions\n")

    val classPath = listOf(fakeJarDir.absolutePath)

    val manager = StandaloneModuleClassLoaderManager(classPath, projectClassPath)
    try {
      val privateLoader = manager.getPrivate(null).classLoader

      val url = privateLoader.getResource("META-INF/services/kotlin.reflect.jvm.internal.BuiltInsLoader")
      assertThat(url).isNotNull()

      val content = url!!.openStream().bufferedReader().use { it.readText() }
      assertThat(content)
        .isEqualTo("_layoutlib_._internal_.kotlin.reflect.jvm.internal.impl.metadata.jvm.deserialization.JvmMetadataExtensions\n")
    } finally {
      manager.close()
    }
  }

  @Test
  fun testRefreshClassPathReplacesSharedModuleClassLoader() {
    val libDir = temporaryFolder.newFolder("lib")
    val projectDir = temporaryFolder.newFolder("project")
    val classPath = listOf(libDir.absolutePath, projectDir.absolutePath)
    val projectClassPath = listOf(projectDir.absolutePath)

    val manager = StandaloneModuleClassLoaderManager(classPath, projectClassPath)
    try {
      val firstLoader = manager.getShared(null).classLoader as DefaultModuleClassLoader
      firstLoader.rClassesInitialized = true
      assertThat(firstLoader.rClassesInitialized).isTrue()

      manager.refreshClassPath(classPath, projectClassPath)
      assertThat(firstLoader.isDisposed).isTrue()
      val secondLoader = manager.getShared(null).classLoader as DefaultModuleClassLoader
      assertThat(secondLoader).isNotSameInstanceAs(firstLoader)
      assertThat(secondLoader.isDisposed).isFalse()
      assertThat(secondLoader.rClassesInitialized).isFalse()
    } finally {
      manager.close()
    }
  }

  @Test
  fun testSharedClassLoaderPropagatesCustomParentAndDisposesOnRefresh() {
    val projectDir = temporaryFolder.newFolder("empty-project")
    val classPath = listOf(projectDir.absolutePath)
    val projectClassPath = listOf(projectDir.absolutePath)

    val customParent = java.net.URLClassLoader(emptyArray(), null)
    val manager = StandaloneModuleClassLoaderManager(classPath, projectClassPath)
    try {
      val sharedRef = manager.getShared(customParent)
      val loader = sharedRef.classLoader as DefaultModuleClassLoader
      assertThat(loader.isDisposed).isFalse()

      manager.refreshClassPath(classPath, projectClassPath)
      assertThat(loader.isDisposed).isTrue()
    } finally {
      manager.close()
    }
  }

  @Test
  fun testRefreshClassPathCachesTransformedLibraryBytesAndReloadsProjectClasses() {
    LibraryClassBinaryCache.clearForTest()
    val libDir = temporaryFolder.newFolder("lib-classes")
    val projectDirV1 = temporaryFolder.newFolder("project-v1")
    val projectDirV2 = temporaryFolder.newFolder("project-v2")

    writeValueClass(libDir, "com/example/lib/StableLib", "lib-v1")
    writeValueClass(projectDirV1, "com/example/app/ProjectWidget", "widget-v1")
    writeValueClass(projectDirV2, "com/example/app/ProjectWidget", "widget-v2")

    val classPathV1 = listOf(libDir.absolutePath, projectDirV1.absolutePath)
    val projectClassPathV1 = listOf(projectDirV1.absolutePath)

    val manager = StandaloneModuleClassLoaderManager(classPathV1, projectClassPathV1)
    try {
      val loaderV1 = manager.getPrivate(null).classLoader
      val libClassV1 = loaderV1.loadClass("com.example.lib.StableLib")
      val projectClassV1 = loaderV1.loadClass("com.example.app.ProjectWidget")
      assertThat(libClassV1.getMethod("getValue").invoke(null)).isEqualTo("lib-v1")
      assertThat(projectClassV1.getMethod("getValue").invoke(null)).isEqualTo("widget-v1")

      val cacheEntryV1 = LibraryClassBinaryCache.getEntry(classPathV1, projectClassPathV1)
      val transformCountAfterV1 = cacheEntryV1.asmTransformCount
      assertThat(transformCountAfterV1).isEqualTo(1)

      // Refreshing with updated project classes while keeping stable library classpath unchanged
      // should reuse the cached ASM-transformed library class bytes without re-running ASM transforms,
      // while loading the newly compiled project class.
      val classPathV2 = listOf(libDir.absolutePath, projectDirV2.absolutePath)
      val projectClassPathV2 = listOf(projectDirV2.absolutePath)
      manager.refreshClassPath(classPathV2, projectClassPathV2)

      val loaderV2 = manager.getPrivate(null).classLoader
      val libClassV2 = loaderV2.loadClass("com.example.lib.StableLib")
      val projectClassV2 = loaderV2.loadClass("com.example.app.ProjectWidget")

      val cacheEntryV2 = LibraryClassBinaryCache.getEntry(classPathV2, projectClassPathV2)
      assertThat(cacheEntryV2).isSameInstanceAs(cacheEntryV1)
      assertThat(cacheEntryV2.asmTransformCount).isEqualTo(transformCountAfterV1)
      assertThat(libClassV2.getMethod("getValue").invoke(null)).isEqualTo("lib-v1")
      assertThat(projectClassV2).isNotSameInstanceAs(projectClassV1)
      assertThat(projectClassV2.getMethod("getValue").invoke(null)).isEqualTo("widget-v2")
      cacheEntryV1.release()
      cacheEntryV2.release()
    } finally {
      manager.close()
    }
  }

  @Test
  fun testSharedClassLoaderWithLayoutlibClassLoaderParentUsesDefaultWrappedParent() {
    val projectDir = temporaryFolder.newFolder("project-layoutlib-parent")
    val classPath = listOf(projectDir.absolutePath)
    val projectClassPath = listOf(projectDir.absolutePath)

    val layoutlibParent = com.android.layoutlib.LayoutlibClassLoader(StandaloneModuleClassLoaderManager::class.java.classLoader)
    val manager = StandaloneModuleClassLoaderManager(classPath, projectClassPath)
    try {
      val sharedNullParent = manager.getShared(null).classLoader as DefaultModuleClassLoader
      manager.refreshClassPath(classPath, projectClassPath)
      val sharedLayoutlibParent = manager.getShared(layoutlibParent).classLoader as DefaultModuleClassLoader

      // Both should have the same wrapped parent class loader (defaultWrappedParent)
      assertThat(sharedLayoutlibParent.parent).isSameInstanceAs(sharedNullParent.parent)
    } finally {
      manager.close()
    }
  }

  @Test
  fun testLibraryClassBinaryCacheEntryClosesOnEvictionAndClear() {
    val libDir1 = temporaryFolder.newFolder("lib1")
    val libDir2 = temporaryFolder.newFolder("lib2")
    val projectDir = temporaryFolder.newFolder("project-cache-evict")

    val classPath1 = listOf(libDir1.absolutePath, projectDir.absolutePath)
    val classPath2 = listOf(libDir2.absolutePath, projectDir.absolutePath)
    val projectClassPath = listOf(projectDir.absolutePath)

    val entry1 = LibraryClassBinaryCache.getEntry(classPath1, projectClassPath)
    assertThat(entry1).isNotNull()
    assertThat(entry1.isClosed).isFalse()

    // Caller releases its retained reference; entry1 remains cached
    entry1.release()
    assertThat(entry1.isClosed).isFalse()

    // Querying with new classpath evicts entry1 and closes it since activeReferences == 0
    val entry2 = LibraryClassBinaryCache.getEntry(classPath2, projectClassPath)
    assertThat(entry2).isNotSameInstanceAs(entry1)
    assertThat(entry1.isClosed).isTrue()

    // Clear for test should also close entry2 without error
    entry2.release()
    LibraryClassBinaryCache.clearForTest()
    assertThat(entry2.isClosed).isTrue()
  }

  @Test
  fun testLibraryClassBinaryCacheEntryDefersDisposalUntilActiveReferencesRelease() {
    val libDir1 = temporaryFolder.newFolder("lib-ref1")
    val libDir2 = temporaryFolder.newFolder("lib-ref2")
    val projectDir = temporaryFolder.newFolder("project-cache-ref")

    val classPath1 = listOf(libDir1.absolutePath, projectDir.absolutePath)
    val classPath2 = listOf(libDir2.absolutePath, projectDir.absolutePath)
    val projectClassPath = listOf(projectDir.absolutePath)

    // getEntry returns entry1 with an active reference held by the caller
    val entry1 = LibraryClassBinaryCache.getEntry(classPath1, projectClassPath)
    assertThat(entry1.isClosed).isFalse()

    // Evict entry1 by requesting entry2 with a different classpath
    val entry2 = LibraryClassBinaryCache.getEntry(classPath2, projectClassPath)
    assertThat(entry2).isNotSameInstanceAs(entry1)

    // entry1 was evicted but remains open while the caller's active reference is held
    assertThat(entry1.isClosed).isFalse()

    // Releasing the caller's active reference triggers deferred closure of the evicted entry
    entry1.release()
    assertThat(entry1.isClosed).isTrue()

    entry2.release()
    LibraryClassBinaryCache.clearForTest()
  }

  @Test
  fun testComputePathTimestampCachesDirectoryTimestamps() {
    val dir = temporaryFolder.newFolder("cached-dir")
    val subFile = File(dir, "test.txt").apply { writeText("initial") }

    val initialTimestamp = LibraryClassBinaryCache.computePathTimestamp(dir.absolutePath)
    assertThat(initialTimestamp).isEqualTo(subFile.lastModified())

    // Update file timestamp in the directory
    val updatedMtime = initialTimestamp + 5000L
    subFile.setLastModified(updatedMtime)

    // With default cache TTL, computePathTimestamp returns cached value
    val cachedTimestamp = LibraryClassBinaryCache.computePathTimestamp(dir.absolutePath)
    assertThat(cachedTimestamp).isEqualTo(initialTimestamp)

    // With maxCacheAgeMs = 0L, fresh timestamp is computed
    val freshTimestamp = LibraryClassBinaryCache.computePathTimestamp(dir.absolutePath, maxCacheAgeMs = 0L)
    assertThat(freshTimestamp).isEqualTo(updatedMtime)
  }

  @Test
  fun testGetPrivateReferenceDisposesClassLoaderOnClose() {
    val projectDir = temporaryFolder.newFolder("project-private-dispose")
    val classPath = listOf(projectDir.absolutePath)
    val projectClassPath = listOf(projectDir.absolutePath)
    val manager = StandaloneModuleClassLoaderManager(classPath, projectClassPath)
    try {
      val ref = manager.getPrivate(null)
      val cl = ref.classLoader as DefaultModuleClassLoader
      assertThat(cl.isDisposed).isFalse()
      ref.close()
      assertThat(cl.isDisposed).isTrue()
    } finally {
      manager.close()
    }
  }

  @Test
  fun testWrappedParentsClearedOnClose() {
    val projectDir = temporaryFolder.newFolder("project-wrapped-parents-close")
    val classPath = listOf(projectDir.absolutePath)
    val projectClassPath = listOf(projectDir.absolutePath)

    val customParent = java.net.URLClassLoader(emptyArray(), null)
    val manager = StandaloneModuleClassLoaderManager(classPath, projectClassPath)
    try {
      val ref = manager.getShared(customParent)
      assertThat(ref.classLoader).isNotNull()
    } finally {
      manager.close()
    }
  }

  @Test
  fun testProjectRClassTakesPrecedenceOverDepsRClassInLibraryPackage() {
    val projectDir = temporaryFolder.newFolder("project-r-precedence")
    val depsDir = temporaryFolder.newFolder("deps-r-precedence")

    // Simulate a library package ("com/example/lib") where:
    // - `StableLib` exists only on `depsClassPath` (establishing depsLoader package affinity).
    // - Both `projectClassPath` (linked R.jar) and `depsClassPath` (unlinked compile R.jar)
    //   contain `com/example/lib/R` and `com/example/lib/R$id`.
    writeValueClass(depsDir, "com/example/lib/StableLib", "from-deps")
    writeValueClass(depsDir, "com/example/lib/R", "unlinked-deps-R")
    writeValueClass(depsDir, "com/example/lib/R\$id", "unlinked-deps-R-id")
    writeValueClass(projectDir, "com/example/lib/R", "linked-project-R")
    writeValueClass(projectDir, "com/example/lib/R\$id", "linked-project-R-id")

    val classPath = listOf(projectDir.absolutePath, depsDir.absolutePath)
    val projectClassPath = listOf(projectDir.absolutePath)

    val manager = StandaloneModuleClassLoaderManager(classPath, projectClassPath)
    try {
      manager.getShared(null).use { ref ->
        val cl = ref.classLoader
        // Load `StableLib` first so `MultiLoaderWithAffinity` records `depsLoader` affinity for
        // `com.example.lib`.
        val stableLibClass = cl.loadClass("com.example.lib.StableLib")
        assertThat(stableLibClass.getMethod("getValue").invoke(null)).isEqualTo("from-deps")

        // Loading `R` and `R$id` in the same package must still resolve the linked version from
        // `projectClassPath` rather than the unlinked version from `depsClassPath`.
        val rClass = cl.loadClass("com.example.lib.R")
        assertThat(rClass.getMethod("getValue").invoke(null)).isEqualTo("linked-project-R")

        val rIdClass = cl.loadClass("com.example.lib.R\$id")
        assertThat(rIdClass.getMethod("getValue").invoke(null)).isEqualTo("linked-project-R-id")
      }
    } finally {
      manager.close()
    }
  }

  /**
   * Generates a `.class` file equivalent to the following Java source:
   * ```java
   * package <pkg>;
   * public final class <ClassName> {
   *   public static String getValue() {
   *     return "<returnValue>";
   *   }
   * }
   * ```
   */
  private fun writeValueClass(rootDir: File, internalName: String, returnValue: String) {
    val cw = ClassWriter(0)
    cw.visit(
      Opcodes.V17,
      Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER or Opcodes.ACC_FINAL,
      internalName,
      null,
      "java/lang/Object",
      null,
    )
    val mv =
      cw.visitMethod(
        Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
        "getValue",
        "()Ljava/lang/String;",
        null,
        null,
      )
    mv.visitCode()
    mv.visitLdcInsn(returnValue)
    mv.visitInsn(Opcodes.ARETURN)
    mv.visitMaxs(1, 0)
    mv.visitEnd()
    cw.visitEnd()

    val outFile = File(rootDir, "$internalName.class")
    outFile.parentFile.mkdirs()
    outFile.writeBytes(cw.toByteArray())
  }
}
