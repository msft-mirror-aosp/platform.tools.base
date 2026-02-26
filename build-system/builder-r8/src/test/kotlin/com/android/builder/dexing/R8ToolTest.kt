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

package com.android.builder.dexing

import com.android.Version
import com.android.builder.dexing.testdata.ClassWithAssertions
import com.android.ide.common.blame.MessageReceiver
import com.android.testutils.TestClassesGenerator
import com.android.testutils.TestInputsGenerator
import com.android.testutils.TestUtils
import com.android.testutils.apk.Dex
import com.android.testutils.truth.DexSubject.assertThat
import com.android.testutils.truth.DexSubject.assertThatDex
import com.android.testutils.truth.PathSubject.assertThat
import com.android.testutils.truth.ZipFileSubject.assertThat
import com.android.tools.r8.CompilationFailedException
import com.android.utils.FileUtils
import com.android.utils.Pair
import com.android.zipflinger.ZipArchive
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.readLines
import kotlin.test.fail
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Sanity test that make sure we can invoke R8 with some basic configurations. */
class R8ToolTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun testClassesFromDir() {
    val classes = tmp.newFolder().toPath()
    TestInputsGenerator.dirWithEmptyClasses(classes, listOf("test/A", "test/B"))

    val output = tmp.newFolder().toPath()

    runR8Tool(inputClasses = listOf(classes), output = output, toolConfig = defaultToolConfig().copy(disableTreeShaking = true))

    assertThat(getDexFileCount(output)).isEqualTo(1)
  }

  @Test
  fun testClassesFromJar() {
    val classes = tmp.newFolder().toPath().resolve("classes.jar")
    TestInputsGenerator.jarWithEmptyClasses(classes, listOf("test/A", "test/B"))

    val output = tmp.newFolder().toPath()

    runR8Tool(inputClasses = listOf(classes), output = output, toolConfig = defaultToolConfig().copy(disableTreeShaking = true))

    assertThat(getDexFileCount(output)).isEqualTo(1)
  }

  @Test
  fun testClassesAndResources() {
    val classes = tmp.newFolder().toPath().resolve("classes.jar")
    ZipOutputStream(classes.toFile().outputStream()).use { zip ->
      zip.putNextEntry(ZipEntry("test/A.class"))
      zip.write(TestClassesGenerator.emptyClass("test", "A"))
      zip.closeEntry()
      zip.putNextEntry(ZipEntry("test/B.class"))
      zip.write(TestClassesGenerator.emptyClass("test", "B"))
      zip.closeEntry()
      zip.putNextEntry(ZipEntry("res.txt"))
      zip.closeEntry()
    }

    val output = tmp.newFolder().toPath()
    val javaRes = tmp.root.resolve("res.jar").toPath()

    runR8Tool(
      inputClasses = listOf(classes),
      output = output,
      toolConfig = defaultToolConfig().copy(disableTreeShaking = true),
      inputJavaResJar = classes,
      javaResourcesJar = javaRes,
    )

    assertThat(getDexFileCount(output)).isEqualTo(1)
    assertThat(javaRes) { it.contains("res.txt") }

    // check Java resources are compressed
    ZipFile(javaRes.toFile()).use { zip ->
      for (entry in zip.entries()) {
        assertThat(entry.method).named("entry is compressed").isEqualTo(ZipEntry.DEFLATED)
      }
    }
  }

  @Test
  fun testMainDexListAllowed() {
    val output = tmp.newFolder().toPath()
    runR8WithMainDexList(output = output, mainDexListDisallowed = false)
    assertThat(getDexFileCount(output)).isEqualTo(2)
  }

  @Test
  fun testMainDexListDisallowed() {
    val exception = assertThrows(CompilationFailedException::class.java) { runR8WithMainDexList(mainDexListDisallowed = true) }
    assertThat(exception.cause?.message)
      .isEqualTo(
        "Unsupported usage of main-dex list. " +
          "The usage of main-dex-list content for the compilation of non-DEX inputs is deprecated. " +
          "See issue https://issuetracker.google.com/181858113 for context."
      )
  }

  @Test
  fun testMainDexListRules() {
    val toolConfig = defaultToolConfig().copy(minSdkVersion = 19, debuggable = true, disableTreeShaking = true)

    val classes = tmp.newFolder().toPath().resolve("classes.jar")
    TestInputsGenerator.dirWithEmptyClasses(classes, listOf("test/A", "test/B"))

    val mainDexRules = tmp.newFile().toPath()
    Files.write(mainDexRules, listOf("-keep class test.A"))
    val mainDexConfig = MainDexListConfig(listOf(mainDexRules), listOf())

    val output = tmp.newFolder().toPath()

    runR8Tool(inputClasses = listOf(classes), output = output, toolConfig = toolConfig, mainDexListConfig = mainDexConfig)

    assertThat(getDexFileCount(output)).isEqualTo(2)
  }

  @Test
  fun testKeepRules() {
    val classes = tmp.newFolder().toPath().resolve("classes.jar")
    TestInputsGenerator.dirWithEmptyClasses(classes, listOf("test/A", "test/B"))

    val proguardRules = tmp.newFile().toPath()
    Files.write(proguardRules, listOf("-keep class test.A"))
    val proguardConfig = ProguardConfig(listOf(KeepRuleFile.WithoutOrigin(proguardRules)), null, listOf(), emptyProguardOutputFiles)

    val output = tmp.newFolder().toPath()

    runR8Tool(inputClasses = listOf(classes), output = output, proguardConfig = proguardConfig)

    assertThat(getDexFileCount(output)).isEqualTo(1)
    assertThatDex(output.resolve("classes.dex").toFile()).containsClass("Ltest/A;")
    assertThatDex(output.resolve("classes.dex").toFile()).doesNotContainClasses("Ltest/B;")
  }

  @Test
  fun testConfigurationOutput() {
    val classes = tmp.newFolder().toPath().resolve("classes.jar")
    TestInputsGenerator.dirWithEmptyClasses(classes, listOf("test/A", "test/B", "external/A", "external/B", "agp/A", "agp/B"))

    val keepRulesDir = tmp.newFolder().toPath()
    val userKeepRules = keepRulesDir.resolve("user.keep")
    Files.write(userKeepRules, listOf("-keep class test.A"))
    val agpInternalKeepRules = keepRulesDir.resolve("agp.keep")
    Files.write(agpInternalKeepRules, listOf("-keep class agp.A"))
    val externalKeepRules = keepRulesDir.resolve("external.keep")
    Files.write(externalKeepRules, listOf("-keep class external.A"))
    val keepRuleWithOrigins =
      listOf(
        KeepRuleFile.WithoutOrigin(userKeepRules),
        KeepRuleFile.AgpInternalOrigin(agpInternalKeepRules),
        KeepRuleFile.MavenOrigin("com.example:foo:0.1", "com.example", "foo", "0.1", externalKeepRules.toString()),
      )

    val mappingFileDir = tmp.newFolder().toPath()
    val proguardConfigurationOutput = mappingFileDir.resolve("configuration.txt")
    val mappingOutputFiles =
      ProguardOutputFiles(
        mappingFileDir.resolve("mapping.txt"),
        mappingFileDir.resolve("mapping.prt"),
        mappingFileDir.resolve("seeds.txt"),
        mappingFileDir.resolve("usage.txt"),
        proguardConfigurationOutput,
        mappingFileDir.resolve("missing_rules.txt"),
      )
    val proguardConfig = ProguardConfig(keepRuleWithOrigins, null, listOf(), mappingOutputFiles)

    val output = tmp.newFolder().toPath()

    runR8Tool(inputClasses = listOf(classes), output = output, proguardConfig = proguardConfig)

    assertThat(getDexFileCount(output)).isEqualTo(1)
    assertThatDex(output.resolve("classes.dex").toFile()).containsClass("Ltest/A;")
    assertThatDex(output.resolve("classes.dex").toFile()).doesNotContainClasses("Ltest/B;")

    val outputText = proguardConfigurationOutput.readLines().filter { it.isNotEmpty() }.joinToString("\n")
    assertThat(outputText)
      .contains(
        """
          # The proguard configuration file for the following section is $userKeepRules
          -keep class test.A
          # End of content from $userKeepRules
          # The proguard configuration file for the following section is Android Gradle plugin ${Version.ANDROID_GRADLE_PLUGIN_VERSION} (extracted file: $agpInternalKeepRules)
          -keep class agp.A
          # End of content from Android Gradle plugin ${Version.ANDROID_GRADLE_PLUGIN_VERSION} (extracted file: $agpInternalKeepRules)
          # The proguard configuration file for the following section is com.example:foo:0.1 (extracted file: $externalKeepRules)
          -keep class external.A
          # End of content from com.example:foo:0.1 (extracted file: $externalKeepRules)
          """
          .trimIndent()
      )
  }

  @Test
  fun testProguardMapping() {
    val testClasses = tmp.newFolder().toPath().resolve("testClasses.jar")
    TestInputsGenerator.pathWithClasses(testClasses, listOf(ExampleClasses.TestClass::class.java))

    val programClasses = tmp.newFolder().toPath().resolve("programClasses.jar")
    TestInputsGenerator.pathWithClasses(programClasses, listOf(ExampleClasses::class.java, ExampleClasses.ProgramClass::class.java))

    val programClasspath = mutableListOf(programClasses)

    val proguardInputMapping = tmp.newFile("space in name.txt").toPath()
    Files.write(
      proguardInputMapping,
      listOf("com.android.builder.dexing.ExampleClasses\$ProgramClass -> foo.Bar:", "  1:1:void method():42:42 -> baz"),
    )
    val proguardConfig =
      ProguardConfig(
        listOf(),
        proguardInputMapping,
        listOf(),
        ProguardOutputFiles(
          tmp.root.toPath().resolve("mapping.txt"),
          tmp.root.toPath().resolve("mapping.prt"),
          tmp.root.toPath().resolve("seeds.txt"),
          tmp.root.toPath().resolve("usage.txt"),
          tmp.root.toPath().resolve("configuration.txt"),
          tmp.root.toPath().resolve("missing_rules.txt"),
        ),
      )

    val output = tmp.newFolder().toPath()

    runR8Tool(
      inputClasses = listOf(testClasses),
      output = output,
      classpath = programClasspath,
      proguardConfig = proguardConfig,
      toolConfig = defaultToolConfig().copy(disableTreeShaking = true, disableMinification = true),
    )

    assertThat(getDexFileCount(output)).isEqualTo(1)
    assertThatDex(output.resolve("classes.dex").toFile())
      .containsClass("Lcom/android/builder/dexing/ExampleClasses\$TestClass;")
      .that()
      .hasMethodThatInvokes("test", "Lfoo/Bar;->baz()V")
    assertThat(Files.exists(proguardConfig.proguardOutputFiles.proguardMapOutput)).isTrue()
  }

  @Test
  fun testUsageAndSeeds() {
    val classes = tmp.newFolder().toPath().resolve("classes.jar")
    TestInputsGenerator.dirWithEmptyClasses(classes, listOf("test/A", "test/B"))
    val output = tmp.newFolder().toPath()

    val proguardSeedsOutput = tmp.root.toPath().resolve("seeds.txt")
    val proguardUsageOutput = tmp.root.toPath().resolve("usage.txt")
    val proguardConfigurationOutput = tmp.root.toPath().resolve("configuration.txt")
    val proguardConfig =
      ProguardConfig(
        listOf(),
        null,
        listOf(),
        ProguardOutputFiles(
          tmp.root.toPath().resolve("mapping.txt"),
          tmp.root.toPath().resolve("mapping.prt"),
          proguardSeedsOutput,
          proguardUsageOutput,
          proguardConfigurationOutput,
          tmp.root.toPath().resolve("missing_rules.txt"),
        ),
      )

    runR8Tool(inputClasses = listOf(classes), output = output, proguardConfig = proguardConfig)

    assertThat(Files.exists(proguardSeedsOutput)).isTrue()
    assertThat(Files.exists(proguardUsageOutput)).isTrue()
    assertThat(Files.exists(proguardConfigurationOutput)).isTrue()
  }

  @Test
  fun testErrorReporting() {
    val proguardRules = tmp.newFile().toPath()
    Files.write(proguardRules, listOf("wrongRuleExample"))
    val proguardConfig = ProguardConfig(listOf(KeepRuleFile.WithoutOrigin(proguardRules)), null, listOf(), emptyProguardOutputFiles)

    val output = tmp.newFolder().toPath()
    val messages = mutableListOf<String>()
    val toolNameTags = mutableListOf<String>()

    try {
      runR8Tool(
        inputClasses = listOf(),
        output = output,
        proguardConfig = proguardConfig,
        messageReceiver = { message ->
          messages.add(message.text)
          toolNameTags.add(message.toolName!!)
        },
      )
      fail("Parsing proguard configuration should fail.")
    } catch (e: Throwable) {
      assertThat(messages.single()).contains("Expected char '-' at")
      assertThat(messages.single()).contains("1:1")
      assertThat(messages.single()).contains("wrongRuleExample")
      assertThat(toolNameTags).containsExactly("R8")
    }
  }

  @Test
  fun testMultiReleaseFromDir() {
    val classes = tmp.newFolder().toPath()
    TestInputsGenerator.dirWithEmptyClasses(classes, listOf("test/A", "test/B"))
    classes.resolve("META-INF/versions/9/test/C.class").also {
      it.parent.toFile().mkdirs()
      it.toFile().writeText("malformed class file")
    }

    val output = tmp.newFolder().toPath()

    runR8Tool(
      inputClasses = listOf(classes),
      output = output,
      toolConfig = defaultToolConfig().copy(disableTreeShaking = true, disableMinification = true),
    )

    assertThatDex(output.resolve("classes.dex").toFile()).containsExactlyClassesIn(listOf("Ltest/A;", "Ltest/B;"))
  }

  @Test
  fun testFeatureJars() {
    val classes = tmp.newFolder().toPath().resolve("classes.jar")
    TestInputsGenerator.jarWithEmptyClasses(classes, listOf("test/A", "test/B"))

    val featureClassesJar = tmp.newFolder().toPath().resolve("feature1.jar")
    TestInputsGenerator.jarWithEmptyClasses(featureClassesJar, listOf("test/C", "test/D"))

    val emptyFeatureClassesJar = tmp.newFolder().toPath().resolve("feature2.jar")
    ZipArchive(emptyFeatureClassesJar).use {}
    assertThat(emptyFeatureClassesJar).exists()

    val javaResJar = tmp.newFolder().toPath().resolve("base.jar")
    TestInputsGenerator.writeJarWithTextEntries(javaResJar, Pair.of("foo.txt", "foo"))

    val featureJavaResJar = tmp.newFolder().toPath().resolve("feature1.jar")
    TestInputsGenerator.writeJarWithTextEntries(featureJavaResJar, Pair.of("bar.txt", "bar"))

    val emptyFeatureJavaResJar = tmp.newFolder().toPath().resolve("feature2.jar")
    ZipArchive(emptyFeatureJavaResJar).use {}
    assertThat(emptyFeatureJavaResJar).exists()

    val output = tmp.newFolder().toPath()
    val javaRes = tmp.root.resolve("res.jar").toPath()
    val featureDexDir = tmp.newFolder().toPath()
    val featureJavaResourceOutputDir = tmp.newFolder().toPath()

    runR8Tool(
      inputClasses = listOf(classes),
      output = output,
      inputJavaResJar = javaResJar,
      javaResourcesJar = javaRes,
      toolConfig = defaultToolConfig().copy(disableTreeShaking = true),
      featureClassJars = listOf(featureClassesJar, emptyFeatureClassesJar),
      featureJavaResourceJars = listOf(featureJavaResJar, emptyFeatureJavaResJar),
      featureDexDir = featureDexDir,
      featureJavaResourceOutputDir = featureJavaResourceOutputDir,
    )

    assertThat(getDexFileCount(output)).isEqualTo(1)
    val feature1DexOutput = featureDexDir.resolve("feature1")
    assertThat(feature1DexOutput).exists()
    assertThat(getDexFileCount(feature1DexOutput)).isEqualTo(1)
    val feature2DexOutput = featureDexDir.resolve("feature2")
    assertThat(feature2DexOutput).exists()
    assertThat(getDexFileCount(feature2DexOutput)).isEqualTo(0)
    assertThat(javaRes).exists()
    assertThat(ZipArchive.listEntries(javaRes).keys).containsExactly("foo.txt")
    val feature1JavaResOutput = featureJavaResourceOutputDir.resolve("feature1.jar")
    assertThat(feature1JavaResOutput).exists()
    assertThat(ZipArchive.listEntries(feature1JavaResOutput).keys).containsExactly("bar.txt")
    val feature2JavaResOutput = featureJavaResourceOutputDir.resolve("feature2.jar")
    assertThat(feature2JavaResOutput).exists()
    assertThat(ZipArchive.listEntries(feature2JavaResOutput)).isEmpty()
  }

  @Test
  fun testAssertionsGeneration() {
    val testClass = ClassWithAssertions::class.java
    val proguardConfig =
      ProguardConfig(
        listOf(),
        null,
        listOf("-keep class ${testClass.name} { public void foo(); }", "-dontwarn ${testClass.name}"),
        emptyProguardOutputFiles,
      )
    val debuggableToolConfig = defaultToolConfig().copy(debuggable = true)

    val classes = tmp.newFolder().toPath().resolve("classes.jar")
    TestInputsGenerator.pathWithClasses(classes, listOf(testClass))

    val output = tmp.newFolder().toPath()

    runR8Tool(inputClasses = listOf(classes), output = output, toolConfig = debuggableToolConfig, proguardConfig = proguardConfig)

    val className = testClass.name.replace('.', '/')
    val dex = Dex(output.toFile().walk().filter { it.extension == "dex" }.single())

    assertThat(dex).containsClass("L$className;").that().hasMethodThatInvokes("foo", "Ljava/lang/AssertionError;-><init>()V")

    val releaseToolConfig = debuggableToolConfig.copy(debuggable = false)
    FileUtils.cleanOutputDir(output.toFile())

    runR8Tool(inputClasses = listOf(classes), output = output, toolConfig = releaseToolConfig, proguardConfig = proguardConfig)

    assertThat(dex)
      .containsClass("L$className;")
      .that()
      .hasMethodThatDoesNotInvoke("foo", "Ljava/lang/AssertionError;-><init>(Ljava/lang/Object;)V")
  }

  @Test
  fun testMissingRulesGenerated() {
    val missingRules = tmp.newFile()
    val proguardConfig =
      ProguardConfig(
        listOf(),
        null,
        listOf(),
        ProguardOutputFiles(
          tmp.newFile().toPath(),
          tmp.newFile().toPath(),
          tmp.newFile().toPath(),
          tmp.newFile().toPath(),
          tmp.newFile().toPath(),
          missingRules.toPath(),
        ),
      )

    val classes =
      tmp.newFolder().toPath().resolve("classes.jar").also {
        val classToWrite = TestClassesGenerator.classWithEmptyMethods("A", "foo:()Ltest/B;", "bar:()Ltest/C;")
        ZipOutputStream(it.toFile().outputStream()).use { zip ->
          zip.putNextEntry(ZipEntry("test/A.class"))
          zip.write(classToWrite)
          zip.closeEntry()
        }
      }

    val output = tmp.newFolder().toPath()

    try {
      runR8Tool(inputClasses = listOf(classes), output = output, proguardConfig = proguardConfig)
    } catch (ignored: CompilationFailedException) {
      assertThat(missingRules).containsAllOf("-dontwarn test.B", "-dontwarn test.C")
    }
  }

  @Test
  fun testLegacyAndStrictFullModeForKeepRules() { // See b/391864651
    val classes = tmp.newFolder().toPath()
    TestInputsGenerator.dirWithEmptyClasses(classes, listOf("test/A", "test/B"))

    val proguardRulesFile = tmp.newFile("proguard-rules.pro")
    proguardRulesFile.writeText("-keep class test.A")
    val proguardConfig =
      ProguardConfig(listOf(KeepRuleFile.WithoutOrigin(proguardRulesFile.toPath())), null, listOf(), emptyProguardOutputFiles)

    val output = tmp.newFolder().toPath()

    // With `strictFullModeForKeepRules = false`, `test.A.<init>` should be kept
    runR8Tool(
      inputClasses = listOf(classes),
      output = output,
      toolConfig = defaultToolConfig().copy(strictFullModeForKeepRules = false),
      proguardConfig = proguardConfig,
    )
    val outputDexFile = output.resolve("classes.dex").toFile()
    assertThatDex(outputDexFile).containsClass("Ltest/A;").that().hasMethod("<init>")

    // With `strictFullModeForKeepRules = true`, `test.A.<init>` should be removed
    runR8Tool(
      inputClasses = listOf(classes),
      output = output,
      toolConfig = defaultToolConfig().copy(strictFullModeForKeepRules = true),
      proguardConfig = proguardConfig,
    )
    assertThatDex(outputDexFile).containsClass("Ltest/A;").that().doesNotHaveMethod("<init>")
  }

  private fun runR8WithMainDexList(output: Path = tmp.newFolder().toPath(), mainDexListDisallowed: Boolean) {
    val toolConfig =
      defaultToolConfig()
        .copy(minSdkVersion = 19, debuggable = true, disableTreeShaking = true, mainDexListDisallowed = mainDexListDisallowed)

    val classes = tmp.newFolder().toPath().resolve("classes.jar")
    TestInputsGenerator.dirWithEmptyClasses(classes, listOf("test/A", "test/B"))

    val mainDexList = tmp.newFile().toPath()
    Files.write(mainDexList, listOf("test/A.class"))
    val mainDexConfig = MainDexListConfig(mainDexRulesFiles = listOf(), mainDexListFiles = listOf(mainDexList), mainDexRules = listOf())

    runR8Tool(inputClasses = listOf(classes), output = output, toolConfig = toolConfig, mainDexListConfig = mainDexConfig)
  }

  private fun runR8Tool(
    inputClasses: Collection<Path>,
    output: Path,
    inputJavaResJar: Path = emptyJavaResources,
    javaResourcesJar: Path = tmp.root.resolve("res.jar").toPath(),
    libraries: Collection<Path> = bootClasspath,
    classpath: Collection<Path> = emptyList(),
    toolConfig: ToolConfig = defaultToolConfig(),
    proguardConfig: ProguardConfig = emptyProguardConfig(),
    mainDexListConfig: MainDexListConfig = emptyMainDexListConfig(),
    resourceShrinkingConfig: ResourceShrinkingConfig? = null,
    messageReceiver: MessageReceiver = NoOpMessageReceiver(),
    featureClassJars: Collection<Path> = emptyList(),
    featureJavaResourceJars: Collection<Path> = emptyList(),
    featureDexDir: Path? = null,
    featureJavaResourceOutputDir: Path? = null,
  ) {
    runR8(
      inputClasses = inputClasses,
      output = output,
      inputJavaResJar = inputJavaResJar,
      javaResourcesJar = javaResourcesJar,
      libraries = libraries,
      classpath = classpath,
      toolConfig = toolConfig,
      proguardConfig = proguardConfig,
      mainDexListConfig = mainDexListConfig,
      resourceShrinkingConfig = resourceShrinkingConfig,
      messageReceiver = messageReceiver,
      featureClassJars = featureClassJars,
      featureJavaResourceJars = featureJavaResourceJars,
      featureDexDir = featureDexDir,
      featureJavaResourceOutputDir = featureJavaResourceOutputDir,
    )
  }

  private val emptyJavaResources by lazy {
    tmp.root.toPath().resolve("java_resources.jar").also { TestInputsGenerator.jarWithEmptyClasses(it, listOf()) }
  }

  private val bootClasspath by lazy { listOf(TestUtils.resolvePlatformPath("android.jar", TestUtils.TestType.AGP)) }

  private fun defaultToolConfig() =
    ToolConfig(
      minSdkVersion = 21,
      debuggable = false,
      disableTreeShaking = false,
      disableMinification = false,
      disableDesugaring = false,
      fullMode = true,
      strictFullModeForKeepRules = true,
      isolatedSplits = null,
      r8OutputType = R8OutputType.DEX,
      mainDexListDisallowed = true, // Default behaviour as in BooleanOption.R8_MAIN_DEX_LIST_DISALLOWED
    )

  private val emptyProguardOutputFiles by lazy {
    val fakeOutput = tmp.newFolder().resolve("fake_output.txt").toPath()
    ProguardOutputFiles(fakeOutput, fakeOutput, fakeOutput, fakeOutput, fakeOutput, fakeOutput)
  }

  private fun emptyProguardConfig() = ProguardConfig(listOf(), null, listOf(), emptyProguardOutputFiles)

  private fun emptyMainDexListConfig() = MainDexListConfig(listOf(), listOf())

  private fun getDexFileCount(dir: Path): Long = Files.list(dir).filter { it.toString().endsWith(".dex") }.count()
}
