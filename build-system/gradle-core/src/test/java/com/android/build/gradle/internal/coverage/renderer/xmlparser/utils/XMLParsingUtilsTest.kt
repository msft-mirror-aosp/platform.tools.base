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

package com.android.build.gradle.internal.coverage.renderer.xmlparser.utils

import com.android.build.gradle.internal.coverage.renderer.xmlparser.utils.calculatePercent
import com.android.build.gradle.internal.coverage.renderer.xmlparser.utils.findProperty
import com.google.common.truth.Truth
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element
import org.xml.sax.InputSource

class XMLParsingUtilsTest {

    @Test
    fun `calculatePercent with zero total returns zero`() {
        Truth.assertThat(calculatePercent(covered = 50, total = 0)).isEqualTo(0)
    }

    @Test
    fun `calculatePercent with zero covered returns zero`() {
        Truth.assertThat(calculatePercent(covered = 0, total = 100)).isEqualTo(0)
    }

    @Test
    fun `calculatePercent with valid inputs returns correct percentage`() {
        Truth.assertThat(calculatePercent(covered = 50, total = 100)).isEqualTo(50)
        Truth.assertThat(calculatePercent(covered = 10, total = 20)).isEqualTo(50)
        Truth.assertThat(calculatePercent(covered = 33, total = 99)).isEqualTo(33)
    }

    @Test
    fun `calculatePercent handles integer division correctly`() {
        Truth.assertThat(calculatePercent(covered = 33, total = 100)).isEqualTo(33)
        Truth.assertThat(calculatePercent(covered = 1, total = 3)).isEqualTo(33)
    }

    @Test
    fun `findProperty with null node returns null`() {
        Truth.assertThat(findProperty(null, "someKey")).isNull()
    }

    @Test
    fun `findProperty with existing key returns correct value`() {
        val propertiesNode = createPropertiesElement("""
            <properties>
                <property name="otherKey" value="otherValue"/>
                <property name="targetKey" value="targetValue"/>
            </properties>
        """)
        Truth.assertThat(findProperty(propertiesNode, "targetKey")).isEqualTo("targetValue")
    }

    @Test
    fun `findProperty with missing key returns null`() {
        val propertiesNode = createPropertiesElement("""
            <properties>
                <property name="otherKey" value="otherValue"/>
            </properties>
        """)
        Truth.assertThat(findProperty(propertiesNode, "nonExistentKey")).isNull()
    }

    @Test
    fun `findProperty with empty properties node returns null`() {
        val propertiesNode = createPropertiesElement("<properties/>")
        Truth.assertThat(findProperty(propertiesNode, "anyKey")).isNull()
    }

    private fun createPropertiesElement(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val inputSource = InputSource(StringReader(xml))
        val doc = builder.parse(inputSource)
        return doc.documentElement
    }
}
