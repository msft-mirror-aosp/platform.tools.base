/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.manifmerger

import com.android.ide.common.blame.SourceFile
import com.android.manifmerger.PlaceholderHandler.KeyBasedValueResolver
import com.google.common.io.Files
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import javax.xml.parsers.ParserConfigurationException
import org.xml.sax.SAXException

object TestUtils {

  private val NULL_RESOLVER =
    object : KeyResolver<String> {
      override fun resolve(key: String): String? = null

      override fun getKeys(): List<String> = emptyList()
    }

  private val NO_PROPERTY_RESOLVER = KeyBasedValueResolver<ManifestSystemProperty> { null }

  @JvmStatic
  fun sourceFile(sourceClass: Class<*>, location: String): SourceFile {
    return SourceFile(sourceClass.simpleName + "#" + location)
  }

  @JvmStatic
  @Throws(IOException::class, SAXException::class, ParserConfigurationException::class)
  fun xmlDocumentFromString(location: SourceFile, input: String, model: ManifestModel): XmlDocument {
    return XmlLoader.load(
      NULL_RESOLVER,
      NO_PROPERTY_RESOLVER,
      location,
      input,
      XmlDocument.Type.MAIN,
      null, /* mainManifestPackageName */
      model,
      MergingReport.Builder(com.android.testutils.MockLog()),
    )
  }

  @JvmStatic
  @Throws(IOException::class, SAXException::class, ParserConfigurationException::class)
  fun xmlLibraryFromString(location: SourceFile, input: String, model: ManifestModel): XmlDocument {
    return XmlLoader.load(
      NULL_RESOLVER,
      NO_PROPERTY_RESOLVER,
      location,
      input,
      XmlDocument.Type.LIBRARY,
      null, /* mainManifestPackageName */
      model,
      MergingReport.Builder(com.android.testutils.MockLog()),
    )
  }

  @JvmStatic
  @Throws(IOException::class, SAXException::class, ParserConfigurationException::class)
  fun xmlDocumentFromString(
    location: SourceFile,
    input: String,
    type: XmlDocument.Type,
    mainManifestPackageName: String?,
    model: ManifestModel,
  ): XmlDocument {
    return XmlLoader.load(
      NULL_RESOLVER,
      NO_PROPERTY_RESOLVER,
      location,
      input,
      type,
      mainManifestPackageName,
      model,
      MergingReport.Builder(com.android.testutils.MockLog()),
    )
  }

  @JvmStatic
  @Throws(IOException::class, SAXException::class, ParserConfigurationException::class)
  fun xmlDocumentFromString(selectors: KeyResolver<String>, location: SourceFile, input: String, model: ManifestModel): XmlDocument {
    return XmlLoader.load(
      selectors,
      NO_PROPERTY_RESOLVER,
      location,
      input,
      XmlDocument.Type.LIBRARY,
      null, /* mainManifestPackageName */
      model,
      MergingReport.Builder(com.android.testutils.MockLog()),
    )
  }

  /** Utility method to save a [String] XML into a file. */
  @JvmStatic
  @Throws(IOException::class)
  fun inputAsFile(testName: String, input: String): File {
    val tmpFile = File.createTempFile(testName, ".xml")
    tmpFile.deleteOnExit()
    Files.asCharSink(tmpFile, StandardCharsets.UTF_8).write(input)
    return tmpFile
  }
}
