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

import com.android.tools.journeys.testengine.JourneysTestEngineInput
import java.io.InputStream
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList

/** Converts journey XML files into Robo JSON scripts. */
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
   * Obtains a list of all prompts from a journey input stream.
   *
   * @param journey The InputStream containing the journey XML data.
   * @return The ordered list of all prompts in the journey.
   * @throws IllegalArgumentException If the XML structure is invalid.
   */
  fun getPrompts(journey: InputStream): List<String> {
    val actionElements = getRoboElements(journey)
    return actionElements.map { element ->
      if (element.tagName != "action") {
        throw IllegalStateException("Unknown tag: ${element.tagName}")
      }
      element.textContent.trim()
    }
  }

  /**
   * Retrieves the child elements of the `<actions>` element in the XML document.
   *
   * @param journey The InputStream containing the journey XML data.
   * @return A list of child elements within the `<actions>` element.
   * @throws IllegalStateException If the expected `<journey>` or `<actions>` element is not found.
   */
  fun getRoboElements(journey: InputStream): List<Element> {
    val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(journey)
    val journeyElement = document.documentElement
    if (journeyElement.tagName != "journey") {
      throw IllegalStateException("The root element must be <journey>, but found <${journeyElement.tagName}>.")
    }
    val childElements = journeyElement.childNodes.toList().mapNotNull { it as? Element }

    val actionsElements = childElements.filter { it.tagName == "actions" }
    val descriptionElements = childElements.filter { it.tagName == "description" }

    if (actionsElements.size != 1) {
      throw IllegalStateException("The <journey> element must have exactly one <actions> element, but found ${actionsElements.size}.")
    }

    if (descriptionElements.size > 1) {
      throw IllegalStateException(
        "The <journey> element can have at most one <description> element, but found ${descriptionElements.size}."
      )
    }

    if (childElements.size != actionsElements.size + descriptionElements.size) {
      val unknownElements = childElements.filter { it.tagName != "actions" && it.tagName != "description" }
      throw IllegalStateException(
        "The <journey> element contains unexpected child elements: ${unknownElements.joinToString { "<${it.tagName}>" }}"
      )
    }

    val actionsElement = actionsElements.first()
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

    if (actionsElement.isEmpty()) {
      throw IllegalStateException("No actions found in the journey.")
    }

    val actions =
      actionsElement.map { element ->
        if (element.tagName != "action") {
          throw IllegalStateException("Unknown tag: ${element.tagName}")
        }
        if (element.textContent.isBlank()) {
          throw IllegalStateException("Action text cannot be empty.")
        }
        val text = element.textContent.trim().replace(Regex("\\s+"), " ").toJsonStringLiteral()
        actionEntry(text)
      }
    writer.write(actions.joinToString(separator = ","))

    writer.write(jsonFooter)
  }

  /** Converts a NodeList to a standard Kotlin List<Node>. */
  private fun NodeList.toList(): List<Node> = (0 until length).map { item(it) }

  /** Creates a JSON string literal from a string, escaping special characters as necessary. */
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
    """
      .trimIndent()

  private val jsonFooter =
    """
      ]
    }]
    """
      .trimIndent()

  private fun actionEntry(content: String): String {
    val agentName = JourneysTestEngineInput.RoboConverterInput.agentName
    return buildString {
      appendLine("{")
      appendLine("  \"eventType\": \"AI_AGENT\",")
      appendLine("  \"aiAgentInstructions\": {")
      append("    \"goal\": $content")
      if (JourneysTestEngineInput.RoboConverterInput.enableExperimentalMode) {
        appendLine(",")
        appendLine("    \"experimental\": \"true\"")
      } else {
        appendLine()
      }
      append("  }")
      if (agentName.isNotBlank()) {
        appendLine(",")
        appendLine("  \"aiAgentName\": \"$agentName\"")
      } else {
        appendLine()
      }
      append("}")
    }
  }
}
