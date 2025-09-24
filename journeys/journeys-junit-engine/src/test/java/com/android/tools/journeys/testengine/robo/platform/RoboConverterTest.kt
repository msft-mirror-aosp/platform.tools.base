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

package com.android.tools.journeys.testengine.robo.platform

import com.android.tools.journeys.testengine.robo.platform.RoboConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
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
                    <action>     Verify that field is empty</action>
                    <action> Action with spaces  </action>
                    <action> <![CDATA[Action with "special" chars like >, &, <]]></action>
                </actions>
            </journey>
        """
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
              "eventType": "AI_AGENT",
              "aiAgentInstructions": {
                "goal": "Type test",
                "hint": "If the goal describes a single action (like 'click the submit button') then the goal is complete after that single action has been taken once, as described in the list of previous actions."
              }
            },{
              "eventType": "ASSERTION",
              "contextDescriptor": {
                "condition": "prompt",
                "prompt": "Assert that field is empty"
              }
            },{
              "eventType": "AI_AGENT",
              "aiAgentInstructions": {
                "goal": "Click Next",
                "hint": "If the goal describes a single action (like 'click the submit button') then the goal is complete after that single action has been taken once, as described in the list of previous actions."
              }
            },{
              "eventType": "ASSERTION",
              "contextDescriptor": {
                "condition": "prompt",
                "prompt": "Verify that field is empty"
              }
            },{
              "eventType": "AI_AGENT",
              "aiAgentInstructions": {
                "goal": "Action with spaces",
                "hint": "If the goal describes a single action (like 'click the submit button') then the goal is complete after that single action has been taken once, as described in the list of previous actions."
              }
            },{
              "eventType": "AI_AGENT",
              "aiAgentInstructions": {
                "goal": "Action with \"special\" chars like >, &, <",
                "hint": "If the goal describes a single action (like 'click the submit button') then the goal is complete after that single action has been taken once, as described in the list of previous actions."
              }
            },  ]
            }]
        """.trimIndent()
        val actualJson = RoboConverter.convert(inputStream)
        assertEquals(expectedJson, actualJson)
    }

    @Test
    fun `convert throws error if journey element is missing`() {
        val xml = "<unknownTag></unknownTag>"
        val inputStream = ByteArrayInputStream(xml.toByteArray())
        val exception = assertThrows(IllegalStateException::class.java) {
            RoboConverter.convert(inputStream)
        }
        assertEquals(
            "The root element must be <journey>, but found <unknownTag>.",
            exception.message
        )
    }

    @Test
    fun `convert throws error for unknown tag inside journey`() {
        val xml = """
            <journey>
                <description>This is a test journey</description>
                <actions><action>Action 1</action></actions>
                <unknownTag1>This is not allowed</unknownTag1>
                <unknownTag2>This is not allowed</unknownTag2>
            </journey>
        """.trimIndent()
        val inputStream = ByteArrayInputStream(xml.toByteArray())
        val exception = assertThrows(IllegalStateException::class.java) {
            RoboConverter.convert(inputStream)
        }
        assertEquals(
            "The <journey> element contains unexpected child elements: <unknownTag1>, <unknownTag2>",
            exception.message
        )
    }

    @Test
    fun `convert throws error if actions element is missing`() {
        val xml = "<journey></journey>"
        val inputStream = ByteArrayInputStream(xml.toByteArray())
        val exception = assertThrows(IllegalStateException::class.java) {
            RoboConverter.convert(inputStream)
        }
        assertEquals(
            "The <journey> element must have exactly one <actions> element, but found 0.",
            exception.message
        )
    }

    @Test
    fun `convert throws error if multiple actions element are present`() {
        val xml = """
            <journey>
                <actions>
                    <action>Type test</action>
                </actions>
                <actions>
                    <action>Type next</action>
                </actions>
            </journey>
        """
        val inputStream = ByteArrayInputStream(xml.toByteArray())
        val exception = assertThrows(IllegalStateException::class.java) {
            RoboConverter.convert(inputStream)
        }
        assertEquals(
            "The <journey> element must have exactly one <actions> element, but found 2.",
            exception.message
        )
    }

    @Test
    fun `convert throws error if multiple description elements are present`() {
        val xml = """
            <journey>
                <actions></actions>
                <description>Description 1</description>
                <description>Description 2</description>
            </journey>
        """
        val inputStream = ByteArrayInputStream(xml.toByteArray())
        val exception = assertThrows(IllegalStateException::class.java) {
            RoboConverter.convert(inputStream)
        }
        assertEquals(
            "The <journey> element can have at most one <description> element, but found 2.",
            exception.message
        )
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
    fun `getPrompts returns correct prompts`() {
        val xml = """
            <journey>
                <actions>
                    <action>Action 1</action>
                    <action>Action 2</action>
                    <action> Action with spaces  </action>
                    <action><![CDATA[Action with "special" chars like >, &, <]]></action>
                </actions>
            </journey>
        """.trimIndent()
        val inputStream = ByteArrayInputStream(xml.toByteArray())
        val elements = RoboConverter.getPrompts(inputStream)

        assertEquals(
            elements,
            listOf(
                "Action 1",
                "Action 2",
                "Action with spaces",
                "Action with \"special\" chars like >, &, <"
            )
        )
    }

    @Test
    fun `correctly identifies assertion keywords`() {
        val xmlAction = "<journey><actions><action>perform click</action></actions></journey>"
        val xmlAssertionVerify =
            "<journey><actions><action>Verify this</action></actions></journey>"
        val xmlAssertionAssert =
            "<journey><actions><action>assert That</action></actions></journey>"
        val xmlAssertionAssertWithExtraSpaces =
            "<journey><actions><action>     Assert That</action></actions></journey>"

        val actionJson = RoboConverter.convert(ByteArrayInputStream(xmlAction.toByteArray()))
        val assertionVerifyJson =
            RoboConverter.convert(ByteArrayInputStream(xmlAssertionVerify.toByteArray()))
        val assertionAssertJson =
            RoboConverter.convert(ByteArrayInputStream(xmlAssertionAssert.toByteArray()))
        val assertionAssertWithSpaceJson =
            RoboConverter.convert(ByteArrayInputStream(xmlAssertionAssertWithExtraSpaces.toByteArray()))

        assertTrue(actionJson.contains("\"eventType\": \"AI_AGENT\""))
        assertTrue(assertionVerifyJson.contains("\"eventType\": \"ASSERTION\""))
        assertTrue(assertionAssertJson.contains("\"eventType\": \"ASSERTION\""))
        assertTrue(assertionAssertWithSpaceJson.contains("\"eventType\": \"ASSERTION\""))
    }
}
