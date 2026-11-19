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

package com.android.build.gradle.internal.dexing

import com.android.build.gradle.internal.services.R8ClassloaderBuildService
import com.android.build.gradle.internal.tasks.testR8Jar
import com.android.builder.dexing.ClassFileInputs
import com.android.builder.dexing.DexArchiveBuilder
import com.android.builder.dexing.DexArchiveBuilderException
import com.android.builder.dexing.DexArchiveMerger
import com.android.builder.dexing.DexArchives
import com.android.builder.dexing.DexingType
import com.android.builder.dexing.KeepRulesConfig
import com.android.builder.dexing.L8OutputMode
import com.android.builder.dexing.MainDexListConfig
import com.android.builder.dexing.ProguardConfig
import com.android.builder.dexing.R8OutputType
import com.android.builder.dexing.R8Tools
import com.android.builder.dexing.R8ToolsImpl
import com.android.builder.dexing.R8Version
import com.android.builder.dexing.ToolConfig
import com.android.builder.multidex.D8MainDexList
import com.android.ide.common.blame.Message
import com.android.ide.common.blame.MessageReceiver
import com.android.testutils.TestInputsGenerator
import com.android.testutils.TestUtils
import com.android.testutils.truth.DexSubject.assertThatDex
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class CreateR8ToolsTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun withIsolatedClassLoader_usesImplementationFromThatLoader() {
    R8ClassloaderBuildService.createClassLoader(setOf(testR8Jar)).use { isolatedLoader ->
      val r8Tools = createR8Tools(isolatedLoader)

      // Loaded by the isolated loader, so it runs the R8 in that loader...
      assertThat(r8Tools.javaClass.classLoader).isSameInstanceAs(isolatedLoader)
      // ...and it's a different class from AGP's own R8ToolsImpl, even though the name is the same.
      assertThat(r8Tools.javaClass).isNotSameInstanceAs(R8ToolsImpl::class.java)
    }
  }

  @Test
  fun getVersionString_throughIsolatedClassLoader() {
    R8ClassloaderBuildService.createClassLoader(setOf(testR8Jar)).use { isolatedLoader ->
      val r8Tools = createR8Tools(isolatedLoader)

      assertThat(r8Tools.getVersionString()).contains(R8Version.VERSION_AGP_WAS_SHIPPED_WITH)
    }
  }

  @Test
  fun runTraceReferences_throughIsolatedClassLoader() {
    val androidJar = TestUtils.resolvePlatformPath("android.jar", TestUtils.TestType.AGP)
    val sourceJar = temporaryFolder.root.toPath().resolve("source.jar")
    TestInputsGenerator.jarWithEmptyClasses(sourceJar, listOf("test/Source"))

    R8ClassloaderBuildService.createClassLoader(setOf(testR8Jar)).use { isolatedLoader ->
      val r8Tools = createR8Tools(isolatedLoader)
      assertThat(r8Tools.javaClass.classLoader).isSameInstanceAs(isolatedLoader)

      // All references resolve against android.jar, so the check passes.
      r8Tools.runTraceReferences(listOf(sourceJar), listOf(androidJar), emptyMap())

      // Without android.jar, java.lang.Object can't be resolved, so the isolated R8's check fails.
      assertThrows(Throwable::class.java) { r8Tools.runTraceReferences(listOf(sourceJar), emptyList(), emptyMap()) }
    }
  }

  @Test
  fun runTraceReferenceToolAndL8_throughIsolatedClassLoader() {
    val androidJar = TestUtils.resolvePlatformPath("android.jar", TestUtils.TestType.AGP)
    val desugarLibJar = TestUtils.getDesugarLibJar()
    val desugarLibConfig = TestUtils.getDesugarLibConfigContent()
    val tmp = temporaryFolder.root.toPath()

    // An app class that uses java.util.Optional, which library desugaring rewrites to j$.util.Optional below minSdk 24.
    val appClassesJar = tmp.resolve("app_classes.jar")
    writeClassUsingOptional(appClassesJar)
    val emptyJavaResJar = tmp.resolve("app_java_res.jar")
    TestInputsGenerator.jarWithEmptyClasses(emptyJavaResJar, emptyList())
    val appDexDir = temporaryFolder.newFolder("app_dex").toPath()
    val desugaredLibDir = temporaryFolder.newFolder("desugared_lib").toPath()
    val keepRules = tmp.resolve("keep_rules.txt")
    val l8DexDir = temporaryFolder.newFolder("l8_dex").toPath()

    R8ClassloaderBuildService.createClassLoader(setOf(testR8Jar)).use { isolatedLoader ->
      val r8Tools = createR8Tools(isolatedLoader)
      assertThat(r8Tools.javaClass.classLoader).isSameInstanceAs(isolatedLoader)

      // Dex the app with library desugaring, so its dex refers to j$.util.Optional.
      r8Tools.runR8(
        inputClasses = listOf(appClassesJar),
        output = appDexDir,
        inputJavaResJar = emptyJavaResJar,
        javaResourcesJar = tmp.resolve("app_java_res_out.jar"),
        libraries = listOf(androidJar),
        classpath = emptyList(),
        toolConfig =
          ToolConfig(
            minSdkVersion = 21,
            debuggable = true,
            disableTreeShaking = false,
            disableMinification = true,
            disableDesugaring = false,
            fullMode = true,
            strictFullModeForKeepRules = false,
            isolatedSplits = null,
            r8OutputType = R8OutputType.DEX,
            mainDexListDisallowed = true,
          ),
        proguardConfig = ProguardConfig(emptyList(), null, listOf("-keep class test.App { *; }"), null, null),
        mainDexListConfig = MainDexListConfig(),
        resourceShrinkingConfig = null,
        messageReceiver = noOpMessageReceiver,
        featureClassJars = emptyList(),
        featureJavaResourceJars = emptyList(),
        featureDexDir = null,
        featureJavaResourceOutputDir = null,
        libConfiguration = desugarLibConfig,
        inputArtProfile = null,
        outputArtProfile = null,
        inputProfileForDexStartupOptimization = null,
        r8Metadata = null,
        partialShrinking = null,
        r8ExecutorService = MoreExecutors.newDirectExecutorService(),
      )

      // The desugared desugar library in class file format, which is the target for keep rule generation.
      r8Tools.runL8(
        inputClasses = listOf(desugarLibJar),
        output = desugaredLibDir,
        libConfiguration = desugarLibConfig,
        libraries = listOf(androidJar),
        minSdkVersion = 21,
        keepRules = KeepRulesConfig(emptyList(), emptyList()),
        isDebuggable = true,
        outputMode = L8OutputMode.ClassFile,
        inputArtProfile = null,
        outputArtProfile = null,
        inputMappingFile = null,
        outputMappingFile = null,
        inputPartitionMappingFile = null,
        outputPartitionMappingFile = null,
        tempDir = null,
      )

      r8Tools.runTraceReferenceTool(
        fullBootClasspath = listOf(androidJar),
        desugaredDesugarLib = listOf(desugaredLibDir.resolve("desugared-desugar-lib.jar")),
        dexFiles = listOf(appDexDir.resolve("classes.dex")),
        keepRuleOutput = keepRules,
      )
      assertThat(Files.readString(keepRules)).contains("j$.util.Optional")

      // Dex and shrink the desugar library with only the generated keep rules.
      r8Tools.runL8(
        inputClasses = listOf(desugarLibJar),
        output = l8DexDir,
        libConfiguration = desugarLibConfig,
        libraries = listOf(androidJar),
        minSdkVersion = 21,
        keepRules = KeepRulesConfig(listOf(keepRules), emptyList()),
        isDebuggable = true,
        outputMode = L8OutputMode.DexIndexed,
        inputArtProfile = null,
        outputArtProfile = null,
        inputMappingFile = null,
        outputMappingFile = null,
        inputPartitionMappingFile = null,
        outputPartitionMappingFile = null,
        tempDir = null,
      )
    }

    val l8Dex = l8DexDir.resolve("classes1000.dex")
    assertThatDex(l8Dex.toFile()).containsClass("Lj$/util/Optional;")
    // Shrunk with the generated keep rules: desugar library classes the app doesn't use are removed.
    assertThatDex(l8Dex.toFile()).doesNotContainClasses("Lj$/time/LocalTime;")
  }

  @Test
  fun generateDesugaredMethods_throughIsolatedClassLoader() {
    val androidJar = TestUtils.resolvePlatformPath("android.jar", TestUtils.TestType.AGP).toFile()
    val desugarLibConfig = TestUtils.getDesugarLibConfigContent()
    val direct = R8ToolsImpl()

    R8ClassloaderBuildService.createClassLoader(setOf(testR8Jar)).use { isolatedLoader ->
      val isolated = createR8Tools(isolatedLoader)
      assertThat(isolated.javaClass.classLoader).isSameInstanceAs(isolatedLoader)

      // Without core library desugaring: only the methods D8 backports.
      val backported = isolated.generateDesugaredMethods(null, emptySet(), 21)
      assertThat(backported).isNotEmpty()
      assertThat(backported).containsExactlyElementsIn(direct.generateDesugaredMethods(null, emptySet(), 21))

      // With core library desugaring, the desugared library APIs are listed too.
      val withDesugarLib = isolated.generateDesugaredMethods(desugarLibConfig, setOf(androidJar), 21)
      assertThat(withDesugarLib).containsAtLeastElementsIn(backported)
      assertThat(withDesugarLib.size).isGreaterThan(backported.size)
      assertThat(withDesugarLib).containsExactlyElementsIn(direct.generateDesugaredMethods(desugarLibConfig, setOf(androidJar), 21))
    }
  }

  @Test
  fun createD8DexBuilder_throughIsolatedClassLoader() {
    val androidJar = TestUtils.resolvePlatformPath("android.jar", TestUtils.TestType.AGP)
    val tmp = temporaryFolder.root.toPath()
    val inputJar = tmp.resolve("input.jar")
    writeClassUsingOptional(inputJar)
    val brokenJar = tmp.resolve("broken.jar")
    JarOutputStream(Files.newOutputStream(brokenJar)).use { out ->
      out.putNextEntry(JarEntry("test/Broken.class"))
      out.write(byteArrayOf(1, 2, 3, 4))
      out.closeEntry()
    }
    val dexDir = temporaryFolder.newFolder("dex").toPath()

    R8ClassloaderBuildService.createClassLoader(setOf(testR8Jar)).use { isolatedLoader ->
      val r8Tools = createR8Tools(isolatedLoader)
      r8Tools.createClassFileProviderFactory(listOf(androidJar)).use { bootclasspath ->
        r8Tools.createClassFileProviderFactory(emptyList()).use { classpath ->
          // The providers and the builder are loaded next to the isolated R8...
          assertThat(bootclasspath.javaClass.classLoader).isSameInstanceAs(isolatedLoader)
          val dexBuilder = createD8DexBuilder(r8Tools, bootclasspath, classpath)
          assertThat(dexBuilder.javaClass.classLoader).isSameInstanceAs(isolatedLoader)
          // ...but the builder is still AGP's DexArchiveBuilder type, so AGP can call it without reflection.
          assertThat(dexBuilder).isInstanceOf(DexArchiveBuilder::class.java)

          ClassFileInputs.fromPath(inputJar).use { input ->
            input.entries { _, _ -> true }.use { entries -> dexBuilder.convert(entries, dexDir, null) }
          }
          assertThatDex(dexDir.resolve("classes.dex").toFile()).containsClass("Ltest/App;")

          // D8 failures surface as AGP's own DexArchiveBuilderException, which AGP catches when reporting dexing errors.
          ClassFileInputs.fromPath(brokenJar).use { input ->
            input
              .entries { _, _ -> true }
              .use { entries ->
                assertThrows(DexArchiveBuilderException::class.java) {
                  dexBuilder.convert(entries, temporaryFolder.newFolder("broken_dex").toPath(), null)
                }
              }
          }

          // Class path providers from another ClassLoader are rejected with a clear message rather than a ClassCastException.
          R8ClassloaderBuildService.createClassLoader(setOf(testR8Jar)).use { otherLoader ->
            createR8Tools(otherLoader).createClassFileProviderFactory(emptyList()).use { otherProvider ->
              val e = assertThrows(IllegalArgumentException::class.java) { createD8DexBuilder(r8Tools, otherProvider, classpath) }
              assertThat(e.message).contains("same ClassLoader")
            }
          }
        }
      }
    }
  }

  @Test
  fun createD8DexMerger_throughIsolatedClassLoader() {
    val androidJar = TestUtils.resolvePlatformPath("android.jar", TestUtils.TestType.AGP)
    val inputJar = temporaryFolder.root.toPath().resolve("input.jar")
    writeClassUsingOptional(inputJar)
    val mergedDir = temporaryFolder.newFolder("merged").toPath()

    R8ClassloaderBuildService.createClassLoader(setOf(testR8Jar)).use { isolatedLoader ->
      val r8Tools = createR8Tools(isolatedLoader)
      val dexDir = dexWith(r8Tools, androidJar, inputJar, "dex")

      val merger =
        r8Tools.createD8DexMerger(
          messageReceiver = noOpMessageReceiver,
          dexingType = DexingType.NATIVE_MULTIDEX,
          minSdkVersion = 21,
          isDebuggable = true,
          d8ExecutorService = MoreExecutors.newDirectExecutorService(),
        )
      // The merger is loaded next to the isolated R8, but it is still AGP's DexArchiveMerger type.
      assertThat(merger.javaClass.classLoader).isSameInstanceAs(isolatedLoader)
      assertThat(merger).isInstanceOf(DexArchiveMerger::class.java)

      // The dex archive entries are read by AGP's code and passed to the merger, as DexMergingTask does.
      DexArchives.fromInput(dexDir).use { dexArchive ->
        merger.mergeDexArchives(dexArchive.sortedDexArchiveEntries, emptyList(), mergedDir, null, null, null, null, null, null, null)
      }
      assertThatDex(mergedDir.resolve("classes.dex").toFile()).containsClass("Ltest/App;")
    }
  }

  @Test
  fun generateMainDexList_throughIsolatedClassLoader() {
    val androidJar = TestUtils.resolvePlatformPath("android.jar", TestUtils.TestType.AGP)
    val inputJar = temporaryFolder.root.toPath().resolve("input.jar")
    writeClassUsingOptional(inputJar)

    R8ClassloaderBuildService.createClassLoader(setOf(testR8Jar)).use { isolatedLoader ->
      val r8Tools = createR8Tools(isolatedLoader)
      val dexDir = dexWith(r8Tools, androidJar, inputJar, "dex")

      val mainDexList =
        r8Tools.generateMainDexList(
          mainDexRules = listOf("-keep class test.App"),
          mainDexRulesFiles = emptyList(),
          programFiles = listOf(dexDir),
          libraryFiles = listOf(androidJar),
          messageReceiver = noOpMessageReceiver,
        )
      assertThat(mainDexList).containsExactly("test/App.class")

      // The same class in two program inputs fails. The failure is AGP's own MainDexListException, which is shared with the isolated
      // ClassLoader, while the rest of D8MainDexList is loaded next to the isolated R8.
      val duplicateDexDir = dexWith(r8Tools, androidJar, inputJar, "duplicate_dex")
      val e =
        assertThrows(D8MainDexList.MainDexListException::class.java) {
          r8Tools.generateMainDexList(
            mainDexRules = emptyList(),
            mainDexRulesFiles = emptyList(),
            programFiles = listOf(dexDir, duplicateDexDir),
            libraryFiles = listOf(androidJar),
            messageReceiver = noOpMessageReceiver,
          )
        }
      assertThat(e.message).contains("is defined multiple times")
      assertThat(isolatedLoader.loadClass("com.android.builder.multidex.D8MainDexList").classLoader).isSameInstanceAs(isolatedLoader)
    }
  }

  @Test
  fun generateGlobalSynthetics_throughIsolatedClassLoader() {
    val androidJar = TestUtils.resolvePlatformPath("android.jar", TestUtils.TestType.AGP)
    val outputDir = temporaryFolder.newFolder("global_synthetics").toPath()

    R8ClassloaderBuildService.createClassLoader(setOf(testR8Jar)).use { isolatedLoader ->
      createR8Tools(isolatedLoader).generateGlobalSynthetics(listOf(androidJar), 21, outputDir)
    }

    // The global synthetics are written as a dex file with at least one class (class_defs_size is at offset 0x60 of the dex header).
    val dexBytes = Files.readAllBytes(outputDir.resolve("classes.dex"))
    assertThat(ByteBuffer.wrap(dexBytes).order(ByteOrder.LITTLE_ENDIAN).getInt(0x60)).isGreaterThan(0)
  }

  /** Dexes [inputJar] into a new directory with the dex builder from [r8Tools], and returns the directory. */
  private fun dexWith(r8Tools: R8Tools, androidJar: Path, inputJar: Path, dirName: String): Path {
    val dexDir = temporaryFolder.newFolder(dirName).toPath()
    r8Tools.createClassFileProviderFactory(listOf(androidJar)).use { bootclasspath ->
      r8Tools.createClassFileProviderFactory(emptyList()).use { classpath ->
        val dexBuilder = createD8DexBuilder(r8Tools, bootclasspath, classpath)
        ClassFileInputs.fromPath(inputJar).use { input ->
          input.entries { _, _ -> true }.use { entries -> dexBuilder.convert(entries, dexDir, null) }
        }
      }
    }
    return dexDir
  }

  private fun createD8DexBuilder(r8Tools: R8Tools, desugarBootclasspath: Closeable, desugarClasspath: Closeable): DexArchiveBuilder =
    r8Tools.createD8DexBuilder(
      minSdkVersion = 21,
      debuggable = true,
      dexPerClass = false,
      withDesugaring = true,
      desugarBootclasspath = desugarBootclasspath,
      desugarClasspath = desugarClasspath,
      coreLibDesugarConfig = null,
      enableApiModeling = false,
      messageReceiver = noOpMessageReceiver,
      useNoOpGlobalSyntheticsConsumer = false,
    )

  /** Writes a jar with `test.App`, whose `static Object get()` returns `java.util.Optional.empty()`. */
  private fun writeClassUsingOptional(jar: Path) {
    val classWriter = ClassWriter(ClassWriter.COMPUTE_MAXS)
    classWriter.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, "test/App", null, "java/lang/Object", null)
    classWriter.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "get", "()Ljava/lang/Object;", null, null).apply {
      visitCode()
      visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Optional", "empty", "()Ljava/util/Optional;", false)
      visitInsn(Opcodes.ARETURN)
      visitMaxs(0, 0)
      visitEnd()
    }
    classWriter.visitEnd()
    JarOutputStream(Files.newOutputStream(jar)).use { out ->
      out.putNextEntry(JarEntry("test/App.class"))
      out.write(classWriter.toByteArray())
      out.closeEntry()
    }
  }

  private val noOpMessageReceiver = MessageReceiver { _: Message -> }
}
