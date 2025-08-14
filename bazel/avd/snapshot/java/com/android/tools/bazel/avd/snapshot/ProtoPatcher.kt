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

package com.android.tools.bazel.avd.snapshot

import com.google.protobuf.ByteString
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.WireFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess

/**
 * Command-line entry point for an application that patches a binary Protocol Buffers file.
 *
 * This tool replaces substrings within string fields without requiring a schema. The replacements
 * are applied sequentially in the order they are provided. For instance, a replacement chain of
 * "a" -> "b" followed by "b" -> "c" will ultimately transform all "a"s into "c"s.
 *
 * @param args Command-line arguments: <file_path> "<old1>" "<new1>" ["<old2>" "<new2>"]...
 */
fun main(args: Array<String>) {
  // Must have a file path and at least one pair of <old_string> <new_string>.
  // The number of arguments after the file path must be even.
  if (args.size < 3 || (args.size - 1) % 2 != 0) {
    System.err.println("Error: Invalid arguments.")
    System.err.println("Usage: proto-patcher.jar <file_path> \"<old1>\" \"<new1>\" [\"<old2>\" \"<new2>\"]...")
    exitProcess(1)
  }

  val targetFile = File(args[0])
  // Create a map of old_string -> new_string from the arguments.
  val replacements = args.drop(1)
    .chunked(2)
    .associate { it[0] to it[1] }

  patchProtoFile(targetFile, replacements)
}

/**
 * Replaces substrings within string fields in a binary protobuf file without a schema.
 * This function reads the target file, performs the replacements, and overwrites the file
 * with the modified content if any changes were made.
 *
 * @param targetFile The target protobuf file to modify.
 * @param replacements A map of substrings to search for and their corresponding new values.
 * @return The total number of fields in which a replacement occurred.
 * @throws FileNotFoundException If the file at the specified path does not exist.
 */
fun patchProtoFile(targetFile: File, replacements: Map<String, String>): Int {
  if (!targetFile.exists()) {
    throw FileNotFoundException("Error: File not found at '${targetFile.absolutePath}'")
  }

  val originalBytes = targetFile.readBytes()
  val result = patchProtoData(originalBytes, replacements)

  if (result.replacedCount > 0) {
    // Overwrite the original file with the modified content.
    targetFile.writeBytes(result.patchedBytes)
    println("Patched ${result.replacedCount} field(s) in '${targetFile.name}'.")
  } else {
    println("No matching strings found. File '${targetFile.name}' was not modified.")
  }

  return result.replacedCount
}

/**
 * Recursively processes a byte array representing protobuf data, patching string fields.
 *
 * The replacements are applied sequentially based on the iteration order of the provided map.
 * This allows for chained replacements, where the output of one replacement can be the input
 * for a subsequent one.
 *
 * @param inputBytes The raw bytes of the protobuf message (or nested message).
 * @param replacements A map of substrings to search for and their corresponding new values.
 * @return A [PatchResult] containing the patched byte array and the number of replacements made.
 */
private fun patchProtoData(inputBytes: ByteArray, replacements: Map<String, String>): PatchResult {
  val inputStream = CodedInputStream.newInstance(inputBytes)
  val byteArrayOutputStream = ByteArrayOutputStream(inputBytes.size)
  val outputStream = CodedOutputStream.newInstance(byteArrayOutputStream)
  var replacedCount = 0

  while (!inputStream.isAtEnd) {
    val tag = inputStream.readTag()
    outputStream.writeUInt32NoTag(tag)

    val wireType = WireFormat.getTagWireType(tag)
    when (wireType) {
      WireFormat.WIRETYPE_VARINT -> outputStream.writeInt64NoTag(inputStream.readInt64())
      WireFormat.WIRETYPE_FIXED32 -> outputStream.writeFixed32NoTag(inputStream.readFixed32())
      WireFormat.WIRETYPE_FIXED64 -> outputStream.writeFixed64NoTag(inputStream.readFixed64())

      WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
        val data = inputStream.readBytes()
        var dataToWrite = data
        var replacementsInField = 0

        try {
          // Attempt to process as a nested message first.
          val nestedResult = patchProtoData(data.toByteArray(), replacements)
          if (nestedResult.replacedCount > 0) {
            dataToWrite = ByteString.copyFrom(nestedResult.patchedBytes)
            replacementsInField = nestedResult.replacedCount
          }
        } catch (_: InvalidProtocolBufferException) {
          // It's not a valid nested message, so it could be a string. Fall back.
          if (data.isValidUtf8) {
            var potentialString = data.toString(StandardCharsets.UTF_8)
            var wasModified = false
            // Apply all replacements sequentially.
            for ((old, new) in replacements) {
              if (potentialString.contains(old)) {
                potentialString = potentialString.replace(old, new)
                wasModified = true
              }
            }

            if (wasModified) {
              dataToWrite = ByteString.copyFrom(potentialString, StandardCharsets.UTF_8)
              replacementsInField = 1 // Count this as one modified field.
            }
          }
        }

        replacedCount += replacementsInField
        outputStream.writeBytesNoTag(dataToWrite)
      }

      WireFormat.WIRETYPE_START_GROUP, WireFormat.WIRETYPE_END_GROUP -> {
        throw InvalidProtocolBufferException("Deprecated group wire types are not supported.")
      }

      else -> {
        throw InvalidProtocolBufferException("Encountered an unknown wire type: $wireType")
      }
    }
  }

  outputStream.flush()
  return PatchResult(byteArrayOutputStream.toByteArray(), replacedCount)
}

/**
 * A data class to hold the results of a patching operation.
 *
 * @property patchedBytes The resulting byte array after replacements.
 * @property replacedCount The total number of fields in which a replacement occurred.
 */
private data class PatchResult(val patchedBytes: ByteArray, val replacedCount: Int) {
  // Override equals and hashCode for proper comparison in tests, especially for the ByteArray.
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false
    other as PatchResult
    if (!patchedBytes.contentEquals(other.patchedBytes)) return false
    if (replacedCount != other.replacedCount) return false
    return true
  }

  override fun hashCode(): Int {
    var result = patchedBytes.contentHashCode()
    result = 31 * result + replacedCount
    return result
  }
}
