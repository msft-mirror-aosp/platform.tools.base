/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.build.gradle.integration.common.fixture.dsl

import com.android.build.gradle.integration.common.fixture.project.builder.BooleanNameHandler
import com.android.build.gradle.integration.common.fixture.project.builder.BuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.CustomBlockInfo

/**
 * Class that record calls to DSL objects
 *
 * DSL objects are represented by Proxy objects (either automatically generated via [DslProxy] or manually implemented (e.g. [ListProxy],
 * [ApplicationProductFlavorProxy], ...)
 *
 * Each proxy object records calls to it in its own instance of [DslRecorder]. Calls are recorded as event. There are 3 major types of
 * events:
 * - method calls (including calls to setters).
 * - calls to getters that return a new proxy instance with its own [DslRecorder]
 * - calls with lambda that run nested blocks. The object being acted on in the block is its own proxy instance with its own [DslRecorder]
 */
interface DslRecorder {
  /**
   * Records a = b.
   *
   * For boolean, see [setBoolean]
   */
  fun set(name: String, value: Any?)

  /**
   * Records a = (boolean)
   *
   * This handles the case where the boolean is call `isName` because this is written differently in kts and groovy file.
   */
  fun setBoolean(name: String, value: Any?, usingIsNotation: Boolean)

  fun call(name: String, args: List<Any?>, isVarArgs: Boolean)

  /** Records Collection.addAll */
  fun collectionAddAll(value: Collection<Any?>)

  /** Records Collection.add */
  fun collectionAdd(value: Any?)

  /** Records Map.putAll */
  fun mapPutAll(value: Map<out Any?, Any?>)

  /** Records Map.put */
  fun mapPut(key: Any, value: Any?)

  /**
   * Records a nested block. The block must be run as part of `action`
   *
   * @param name the name of the method running the block
   * @param parameters the other parameters to pass to the method running the block
   * @param instanceProvider an action that will instantiate the proxy that the block applies to
   * @param action the action that runs the block.
   */
  fun <T> runNestedBlock(name: String, parameters: List<Any?>, instanceProvider: (DslRecorder) -> T, action: T.() -> Unit)

  /**
   * Records a nested block for a custom class. The block must be run as part of `action`
   *
   * Custom classes are not part of the AGP API. They are provided via 3P plugin extensions.
   *
   * This is used in test files via [ExtensionAwareDefinition.viaExtension]
   *
   * @param name the name of the method running the block
   * @param blockClass the class representing the block
   * @param parameters the other parameters to pass to the method running the block
   * @param instanceProvider an action that will instantiate the proxy that the block applies to
   * @param action the action that runs the block.
   */
  fun <T> runCustomBlock(
    name: String,
    blockClass: Class<T>,
    parameters: List<Any?>,
    instanceProvider: (DslRecorder) -> T,
    action: T.() -> Unit,
  )

  /**
   * Create a chained event and returns the chained recorder.
   *
   * This must be passed to the matching Proxy object.
   */
  fun createChainedRecorder(name: String): DslRecorder

  // returns the content to write the
  fun writeContent(writer: BuildWriter, parentName: String? = null)
}

internal class DefaultDslRecorder() : DslRecorder {

  enum class EventType {
    ASSIGNMENT,
    CALL,
    NESTED_BLOCK,
    CUSTOM_BLOCK,
    CHAINED_CALL,
    COLLECTION_ADD_ALL,
    COLLECTION_ADD,
    MAP_PUT_ALL,
    MAP_PUT,
  }

  data class Event(val type: EventType, val payload: NamedPayload)

  interface NamedPayload {
    val name: String
  }

  open class NamedData(override val name: String, val value: Any? = null) : NamedPayload {
    override fun toString(): String {
      return "NamedData(name='$name', value=$value)"
    }
  }

  data class MethodInfo(override val name: String, val args: List<Any?>, val isVarArgs: Boolean) : NamedPayload

  class AssignmentData(private val propName: String, val value: Any?, private val usingIsNotation: Boolean = false) : NamedPayload {

    override val name: String
      get() = throw RuntimeException("Use computeName() instead")

    fun computeName(booleanNameHandler: BooleanNameHandler) =
      // need to convert the name with isX as needed
      if (usingIsNotation) booleanNameHandler.toIsBooleanName(propName) else propName

    override fun toString(): String {
      return "AssignmentData(propName='$propName', usingIsNotation=$usingIsNotation, value=$value)"
    }
  }

  data class NestedBlockData(override val name: String, val dslRecorder: DslRecorder, val args: List<Any?>) : NamedPayload

  data class CustomBlockData(
    override val name: String,
    override val blockClass: Class<*>,
    val dslRecorder: DslRecorder,
    val args: List<Any?>,
  ) : NamedPayload, CustomBlockInfo

  private val eventList = mutableListOf<Event>()

  internal fun clear() {
    eventList.clear()
  }

  override fun set(name: String, value: Any?) {
    eventList += Event(EventType.ASSIGNMENT, AssignmentData(name, value))
  }

  override fun setBoolean(name: String, value: Any?, usingIsNotation: Boolean) {
    eventList += Event(EventType.ASSIGNMENT, AssignmentData(name, value, usingIsNotation))
  }

  override fun call(name: String, args: List<Any?>, isVarArgs: Boolean) {
    eventList += Event(EventType.CALL, MethodInfo(name, args, isVarArgs))
  }

  override fun collectionAddAll(value: Collection<Any?>) {
    eventList += Event(EventType.COLLECTION_ADD_ALL, NamedData("", value))
  }

  override fun collectionAdd(value: Any?) {
    eventList += Event(EventType.COLLECTION_ADD, NamedData("", value))
  }

  override fun mapPutAll(value: Map<out Any?, Any?>) {
    eventList += Event(EventType.MAP_PUT_ALL, NamedData("", value))
  }

  override fun mapPut(key: Any, value: Any?) {
    eventList += Event(EventType.MAP_PUT, NamedData("", key to value))
  }

  override fun <T> runNestedBlock(name: String, parameters: List<Any?>, instanceProvider: (DslRecorder) -> T, action: T.() -> Unit) {
    val dslRecorder = DefaultDslRecorder()

    action(instanceProvider(dslRecorder))

    eventList += Event(EventType.NESTED_BLOCK, NestedBlockData(name, dslRecorder, parameters))
  }

  override fun <T> runCustomBlock(
    name: String,
    blockClass: Class<T>,
    parameters: List<Any?>,
    instanceProvider: (DslRecorder) -> T,
    action: T.() -> Unit,
  ) {
    val dslRecorder = DefaultDslRecorder()

    action(instanceProvider(dslRecorder))

    eventList += Event(EventType.CUSTOM_BLOCK, CustomBlockData(name, blockClass, dslRecorder, parameters))
  }

  override fun createChainedRecorder(name: String): DslRecorder {
    val newRecorder = DefaultDslRecorder()
    eventList += Event(EventType.CHAINED_CALL, NamedData(name, newRecorder))

    return newRecorder
  }

  private fun String?.dot(name: String): String = this?.let { "$this.$name" } ?: name

  override fun writeContent(writer: BuildWriter, parentName: String?) {
    for (event in eventList) {
      when (event.type) {
        EventType.ASSIGNMENT -> {
          val payload = event.payload as AssignmentData
          writer.set(parentName.dot(payload.computeName(writer)), payload.value)
        }
        EventType.CALL -> {
          val info = event.payload as MethodInfo
          writer.method(parentName.dot(info.name), info.args, info.isVarArgs)
        }
        EventType.NESTED_BLOCK -> {
          val data = event.payload as NestedBlockData
          writer.block(parentName.dot(data.name), data.args, data.dslRecorder) {
            // parent name is always empty inside a nested block
            it.writeContent(this, parentName = null)
          }
        }
        EventType.CUSTOM_BLOCK -> {
          val data = event.payload as CustomBlockData
          val blockName = writer.getCustomBlockName(data)
          writer.block(parentName.dot(blockName), data.args, data.dslRecorder) {
            // parent name is always empty inside a nested block
            it.writeContent(this, parentName = null)
          }
        }
        EventType.CHAINED_CALL -> {
          val payload = event.payload as NamedData
          val chainedRecorder = payload.value as DslRecorder
          chainedRecorder.writeContent(writer, parentName.dot(payload.name))
        }
        EventType.COLLECTION_ADD -> {
          val info = event.payload as NamedData
          // There must be a parent since the collection must come from somewhere
          parentName ?: throw RuntimeException("Event COLLECTION_ADD without a parent!")
          writer.writeCollectionAdd(parentName, info.value)
        }
        EventType.COLLECTION_ADD_ALL -> {
          val info = event.payload as NamedData
          // There must be a parent since the collection must come from somewhere
          parentName ?: throw RuntimeException("Event COLLECTION_ADD_ALL without a parent!")
          writer.writeCollectionAddAll(parentName, info.value as Collection<*>)
        }
        EventType.MAP_PUT -> {
          val info = event.payload as NamedData
          @Suppress("UNCHECKED_CAST") val pair = info.value as Pair<Any, Any?>
          // There must be a parent since the map must come from somewhere
          parentName ?: throw RuntimeException("Event MAP_PUT without a parent!")
          writer.writeMapPut(parentName, pair.first, pair.second)
        }
        EventType.MAP_PUT_ALL -> {
          val info = event.payload as NamedData
          // There must be a parent since the map must come from somewhere
          parentName ?: throw RuntimeException("Event MAP_PUT_ALL without a parent!")
          writer.writeMapPutAll(parentName, info.value as Map<*, *>)
        }
        else -> throw RuntimeException("Unsupported EventType: ${event.type}")
      }
    }
  }
}
