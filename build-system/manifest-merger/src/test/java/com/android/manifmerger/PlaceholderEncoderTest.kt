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

package com.android.manifmerger

import com.android.utils.PositionXmlParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.w3c.dom.Element

/** Tests for [PlaceholderEncoder] */
class PlaceholderEncoderTest {

  @Test
  fun testPlaceHolderEncoding() {
    test("\${applicationId}", "dollar_openBracket_applicationId_closeBracket")
    test("prefix\${applicationId}", "prefixdollar_openBracket_applicationId_closeBracket")
    test("\${applicationId}suffix", "dollar_openBracket_applicationId_closeBracketsuffix")
    test("prefix\${applicationId}suffix", "prefixdollar_openBracket_applicationId_closeBracketsuffix")
  }

  @Test
  fun testPathPlaceHolderEncoding() {
    val xml =
      """
      |<?xml version="1.0" encoding="utf-8"?>
      |<manifest xmlns:android="http://schemas.android.com/apk/res/android">
      |
      |    <application>
      |        <activity android:name=".HelloWorld"
      |                  android:label="@string/app_name">
      |            <intent-filter>
      |                <action android:name="android.intent.action.VIEW"/>
      |                <category android:name="android.intent.category.DEFAULT"/>
      |                <category android:name="android.intent.category.BROWSABLE"/>
      |                <data android:scheme="https"
      |                      android:host="example.com"
      |                      android:path="${'$'}{myPath}"/>
      |            </intent-filter>
      |        </activity>
      |    </application>
      |
      |</manifest>
      """
        .trimMargin()
    val document = PositionXmlParser.parse(xml)

    visit(document.documentElement)
    val dataNodes = document.getElementsByTagName("data")
    assertThat(dataNodes.length).isEqualTo(1)
    val data = dataNodes.item(0)
    val path = data.attributes.getNamedItem("android:path")
    assertThat(path.nodeValue).isEqualTo("/dollar_openBracket_myPath_closeBracket")
  }

  private fun test(originalValue: String, expectedValue: String) {
    val xml =
      """
      |<?xml version="1.0" encoding="utf-8"?>
      |<manifest xmlns:android="http://schemas.android.com/apk/res/android">
      |
      |    <application>
      |        <provider
      |            android:authorities="$originalValue"/>
      |    </application>
      |
      |</manifest>
      """
        .trimMargin()
    val document = PositionXmlParser.parse(xml)

    visit(document.documentElement)
    val providers = document.getElementsByTagName("provider")
    assertThat(providers.length).isEqualTo(1)
    val provider = providers.item(0)
    val authorities = provider.attributes.getNamedItem("android:authorities")
    assertThat(authorities.nodeValue).isEqualTo(expectedValue)
  }

  private fun visit(element: Element) {
    PlaceholderEncoder.encode(element)
    val childNodes = element.childNodes
    for (i in 0 until childNodes.length) {
      val child = childNodes.item(i)
      if (child is Element) {
        visit(child)
      }
    }
  }
}
