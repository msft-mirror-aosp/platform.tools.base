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

package com.android.tools.render

import com.android.SdkConstants
import com.android.ide.common.rendering.api.ResourceValue as ApiResourceValue
import com.android.tools.dom.ActivityAttributesSnapshot
import com.android.tools.rendering.api.RenderModelManifest
import com.android.utils.XmlUtils
import com.android.xml.AndroidManifest
import com.google.devrel.gmscore.tools.apk.arsc.ResourceFile
import com.google.devrel.gmscore.tools.apk.arsc.ResourceValue
import com.google.devrel.gmscore.tools.apk.arsc.XmlAttribute
import com.google.devrel.gmscore.tools.apk.arsc.XmlChunk
import com.google.devrel.gmscore.tools.apk.arsc.XmlStartElementChunk
import java.io.ByteArrayInputStream
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import java.util.zip.ZipFile
import org.w3c.dom.Element

/** [RenderModelManifest] for standalone rendering, providing application-level manifest metadata such as RTL support. */
class StandaloneRenderModelManifest(
  override val isRtlSupported: Boolean,
  override val applicationLabel: ApiResourceValue? = null,
  override val applicationIcon: ApiResourceValue? = null,
) : RenderModelManifest {

  override fun getActivityAttributes(activity: String): ActivityAttributesSnapshot? = null

  companion object {
    private val logger = Logger.getLogger(StandaloneRenderModelManifest::class.java.name)

    /**
     * Attempts to read and parse the manifest from the given [path].
     *
     * The [path] can be an APK/ZIP file containing `AndroidManifest.xml`, a direct path to an `AndroidManifest.xml` file, or a directory
     * containing `AndroidManifest.xml`.
     */
    fun fromPath(path: String): StandaloneRenderModelManifest? {
      val file = File(path)
      if (!file.exists()) return null
      if (file.isDirectory) {
        return fromPath(File(file, SdkConstants.FN_ANDROID_MANIFEST_XML).absolutePath)
      }
      if (file.name == SdkConstants.FN_ANDROID_MANIFEST_XML) {
        return try {
          parsePlainText(file.readBytes())
        } catch (e: Exception) {
          logger.log(Level.WARNING, "Failed to read manifest file from $path", e)
          null
        }
      }
      return fromApk(path)
    }

    /** Reads and parses compiled binary `AndroidManifest.xml` from an APK / ZIP archive at [apkPath]. */
    fun fromApk(apkPath: String): StandaloneRenderModelManifest? {
      val file = File(apkPath)
      if (!file.exists() || !file.isFile) return null
      return try {
        ZipFile(file).use { zip ->
          val entry = zip.getEntry(SdkConstants.FN_ANDROID_MANIFEST_XML) ?: return null
          val bytes = zip.getInputStream(entry).use { it.readBytes() }
          parseBinary(bytes)
        }
      } catch (e: Exception) {
        logger.log(Level.WARNING, "Failed to read ${SdkConstants.FN_ANDROID_MANIFEST_XML} from $apkPath", e)
        null
      }
    }

    /** Parses manifest content from compiled binary XML [bytes] (APK format). */
    fun parseBinary(bytes: ByteArray): StandaloneRenderModelManifest? {
      return try {
        val resourceFile = ResourceFile(bytes)
        val xmlChunk = resourceFile.chunks.firstOrNull() as? XmlChunk ?: return null
        val startElements = xmlChunk.chunks.values.filterIsInstance<XmlStartElementChunk>()
        val appChunk = startElements.firstOrNull { it.name == AndroidManifest.NODE_APPLICATION }
        if (appChunk != null) {
          val isRtlSupported =
            appChunk.attributes
              .firstOrNull {
                (it.namespace() == SdkConstants.ANDROID_URI || it.namespace().isEmpty()) &&
                  it.name() == AndroidManifest.ATTRIBUTE_SUPPORTS_RTL
              }
              ?.let { parseBooleanAttribute(it, xmlChunk) } ?: false
          return StandaloneRenderModelManifest(isRtlSupported = isRtlSupported)
        }
        val hasManifest = startElements.any { it.name == AndroidManifest.NODE_MANIFEST }
        if (hasManifest) StandaloneRenderModelManifest(isRtlSupported = false) else null
      } catch (e: Exception) {
        null
      }
    }

    // Note that this implementation currently only handles literal boolean and string values for android:supportsRtl.
    // It does not resolve resource references (e.g. @bool/is_rtl_supported), which would require a full resource
    // repository to be available during manifest parsing.
    private fun parseBooleanAttribute(attr: XmlAttribute, xmlChunk: XmlChunk): Boolean {
      val typedValue = attr.typedValue()
      return when (typedValue.type()) {
        ResourceValue.Type.INT_BOOLEAN -> typedValue.data() != 0
        ResourceValue.Type.STRING -> {
          val raw = attr.rawValue()
          if (!raw.isNullOrEmpty()) {
            raw == "true"
          } else {
            try {
              xmlChunk.getString(typedValue.data()) == "true"
            } catch (e: Exception) {
              false
            }
          }
        }
        else -> false
      }
    }

    /** Parses manifest content from plain text XML [bytes] (source format). */
    fun parsePlainText(bytes: ByteArray): StandaloneRenderModelManifest? {
      return try {
        val builder = XmlUtils.createDocumentBuilderFactory().apply { isNamespaceAware = true }.newDocumentBuilder()
        val doc = builder.parse(ByteArrayInputStream(bytes))
        val applications = doc.getElementsByTagName(AndroidManifest.NODE_APPLICATION)
        if (applications.length == 0) {
          val manifests = doc.getElementsByTagName(AndroidManifest.NODE_MANIFEST)
          return if (manifests.length > 0) StandaloneRenderModelManifest(isRtlSupported = false) else null
        }
        val appElement = applications.item(0) as? Element ?: return null
        val supportsRtlAttr =
          appElement.getAttributeNS(SdkConstants.ANDROID_URI, AndroidManifest.ATTRIBUTE_SUPPORTS_RTL).ifEmpty {
            appElement.getAttribute("android:${AndroidManifest.ATTRIBUTE_SUPPORTS_RTL}").ifEmpty {
              appElement.getAttribute(AndroidManifest.ATTRIBUTE_SUPPORTS_RTL)
            }
          }
        StandaloneRenderModelManifest(isRtlSupported = supportsRtlAttr == "true")
      } catch (e: Exception) {
        null
      }
    }
  }
}
