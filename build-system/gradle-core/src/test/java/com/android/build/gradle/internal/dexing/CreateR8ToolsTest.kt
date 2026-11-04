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
import com.android.builder.dexing.KeepRulesConfig
import com.android.builder.dexing.L8OutputMode
import com.android.builder.dexing.MainDexListConfig
import com.android.builder.dexing.ProguardConfig
import com.android.builder.dexing.R8OutputType
import com.android.builder.dexing.R8ToolsImpl
import com.android.builder.dexing.ToolConfig
import com.android.ide.common.blame.Message
import com.android.ide.common.blame.MessageReceiver
import com.android.testutils.TestInputsGenerator
import com.android.testutils.TestUtils
import com.android.testutils.truth.DexSubject.assertThatDex
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import java.io.File
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
  fun withoutIsolatedClassLoader_usesAgpsOwnImplementation() {
    val r8Tools = createR8Tools(null)

    // AGP's own copy, which runs the R8 bundled with AGP.
    assertThat(r8Tools).isInstanceOf(R8ToolsImpl::class.java)
    assertThat(r8Tools.javaClass.classLoader).isSameInstanceAs(R8ToolsImpl::class.java.classLoader)
  }

  @Test
  fun withIsolatedClassLoader_usesImplementationFromThatLoader() {
    val r8Jar = temporaryFolder.newFile("r8.jar")
    R8ClassloaderBuildService.createClassLoader(setOf(r8Jar)).use { isolatedLoader ->
      val r8Tools = createR8Tools(isolatedLoader)

      // Loaded by the isolated loader, so it runs the R8 in that loader...
      assertThat(r8Tools.javaClass.classLoader).isSameInstanceAs(isolatedLoader)
      // ...and it's a different class from AGP's own R8ToolsImpl, even though the name is the same.
      assertThat(r8Tools.javaClass).isNotSameInstanceAs(R8ToolsImpl::class.java)
    }
  }

  @Test
  fun runTraceReferences_throughIsolatedClassLoader() {
    val androidJar = TestUtils.resolvePlatformPath("android.jar", TestUtils.TestType.AGP)
    val sourceJar = temporaryFolder.root.toPath().resolve("source.jar")
    TestInputsGenerator.jarWithEmptyClasses(sourceJar, listOf("test/Source"))

    R8ClassloaderBuildService.createClassLoader(setOf(r8Jar)).use { isolatedLoader ->
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

    R8ClassloaderBuildService.createClassLoader(setOf(r8Jar)).use { isolatedLoader ->
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
  fun generateDesugaredMethods_throughIsolatedClassLoader_matchesBundledR8() {
    val androidJar = TestUtils.resolvePlatformPath("android.jar", TestUtils.TestType.AGP).toFile()
    val desugarLibConfig = TestUtils.getDesugarLibConfigContent()
    val bundled = createR8Tools(null)

    R8ClassloaderBuildService.createClassLoader(setOf(r8Jar)).use { isolatedLoader ->
      val isolated = createR8Tools(isolatedLoader)
      assertThat(isolated.javaClass.classLoader).isSameInstanceAs(isolatedLoader)

      // Without core library desugaring: only the methods D8 backports.
      val backported = isolated.generateDesugaredMethods(null, emptySet(), 21)
      assertThat(backported).isNotEmpty()
      assertThat(backported).containsExactlyElementsIn(bundled.generateDesugaredMethods(null, emptySet(), 21))

      // With core library desugaring, the desugared library APIs are listed too.
      val withDesugarLib = isolated.generateDesugaredMethods(desugarLibConfig, setOf(androidJar), 21)
      assertThat(withDesugarLib).containsAtLeastElementsIn(backported)
      assertThat(withDesugarLib.size).isGreaterThan(backported.size)
      assertThat(withDesugarLib).containsExactlyElementsIn(bundled.generateDesugaredMethods(desugarLibConfig, setOf(androidJar), 21))
    }
  }

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

  private val r8Jar: File by lazy { File(Class.forName("com.android.tools.r8.R8").protectionDomain.codeSource.location.toURI()) }
}
