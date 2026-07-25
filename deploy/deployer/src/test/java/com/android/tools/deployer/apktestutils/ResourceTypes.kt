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

/**
 * Standard Android binary resource chunk types, struct sizes, data types, and attribute resource IDs.
 *
 * References:
 * - Android OS: `frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h`
 * - Android SDK: `android.R.attr`, `android.util.TypedValue`
 */
object ResourceTypes {

  const val ANDROID_URI: String = "http://schemas.android.com/apk/res/android"
  const val ANDROID_PREFIX: String = "android"

  /** Chunk Types (`ResChunk_header.type`) */
  object ChunkType {
    const val RES_NULL: Short = 0x0000
    const val RES_STRING_POOL: Short = 0x0001
    const val RES_TABLE: Short = 0x0002
    const val RES_XML: Short = 0x0003

    // XML-specific chunk types
    const val RES_XML_START_NAMESPACE: Short = 0x0100
    const val RES_XML_END_NAMESPACE: Short = 0x0101
    const val RES_XML_START_ELEMENT: Short = 0x0102
    const val RES_XML_END_ELEMENT: Short = 0x0103
    const val RES_XML_RESOURCE_MAP: Short = 0x0180
  }

  /** Struct and Header Sizes in bytes */
  object StructSize {
    const val CHUNK_HEADER: Int = 8 // sizeof(ResChunk_header)
    const val STRING_POOL_HEADER: Int = 28 // sizeof(ResStringPool_header)
    const val XML_NODE_HEADER: Int = 16 // sizeof(ResXMLTree_node)
    const val XML_ELEMENT_ATTR_EXT: Int = 20 // sizeof(ResXMLTree_attrExt)
    const val XML_END_ELEMENT_EXT: Int = 8 // sizeof(ResXMLTree_endElementExt: ns + name)
    const val XML_ATTRIBUTE: Int = 20 // sizeof(ResXMLTree_attribute)
    const val RES_VALUE: Int = 8 // sizeof(Res_value)
    const val NAMESPACE_EXT: Int = 8 // prefix + uri indices
  }

  /** Value Data Types (`Res_value.dataType`) */
  object DataType {
    const val TYPE_NULL: Byte = 0x00
    const val TYPE_STRING: Byte = 0x03
    const val TYPE_INT_DEC: Byte = 0x10
    const val TYPE_INT_BOOLEAN: Byte = 0x12
  }

  /** Standard Framework Attribute Resource IDs (`android.R.attr.*`) */
  object AndroidAttr {
    const val THEME: Int = 0x01010000
    const val LABEL: Int = 0x01010001
    const val ICON: Int = 0x01010002
    const val NAME: Int = 0x01010003
    const val ENABLED: Int = 0x0101000e
    const val EXPORTED: Int = 0x01010010
    const val DEBUGGABLE: Int = 0x010100ff
    const val TARGET_ACTIVITY: Int = 0x01010202
    const val MIN_SDK_VERSION: Int = 0x0101020c
    const val TARGET_SDK_VERSION: Int = 0x01010270
    const val VERSION_CODE: Int = 0x0101021b
    const val VERSION_NAME: Int = 0x0101021c

    val MAPPED_ATTRS: LinkedHashMap<String, Int> =
      linkedMapOf(
        "theme" to THEME,
        "label" to LABEL,
        "icon" to ICON,
        "name" to NAME,
        "enabled" to ENABLED,
        "exported" to EXPORTED,
        "debuggable" to DEBUGGABLE,
        "targetActivity" to TARGET_ACTIVITY,
        "minSdkVersion" to MIN_SDK_VERSION,
        "targetSdkVersion" to TARGET_SDK_VERSION,
        "versionCode" to VERSION_CODE,
        "versionName" to VERSION_NAME,
      )
  }
}
