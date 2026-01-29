/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.tools.screenshot.descriptor

import com.android.tools.preview.multipreview.BaseAnnotationRepresentation
import com.android.tools.preview.multipreview.ComposePreviewMethod
import com.android.tools.preview.multipreview.ParameterRepresentation
import com.android.tools.preview.multipreview.PreviewMethod
import com.android.tools.preview.multipreview.WearTilePreviewMethod
import com.android.tools.render.compose.ComposeScreenshot
import com.android.tools.render.wear.WearTileScreenshot
import com.android.tools.screenshot.PreviewScreenshotExecutionContext
import java.security.MessageDigest
import java.util.Optional
import java.util.SortedMap
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestSource
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.engine.support.hierarchical.Node
import org.objectweb.asm.Type

class PreviewAnnotationDescriptor(
  parentId: UniqueId,
  private val className: String,
  private val methodName: String,
  private val preview: PreviewMethod,
  private val previewAnnotation: BaseAnnotationRepresentation,
  private val displayNameIncludesParams: Boolean,
  private val previewId: String = calcPreviewId(preview, previewAnnotation),
) : AbstractTestDescriptor(parentId.append(SEGMENT_TYPE, previewId), methodName), Node<PreviewScreenshotExecutionContext> {
  companion object {
    const val SEGMENT_TYPE: String = "previewAnnotation"

    private val invalidCharsRegex = """[\u0000-\u001F\\/:*?"<>|]+""".toRegex()

    private fun calcPreviewId(preview: PreviewMethod, previewAnnotation: BaseAnnotationRepresentation): String {
      val previewIdBuilder = StringBuilder(preview.method.methodFqn)

      val previewName = previewAnnotation.parameters["name"]
      if (previewName != null && previewName is CharSequence) {
        if (previewName.contains(invalidCharsRegex)) {
          System.err.println(
            "Preview name '$previewName' contains invalid characters. " +
              "It will be included in the HTML report but ignored in image file names."
          )
        } else {
          previewIdBuilder.append("_").append(previewName)
        }
      }

      val digest = MessageDigest.getInstance("SHA-1")

      updateAndAppendHash(digest, previewIdBuilder, "annotation-parameters", previewAnnotation.parameters)

      if (preview.method.parameters.isNotEmpty()) {
        // Currently only one param is supported and max size of method.parameters is 1
        for (param in preview.method.parameters) {
          updateAndAppendHash(digest, previewIdBuilder, "method-parameter-annotations", param.annotationParameters)
        }
      }

      return previewIdBuilder.toString()
    }

    private fun updateAndAppendHash(digest: MessageDigest, builder: StringBuilder, dataSectionName: String, dataMap: Map<String, *>) {
      if (dataMap.isNotEmpty()) {
        digest.update(dataSectionName.toByteArray())
        for ((key, value) in dataMap.toSortedMap()) {
          digest.update(key.toByteArray())
          digest.update(value.toString().toByteArray())
        }
        builder.append("_").append(calcHexString(digest.digest()))
      }
    }

    private fun calcHexString(digest: ByteArray): String {
      val hexString = digest.joinToString("") { eachByte -> "%02x".format(eachByte) }
      return if (hexString.length >= 8) {
        hexString.substring(0, 8)
      } else {
        hexString
      }
    }
  }

  private val source: MethodSource = MethodSource.from(className, methodName)

  override fun getType(): TestDescriptor.Type = TestDescriptor.Type.CONTAINER

  override fun getSource(): Optional<TestSource> = Optional.of(source)

  override fun mayRegisterTests(): Boolean = true

  override fun execute(
    context: PreviewScreenshotExecutionContext,
    dynamicTestExecutor: Node.DynamicTestExecutor,
  ): PreviewScreenshotExecutionContext {
    requireNotNull(context.renderer)

    val previewScreenshot =
      when (preview) {
        is ComposePreviewMethod -> {
          ComposeScreenshot(
            methodFQN = preview.method.methodFqn,
            methodParams = convertListMap(preview.method.parameters),
            previewParams = convertMap(previewAnnotation.parameters),
            previewId = previewId,
          )
        }
        is WearTilePreviewMethod -> {
          WearTileScreenshot(
            methodFQN = preview.method.methodFqn,
            previewParams = convertMap(previewAnnotation.parameters),
            previewId = previewId,
          )
        }
      }

    context.renderer.render(previewScreenshot, context.previewImageOutputDir.absolutePath).forEachIndexed { idx, result ->
      val previewNameBuilder = StringBuilder()
      val nameParam = previewScreenshot.previewParams["name"]
      nameParam?.let { previewNameBuilder.append("_$it") }

      val otherParamsBuilder = StringBuilder()
      if (displayNameIncludesParams) {
        previewScreenshot.previewParams
          .filterKeys { it != "name" }
          .let {
            if (it.isNotEmpty()) {
              otherParamsBuilder.append("_$it")
            }
          }
      }
      if (previewScreenshot is ComposeScreenshot) {
        if (previewScreenshot.methodParams.isNotEmpty()) {
          otherParamsBuilder.append("_${previewScreenshot.methodParams}_$idx")
        }
      }

      if (otherParamsBuilder.isNotEmpty()) {
        previewNameBuilder.append(otherParamsBuilder)
      }

      val previewDisplayName =
        nameParam?.toString() ?: otherParamsBuilder.toString().removePrefix("_").takeIf { it.isNotEmpty() } ?: methodName

      val childNode =
        PreviewScreenshotDescriptor(uniqueId, className, methodName, previewNameBuilder.toString(), previewDisplayName, idx, result)
      addChild(childNode)
      dynamicTestExecutor.execute(childNode)
    }
    return context
  }

  /**
   * Converts list of [ParameterRepresentation] to a list of maps representing method parameters
   *
   * The generated list contains sorted maps and is sorted by key and then by value.
   *
   * @param parameters the list of [ParameterRepresentation] to convert
   * @return a list of maps *
   */
  private fun convertListMap(parameters: List<ParameterRepresentation>): List<SortedMap<String, String>> {
    return sortListOfSortedMaps(parameters.map { convertMap(it.annotationParameters) })
  }

  private fun convertMap(map: Map<String, Any?>): SortedMap<String, String> =
    map.map { (key, value) -> key to (if (key == "provider") (value as Type).className else value.toString()) }.toMap().toSortedMap()

  /**
   * Sort provided list of maps
   *
   * Empty maps will be listed first, then maps will be sorted by key. If there are multiple maps with the same key, that key's value will
   * be used to sort.
   * *
   */
  private fun sortListOfSortedMaps(listToSort: List<SortedMap<String, String>>): List<SortedMap<String, String>> {
    return listToSort.sortedWith { map1, map2 ->
      val sortedEntries1 = map1.entries.sortedWith(compareBy<Map.Entry<String, String>> { it.key }.thenBy { it.value })
      val sortedEntries2 = map2.entries.sortedWith(compareBy<Map.Entry<String, String>> { it.key }.thenBy { it.value })

      // Iterate through both lists of entries, comparing each pair
      val largerSize = maxOf(sortedEntries1.size, sortedEntries2.size)
      for (i in IntRange(0, largerSize)) {
        // If we run out of entries in one map, the shorter map comes first
        if (i >= sortedEntries1.size) return@sortedWith -1
        if (i >= sortedEntries2.size) return@sortedWith 1

        val (key1, value1) = sortedEntries1[i]
        val (key2, value2) = sortedEntries2[i]

        // Compare keys first
        val keyComparison = key1.compareTo(key2)
        if (keyComparison != 0) return@sortedWith keyComparison

        // If keys are equal, compare values
        val valueComparison = value1.compareTo(value2)
        if (valueComparison != 0) return@sortedWith valueComparison
      }

      // If all entries match, the maps are equal
      return@sortedWith 0
    }
  }
}
