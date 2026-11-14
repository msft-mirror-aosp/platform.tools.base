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
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.w3c.dom.Element

class CoverageUtilsTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun findProjectClassesLeavesOutTheClassesThatReportsSkip() {
    val classFolder = temporaryFolder.newFolder("classes")
    writeFiles(
      classFolder,
      "com/example/Foo.class",
      "com/example/Foo\$Bar.class",
      "com/example/R.class",
      "com/example/R\$string.class",
      "com/example/Manifest.class",
      "com/example/Manifest\$permission.class",
      "com/example/BuildConfig.class",
      "META-INF/app.kotlin_module",
    )

    assertThat(findProjectClasses(listOf(classFolder)))
      .containsExactly("com/example/Foo", "com/example/Foo\$Bar", "com/example/BuildConfig")
  }

  @Test
  fun findProjectClassesNamesTheClassesOfANestedClassFolderRelativeToIt() {
    val classFolder = temporaryFolder.newFolder("classes")
    writeFiles(classFolder, "com/example/Foo.class")
    val nestedClassFolder = File(classFolder, "nested")
    writeFiles(nestedClassFolder, "com/example/Bar.class")

    assertThat(findProjectClasses(listOf(classFolder, nestedClassFolder))).containsExactly("com/example/Foo", "com/example/Bar")
  }

  @Test
  fun findProjectClassesSkipsEntriesThatAreNotFolders() {
    val jar = temporaryFolder.newFile("classes.jar")
    val classFile = temporaryFolder.newFile("Foo.class")

    assertThat(findProjectClasses(listOf(jar, classFile, File(temporaryFolder.root, "missing")))).isEmpty()
  }

  @Test
  fun findGeneratedCodeFindsTheCodeOfGeneratedAnnotations() {
    val classFolder = temporaryFolder.newFolder("classes")
    // Dagger's annotation has class retention.
    writeClass(classFolder, "com/example/Foo_Factory", classAnnotation = "Ldagger/internal/DaggerGenerated;")
    // The simple name only needs to contain Generated.
    writeClass(classFolder, "com/example/Bar", classAnnotation = "Lcom/example/GeneratedBy;")
    writeClass(
      classFolder,
      "com/example/Baz",
      methodAnnotations =
        mapOf(
          // The simple name of a nested annotation is the part after the $.
          "generated" to "Lcom/example/Outer\$Generated;",
          "written" to "Lcom/example/Generated\$Factory;",
          "plain" to "Lkotlin/jvm/JvmStatic;",
        ),
    )
    writeClass(classFolder, "com/example/Plain", classAnnotation = "Lkotlin/Metadata;")
    // Files that ASM can't read don't stop the others, and their code stays in the report: one that isn't a class file, one of a class file
    // version it doesn't support, and one without its last byte, on which ClassReader.accept() fails only after it visited the annotation.
    writeFiles(classFolder, "com/example/Broken.class")
    File(classFolder, "com/example/Future.class")
      .writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, 0, 0xFF.toByte()))
    writeClass(classFolder, "com/example/Cut_Factory", classAnnotation = "Ldagger/internal/DaggerGenerated;")
    val cut = File(classFolder, "com/example/Cut_Factory.class")
    cut.writeBytes(cut.readBytes().let { it.copyOf(it.size - 1) })

    val generatedCode = findGeneratedCode(listOf(classFolder))

    assertThat(generatedCode.classes).containsExactly("com/example/Foo_Factory", "com/example/Bar")
    assertThat(generatedCode.methods).containsExactly("com/example/Baz.generated()V")
  }

  @Test
  fun onDeviceReportLeavesOutGeneratedCode() {
    val runDir = temporaryFolder.newFolder("coverage_data")
    val metadata =
      CoverageMetadata.newBuilder()
        .setVersion(1)
        .addClasses(onDeviceClass("com/example/Foo_Factory", "Foo_Factory.java", blockId = 0))
        .addClasses(onDeviceClass("com/example/Foo", "Foo.kt", blockId = 1))
        .build()
    File(runDir, ON_DEVICE_COVERAGE_METADATA_FILE).outputStream().use { metadata.writeTo(it) }
    val hits = CoverageHits.newBuilder().setVersion(1).setHitMask(ByteString.copyFrom(BitSet().apply { set(0, 2) }.toByteArray())).build()
    File(runDir, ON_DEVICE_COVERAGE_HITS_FILE).outputStream().use { hits.writeTo(it) }
    val classFolder = temporaryFolder.newFolder("classes")
    writeClass(classFolder, "com/example/Foo_Factory", classAnnotation = "Ldagger/internal/DaggerGenerated;")
    writeClass(classFolder, "com/example/Foo")
    val report = File(temporaryFolder.root, "report.xml")

    generateOnTheFlyXml(
      File(runDir, ON_DEVICE_COVERAGE_METADATA_FILE),
      File(runDir, ON_DEVICE_COVERAGE_HITS_FILE),
      report,
      "debug",
      testPackageId = null,
      exclusions = emptySet(),
      sourceFolders = emptyList(),
      classFolders = listOf(classFolder),
    )

    val docFactory = DocumentBuilderFactory.newInstance()
    docFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    val classes = docFactory.newDocumentBuilder().parse(report).getElementsByTagName("class")
    val methodCounts =
      (0 until classes.length).associate { index ->
        val element = classes.item(index) as Element
        element.getAttribute("name") to element.getElementsByTagName("method").length
      }
    // The generated class stays in the report, without its method.
    assertThat(methodCounts).containsExactly("com/example/Foo_Factory", 0, "com/example/Foo", 1)
  }

  private fun onDeviceClass(name: String, sourceFile: String, blockId: Int): ClassMetadata =
    ClassMetadata.newBuilder()
      .setClassName("L$name;")
      .setSourceFile(sourceFile)
      .addMethods(
        MethodMetadata.newBuilder()
          .setName("get")
          .setSignature("()V")
          .addBlocks(
            BlockMetadata.newBuilder()
              .setBlockId(blockId)
              .setBranchCount(1)
              .addLines(LineMetadata.newBuilder().setLineNumber(3).setInstructionCount(2))
          )
      )
      .build()

  /**
   * Writes the class file of an abstract class [name] into [folder], with the class retention [classAnnotation], and abstract methods `()V`
   * named after the keys of [methodAnnotations], each with the runtime retention annotation of its value.
   */
  private fun writeClass(folder: File, name: String, classAnnotation: String? = null, methodAnnotations: Map<String, String> = emptyMap()) {
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, name, null, "java/lang/Object", null)
    classAnnotation?.let { writer.visitAnnotation(it, /* visible= */ false).visitEnd() }
    for ((method, annotation) in methodAnnotations) {
      val methodVisitor = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, method, "()V", null, null)
      methodVisitor.visitAnnotation(annotation, /* visible= */ true).visitEnd()
      methodVisitor.visitEnd()
    }
    writer.visitEnd()
    File(folder, "$name.class").apply { parentFile.mkdirs() }.writeBytes(writer.toByteArray())
  }

  private fun writeFiles(folder: File, vararg paths: String) {
    for (path in paths) {
      File(folder, path).apply { parentFile.mkdirs() }.writeText("")
    }
  }
}
