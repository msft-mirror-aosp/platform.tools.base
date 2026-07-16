/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.tools.lint

import com.android.ide.common.blame.SourcePosition
import com.android.ide.common.rendering.api.ArrayResourceValue
import com.android.ide.common.rendering.api.ArrayResourceValueImpl
import com.android.ide.common.rendering.api.AttrResourceValue
import com.android.ide.common.rendering.api.AttrResourceValueImpl
import com.android.ide.common.rendering.api.AttributeFormat
import com.android.ide.common.rendering.api.DensityBasedResourceValue
import com.android.ide.common.rendering.api.DensityBasedResourceValueImpl
import com.android.ide.common.rendering.api.PluralsResourceValue
import com.android.ide.common.rendering.api.PluralsResourceValueImpl
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceValue
import com.android.ide.common.rendering.api.ResourceValueImpl
import com.android.ide.common.rendering.api.StyleItemResourceValueImpl
import com.android.ide.common.rendering.api.StyleResourceValue
import com.android.ide.common.rendering.api.StyleResourceValueImpl
import com.android.ide.common.rendering.api.StyleableResourceValue
import com.android.ide.common.rendering.api.StyleableResourceValueImpl
import com.android.ide.common.rendering.api.TextResourceValueImpl
import com.android.ide.common.resources.ResourceFile
import com.android.ide.common.resources.ResourceItem
import com.android.ide.common.resources.ResourceMergerItem
import com.android.ide.common.resources.configuration.FolderConfiguration
import com.android.ide.common.util.PathString
import com.android.resources.Density
import com.android.resources.ResourceType
import com.android.tools.lint.client.api.LintClient
import com.android.tools.lint.detector.api.DefaultPosition
import com.android.tools.lint.detector.api.Issue.IgnoredIdProvider
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.Location.LocationAware
import com.android.tools.lint.detector.api.Project
import com.android.tools.lint.model.PathVariables
import com.android.utils.Base128InputStream
import com.android.utils.Base128InputStream.StreamFormatException
import com.android.utils.Base128OutputStream
import com.google.common.collect.BiMap
import com.google.common.collect.HashBiMap
import com.google.common.collect.ListMultimap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.EnumMap

/**
 * Persists a [LintResourceRepository] (and later reconstitutes it), intended for caching of resources for projects, libraries and
 * frameworks.
 *
 * The format is a compact binary stream built on the LEB128 varint encoding provided by [Base128OutputStream] and [Base128InputStream] (the
 * same primitives used by the resource repository caches in `com.android.resources.base`). Unlike those caches, this format also records
 * source positions, `tools:ignore` ids and raw XML values, which lint requires.
 */
object LintResourcePersistence {
  /** File starts with these bytes; anything else is rejected (e.g. files from the older text-based format). */
  private val MAGIC = byteArrayOf('L'.code.toByte(), 'R'.code.toByte(), 'R'.code.toByte())

  /** Bump when making incompatible changes to the format (and consider renaming the cache directories; see [LintResourceRepository]). */
  private const val FORMAT_VERSION = 1

  /** Per-item flags */
  private enum class ItemFlag {
    FileBased,
    HasPosition,
    HasIgnoredIds,
    HasText,
    HasRawSource,
    HasArguments;

    val asFlag: Int = 1 shl ordinal

    companion object {
      fun of(vararg entries: Pair<ItemFlag, Boolean>): Int =
        entries.fold(0) { flags, (key, value) -> if (value) flags or key.asFlag else flags }
    }
  }

  private operator fun Int.contains(key: ItemFlag): Boolean = this and key.asFlag != 0

  /**
   * Serializes the lint resource repository; can be deserialized with [deserialize]. The [pathVariables] help write relative paths. If
   * [sort] is true, elements will be sorted by name; this is used in tests to ensure stable output.
   */
  fun serialize(repository: LintResourceRepository, pathVariables: PathVariables, root: File?, sort: Boolean = false): ByteArray {
    val typeToMap = repository.typeToMap
    if (typeToMap.isEmpty()) {
      return ByteArray(0)
    }

    val namespace = repository.namespace
    val framework = namespace == ResourceNamespace.ANDROID
    val byteStream = ByteArrayOutputStream(1 shl (if (framework) 20 else 10))

    fun <X> Collection<X>.maybeSortedBy(key: (X) -> Comparable<*>) = if (sort) sortedWith(compareBy(key)) else this

    Base128OutputStream(byteStream).use { out ->
      MAGIC.forEach(out::writeByte)
      out.writeInt(FORMAT_VERSION)
      out.writeString(namespace.xmlNamespaceUri)
      out.writeString(repository.libraryName)

      val fileMap: BiMap<PathString, Int> = HashBiMap.create(if (framework) 11000 else 100)
      var fileCount = 0
      for (multimap in typeToMap.values) {
        for (item in multimap.values()) {
          fileMap.computeIfAbsent(item.source) { fileCount++ }
        }
      }

      val rootPath = root?.path
      val indexToFile = fileMap.inverse()
      out.writeInt(fileCount)
      repeat(fileCount) { i ->
        val source = indexToFile[i] ?: error("Missing file for index $i")
        out.writeString(pathVariables.toPathString(source.rawPath, rootPath, unix = true))
      }

      val entries = typeToMap.entries.maybeSortedBy { it.key }

      out.writeInt(entries.count { !it.value.isEmpty })
      for ((type, map) in entries) {
        if (map.isEmpty) continue
        out.writeString(type.getName())

        val values = map.values().maybeSortedBy { it.name }
        out.writeInt(values.size)
        for (item in values) {
          writeItem(out, item, fileMap)
        }
      }
    }

    return byteStream.toByteArray()
  }

  private fun writeItem(out: Base128OutputStream, item: ResourceItem, fileMap: Map<PathString, Int>) {
    val position: SourcePosition?
    val ignoredIds: String
    val fileBased = item.isFileBased

    when {
      !fileBased && item is LintResourceItem -> {
        position = item.position.takeUnless { it == SourcePosition.UNKNOWN }
        ignoredIds = item.getIgnoredIds()
      }
      !fileBased && item is LocationAware -> {
        if (!LintClient.isUnitTest) {
          // This path is only used from tests (where we serialize a deserialized repository;
          // we don't do that in the product, but in the tests we do it to very efficiently
          // compare all aspects of the repository being identical
          throw IllegalStateException()
        }
        val location = item.getLocation()
        val start = location.start
        val end = location.end
        position =
          if (start != null || end != null) {
            SourcePosition(
              start?.line ?: -1,
              start?.column ?: -1,
              start?.offset ?: -1,
              end?.line ?: (start?.line ?: -1),
              end?.column ?: (start?.column ?: -1),
              end?.offset ?: (start?.offset ?: -1),
            )
          } else null
        ignoredIds = if (item is IgnoredIdProvider) item.getIgnoredIds() else ""
      }
      else -> {
        position = null
        ignoredIds = ""
      }
    }

    // Compute the value content the same way the resource value will be reconstructed
    // in [LintDeserializedResourceItem.createResourceValue].
    var text: String? = null
    var rawSource: String? = null
    var arguments: ByteArray? = null
    val resourceValue = if (fileBased) null else item.resourceValue
    if (resourceValue != null) {
      val type = item.type
      when {
        type == ResourceType.ARRAY && resourceValue is ArrayResourceValue -> {
          arguments = encodeArguments { args ->
            args.writeInt(resourceValue.elementCount)
            repeat(resourceValue.elementCount) { i -> args.writeString(resourceValue.getElement(i)) }
          }
        }
        type == ResourceType.PLURALS && resourceValue is PluralsResourceValue -> {
          arguments = encodeArguments { args ->
            args.writeInt(resourceValue.pluralsCount)
            repeat(resourceValue.pluralsCount) { i ->
              args.writeString(resourceValue.getQuantity(i))
              args.writeString(resourceValue.getValue(i))
            }
          }
        }
        type == ResourceType.STYLE && resourceValue is StyleResourceValue -> {
          arguments = encodeArguments { args ->
            val parentStyleName = resourceValue.parentStyleName
            when {
              // Need to distinguish between empty (no parent) and null
              // (can inherit from implied parent, e.g. Foo.Bar will
              // inherit from "Foo" is parent is not set, but
              // won't if parent=""
              parentStyleName == null -> args.writeInt(0)
              parentStyleName.isEmpty() -> args.writeInt(1)
              else -> {
                args.writeInt(2)
                args.writeString(parentStyleName)
              }
            }
            val definedItems = resourceValue.definedItems
            args.writeInt(definedItems.size)
            for (styleItem in definedItems) {
              args.writeString(styleItem.attrName)
              // or null?
              args.writeString(styleItem.value ?: "")
            }
          }
        }
        type == ResourceType.ATTR && resourceValue is AttrResourceValueImpl -> {
          arguments = encodeArguments { args -> writeAttrValue(args, resourceValue) }
        }
        type == ResourceType.STYLEABLE && resourceValue is StyleableResourceValue -> {
          arguments = encodeArguments { args ->
            val attributes = resourceValue.allAttributes
            args.writeInt(attributes.size)
            for (attribute in attributes) {
              args.writeString(attribute.name)
              writeAttrValue(args, attribute)
            }
          }
        }
        DensityBasedResourceValue.isDensityBasedResourceType(type) && resourceValue is DensityBasedResourceValue -> {
          arguments = encodeArguments { args -> args.writeString(resourceValue.resourceDensity.resourceValue) }
        }
        else -> {
          text = resourceValue.value
          if (text != null) {
            val raw: String? = resourceValue.rawXmlValue
            if (raw != null && raw != text) {
              rawSource = raw
            }
          }
        }
      }
    }

    val flags =
      ItemFlag.of(
        ItemFlag.FileBased to fileBased,
        ItemFlag.HasPosition to (position != null),
        ItemFlag.HasIgnoredIds to ignoredIds.isNotEmpty(),
        ItemFlag.HasText to (text != null),
        ItemFlag.HasRawSource to (rawSource != null),
        ItemFlag.HasArguments to (arguments != null),
      )

    out.writeString(item.name)
    out.writeInt(fileMap[item.source] ?: error("Missing file index for ${item.source}"))
    out.writeInt(flags)
    if (position != null) {
      // Written as value + 1 so that the common "unknown" value -1 encodes as a single 0 byte.
      // Unlike the previous text-based format, these are full-width varints; large files
      // with offsets above 64K are persisted without truncation (b/533056758).
      out.writeInt(position.startLine + 1)
      out.writeInt(position.startColumn + 1)
      out.writeInt(position.startOffset + 1)
      out.writeInt(position.endLine + 1)
      out.writeInt(position.endColumn + 1)
      out.writeInt(position.endOffset + 1)
    }
    if (ignoredIds.isNotEmpty()) out.writeString(ignoredIds)
    if (text != null) out.writeString(text)
    if (rawSource != null) out.writeString(rawSource)
    if (arguments != null) out.writeBytes(arguments)
  }

  /** Formats and enumeration/flag values for an attr; shared between attr and styleable payloads. */
  private fun writeAttrValue(args: Base128OutputStream, attr: AttrResourceValue) {
    // Descriptions, group names etc are only supported for the framework
    val formats = attr.formats
    args.writeString(if (formats.isEmpty()) null else formats.joinToString("|") { it.getName() })
    val attributeValues = attr.attributeValues
    args.writeInt(attributeValues.size)
    for ((key, value) in attributeValues) {
      args.writeString(key)
      if (value == null) {
        args.writeBoolean(false)
      } else {
        args.writeBoolean(true)
        args.writeInt(value)
      }
    }
  }

  private inline fun encodeArguments(block: (Base128OutputStream) -> Unit): ByteArray {
    val bytes = ByteArrayOutputStream(64)
    Base128OutputStream(bytes).use { block(it) }
    return bytes.toByteArray()
  }

  /** Deserializes a lint resource repository created by [serialize] */
  fun deserialize(
    bytes: ByteArray,
    pathVariables: PathVariables,
    root: File? = null,
    project: Project? = null,
    allowMissingPathVariable: Boolean = false,
  ): LintResourceRepository {
    if (bytes.isEmpty()) {
      return LintResourceRepository.Companion.EmptyRepository
    }

    Base128InputStream(ByteArrayInputStream(bytes)).use { input ->
      if (!input.validateContents(MAGIC)) {
        throw StreamFormatException("Not a lint resource repository (missing file header)")
      }
      val version = input.readInt()
      if (version != FORMAT_VERSION) {
        throw StreamFormatException("Unsupported lint resource repository version $version (expected $FORMAT_VERSION)")
      }
      input.setStringCache(HashMap()) // enables string instance sharing

      val map: MutableMap<ResourceType, ListMultimap<String, ResourceItem>> = EnumMap(ResourceType::class.java)

      val namespaceUri = input.readString() ?: throw StreamFormatException("Missing namespace")
      val namespace = ResourceNamespace.fromNamespaceUri(namespaceUri) ?: ResourceNamespace.RES_AUTO
      val libraryName = input.readString()

      val fileCount = input.readInt()
      val fileList =
        List(fileCount) { i ->
          val path = input.readString() ?: throw StreamFormatException("Missing path for file $i")
          pathVariables.fromPathString(path, root, allowMissingPathVariable)
        }

      val parentConfigMap = HashMap<String, FolderConfiguration>(fileCount / 4 + 1)
      val folderConfigMap = HashMap<File, FolderConfiguration>(fileCount * 2)
      for (file in fileList) {
        val folderName = file.parentFile?.name ?: continue
        val config =
          parentConfigMap[folderName]
            ?: FolderConfiguration.getConfigForFolder(folderName)?.also {
              it.normalizeByAddingImpliedVersionQualifier()
              parentConfigMap[folderName] = it
            }
            ?: continue
        folderConfigMap[file] = config
      }

      // Map of the values added from each resource file
      val valueItems = HashMap<File, MutableList<LintDeserializedResourceItem>>()

      val typeCount = input.readInt()
      repeat(typeCount) {
        val typeName = input.readString() ?: throw StreamFormatException("Missing resource type name")
        val type = ResourceType.fromClassName(typeName) ?: throw StreamFormatException("Unknown resource type $typeName")
        val itemCount = input.readInt()
        repeat(itemCount) {
          val name = input.readString() ?: throw StreamFormatException("Missing resource name")
          val fileIndex = input.readInt()
          val flags = input.readInt()
          val file = fileList[fileIndex]
          val config = folderConfigMap[file] ?: throw StreamFormatException("Missing folder configuration for $file")

          val position =
            if (ItemFlag.HasPosition in flags) {
              SourcePosition(
                input.readInt() - 1,
                input.readInt() - 1,
                input.readInt() - 1,
                input.readInt() - 1,
                input.readInt() - 1,
                input.readInt() - 1,
              )
            } else null
          val ignoredIds = if (ItemFlag.HasIgnoredIds in flags) input.readString() ?: "" else ""
          val text = if (ItemFlag.HasText in flags) input.readString() ?: "" else null
          val rawSource = if (ItemFlag.HasRawSource in flags) input.readString() else null
          val arguments = if (ItemFlag.HasArguments in flags) input.readBytes() else null

          if (ItemFlag.FileBased in flags) {
            val item = LintResourceItem(file, name, namespace, type, null, false, libraryName, config, true, ignoredIds, null)
            LintResourceRepository.recordItem(map, type, name, item)

            // As a side effect sets item.sourceFile
            ResourceFile(file, item, config)
          } else {
            val item =
              LintDeserializedResourceItem(
                file,
                name,
                namespace,
                type,
                config,
                false,
                rawSource,
                text,
                arguments,
                libraryName,
                ignoredIds,
                position,
              )
            LintResourceRepository.recordItem(map, type, name, item)
            valueItems.getOrPut(file, ::ArrayList).add(item)
          }
        }
      }

      // Initialize resource files for value resources; we couldn't do that
      // during initialization since we need to pass in all items for each
      // file at the same time
      for ((file, items) in valueItems) {
        val config = folderConfigMap[file]!!
        val itemList: List<LintDeserializedResourceItem> = items
        // Constructor has side effect of recording itself on each item
        ResourceFile(file, itemList, config)
      }

      return LintResourceRepository(project, map, namespace, libraryName)
    }
  }

  /** Serializes a lint resource repository. */
  fun serialize(repository: LintResourceRepository, pathVariables: PathVariables): ByteArray {
    return serialize(repository, pathVariables, null)
  }

  private class LintDeserializedResourceItem(
    private val sourceFile: File,
    name: String,
    namespace: ResourceNamespace,
    type: ResourceType,
    private val config: FolderConfiguration,
    private val fileBased: Boolean,
    private val rawSource: String?,
    /** Source text. */
    private val text: String?,
    /**
     * Additional serialized data, used to deserialize a specific resource value. This is done lazily since lint almost never consults
     * resource values for anything other than strings and dimensions (and only usually when some other potentially triggering issue is
     * there.)
     */
    private val arguments: ByteArray?,
    private val library: String?,
    private val ignoredIds: String,
    private val position: SourcePosition?,
  ) : ResourceMergerItem(name, namespace, type, null, false, null), LocationAware, IgnoredIdProvider {
    override fun getConfiguration(): FolderConfiguration {
      return config
    }

    override fun getFile(): File {
      return sourceFile
    }

    override fun getSource(): PathString {
      return PathString(sourceFile)
    }

    override fun getValueText(): String {
      return text ?: ""
    }

    override fun getResourceValue(): ResourceValue {
      return mResourceValue ?: createResourceValue().also { mResourceValue = it }
    }

    override fun getLibraryName(): String? {
      return library
    }

    private fun createResourceValue(): ResourceValue {
      // Lazily construct resource value from value data
      return if (arguments == null) {
        when {
          type == ResourceType.ATTR -> {
            AttrResourceValueImpl(namespace, name, library)
          }
          type == ResourceType.STRING && text != null -> {
            TextResourceValueImpl(namespace, name, text, rawSource, library)
          }
          type == ResourceType.ARRAY -> {
            ArrayResourceValueImpl(namespace, name, library)
          }
          type == ResourceType.STYLEABLE -> {
            StyleableResourceValueImpl(namespace, name, null, library)
          }
          type == ResourceType.STYLE -> {
            StyleItemResourceValueImpl(namespace, name, null, library)
          }
          text != null -> {
            ResourceValueImpl(namespace, type, name, text, library)
          }
          isFileBased -> {
            ResourceValueImpl(namespace, type, name, sourceFile.path, library)
          }
          else -> {
            ResourceValueImpl(namespace, type, name, library)
          }
        }
      } else {
        assert(arguments.isNotEmpty())
        Base128InputStream(ByteArrayInputStream(arguments)).use { reader ->
          when {
            type == ResourceType.ARRAY ->
              ArrayResourceValueImpl(namespace, name, library).apply { repeat(reader.readInt()) { addElement(reader.readString() ?: "") } }
            type == ResourceType.PLURALS -> {
              PluralsResourceValueImpl(namespace, name, text, library).apply {
                repeat(reader.readInt()) {
                  val quantity = reader.readString() ?: ""
                  val value = reader.readString() ?: ""
                  addPlural(quantity, value)
                }
              }
            }
            type == ResourceType.STYLE -> {
              val parent =
                when (reader.readInt()) {
                  0 -> null
                  1 -> ""
                  else -> reader.readString()
                }
              StyleResourceValueImpl(namespace, name, parent, library).apply {
                repeat(reader.readInt()) {
                  val itemName = reader.readString() ?: ""
                  val value = reader.readString() ?: ""
                  val item = StyleItemResourceValueImpl(namespace, itemName, value, library)
                  addItem(item)
                }
              }
            }
            type == ResourceType.STYLEABLE ->
              StyleableResourceValueImpl(namespace, name, null, library).apply {
                repeat(reader.readInt()) {
                  val attrName = reader.readString() ?: ""
                  val attr = AttrResourceValueImpl(namespace, attrName, library)
                  addValue(attr)
                  readAttrValue(reader, attr, defaultToReference = false)
                }
              }
            type == ResourceType.ATTR ->
              AttrResourceValueImpl(namespace, name, library).also { readAttrValue(reader, it, defaultToReference = true) }
            DensityBasedResourceValue.isDensityBasedResourceType(type) -> {
              val density = Density.getEnum(reader.readString())!!
              // value path or null?
              DensityBasedResourceValueImpl(namespace, type, name, null, density, library)
            }
            else -> ResourceValueImpl(namespace, type, name, file.path, library)
          }
        }
      }
    }

    private fun readAttrValue(reader: Base128InputStream, attr: AttrResourceValueImpl, defaultToReference: Boolean) {
      val format = reader.readString()
      if (format.isNullOrEmpty()) {
        if (defaultToReference) {
          // Only specified format, not arguments
          attr.setFormats(listOf(AttributeFormat.REFERENCE))
        }
      } else {
        attr.setFormats(AttributeFormat.parse(format))
      }
      repeat(reader.readInt()) {
        val valueName = reader.readString() ?: ""
        val value = if (reader.readBoolean()) reader.readInt() else null
        attr.addValue(valueName, value, null)
      }
    }

    override fun isFileBased(): Boolean {
      return fileBased
    }

    override fun toString(): String {
      val path = file.path
      val parentPath: String? = file.parentFile?.parentFile?.path
      return if (parentPath != null) {
        "${this::class.java.simpleName}(${path.substring(parentPath.length + 1)})"
      } else {
        super.toString()
      }
    }

    override fun getLocation(): Location {
      val position = position
      return if (position != null) {
        Location.create(
          file,
          DefaultPosition(position.startLine, position.startColumn, position.startOffset),
          DefaultPosition(position.endLine, position.endColumn, position.endOffset),
        )
      } else {
        Location.create(file)
      }
    }

    override fun getIgnoredIds(): String {
      return ignoredIds
    }
  }
}
