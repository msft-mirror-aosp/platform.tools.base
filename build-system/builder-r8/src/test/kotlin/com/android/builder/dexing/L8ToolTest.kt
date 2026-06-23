/*
 * Copyright (C) 2019 The Android Open Source Project
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

import com.android.testutils.TestInputsGenerator
import com.android.testutils.TestUtils
import com.android.testutils.truth.DexSubject
import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Sanity test to make sure we can invoke L8 successfully */
class L8ToolTest {
  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun testDexGeneration() {
    val output = tmp.newFolder().toPath()
    runL8(desugarJar, output, desugarConfig, bootClasspath, 20, KeepRulesConfig(emptyList(), emptyList()), true, L8OutputMode.DexIndexed)
    assertThat(getDexFileCount(output)).isEqualTo(1)
    assertThat(output.resolve("classes1000.dex")).exists()
  }

  @Test
  fun testShrinking() {
    val output = tmp.newFolder().resolve("out")
    val input = tmp.newFolder().toPath()

    val keepRulesFile1 =
      input.toFile().resolve("keep_rules").also { file -> file.bufferedWriter().use { it.write("-keep class j$.util.stream.Stream {*;}") } }
    val keepRulesFile2 =
      input.toFile().resolve("dir/keep_rules").also { file ->
        file.parentFile.mkdirs()
        file.bufferedWriter().use { it.write("-keep class j$.util.Optional {*;}") }
      }
    runL8(
      desugarJar,
      output.toPath(),
      desugarConfig,
      bootClasspath,
      20,
      KeepRulesConfig(listOf(keepRulesFile1.toPath(), keepRulesFile2.toPath()), emptyList()),
      true,
      L8OutputMode.DexIndexed,
    )
    val dexFile = output.resolve("classes1000.dex")
    DexSubject.assertThatDex(dexFile).containsClass("Lj$/util/stream/Stream;")
    DexSubject.assertThatDex(dexFile).containsClass("Lj$/util/Optional;")
    // check unused API classes are removed from the from desugar lib dex.
    DexSubject.assertThatDex(dexFile).doesNotContainClasses("Lj$/time/LocalTime;")
  }

  @Test
  fun testMappingMerge() {
    val output = tmp.newFolder().toPath()
    val r8InputMapping = tmp.newFile("r8_mapping.txt").toPath()
    Files.write(r8InputMapping, listOf("# compiler: R8", "# pg_map_id: r8-uuid-1234", "com.example.App -> a.a:", "    void main() -> b"))

    // Setup Keep Rules to force L8 to generate its own mapping
    // keeping a known class from the desugar jar so L8 has something to obfuscate/map
    val keepRulesFile = tmp.newFile("rules.pro").toPath()
    Files.write(keepRulesFile, listOf("-keep class j$.util.stream.Stream { *; }"))

    val finalOutputMapping = output.resolve("mapping.txt")

    runL8(
      inputClasses = desugarJar,
      output = output,
      libConfiguration = desugarConfig,
      libraries = bootClasspath,
      minSdkVersion = 20,
      keepRules = KeepRulesConfig(listOf(keepRulesFile), emptyList()),
      isDebuggable = false, // FALSE to enable obfuscation
      outputMode = L8OutputMode.DexIndexed,
      inputMappingFile = r8InputMapping,
      outputMappingFile = finalOutputMapping,
    )

    assertThat(finalOutputMapping).exists()
    val content = Files.readAllLines(finalOutputMapping)

    // Verify R8 Header is at the top
    assertThat(content[0]).isEqualTo("# compiler: R8")
    assertThat(content).contains("# pg_map_id: r8-uuid-1234")
    assertThat(content).contains("com.example.App -> a.a:")

    // Verify L8 content is merged
    // We look for the class we kept. It might be obfuscated or kept depending on rules,
    // but the mapping file should mention it.
    // Since we didn't use -dontobfuscate, L8 should produce a map.
    // We can check for the separator we added in L8Tool.
    assertThat(content).contains(L8_MAPPING_HEADER)

    // Verify L8 Headers are STRIPPED
    // The file should NOT contain a second "pg_map_id" line from L8.
    // (R8's id is present, L8's should be gone).
    val mapIdCount = content.count { it.contains("pg_map_id") }
    assertThat(mapIdCount).named("Should only have one Map ID (from R8)").isEqualTo(1)
  }

  @Test
  fun testPartitionMappingMerge() {
    val output = tmp.newFolder().toPath()

    // Generate dummy R8 input classes
    val r8InputClasses = tmp.newFolder().toPath().resolve("classes.jar")
    TestInputsGenerator.jarWithEmptyClasses(r8InputClasses, listOf("com/example/App"))

    val r8OutputFolder = tmp.newFolder().toPath()
    val r8MappingFile = r8OutputFolder.resolve("mapping.txt")
    val r8PartitionMappingFile = r8OutputFolder.resolve("mapping.prt")

    val proguardOutputFiles =
      ProguardOutputFiles(
        proguardMapOutput = r8MappingFile,
        proguardPartitionMapOutput = r8PartitionMappingFile,
        proguardSeedsOutput = r8OutputFolder.resolve("seeds.txt"),
        proguardUsageOutput = r8OutputFolder.resolve("usage.txt"),
        proguardConfigurationOutput = r8OutputFolder.resolve("configuration.txt"),
        missingKeepRules = r8OutputFolder.resolve("missing_rules.txt"),
      )
    val proguardConfig =
      ProguardConfig(
        keepRuleWithOrigins = emptyList(),
        proguardMapInput = null,
        proguardConfigurations = listOf("-keep class com.example.App { *; }"),
        proguardOutputFiles = proguardOutputFiles,
        proguardOutputReports = ProguardOutputReports(r8OutputFolder.resolve("report.pb"), r8OutputFolder.resolve("report.html")),
      )

    val emptyJavaRes = tmp.newFolder().toPath().resolve("res.jar")
    TestInputsGenerator.jarWithEmptyClasses(emptyJavaRes, emptyList())

    // Run R8 to produce mapping.txt and mapping.prt
    runR8(
      inputClasses = listOf(r8InputClasses),
      output = tmp.newFolder().toPath(),
      inputJavaResJar = emptyJavaRes,
      javaResourcesJar = null,
      libraries = bootClasspath,
      classpath = emptyList(),
      toolConfig =
        ToolConfig(
          minSdkVersion = 20,
          debuggable = false,
          disableTreeShaking = false,
          disableMinification = false,
          disableDesugaring = true,
          fullMode = true,
          strictFullModeForKeepRules = true,
          isolatedSplits = null,
          r8OutputType = R8OutputType.DEX,
          mainDexListDisallowed = true,
        ),
      proguardConfig = proguardConfig,
      mainDexListConfig = MainDexListConfig(emptyList(), emptyList()),
      resourceShrinkingConfig = null,
      messageReceiver = NoOpMessageReceiver(),
      featureClassJars = emptyList(),
      featureJavaResourceJars = emptyList(),
      featureDexDir = null,
      featureJavaResourceOutputDir = null,
    )

    // Setup Keep Rules for L8 desugaring
    val keepRulesFile = tmp.newFile("rules.pro").toPath()
    Files.write(keepRulesFile, listOf("-keep class j\$.util.stream.Stream { *; }"))

    val finalOutputPartitionMapping = output.resolve("mapping.prt")

    // Run L8 and merge the partitioned mapping from R8
    runL8(
      inputClasses = desugarJar,
      output = output,
      libConfiguration = desugarConfig,
      libraries = bootClasspath,
      minSdkVersion = 20,
      keepRules = KeepRulesConfig(listOf(keepRulesFile), emptyList()),
      isDebuggable = false,
      outputMode = L8OutputMode.DexIndexed,
      inputMappingFile = r8MappingFile,
      inputPartitionMappingFile = r8PartitionMappingFile,
      outputPartitionMappingFile = finalOutputPartitionMapping,
    )

    assertThat(finalOutputPartitionMapping).exists()
    ZipFile(finalOutputPartitionMapping.toFile()).use { zip ->
      val names = zip.entries().toList().map { it.name }
      assertThat(names).contains("METADATA")
      // Check that it contains multiple partitions (R8 + L8)
      assertThat(names.size).isGreaterThan(1)
    }
  }

  private fun getDexFileCount(dir: Path): Long = Files.list(dir).filter { it.toString().endsWith(".dex") }.count()

  companion object {
    val bootClasspath = listOf(TestUtils.resolvePlatformPath("android.jar", TestUtils.TestType.AGP))
    val desugarJar = listOf(TestUtils.getDesugarLibJar())
    val desugarConfig = TestUtils.getDesugarLibConfigContent()
  }
}
