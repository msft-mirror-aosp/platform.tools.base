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

package com.android.tools.journeys.testengine.robo

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import java.io.ByteArrayInputStream

class RoboConverterTest {

    @Test
    fun `convert actions and assertions`() {
        val xml = """
            <journey>
                <actions>
                    <action>Type test</action>
                    <action>Assert that field is empty</action>
                    <action>Click Next</action>
                </actions>
            </journey>
        """.trimIndent()
        val inputStream = ByteArrayInputStream(xml.toByteArray())

        val expectedJson = """
            "roboscript": {
              "executionMode": {
                "strict": true
              },
              "postscript": {
                "terminate": true
              }
            }
            [{
              "crawlStage": "crawl",
              "contextDescriptor": {
                "condition": "app_under_test_shown"
              },
              "maxNumberOfRuns": 1,
              "actions": [{
              "eventType": "PROMPT",
              "prompt": "Type test"
            },{
              "eventType": "ASSERTION",
              "contextDescriptor": {
                "condition": "prompt",
                "prompt": "Assert that field is empty"
              }
            },{
              "eventType": "PROMPT",
              "prompt": "Click Next"
            },  ]
            }]
        """.trimIndent()
        val actualJson = RoboConverter.convert(inputStream)
        assertEquals(expectedJson, actualJson)
    }

    @Test
    fun `convert throws error if actions element is missing`() {
        val xml = "<journey></journey>"
        val inputStream = ByteArrayInputStream(xml.toByteArray())
        val exception = assertThrows(IllegalStateException::class.java) {
            RoboConverter.convert(inputStream)
        }
        assertEquals("There should be one and only one <actions> element", exception.message)
    }

    @Test
    fun `convert throws error for unknown tag inside actions`() {
        val xml = """
            <journey>
                <actions>
                    <action>Valid action</action>
                    <unknownTag>This is not allowed</unknownTag>
                </actions>
            </journey>
        """.trimIndent()
        val inputStream = ByteArrayInputStream(xml.toByteArray())
        val exception = assertThrows(IllegalStateException::class.java) {
            RoboConverter.convert(inputStream)
        }
        assertEquals("Unknown tag: unknownTag", exception.message)
    }

    @Test
    fun `getRoboElements returns correct elements`() {
        val xml = """
            <journey>
                <actions>
                    <action>Action 1</action>
                    <action>Action 2</action>
                    NotAnElementNode
                </actions>
            </journey>
        """.trimIndent()
        val inputStream = ByteArrayInputStream(xml.toByteArray())
        val elements = RoboConverter.getRoboElements(inputStream)

        assertEquals(2, elements.size)
        assertEquals("action", elements[0].tagName)
        assertEquals("Action 1", elements[0].textContent.trim())
        assertEquals("action", elements[1].tagName)
        assertEquals("Action 2", elements[1].textContent.trim())
    }

    @Test
    fun `correctly identifies assertion keywords`() {
        val xmlAction =
            """<journey><actions><action>perform click</action></actions></journey>""".trimIndent()
        val xmlAssertionVerify =
            """<journey><actions><action>Verify this</action></actions></journey>""".trimIndent()
        val xmlAssertionAssert =
            """<journey><actions><action>assert That</action></actions></journey>""".trimIndent()

        val actionJson = RoboConverter.convert(ByteArrayInputStream(xmlAction.toByteArray()))
        val assertionVerifyJson =
            RoboConverter.convert(ByteArrayInputStream(xmlAssertionVerify.toByteArray()))
        val assertionAssertJson =
            RoboConverter.convert(ByteArrayInputStream(xmlAssertionAssert.toByteArray()))

        assertTrue(actionJson.contains("\"eventType\": \"PROMPT\""))
        assertTrue(assertionVerifyJson.contains("\"eventType\": \"ASSERTION\""))
        assertTrue(assertionAssertJson.contains("\"eventType\": \"ASSERTION\""))
    }
}
