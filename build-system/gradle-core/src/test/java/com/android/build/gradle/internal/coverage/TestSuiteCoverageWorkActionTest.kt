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

package com.android.build.gradle.internal.coverage

import com.android.testutils.truth.PathSubject.assertThat
import com.android.tools.coverage.proto.CoverageMetadataProto.BlockMetadata
import com.android.tools.coverage.proto.CoverageMetadataProto.ClassMetadata
import com.android.tools.coverage.proto.CoverageMetadataProto.CoverageHits
import com.android.tools.coverage.proto.CoverageMetadataProto.CoverageMetadata
import com.android.tools.coverage.proto.CoverageMetadataProto.LineMetadata
import com.android.tools.coverage.proto.CoverageMetadataProto.MethodMetadata
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import java.io.File
import java.util.BitSet
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TestSuiteCoverageWorkActionTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private lateinit var project: Project

  @Before
  fun setUp() {
    project = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder("project")).build()
  }

  @Test
  fun reportIsGeneratedFromOnDeviceCoverage() {
    val coverageDir = temporaryFolder.newFolder("coverage")
    writeOnDeviceCoverage(File(coverageDir, "coverage_data"))
    val reportDir = temporaryFolder.newFolder("report")

    coverageWorker(coverageDir.walk().filter { it.isFile }.toList(), reportDir).execute()

    assertThat(File(reportDir, "report.xml")).exists()
    assertThat(File(reportDir, "report.xml").readText()).contains("<class name=\"com/example/app/Foo\"")
    assertThat(File(reportDir, "index.html")).exists()
  }

  @Test
  fun reportOfASuiteWithHostAndDeviceTestsIncludesTheOnDeviceCoverage() {
    val hostCoverageFile = File(temporaryFolder.newFolder("host"), "test.exec").apply { writeText("") }
    val coverageDir = temporaryFolder.newFolder("coverage")
    writeOnDeviceCoverage(File(coverageDir, "coverage_data"))
    val reportDir = temporaryFolder.newFolder("report")

    coverageWorker(coverageDir.walk().filter { it.isFile }.toList() + hostCoverageFile, reportDir).execute()

    assertThat(File(reportDir, "report.xml").readText()).contains("<class name=\"com/example/app/Foo\"")
    assertThat(File(reportDir, "index.html")).exists()
  }

  @Test
  fun reportIncludesClassesThatTheTestedVariantCompilesWithoutASource() {
    val coverageDir = temporaryFolder.newFolder("coverage")
    writeOnDeviceCoverage(File(coverageDir, "coverage_data"), COMPONENT)
    val reportDir = temporaryFolder.newFolder("report")

    coverageWorker(coverageDir.walk().filter { it.isFile }.toList(), reportDir, classFolders = listOf(componentClassFolder())).execute()

    assertThat(File(reportDir, "report.xml").readText()).contains("<class name=\"$COMPONENT\"")
  }

  @Test
  fun reportOfSeveralOnDeviceRunsIncludesClassesThatTheTestedVariantCompilesWithoutASource() {
    val coverageDir = temporaryFolder.newFolder("coverage")
    writeOnDeviceCoverage(File(coverageDir, "device-1/coverage_data"), COMPONENT)
    writeOnDeviceCoverage(File(coverageDir, "device-2/coverage_data"), COMPONENT)
    val reportDir = temporaryFolder.newFolder("report")

    coverageWorker(coverageDir.walk().filter { it.isFile }.toList(), reportDir, classFolders = listOf(componentClassFolder())).execute()

    assertThat(File(reportDir, "report.xml").readText()).contains("<class name=\"$COMPONENT\"")
  }

  /** Returns a class folder with the class file of [COMPONENT], which Hilt generates the source of outside the source folders. */
  private fun componentClassFolder(): File {
    val classFolder = temporaryFolder.newFolder("classes")
    File(classFolder, "$COMPONENT.class").apply { parentFile.mkdirs() }.writeText("")
    return classFolder
  }

  /** Writes the files of one on-device coverage run, in which `Foo.bar()` was hit and the `builder()` methods of [otherClasses] weren't. */
  private fun writeOnDeviceCoverage(runDir: File, vararg otherClasses: String) {
    runDir.mkdirs()
    val metadata =
      CoverageMetadata.newBuilder()
        .setVersion(1)
        .addClasses(
          ClassMetadata.newBuilder()
            .setClassName("Lcom/example/app/Foo;")
            .setSourceFile("Foo.kt")
            .addMethods(
              MethodMetadata.newBuilder()
                .setName("bar")
                .setSignature("()V")
                .addBlocks(
                  BlockMetadata.newBuilder()
                    .setBlockId(0)
                    .setBranchCount(1)
                    .addLines(LineMetadata.newBuilder().setLineNumber(3).setInstructionCount(2))
                )
            )
        )
        .addAllClasses(
          otherClasses.mapIndexed { index, name ->
            ClassMetadata.newBuilder()
              .setClassName("L$name;")
              .setSourceFile(name.substringAfterLast('/') + ".java")
              .addMethods(
                MethodMetadata.newBuilder()
                  .setName("builder")
                  .setSignature("()V")
                  .addBlocks(
                    BlockMetadata.newBuilder()
                      .setBlockId(index + 1)
                      .setBranchCount(1)
                      .addLines(LineMetadata.newBuilder().setLineNumber(5).setInstructionCount(2))
                  )
              )
              .build()
          }
        )
        .build()
    File(runDir, ON_DEVICE_COVERAGE_METADATA_FILE).outputStream().use { metadata.writeTo(it) }
    val hits = CoverageHits.newBuilder().setVersion(1).setHitMask(ByteString.copyFrom(BitSet().apply { set(0) }.toByteArray())).build()
    File(runDir, ON_DEVICE_COVERAGE_HITS_FILE).outputStream().use { hits.writeTo(it) }
  }

  private fun coverageWorker(
    coverageFiles: List<File>,
    reportDir: File,
    classFolders: List<File> = emptyList(),
  ): TestSuiteCoverageWorkAction {
    val sourceFolder = File(temporaryFolder.root, "src/main/kotlin")
    File(sourceFolder, "com/example/app/Foo.kt")
      .apply { parentFile.mkdirs() }
      .writeText("package com.example.app\n\nclass Foo {\n  fun bar() {}\n}\n")
    return object : TestSuiteCoverageWorkAction() {
      override fun getParameters(): TestSuiteCoverageWorkParameters {
        return object : TestSuiteCoverageWorkParameters {
          override val coverageFiles = project.files(coverageFiles)
          override val reportDir = project.objects.directoryProperty().apply { set(reportDir) }
          override val classFolders = project.files(classFolders)
          override val sourceFolders = project.files(sourceFolder)
          override val reportName = project.objects.property(String::class.java).apply { set("debug") }
          override val projectName = project.objects.property(String::class.java).apply { set(":app") }
          override val variantName = project.objects.property(String::class.java).apply { set("debug") }
          override val testSuiteName = project.objects.property(String::class.java).apply { set("mySuite") }
          override val rootProjectName = project.objects.property(String::class.java).apply { set("project") }
          override val rootProjectDir = project.objects.directoryProperty().apply { set(temporaryFolder.root) }
        }
      }
    }
  }

  private companion object {
    /** A class that Hilt generates the source of. */
    const val COMPONENT = "com/example/app/DaggerApp_HiltComponents_SingletonC"
  }
}
