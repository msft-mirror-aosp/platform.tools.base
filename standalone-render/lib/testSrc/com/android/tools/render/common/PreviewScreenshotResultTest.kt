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

package com.android.tools.render.common

import com.android.tools.rendering.compose.ComposeViewInfo
import com.android.tools.rendering.compose.PxBounds
import com.android.tools.rendering.compose.SourceLocationImpl
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass
import java.io.Serializable
import java.util.Base64
import org.junit.Test

class PreviewScreenshotResultTest {

  private class LegacySevenFieldResult(
    val previewId: String,
    val methodFQN: String,
    val imagePath: String,
    val error: ScreenshotError?,
    val displayName: String?,
    val viewInfos: List<ComposeViewInfo>,
    val componentTreePath: String?,
  ) : Serializable {
    companion object {
      private const val serialVersionUID: Long = -1391406289525218573L
    }
  }

  // Exercises readResolve()'s defensive viewInfos == null guard under the 0.0.1-alpha17 UID.
  // Published 0.0.1-alpha13..0.0.1-alpha16 4-field classes had a different auto-generated UID (5359656199751605335L).
  private class LegacyFourFieldResult(
    val previewId: String,
    val methodFQN: String,
    val imagePath: String,
    val error: ScreenshotError?,
  ) : Serializable {
    companion object {
      private const val serialVersionUID: Long = -1391406289525218573L
    }
  }

  @Test
  fun serialVersionUidMatchesPublishedAlpha17Class() {
    val uid = ObjectStreamClass.lookup(PreviewScreenshotResult::class.java).serialVersionUID
    assertThat(uid).isEqualTo(-1391406289525218573L)
  }

  @Test
  fun javaSerializationRoundTripPreservesWarnings() {
    val warning = RenderProblem("Unsupported device spec", "java.lang.RuntimeException: trace")
    val original =
      PreviewScreenshotResult(
        previewId = "id",
        methodFQN = "pkg.PreviewKt.Sample",
        imagePath = "pkg/id.png",
        error = null,
        displayName = "Sample",
        viewInfos = emptyList(),
        componentTreePath = "pkg/id.json",
        warnings = listOf(warning),
      )

    val bytes = serialize(original)
    val deserialized = deserialize(bytes)

    assertThat(deserialized).isEqualTo(original)
    assertThat(deserialized.warnings).containsExactly(warning)
  }

  @Test
  fun deserializingCapturedAlpha17StreamDefaultsWarningsToEmptyList() {
    // Captured directly from published compose-preview-renderer-0.0.1-alpha17.jar (2026-10-30):
    // PreviewScreenshotResult("id", "pkg.PreviewKt.Sample", "pkg/id.png", null, "Sample", emptyList(), "pkg/id.json")
    val capturedBytes =
      Base64.getDecoder()
        .decode(
          "rO0ABXNyADdjb20uYW5kcm9pZC50b29scy5yZW5kZXIuY29tbW9uLlByZXZpZXdTY3JlZW5zaG90UmVzdWx07LC7Wgaa4vMCAAdMABFjb21wb25lbnRUcmVlUGF0aHQAEkxqYXZhL2xhbmcvU3RyaW5nO0wAC2Rpc3BsYXlOYW1lcQB+AAFMAAVlcnJvcnQAMUxjb20vYW5kcm9pZC90b29scy9yZW5kZXIvY29tbW9uL1NjcmVlbnNob3RFcnJvcjtMAAlpbWFnZVBhdGhxAH4AAUwACW1ldGhvZEZRTnEAfgABTAAJcHJldmlld0lkcQB+AAFMAAl2aWV3SW5mb3N0ABBMamF2YS91dGlsL0xpc3Q7eHB0AAtwa2cvaWQuanNvbnQABlNhbXBsZXB0AApwa2cvaWQucG5ndAAUcGtnLlByZXZpZXdLdC5TYW1wbGV0AAJpZHNyAB9qYXZhLnV0aWwuQ29sbGVjdGlvbnMkRW1wdHlMaXN0ergXtDynnt4CAAB4cA=="
        )

    val deserialized = deserialize(capturedBytes)

    assertThat(deserialized.previewId).isEqualTo("id")
    assertThat(deserialized.methodFQN).isEqualTo("pkg.PreviewKt.Sample")
    assertThat(deserialized.imagePath).isEqualTo("pkg/id.png")
    assertThat(deserialized.error).isNull()
    assertThat(deserialized.displayName).isEqualTo("Sample")
    assertThat(deserialized.componentTreePath).isEqualTo("pkg/id.json")
    assertThat(deserialized.viewInfos).isEmpty()
    assertThat(deserialized.warnings).isEmpty()
  }

  @Test
  fun deserializingLegacySevenFieldStreamDefaultsWarningsToEmptyList() {
    val error =
      ScreenshotError(
        status = "ERROR_RENDER_TASK",
        message = "Render failed",
        stackTrace = "trace",
        problems = listOf(RenderProblem("problem", null)),
        brokenClasses = listOf(BrokenClass("pkg.Broken", "brokenTrace")),
        missingClasses = listOf("pkg.Missing"),
      )
    val viewInfo = ComposeViewInfo(SourceLocationImpl("Sample.kt", 10, 100), PxBounds(0, 0, 100, 100), emptyList(), "Box")
    val legacy =
      LegacySevenFieldResult(
        previewId = "id",
        methodFQN = "pkg.PreviewKt.Sample",
        imagePath = "pkg/id.png",
        error = error,
        displayName = "Sample",
        viewInfos = listOf(viewInfo),
        componentTreePath = "pkg/id.json",
      )

    val bytes = serializeAsLegacyPreviewScreenshotResult(legacy)
    val deserialized = deserialize(bytes)

    assertThat(deserialized.previewId).isEqualTo("id")
    assertThat(deserialized.methodFQN).isEqualTo("pkg.PreviewKt.Sample")
    assertThat(deserialized.imagePath).isEqualTo("pkg/id.png")
    assertThat(deserialized.error).isEqualTo(error)
    assertThat(deserialized.displayName).isEqualTo("Sample")
    assertThat(deserialized.componentTreePath).isEqualTo("pkg/id.json")
    assertThat(deserialized.viewInfos).containsExactly(viewInfo)
    assertThat(deserialized.warnings).isEmpty()
  }

  @Test
  fun deserializingLegacyFourFieldStreamDefaultsMissingCollectionsToEmptyList() {
    val legacy = LegacyFourFieldResult(previewId = "id", methodFQN = "pkg.PreviewKt.Sample", imagePath = "pkg/id.png", error = null)

    val bytes = serializeAsLegacyPreviewScreenshotResult(legacy)
    val deserialized = deserialize(bytes)

    assertThat(deserialized.previewId).isEqualTo("id")
    assertThat(deserialized.displayName).isNull()
    assertThat(deserialized.componentTreePath).isNull()
    assertThat(deserialized.viewInfos).isEmpty()
    assertThat(deserialized.warnings).isEmpty()
  }

  @Test
  fun deserializingNewStreamIntoLegacySevenFieldClassIgnoresWarnings() {
    val current =
      PreviewScreenshotResult(
        previewId = "id",
        methodFQN = "pkg.PreviewKt.Sample",
        imagePath = "pkg/id.png",
        error = null,
        displayName = "Sample",
        viewInfos = emptyList(),
        componentTreePath = "pkg/id.json",
        warnings = listOf(RenderProblem("Unsupported device spec", "trace")),
      )

    val bytes = rewriteClassName(serialize(current), PreviewScreenshotResult::class.java.name, LegacySevenFieldResult::class.java.name)
    val deserialized = ByteArrayInputStream(bytes).use { bais -> ObjectInputStream(bais).use { it.readObject() as LegacySevenFieldResult } }

    assertThat(deserialized.previewId).isEqualTo("id")
    assertThat(deserialized.methodFQN).isEqualTo("pkg.PreviewKt.Sample")
    assertThat(deserialized.imagePath).isEqualTo("pkg/id.png")
    assertThat(deserialized.displayName).isEqualTo("Sample")
    assertThat(deserialized.componentTreePath).isEqualTo("pkg/id.json")
    assertThat(deserialized.viewInfos).isEmpty()
  }

  @Test
  fun preservesSevenArgJvmConstructorAndCopySignature() {
    val sevenArgCtor =
      PreviewScreenshotResult::class
        .java
        .getConstructor(
          String::class.java,
          String::class.java,
          String::class.java,
          ScreenshotError::class.java,
          String::class.java,
          List::class.java,
          String::class.java,
        )
    val instance = sevenArgCtor.newInstance("id", "pkg.Fq", "img.png", null, "Disp", emptyList<ComposeViewInfo>(), "tree.json")
    assertThat(instance.warnings).isEmpty()

    val copyMethod =
      PreviewScreenshotResult::class
        .java
        .getMethod(
          "copy",
          String::class.java,
          String::class.java,
          String::class.java,
          ScreenshotError::class.java,
          String::class.java,
          List::class.java,
          String::class.java,
        )
    assertThat(copyMethod.returnType).isEqualTo(PreviewScreenshotResult::class.java)
  }

  @Test
  fun equalsHashCodeAndToStringIncludeWarnings() {
    val base = PreviewScreenshotResult("id", "pkg.Fq", "img.png", null)
    val withWarning = PreviewScreenshotResult("id", "pkg.Fq", "img.png", null, warnings = listOf(RenderProblem("warn", null)))
    val withSameWarning = PreviewScreenshotResult("id", "pkg.Fq", "img.png", null, warnings = listOf(RenderProblem("warn", null)))

    assertThat(withWarning).isNotEqualTo(base)
    assertThat(withWarning).isEqualTo(withSameWarning)
    assertThat(withWarning.hashCode()).isEqualTo(withSameWarning.hashCode())
    assertThat(withWarning.toString()).contains("warnings=[RenderProblem(html=warn, stackTrace=null)]")
  }

  private fun serialize(obj: Serializable): ByteArray =
    ByteArrayOutputStream().use { baos ->
      ObjectOutputStream(baos).use { it.writeObject(obj) }
      baos.toByteArray()
    }

  private fun serializeAsLegacyPreviewScreenshotResult(legacy: Serializable): ByteArray =
    rewriteClassName(serialize(legacy), legacy.javaClass.name, PreviewScreenshotResult::class.java.name)

  private fun rewriteClassName(bytes: ByteArray, fromClassName: String, toClassName: String): ByteArray {
    val oldName = lengthPrefixedUtf8(fromClassName)
    val newName = lengthPrefixedUtf8(toClassName)
    val index = indexOf(bytes, oldName)
    assertThat(index).isAtLeast(0)
    return bytes.copyOfRange(0, index) + newName + bytes.copyOfRange(index + oldName.size, bytes.size)
  }

  private fun lengthPrefixedUtf8(value: String): ByteArray {
    val utf8 = value.toByteArray(Charsets.UTF_8)
    return byteArrayOf(((utf8.size ushr 8) and 0xFF).toByte(), (utf8.size and 0xFF).toByte()) + utf8
  }

  private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
    outer@ for (i in 0..(haystack.size - needle.size)) {
      for (j in needle.indices) {
        if (haystack[i + j] != needle[j]) continue@outer
      }
      return i
    }
    return -1
  }

  private fun deserialize(bytes: ByteArray): PreviewScreenshotResult =
    ByteArrayInputStream(bytes).use { bais -> ObjectInputStream(bais).use { it.readObject() as PreviewScreenshotResult } }
}
