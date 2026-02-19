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

package com.android.build.gradle.integration.library

import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import org.junit.Rule
import org.junit.Test

class AarExtractedAnnotationsTest {
  @get:Rule
  val rule =
    GradleRule.configure().from {
      androidLibrary(":lib") {
        dependencies { implementation("androidx.appcompat:appcompat:1.1.0") }
        android { namespace = "com.example.lib" }
        files {
          add(
            "src/main/java/com/example/lib/ActionBar.java",
            """
            package com.example.lib;

            import androidx.annotation.IntDef;

            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;

            public abstract class ActionBar {
                //...
                // Define the list of accepted constants and declare the NavigationMode annotation.
                @Retention(RetentionPolicy.SOURCE)
                @IntDef({NAVIGATION_MODE_STANDARD, NAVIGATION_MODE_LIST, NAVIGATION_MODE_TABS})
                public @interface NavigationMode {}

                // Declare the constants.
                public static final int NAVIGATION_MODE_STANDARD = 0;
                public static final int NAVIGATION_MODE_LIST = 1;
                public static final int NAVIGATION_MODE_TABS = 2;

                // Decorate the target methods with the annotation.
                @NavigationMode
                public abstract int getNavigationMode();

                // Attach the annotation.
                public abstract void setNavigationMode(@NavigationMode int mode);
            }
            """
              .trimIndent(),
          )
        }
        files {
          add(
            "src/main/kotlin/com/example/lib/DisplayOptions.kt",
            """
            package com.example.lib

            import android.app.ActionBar.DISPLAY_HOME_AS_UP
            import android.app.ActionBar.DISPLAY_SHOW_HOME
            import android.app.ActionBar.DISPLAY_USE_LOGO
            import androidx.annotation.IntDef

            @IntDef(flag = true, value = [
                DISPLAY_USE_LOGO,
                DISPLAY_SHOW_HOME,
                DISPLAY_HOME_AS_UP
            ])
            @Retention(AnnotationRetention.SOURCE)
            annotation class DisplayOptions
            """
              .trimIndent(),
          )
        }
      }
    }

  @Test
  fun checkAnnotationsZipFileContents() {
    rule.build.executor.run(":lib:assemble")

    rule.build.androidLibrary(":lib").assertAar(AarSelector.DEBUG) {
      innerZip("annotations.zip") {
        contains("com/example/lib/annotations.xml")
        textFile("com/example/lib/annotations.xml").isEqualTo(EXPECTED_ANNOTATIONS_XML)
      }
    }
  }

  companion object {
    val EXPECTED_ANNOTATIONS_XML =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <root>
        <item name="com.example.lib.ActionBar int getNavigationMode()">
          <annotation name="androidx.annotation.IntDef">
            <val name="value" val="{com.example.lib.ActionBar.NAVIGATION_MODE_STANDARD, com.example.lib.ActionBar.NAVIGATION_MODE_LIST, com.example.lib.ActionBar.NAVIGATION_MODE_TABS}" />
          </annotation>
        </item>
        <item name="com.example.lib.ActionBar void setNavigationMode(int) 0">
          <annotation name="androidx.annotation.IntDef">
            <val name="value" val="{com.example.lib.ActionBar.NAVIGATION_MODE_STANDARD, com.example.lib.ActionBar.NAVIGATION_MODE_LIST, com.example.lib.ActionBar.NAVIGATION_MODE_TABS}" />
          </annotation>
        </item>
        <item name="com.example.lib.ActionBar.NavigationMode">
          <annotation name="androidx.annotation.IntDef">
            <val name="value" val="{com.example.lib.ActionBar.NAVIGATION_MODE_STANDARD, com.example.lib.ActionBar.NAVIGATION_MODE_LIST, com.example.lib.ActionBar.NAVIGATION_MODE_TABS}" />
          </annotation>
        </item>
        <item name="com.example.lib.DisplayOptions">
          <annotation name="androidx.annotation.IntDef">
            <val name="flag" val="true" />
            <val name="value" val="{android.app.ActionBar.DISPLAY_USE_LOGO, android.app.ActionBar.DISPLAY_SHOW_HOME, android.app.ActionBar.DISPLAY_HOME_AS_UP}" />
          </annotation>
        </item>
      </root>
      """
        .trimIndent()
  }
}
