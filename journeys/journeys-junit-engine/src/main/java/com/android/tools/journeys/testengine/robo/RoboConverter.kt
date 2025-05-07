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

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.InputStream
import java.io.StringWriter
import java.io.Writer
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Converts journey XML files into Robo JSON scripts.
 */
object RoboConverter {

    /**
     * Converts an InputStream containing journey XML data to a Robo JSON script string.
     *
     * @param journey The InputStream containing the journey XML data.
     * @return The Robo JSON script as a string.
     * @throws IllegalArgumentException If the XML structure is invalid.
     */
    fun convert(journey: InputStream): String {
        return StringWriter().also { convertXmlDoc(journey, it) }.toString()
    }

    /**
     * Retrieves the child elements of the `<actions>` element in the XML document.
     *
     * @param journey The InputStream containing the journey XML data.
     * @return A list of child elements within the `<actions>` element.
     * @throws IllegalStateException If the `<actions>` element is not found.
     */
    fun getRoboElements(journey: InputStream): List<Element> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(journey)
        val actionsElement = document.getElementsByTagName("actions").item(0) as? Element
            ?: throw IllegalStateException("There should be one and only one <actions> element")

        return actionsElement.childNodes.toList().mapNotNull { it as? Element }
    }

    /**
     * Converts a parsed XML document to Robo JSON, writing the output to a StringWriter.
     *
     * @param journey The InputStream containing the journey XML data.
     * @param writer The StringWriter to write the JSON output to.
     * @throws IllegalStateException If the XML structure is invalid.
     */
    private fun convertXmlDoc(journey: InputStream, writer: StringWriter) {
        writer.write(jsonHeader)

        val actionsElement = getRoboElements(journey)

        actionsElement.forEach { element ->
            if (element.tagName != "action") {
                throw IllegalStateException("Unknown tag: ${element.tagName}")
            }
            val text = element.textContent.trim().replace(Regex("\\s+"), " ").toJsonStringLiteral()
            if (isAssertion(element)) {
                writer.addJsonAssertion(text)
            } else {
                writer.addJsonAction(text)
            }
        }

        writer.write(jsonFooter)
    }

    private fun isAssertion(element: Element): Boolean {
        return assertionKeywords.any { element.textContent.lowercase().startsWith("$it") }
    }

    /**
     * Appends a JSON action entry to the Writer.
     *
     * @param textContent The text content of the action.
     */
    private fun Writer.addJsonAction(textContent: String) =
        write(actionEntry.replace("\"%CONTENT%\"", textContent))

    /**
     * Appends a JSON assertion entry to the Writer.
     *
     * @param textContent The text content of the assertion.
     */
    private fun Writer.addJsonAssertion(textContent: String) =
        write(assertionEntry.replace("\"%CONTENT%\"", textContent))

    /**
     * Converts a NodeList to a standard Kotlin List<Node>.
     */
    private fun NodeList.toList(): List<Node> = (0 until length).map { item(it) }

    /**
     * Creates a JSON string literal from a string, escaping special characters as necessary.
     */
    private fun String.toJsonStringLiteral(): String = buildString {
        append('"')
        for (c in this@toJsonStringLiteral) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                in '\u0000'..'\u001F' -> {
                    append("\\u")
                    val hex = c.code.toString(16)
                    repeat(4 - hex.length) { append('0') }
                    append(hex.uppercase())
                }

                else -> append(c)
            }
        }
        append('"')
    }

    private val jsonHeader =
        """
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
          "actions": [
    """.trimIndent()

    private val jsonFooter =
        """
          ]
        }]
    """.trimIndent()

    private val actionEntry =
        """
          {
            "eventType": "PROMPT",
            "prompt": "%CONTENT%"
          },
    """.trimIndent()

    private val assertionEntry =
        """
          {
            "eventType": "ASSERTION",
            "contextDescriptor": {
              "condition": "prompt",
              "prompt": "%CONTENT%"
            }
          },
    """.trimIndent()

    private val assertionKeywords = listOf("verify", "assert", "check that")
}
