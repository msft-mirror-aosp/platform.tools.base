/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.tools.preview.multipreview

import com.android.testutils.TestClassesGenerator
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val ROOT_PKG = "com/example/test"
private const val COMPOSE_PREVIEW_ANNOTATION = "androidx/compose/ui/tooling/preview/Preview"
private const val WEAR_TILE_PREVIEW_ANNOTATION = "androidx/wear/tiles/tooling/preview/Preview"
private const val COMPOSABLE_ANNOTATION = "androidx/compose/runtime/Composable"
private const val TILE_PREVIEW_DATA = "Landroidx/wear/tiles/tooling/preview/TilePreviewData;"
private const val TILE_PREVIEW_METHOD_SIGNATURE = "()$TILE_PREVIEW_DATA"

class PerfArtificialDataMultipreviewTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun testFullProject_newFinderImpl() {
    val files = prepareClassPath().map { File(it) }
    computeAndRecordMetric(
      "multipreview_time_artificial_data_new_impl_no_filter",
      "multipreview_memory_artificial_data_new_impl_no_filter",
    ) {
      val metric = MultipreviewMetric()
      metric.beforeTest()
      val previewMethods = PreviewMethodFinder(listOf(), files, listOf(), listOf(), listOf()).findAllPreviewMethods()
      metric.afterTest()
      // Validity check
      assertEquals(2830, previewMethods.size)
      assertEquals(1400, previewMethods.filterIsInstance<ComposePreviewMethod>().size)
      assertEquals(1430, previewMethods.filterIsInstance<WearTilePreviewMethod>().size)
      metric
    }
  }

  @Test
  fun testFullProject_withMainFilter_newFinderImpl() {
    val files = prepareClassPath().map { File(it) }
    val mainJars = files.subList(0, 1)
    val depsJars = files.subList(1, files.size)
    computeAndRecordMetric(
      "multipreview_time_artificial_data_new_impl_package_filter",
      "multipreview_memory_artificial_data_new_impl_package_filter",
    ) {
      val metric = MultipreviewMetric()
      metric.beforeTest()
      val previewMethods = PreviewMethodFinder(listOf(), mainJars, listOf(), listOf(), depsJars).findAllPreviewMethods()
      metric.afterTest()
      // Validity check
      assertEquals(570, previewMethods.size)
      assertEquals(300, previewMethods.filterIsInstance<ComposePreviewMethod>().size)
      assertEquals(270, previewMethods.filterIsInstance<WearTilePreviewMethod>().size)
      metric
    }
  }

  /**
   * This roughly tries to mimic the structure of the real android project that uses Compose:
   * * Library with a base annotation
   * * Main module that depends on the number (100) of other modules and libraries (1000)
   * * Each module contains some classes with methods and annotations
   */
  private fun prepareClassPath(): List<String> {
    val rootFolder = temporaryFolder.newFolder()
    val multiMultiMainId = 4
    val multiMultiModuleId = 4
    val multiMultiLibId = 4
    val multiMultiFolderId = 4
    val multiMultiTileMainId = 5
    return listOf(
      // 100 + 20 + 10 + 40 + 10 + 30 + 4 + 100 + 10 + 40 + 100 + 20 + 10 = 494 classes
      // 100 + 200 = 300 compose preview methods
      // 120 + 150 = 270 wear tile preview methods
      // 300 + 270 = 570 preview methods
      createJar(
        jarPath = rootFolder.toPath().resolve("main.jar"),
        pkg = "$ROOT_PKG/main",
        config =
          PackageConfig(
            unrelatedClasses = UnrelatedClasses(methodsCount = 20, count = 100),
            unrelatedAnnotationsCount = 20,
            multiAnnotations =
              listOf(
                MultiAnnotations(multiAnnotationsCount = 10, parentAnnotation = COMPOSE_PREVIEW_ANNOTATION, count = 10),
                MultiAnnotations(multiAnnotationsCount = multiMultiMainId, parentAnnotation = COMPOSE_PREVIEW_ANNOTATION, count = 40),
                MultiAnnotations(multiAnnotationsCount = 3, count = 10),
                MultiAnnotations(multiAnnotationsCount = multiMultiTileMainId, parentAnnotation = WEAR_TILE_PREVIEW_ANNOTATION, count = 30),
              ),
            annotatedClasses =
              listOf(
                AnnotatedClasses(
                  methodsCount = 20,
                  annotations = listOf(TestClassesGenerator.Annotation("$ROOT_PKG/main/UnrelatedAnnotation0")),
                  count = 100,
                ),
                // 10 * 10 = 100 compose preview methods
                AnnotatedClasses(
                  methodsCount = 10,
                  annotations =
                    listOf(
                      TestClassesGenerator.Annotation(COMPOSABLE_ANNOTATION),
                      TestClassesGenerator.Annotation("$ROOT_PKG/main/Multi${multiMultiMainId}Annotation0"),
                    ),
                  count = 10,
                ),
                // 5 * 40 = 200 compose preview methods
                AnnotatedClasses(
                  methodsCount = 5,
                  annotations =
                    listOf(
                      TestClassesGenerator.Annotation(COMPOSABLE_ANNOTATION),
                      TestClassesGenerator.Annotation(COMPOSE_PREVIEW_ANNOTATION, listOf("foo", "bar")),
                      TestClassesGenerator.Annotation(COMPOSE_PREVIEW_ANNOTATION, listOf("qwe", "asd")),
                    ),
                  count = 40,
                ),
                AnnotatedClasses(
                  methodsCount = 30,
                  annotations = listOf(TestClassesGenerator.Annotation("$ROOT_PKG/main/UnrelatedAnnotation0")),
                  count = 100,
                  methodSignature = TILE_PREVIEW_METHOD_SIGNATURE,
                ),
                // 15 * 10 = 150 wear tile preview methods
                AnnotatedClasses(
                  methodsCount = 15,
                  annotations =
                    listOf(
                      TestClassesGenerator.Annotation(WEAR_TILE_PREVIEW_ANNOTATION, listOf("foo", "bar")),
                      TestClassesGenerator.Annotation(WEAR_TILE_PREVIEW_ANNOTATION, listOf("qwe", "asd")),
                    ),
                  count = 10,
                  methodSignature = TILE_PREVIEW_METHOD_SIGNATURE,
                ),
                // 6 * 20 = 120 wear tile preview methods
                AnnotatedClasses(
                  methodsCount = 6,
                  annotations = listOf(TestClassesGenerator.Annotation("$ROOT_PKG/main/Multi${multiMultiTileMainId}Annotation0")),
                  count = 20,
                  methodSignature = TILE_PREVIEW_METHOD_SIGNATURE,
                ),
              ),
          ),
      ),
      // 1 + 100 classes
      // 0 preview methods
      createJar(
        rootFolder.toPath().resolve("base.jar"),
        sequence {
          yield(COMPOSE_PREVIEW_ANNOTATION to TestClassesGenerator.annotationClass(COMPOSE_PREVIEW_ANNOTATION, listOf("param1", "param2")))
          (0 until 100)
            .map { "$ROOT_PKG/base/SimpleClass$it" }
            .forEach { name ->
              yield(
                name to
                  TestClassesGenerator.classWithFieldsAndMethods(
                    name,
                    (0 until 20).map { "field$it" },
                    (0 until 20).map { "method$it:()V" },
                  )
              )
            }
        },
      ),
      // 20 * (100 + 20 + 10 + 40 + 10 + 30 + 100 + 5 + 10 + 100 + 6 + 11) = 8840 classes
      // 500 + 600 = 1100 compose preview methods
      // 720 + 440 = 1160 wear tile preview methods
      // 1100 + 1160 = 2260 preview methods
      *(0 until 20)
        .map { moduleId ->
          createJar(
            jarPath = rootFolder.toPath().resolve("classes$moduleId.jar"),
            pkg = "$ROOT_PKG/module$moduleId",
            config =
              PackageConfig(
                unrelatedClasses = UnrelatedClasses(methodsCount = 20, count = 100),
                unrelatedAnnotationsCount = 20,
                multiAnnotations =
                  listOf(
                    MultiAnnotations(multiAnnotationsCount = 10, parentAnnotation = COMPOSE_PREVIEW_ANNOTATION, count = 10),
                    MultiAnnotations(multiAnnotationsCount = multiMultiModuleId, parentAnnotation = COMPOSE_PREVIEW_ANNOTATION, count = 40),
                    MultiAnnotations(multiAnnotationsCount = 3, count = 10),
                    MultiAnnotations(
                      multiAnnotationsCount = multiMultiTileMainId,
                      parentAnnotation = WEAR_TILE_PREVIEW_ANNOTATION,
                      count = 30,
                    ),
                  ),
                annotatedClasses =
                  listOf(
                    AnnotatedClasses(
                      methodsCount = 20,
                      annotations = listOf(TestClassesGenerator.Annotation("$ROOT_PKG/module$moduleId/UnrelatedAnnotation0")),
                      count = 100,
                    ),
                    // 20 * 5 * 5 = 500 compose preview methods
                    AnnotatedClasses(
                      methodsCount = 5,
                      annotations =
                        listOf(
                          TestClassesGenerator.Annotation(COMPOSABLE_ANNOTATION),
                          TestClassesGenerator.Annotation("$ROOT_PKG/module$moduleId/Multi${multiMultiModuleId}Annotation0"),
                        ),
                      count = 5,
                    ),
                    // 20 * 3 * 10 = 600 compose preview methods
                    AnnotatedClasses(
                      methodsCount = 3,
                      annotations =
                        listOf(
                          TestClassesGenerator.Annotation(COMPOSABLE_ANNOTATION),
                          TestClassesGenerator.Annotation(COMPOSE_PREVIEW_ANNOTATION, listOf("foo$moduleId", "bar$moduleId")),
                          TestClassesGenerator.Annotation(COMPOSE_PREVIEW_ANNOTATION, listOf("qwe$moduleId", "asd$moduleId")),
                        ),
                      count = 10,
                    ),
                    AnnotatedClasses(
                      methodsCount = 30,
                      annotations = listOf(TestClassesGenerator.Annotation("$ROOT_PKG/module$moduleId/UnrelatedAnnotation0")),
                      count = 100,
                      methodSignature = TILE_PREVIEW_METHOD_SIGNATURE,
                    ),
                    // 20 * 5 * 5 = 720 wear tile preview methods
                    AnnotatedClasses(
                      methodsCount = 6,
                      annotations =
                        listOf(TestClassesGenerator.Annotation("$ROOT_PKG/module$moduleId/Multi${multiMultiTileMainId}Annotation0")),
                      count = 6,
                      methodSignature = TILE_PREVIEW_METHOD_SIGNATURE,
                    ),
                    // 20 * 2 * 11 = 440 wear tile preview methods
                    AnnotatedClasses(
                      methodsCount = 2,
                      annotations =
                        listOf(
                          TestClassesGenerator.Annotation(WEAR_TILE_PREVIEW_ANNOTATION, listOf("foo$moduleId", "bar$moduleId")),
                          TestClassesGenerator.Annotation(WEAR_TILE_PREVIEW_ANNOTATION, listOf("qwe$moduleId", "asd$moduleId")),
                        ),
                      count = 11,
                      methodSignature = TILE_PREVIEW_METHOD_SIGNATURE,
                    ),
                  ),
              ),
          )
        }
        .toTypedArray(),
      // 100 * (100 + 20 + 10 + 4 + 10 + 100) = 24400 classes
      // 0 preview methods
      *(0 until 100)
        .map { libId ->
          val pkg = "$ROOT_PKG/lib$libId"
          createJar(
            jarPath = rootFolder.toPath().resolve("lib_classes$libId.jar"),
            pkg = pkg,
            config =
              PackageConfig(
                unrelatedClasses = UnrelatedClasses(methodsCount = 20, count = 100),
                unrelatedAnnotationsCount = 20,
                multiAnnotations =
                  listOf(
                    MultiAnnotations(multiAnnotationsCount = 10, parentAnnotation = COMPOSE_PREVIEW_ANNOTATION, count = 10),
                    MultiAnnotations(multiAnnotationsCount = multiMultiLibId, parentAnnotation = COMPOSE_PREVIEW_ANNOTATION, count = 40),
                    MultiAnnotations(multiAnnotationsCount = 3, count = 10),
                  ),
                annotatedClasses =
                  listOf(
                    AnnotatedClasses(
                      methodsCount = 20,
                      annotations = listOf(TestClassesGenerator.Annotation("$pkg/UnrelatedAnnotation0")),
                      count = 100,
                    )
                  ),
              ),
          )
        }
        .toTypedArray(),
      // 20 * (100 + 20 + 10 + 4 + 10 + 100) = 4880 classes
      // 0 preview methods
      *(0 until 20)
        .map { folderId ->
          val pkg = "$ROOT_PKG/generated$folderId"
          createFolder(
            folderPath = rootFolder.toPath().resolve("generated_classes$folderId"),
            pkg = pkg,
            config =
              PackageConfig(
                unrelatedClasses = UnrelatedClasses(20, 100),
                unrelatedAnnotationsCount = 20,
                multiAnnotations =
                  listOf(
                    MultiAnnotations(multiAnnotationsCount = 10, parentAnnotation = COMPOSE_PREVIEW_ANNOTATION, count = 10),
                    MultiAnnotations(multiAnnotationsCount = multiMultiFolderId, parentAnnotation = COMPOSE_PREVIEW_ANNOTATION, count = 40),
                    MultiAnnotations(multiAnnotationsCount = 3, count = 10),
                  ),
                annotatedClasses =
                  listOf(
                    AnnotatedClasses(
                      methodsCount = 20,
                      annotations = listOf(TestClassesGenerator.Annotation("$ROOT_PKG/lib$folderId/UnrelatedAnnotation$folderId")),
                      count = 100,
                    )
                  ),
              ),
          )
        }
        .toTypedArray(),
      // 3 * (100 + 500) = 1800 classes
      // 0 preview methods
      *(0 until 3)
        .map { bigJarId ->
          createJar(
            jarPath = rootFolder.toPath().resolve("verybig_classes$bigJarId.jar"),
            pkg = "$ROOT_PKG/verybig$bigJarId",
            config =
              PackageConfig(
                unrelatedAnnotationsCount = 100,
                annotatedClasses =
                  listOf(
                    AnnotatedClasses(
                      methodsCount = 20,
                      annotations = listOf(TestClassesGenerator.Annotation("$ROOT_PKG/verybig$bigJarId/UnrelatedAnnotation1")),
                      count = 500,
                    )
                  ),
              ),
          )
        }
        .toTypedArray(),
    )
  }

  private fun createJar(jarPath: Path, entries: Sequence<Pair<String, ByteArray>>): String {
    ZipOutputStream(Files.newOutputStream(jarPath)).use { zip ->
      entries.forEach {
        val entry = ZipEntry("${it.first}.class")
        zip.putNextEntry(entry)
        zip.write(it.second)
        zip.closeEntry()
      }
    }
    return jarPath.toString()
  }

  private fun createClasses(pkg: String, config: PackageConfig): Sequence<Pair<String, ByteArray>> = sequence {
    config.multiAnnotations.forEach { (m, parentAnnotation, n) ->
      (0 until n).forEach { i ->
        val isBase = parentAnnotation != null
        val name = if (isBase) "$pkg/Multi${m}Annotation$i" else "$pkg/MultiMulti${m}Annotation$i"
        yield(
          name to
            TestClassesGenerator.annotationClass(
              name,
              emptyList(),
              (0 until m).map { j ->
                if (isBase) TestClassesGenerator.Annotation(parentAnnotation, (0..1).map { "val${j}_${it}" })
                else TestClassesGenerator.Annotation("$pkg/Multi${m}Annotation${i * m + j}", emptyList())
              },
            )
        )
      }
    }
    (0 until config.unrelatedAnnotationsCount)
      .map { "$pkg/UnrelatedAnnotation$it" }
      .forEach { name -> yield(name to TestClassesGenerator.annotationClass(name)) }
    config.unrelatedClasses.let { (m, n) ->
      (0 until n)
        .map { "$pkg/Simple${m}Class$it" }
        .forEach { name ->
          yield(name to TestClassesGenerator.classWithAnnotatedMethods(name, (0 until m).map { "method$it:()V" }, emptyList(), m))
        }
    }
    config.annotatedClasses.forEach { (m, annotations, n, methodSignature) ->
      (0 until n)
        .map { "$pkg/AnnotatedMethods${m}Class$it" }
        .forEach { name ->
          yield(
            name to
              TestClassesGenerator.classWithAnnotatedMethods(
                name,
                (0 until m).map { "AnnotatedMethod$it:$methodSignature" },
                annotations,
                m,
              )
          )
        }
    }
  }

  private fun createJar(jarPath: Path, pkg: String, config: PackageConfig): String = createJar(jarPath, createClasses(pkg, config))

  private fun createFolder(folderPath: Path, entries: Sequence<Pair<String, ByteArray>>): String {
    folderPath.toFile().mkdirs()
    entries.forEach { (name, content) ->
      val folderName = name.substringBeforeLast("/")
      val fileName = name.substringAfterLast("/")
      val fullFolderPath = folderPath.resolve(folderName)
      fullFolderPath.toFile().mkdirs()
      Files.write(fullFolderPath.resolve("$fileName.class"), content)
    }
    return folderPath.toString()
  }

  /** [methodsCount] - number of methods per class, [count] - number of classes */
  private data class UnrelatedClasses(val methodsCount: Int, val count: Int)

  /**
   * [multiAnnotationsCount] - number of (multipreview) annotations annotating each of this multipreview annotations. [parentAnnotation] -
   * optional parent annotation, if specified this multi annotation is considered to be a base multi annotation [count] - number of
   * multi-multipreview annotations
   */
  private data class MultiAnnotations(val multiAnnotationsCount: Int, val parentAnnotation: String? = null, val count: Int)

  /**
   * [methodsCount] - number of methods of a class [annotations] - annotations to annotate the methods (all methods annotated with the same
   * annotations) [count] - number of classes [methodSignature] - method signature to use on generated methods
   */
  private data class AnnotatedClasses(
    val methodsCount: Int,
    val annotations: List<TestClassesGenerator.Annotation>,
    val count: Int,
    val methodSignature: String = "(Ljava/lang/String;Ljava/lang/String;)V",
  )

  private data class PackageConfig(
    val unrelatedClasses: UnrelatedClasses = UnrelatedClasses(0, 0),
    val unrelatedAnnotationsCount: Int = 0,
    val multiAnnotations: List<MultiAnnotations> = emptyList(),
    val annotatedClasses: List<AnnotatedClasses> = emptyList(),
  )

  private fun createFolder(folderPath: Path, pkg: String, config: PackageConfig): String =
    createFolder(folderPath, createClasses(pkg, config))
}
