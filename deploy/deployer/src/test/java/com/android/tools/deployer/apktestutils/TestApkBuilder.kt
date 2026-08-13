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
package com.android.tools.deployer.apktestutils

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Path
import java.util.Random
import java.util.zip.ZipOutputStream

/**
 * Helper builder to construct virtual APK structures in memory for testing.
 *
 * Provides a DSL-like API to define files (text, binary, random) that will be packaged into a virtual ZIP/APK archive.
 */
class TestApkBuilder {
  val entries = ArrayList<VirtualEntry>()

  companion object {
    val DEFAULT_MANIFEST_BYTES: ByteArray by lazy {
      ManifestBuilder("com.example.runtestapp")
        .apply {
          activity(".LauncherActivity", isLauncher = true)
          activity(".SecondActivity")
        }
        .buildBinaryXml()
    }
  }

  /** Adds the default binary AndroidManifest.xml (compiled AXML) to the virtual APK. */
  fun DefaultBinaryManifest() {
    entries.add(BinaryEntry("AndroidManifest.xml", DEFAULT_MANIFEST_BYTES))
  }

  /** Adds a dynamic binary AndroidManifest.xml compiled directly from [ManifestBuilder] to the virtual APK. */
  fun Manifest(packageName: String, block: ManifestBuilder.() -> Unit) {
    val bytes = ManifestBuilder(packageName).apply(block).buildBinaryXml()
    entries.add(BinaryEntry("AndroidManifest.xml", bytes))
  }

  /** Adds a text file entry to the virtual APK from a supplier. */
  fun TextFile(name: String, content: () -> String) {
    entries.add(TextEntry(name, content()))
  }

  /** Adds a text file entry to the virtual APK with explicit string content. */
  fun TextFile(name: String, content: String) {
    entries.add(TextEntry(name, content))
  }

  /** Adds a binary file entry with pseudo-random content of random length. */
  fun RandomBinary(name: String, content: () -> Long) {
    val seed = content()
    val random = Random(seed)
    val length = 50 + random.nextInt(500)
    val bytes = ByteArray(length)
    random.nextBytes(bytes)
    entries.add(BinaryEntry(name, bytes))
  }

  /** Adds a binary file entry with pseudo-random content of fixed length. */
  fun RandomBinary(name: String, length: Int, seed: Long) {
    val random = Random(seed)
    val bytes = ByteArray(length)
    random.nextBytes(bytes)
    entries.add(BinaryEntry(name, bytes))
  }

  /** Adds a binary file entry with explicit byte content. */
  fun BinaryFile(name: String, bytes: ByteArray) {
    entries.add(BinaryEntry(name, bytes))
  }

  /** Adds a binary file entry with explicit byte content and custom extra fields. */
  fun BinaryFileWithExtra(name: String, bytes: ByteArray, extra: ByteArray) {
    entries.add(ExtraFieldsBinaryEntry(name, bytes, extra))
  }
}

/**
 * DSL entry point to construct a virtual [TestApkBuilder].
 *
 * Example usage:
 * ```
 * val apk = Apk {
 *   Manifest("com.example.app") {
 *     activity(".MainActivity", isLauncher = true)
 *   }
 *   BinaryFile("classes.dex", dexBytes)
 * }
 * ```
 */
fun Apk(init: TestApkBuilder.() -> Unit): TestApkBuilder {
  val builder = TestApkBuilder()
  builder.init()
  return builder
}

/** Assembles the virtual entries, returning the fully serialized ZIP/APK byte array. */
fun TestApkBuilder.buildZipBytes(): ByteArray {
  val finalEntries = ArrayList(entries)
  // If no AndroidManifest.xml was added, automatically include the default binary manifest
  if (finalEntries.none { it.name == "AndroidManifest.xml" }) {
    finalEntries.add(0, BinaryEntry("AndroidManifest.xml", TestApkBuilder.DEFAULT_MANIFEST_BYTES))
  }
  val baos = ByteArrayOutputStream()
  ZipOutputStream(baos).use { zos ->
    for (entry in finalEntries) {
      entry.writeTo(zos)
    }
  }
  return baos.toByteArray()
}

/** Assembles the virtual entries, returning a [ByteBuffer]. */
fun TestApkBuilder.build(): ByteBuffer {
  return ByteBuffer.wrap(buildZipBytes())
}

/** Writes the serialized ZIP/APK bytes to the target [File]. */
fun TestApkBuilder.writeTo(file: File) {
  file.outputStream().use { os -> os.write(buildZipBytes()) }
}

/** Writes the serialized ZIP/APK bytes to the target [Path]. */
fun TestApkBuilder.writeTo(path: Path) {
  writeTo(path.toFile())
}

/** Writes the serialized ZIP/APK bytes to the target [SeekableByteChannel]. */
fun TestApkBuilder.writeTo(channel: SeekableByteChannel) {
  val bytes = buildZipBytes()
  channel.write(ByteBuffer.wrap(bytes))
}
