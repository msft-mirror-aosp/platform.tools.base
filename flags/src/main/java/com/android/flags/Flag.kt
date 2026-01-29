/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.flags

import java.util.Locale

/**
 * A flag is a setting with an unique ID and a default value. Flags are often used to gate features (e.g. start with the feature disabled or
 * enabled) or initialize a feature with some default value (e.g. how much memory to initialize a system with, what mode a system should use
 * by default).
 *
 * A flag final value, queried via [get], is not necessarily the same as its [default] value. [Flag] are linked to a [Flags] instance which
 * can provide an "override" over the default value.
 */
sealed class Flag<T>(
  /** Returns the [FlagGroup] that this flag is part of. */
  val group: FlagGroup,
  name: String,
  /** Returns a user-friendly display name for this flag. */
  val displayName: String,
  /** Returns a user-friendly description for what feature this flag gates. */
  val description: String,
  val default: FlagDefault<T>,
  private val valueConverter: ValueConverter<T>,
  private val examples: List<T> = emptyList(),
) {

  /** Returns a unique ID for this flag. It will be composed of the group's name prefixed to this flag's name. */
  val id: String = group.name + "." + name
  private var _defaultValue: T? = null
  private val defaultValue
    get(): T = _defaultValue ?: default.get().also { _defaultValue = it }

  init {
    group.flags.register(this)
  }

  /** Verifies that this flag has valid information */
  fun validate() {
    group.validate()
    verifyRoundTrip("Default", defaultValue, valueConverter)
    for (t in examples) {
      verifyRoundTrip("Example", defaultValue, valueConverter)
    }
    verifyFlagIdFormat(id)
    verifyDisplayTextFormat(displayName)
    verifyDisplayTextFormat(description)
  }

  /** Returns the value of this flag. */
  fun get(): T {
    val strValue = group.flags.getValue(this) ?: return defaultValue

    return try {
      valueConverter.deserialize(strValue)
    } catch (_: Exception) {
      defaultValue
    }
  }

  /**
   * Override the value of this flag at runtime.
   *
   * This method does not modify this flag definition directly, but instead adds an entry into its parent [Flags.getUserOverrides]
   * collection.
   */
  fun override(overrideValue: T) {
    group.flags.userOverrides.put(this, valueConverter.serialize(overrideValue))
  }

  /** Clear any override previously set by [.override]. */
  fun clearOverride() {
    group.flags.userOverrides.remove(this)
  }

  /**
   * Whether the flag's value has been overridden manually by the user.
   *
   * This is generally done in the Flag UI.
   */
  val isUserOverridden: Boolean
    get() = group.flags.userOverrides[this] != null

  /**
   * Simple interface for converting a value to and from a String. This is useful as all flags are really strings underneath, although it's
   * convenient to expose, say, boolean flags to users instead.
   */
  protected interface ValueConverter<T> {
    fun serialize(value: T): String

    fun deserialize(strValue: String): T
  }

  companion object {
    /**
     * Verify that a flag's ID is correctly formatted, i.e. consisting of only lower-case letters, numbers, and periods. Furthermore, the
     * first character of an ID must be a letter and cannot end with one.
     */
    @JvmStatic
    fun verifyFlagIdFormat(id: String) {
      require(id.matches("[a-z][a-z0-9]*(\\.[a-z0-9]+)*".toRegex())) { "Invalid id: $id" }
    }

    /** Verify that display text is correctly formatted. */
    @JvmStatic
    fun verifyDisplayTextFormat(name: String) {
      require(name.isNotEmpty() && name[0] != ' ' && name[name.length - 1] != ' ') { "Invalid name: $name" }
    }

    private fun <T> verifyRoundTrip(descriptiveName: String, value: T, converter: ValueConverter<T>) {
      val serialized =
        try {
          converter.serialize(value)
        } catch (e: Exception) {
          throw IllegalArgumentException("$descriptiveName value '$value' cannot be serialized", e)
        }
      val deserialized =
        try {
          converter.deserialize(serialized)
        } catch (e: Exception) {
          throw IllegalArgumentException("$descriptiveName value '$value' cannot be deserialized.")
        }

      require(deserialized == value) {
        "Deserialized value '$deserialized' does not match original ${descriptiveName.lowercase(Locale.US)} value '$value'."
      }
    }
  }
}

class MendelFlag(
  group: FlagGroup,
  name: String,
  val mendelId: Int,
  displayName: String,
  description: String,
  defaultValueProvider: FlagDefault<Boolean>,
) : Flag<Boolean>(group, name, displayName, description, defaultValueProvider, Converter) {
  constructor(
    group: FlagGroup,
    name: String,
    mendelId: Int,
    displayName: String,
    description: String,
    defaultValue: Boolean,
  ) : this(group, name, mendelId, displayName, description, StaticFlagDefault<Boolean>(defaultValue))

  object Converter : ValueConverter<Boolean> {
    override fun serialize(value: Boolean) = value.toString()

    override fun deserialize(strValue: String) = strValue.toBoolean()
  }
}

object DEFAULT_BOOLEAN_FLAG_VALUE : FlagDefault<Boolean>("BooleanFlag always default to false") {
  override fun get(): Boolean = false
}

/**
 * a flag with a boolean value.
 *
 * Unlike other flags this does not receive a default value. The default value is automatically false.
 *
 * To change the default value, you need to add the flag id (group.name) to tools/adt/idea/android-common/flags/resources/feature_flags.txt.
 */
class BooleanFlag(group: FlagGroup, name: String, displayName: String, description: String) :
  Flag<Boolean>(group, name, displayName, description, DEFAULT_BOOLEAN_FLAG_VALUE, Converter) {

  object Converter : ValueConverter<Boolean> {
    override fun serialize(value: Boolean) = value.toString()

    override fun deserialize(strValue: String) = strValue.toBoolean()
  }
}

class IntFlag constructor(group: FlagGroup, name: String, displayName: String, description: String, default: FlagDefault<Int>) :
  Flag<Int>(group, name, displayName, description, default, Converter) {

  constructor(
    group: FlagGroup,
    name: String,
    displayName: String,
    description: String,
    defaultValue: Int,
  ) : this(group, name, displayName, description, StaticFlagDefault(defaultValue))

  object Converter : ValueConverter<Int> {
    override fun serialize(value: Int) = value.toString()

    override fun deserialize(strValue: String) = strValue.toInt()
  }
}

class LongFlag(group: FlagGroup, name: String, displayName: String, description: String, defaultValueProvider: FlagDefault<Long>) :
  Flag<Long>(group, name, displayName, description, defaultValueProvider, Converter) {

  constructor(
    group: FlagGroup,
    name: String,
    displayName: String,
    description: String,
    defaultValue: Long,
  ) : this(group, name, displayName, description, StaticFlagDefault(defaultValue))

  object Converter : ValueConverter<Long> {
    override fun serialize(value: Long) = value.toString()

    override fun deserialize(strValue: String) = strValue.toLong()
  }
}

class StringFlag(group: FlagGroup, name: String, displayName: String, description: String, defaultValueSupplier: FlagDefault<String>) :
  Flag<String>(group, name, displayName, description, defaultValueSupplier, Converter) {

  constructor(
    group: FlagGroup,
    name: String,
    displayName: String,
    description: String,
    defaultValue: String,
  ) : this(group, name, displayName, description, StaticFlagDefault(defaultValue))

  object Converter : ValueConverter<String> {
    override fun serialize(value: String) = value

    override fun deserialize(strValue: String) = strValue
  }
}

class EnumFlag<T : Enum<T>>(
  group: FlagGroup,
  name: String,
  displayName: String,
  description: String,
  defaultValueSupplier: FlagDefault<T>,
  valueClass: Class<T>,
) : Flag<T>(group, name, displayName, description, defaultValueSupplier, EnumConverter(valueClass)) {

  constructor(
    group: FlagGroup,
    name: String,
    displayName: String,
    description: String,
    defaultValue: T,
  ) : this(group, name, displayName, description, StaticFlagDefault(defaultValue), defaultValue.javaClass)

  /**
   * Creates a [ValueConverter] for the given enum class. Values are stored using their names, to make it easier to override them using JVM
   * properties (lower-case names are also recognized).
   *
   * @see Enum#name()
   */
  private class EnumConverter<T : Enum<T>>(private val enumClass: Class<T>) : ValueConverter<T> {
    override fun serialize(value: T) = value.name

    override fun deserialize(strValue: String): T = java.lang.Enum.valueOf(enumClass, strValue.uppercase(Locale.US))
  }
}

abstract class CustomTypeFlag<T>
protected constructor(
  type: Class<T>,
  group: FlagGroup,
  name: String,
  displayName: String,
  description: String,
  defaultValue: T,
  private val valueConverter: ValueConverter<T>,
  val examples: List<T>,
) : Flag<T>(group, name, displayName, description, StaticFlagDefault(defaultValue), valueConverter) {
  init {
    check(!type.isEnum) { "Use EnumFlag instead" }
    check(!DISALLOWED_CUSTOM_TYPES.contains(type as Class<*>)) { "Use specialized Flag implementation for $type" }
  }

  fun fromString(string: String): T = valueConverter.deserialize(string)

  fun toString(value: T): String = valueConverter.serialize(value)
}

private val DISALLOWED_CUSTOM_TYPES = listOf<Class<*>>(Int::class.java, Long::class.java, String::class.java, Boolean::class.java)

object DEFAULT_DEBUG_FLAG_VALUE : FlagDefault<Boolean>("DebugFlags are only programmatically set") {
  override fun get(): Boolean = java.lang.Boolean.getBoolean("flags.debug.enabled")
}

class DebugFlag(group: FlagGroup, name: String, displayName: String, description: String) :
  Flag<Boolean>(group, name, displayName, description, DEFAULT_DEBUG_FLAG_VALUE, Converter) {

  object Converter : ValueConverter<Boolean> {
    override fun serialize(value: Boolean) = value.toString()

    override fun deserialize(strValue: String) = strValue.toBoolean()
  }
}
