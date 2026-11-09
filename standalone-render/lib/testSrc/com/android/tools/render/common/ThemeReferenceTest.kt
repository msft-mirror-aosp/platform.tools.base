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
import org.junit.Assert.assertThrows
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
    assertEquals("@style/Theme.Other", toThemeReference("@style/Theme.Other", appResources))
    assertEquals("@android:style/Theme.Material", toThemeReference("@android:style/Theme.Material", appResources))
    assertEquals("@com.example.lib:style/Theme.Lib", toThemeReference("@com.example.lib:style/Theme.Lib", appResources))
    assertEquals("@null", toThemeReference("@null", appResources))
    assertEquals("?attr/myTheme", toThemeReference("?attr/myTheme", appResources))
    assertEquals("?myTheme", toThemeReference("?myTheme", appResources))
    assertEquals("?android:attr/dialogTheme", toThemeReference(" ?android:attr/dialogTheme ", appResources))
    assertEquals("?android:dialogTheme", toThemeReference("?android:dialogTheme", appResources))
  }

  @Test
  fun projectStyles_referToTheProjectStyle() {
    assertEquals("@style/Theme.MyApp", toThemeReference("Theme.MyApp", appResources))
    assertEquals("@style/GreenAccent", toThemeReference("GreenAccent", appResources))
    assertEquals("@style/Theme.MyApp", toThemeReference(" Theme.MyApp\n", appResources))
  }

  @Test
  fun otherStyles_referToTheFrameworkStyle() {
    assertEquals("@android:style/Theme.Material", toThemeReference("Theme.Material", appResources))
    assertEquals("@android:style/Theme.Material.Light", toThemeReference(" Theme.Material.Light ", appResources))
    // A misspelled project style is a framework style too.
    assertEquals("@android:style/Theme.MyAp", toThemeReference("Theme.MyAp", appResources))
  }

  @Test
  fun androidPrefix_refersToTheFrameworkStyle() {
    assertEquals("@android:style/Theme.Material", toThemeReference("android:Theme.Material", appResources))
    assertEquals("@android:style/Theme.Material", toThemeReference("android:style/Theme.Material", appResources))
    assertEquals("@android:style/Theme.MyApp", toThemeReference(" android:Theme.MyApp ", appResources))
    assertEquals("@android:style/Theme.MyApp", toThemeReference("android:style/Theme.MyApp", appResources))
    assertEquals("@style/Theme.Other", toThemeReference("style/Theme.Other", appResources))
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
        "?android:",
        "?android:attr/",
        "?:myTheme",
        "?style/Theme.MyApp",
        "@color/accent",
        "@+style/Theme.MyApp",
        "@*android:style/Theme.Material",
        "android:",
        "android:style/",
        "android:style/style/Theme.Material",
        "android:color/white",
        "style/",
        "style/style/Theme.MyApp",
        "com.example.lib:Theme.Lib",
        "Theme MyApp",
      )) {
      assertThrows("Expected '$invalid' to fail", IllegalArgumentException::class.java) { toThemeReference(invalid, appResources) }
    }
  }
}
