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

import com.android.tools.render.common.ComposeParameterExtractor
import com.android.tools.render.common.ComposeSemanticsExtractor
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.intellij.util.ref.GCUtil
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

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

      val transformCountAfterV1 = LibraryClassBinaryCache.asmTransformCount
      assertThat(transformCountAfterV1).isEqualTo(1)

      // Refreshing with updated project classes while keeping stable library classpath unchanged
      // should reuse the cached ASM-transformed library class bytes from ClassBinaryCacheManager
      // without re-running ASM transforms, while loading the newly compiled project class.
      val classPathV2 = listOf(libDir.absolutePath, projectDirV2.absolutePath)
      val projectClassPathV2 = listOf(projectDirV2.absolutePath)
      manager.refreshClassPath(classPathV2, projectClassPathV2)

      val loaderV2 = manager.getPrivate(null).classLoader
      val libClassV2 = loaderV2.loadClass("com.example.lib.StableLib")
      val projectClassV2 = loaderV2.loadClass("com.example.app.ProjectWidget")

      assertThat(LibraryClassBinaryCache.asmTransformCount).isEqualTo(transformCountAfterV1)
      assertThat(libClassV2.getMethod("getValue").invoke(null)).isEqualTo("lib-v1")
      assertThat(projectClassV2).isNotSameInstanceAs(projectClassV1)
      assertThat(projectClassV2.getMethod("getValue").invoke(null)).isEqualTo("widget-v2")
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
  fun testLibraryClassBinaryCacheInvalidatesWhenJarTimestampChanges() {
    LibraryClassBinaryCache.clearForTest()
    val libJar = File(temporaryFolder.root, "dep.jar")
    val projectDir = temporaryFolder.newFolder("project-jar-evict")

    writeJarWithValueClass(libJar, "com/example/lib/JarLib", "jar-v1", lastModifiedMs = 1_000_000L)
    val classPath = listOf(libJar.absolutePath, projectDir.absolutePath)
    val projectClassPath = listOf(projectDir.absolutePath)

    val manager = StandaloneModuleClassLoaderManager(classPath, projectClassPath)
    try {
      val ref1 = manager.getPrivate(null)
      val cls1 = ref1.classLoader.loadClass("com.example.lib.JarLib")
      assertThat(cls1.getMethod("getValue").invoke(null)).isEqualTo("jar-v1")
      assertThat(LibraryClassBinaryCache.asmTransformCount).isEqualTo(1)
      ref1.close()

      // Replace the JAR in-place with an updated modification timestamp and an additional class in a
      // brand-new package to verify that both ClassBinaryCacheManager and UrlClassLoader's shared
      // package-hash CachePool invalidate stale entries for the modified JAR path.
      writeJarWithValueClasses(
        libJar,
        mapOf(
          "com/example/lib/JarLib" to "jar-v2",
          "com/example/lib/newbranch/NewBranchLib" to "branch-v2",
        ),
        lastModifiedMs = 2_000_000L,
      )
      manager.refreshClassPath(classPath, projectClassPath)

      val ref2 = manager.getPrivate(null)
      val cls2 = ref2.classLoader.loadClass("com.example.lib.JarLib")
      assertThat(cls2.getMethod("getValue").invoke(null)).isEqualTo("jar-v2")
      val newPkgCls = ref2.classLoader.loadClass("com.example.lib.newbranch.NewBranchLib")
      assertThat(newPkgCls.getMethod("getValue").invoke(null)).isEqualTo("branch-v2")
      assertThat(LibraryClassBinaryCache.asmTransformCount).isEqualTo(3)
      ref2.close()
    } finally {
      manager.close()
    }
  }

  @Test
  fun testLibraryClassBinaryCacheInvalidatesWhenDirectoryTimestampChanges() {
    LibraryClassBinaryCache.clearForTest()
    val libDir = temporaryFolder.newFolder("lib-dir-evict")
    val projectDir = temporaryFolder.newFolder("project-dir-evict")

    writeValueClass(libDir, "com/example/lib/DirLib", "dir-v1")
    val initialMtime = LibraryClassBinaryCache.computePathTimestamp(libDir.absolutePath, maxCacheAgeMs = 0L)
    val classPath = listOf(libDir.absolutePath, projectDir.absolutePath)
    val projectClassPath = listOf(projectDir.absolutePath)

    val manager = StandaloneModuleClassLoaderManager(classPath, projectClassPath)
    try {
      val ref1 = manager.getPrivate(null)
      val cls1 = ref1.classLoader.loadClass("com.example.lib.DirLib")
      assertThat(cls1.getMethod("getValue").invoke(null)).isEqualTo("dir-v1")
      assertThat(LibraryClassBinaryCache.asmTransformCount).isEqualTo(1)
      ref1.close()

      // Update the nested .class file on disk with a newer timestamp and refresh the TTL cache
      val updatedFile = writeValueClass(libDir, "com/example/lib/DirLib", "dir-v2")
      updatedFile.setLastModified(initialMtime + 5_000L)
      LibraryClassBinaryCache.computePathTimestamp(libDir.absolutePath, maxCacheAgeMs = 0L)

      val ref2 = manager.getPrivate(null)
      val cls2 = ref2.classLoader.loadClass("com.example.lib.DirLib")
      assertThat(cls2.getMethod("getValue").invoke(null)).isEqualTo("dir-v2")
      assertThat(LibraryClassBinaryCache.asmTransformCount).isEqualTo(2)
      ref2.close()
    } finally {
      manager.close()
    }
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
  fun testWarmDepsClassLoaderPrioritizesLocalClassResourcesOverHostParent() {
    val depsJar = File(temporaryFolder.newFolder("deps-resource-precedence"), "deps.jar")
    val projectDir = temporaryFolder.newFolder("project-resource-precedence")
    // `org/junit/Assert.class` is present on the host JUnit runner ClassLoader (which
    // FirewalledResourcesClassLoader permits for `*.class` lookups), and also packaged in `deps.jar`.
    writeJarWithValueClass(depsJar, "org/junit/Assert", "from-deps-jar", lastModifiedMs = 1_000_000L)

    val classPath = listOf(depsJar.absolutePath, projectDir.absolutePath)
    val manager = StandaloneModuleClassLoaderManager(classPath, listOf(projectDir.absolutePath))
    try {
      val sharedLoader = manager.getShared(null).classLoader
      val expectedJarUriSegment = depsJar.toURI().path

      val resourceUrl = sharedLoader.getResource("org/junit/Assert.class")
      assertThat(resourceUrl).isNotNull()
      assertThat(resourceUrl.toString()).contains(expectedJarUriSegment)

      val resourceUrls = sharedLoader.getResources("org/junit/Assert.class").toList()
      assertThat(resourceUrls).isNotEmpty()
      assertThat(resourceUrls.first().toString()).contains(expectedJarUriSegment)

      // Repeated `getPrivate` calls within the same refreshGeneration reuse the validated WarmDepsClassLoader.
      val privateRef1 = manager.getPrivate(null)
      val privateRef2 = manager.getPrivate(null)
      try {
        assertThat(privateRef1.classLoader.parent).isSameInstanceAs(sharedLoader.parent)
        assertThat(privateRef2.classLoader.parent).isSameInstanceAs(sharedLoader.parent)
      } finally {
        privateRef1.close()
        privateRef2.close()
      }
    } finally {
      manager.close()
      LibraryClassBinaryCache.clearForTest()
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

  @Test
  fun testFastServiceLoaderStringReplacementsResolveRepackagedMainDispatcherFactories() {
    LibraryClassBinaryCache.clearForTest()
    val depsDir = temporaryFolder.newFolder("deps-coroutines")
    val projectDir = temporaryFolder.newFolder("project-coroutines")

    // FastServiceLoader.loadMainDispatcherFactory() resolves AndroidDispatcherFactory and
    // TestMainDispatcherFactory via 3-arg Class.forName(name, true, clz.getClassLoader()). Because
    // clz.getClassLoader() executes between the LDC string constant and Class.forName,
    // RepackageTransform's ClassForNameRepackageMethodTransform clears lastLoadedLdc before
    // visiting Class.forName. StringReplaceTransform rewrites the string literals so 3-arg
    // Class.forName resolves the repackaged _layoutlib_._internal_.kotlinx.coroutines.* classes.
    writeValueClass(depsDir, "kotlinx/coroutines/android/AndroidDispatcherFactory", "android-dispatcher")
    writeValueClass(depsDir, "kotlinx/coroutines/test/internal/TestMainDispatcherFactory", "test-dispatcher")
    writeClassForNameProbe(
      depsDir,
      "kotlinx/coroutines/internal/FastServiceLoader",
      "kotlinx.coroutines.android.AndroidDispatcherFactory",
      "kotlinx.coroutines.test.internal.TestMainDispatcherFactory",
    )

    val classPath = listOf(depsDir.absolutePath, projectDir.absolutePath)
    val manager = StandaloneModuleClassLoaderManager(classPath, listOf(projectDir.absolutePath))
    try {
      manager.getShared(null).use { ref ->
        val cl = ref.classLoader
        val fastServiceLoaderClass = cl.loadClass("${INTERNAL_PACKAGE}kotlinx.coroutines.internal.FastServiceLoader")
        val resolvedNames = fastServiceLoaderClass.getMethod("resolveFactories").invoke(null) as String
        assertThat(resolvedNames)
          .isEqualTo(
            "${INTERNAL_PACKAGE}kotlinx.coroutines.android.AndroidDispatcherFactory," +
              "${INTERNAL_PACKAGE}kotlinx.coroutines.test.internal.TestMainDispatcherFactory"
          )
      }

      // Also verify when FastServiceLoader is loaded through PROJECT_CLASS_TRANSFORMS.
      writeClassForNameProbe(
        projectDir,
        "kotlinx/coroutines/internal/FastServiceLoader",
        "kotlinx.coroutines.android.AndroidDispatcherFactory",
        "kotlinx.coroutines.test.internal.TestMainDispatcherFactory",
      )
      manager.refreshClassPath(
        listOf(depsDir.absolutePath, projectDir.absolutePath),
        listOf(depsDir.absolutePath, projectDir.absolutePath),
      )
      manager.getShared(null).use { ref ->
        val cl = ref.classLoader
        val fastServiceLoaderClass = cl.loadClass("${INTERNAL_PACKAGE}kotlinx.coroutines.internal.FastServiceLoader")
        val resolvedNames = fastServiceLoaderClass.getMethod("resolveFactories").invoke(null) as String
        assertThat(resolvedNames)
          .isEqualTo(
            "${INTERNAL_PACKAGE}kotlinx.coroutines.android.AndroidDispatcherFactory," +
              "${INTERNAL_PACKAGE}kotlinx.coroutines.test.internal.TestMainDispatcherFactory"
          )
      }
    } finally {
      manager.close()
      LibraryClassBinaryCache.clearForTest()
    }
  }

  @Test
  fun testWarmDepsClassLoaderRetainsLoadedLibraryClassesWhileReloadingPreviewToolingAdapters() {
    LibraryClassBinaryCache.clearForTest()
    val depsDir = temporaryFolder.newFolder("deps-warm-retention")
    val projectDirV1 = temporaryFolder.newFolder("project-warm-v1")
    val projectDirV2 = temporaryFolder.newFolder("project-warm-v2")

    writeValueClass(depsDir, "com/example/lib/StableLib", "lib-v1")
    writeValueClass(depsDir, "androidx/compose/runtime/FakeRuntime", "runtime-v1")
    writeValueClass(depsDir, "androidx/compose/material3/FakeButton", "m3-v1")
    writeValueClass(depsDir, "androidx/compose/ui/tooling/data/FakeSourceInfo", "tooling-data-v1")
    writeValueClass(depsDir, "androidx/compose/ui/tooling/preview/FakePreviewParam", "tooling-preview-v1")
    writeValueClass(depsDir, "kotlin/FakeKotlinStdlib", "kotlin-v1")
    writeOneArgForNameInvokerClass(
      depsDir,
      "androidx/compose/ui/tooling/FakeComposableInvoker",
      "com.example.app.ProjectWidget",
    )
    writeOneArgForNameInvokerClass(
      depsDir,
      "androidx/wear/tiles/tooling/FakeTileServiceViewAdapter",
      "com.example.app.ProjectWidget",
    )
    writeOneArgForNameInvokerClass(
      depsDir,
      "androidx/glance/appwidget/preview/FakeGlanceAdapter",
      "com.example.app.ProjectWidget",
    )
    writeValueClass(projectDirV1, "com/example/app/ProjectWidget", "widget-v1")
    writeValueClass(projectDirV2, "com/example/app/ProjectWidget", "widget-v2")

    val classPathV1 = listOf(depsDir.absolutePath, projectDirV1.absolutePath)
    val manager = StandaloneModuleClassLoaderManager(classPathV1, listOf(projectDirV1.absolutePath))
    try {
      val loaderV1 = manager.getShared(null).classLoader as DefaultModuleClassLoader
      val libClassV1 = loaderV1.loadClass("com.example.lib.StableLib")
      val runtimeClassV1 = loaderV1.loadClass("androidx.compose.runtime.FakeRuntime")
      val m3ClassV1 = loaderV1.loadClass("androidx.compose.material3.FakeButton")
      val toolingDataClassV1 = loaderV1.loadClass("androidx.compose.ui.tooling.data.FakeSourceInfo")
      val toolingPreviewClassV1 = loaderV1.loadClass("androidx.compose.ui.tooling.preview.FakePreviewParam")
      val kotlinClassV1 = loaderV1.loadClass("${INTERNAL_PACKAGE}kotlin.FakeKotlinStdlib")
      val invokerClassV1 = loaderV1.loadClass("androidx.compose.ui.tooling.FakeComposableInvoker")
      val wearAdapterClassV1 = loaderV1.loadClass("androidx.wear.tiles.tooling.FakeTileServiceViewAdapter")
      val glanceAdapterClassV1 = loaderV1.loadClass("androidx.glance.appwidget.preview.FakeGlanceAdapter")
      assertThat(invokerClassV1.getMethod("invokeGetValue").invoke(null)).isEqualTo("widget-v1")
      assertThat(wearAdapterClassV1.getMethod("invokeGetValue").invoke(null)).isEqualTo("widget-v1")
      assertThat(glanceAdapterClassV1.getMethod("invokeGetValue").invoke(null)).isEqualTo("widget-v1")

      val classPathV2 = listOf(depsDir.absolutePath, projectDirV2.absolutePath)
      val retiredV1 = manager.refreshClassPath(classPathV2, listOf(projectDirV2.absolutePath))
      assertThat(retiredV1).isSameInstanceAs(loaderV1)
      assertThat(manager.drainRetiredClassLoaders().mapNotNull { it.get() }).containsExactly(loaderV1)

      val loaderV2 = manager.getShared(null).classLoader as DefaultModuleClassLoader
      // Classes already loaded in WarmDepsClassLoader during V1 are immediately visible via hasLoadedClass on V2,
      // while hasLoadedOwnClass tracks only classes defined directly by loaderV2.
      assertThat(loaderV2.hasLoadedClass("androidx.compose.runtime.FakeRuntime")).isTrue()
      assertThat(loaderV2.hasLoadedOwnClass("androidx.compose.runtime.FakeRuntime")).isFalse()
      assertThat(loaderV2.hasLoadedClass("${INTERNAL_PACKAGE}kotlin.FakeKotlinStdlib")).isTrue()
      assertThat(loaderV2.hasLoadedClass("androidx.compose.ui.tooling.FakeComposableInvoker")).isFalse()

      val libClassV2 = loaderV2.loadClass("com.example.lib.StableLib")
      val runtimeClassV2 = loaderV2.loadClass("androidx.compose.runtime.FakeRuntime")
      val m3ClassV2 = loaderV2.loadClass("androidx.compose.material3.FakeButton")
      val toolingDataClassV2 = loaderV2.loadClass("androidx.compose.ui.tooling.data.FakeSourceInfo")
      val toolingPreviewClassV2 = loaderV2.loadClass("androidx.compose.ui.tooling.preview.FakePreviewParam")
      val kotlinClassV2 = loaderV2.loadClass("${INTERNAL_PACKAGE}kotlin.FakeKotlinStdlib")
      val invokerClassV2 = loaderV2.loadClass("androidx.compose.ui.tooling.FakeComposableInvoker")
      val wearAdapterClassV2 = loaderV2.loadClass("androidx.wear.tiles.tooling.FakeTileServiceViewAdapter")
      val glanceAdapterClassV2 = loaderV2.loadClass("androidx.glance.appwidget.preview.FakeGlanceAdapter")
      assertThat(loaderV2.hasLoadedOwnClass("androidx.compose.ui.tooling.FakeComposableInvoker")).isTrue()

      // External libraries, Compose runtime/M3, ui-tooling-data/preview, and repackaged kotlin classes
      // remain defined in the retained WarmDepsClassLoader.
      assertThat(libClassV2).isSameInstanceAs(libClassV1)
      assertThat(runtimeClassV2).isSameInstanceAs(runtimeClassV1)
      assertThat(m3ClassV2).isSameInstanceAs(m3ClassV1)
      assertThat(toolingDataClassV2).isSameInstanceAs(toolingDataClassV1)
      assertThat(toolingPreviewClassV2).isSameInstanceAs(toolingPreviewClassV1)
      assertThat(kotlinClassV2).isSameInstanceAs(kotlinClassV1)
      // Preview tooling adapters are reloaded in the child DefaultModuleClassLoader so 1-arg
      // Class.forName resolves the updated user project class.
      assertThat(invokerClassV2).isNotSameInstanceAs(invokerClassV1)
      assertThat(wearAdapterClassV2).isNotSameInstanceAs(wearAdapterClassV1)
      assertThat(glanceAdapterClassV2).isNotSameInstanceAs(glanceAdapterClassV1)
      assertThat(invokerClassV2.getMethod("invokeGetValue").invoke(null)).isEqualTo("widget-v2")
      assertThat(wearAdapterClassV2.getMethod("invokeGetValue").invoke(null)).isEqualTo("widget-v2")
      assertThat(glanceAdapterClassV2.getMethod("invokeGetValue").invoke(null)).isEqualTo("widget-v2")
    } finally {
      manager.close()
      LibraryClassBinaryCache.clearForTest()
    }
  }

  @Test
  fun testWarmDepsClassLoaderInvalidatesOnProjectRChangeOrNewShadowingProjectClass() {
    LibraryClassBinaryCache.clearForTest()
    val depsDir = temporaryFolder.newFolder("deps-r-and-shadow")
    val projectDir = temporaryFolder.newFolder("project-r-and-shadow")

    writeValueClass(depsDir, "com/example/shared/SharedHelper", "from-deps")
    val rStringFile = writeValueClass(projectDir, "com/example/app/R\$string", "r-v1")
    rStringFile.setLastModified(1_000_000L)

    val classPath = listOf(depsDir.absolutePath, projectDir.absolutePath)
    val projectClassPath = listOf(projectDir.absolutePath)
    val manager = StandaloneModuleClassLoaderManager(classPath, projectClassPath)
    try {
      val loader1 = manager.getShared(null).classLoader
      val shared1 = loader1.loadClass("com.example.shared.SharedHelper")
      val r1 = loader1.loadClass("com.example.app.R\$string")
      assertThat(shared1.getMethod("getValue").invoke(null)).isEqualTo("from-deps")
      assertThat(r1.getMethod("getValue").invoke(null)).isEqualTo("r-v1")

      // 1. Updating linked R$string timestamp/bytes in projectClassPath invalidates WarmDepsClassLoader.
      val updatedR = writeValueClass(projectDir, "com/example/app/R\$string", "r-v2-updated")
      updatedR.setLastModified(2_000_000L)
      manager.refreshClassPath(classPath, projectClassPath)

      val loader2 = manager.getShared(null).classLoader
      val r2 = loader2.loadClass("com.example.app.R\$string")
      val shared2 = loader2.loadClass("com.example.shared.SharedHelper")
      assertThat(r2).isNotSameInstanceAs(r1)
      assertThat(r2.getMethod("getValue").invoke(null)).isEqualTo("r-v2-updated")
      assertThat(shared2).isNotSameInstanceAs(shared1)

      // 2. Adding a new user project class that shadows an already-loaded WarmDepsClassLoader class
      // invalidates WarmDepsClassLoader so the user project class takes precedence.
      writeValueClass(projectDir, "com/example/shared/SharedHelper", "shadowed-by-project")
      manager.refreshClassPath(classPath, projectClassPath)

      val loader3 = manager.getShared(null).classLoader
      val shared3 = loader3.loadClass("com.example.shared.SharedHelper")
      assertThat(shared3).isNotSameInstanceAs(shared2)
      assertThat(shared3.getMethod("getValue").invoke(null)).isEqualTo("shadowed-by-project")
    } finally {
      manager.close()
      LibraryClassBinaryCache.clearForTest()
    }
  }

  object HostFakeSystemProperties {
    @JvmField val sChangeCallbacks = ArrayList<Runnable>()
  }

  @Test
  fun testDisposedModuleClassLoadersAndBridgeContextsAreGarbageCollectedWithoutRetention() {
    LibraryClassBinaryCache.clearForTest()
    HostFakeSystemProperties.sChangeCallbacks.clear()
    val depsJar = File(temporaryFolder.root, "gc-deps.jar")
    val missingMethodDepsJar = File(temporaryFolder.root, "gc-missing-method-deps.jar")
    val projectDirV1 = temporaryFolder.newFolder("gc-project-v1")
    val projectDirV2 = temporaryFolder.newFolder("gc-project-v2")

    compileJavaSourcesToJar(
      depsJar,
      lastModifiedMs = 1_000_000L,
      mapOf(
        "androidx/compose/ui/platform/WindowRecomposer_androidKt.java" to
          """
          package androidx.compose.ui.platform;
          import java.util.LinkedHashMap;
          import java.util.Map;
          public final class WindowRecomposer_androidKt {
            public static final Map<Object, Object> animationScale = new LinkedHashMap<>();
          }
          """
            .trimIndent(),
        "androidx/compose/ui/platform/AndroidComposeView.java" to
          """
          package androidx.compose.ui.platform;
          import java.util.ArrayList;
          public final class AndroidComposeView {
            // Mirrors androidx.collection.MutableObjectList (not a java.util.Collection, exposes 0-arg clear()).
            public static final class FakeMutableObjectList {
              private final ArrayList<Object> items = new ArrayList<>();
              public void add(Object item) { items.add(item); }
              public int getSize() { return items.size(); }
              public void clear() { items.clear(); }
            }
            public static Class<?> systemPropertiesClass;
            public static final FakeMutableObjectList composeViews = new FakeMutableObjectList();
            public static Runnable systemPropertiesChangedRunnable = () -> {};
          }
          """
            .trimIndent(),
        "androidx/compose/ui/unit/TextUnit.java" to
          """
          package androidx.compose.ui.unit;
          public final class TextUnit {
            public static String toString_impl(long value) { return "14.0.sp"; }
          }
          """
            .trimIndent(),
        "androidx/compose/ui/graphics/ColorKt.java" to
          """
          package androidx.compose.ui.graphics;
          public final class ColorKt {
            public static boolean isSpecified_8_81llA(long value) { return true; }
            public static int toArgb_8_81llA(long value) { return 0xFF112233; }
          }
          """
            .trimIndent(),
      ),
    )
    // A second jar that (a) deliberately omits TextUnit.toString-impl so ComposeParameterExtractor's
    // private noMethodSentinelTarget path is exercised and verified for zero ClassLoader retention, and
    // (b) models Compose 1.11+'s WindowRecomposer_androidKt.animationScale (MutableScatterMap, not java.util.Map).
    compileJavaSourcesToJar(
      missingMethodDepsJar,
      lastModifiedMs = 1_000_000L,
      mapOf(
        "androidx/compose/ui/unit/TextUnit.java" to
          """
          package androidx.compose.ui.unit;
          public final class TextUnit {
            @Override public String toString() { return "instance-toString"; }
          }
          """
            .trimIndent(),
        "androidx/compose/ui/platform/WindowRecomposer_androidKt.java" to
          """
          package androidx.compose.ui.platform;
          import java.util.LinkedHashMap;
          public final class WindowRecomposer_androidKt {
            public static final class FakeMutableScatterMap {
              private final LinkedHashMap<Object, Object> entries = new LinkedHashMap<>();
              public void put(Object key, Object value) { entries.put(key, value); }
              public int getSize() { return entries.size(); }
              public void clear() { entries.clear(); }
            }
            public static final FakeMutableScatterMap animationScale = new FakeMutableScatterMap();
          }
          """
            .trimIndent(),
      ),
    )
    compileJavaSourcesToDir(
      projectDirV1,
      mapOf(
        "com/example/app/UserViewV1.java" to
          """
          package com.example.app;
          public final class UserViewV1 {
            public final String label = "v1";
            public String getLabel() { return label; }
          }
          """
            .trimIndent()
      ),
    )
    writeValueClass(projectDirV2, "com/example/app/UserViewV2", "v2")

    val classPathV1 = listOf(depsJar.absolutePath, projectDirV1.absolutePath)
    val manager = StandaloneModuleClassLoaderManager(classPathV1, listOf(projectDirV1.absolutePath))
    try {
      // Capture WeakReferences inside a non-inline helper method so no ClassLoader, Class<?>, or
      // BridgeContext references remain in JVM stack slots of this test frame during GC.
      val captured = exerciseV1LoaderAndCaptureRefs(manager)
      assertThat(HostFakeSystemProperties.sChangeCallbacks).hasSize(1)

      // Refreshing projectClassPath disposes childLoader while keeping WarmDepsClassLoader warm.
      val classPathV2 = listOf(depsJar.absolutePath, projectDirV2.absolutePath)
      manager.refreshClassPath(classPathV2, listOf(projectDirV2.absolutePath))
      manager.drainRetiredClassLoaders()
      loadClassWithoutRetainingFrameRef(manager, "com.example.app.UserViewV2")

      awaitGarbageCollection("disposed child DefaultModuleClassLoader", captured.childLoaderRef)
      awaitGarbageCollection("reloaded user class UserViewV1", captured.userClassRef)
      awaitGarbageCollection("WindowRecomposer BridgeContext", captured.animationBridgeContextRef)
      awaitGarbageCollection("AndroidComposeView per-render view", captured.composeViewEntryRef)
      assertThat(captured.warmDepsRef.get()).isNotNull()
      // SystemProperties callback remains registered while WarmDepsClassLoader stays warm.
      assertThat(HostFakeSystemProperties.sChangeCallbacks).hasSize(1)

      // Updating the external library JAR timestamp evicts and closes WarmDepsClassLoader, which
      // unregisters systemPropertiesChangedRunnable from HostFakeSystemProperties.sChangeCallbacks;
      // verify WarmDepsClassLoader is garbage-collected with zero ClassValue / extractor retention.
      writeJarWithValueClass(depsJar, "com/example/lib/UpdatedJarLib", "v2", lastModifiedMs = 2_000_000L)
      manager.refreshClassPath(classPathV2, listOf(projectDirV2.absolutePath))
      manager.drainRetiredClassLoaders()
      loadClassWithoutRetainingFrameRef(manager, "com.example.lib.UpdatedJarLib")

      assertThat(HostFakeSystemProperties.sChangeCallbacks).isEmpty()
      awaitGarbageCollection("evicted WarmDepsClassLoader", captured.warmDepsRef)

      // Also verify standalone 3-arg DefaultModuleClassLoader (both with valid TextUnit/ColorKt and
      // with TextUnit missing toString-impl so noMethodSentinelTarget is cached) is garbage-collected.
      val standaloneLoaderRef = exerciseStandalone3ArgLoaderAndCaptureRef(classPathV1, listOf(projectDirV1.absolutePath))
      awaitGarbageCollection("disposed 3-arg DefaultModuleClassLoader", standaloneLoaderRef)

      val (sentinelLoaderRef, scatterContextRef) =
        exerciseStandalone3ArgLoaderWithScatterMapAndCaptureRefs(
          listOf(missingMethodDepsJar.absolutePath, projectDirV1.absolutePath),
          listOf(projectDirV1.absolutePath),
        )
      awaitGarbageCollection("Compose 1.11+ MutableScatterMap animationScale BridgeContext", scatterContextRef)
      awaitGarbageCollection("disposed 3-arg DefaultModuleClassLoader with noMethodSentinel", sentinelLoaderRef)
    } finally {
      HostFakeSystemProperties.sChangeCallbacks.clear()
      manager.close()
      LibraryClassBinaryCache.clearForTest()
    }
  }

  private class CapturedGcRefs(
    val childLoaderRef: WeakReference<ClassLoader>,
    val userClassRef: WeakReference<Class<*>>,
    val warmDepsRef: WeakReference<ClassLoader>,
    val animationBridgeContextRef: WeakReference<Any>,
    val composeViewEntryRef: WeakReference<Any>,
  )

  private fun exerciseV1LoaderAndCaptureRefs(manager: StandaloneModuleClassLoaderManager): CapturedGcRefs {
    val childLoader = manager.getShared(null).classLoader as DefaultModuleClassLoader
    val warmDeps = childLoader.parent!!
    val userClass = childLoader.loadClass("com.example.app.UserViewV1")
    val userInstance = userClass.getDeclaredConstructor().newInstance()

    // Exercise ClassValue and WeakHashMap reflection caches across ComposeParameterExtractor and
    // ComposeSemanticsExtractor.
    ComposeParameterExtractor.formatTextUnitValue(0x100000000L, childLoader)
    assertThat(ComposeSemanticsExtractor.formatColorValue(0xFF11223300000000UL.toLong(), childLoader))
      .isEqualTo("#FF112233")

    // Populate retained static fields in WarmDepsClassLoader with per-render objects.
    val dummyBridgeContext = Any()
    val windowRecomposerClass = childLoader.loadClass("androidx.compose.ui.platform.WindowRecomposer_androidKt")
    @Suppress("UNCHECKED_CAST")
    val animMap = windowRecomposerClass.getField("animationScale").get(null) as MutableMap<Any, Any>
    animMap[dummyBridgeContext] = "flow"

    val acvClass = childLoader.loadClass("androidx.compose.ui.platform.AndroidComposeView")
    acvClass.getField("systemPropertiesClass").set(null, HostFakeSystemProperties::class.java)
    val runnable = acvClass.getField("systemPropertiesChangedRunnable").get(null) as Runnable
    HostFakeSystemProperties.sChangeCallbacks.add(runnable)
    val composeViews = acvClass.getField("composeViews").get(null)
    composeViews.javaClass.getMethod("add", Any::class.java).invoke(composeViews, userInstance)
    assertThat(composeViews.javaClass.getMethod("getSize").invoke(composeViews)).isEqualTo(1)

    // Release per-render state just like Renderer does after each preview.
    clearComposeUiDispatcherWork(childLoader)
    assertThat(animMap).isEmpty()
    assertThat(composeViews.javaClass.getMethod("getSize").invoke(composeViews)).isEqualTo(0)

    return CapturedGcRefs(
      childLoaderRef = WeakReference(childLoader),
      userClassRef = WeakReference(userClass),
      warmDepsRef = WeakReference(warmDeps),
      animationBridgeContextRef = WeakReference(dummyBridgeContext),
      composeViewEntryRef = WeakReference(userInstance),
    )
  }

  private fun loadClassWithoutRetainingFrameRef(manager: StandaloneModuleClassLoaderManager, fqcn: String) {
    assertThat(manager.getShared(null).classLoader.loadClass(fqcn)).isNotNull()
  }

  private fun exerciseStandalone3ArgLoaderAndCaptureRef(
    classPath: List<String>,
    projectClassPath: List<String>,
  ): WeakReference<ClassLoader> {
    val standaloneLoader =
      DefaultModuleClassLoader(DefaultModuleClassLoader::class.java.classLoader, classPath, projectClassPath)
    standaloneLoader.loadClass("com.example.app.UserViewV1")
    ComposeParameterExtractor.formatTextUnitValue(0x100000000L, standaloneLoader)
    ComposeSemanticsExtractor.formatColorValue(0xFF11223300000000UL.toLong(), standaloneLoader)
    standaloneLoader.dispose()
    return WeakReference(standaloneLoader)
  }

  private fun exerciseStandalone3ArgLoaderWithScatterMapAndCaptureRefs(
    classPath: List<String>,
    projectClassPath: List<String>,
  ): Pair<WeakReference<ClassLoader>, WeakReference<Any>> {
    val standaloneLoader =
      DefaultModuleClassLoader(DefaultModuleClassLoader::class.java.classLoader, classPath, projectClassPath)
    standaloneLoader.loadClass("com.example.app.UserViewV1")
    ComposeParameterExtractor.formatTextUnitValue(0x100000000L, standaloneLoader)
    val dummyScatterContext = Any()
    val wrClass = standaloneLoader.loadClass("androidx.compose.ui.platform.WindowRecomposer_androidKt")
    val scatterMap = wrClass.getField("animationScale").get(null)
    scatterMap.javaClass.getMethod("put", Any::class.java, Any::class.java).invoke(scatterMap, dummyScatterContext, "flow")
    assertThat(scatterMap.javaClass.getMethod("getSize").invoke(scatterMap)).isEqualTo(1)
    clearComposeUiDispatcherWork(standaloneLoader)
    assertThat(scatterMap.javaClass.getMethod("getSize").invoke(scatterMap)).isEqualTo(0)
    standaloneLoader.dispose()
    return Pair(WeakReference(standaloneLoader), WeakReference(dummyScatterContext))
  }

  /**
   * Generates a `.class` file equivalent to the following Java source:
   * ```java
   * package <pkg>;
   * public final class <ClassName> {
   *   public static String invokeGetValue() throws Exception {
   *     return (String) Class.forName("<targetClassName>").getMethod("getValue").invoke(null);
   *   }
   * }
   * ```
   */
  private fun writeOneArgForNameInvokerClass(
    rootDir: File,
    internalName: String,
    targetClassName: String,
  ) {
    val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
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
        "invokeGetValue",
        "()Ljava/lang/String;",
        null,
        arrayOf("java/lang/Exception"),
      )
    mv.visitCode()
    mv.visitLdcInsn(targetClassName)
    mv.visitMethodInsn(
      Opcodes.INVOKESTATIC,
      "java/lang/Class",
      "forName",
      "(Ljava/lang/String;)Ljava/lang/Class;",
      false,
    )
    mv.visitLdcInsn("getValue")
    mv.visitInsn(Opcodes.ICONST_0)
    mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class")
    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "java/lang/Class",
      "getMethod",
      "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
      false,
    )
    mv.visitInsn(Opcodes.ACONST_NULL)
    mv.visitInsn(Opcodes.ICONST_0)
    mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "java/lang/reflect/Method",
      "invoke",
      "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
      false,
    )
    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String")
    mv.visitInsn(Opcodes.ARETURN)
    mv.visitMaxs(0, 0)
    mv.visitEnd()
    cw.visitEnd()

    val outFile = File(rootDir, "$internalName.class")
    outFile.parentFile.mkdirs()
    outFile.writeBytes(cw.toByteArray())
  }

  /**
   * Generates a `.class` file equivalent to the following Java source:
   * ```java
   * package <pkg>;
   * public final class <ClassName> {
   *   public static String resolveFactories() throws ClassNotFoundException {
   *     StringBuilder sb = new StringBuilder();
   *     sb.append(Class.forName("<classNames[0]>", true, <ClassName>.class.getClassLoader()).getName());
   *     // ... joined with "," for each entry in classNames
   *     return sb.toString();
   *   }
   * }
   * ```
   */
  private fun writeClassForNameProbe(
    rootDir: File,
    internalName: String,
    vararg classNames: String,
  ) {
    val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
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
        "resolveFactories",
        "()Ljava/lang/String;",
        null,
        arrayOf("java/lang/ClassNotFoundException"),
      )
    mv.visitCode()
    mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
    mv.visitInsn(Opcodes.DUP)
    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)

    for ((index, targetName) in classNames.withIndex()) {
      if (index > 0) {
        mv.visitLdcInsn(",")
        mv.visitMethodInsn(
          Opcodes.INVOKEVIRTUAL,
          "java/lang/StringBuilder",
          "append",
          "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
          false,
        )
      }
      mv.visitLdcInsn(targetName)
      mv.visitInsn(Opcodes.ICONST_1)
      mv.visitLdcInsn(Type.getObjectType(internalName))
      mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "java/lang/Class",
        "getClassLoader",
        "()Ljava/lang/ClassLoader;",
        false,
      )
      mv.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "java/lang/Class",
        "forName",
        "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;",
        false,
      )
      mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "java/lang/Class",
        "getName",
        "()Ljava/lang/String;",
        false,
      )
      mv.visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "java/lang/StringBuilder",
        "append",
        "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
        false,
      )
    }

    mv.visitMethodInsn(
      Opcodes.INVOKEVIRTUAL,
      "java/lang/StringBuilder",
      "toString",
      "()Ljava/lang/String;",
      false,
    )
    mv.visitInsn(Opcodes.ARETURN)
    mv.visitMaxs(0, 0)
    mv.visitEnd()
    cw.visitEnd()

    val outFile = File(rootDir, "$internalName.class")
    outFile.parentFile.mkdirs()
    outFile.writeBytes(cw.toByteArray())
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
  private fun writeValueClass(rootDir: File, internalName: String, returnValue: String): File {
    val bytes = generateValueClassBytes(internalName, returnValue)
    val outFile = File(rootDir, "$internalName.class")
    outFile.parentFile.mkdirs()
    outFile.writeBytes(bytes)
    return outFile
  }

  /** Packages a generated `.class` file (see [writeValueClass]) into [jarFile] with the given [lastModifiedMs] timestamp. */
  private fun writeJarWithValueClass(jarFile: File, internalName: String, returnValue: String, lastModifiedMs: Long) {
    writeJarWithValueClasses(jarFile, mapOf(internalName to returnValue), lastModifiedMs)
  }

  /** Packages one or more generated `.class` files into [jarFile] with the given [lastModifiedMs] timestamp. */
  private fun writeJarWithValueClasses(jarFile: File, classes: Map<String, String>, lastModifiedMs: Long) {
    JarOutputStream(FileOutputStream(jarFile)).use { jos ->
      for ((internalName, returnValue) in classes) {
        val bytes = generateValueClassBytes(internalName, returnValue)
        val entry = JarEntry("$internalName.class")
        entry.time = lastModifiedMs
        jos.putNextEntry(entry)
        jos.write(bytes)
        jos.closeEntry()
      }
    }
    jarFile.setLastModified(lastModifiedMs)
  }

  /** Generates bytecode for the class documented in [writeValueClass]. */
  private fun generateValueClassBytes(internalName: String, returnValue: String): ByteArray {
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
    return cw.toByteArray()
  }

  private fun compileJavaSourcesToDir(outDir: File, sources: Map<String, String>) {
    val srcDir = temporaryFolder.newFolder()
    val javaFiles =
      sources.map { (relPath, javaSource) ->
        File(srcDir, relPath.replace('/', File.separatorChar))
          .apply {
            parentFile.mkdirs()
            writeText(javaSource)
          }
          .absolutePath
      }
    val compiler = ToolProvider.getSystemJavaCompiler()
    assertThat(compiler).isNotNull()
    val exitCode =
      compiler.run(null, null, null, "--release", "17", "-d", outDir.absolutePath, *javaFiles.toTypedArray())
    assertThat(exitCode).isEqualTo(0)
  }

  private fun compileJavaSourcesToJar(jarFile: File, lastModifiedMs: Long, sources: Map<String, String>) {
    val classesDir = temporaryFolder.newFolder()
    compileJavaSourcesToDir(classesDir, sources)
    val rootPath = classesDir.toPath()
    JarOutputStream(FileOutputStream(jarFile)).use { jos ->
      classesDir
        .walkTopDown()
        .filter { it.isFile && it.name.endsWith(".class") }
        .sortedBy { it.absolutePath }
        .forEach { classFile ->
          val entryName = rootPath.relativize(classFile.toPath()).toString().replace('\\', '/')
          val entry = JarEntry(entryName).apply { time = lastModifiedMs }
          jos.putNextEntry(entry)
          jos.write(classFile.readBytes())
          jos.closeEntry()
        }
    }
    jarFile.setLastModified(lastModifiedMs)
  }

  /**
   * Triggers GC within a 10s deadline until [ref] is cleared.
   */
  private fun awaitGarbageCollection(description: String, ref: WeakReference<*>) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (System.nanoTime() < deadline && ref.get() != null) {
      GCUtil.tryGcSoftlyReachableObjects()
      System.gc()
      Thread.sleep(10)
    }
    assertWithMessage("Expected $description to be garbage-collected").that(ref.get()).isNull()
  }
}
