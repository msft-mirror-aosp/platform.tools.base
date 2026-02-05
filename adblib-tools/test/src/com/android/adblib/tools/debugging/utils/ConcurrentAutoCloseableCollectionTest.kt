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
package com.android.adblib.tools.debugging.utils

import kotlin.test.assertContentEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConcurrentAutoCloseableCollectionTest {
  class FakeAutoCloseable(val name: String, var isClosed: Boolean = false) : AutoCloseable {

    var closeCount = 0
    var shouldThrowOnClose = false

    override fun close() {
      closeCount++
      if (shouldThrowOnClose) {
        throw Exception("Simulated exception on close for $name")
      }
      isClosed = true
    }

    override fun toString(): String {
      return "FakeAutoCloseable(name='$name', isClosed=$isClosed, closeCount=$closeCount, shouldThrowOnClose=$shouldThrowOnClose)"
    }
  }

  @Test
  fun addElement() {
    // Prepare
    val collection = ConcurrentAutoCloseableCollection<Any>()
    val element1 = FakeAutoCloseable("element1")
    val element2 = "string element"

    // Act
    collection.add(element1)
    collection.add(element2)
    assertContentEquals(listOf(element1, element2), collection.toList())
  }

  @Test
  fun closeCollection() {
    // Prepare
    val collection = ConcurrentAutoCloseableCollection<Any>()
    val element1 = FakeAutoCloseable("element1")
    val element2 = FakeAutoCloseable("element2")
    val element3 = "string element"
    val element4 = 34
    collection.add(element1)
    collection.add(element2)
    collection.add(element3)
    collection.add(element4)

    // Act
    collection.close()

    // Assert: AutoClosable elements get closed
    assertTrue(element1.isClosed)
    assertTrue(element2.isClosed)
    assertEquals(1, element1.closeCount)
    assertEquals(1, element2.closeCount)
    // Assert: the collection is cleared
    assertTrue(collection.toList().isEmpty())
  }

  @Test
  fun addAfterCloseClosesAutoClosableElement() {
    // Prepare
    val collection = ConcurrentAutoCloseableCollection<Any>()
    val autoClosableElement = FakeAutoCloseable("element1")
    val nonAutoClosableElement = "just a string"
    collection.close()

    // Act
    collection.add(nonAutoClosableElement)
    collection.add(autoClosableElement)

    // Assert
    assertTrue(autoClosableElement.isClosed)
    assertTrue(collection.toList().isEmpty())
  }

  @Test
  fun iterateOverElements() {
    // Prepare
    val collection = ConcurrentAutoCloseableCollection<FakeAutoCloseable>()
    val element1 = FakeAutoCloseable("element1")
    val element2 = FakeAutoCloseable("element2")
    collection.add(element1)
    collection.add(element2)

    // Act
    val iteratedElements = collection.asSequence().toList()

    // Assert
    assertEquals(2, iteratedElements.size)
    assertTrue(iteratedElements.containsAll(listOf(element1, element2)))
  }

  @Test
  fun iteratorIsASnapshot() {
    // Prepare
    val collection = ConcurrentAutoCloseableCollection<FakeAutoCloseable>()
    val element1 = FakeAutoCloseable("element1")
    val element2 = FakeAutoCloseable("element2")
    collection.add(element1)
    collection.add(element2)
    val iterator = collection.iterator()
    // Add a third element after the iterator is created
    val element3 = FakeAutoCloseable("element3")
    collection.add(element3)

    // Act
    val iteratedElements = iterator.asSequence().toList()

    // Assert
    assertEquals(2, iteratedElements.size)
    assertTrue(iteratedElements.containsAll(listOf(element1, element2)))
  }

  @Test
  fun closeCollectionHandlesExceptionInElementClose() {
    // Prepare
    val collection = ConcurrentAutoCloseableCollection<FakeAutoCloseable>()
    val nonFailingElement = FakeAutoCloseable("notFailingElement")
    val failingElement1 = FakeAutoCloseable("failingElement1").apply { shouldThrowOnClose = true }
    val failingElement2 = FakeAutoCloseable("failingElement2").apply { shouldThrowOnClose = true }
    collection.add(nonFailingElement)
    collection.add(failingElement1)
    collection.add(failingElement2)

    // Act / Assert
    try {
      collection.close()
      fail("Should not reach")
    } catch (exception: Throwable) {
      assertTrue(collection.toList().isEmpty())
      assertTrue(nonFailingElement.isClosed)

      assertEquals("One or more errors closing elements of auto closable collection", exception.message)

      assertEquals(2, exception.suppressed.size)
      assertEquals("Simulated exception on close for failingElement1", exception.suppressed[0].message)
      assertFalse(failingElement1.isClosed)
      assertEquals(1, failingElement1.closeCount)

      assertEquals("Simulated exception on close for failingElement2", exception.suppressed[1].message)
      assertFalse(failingElement2.isClosed)
      assertEquals(1, failingElement2.closeCount)
    }
  }
}
