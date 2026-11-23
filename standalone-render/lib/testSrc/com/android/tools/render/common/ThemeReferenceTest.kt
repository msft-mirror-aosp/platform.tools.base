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
package com.android.tools.render.common

import com.android.tools.res.FolderResourceRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// This test sorts after RendererTest in JarTestSuite and doesn't render, so it doesn't use RenderTestRule.
class ThemeReferenceTest {
  @get:Rule val tmpFolder = TemporaryFolder()

  private lateinit var appResources: FolderResourceRepository

  @Before
  fun setUp() {
    val resFolder = tmpFolder.newFolder("res")
    File(resFolder, "values")
      .apply { mkdirs() }
      .resolve("styles.xml")
      .writeText(
        """
        <resources>
            <style name="Theme.MyApp" parent="android:Theme.Material.Light" />
            <style name="GreenAccent" parent="android:Theme.Material.Light" />
        </resources>
        """
          .trimIndent()
      )
    appResources = FolderResourceRepository(resFolder)
  }

  @Test
  fun referencesAndThemeAttributes_areKept() {
    for ((input, expected) in
      listOf(
        "@style/Theme.Other" to "@style/Theme.Other",
        "@android:style/Theme.Material" to "@android:style/Theme.Material",
        "@com.example.lib:style/Theme.Lib" to "@com.example.lib:style/Theme.Lib",
        "@null" to "@null",
        "?attr/myTheme" to "?attr/myTheme",
        "?myTheme" to "?myTheme",
        " ?android:attr/dialogTheme " to "?android:attr/dialogTheme",
        "?android:dialogTheme" to "?android:dialogTheme",
      )) {
      assertTrue("Expected '$input' to have valid syntax", isValidThemeSyntax(input))
      assertEquals(expected, toThemeReference(input, appResources))
    }
  }

  @Test
  fun projectStyles_referToTheProjectStyle() {
    for ((input, expected) in
      listOf(
        "Theme.MyApp" to "@style/Theme.MyApp",
        "GreenAccent" to "@style/GreenAccent",
        " Theme.MyApp\n" to "@style/Theme.MyApp",
      )) {
      assertTrue("Expected '$input' to have valid syntax", isValidThemeSyntax(input))
      assertEquals(expected, toThemeReference(input, appResources))
    }
  }

  @Test
  fun otherStyles_referToTheFrameworkStyle() {
    for ((input, expected) in
      listOf(
        "Theme.Material" to "@android:style/Theme.Material",
        " Theme.Material.Light " to "@android:style/Theme.Material.Light",
        // A misspelled project style is a framework style too.
        "Theme.MyAp" to "@android:style/Theme.MyAp",
      )) {
      assertTrue("Expected '$input' to have valid syntax", isValidThemeSyntax(input))
      assertEquals(expected, toThemeReference(input, appResources))
    }
  }

  @Test
  fun androidPrefix_refersToTheFrameworkStyle() {
    for ((input, expected) in
      listOf(
        "android:Theme.Material" to "@android:style/Theme.Material",
        "android:style/Theme.Material" to "@android:style/Theme.Material",
        " android:Theme.MyApp " to "@android:style/Theme.MyApp",
        "android:style/Theme.MyApp" to "@android:style/Theme.MyApp",
        "style/Theme.Other" to "@style/Theme.Other",
      )) {
      assertTrue("Expected '$input' to have valid syntax", isValidThemeSyntax(input))
      assertEquals(expected, toThemeReference(input, appResources))
    }
  }

  @Test
  fun invalidReferences_areRejected() {
    for (invalid in
      listOf(
        "",
        " ",
        "@",
        "?",
        "@empty",
        "@style/",
        "@android:style/",
        "@:style/Theme.Material",
        "@style/foo:Theme.Material",
        "@style/a/b",
        "@a b:style/Theme.Material",
        "?attr/",
        "?/myTheme",
        "?android:/myTheme",
        "?android:",
        "?android:attr/",
        "?:myTheme",
        "?style/Theme.MyApp",
        "@color/accent",
        "@+style/Theme.MyApp",
        "@*android:style/Theme.Material",
        "?*android:attr/dialogTheme",
        "android:",
        "android:style/",
        "android:style/style/Theme.Material",
        "android:color/white",
        "style/",
        "style/style/Theme.MyApp",
        "com.example.lib:Theme.Lib",
        "Theme MyApp",
        "#FF0000",
        "@style/*X",
        "android:*X",
        "?[foo]",
        "@com..x:style/X",
        "@com.:style/X",
        "@style/Theme.A\"B'C&D<E>",
      )) {
      assertFalse("Expected '$invalid' to have invalid syntax", isValidThemeSyntax(invalid))
      assertThrows("Expected '$invalid' to fail", IllegalArgumentException::class.java) { toThemeReference(invalid, appResources) }
    }
  }
}
