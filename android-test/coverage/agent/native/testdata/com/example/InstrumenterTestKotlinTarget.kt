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

package com.example

import kotlinx.serialization.Serializable

/** Instrumented by instrumenter_test.cc with the real Kotlin filters. */
class InstrumenterTestKotlinTarget {
  private var count = 1

  // Kotlin evaluates the default value in the synthetic greet$default method.
  fun greet(name: String = "World"): String = "Hello $name"

  // Kotlin inlines the body of twice() into callsTwice(), on lines after the
  // end of this file.
  inline fun twice(value: Int, block: (Int) -> Int): Int = block(value) * 2

  fun callsTwice(): Int = twice(count) { it + 1 }

  // The null checks of the safe calls (?.) and of the elvis operator (?:) all
  // lead to `return 0`, which only runs when one of the values is null.
  fun nameLength(node: Node?): Int {
    val name = node?.next?.name ?: return 0
    return name.length
  }

  class Node(val next: Node?, val name: String?)

  // Kotlin gives the object a private constructor that only calls the
  // constructor of Object.
  object Defaults {
    fun name(): String = "World"
  }

  inner class Reader {
    // Reads `count` through the synthetic accessor access$getCount$p.
    fun read(): Int = count
  }

  // A `when` on a sealed class or an enum that covers every case ends with a
  // throw of NoWhenBranchMatchedException, which can't run.
  sealed interface Shape

  class Circle : Shape

  class Square : Shape

  fun sides(shape: Shape): Int =
    when (shape) {
      is Circle -> 0
      is Square -> 4
    }

  // The nullable subject is checked for null before the switch on its case,
  // which handles null as one of the cases.
  fun severity(level: DeprecationLevel?): Int =
    when (level) {
      DeprecationLevel.WARNING -> 1
      DeprecationLevel.ERROR -> 2
      DeprecationLevel.HIDDEN -> 3
      null -> 0
    }

  // The switch of the `when` is inlined into callsLevel(), after the read of
  // `count`.
  inline fun level(severity: DeprecationLevel): Int =
    when (severity) {
      DeprecationLevel.WARNING -> 1
      DeprecationLevel.ERROR -> 2
      DeprecationLevel.HIDDEN -> 3
    }

  fun callsLevel(severity: DeprecationLevel): Int = count + level(severity)

  // Both `when`s need -1 for null, which D8 loads only once.
  fun severities(first: DeprecationLevel?, second: DeprecationLevel?): Int {
    val a =
      when (first) {
        DeprecationLevel.WARNING -> 1
        DeprecationLevel.ERROR -> 2
        DeprecationLevel.HIDDEN -> 3
        null -> 0
      }
    val b =
      when (second) {
        DeprecationLevel.WARNING -> 10
        DeprecationLevel.ERROR -> 20
        DeprecationLevel.HIDDEN -> 30
        null -> 0
      }
    return a + b
  }

  interface Transform<T> {
    operator fun invoke(value: T): T
  }

  // The object gets a synthetic bridge invoke(Any?): Any? that unboxes the
  // parameter and calls invoke(Int).
  val increment: Transform<Int> =
    object : Transform<Int> {
      override fun invoke(value: Int): Int = value + 1
    }

  // Kotlin also generates Labeled$DefaultImpls for the classes compiled against
  // Labeled without default methods in the JVM. Its label() calls the default
  // method.
  interface Labeled {
    fun label(): String = "label"
  }

  // Topic gets a bridge to the default method of Labeled, which isn't
  // synthetic.
  class Topic : Labeled

  private operator fun invoke(): Int = count

  // Caller calls the private invoke() through the synthetic accessor
  // access$invoke.
  inner class Caller {
    fun call(): Int = this@InstrumenterTestKotlinTarget()
  }

  // The serialization plugin gives the companion object of Message a
  // serializer() that returns the generated serializer, on the line of the
  // class like the constructor of the companion object.
  @Serializable class Message(val text: String)

  // It gives the companion object of a sealed class a serializer() that returns
  // the serializer that the companion object creates once.
  @Serializable sealed class Event

  // It gives the object a serializer() that returns the serializer that it
  // creates once.
  @Serializable object Ping

  // Kotlin gives Size a constructor that only passes the name and the ordinal
  // of a constant to the constructor of Enum.
  enum class Size {
    SMALL,
    LARGE,
  }

  // The handler of the synchronized block exits the monitor and throws the
  // exception again.
  fun synchronizedBlock(lock: Any): Int = synchronized(lock) { count }

  // Kotlin compiles the code of a value class into static methods, such as
  // toString-impl(), and calls them directly. The getter of `value` and the
  // toString() that calls toString-impl() are instance methods for Java code
  // and boxed values.
  @JvmInline
  value class Meters(val value: Int) {
    override fun toString(): String = "$value m"
  }
}
