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
package com.android.adblib

import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ListWithStateFlowStatusTest {

    @Test
    fun equals_works(): Unit = runBlockingWithTimeout {
        // Prepare
        val empty = ListWithStateFlowStatus(emptyList<Int>(), StateFlowStatus.startOfFlow)
        val nonEmpty = ListWithStateFlowStatus(listOf(1, 2), StateFlowStatus.startOfFlow)
        val emptyStatus2 = ListWithStateFlowStatus(emptyList<Int>(), StateFlowStatus.endOfFlow)
        val nonEmptyStatus2 = ListWithStateFlowStatus(listOf(1, 2), StateFlowStatus.endOfFlow)

        // Act/Assert
        assertEquals(emptyList<Int>(), empty)
        assertNotEquals(emptyList<Int>(), nonEmpty)
        assertEquals(emptyList<Int>(), emptyStatus2)
        assertNotEquals(emptyList<Int>(), nonEmptyStatus2)

        assertNotEquals(listOf(1, 2), empty)
        assertEquals(listOf(1, 2), nonEmpty)
        assertNotEquals(listOf(1, 2), emptyStatus2)
        assertEquals(listOf(1, 2), nonEmptyStatus2)

        assertEquals(empty, empty)
        assertNotEquals(empty, nonEmpty)
        assertNotEquals(empty, emptyStatus2) // Note: Status are different!
        assertNotEquals(empty, nonEmptyStatus2)

        assertNotEquals(nonEmpty, empty)
        assertEquals(nonEmpty, nonEmpty)
        assertNotEquals(nonEmpty, emptyStatus2)
        assertNotEquals(nonEmpty, nonEmptyStatus2)  // Note: Status are different!

        assertNotEquals(emptyStatus2, empty)  // Note: Status are different!
        assertNotEquals(emptyStatus2, nonEmpty)
        assertEquals(emptyStatus2, emptyStatus2)
        assertNotEquals(emptyStatus2, nonEmptyStatus2)  // Note: Status are different!

        assertNotEquals(nonEmptyStatus2, empty)
        assertNotEquals(nonEmptyStatus2, nonEmpty)  // Note: Status are different!
        assertNotEquals(nonEmptyStatus2, emptyStatus2)
        assertEquals(nonEmptyStatus2, nonEmptyStatus2)
    }

    @Test
    fun toString_works(): Unit = runBlockingWithTimeout {
        // Prepare
        val list1 = MyList(listOf(1, 2), StateFlowStatus.startOfFlow)
        val list2 = MyList(listOf(1, 2), StateFlowStatus.active)
        val list3 = MyList(listOf(1, 2), StateFlowStatus.endOfFlow)
        val list4 = MyList(listOf(1, 2), StateFlowStatus.retrying(Exception("Foo")))

        // Act/Assert
        assertEquals("MyList(Flow is initializing): [1, 2]", list1.toString())
        assertEquals("MyList(Flow is active): [1, 2]", list2.toString())
        assertEquals("MyList(Flow has stopped): [1, 2]", list3.toString())
        assertEquals("MyList(Flow is retrying (currentError=\"Foo\")): [1, 2]", list4.toString())
    }

    @Test
    fun hashCode_works(): Unit = runBlockingWithTimeout {
        // Prepare
        val list1 = MyList(listOf(1, 2), StateFlowStatus.startOfFlow)
        val list2 = MyList(listOf(1, 2), StateFlowStatus.active)
        val list3 = MyList(listOf(1, 2), StateFlowStatus.endOfFlow)
        val list4 = MyList(listOf(1, 2), StateFlowStatus.retrying(Exception("Foo")))

        // Act/Assert
        assertEquals(list1.hashCode(), list1.hashCode())
        assertNotEquals(list1.hashCode(), list2.hashCode())
        assertNotEquals(list1.hashCode(), list3.hashCode())
        assertNotEquals(list1.hashCode(), list4.hashCode())
    }

    private class MyList(list: List<Int>, flowStatus: StateFlowStatus)
        : ListWithStateFlowStatus<Int>(list, flowStatus)
}
