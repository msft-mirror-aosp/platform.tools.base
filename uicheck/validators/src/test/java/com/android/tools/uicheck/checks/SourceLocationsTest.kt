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

package com.android.tools.uicheck.checks

import com.android.tools.uicheck.ProjectSourceFile
import com.android.tools.uicheck.snapshot.UiCheckSourceLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceLocationsTest {

  private val projectFiles =
    listOf(
      ProjectSourceFile("LoginScreen.kt", "com.example"),
      ProjectSourceFile("Button.kt", "com.example.ui"),
    )
  private val loginButton = UiCheckSourceLocation("LoginScreen.kt", 42, "Button")

  /** Verifies that a node directly in user code resolves to its own source call site. */
  @Test
  fun usesNodesOwnCallSiteInUserCode() {
    val button = node("Button", bounds(0, 100, 1080, 220), sourceLoc("LoginScreen.kt", 42, "com.example"))

    assertEquals(
      loginButton,
      SourceLocations.resolve(listOf(button), UserCodeFilter(projectFiles))[button],
    )
  }

  /** Verifies that an internal library node inherits the nearest enclosing user call site. */
  @Test
  fun libraryNodeInheritsNearestUserAncestor() {
    // The project also has a Button.kt, but Material's Button.kt is in another package.
    val surface =
      node(
        "Surface",
        bounds(0, 100, 1080, 220),
        sourceLoc("Button.kt", 125, "androidx.compose.material3"),
      )
    val button =
      node(
        "Button",
        bounds(0, 100, 1080, 220),
        sourceLoc("LoginScreen.kt", 42, "com.example"),
        children = listOf(surface),
      )

    assertEquals(
      loginButton,
      SourceLocations.resolve(listOf(button), UserCodeFilter(projectFiles))[surface],
    )
  }

  /** Verifies package-hash disambiguation when user code shares a file name with a library file. */
  @Test
  fun userFileWithSameNameAsLibraryFileIsUserCode() {
    val myButton = node("MyButton", bounds(0, 0, 100, 100), sourceLoc("Button.kt", 7, "com.example.ui"))

    assertEquals(
      UiCheckSourceLocation("Button.kt", 7, "MyButton"),
      SourceLocations.resolve(listOf(myButton), UserCodeFilter(projectFiles))[myButton],
    )
  }

  /** Verifies fallback matching against the preview method's package when project source roots are unavailable. */
  @Test
  fun withoutProjectSourcesOnlyPreviewPackageIsUserCode() {
    val text = node("Text", bounds(0, 0, 100, 20), sourceLoc("Text.kt", 80, "androidx.compose.material3"))
    val column =
      node(
        "Column",
        bounds(0, 0, 100, 100),
        sourceLoc("LoginScreen.kt", 30, "com.example"),
        children = listOf(text),
      )
    val userCode = UserCodeFilter(emptyList(), previewMethodFqn = "com.example.LoginScreenKt.LoginPreview")

    assertEquals(
      UiCheckSourceLocation("LoginScreen.kt", 30, "Column"),
      SourceLocations.resolve(listOf(column), userCode)[text],
    )
  }

  /** Verifies that ancestors above user code return `null` while unattributed sibling leaves fall back to the first user call site. */
  @Test
  fun ancestorsAboveUserCodeReturnNullWhileSiblingLeavesFallBackToFirstUserCallSite() {
    val text = node("Text", bounds(0, 0, 100, 20), sourceLoc("LoginScreen.kt", 50, "com.example"))
    val spacer = node("Spacer", bounds(0, 20, 100, 40))
    val theme =
      node(
        "MaterialTheme",
        bounds(0, 0, 100, 100),
        sourceLoc("MaterialTheme.kt", 90, "androidx.compose.material3"),
        children = listOf(text, spacer),
      )
    val locations = SourceLocations.resolve(listOf(theme), UserCodeFilter(projectFiles))

    val firstUserCallSite = UiCheckSourceLocation("LoginScreen.kt", 50, "Text")
    assertNull(locations[theme])
    assertEquals(firstUserCallSite, locations[spacer])
  }

  /** Verifies that a tree with no user code resolves all nodes to `null`, and [SourceLocations.EMPTY] returns `null`. */
  @Test
  fun treeWithoutUserCodeAndEmptyLocationsReturnNull() {
    val libText = node("Text", bounds(0, 0, 100, 20), sourceLoc("Text.kt", 80, "androidx.compose.material3"))
    val libBox =
      node(
        "Box",
        bounds(0, 0, 100, 100),
        sourceLoc("Box.kt", 40, "androidx.compose.foundation.layout"),
        children = listOf(libText),
      )
    val locations = SourceLocations.resolve(listOf(libBox), UserCodeFilter(projectFiles))

    assertNull(locations[libBox])
    assertNull(locations[libText])
    assertNull(SourceLocations.EMPTY[libBox])
  }

  /** Verifies that distinct node instances with equal data-class fields resolve to their own enclosing user call sites. */
  @Test
  fun distinguishesIdenticalDataClassNodesByIdentity() {
    val leaf1 = node("Text", bounds(0, 0, 100, 20))
    val leaf2 = node("Text", bounds(0, 0, 100, 20))
    val row1 =
      node(
        "Row",
        bounds(0, 0, 100, 20),
        sourceLoc("LoginScreen.kt", 10, "com.example"),
        children = listOf(leaf1),
      )
    val row2 =
      node(
        "Row",
        bounds(0, 20, 100, 40),
        sourceLoc("LoginScreen.kt", 25, "com.example"),
        children = listOf(leaf2),
      )
    val locations = SourceLocations.resolve(listOf(row1, row2), UserCodeFilter(projectFiles))

    assertEquals(UiCheckSourceLocation("LoginScreen.kt", 10, "Row"), locations[leaf1])
    assertEquals(UiCheckSourceLocation("LoginScreen.kt", 25, "Row"), locations[leaf2])
  }
}
