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

package com.android.build.gradle.internal.dependency

import com.android.SdkConstants.FN_PROGUARD_TXT
import com.android.build.gradle.internal.fixtures.FakeGradleProperty
import com.android.build.gradle.internal.fixtures.FakeGradleProvider
import com.android.build.gradle.internal.fixtures.FakeGradleRegularFile
import com.android.build.gradle.internal.fixtures.FakeTransformOutputs
import com.android.build.gradle.internal.publishing.AndroidArtifacts
import com.android.testutils.ZipContents
import com.android.testutils.generateAarWithContent
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Parameterized test for extracting and filtering consumer ProGuard / R8 rules from AAR and JAR artifacts. */
@RunWith(Parameterized::class)
class ConsumerProguardRulesTransformTest(
  private val container: ContainerType,
  private val ruleType: RuleType,
  private val filterGlobals: Boolean,
) {

  enum class ContainerType {
    AAR,
    JAR,
  }

  enum class RuleType {
    TARGETED_R8,
    LEGACY_PROGUARD,
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}_{1}_filterDisallowed={2}")
    fun parameters(): Collection<Array<Any>> =
      ContainerType.values().flatMap { container ->
        RuleType.values().flatMap { ruleType ->
          listOf(true, false).map { filter ->
            arrayOf(container, ruleType, filter)
          }
        }
      }

    private val RULE_CONTENT_RAW =
      """
      -keep class com.example.MyClass { *; }
      -dontoptimize # comment1
      -repackageclasses # comment2
      """
        .trimIndent() + "\n"

    private val RULE_CONTENT_FILTERED =
      """
      -keep class com.example.MyClass { *; }
      # REMOVED CONSUMER RULE: -dontoptimize # comment1
      # REMOVED CONSUMER RULE: -repackageclasses # comment2
      """
        .trimIndent() + "\n"

    /**
     * For R8 version targeted rules, these relative paths are the same for JARs and AARs, they just have different roots
     * - AAR: <aar>/classes.jar/...
     * - JAR: <jar>/...
     *
     * See https://developer.android.com/topic/performance/app-optimization/library-optimization#support-different
     */
    private val TARGETED_PATHS =
      listOf(
        "META-INF/com.android.tools/r8/r8.ext",
        "META-INF/com.android.tools/r8-from-8.2.0/r8-from-8.2.0.ext",
        "META-INF/com.android.tools/r8-from-8.0.0-upto-8.2.0/r8-from-8.0.0-upto-8.2.0.ext",
        "META-INF/com.android.tools/r8-upto-8.0.0/r8-upto-8.0.0.ext",
      )

    private val LEGACY_JAR_PATHS =
      listOf(
        "META-INF/proguard/rules1.pro",
        "META-INF/proguard/rules2.pro",
      )
  }

  @get:Rule val tmpDir = TemporaryFolder()

  @Test
  fun testRuleExtractionAndFiltering() {
    val artifact = createArtifact(container, ruleType)
    val actualRules = extractRules(artifact, container, filterGlobals)
    val expectedContent = if (filterGlobals) RULE_CONTENT_FILTERED else RULE_CONTENT_RAW
    val expectedRules =
      when (ruleType) {
        RuleType.TARGETED_R8 -> TARGETED_PATHS.associateWith { expectedContent }
        RuleType.LEGACY_PROGUARD ->
          when (container) {
            ContainerType.AAR -> mapOf(FN_PROGUARD_TXT to expectedContent)
            ContainerType.JAR -> LEGACY_JAR_PATHS.associateWith { expectedContent }
          }
      }
    assertEquals(expectedRules, actualRules)
  }

  @Test
  fun testZipSlipPathsRejected() {
    val evilArtifact = createZipSlipArtifact(container)
    assertEquals(emptyMap(), extractRules(evilArtifact, container, filterGlobals = true))
  }

  @Test
  fun testArtifactWithoutRules() {
    val emptyArtifact = createEmptyArtifact(container)
    assertEquals(emptyMap(), extractRules(emptyArtifact, container, filterGlobals = true))
  }

  private fun createArtifact(container: ContainerType, ruleType: RuleType): File {
    return when (container) {
      // see https://developer.android.com/topic/performance/app-optimization/library-optimization#support-different for different
      // packaging in AAR vs JAR
      ContainerType.AAR -> {
        val aarFile = tmpDir.root.resolve("test.aar")
        val (classesJar, proguardTxt) =
          when (ruleType) {
            // targeted rules are within classes.jar ...
            RuleType.TARGETED_R8 -> ZipContents(TARGETED_PATHS.associateWith { RULE_CONTENT_RAW.toByteArray() }).toByteArray() to null
            // ... legacy rules are not, they're just directly in the aar
            RuleType.LEGACY_PROGUARD -> byteArrayOf() to RULE_CONTENT_RAW
          }
        aarFile.writeBytes(generateAarWithContent(packageName = "com.example", mainJar = classesJar, proguardTxt = proguardTxt))
        aarFile
      }
      ContainerType.JAR -> {
        val jarFile = tmpDir.root.resolve("test.jar")
        val paths =
          when (ruleType) {
            RuleType.TARGETED_R8 -> TARGETED_PATHS
            RuleType.LEGACY_PROGUARD -> LEGACY_JAR_PATHS
          }
        ZipContents(paths.associateWith { RULE_CONTENT_RAW.toByteArray() }).writeToFile(jarFile)
        jarFile
      }
    }
  }

  private fun createEmptyArtifact(container: ContainerType): File {
    return when (container) {
      ContainerType.AAR -> {
        val file = tmpDir.root.resolve("empty.aar")
        file.writeBytes(generateAarWithContent(packageName = "com.example"))
        file
      }
      ContainerType.JAR -> {
        val file = tmpDir.root.resolve("empty.jar")
        ZipContents(mapOf("com/example/Unrelated.txt" to "hello".toByteArray())).writeToFile(file)
        file
      }
    }
  }

  private fun createZipSlipArtifact(container: ContainerType): File {
    val evilJar = tmpDir.root.resolve("evil.jar")
    ZipOutputStream(FileOutputStream(evilJar)).use { zos ->
      zos.putNextEntry(ZipEntry("META-INF/com.android.tools/r8/../../evil.ext"))
      zos.write("-dontoptimize\n".toByteArray())
      zos.closeEntry()
      zos.putNextEntry(ZipEntry("META-INF/proguard/..\\..\\evil_win.pro"))
      zos.write("-dontoptimize\n".toByteArray())
      zos.closeEntry()
    }
    return when (container) {
      ContainerType.AAR -> {
        val aarFile = tmpDir.root.resolve("evil.aar")
        aarFile.writeBytes(generateAarWithContent(packageName = "com.example", mainJar = evilJar.readBytes()))
        aarFile
      }
      ContainerType.JAR -> evilJar
    }
  }

  /**
   * Executes the consumer rules extraction transform pipeline on the artifact.
   *
   * @return A map from relative rule file path (e.g., "META-INF/com.android.tools/r8/r8.ext", "META-INF/proguard/rules1.pro", or
   *   "proguard.txt") to its extracted rule content.
   */
  private fun extractRules(artifactFile: File, container: ContainerType, filterGlobals: Boolean): Map<String, String> {
    val transformOutputs = FakeTransformOutputs(tmpDir)
    when (container) {
      ContainerType.AAR -> {
        val extractedAarDir = tmpDir.root.resolve("extracted_${artifactFile.nameWithoutExtension}")
        AarExtractor().extract(artifactFile, extractedAarDir)
        createAarTransform(extractedAarDir, filterGlobals).transform(transformOutputs)
      }
      ContainerType.JAR -> {
        createJarTransform(artifactFile, filterGlobals).transform(transformOutputs)
      }
    }
    return collectExtractedRules(transformOutputs)
  }

  /**
   * Collects all extracted rule files registered in [FakeTransformOutputs].
   *
   * Transforms emit either:
   * - A directory output under `shrink-rules/lib/<relative-rule-path>` (used when emitting targeted R8 rules or JAR legacy ProGuard rules
   *   for downstream consumption by [FilterShrinkerRulesTransform]). The "lib/" prefix is stripped so the map keys match the relative rule
   *   path within the artifact.
   * - A single file output such as `proguard.txt` (used when emitting legacy ProGuard rules from an AAR).
   *
   * @return A map from normalized relative rule path to the extracted rule file content.
   */
  private fun collectExtractedRules(transformOutputs: FakeTransformOutputs): Map<String, String> {
    return transformOutputs.outputFiles
      .flatMap { fileOrDir ->
        if (fileOrDir.isDirectory) {
          fileOrDir
            .walk()
            .filter { it.isFile }
            .map {
              it.relativeTo(fileOrDir).invariantSeparatorsPath.removePrefix("lib/") to it.readText().replace("\r\n", "\n")
            }
            .asIterable()
        } else if (fileOrDir.isFile) {
          listOf(fileOrDir.name to fileOrDir.readText().replace("\r\n", "\n"))
        } else {
          emptyList()
        }
      }
      .toMap()
  }

  private fun createAarTransform(extractedAarDir: File, filterGlobals: Boolean): AarTransform {
    return object : AarTransform() {
      override val inputArtifact: Provider<FileSystemLocation> = FakeGradleProvider(FakeGradleRegularFile(extractedAarDir))

      override fun getParameters(): Parameters =
        object : Parameters {
          override val projectName: Property<String> = FakeGradleProperty("")
          override val targetType: Property<AndroidArtifacts.ArtifactType> =
            FakeGradleProperty(AndroidArtifacts.ArtifactType.UNFILTERED_PROGUARD_RULES)
          override val namespacedSharedLibSupport: Property<Boolean> = FakeGradleProperty(false)
          override val filterOutGlobalRules: Property<Boolean> = FakeGradleProperty(filterGlobals)
        }
    }
  }

  private fun createJarTransform(jarFile: File, filterGlobals: Boolean): ExtractProGuardRulesTransform {
    return object : ExtractProGuardRulesTransform() {
      override val inputArtifact: Provider<FileSystemLocation> = FakeGradleProvider(FakeGradleRegularFile(jarFile))

      override fun getParameters(): Parameters =
        object : Parameters {
          override val projectName: Property<String> = FakeGradleProperty("")
          override val filterOutGlobalRules: Property<Boolean> = FakeGradleProperty(filterGlobals)
        }
    }
  }
}
