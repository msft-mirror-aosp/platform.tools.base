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

import com.android.tools.lint.checks.fx.utils.Encoder.Companion.adapt
import com.android.tools.lint.checks.fx.utils.Encoder.Companion.orNull
import com.android.tools.lint.checks.fx.utils.Encoder.Companion.withDefault
import com.android.tools.lint.checks.fx.utils.Encoder.Companion.zeroOrMore
import com.google.common.truth.Truth
import kotlin.io.path.createTempDirectory
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Test

class EncoderTest {

  private sealed interface Tree {
    data object Leaf : Tree

    data class Node(val data: Int, val branches: List<Tree> = listOf()) : Tree
  }

  private val treeEncoder: Encoder<Tree> =
    Encoder.fix { treeEncoder ->
      Encoder.sum<Tree>(
        Encoder.case<_, Tree.Leaf>(Encoder.const(Tree.Leaf)),
        Encoder.case<_, Tree.Node>(Encoder.product(Tree::Node, Encoder.int, treeEncoder.zeroOrMore())),
      )
    }

  private enum class Suit {
    Heart,
    Diamond,
    Club,
    Spade,
  }

  @Test
  fun `primitive encoders preserve values`() {
    Encoder.int.testEncodingThenDecodingPreservesValue(42)
    Encoder.int.testEncodingThenDecodingPreservesValue(-1)
    Encoder.int.testEncodingThenDecodingPreservesValue(255)
    Encoder.int.testEncodingThenDecodingPreservesValue(Int.MIN_VALUE)
    Encoder.int.testEncodingThenDecodingPreservesValue(Int.MAX_VALUE)

    Encoder.long.testEncodingThenDecodingPreservesValue(42L)
    Encoder.long.testEncodingThenDecodingPreservesValue(-1L)
    Encoder.long.testEncodingThenDecodingPreservesValue(255L)
    Encoder.long.testEncodingThenDecodingPreservesValue(Long.MIN_VALUE)
    Encoder.long.testEncodingThenDecodingPreservesValue(Long.MAX_VALUE)

    Encoder.string.testEncodingThenDecodingPreservesValue("")
    Encoder.string.testEncodingThenDecodingPreservesValue("foo")

    Encoder.boolean.testEncodingThenDecodingPreservesValue(false)
    Encoder.boolean.testEncodingThenDecodingPreservesValue(true)
  }

  @Test
  fun `list encoder preserve values`() {
    val intListEncoder = Encoder.int.zeroOrMore()
    intListEncoder.testEncodingThenDecodingPreservesValue(listOf())
    intListEncoder.testEncodingThenDecodingPreservesValue(listOf(42))
    intListEncoder.testEncodingThenDecodingPreservesValue(listOf(42, 43))
    intListEncoder.testEncodingThenDecodingPreservesValue(listOf(42, 0, -1))
  }

  @Test
  fun `test interned string preverse value and saves in obvious case`() {
    data class Person(val first: String, val last: String, val age: Int)

    val personEncoder = Encoder.product(::Person, Encoder.string, Encoder.string, Encoder.int).zeroOrMore()
    val optPersonEncoder = Encoder.product(::Person, Encoder.internedString, Encoder.internedString, Encoder.int).zeroOrMore()

    val data =
      listOf(
        Person("John", "Smith", 10),
        Person("John", "Doe", 20),
        Person("Smith", "Adam", 30),
        Person("Adam", "Smith", 40),
        Person("Smith", "John", 50),
      )

    personEncoder.testEncodingThenDecodingPreservesValue(data)
    optPersonEncoder.testEncodingThenDecodingPreservesValue(data)

    Truth.assertThat(optPersonEncoder.encodingSize(data)).isLessThan(personEncoder.encodingSize(data))
  }

  fun `compressing encoder preserves information, and saves in obvious case`() {
    data class Data(
      val a: Map<String, Int> = persistentMapOf(),
      val b: List<String> = listOf(),
      val c: Int = 0,
      val d: Set<String> = persistentSetOf(),
    )

    val encoder =
      Encoder.product(
        ::Data,
        Encoder.map(Encoder.string, Encoder.int),
        Encoder.string.zeroOrMore(),
        Encoder.int,
        Encoder.set(Encoder.string),
      )

    val optEncoder =
      Encoder.product(
        ::Data,
        Encoder.map(Encoder.string, Encoder.int withDefault 0) withDefault persistentMapOf(),
        Encoder.string.zeroOrMore() withDefault listOf(),
        Encoder.int withDefault 0,
        Encoder.set(Encoder.string) withDefault persistentSetOf(),
      )

    val listEncoder = encoder.zeroOrMore()
    val optListEncoder = optEncoder.zeroOrMore()

    val data =
      listOf(
        Data(),
        Data(c = 42),
        Data(a = persistentMapOf("foo" to 0)),
        Data(a = persistentMapOf("bar" to 0, "foo" to 0, "qux" to 1)),
        Data(c = 43, d = persistentSetOf("foo", "bar")),
      )

    listEncoder.testEncodingThenDecodingPreservesValue(data)
    optListEncoder.testEncodingThenDecodingPreservesValue(data)

    Truth.assertThat(optListEncoder.encodingSize(data)).isLessThan(listEncoder.encodingSize(data))
  }

  @Test
  fun `sum, product, list, and recursive encoders preserve values`() {
    val tree = Tree.Node(42, listOf(Tree.Node(1), Tree.Node(2, listOf(Tree.Leaf, Tree.Node(21), Tree.Leaf)), Tree.Leaf))
    treeEncoder.testEncodingThenDecodingPreservesValue(tree)
  }

  @Test
  fun `encoder of map on complex objects preserve values`() {
    val tree1 = Tree.Node(42, listOf(Tree.Node(1), Tree.Node(2, listOf(Tree.Leaf, Tree.Node(21), Tree.Leaf)), Tree.Leaf))
    val tree2 = Tree.Node(2, listOf(Tree.Leaf, Tree.Node(21), Tree.Leaf))
    val treeMap = persistentMapOf<Tree, Tree>(tree1 to tree2, tree2 to Tree.Node(42, listOf(tree1, tree1)))
    val treeMapEncoder = Encoder.map(treeEncoder, treeEncoder)
    treeMapEncoder.testEncodingThenDecodingPreservesValue(treeMap)
  }

  @Test
  fun `encoder of set on complex objects preserve values`() {
    val tree1 = Tree.Node(42, listOf(Tree.Node(1), Tree.Node(2, listOf(Tree.Leaf, Tree.Node(21), Tree.Leaf)), Tree.Leaf))
    val tree2 = Tree.Node(2, listOf(Tree.Leaf, Tree.Node(21), Tree.Leaf))
    val treeSet = persistentSetOf(tree1, tree2)
    val treeSetEncoder = Encoder.set(treeEncoder)
    treeSetEncoder.testEncodingThenDecodingPreservesValue(treeSet)
  }

  @Test
  fun `encoder of enum list preserve values`() {
    val suits = listOf(Suit.Heart, Suit.Diamond, Suit.Heart, Suit.Spade)
    val suitListEncoder = Encoder.enum<Suit>().zeroOrMore()
    suitListEncoder.testEncodingThenDecodingPreservesValue(suits)
  }

  @Test
  fun `encoder of list of nullables preserve values`() {
    val maybeStrings = listOf("foo", "bar", null, "qux")
    val maybeStringEncoder = Encoder.string.orNull().zeroOrMore()
    maybeStringEncoder.testEncodingThenDecodingPreservesValue(maybeStrings)
  }

  @Test
  fun `nested sum encoder not confused`() {
    abstract class Inner
    class Inner1 : Inner()
    class Inner2 : Inner()
    val inner1 = Inner1()
    val inner2 = Inner2()
    abstract class Outer
    data class Outer1(val inner: Inner) : Outer()
    data class Outer2(val value: Inner) : Outer()
    data class Outer3(val value: Inner) : Outer()

    val innerEncoder = Encoder.sum<Inner>(Encoder.case<_, Inner1>(Encoder.const(inner1)), Encoder.case<_, Inner2>(Encoder.const(inner2)))

    val outerEncoder =
      Encoder.sum<Outer>(
        Encoder.case<_, Outer1>(innerEncoder.adapt(Outer1::inner, ::Outer1)),
        Encoder.case<_, Outer2>(innerEncoder.adapt(Outer2::value, ::Outer2)),
        Encoder.case<_, Outer3>(innerEncoder.adapt(Outer3::value, ::Outer3)),
      )

    outerEncoder.testEncodingThenDecodingPreservesValue(Outer3(inner1))
  }

  private fun <T> Encoder<T>.encodingSize(value: T): Int {
    val (main, interns) = encodeAsStrings(value, Encoder.internedString)
    return main.length + interns.sumOf(String::length)
  }

  private fun <T> Encoder<T>.testEncodingThenDecodingPreservesValue(value: T) {
    // Test decoding/encoding to/from memory
    run {
      val (main, interns) = encodeAsStrings(value, Encoder.internedString)
      Truth.assertThat(interns.size).isEqualTo(1)
      val decoded = decodeFromStrings(main, Encoder.internedString to interns[0])
      Truth.assertThat(decoded).isEqualTo(value)
    }

    // Test decoding/encoding to/from files
    run {
      val dirPath = createTempDirectory().toFile().absolutePath
      encodeToDir(value, dirPath, Encoder.internedString)
      val decoded = decodeFromDir(dirPath, Encoder.internedString)
      Truth.assertThat(decoded).isEqualTo(value)
    }
  }
}
