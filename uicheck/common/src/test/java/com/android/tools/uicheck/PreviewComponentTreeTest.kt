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

package com.android.tools.uicheck

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PreviewComponentTreeTest {

  /** Verifies JSON deserialization of [PreviewComponentTree] with full metadata, nested children, and [ComponentBounds]. */
  @Test
  fun readsComponentTreeJsonWithNestedChildrenAndAllProperties() {
    val json =
      """
      {
        "previewId": "com.example.AppKt.GreetingPreview_0",
        "methodFQN": "com.example.AppKt.GreetingPreview",
        "displayName": "GreetingPreview",
        "width": 1080,
        "height": 2340,
        "densityDpi": 420,
        "fontScale": 1.0,
        "uiMode": 0,
        "deviceId": "spec:width=411dp,height=891dp",
        "components": [
          {
            "name": "Column",
            "bounds": { "left": 0, "top": 0, "right": 1080, "bottom": 2340, "width": 1080, "height": 2340 },
            "sourceLocation": { "fileName": "App.kt", "lineNumber": 30, "packageHash": 12345 },
            "modifiers": { "padding": "16dp" },
            "children": [
              {
                "name": "Button",
                "bounds": { "left": 10, "top": 20, "right": 310, "bottom": 140, "width": 300, "height": 120 },
                "sourceLocation": { "fileName": "App.kt", "lineNumber": 42 },
                "semantics": { "role": "Button", "text": "Submit" },
                "parameters": { "enabled": "true" }
              }
            ]
          }
        ]
      }
      """
        .trimIndent()

    val deserialized = PreviewComponentTreeParser.parse(StringReader(json))

    assertEquals("com.example.AppKt.GreetingPreview_0", deserialized.previewId)
    assertEquals("com.example.AppKt.GreetingPreview", deserialized.methodFqn)
    assertEquals("GreetingPreview", deserialized.displayName)
    assertEquals(1080, deserialized.width)
    assertEquals(2340, deserialized.height)
    assertEquals(420, deserialized.densityDpi)
    assertEquals(1.0f, deserialized.fontScale)
    assertEquals(0, deserialized.uiMode)
    assertEquals("spec:width=411dp,height=891dp", deserialized.deviceId)

    val root = deserialized.components.single()
    assertEquals("Column", root.name)
    assertEquals(1080, root.bounds.width)
    assertEquals(2340, root.bounds.height)
    assertEquals("App.kt", root.sourceLocation?.fileName)
    assertEquals(30, root.sourceLocation?.lineNumber)
    assertEquals(12345, root.sourceLocation?.packageHash)
    assertEquals(mapOf("padding" to "16dp"), root.modifiers)

    val child = root.children?.single()!!
    assertEquals("Button", child.name)
    assertEquals(300, child.bounds.width)
    assertEquals(120, child.bounds.height)
    assertEquals("App.kt", child.sourceLocation?.fileName)
    assertEquals(42, child.sourceLocation?.lineNumber)
    assertNull(child.sourceLocation?.packageHash)
    assertEquals(mapOf("role" to "Button", "text" to "Submit"), child.semantics)
    assertEquals(mapOf("enabled" to "true"), child.parameters)
  }

  /** Verifies that [ComponentBounds] computes width and height when omitted from the JSON payload. */
  @Test
  fun computesComponentBoundsDimensionsWhenOmittedFromJson() {
    val json =
      """
      {
        "previewId": "p0",
        "methodFQN": "com.example.AppKt.Preview",
        "width": 400,
        "height": 800,
        "components": [
          {
            "name": "Box",
            "bounds": { "left": 10, "top": 20, "right": 60, "bottom": 120 }
          }
        ]
      }
      """
        .trimIndent()
    val parsed = PreviewComponentTreeParser.parse(StringReader(json))
    val bounds = parsed.components.single().bounds
    assertEquals(50, bounds.width)
    assertEquals(100, bounds.height)
  }

  /** Verifies that [PreviewComponentTreeParser.parse] accepts alternate `"methodFqn"` casing. */
  @Test
  fun acceptsAlternateMethodFqnCasing() {
    val alternateJson =
      """
      {
        "previewId": "p0",
        "methodFqn": "com.example.AppKt.Preview",
        "width": 400,
        "height": 800,
        "components": []
      }
      """
        .trimIndent()
    val parsed = PreviewComponentTreeParser.parse(StringReader(alternateJson))
    assertEquals("com.example.AppKt.Preview", parsed.methodFqn)
  }

  /** Verifies rejection of empty or null JSON inputs. */
  @Test
  fun rejectsEmptyOrNullJson() {
    assertThrows(IllegalArgumentException::class.java) {
      PreviewComponentTreeParser.parse(StringReader(""))
    }
    assertThrows(IllegalArgumentException::class.java) {
      PreviewComponentTreeParser.parse(StringReader("null"))
    }
  }

  @Test
  fun rejectsMalformedJson() {
    val exception =
      assertThrows(IllegalArgumentException::class.java) {
        PreviewComponentTreeParser.parse(StringReader("{ invalid json"))
      }
    assertEquals("Malformed component tree JSON", exception.message)
  }

  /** Verifies rejection when top-level required fields are missing. */
  @Test
  fun rejectsMissingTopLevelFields() {
    // Missing previewId
    val missingPreviewId =
      assertThrows(IllegalArgumentException::class.java) {
        PreviewComponentTreeParser.parse(StringReader("""{"methodFQN":"m","width":10,"height":10,"components":[]}"""))
      }
    assertEquals("Missing required field: previewId", missingPreviewId.message)

    // Missing methodFQN
    val missingMethodFqn =
      assertThrows(IllegalArgumentException::class.java) {
        PreviewComponentTreeParser.parse(StringReader("""{"previewId":"p0","width":10,"height":10,"components":[]}"""))
      }
    assertEquals("Missing required field: methodFQN", missingMethodFqn.message)

    // Missing components
    val missingComponents =
      assertThrows(IllegalArgumentException::class.java) {
        PreviewComponentTreeParser.parse(StringReader("""{"previewId":"p0","methodFQN":"m","width":10,"height":10}"""))
      }
    assertEquals("Missing required field: components", missingComponents.message)
  }

  /** Verifies rejection when component nodes are null or missing required fields. */
  @Test
  fun rejectsInvalidComponentNodes() {
    // Null component node in list
    val nullNode =
      assertThrows(IllegalArgumentException::class.java) {
        PreviewComponentTreeParser.parse(StringReader("""{"previewId":"p0","methodFQN":"m","width":10,"height":10,"components":[null]}"""))
      }
    assertEquals("Component tree node cannot be null", nullNode.message)

    // Missing component name
    val missingName =
      assertThrows(IllegalArgumentException::class.java) {
        PreviewComponentTreeParser.parse(
          StringReader(
            """{"previewId":"p0","methodFQN":"m","width":10,"height":10,"components":[{"bounds":{"left":0,"top":0,"right":10,"bottom":10}}]}"""
          )
        )
      }
    assertEquals("Missing required field: component node 'name'", missingName.message)

    // Missing component bounds
    val missingBounds =
      assertThrows(IllegalArgumentException::class.java) {
        PreviewComponentTreeParser.parse(
          StringReader("""{"previewId":"p0","methodFQN":"m","width":10,"height":10,"components":[{"name":"Box"}]}""")
        )
      }
    assertEquals("Missing required field: component node 'bounds'", missingBounds.message)

    // Missing sourceLocation fileName
    val missingFileName =
      assertThrows(IllegalArgumentException::class.java) {
        PreviewComponentTreeParser.parse(
          StringReader(
            """{"previewId":"p0","methodFQN":"m","width":10,"height":10,"components":[{"name":"Box","bounds":{"left":0,"top":0,"right":10,"bottom":10},"sourceLocation":{"lineNumber":10}}]}"""
          )
        )
      }
    assertEquals("Missing required field: sourceLocation 'fileName'", missingFileName.message)

    // Invalid child node
    val invalidChild =
      assertThrows(IllegalArgumentException::class.java) {
        PreviewComponentTreeParser.parse(
          StringReader(
            """{"previewId":"p0","methodFQN":"m","width":10,"height":10,"components":[{"name":"Box","bounds":{"left":0,"top":0,"right":10,"bottom":10},"children":[{"name":"Child"}]}]}"""
          )
        )
      }
    assertEquals("Missing required field: component node 'bounds'", invalidChild.message)
  }
}
