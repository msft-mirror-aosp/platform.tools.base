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

package com.android.build.gradle.internal.tasks

import com.android.build.gradle.internal.fixtures.FakeGradleProperty
import com.android.build.gradle.internal.fixtures.FakeNoOpAnalyticsService
import com.android.build.gradle.internal.fixtures.FakeObjectFactory
import com.android.build.gradle.internal.packaging.defaultExcludes
import com.android.build.gradle.internal.packaging.defaultMerges
import com.android.build.gradle.internal.profile.AnalyticsService
import com.android.builder.packaging.JarFlinger
import com.android.testutils.truth.ZipFileSubject.assertThat
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.gradle.api.provider.Property
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Test cases for [MergeJavaResOptimizedWorkAction]. */
class MergeJavaResOptimizedWorkActionTest {

  @get:Rule val tmpDir = TemporaryFolder()

  @Test
  fun testMergeJavaRes() {
    val projectJar = tmpDir.root.resolve("project.jar")

    JarFlinger(projectJar.toPath()).use {
      it.addEntry("projectFile", byteStream("project content"))
      it.addEntry("conflictFile", byteStream("project conflict"))
    }

    val dependencyJar = tmpDir.root.resolve("dependency.jar")
    JarFlinger(dependencyJar.toPath()).use {
      it.addEntry("dependencyFile", byteStream("dependency content"))
      it.addEntry("conflictFile", byteStream("dependency conflict"))
      it.addEntry("LICENSE", byteStream("license content"))
    }

    val outputFile = tmpDir.root.resolve("out.jar")

    object : MergeJavaResOptimizedWorkAction() {
        override fun getParameters(): Params {
          return object : Params() {
            override val projectJavaResJar = FakeObjectFactory.factory.fileProperty().also { it.set(projectJar) }
            override val mergedDependenciesJavaRes = FakeObjectFactory.factory.fileCollection().from(dependencyJar)
            override val featureJavaRes = FakeObjectFactory.factory.fileCollection()
            override val outputFile = FakeObjectFactory.factory.fileProperty().also { it.set(outputFile) }
            override val noCompress = FakeObjectFactory.factory.listProperty(String::class.java)
            override val excludes = FakeObjectFactory.factory.setProperty(String::class.java).also { it.set(defaultExcludes) }
            override val pickFirsts = FakeObjectFactory.factory.setProperty(String::class.java)
            override val merges = FakeObjectFactory.factory.setProperty(String::class.java).also { it.set(defaultMerges) }
            override val projectPath = FakeGradleProperty("projectName")
            override val taskOwner = FakeGradleProperty("taskOwner")
            override val workerKey = FakeGradleProperty("workerKey")
            override val analyticsService: Property<AnalyticsService> = FakeGradleProperty(FakeNoOpAnalyticsService())
          }
        }
      }
      .execute()

    assertThat(outputFile) {
      it.contains("projectFile")
      it.contains("dependencyFile")
      it.containsFileWithContent("conflictFile", "project conflict")
      it.doesNotContain("LICENSE")
    }
  }

  @Test
  fun testMergeResourcesWithMerges() {
    val projectJar = tmpDir.root.resolve("project.jar")
    JarFlinger(projectJar.toPath()).use { it.addEntry("mergeFile", byteStream("project\n")) }

    val dependencyJar = tmpDir.root.resolve("dependency.jar")
    JarFlinger(dependencyJar.toPath()).use { it.addEntry("mergeFile", byteStream("dependency\n")) }
    val outputFile = tmpDir.root.resolve("out.jar")

    object : MergeJavaResOptimizedWorkAction() {
        override fun getParameters(): Params {
          return object : Params() {
            override val projectJavaResJar = FakeObjectFactory.factory.fileProperty().also { it.set(projectJar) }
            override val mergedDependenciesJavaRes = FakeObjectFactory.factory.fileCollection().from(dependencyJar)
            override val featureJavaRes = FakeObjectFactory.factory.fileCollection()
            override val outputFile = FakeObjectFactory.factory.fileProperty().also { it.set(outputFile) }
            override val noCompress = FakeObjectFactory.factory.listProperty(String::class.java)
            override val excludes = FakeObjectFactory.factory.setProperty(String::class.java).also { it.set(defaultExcludes) }
            override val pickFirsts = FakeObjectFactory.factory.setProperty(String::class.java)
            override val merges = FakeObjectFactory.factory.setProperty(String::class.java).also { it.set(setOf("mergeFile")) }
            override val projectPath = FakeGradleProperty("projectName")
            override val taskOwner = FakeGradleProperty("taskOwner")
            override val workerKey = FakeGradleProperty("workerKey")
            override val analyticsService: Property<AnalyticsService> = FakeGradleProperty(FakeNoOpAnalyticsService())
          }
        }
      }
      .execute()

    assertThat(outputFile) { it.containsFileWithContent("mergeFile", "project\ndependency\n") }
  }

  @Test
  fun testMergeResourcesWithPickFirsts() {
    val projectJar =
      tmpDir.root.resolve("project.jar").also { file ->
        JarFlinger(file.toPath()).use { it.addEntry("pickFirstFile", byteStream("project content")) }
      }

    val dependencyJar =
      File(tmpDir.root, "dependency.jar").also { file ->
        JarFlinger(file.toPath()).use { it.addEntry("pickFirstFile", byteStream("dependency content")) }
      }

    val outputFile = tmpDir.root.resolve("out.jar")

    object : MergeJavaResOptimizedWorkAction() {
        override fun getParameters(): Params {
          return object : Params() {
            override val projectJavaResJar = FakeObjectFactory.factory.fileProperty().also { it.set(projectJar) }
            override val mergedDependenciesJavaRes = FakeObjectFactory.factory.fileCollection().from(dependencyJar)
            override val featureJavaRes = FakeObjectFactory.factory.fileCollection()
            override val outputFile = FakeObjectFactory.factory.fileProperty().also { it.set(outputFile) }
            override val noCompress = FakeObjectFactory.factory.listProperty(String::class.java)
            override val excludes = FakeObjectFactory.factory.setProperty(String::class.java).also { it.set(defaultExcludes) }
            override val pickFirsts = FakeObjectFactory.factory.setProperty(String::class.java).also { it.set(setOf("pickFirstFile")) }
            override val merges = FakeObjectFactory.factory.setProperty(String::class.java).also { it.set(defaultMerges) }
            override val projectPath = FakeGradleProperty("projectName")
            override val taskOwner = FakeGradleProperty("taskOwner")
            override val workerKey = FakeGradleProperty("workerKey")
            override val analyticsService: Property<AnalyticsService> = FakeGradleProperty(FakeNoOpAnalyticsService())
          }
        }
      }
      .execute()

    assertThat(outputFile) {
      // Selects project as it is a higher priority.
      it.containsFileWithContent("pickFirstFile", "project content")
    }
  }

  @Test
  fun testMergeResourcesWithNoCompress() {
    val projectJar =
      tmpDir.root.resolve("project.jar").also { file ->
        JarFlinger(file.toPath()).use {
          val outputStream = ByteArrayOutputStream()
          DeflaterOutputStream(outputStream).use { os -> os.write("content".toByteArray()) }
          it.addEntry("compressedFile", outputStream.toByteArray().inputStream())
          it.addEntry("uncompressedFile", byteStream("content"))
        }
      }

    val outputFile = tmpDir.root.resolve("out.jar")

    object : MergeJavaResOptimizedWorkAction() {
        override fun getParameters(): Params {
          return object : Params() {
            override val projectJavaResJar = FakeObjectFactory.factory.fileProperty().also { it.set(projectJar) }
            override val mergedDependenciesJavaRes = FakeObjectFactory.factory.fileCollection()
            override val featureJavaRes = FakeObjectFactory.factory.fileCollection()
            override val outputFile = FakeObjectFactory.factory.fileProperty().also { it.set(outputFile) }
            override val noCompress = FakeObjectFactory.factory.listProperty(String::class.java).also { it.set(listOf("uncompressedFile")) }
            override val excludes = FakeObjectFactory.factory.setProperty(String::class.java).also { it.set(defaultExcludes) }
            override val pickFirsts = FakeObjectFactory.factory.setProperty(String::class.java)
            override val merges = FakeObjectFactory.factory.setProperty(String::class.java).also { it.set(defaultMerges) }
            override val projectPath = FakeGradleProperty("projectName")
            override val taskOwner = FakeGradleProperty("taskOwner")
            override val workerKey = FakeGradleProperty("workerKey")
            override val analyticsService: Property<AnalyticsService> = FakeGradleProperty(FakeNoOpAnalyticsService())
          }
        }
      }
      .execute()

    ZipFile(outputFile).use {
      assertThat(it.getEntry("compressedFile").method).isEqualTo(ZipEntry.DEFLATED)
      assertThat(it.getEntry("uncompressedFile").method).isEqualTo(ZipEntry.STORED)
    }
  }

  @Test
  fun testMergeResourcesWithManifestConflict() {
    val projectJar = tmpDir.root.resolve("project.jar")
    JarFlinger(projectJar.toPath()).use {
      it.addEntry("META-INF/MANIFEST.MF", byteStream("Manifest-Version: 1.0\n"))
      it.addEntry("projectFile", byteStream("project content"))
    }

    val dependencyJar =
      tmpDir.root.resolve("dependency.jar").also { jar ->
        JarFlinger(jar.toPath()).use {
          it.addEntry("META-INF/MANIFEST.MF", byteStream("Manifest-Version: 1.0\n"))
          it.addEntry("dependencyFile", byteStream("dependency content"))
        }
      }

    val outputFile = tmpDir.root.resolve("out.jar")

    object : MergeJavaResOptimizedWorkAction() {
        override fun getParameters(): Params {
          return object : Params() {
            override val projectJavaResJar = FakeObjectFactory.factory.fileProperty().also { it.set(projectJar) }
            override val mergedDependenciesJavaRes = FakeObjectFactory.factory.fileCollection().from(dependencyJar, dependencyJar)
            override val featureJavaRes = FakeObjectFactory.factory.fileCollection()
            override val outputFile = FakeObjectFactory.factory.fileProperty().also { it.set(outputFile) }
            override val noCompress = FakeObjectFactory.factory.listProperty(String::class.java)
            override val excludes = FakeObjectFactory.factory.setProperty(String::class.java).also { it.set(defaultExcludes) }
            override val pickFirsts = FakeObjectFactory.factory.setProperty(String::class.java)
            override val merges = FakeObjectFactory.factory.setProperty(String::class.java).also { it.set(defaultMerges) }
            override val projectPath = FakeGradleProperty("projectName")
            override val taskOwner = FakeGradleProperty("taskOwner")
            override val workerKey = FakeGradleProperty("workerKey")
            override val analyticsService: Property<AnalyticsService> = FakeGradleProperty(FakeNoOpAnalyticsService())
          }
        }
      }
      .execute()

    assertThat(outputFile) {
      it.contains("projectFile")
      it.contains("dependencyFile")
      // MANIFEST.MF should be excluded by default
      it.doesNotContain("META-INF/MANIFEST.MF")
    }
  }

  @Test
  fun testMergeResourcesWithManifestConflictInDependencies() {
    val dependencyJar1 = tmpDir.root.resolve("dependency1.jar")
    JarFlinger(dependencyJar1.toPath()).use {
      it.addEntry("META-INF/MANIFEST.MF", byteStream("Manifest-Version: 1.0\n"))
      it.addEntry("dependencyFile1", byteStream("dependency content 1"))
    }

    val dependencyJar2 = tmpDir.root.resolve("dependency2.jar")
    JarFlinger(dependencyJar2.toPath()).use {
      it.addEntry("META-INF/MANIFEST.MF", byteStream("Manifest-Version: 1.0\n"))
      it.addEntry("dependencyFile2", byteStream("dependency content 2"))
    }

    val outputFile = tmpDir.root.resolve("out.jar")

    object : MergeJavaResOptimizedWorkAction() {
        override fun getParameters(): Params {
          return object : Params() {
            override val projectJavaResJar = FakeObjectFactory.factory.fileProperty()
            override val mergedDependenciesJavaRes = FakeObjectFactory.factory.fileCollection().from(dependencyJar1, dependencyJar2)
            override val featureJavaRes = FakeObjectFactory.factory.fileCollection()
            override val outputFile = FakeObjectFactory.factory.fileProperty().also { it.set(outputFile) }
            override val noCompress = FakeObjectFactory.factory.listProperty(String::class.java)
            override val excludes = FakeObjectFactory.factory.setProperty(String::class.java).also { it.set(defaultExcludes) }
            override val pickFirsts = FakeObjectFactory.factory.setProperty(String::class.java)
            override val merges = FakeObjectFactory.factory.setProperty(String::class.java).also { it.set(defaultMerges) }
            override val projectPath = FakeGradleProperty("projectName")
            override val taskOwner = FakeGradleProperty("taskOwner")
            override val workerKey = FakeGradleProperty("workerKey")
            override val analyticsService: Property<AnalyticsService> = FakeGradleProperty(FakeNoOpAnalyticsService())
          }
        }
      }
      .execute()

    assertThat(outputFile) {
      it.contains("dependencyFile1")
      it.contains("dependencyFile2")
      it.doesNotContain("META-INF/MANIFEST.MF")
    }
  }

  private fun byteStream(content: String): ByteArrayInputStream = ByteArrayInputStream(content.toByteArray())
}
