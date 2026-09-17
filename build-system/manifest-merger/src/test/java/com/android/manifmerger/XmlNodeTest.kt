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

package com.android.manifmerger

import com.android.SdkConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.w3c.dom.Element
import org.w3c.dom.Node

/** Tests for the [XmlNode] class */
class XmlNodeTest {

  private val nodeOne = mock<Node>()
  private val nodeTwo = mock<Node>()
  private val element = mock<Element>()

  @Test
  fun noNamespace() {
    whenever(nodeOne.nodeName).thenReturn("my-name")

    val nodeName = XmlNode.unwrapName(nodeOne)
    assertFalse(nodeName.isInNamespace(SdkConstants.ANDROID_URI))
    assertEquals("my-name", nodeName.toString())
  }

  @Test
  fun noNamespaceEqualityAndHashCoding() {
    whenever(nodeOne.nodeName).thenReturn("my-name")
    whenever(nodeTwo.nodeName).thenReturn("my-name")

    assertEquals(XmlNode.unwrapName(nodeOne), XmlNode.unwrapName(nodeTwo))
    assertEquals(XmlNode.unwrapName(nodeOne).hashCode(), XmlNode.unwrapName(nodeTwo).hashCode())
  }

  @Test
  fun noNamespaceInequalityAndHashCoding() {
    whenever(nodeOne.nodeName).thenReturn("my-name")
    whenever(nodeTwo.nodeName).thenReturn("my-other-name")

    assertNotSame(XmlNode.unwrapName(nodeOne), XmlNode.unwrapName(nodeTwo))
    assertNotSame(XmlNode.unwrapName(nodeOne).hashCode(), XmlNode.unwrapName(nodeTwo).hashCode())
  }

  @Test
  fun namespace() {
    whenever(nodeOne.namespaceURI).thenReturn(SdkConstants.ANDROID_URI)
    whenever(nodeOne.localName).thenReturn("my-name")
    whenever(nodeOne.prefix).thenReturn("android")

    val nodeName = XmlNode.unwrapName(nodeOne)
    assertTrue(nodeName.isInNamespace(SdkConstants.ANDROID_URI))
    assertEquals("android:my-name", nodeName.toString())
  }

  @Test
  fun namespaceEqualityAndHashCoding() {
    // different local name.
    whenever(nodeOne.namespaceURI).thenReturn(SdkConstants.ANDROID_URI)
    whenever(nodeOne.localName).thenReturn("my-name")
    whenever(nodeOne.prefix).thenReturn("android")

    whenever(nodeTwo.namespaceURI).thenReturn(SdkConstants.ANDROID_URI)
    whenever(nodeTwo.localName).thenReturn("my-name")
    whenever(nodeTwo.prefix).thenReturn("android")

    assertEquals(XmlNode.unwrapName(nodeOne), XmlNode.unwrapName(nodeTwo))
    assertEquals(XmlNode.unwrapName(nodeOne).hashCode(), XmlNode.unwrapName(nodeTwo).hashCode())

    // different namespace.
    whenever(nodeOne.namespaceURI).thenReturn(SdkConstants.ANDROID_URI)
    whenever(nodeOne.localName).thenReturn("my-name")
    whenever(nodeOne.prefix).thenReturn("android")

    whenever(nodeTwo.namespaceURI).thenReturn(SdkConstants.ANDROID_URI)
    whenever(nodeTwo.localName).thenReturn("my-name")
    // another prefix should not matter, they are still the same xml names.
    whenever(nodeTwo.prefix).thenReturn("y")

    assertEquals(XmlNode.unwrapName(nodeOne), XmlNode.unwrapName(nodeTwo))
    assertEquals(XmlNode.unwrapName(nodeOne).hashCode(), XmlNode.unwrapName(nodeTwo).hashCode())
  }

  @Test
  fun namespaceInequality() {
    whenever(nodeOne.namespaceURI).thenReturn(SdkConstants.ANDROID_URI)
    whenever(nodeOne.localName).thenReturn("my-name")
    whenever(nodeOne.prefix).thenReturn("android")

    whenever(nodeTwo.namespaceURI).thenReturn(SdkConstants.ANDROID_URI)
    whenever(nodeTwo.localName).thenReturn("my-other-name")
    whenever(nodeTwo.prefix).thenReturn("android")

    assertNotSame(XmlNode.unwrapName(nodeOne), XmlNode.unwrapName(nodeTwo))
    assertNotSame(XmlNode.unwrapName(nodeOne).hashCode(), XmlNode.unwrapName(nodeTwo).hashCode())

    whenever(nodeOne.namespaceURI).thenReturn(SdkConstants.ANDROID_URI)
    whenever(nodeOne.localName).thenReturn("my-name")
    whenever(nodeOne.prefix).thenReturn("android")

    whenever(nodeTwo.namespaceURI).thenReturn(SdkConstants.TOOLS_URI)
    whenever(nodeTwo.localName).thenReturn("my-name")
    whenever(nodeTwo.prefix).thenReturn("android")

    assertNotSame(XmlNode.unwrapName(nodeOne), XmlNode.unwrapName(nodeTwo))
    assertNotSame(XmlNode.unwrapName(nodeOne).hashCode(), XmlNode.unwrapName(nodeTwo).hashCode())
  }

  @Test
  fun addAttributeToNode() {
    whenever(nodeOne.nodeName).thenReturn("my-name")

    val nodeName = XmlNode.unwrapName(nodeOne)
    nodeName.addToNode(element, "my-value")
    verify(element).setAttribute("my-name", "my-value")
    verifyNoMoreInteractions(element)
  }

  @Test
  fun addNamespaceAwareAttributeToNode() {
    whenever(nodeOne.namespaceURI).thenReturn(SdkConstants.ANDROID_URI)
    whenever(nodeOne.localName).thenReturn("my-name")
    whenever(nodeOne.prefix).thenReturn("android")

    val nodeName = XmlNode.unwrapName(nodeOne)
    nodeName.addToNode(element, "my-value")
    verify(element).setAttributeNS(SdkConstants.ANDROID_URI, "android:my-name", "my-value")
    verifyNoMoreInteractions(element)
  }
}
