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
package com.android.tools.lint.checks.fx.utils

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Paths
import java.util.Base64
import kotlin.enums.enumEntries
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KFunction2
import kotlin.reflect.KFunction3
import kotlin.reflect.KFunction4
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.plus
import kotlinx.collections.immutable.toPersistentSet

/**
 * An encoder for [T] saves and loads a value of type [T] to and from a byte stream.
 *
 * Users of [Encoder] should not rely on any other encoding details other than the promise that "encoding then decoding gives back the same
 * value",
 */
interface Encoder<T> {

  /** Save [value] into [state], advancing [state]'s internal cursor */
  fun encodeTo(stream: OutputStream, state: EncodingState<*>, value: T)

  /** Load value of [T] out of [state], advancing [state]'s internal cursor */
  fun decodeFrom(stream: InputStream, state: DecodingState<*>): T

  companion object {
    /** Encoder for [Boolean] */
    val boolean: Encoder<Boolean> = Primitive(InputStream::readBoolean, OutputStream::writeBoolean)

    /** Encoder for [Byte] */
    val byte: Encoder<Byte> = Primitive(InputStream::readByte, OutputStream::writeByte)

    /** Encoder for [Int] */
    val int: Encoder<Int> = Primitive(InputStream::readInt, OutputStream::writeInt)

    /** Encoder for [Long] */
    val long: Encoder<Long> = Primitive(InputStream::readLong, OutputStream::writeLong)

    /** Encoder for [String] */
    val string: Encoder<String> = Primitive(InputStream::readString, OutputStream::writeString)

    val internedString: Encoder<String> = Interned(string)

    fun <T> Encoder<T>.interned(): Encoder<T> = Interned(this)

    /** Encoder for [S] in terms of [T], as long as ([from] ∘ [to]) is identity on [S] */
    fun <S, T> Encoder<T>.adapt(to: (S) -> T, from: (T) -> S): Encoder<S> = Adapter(this, to, from)

    /** Forward reference to the [Encoder] computed by [ref] */
    operator fun <T> invoke(ref: () -> Encoder<T>): Encoder<T> = Delay(ref)

    /** Recursive [Encoder] defined in terms of itself on a smaller component of [T] */
    fun <T> fix(make: (Encoder<T>) -> Encoder<T>): Encoder<T> =
      object {
          val self: Encoder<T> = Delay { make(self) }
        }
        .self

    /** Trivial constant [Encoder] that always decodes to [value] */
    fun <T> const(value: T): Encoder<T> = Const(value)

    /** Encoder for [cases] of [T] */
    fun <T : Any> sum(vararg cases: Case<T, *>): Encoder<T> = Sum(cases)

    /** Encoder for [Enum]<[E]> */
    inline fun <reified E : Enum<E>> enum(): Encoder<E> {
      val entries = enumEntries<E>()
      return when {
        entries.size < Byte.MAX_VALUE -> byte.adapt({ it.ordinal.toByte() }, { entries[it.toInt()] })
        else -> int.adapt(Enum<E>::ordinal, entries::get)
      }
    }

    /**
     * Return an encoder like [this], but if it appears as part of a product and has a common [default] value, the encoding of containing
     * product might be optimized.
     */
    infix fun <T> Encoder<T>.withDefault(default: T): Encoder<T> =
      WithHintedDefault(if (this is WithHintedDefault) encoder else this, default)

    /** Encoder for [T]? */
    fun <T : Any> Encoder<T>.orNull(): Encoder<T?> = Nullable(this)

    /** Encoder for [List] of [T], given one of [T] */
    fun <T> Encoder<T>.zeroOrMore(): Encoder<List<T>> = ZeroOrMore(this)

    /** Encoder for [PersistentMap] of keys [K] and values [V] */
    fun <K, V> map(key: Encoder<K>, value: Encoder<V>): Encoder<PersistentMap<K, V>> =
      product(::Pair, key, value)
        .zeroOrMore()
        .adapt(to = PersistentMap<K, V>::toList, from = { it.fold(persistentMapOf(), PersistentMap<K, V>::plus) })

    /** Encoder for [PersistentSet] of [T] */
    fun <T> set(elem: Encoder<T>): Encoder<PersistentSet<T>> = elem.zeroOrMore().adapt(PersistentSet<T>::toList, List<T>::toPersistentSet)

    /** Encoder for [S] as a reuse of one for its super type [T] */
    inline fun <T, reified S : T> Encoder<T>.subType(): Encoder<S> = adapt({ it }, { it as S })

    /** Encoder for class [P], given ones on its fields [T0] and [T1] */
    inline fun <reified P : Any, T0, T1> product(constructor: KFunction2<T0, T1, P>, enc0: Encoder<T0>, enc1: Encoder<T1>): Encoder<P> =
      P::class.uncheckedProduct(constructor, enc0, enc1)

    /** Encoder for class [P], given ones on its fields [T0], [T1], and [T2] */
    inline fun <reified P : Any, T0, T1, T2> product(
      constructor: KFunction3<T0, T1, T2, P>,
      enc0: Encoder<T0>,
      enc1: Encoder<T1>,
      enc2: Encoder<T2>,
    ): Encoder<P> = P::class.uncheckedProduct(constructor, enc0, enc1, enc2)

    /** Encoder for class [P], given ones on its fields [T0], [T1], [T2], and [T3] */
    inline fun <reified P : Any, T0, T1, T2, T3> product(
      constructor: KFunction4<T0, T1, T2, T3, P>,
      enc0: Encoder<T0>,
      enc1: Encoder<T1>,
      enc2: Encoder<T2>,
      enc3: Encoder<T3>,
    ): Encoder<P> = P::class.uncheckedProduct(constructor, enc0, enc1, enc2, enc3)

    fun <P : Any> KClass<P>.uncheckedProduct(constructor: KFunction<P>, vararg encoders: Encoder<*>): Encoder<P> =
      Product(this, constructor, encoders)

    /** Given an encoder for subtype [Ti] of [T], make a [Case] of encoder for [T] */
    inline fun <T, reified Ti : T & Any> case(encoder: Encoder<Ti>, name: String = Ti::class.java.simpleName): Case<T, Ti> =
      Case(name, encoder) { it as? Ti }
  }

  private class WithHintedDefault<T>(val encoder: Encoder<T>, val default: T) : Encoder<T> by encoder

  private class Const<T>(val const: T) : Encoder<T> {
    override fun encodeTo(stream: OutputStream, state: EncodingState<*>, value: T) {
      require(value == const) { "Expect only `$const`, given `$value`" }
    }

    override fun decodeFrom(stream: InputStream, state: DecodingState<*>): T = const
  }

  private class Primitive<T>(private val read: (InputStream) -> T, private val write: (OutputStream, T) -> Unit) : Encoder<T> {
    override fun encodeTo(stream: OutputStream, state: EncodingState<*>, value: T) = write(stream, value)

    override fun decodeFrom(stream: InputStream, state: DecodingState<*>) = read(stream)
  }

  private class Interned<T>(val base: Encoder<T>) : Encoder<T> {
    override fun encodeTo(stream: OutputStream, state: EncodingState<*>, value: T) = stream.writeInt(state.intern(base, value))

    override fun decodeFrom(stream: InputStream, state: DecodingState<*>): T = state.valueAt(base, stream.readInt())
  }

  private class Nullable<T : Any>(private val base: Encoder<T>) : Encoder<T?> {
    override fun encodeTo(stream: OutputStream, state: EncodingState<*>, value: T?) =
      when (value) {
        null -> stream.writeBoolean(false)
        else -> {
          stream.writeBoolean(true)
          base.encodeTo(stream, state, value)
        }
      }

    override fun decodeFrom(stream: InputStream, state: DecodingState<*>): T? =
      when (stream.readBoolean()) {
        false -> null
        true -> base.decodeFrom(stream, state)
      }
  }

  private class Adapter<S, T>(private val base: Encoder<T>, private val to: (S) -> T, private val from: (T) -> S) : Encoder<S> {
    override fun encodeTo(stream: OutputStream, state: EncodingState<*>, value: S) = base.encodeTo(stream, state, to(value))

    override fun decodeFrom(stream: InputStream, state: DecodingState<*>): S = from(base.decodeFrom(stream, state))
  }

  private class Product<P : Any>(klass: KClass<P>, private val constructor: KFunction<P>, encoders: Array<out Encoder<*>>) : Encoder<P> {
    init {
      require(encoders.size <= Byte.SIZE_BITS) {
        "Product of more than ${Byte.SIZE_BITS} flattened components not supported. Break it down."
      }
    }

    private val shouldTryCompressing = encoders.any { it is WithHintedDefault }

    private val fields: Array<Field<P, *>> =
      Array(encoders.size) { i ->
        val param = constructor.parameters[i]
        val prop =
          klass.memberProperties.find { it.name == param.name }
            ?: throw IllegalArgumentException("Class `${klass.simpleName}` has no field `${param.name}`")
        fun <T> uncheckedField(prop: KProperty1<P, *>, enc: Encoder<T>) = Field(prop as KProperty1<P, T>, enc)
        uncheckedField(prop, encoders[i])
      }

    override fun encodeTo(stream: OutputStream, state: EncodingState<*>, value: P) =
      if (shouldTryCompressing) {
        val fieldsToEncode = mutableListOf<Field<P, *>>()
        var mask: Byte = 0.toByte()
        for ((i, field) in fields.withIndex()) {
          val enc = field.encoder
          if (enc !is WithHintedDefault || enc.default != field.access(value)) {
            mask = (mask.toInt() or (1 shl i)).toByte()
            fieldsToEncode.add(field)
          }
        }

        stream.writeByte(mask)
        for (field in fieldsToEncode) field.encode(stream, state, value)
      } else for (field in fields) field.encode(stream, state, value)

    override fun decodeFrom(stream: InputStream, state: DecodingState<*>): P {
      val args =
        if (shouldTryCompressing) {
          val mask = stream.readByte().toInt()
          val args =
            Array(fields.size) { i ->
              val field = fields[i]
              val enc = field.encoder
              when {
                enc is WithHintedDefault && mask and (1 shl i) == 0 -> enc.default
                else -> field.decode(stream, state)
              }
            }
          args
        } else {
          Array(fields.size) { fields[it].decode(stream, state) }
        }
      return constructor.call(*args)
    }

    private class Field<P, T>(val access: KProperty1<P, T>, val encoder: Encoder<T>) {
      fun decode(stream: InputStream, state: DecodingState<*>): T = encoder.decodeFrom(stream, state)

      fun encode(stream: OutputStream, state: EncodingState<*>, product: P) = encoder.encodeTo(stream, state, access(product))
    }
  }

  private class Sum<S>(private val cases: Array<out Case<S, *>>) : Encoder<S> {

    private val indexEncoder: Encoder<Int> =
      when {
        cases.size < Byte.MAX_VALUE -> byte.adapt({ it.toByte() }, { it.toInt() })
        else -> int
      }

    override fun encodeTo(stream: OutputStream, state: EncodingState<*>, value: S) {
      for ((i, case) in cases.withIndex()) {
        if (case.tryEncodeTo(stream, state, value, { indexEncoder.encodeTo(stream, state, i) })) return
      }
      throw IllegalArgumentException("None of [${cases.joinToString()}] applies to $value")
    }

    override fun decodeFrom(stream: InputStream, state: DecodingState<*>): S =
      cases[indexEncoder.decodeFrom(stream, state)].decodeFrom(stream, state)
  }

  private class ZeroOrMore<T>(private val elem: Encoder<T>) : Encoder<List<T>> {
    override fun encodeTo(stream: OutputStream, state: EncodingState<*>, value: List<T>) {
      stream.writeInt(value.size)
      for (v in value) elem.encodeTo(stream, state, v)
    }

    override fun decodeFrom(stream: InputStream, state: DecodingState<*>): List<T> =
      List(stream.readInt()) { elem.decodeFrom(stream, state) }
  }

  private class Delay<T>(ref: () -> Encoder<T>) : Encoder<T> {
    private val delegate by lazy(LazyThreadSafetyMode.NONE, ref)

    override fun encodeTo(stream: OutputStream, state: EncodingState<*>, value: T) = delegate.encodeTo(stream, state, value)

    override fun decodeFrom(stream: InputStream, state: DecodingState<*>): T = delegate.decodeFrom(stream, state)
  }

  class Case<T, Ti : T & Any>(val name: String, private val encoder: Encoder<Ti>, private val tryCast: (T) -> Ti?) :
    Encoder<Ti> by encoder {
    override fun toString() = name

    internal fun tryEncodeTo(stream: OutputStream, state: EncodingState<*>, value: T, writeIndex: () -> Unit): Boolean =
      when (val value0 = tryCast(value)) {
        null -> false
        else -> {
          writeIndex()
          encoder.encodeTo(stream, state, value0)
          true
        }
      }
  }

  class EncodingState<S : OutputStream>(interning: List<Pair<Encoder<*>, S>>) {
    private val records =
      interning.map { (encoder, stream) ->
        require(encoder is Interned) { "Encoder $encoder is not interned" }
        Record(encoder.base, mutableMapOf(), mutableListOf(), stream)
      }

    /**
     * The index of the last intern stream flushed at the end. If flushing one stream results in growing an already flushed intern stream,
     * there'll be a runtime error. This can be avoided by only allowing earlier intern streams to refer to later ones.
     */
    private var lastFinalizingIndex: Int = -1

    fun <T> intern(rawEncoder: Encoder<T>, value: T): Int =
      when (val recordIndex = records.indexOfFirst { it.rawEncoder == rawEncoder }) {
        !in records.indices ->
          throw IllegalArgumentException("$rawEncoder not registered for interning in ${records.map { it.rawEncoder }}")
        in 0..lastFinalizingIndex ->
          throw IllegalStateException(
            "Circular reference in interned values not supported. " +
              "Interning new value at #$recordIndex while flushing #$lastFinalizingIndex."
          )
        else -> intern(records[recordIndex] as Record<T>, value)
      }

    private fun <T> intern(record: Record<T>, value: T): Int {
      val (_, memo, values, _) = record
      return memo.getOrPut(value) {
        val n = values.size
        values.add(value)
        n
      }
    }

    fun finalize() {
      for ((i, record) in records.withIndex()) {
        lastFinalizingIndex = i
        record.finalize()
      }
    }

    private fun <T> Record<T>.finalize() {
      try {
        val (rawEncoder, _, values, stream) = this
        stream.writeInt(values.size)
        for (v in values) rawEncoder.encodeTo(stream, this@EncodingState, v)
      } finally {
        stream.close()
      }
    }

    private data class Record<T>(
      val rawEncoder: Encoder<T>,
      val memo: MutableMap<T, Int>,
      val values: MutableList<T>,
      val stream: OutputStream,
    )
  }

  class DecodingState<S : InputStream>(interning: List<Pair<Encoder<*>, S>>) {
    private val records =
      interning.map { (encoder, stream) ->
        require(encoder is Interned) { "Encoder $encoder is not interned" }
        Record(encoder.base, mutableListOf())
      }
    private var lastLoaded: Int = interning.size

    init {
      fun <T> Record<T>.init(stream: InputStream) {
        val (encoder, memos) = this
        val n = stream.readInt()
        repeat(n) { memos.add(encoder.decodeFrom(stream, this@DecodingState)) }
        stream.close()
      }

      for (i in interning.size - 1 downTo 0) {
        records[i].init(interning[i].second)
        lastLoaded = i
      }
    }

    fun <T> valueAt(rawEncoder: Encoder<T>, index: Int): T =
      when (val recordIndex = records.indexOfFirst { it.rawEncoder == rawEncoder }) {
        !in records.indices -> throw IllegalStateException("$rawEncoder not registered for interning in ${records.map { it.rawEncoder }}")
        !in lastLoaded until records.size ->
          throw IllegalStateException(
            "Circular reference in interned values not supported. " +
              "Loading interned value from #$recordIndex while having loaded down to #$lastLoaded"
          )
        else -> valueAt(records[recordIndex] as Record<T>, index)
      }

    private fun <T> valueAt(record: Record<T>, index: Int): T {
      val (_, memo) = record
      return memo[index]
    }

    private data class Record<T>(val rawEncoder: Encoder<T>, val memo: MutableList<T>)
  }
}

private fun InputStream.readByte(): Byte = read().toByte()

private fun OutputStream.writeByte(b: Byte) = write(b.toInt())

private fun InputStream.readInt(): Int = (0 until Int.SIZE_BYTES).fold(0) { res, i -> res or (read() shl (i * Byte.SIZE_BITS)) }

private fun OutputStream.writeInt(n: Int) {
  for (i in 0 until Int.SIZE_BYTES) write(n ushr (i * Byte.SIZE_BITS))
}

private fun InputStream.readBoolean(): Boolean = read() != 0

private fun OutputStream.writeBoolean(b: Boolean) = write(if (b) 1 else 0)

private fun InputStream.readLong(): Long =
  (0 until Long.SIZE_BYTES).fold(0L) { res, i -> res or (read().toLong() shl (i * Byte.SIZE_BITS)) }

private fun OutputStream.writeLong(l: Long) {
  for (i in 0 until Long.SIZE_BYTES) write((l ushr (i * Byte.SIZE_BITS)).toInt())
}

private fun OutputStream.writeString(s: String) {
  val sBytes = s.encodeToByteArray()
  writeInt(sBytes.size)
  write(sBytes)
}

private fun InputStream.readString(): String = readNBytes(readInt()).decodeToString()

/** Return a [String] encoding of the value, as well as an extra [String] for each interned [Encoder] */
fun <T> Encoder<T>.encodeAsStrings(value: T, vararg internedEncoders: Encoder<*>): Pair<String, List<String>> {
  val mainStream = ByteArrayOutputStream()
  val internStreams = List(internedEncoders.size, ::ByteArrayOutputStream)
  encodeAndFinalize(mainStream, Encoder.EncodingState(internedEncoders zip internStreams), value)
  return with(Base64.getEncoder()) {
    fun ByteArrayOutputStream.toEncodedString() = encodeToString(toByteArray())
    mainStream.toEncodedString() to internStreams.map(ByteArrayOutputStream::toEncodedString)
  }
}

/** Decode [String] and interned values */
fun <T> Encoder<T>.decodeFromStrings(mainEncoding: String, vararg interns: Pair<Encoder<*>, String>): T =
  with(Base64.getDecoder()) {
    fun String.toDecodingStream() = ByteArrayInputStream(decode(this))
    val state = Encoder.DecodingState(interns.map { (enc, str) -> enc to str.toDecodingStream() })
    decodeFrom(mainEncoding.toDecodingStream(), state)
  }

fun <T> Encoder<T>.encodeToDir(value: T, dirPath: String, vararg internedEncoders: Encoder<*>) {
  fun outStream(fileName: String) =
    with(Paths.get(dirPath, fileName).toFile()) {
      if (!exists()) createNewFile()
      FileOutputStream(this)
    }
  val mainStream = outStream(PREFIX_DATA)
  val internStreams = List(internedEncoders.size) { outStream("$PREFIX_DATA$it") }
  encodeAndFinalize(mainStream, Encoder.EncodingState(internedEncoders zip internStreams), value)
}

fun <T> Encoder<T>.decodeFromDir(dirPath: String, vararg internEncoders: Encoder<*>): T {
  fun inStream(fileName: String) = FileInputStream(Paths.get(dirPath, fileName).toFile())
  val mainStream = inStream(PREFIX_DATA)
  val internStreams = List(internEncoders.size) { inStream("$PREFIX_DATA$it") }
  try {
    return decodeFrom(mainStream, Encoder.DecodingState(internEncoders zip internStreams))
  } finally {
    mainStream.close()
    internStreams.forEach(FileInputStream::close)
  }
}

private fun <S : OutputStream, T> Encoder<T>.encodeAndFinalize(stream: S, state: Encoder.EncodingState<S>, value: T) {
  try {
    encodeTo(stream, state, value)
  } finally {
    stream.close()
    state.finalize()
  }
}

private const val PREFIX_DATA = "data"
