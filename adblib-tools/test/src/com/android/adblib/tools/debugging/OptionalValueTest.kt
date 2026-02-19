/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.adblib.tools.debugging

import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionalValueTest {

  @Test
  fun optionalValue_empty_toString_works(): Unit = runBlockingWithTimeout {
    // Prepare
    val empty = OptionalValue.empty<Int>()

    // Act
    val result = empty.toString()

    // Assert
    assertEquals("OptionalValue.empty", result)
  }

  @Test
  fun optionalValue_empty_equals_works(): Unit = runBlockingWithTimeout {
    // Prepare
    val empty1 = OptionalValue.empty<Int>()
    val empty2 = OptionalValue.empty<String>()

    // Act/Assert
    assertEquals(empty1, empty2)
  }

  @Test
  fun optionalValue_empty_isSingleton(): Unit = runBlockingWithTimeout {
    // Prepare
    val empty1 = OptionalValue.empty<Int>()
    val empty2 = OptionalValue.empty<String>()

    // Act/Assert
    assertSame(empty1, empty2)
  }

  @Test
  fun optionalValue_ofError_doesNotAllowEmptyMessage(): Unit = runBlockingWithTimeout {
    // Act/Assert
    assertThrows(IllegalArgumentException::class.java) { OptionalValue.ofError<Int>("") }
  }

  @Test
  fun optionalValue_ofError_toString_works(): Unit = runBlockingWithTimeout {
    // Prepare
    val value = OptionalValue.ofError<Int>("My message")

    // Act
    val result = value.toString()

    // Assert
    assertEquals("OptionalValue.error(\"My message\")", result)
  }

  @Test
  fun optionalValue_ofError_equals_works(): Unit = runBlockingWithTimeout {
    // Prepare
    val value1 = OptionalValue.ofError<Int>("My message")
    val value2 = OptionalValue.ofError<Int>("My message")
    val value3 = OptionalValue.ofError<Int>("My message 2")

    // Act/Assert
    assertEquals(value1, value2)
    assertNotEquals(value1, value3)
    assertNotEquals(value2, value3)
  }

  @Test
  fun optionalValue_of_toString_works(): Unit = runBlockingWithTimeout {
    // Prepare
    val value = OptionalValue.of(5)
    val value2 = OptionalValue.of("foo")

    // Act
    val result = value.toString()
    val result2 = value2.toString()

    // Assert
    assertEquals("OptionalValue(5)", result)
    assertEquals("OptionalValue(\"foo\")", result2)
  }

  @Test
  fun optionalValue_of_equals_works(): Unit = runBlockingWithTimeout {
    // Prepare
    val value1 = OptionalValue.of(5)
    val value2 = OptionalValue.of(5)
    val value3 = OptionalValue.of("My message 2")

    // Act/Assert
    assertEquals(value1, value2)
    assertNotEquals(value1, value3)
    assertNotEquals(value2, value3)
  }

  @Test
  fun optionalValue_of_hashCode_works(): Unit = runBlockingWithTimeout {
    // Prepare
    val value1 = OptionalValue.of(5)
    val value2 = OptionalValue.of(5)
    val value3 = OptionalValue.of("My message 2")

    // Act/Assert
    assertEquals(value1.hashCode(), value2.hashCode())
    assertNotEquals(value1.hashCode(), value3.hashCode())
    assertNotEquals(value2.hashCode(), value3.hashCode())
  }

  @Test
  fun optionalValue_ofBoolean_areSingletons(): Unit = runBlockingWithTimeout {
    // Prepare
    val value1 = OptionalValue.of(true)
    val value2 = OptionalValue.of(true)
    val value3 = OptionalValue.of(false)
    val value4 = OptionalValue.of(false)

    // Act/Assert
    assertSame(value1, value2)
    assertSame(value3, value4)
  }

  @Test
  fun optionalValue_ofZero_isSingleton(): Unit = runBlockingWithTimeout {
    // Prepare
    val value1 = OptionalValue.of(0)
    val value2 = OptionalValue.of(0)

    // Act/Assert
    assertSame(value1, value2)
  }

  @Test
  fun optionalValue_ofZero_isNotTheSameAsZeroLong(): Unit = runBlockingWithTimeout {
    // Prepare
    val value1 = OptionalValue.of(0)
    val value2 = OptionalValue.of(0L)

    // Act/Assert
    assertNotSame(value1, value2)
    assertNotEquals(value1, value2)
  }

  @Test
  fun optionalValue_ofEmptyString_isSingleton(): Unit = runBlockingWithTimeout {
    // Prepare
    val value1 = OptionalValue.of("")
    val value2 = OptionalValue.of("")

    // Act/Assert
    assertSame(value1, value2)
  }

  @Test
  fun optionalValue_isEmpty_Works(): Unit = runBlockingWithTimeout {
    // Prepare
    val empty = OptionalValue.empty<String>()
    val error = OptionalValue.ofError<String>("My message")
    val value = OptionalValue.of("Foo Bar")

    // Act/Assert
    assertTrue(empty.isEmpty)
    assertFalse(error.isEmpty)
    assertFalse(value.isEmpty)
  }

  @Test
  fun optionalValue_isError_Works(): Unit = runBlockingWithTimeout {
    // Prepare
    val empty = OptionalValue.empty<String>()
    val error = OptionalValue.ofError<String>("My message")
    val value = OptionalValue.of("Foo Bar")

    // Act/Assert
    assertFalse(empty.isError)
    assertTrue(error.isError)
    assertFalse(value.isError)
  }

  @Test
  fun optionalValue_hasValue_Works(): Unit = runBlockingWithTimeout {
    // Prepare
    val empty = OptionalValue.empty<String>()
    val error = OptionalValue.ofError<String>("My message")
    val value = OptionalValue.of("Foo Bar")

    // Act/Assert
    assertFalse(empty.hasValue)
    assertFalse(error.hasValue)
    assertTrue(value.hasValue)
  }

  @Test
  fun optionalValue_getErrorMessageOrThrow_Works(): Unit = runBlockingWithTimeout {
    // Prepare
    val empty = OptionalValue.empty<String>()
    val error = OptionalValue.ofError<String>("My message")
    val value = OptionalValue.of("Foo Bar")

    // Act/Assert
    assertThrows(NoSuchElementException::class.java) { empty.getErrorMessageOrThrow() }
    assertEquals("My message", error.getErrorMessageOrThrow())
    assertThrows(NoSuchElementException::class.java) { value.getErrorMessageOrThrow() }
  }

  @Test
  fun optionalValue_getOrThrow_Works(): Unit = runBlockingWithTimeout {
    // Prepare
    val empty = OptionalValue.empty<String>()
    val error = OptionalValue.ofError<String>("My message")
    val value = OptionalValue.of("Foo Bar")

    // Act/Assert
    assertThrows(NoSuchElementException::class.java) { empty.getOrThrow() }
    assertThrows(NoSuchElementException::class.java) { error.getOrThrow() }
    assertEquals("Foo Bar", value.getOrThrow())
  }

  @Test
  fun optionalValue_getOrNull_Works(): Unit = runBlockingWithTimeout {
    // Prepare
    val empty = OptionalValue.empty<String>()
    val error = OptionalValue.ofError<String>("My message")
    val value = OptionalValue.of("Foo Bar")

    // Act/Assert
    assertNull(empty.getOrNull())
    assertNull(error.getOrNull())
    assertNotNull(value.getOrNull())
  }

  @Test
  fun optionalValue_getOrDefault_Works(): Unit = runBlockingWithTimeout {
    // Prepare
    val empty = OptionalValue.empty<String>()
    val error = OptionalValue.ofError<String>("My message")
    val value = OptionalValue.of("Foo Bar")

    // Act/Assert
    assertEquals("foo", empty.getOrDefault("foo"))
    assertEquals("foo", error.getOrDefault("foo"))
    assertEquals("Foo Bar", value.getOrDefault("foo"))
  }

  @Test
  fun optionalValue_alsoIfValue_Works(): Unit = runBlockingWithTimeout {
    // Prepare
    val empty = OptionalValue.empty<String>()
    val error = OptionalValue.ofError<String>("My message")
    val value = OptionalValue.of("Foo Bar")

    // Act
    var alsoValue1 = 5
    var alsoValue2 = 5
    var alsoValue3 = 5
    empty.alsoIfValue { alsoValue1 = 10 }
    error.alsoIfValue { alsoValue2 = 10 }
    value.alsoIfValue { alsoValue3 = 10 }

    // Assert
    assertEquals(5, alsoValue1)
    assertEquals(5, alsoValue2)
    assertEquals(10, alsoValue3)
  }

  @Test
  fun optionalValue_orElse_Works(): Unit = runBlockingWithTimeout {
    // Prepare
    val empty = OptionalValue.empty<String>()
    val error = OptionalValue.ofError<String>("My message")
    val value1 = OptionalValue.of("Foo Bar")
    val value2 = OptionalValue.of("Foo Bar Blah")

    // Act
    val result1 = empty.orElse(empty)
    val result2 = empty.orElse(value1)
    val result3 = empty.orElse(value2)
    val result4 = empty.orElse(error)

    val result11 = error.orElse(empty)
    val result12 = error.orElse(value1)
    val result13 = error.orElse(value2)
    val result14 = error.orElse(error)

    val result21 = value1.orElse(empty)
    val result22 = value1.orElse(value1)
    val result23 = value1.orElse(value2)
    val result24 = value1.orElse(error)

    val result31 = value2.orElse(empty)
    val result32 = value2.orElse(value1)
    val result33 = value2.orElse(value2)
    val result34 = value2.orElse(error)

    // Assert
    assertSame(empty, result1)
    assertSame(value1, result2)
    assertSame(value2, result3)
    assertSame(error, result4)

    assertSame(error, result11)
    assertSame(value1, result12)
    assertSame(value2, result13)
    assertSame(error, result14)

    assertSame(value1, result21)
    assertSame(value1, result22)
    assertSame(value1, result23)
    assertSame(value1, result24)

    assertSame(value2, result31)
    assertSame(value2, result32)
    assertSame(value2, result33)
    assertSame(value2, result34)
  }
}
