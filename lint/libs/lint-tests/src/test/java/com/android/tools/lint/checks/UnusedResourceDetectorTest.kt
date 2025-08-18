/*
 * Copyright (C) 2011 The Android Open Source Project
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
package com.android.tools.lint.checks

import com.android.tools.lint.checks.infrastructure.ProjectDescription
import com.android.tools.lint.checks.infrastructure.TestFiles.rClass
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.client.api.LintClient
import com.android.tools.lint.detector.api.Detector
import org.intellij.lang.annotations.Language

class UnusedResourceDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector {
    return UnusedResourceDetector()
  }

  override fun allowCompilationErrors(): Boolean {
    // Some of these unit tests are still relying on source code that references
    // unresolved symbols etc.
    return true
  }

  fun testDocumentationExample() {
    lint()
      .files(
        xml(
          "res/layout/main.xml",
          """
          <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                  android:id="@+id/layout">
              <Button
                  android:id="@+id/button1"
                  android:text="Button" />
          </LinearLayout>
          """
            .trimIndent(),
        ),
        xml(
          "res/values/strings.xml",
          """
          <resources>
              <string name="app_name">Test</string>
              <string name="some_string">Some String</string>
          </resources>
          """
            .trimIndent(),
        ),
        java(
          """
          import android.app.Activity;
          import android.os.Bundle;

          public class MyActivity extends Activity {
              @Override
              public void onCreate(Bundle savedInstanceState) {
                  super.onCreate(savedInstanceState);
                  setContentView(R.layout.main);
                  String name = getString(R.string.app_name);
              }
          }
          """
            .trimIndent()
        ),
      )
      .issues(UnusedResourceDetector.ISSUE, UnusedResourceDetector.ISSUE_IDS)
      .run()
      .expect(
        """
          res/values/strings.xml:3: Warning: The resource R.string.some_string appears to be unused [UnusedResources]
              <string name="some_string">Some String</string>
                      ~~~~~~~~~~~~~~~~~~
          res/layout/main.xml:2: Warning: The resource R.id.layout appears to be unused [UnusedIds]
                  android:id="@+id/layout">
                  ~~~~~~~~~~~~~~~~~~~~~~~~
          res/layout/main.xml:4: Warning: The resource R.id.button1 appears to be unused [UnusedIds]
                  android:id="@+id/button1"
                  ~~~~~~~~~~~~~~~~~~~~~~~~~
          0 errors, 3 warnings
          """
          .trimIndent()
      )
  }

  fun testUnused() {
    val expected =
      """
        res/layout/accessibility.xml:2: Warning: The resource R.layout.accessibility appears to be unused [UnusedResources]
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:id="@+id/newlinear" android:orientation="vertical" android:layout_width="match_parent" android:layout_height="match_parent">
        ^
        res/layout/main.xml:2: Warning: The resource R.layout.main appears to be unused [UnusedResources]
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
        ^
        res/layout/other.xml:2: Warning: The resource R.layout.other appears to be unused [UnusedResources]
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
        ^
        res/values/strings2.xml:3: Warning: The resource R.string.hello appears to be unused [UnusedResources]
            <string name="hello">Hello</string>
                    ~~~~~~~~~~~~
        0 errors, 4 warnings
        """
        .trimIndent()
    lint()
      .files(
        mStrings2,
        mLayout1,
        xml(
          "res/layout/other.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
              android:layout_width="match_parent"
              android:layout_height="match_parent"
              android:orientation="vertical" >

              <include
                  android:layout_width="wrap_content"
                  android:layout_height="wrap_content"
                  layout="@layout/layout2" />

              <Button
                  android:id="@+id/button1"
                  android:layout_width="wrap_content"
                  android:layout_height="wrap_content"
                  android:text="Button" />

              <Button
                  android:id="@+id/button2"
                  android:layout_width="wrap_content"
                  android:layout_height="wrap_content"
                  android:text="Button" />

          </LinearLayout>
          """
            .trimIndent(),
        ), // Rename .txt files to .java
        mTest,
        mR,
        manifest().minSdk(14),
        mAccessibility, // https://issuetracker.google.com/113686968
        source("res/raw/.DS_Store", ""),
      )
      .issues(UnusedResourceDetector.ISSUE)
      .run()
      .expect(expected)
  }

  fun testUnusedIds() {
    val expected =
      """
        res/layout/accessibility.xml:2: Warning: The resource R.layout.accessibility appears to be unused [UnusedResources]
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:id="@+id/newlinear" android:orientation="vertical" android:layout_width="match_parent" android:layout_height="match_parent">
        ^
        res/layout/accessibility.xml:2: Warning: The resource R.id.newlinear appears to be unused [UnusedIds]
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:id="@+id/newlinear" android:orientation="vertical" android:layout_width="match_parent" android:layout_height="match_parent">
                                                                                 ~~~~~~~~~~~~~~~~~~~~~~~~~~~
        res/layout/accessibility.xml:3: Warning: The resource R.id.button1 appears to be unused [UnusedIds]
            <Button android:text="Button" android:id="@+id/button1" android:layout_width="wrap_content" android:layout_height="wrap_content"></Button>
                                          ~~~~~~~~~~~~~~~~~~~~~~~~~
        res/layout/accessibility.xml:4: Warning: The resource R.id.android_logo appears to be unused [UnusedIds]
            <ImageView android:id="@+id/android_logo" android:layout_width="wrap_content" android:layout_height="wrap_content" android:src="@drawable/android_button" android:focusable="false" android:clickable="false" android:layout_weight="1.0" />
                       ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        res/layout/accessibility.xml:5: Warning: The resource R.id.android_logo2 appears to be unused [UnusedIds]
            <ImageButton android:importantForAccessibility="yes" android:id="@+id/android_logo2" android:layout_width="wrap_content" android:layout_height="wrap_content" android:src="@drawable/android_button" android:focusable="false" android:clickable="false" android:layout_weight="1.0" />
                                                                 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 5 warnings
        """
        .trimIndent()
    lint().files(mTest, mR, manifest().minSdk(14), mAccessibility).run().expect(expected)
  }

  fun testImplicitFragmentUsage() {
    // Regression test for https://code.google.com/p/android/issues/detail?id=209393
    // Ensure fragment id's aren't deleted.
    lint()
      .files(
        xml(
          "res/layout/has_fragment.xml",
          """
          <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
          <fragment
              android:id="@+id/viewer"
              android:name="package.name.MyFragment"
              android:layout_width="match_parent"
              android:layout_height="match_parent"/>
          </LinearLayout>
          """
            .trimIndent(),
        ),
        java(
          "src/test/pkg/Test.java",
          """
          package test.pkg;
          public class Test {
              public void test() {
                  int used = R.layout.has_fragment;
              }
          }
          """
            .trimIndent(),
        ),
      )
      .run()
      .expectClean()
  }

  fun testArrayReference() {
    val expected =
      """
        res/values/arrayusage.xml:2: Warning: The resource R.string.my_item appears to be unused [UnusedResources]
        <string name="my_item">An Item</string>
                ~~~~~~~~~~~~~~
        res/values/arrayusage.xml:3: Warning: The resource R.array.my_array appears to be unused [UnusedResources]
        <string-array name="my_array">
                      ~~~~~~~~~~~~~~~
        0 errors, 2 warnings
        """
        .trimIndent()
    lint()
      .files(
        xml(
          "res/values/arrayusage.xml",
          """
          <resources>
          <string name="my_item">An Item</string>
          <string-array name="my_array">
             <item>@string/my_item</item>
          </string-array>
          </resources>
          """
            .trimIndent(),
        )
      )
      .issues(UnusedResourceDetector.ISSUE)
      .run()
      .expect(expected)
  }

  fun testArrayReferenceIncluded() {
    lint()
      .files(
        xml(
          "res/values/arrayusage.xml",
          """
          <resources xmlns:tools="http://schemas.android.com/tools"   tools:keep="@array/my_array">
          <string name="my_item">An Item</string>
          <string-array name="my_array">
             <item>@string/my_item</item>
          </string-array>
          </resources>
          """
            .trimIndent(),
        )
      )
      .issues(UnusedResourceDetector.ISSUE)
      .run()
      .expectClean()
  }

  fun testAttrs() {
    val expected =
      """
        res/layout/customattrlayout.xml:2: Warning: The resource R.layout.customattrlayout appears to be unused [UnusedResources]
        <foo.bar.ContentFrame
        ^
        0 errors, 1 warnings
        """
        .trimIndent()
    lint()
      .files(
        xml(
          "res/values/customattr.xml",
          """
          <resources>
              <declare-styleable name="ContentFrame">
                  <attr name="content" format="reference" />
                  <attr name="contentId" format="reference" />
              </declare-styleable>
          </resources>
          """
            .trimIndent(),
        ),
        xml(
          "res/layout/customattrlayout.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <foo.bar.ContentFrame
              xmlns:android="http://schemas.android.com/apk/res/android"
              xmlns:foobar="http://schemas.android.com/apk/res/foo.bar"
              android:layout_width="match_parent"
              android:layout_height="match_parent"
              foobar:contentId="@+id/test" />
          """
            .trimIndent(),
        ),
        java(
          """
          /* AUTO-GENERATED FILE.  DO NOT MODIFY.
           *
           * This class was automatically generated by the
           * aapt tool from the resource data it found.  It
           * should not be modified by hand.
           */

          package my.pkg;

          public final class R {
              public static final class attr {
                  public static final int contentId=0x7f020000;
              }
          }
          """
            .trimIndent()
        ),
        manifest().minSdk(14),
      )
      .issues(UnusedResourceDetector.ISSUE)
      .run()
      .expect(expected)
  }

  fun testMultiProjectIgnoreLibraries() {
    lint()
      .files( // Main project
        manifest().pkg("foo.Main").minSdk(14),
        java(
          """
          package foo.main;

          public class MainCode {
              static {
                  System.out.println(R.string.string2);
              }
          }
          """
            .trimIndent()
        ), // Library project
        manifest().pkg("foo.library").minSdk(14).to("../LibraryProject/AndroidManifest.xml"),
        mLibraryCode,
        xml(
          "../LibraryProject/res/values/strings.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <resources>

              <string name="string1">String 1</string>
              <string name="string2">String 2</string>
              <string name="string3">String 3</string>

          </resources>
          """
            .trimIndent(),
        ),
      )
      .run()
      .expect(
        """
          ../LibraryProject/res/values/strings.xml:6: Warning: The resource R.string.string3 appears to be unused [UnusedResources]
              <string name="string3">String 3</string>
                      ~~~~~~~~~~~~~~
          0 errors, 1 warnings
          """
          .trimIndent()
      )
  }

  // TODO: Make sure I test the situation where UnusedIds is enabled in one project but not
  // the other; we have to make sure we catch and handle that.
  fun testMultiProject() {
    // Library defines string1 and string2, but only references string1.
    // Main references string2.
    val library =
      project( // Library project
          mLibraryManifest,
          mLibraryCode,
          mLibraryStrings,
        )
        .type(ProjectDescription.Type.LIBRARY)
        .name("LibraryProject")

    val main = project(mMainCode, manifest().minSdk(15)).name("App").dependsOn(library)

    lint().projects(main, library).reportFrom(main).run().expectClean()
  }

  fun testMultiProject2() {
    // Add some unused resources both in the library and in the main module
    // to make sure that we correctly compute locations

    // This test also adds a tools:keep wildcard in the app module and makes
    // sure it takes effect to filter out unused candidates from the library
    // such as @string/kept1

    val library =
      project( // Library project
          mLibraryManifest,
          mLibraryCode,
          mLibraryStrings,
          xml(
            "res/values/strings2.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="unused1">Unused 1</string>
                <string name="kept1">Kept1 1</string>
            </resources>
            """
              .trimIndent(),
          ),
        )
        .type(ProjectDescription.Type.LIBRARY)
        .name("LibraryProject")

    val main =
      project(
          mMainCode,
          manifest().minSdk(15),
          xml(
            "res/values/strings2.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources
                xmlns:tools="http://schemas.android.com/tools"     tools:keep="@string/ke*">
                <string name="unused2">Unused 2</string>
            </resources>
            """
              .trimIndent(),
          ),
        )
        .name("App")
        .dependsOn(library)

    lint()
      .projects(main, library)
      .reportFrom(main)
      .run()
      .expect(
        """
          ../LibraryProject/res/values/strings2.xml:3: Warning: The resource R.string.unused1 appears to be unused [UnusedResources]
              <string name="unused1">Unused 1</string>
                      ~~~~~~~~~~~~~~
          res/values/strings2.xml:4: Warning: The resource R.string.unused2 appears to be unused [UnusedResources]
              <string name="unused2">Unused 2</string>
                      ~~~~~~~~~~~~~~
          0 errors, 2 warnings
          """
          .trimIndent()
      )
  }

  fun testMultiProject3() {
    // Regression test for b/200577800.
    // Similar to testMultiProject2, except there are 2 library modules with
    // the same unused resource
    val library1 =
      project(
          mLibraryManifest,
          mLibraryCode,
          mLibraryStrings,
          xml(
            "res/values-fr/strings2.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="unused1">Unused 1</string>
                <string name="kept1">Kept 1</string>
            </resources>
            """
              .trimIndent(),
          ),
          xml(
            "res/values/strings2.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="unused1">Unused 1</string>
                <string name="kept1">Kept 1</string>
            </resources>
            """
              .trimIndent(),
          ),
          xml(
            "res/values-en/strings2.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="unused1">Unused 1</string>
                <string name="kept1">Kept 1</string>
            </resources>
            """
              .trimIndent(),
          ),
        )
        .type(ProjectDescription.Type.LIBRARY)
        .name("LibraryProject1")

    val library2 =
      project(
          mLibraryManifest,
          mLibraryCode,
          mLibraryStrings,
          xml(
            "res/values/strings2.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="unused1">Unused 1</string>
                <string name="kept2">Kept 2</string>
            </resources>
            """
              .trimIndent(),
          ),
        )
        .type(ProjectDescription.Type.LIBRARY)
        .name("LibraryProject2")

    val main =
      project(
          mMainCode,
          manifest().minSdk(15),
          xml(
            "res/values/strings2.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources
                xmlns:tools="http://schemas.android.com/tools"     tools:keep="@string/ke*">
                <string name="unused2">Unused 2</string>
            </resources>
            """
              .trimIndent(),
          ),
        )
        .name("App")
        .dependsOn(library1)
        .dependsOn(library2)

    lint()
      .projects(main, library1, library2)
      .reportFrom(main)
      .run()
      .expect(
        """
          ../LibraryProject1/res/values/strings2.xml:3: Warning: The resource R.string.unused1 appears to be unused [UnusedResources]
              <string name="unused1">Unused 1</string>
                      ~~~~~~~~~~~~~~
          ../LibraryProject2/res/values/strings2.xml:3: Warning: The resource R.string.unused1 appears to be unused [UnusedResources]
              <string name="unused1">Unused 1</string>
                      ~~~~~~~~~~~~~~
          res/values/strings2.xml:4: Warning: The resource R.string.unused2 appears to be unused [UnusedResources]
              <string name="unused2">Unused 2</string>
                      ~~~~~~~~~~~~~~
          0 errors, 3 warnings
          """
          .trimIndent()
      )
  }

  fun testFqcnReference() {
    lint()
      .files(
        mLayout1,
        java(
          """
          package test.pkg;

          import android.app.Activity;
          import android.os.Bundle;

          public class UnusedReference extends Activity {
              @Override
              public void onCreate(Bundle savedInstanceState) {
                  super.onCreate(savedInstanceState);
                  setContentView(test.pkg.R.layout.main);
              }
          }
          """
            .trimIndent()
        ),
        manifest().minSdk(14),
      )
      .issues(UnusedResourceDetector.ISSUE)
      .run()
      .expectClean()
  }

  fun testKotlin() {
    lint()
      .files(
        mLayout1,
        kotlin(
          """
          package test.pkg

          import android.app.Activity
          import android.os.Bundle

          class UnusedReference : Activity() {
              public override fun onCreate(savedInstanceState: Bundle?) {
                  super.onCreate(savedInstanceState)
                  setContentView(test.pkg.R.layout.main)
                  setContentView(R.layout.main)
              }
          }
          """
            .trimIndent()
        ),
        rClass("test.pkg", "@layout/main"),
        manifest().minSdk(14),
      )
      .issues(UnusedResourceDetector.ISSUE) // Not id's
      .run()
      .expectClean()
  }

  fun testKotlin2() {
    // Regression test for issue 63150366, comment #17 - reference in class declaration
    // Blocked on https://youtrack.jetbrains.com/issue/KT-21409
    //
    lint()
      .files(
        mLayout1,
        kotlin(
          """
          package test.pkg

          open class Parent(val number: Int) {
          }

          class Five : Parent(R.layout.main)
          """
            .trimIndent()
        ),
        rClass("test.pkg", "@layout/main"),
        manifest().minSdk(14),
      )
      .issues(UnusedResourceDetector.ISSUE) // Not id's
      .run()
      .expectClean()
  }

  fun testKotlin3() {
    // Regression test for issue 76213486
    // 76213486: Resource ids passed into Kotlin enum constructors are not considered used
    lint()
      .files(
        kotlin(
          """
          package test.pkg

          enum class KotlinEnum(val resId: Int) {
              MAIN(R.layout.main1)
          }
          """
            .trimIndent()
        ),
        java(
          """
          public enum JavaEnum {
              MAIN(R.layout.main2);

              JavaEnum(int arg) {
              }
          }
          """
            .trimIndent()
        ),
        xml("res/layout/main1.xml", LAYOUT_XML),
        xml("res/layout/main2.xml", LAYOUT_XML),
        rClass("test.pkg", "@layout/main1", "@layout/main2"),
        manifest().minSdk(14),
      )
      .issues(UnusedResourceDetector.ISSUE) // Not id's
      .run()
      .expectClean()
  }

  fun testKotlin4() {
    // Regression test for https://issuetracker.google.com/113198298
    lint()
      .files(
        mLayout1,
        kotlin(
          """
          package test.pkg.other

          import android.app.Activity
          import android.os.Bundle
          import test.pkg.R as RC

          class MainIsUsed : Activity() {
              public override fun onCreate(savedInstanceState: Bundle?) {
                  super.onCreate(savedInstanceState)
                  setContentView(RC.layout.main)
              }
          }
          """
            .trimIndent()
        ),
        rClass("test.pkg", "@layout/main"),
        manifest().minSdk(14),
      )
      .issues(UnusedResourceDetector.ISSUE) // Not id's
      .run()
      .expectClean()
  }

  fun testPlurals() {
    lint()
      .files(
        xml(
          "res/values/strings4.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <resources xmlns:tools="http://schemas.android.com/tools">
              <string name="hello">Hello</string>
          </resources>
          """
            .trimIndent(),
        ),
        xml(
          "res/values/plurals.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <resources>
              <plurals name="my_plural">
                  <item quantity="one">@string/hello</item>
                  <item quantity="few">@string/hello</item>
                  <item quantity="other">@string/hello</item>
              </plurals>
          </resources>
          """
            .trimIndent(),
        ),
        java(
          "src/test/pkg/Test.java",
          """
          package test.pkg;
          public class Test {
              public void test() {
                  int used = R.plurals.my_plural;
              }
          }
          """
            .trimIndent(),
        ),
      )
      .run()
      .expectClean()
  }

  fun testLibraryMerging() {
    // http://code.google.com/p/android/issues/detail?id=36952
    val library =
      project(mLibraryManifest, projectProperties().library(true), mLibraryCode, mLibraryStrings)
        .name("LibraryProject")
    val main =
      project( // Main project
          manifest().pkg("foo.main").minSdk(14),
          projectProperties()
            .property("android.library.reference.1", "../LibraryProject")
            .property("manifestmerger.enabled", "true"),
          mMainCode,
        )
        .name("MainProject")
        .dependsOn(library)
    // The strings are all referenced in the library project's manifest file
    // which in this project is merged in
    lint().projects(library, main).run().expectClean()
  }

  fun testCornerCase() {
    // See http://code.google.com/p/projectlombok/issues/detail?id=415
    lint()
      .files(
        java(
          """
          // http://code.google.com/p/projectlombok/issues/detail?id=415
          package test.pkg;
          public class X {
            public void X(Y parent) {
              parent.new Z(parent.getW()).execute();
            }
          }
          """
            .trimIndent()
        ),
        manifest().minSdk(14),
      )
      .run()
      .expectClean()
  }

  fun testAnalytics() {
    // See http://code.google.com/p/android/issues/detail?id=42565
    lint()
      .files(
        xml(
          "res/values/analytics.xml",
          """
          <?xml version="1.0" encoding="utf-8" ?>
          <resources>
            <!--Replace placeholder ID with your tracking ID-->
            <string name="ga_trackingId">UA-12345678-1</string>

            <!--Enable Activity tracking-->
            <bool name="ga_autoActivityTracking">true</bool>

            <!--Enable automatic exception tracking-->
            <bool name="ga_reportUncaughtExceptions">true</bool>

            <!-- The screen names that will appear in your reporting -->
            <string name="com.example.app.BaseActivity">Home</string>
            <string name="com.example.app.PrefsActivity">Preferences</string>
            <string name="test.pkg.OnClickActivity">Clicks</string>
          </resources>
          """
            .trimIndent(),
        )
      )
      .issues(UnusedResourceDetector.ISSUE)
      .run()
      .expectClean()
  }

  fun testIntegers() {
    // See https://code.google.com/p/android/issues/detail?id=53995
    lint()
      .files(
        xml(
          "res/values/integers.xml",
          """
          <resources>
              <item name="bar_display_duration" type="integer">3600</item>
              <item name="bar_slide_out_duration" type="integer">2400</item>
          </resources>
          """
            .trimIndent(),
        ),
        xml(
          "res/anim/slide_in_out.xml",
          """
          <set xmlns:android="http://schemas.android.com/apk/res/android"
               xmlns:tools="http://schemas.android.com/tools"
               tools:ignore="UnusedResources">
              <translate
                android:duration="@integer/bar_slide_out_duration"
                android:startOffset="@integer/bar_display_duration" />
          </set>

          """
            .trimIndent(),
        ),
      )
      .run()
      .expectClean()
  }

  fun testIntegerArrays() {
    // See http://code.google.com/p/android/issues/detail?id=59761
    lint()
      .files(
        xml(
          "res/values/integer_arrays.xml",
          """
          <resources xmlns:tools="http://schemas.android.com/tools">
              <dimen name="used">16dp</dimen>

              <integer-array name="iconsets_array_ids" tools:ignore="UnusedResources">
                  <item>@array/iconset_pixelmixer_basic</item>
                  <item>@array/iconset_dryicons_coquette</item>
              </integer-array>

              <integer-array name="iconset_pixelmixer_basic">
                  <item>@dimen/used</item>
              </integer-array>

              <integer-array name="iconset_dryicons_coquette">
                  <item>@dimen/used</item>
              </integer-array>
          </resources>
          """
            .trimIndent(),
        )
      )
      .issues(UnusedResourceDetector.ISSUE)
      .run()
      .expectClean()
  }

  fun testUnitTestReferences() {
    // Make sure that we pick up references in unit tests as well
    // Regression test for
    // https://code.google.com/p/android/issues/detail?id=79066
    lint()
      .files(
        mStrings2,
        mLayout1,
        mOther,
        mTest,
        mR,
        manifest(),
        mAccessibility, // Add unit test source which references resources which would otherwise
        // be marked as unused

        java(
          "test/my/pkg/MyTest.java",
          """
          package my.pkg;
          class MyTest {
              public void test() {
                  System.out.println(R.layout.accessibility);
                  System.out.println(R.layout.main);
                  System.out.println(R.layout.other);
                  System.out.println(R.string.hello);
              }
          }
          """
            .trimIndent(),
        ),
      )
      .issues(UnusedResourceDetector.ISSUE)
      .run()
      .expectClean()
  }

  fun testDataBinding_resourcesUsingAtSyntaxAreConsideredUsed() {
    // Make sure that resources referenced only via a data binding expression
    // are not counted as unused.
    // Regression test for https://code.google.com/p/android/issues/detail?id=183934
    lint()
      .files(
        xml(
          "res/values/resources.xml",
          """
          <resources>
              <item type='dimen' name='largePadding'>20dp</item>
              <item type='dimen' name='smallPadding'>15dp</item>
              <item type='string' name='nameFormat'>%1${'$'}s %2${'$'}s</item>
          </resources>
          """
            .trimIndent(),
        ), // Add unit test source which references resources which would otherwise
        // be marked as unused

        xml(
          "res/layout/db.xml",
          """
          <layout xmlns:android="http://schemas.android.com/apk/res/android"
              xmlns:tools="http://schemas.android.com/tools"     tools:keep="@layout/db">
             <data>
                 <variable name="user" type="com.example.User"/>
             </data>
             <LinearLayout
                 android:orientation="vertical"
                 android:layout_width="match_parent"
                 android:layout_height="match_parent"
                 android:padding="@{large? @dimen/largePadding : @dimen/smallPadding}"
                 android:text="@{@string/nameFormat(firstName, lastName)}" />
          </layout>
          """
            .trimIndent(),
        ),
      )
      .issues(UnusedResourceDetector.ISSUE)
      .run()
      .expectClean()
  }

  fun testDataBinding_resourcesUsingRNamespacingAreConsideredUsed() {
    // Make sure that resources referenced only via a data binding expression in the
    // form of "R.type.name" are not counted as unused.
    lint()
      .files(
        xml(
          "res/values/resources.xml",
          """
          <resources>
              <item type='dimen' name='largePadding'>20dp</item>
              <item type='dimen' name='smallPadding'>15dp</item>
              <item type='string' name='name'>Name</item>
          </resources>
          """
            .trimIndent(),
        ), // Add unit test source which references resources which would otherwise
        // be marked as unused

        xml(
          "res/layout/db.xml",
          """
          <layout xmlns:android="http://schemas.android.com/apk/res/android"
              xmlns:tools="http://schemas.android.com/tools"     tools:keep="@layout/db">
             <LinearLayout
                 android:orientation="vertical"
                 android:layout_width="match_parent"
                 android:layout_height="match_parent"
                 android:padding="@{large? R.dimen.largePadding : R.dimen.smallPadding}"
                 android:text="@{R.string.name}" />
              <Button android:text="@{SomeEnum.NOMER.isEditable(viewmodel.fieldIsEditable)}"
           />
          </layout>
          """
            .trimIndent(),
        ),
      )
      .issues(UnusedResourceDetector.ISSUE)
      .run()
      .expectClean()
  }

  fun testDataBinding_idsAddedInDataBindingLayoutsAreConsideredUsed() {
    // Make sure id's in data binding layouts aren't considered unused
    // (since the compiler will generate accessors for these that
    // may not be visible when running lint on edited sources)
    // Regression test for https://code.google.com/p/android/issues/detail?id=189065
    lint()
      .files(
        xml(
          "res/layout/db.xml",
          """
          <layout xmlns:android="http://schemas.android.com/apk/res/android"
              xmlns:tools="http://schemas.android.com/tools"     tools:keep="@layout/db">
             <data>
                 <variable name="user" type="com.example.User"/>
             </data>
             <LinearLayout
                 android:orientation="vertical"
                 android:id="@+id/my_id"
                 android:layout_width="match_parent"
                 android:layout_height="match_parent" />
          </layout>
          """
            .trimIndent(),
        )
      )
      .run()
      .expectClean()
  }

  fun testPublic() {
    // Resources marked as public should not be listed as potentially unused
    val expected =
      """
        res/values/resources.xml:4: Warning: The resource R.string.nameFormat appears to be unused [UnusedResources]
            <item type='string' name='nameFormat'>%1${'$'}s %2${'$'}s</item>
                                ~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
        .trimIndent()
    lint()
      .files(
        xml(
          "res/values/resources.xml",
          """
          <resources>
              <item type='dimen' name='largePadding'>20dp</item>
              <item type='dimen' name='smallPadding'>15dp</item>
              <item type='string' name='nameFormat'>%1${'$'}s %2${'$'}s</item>
              <public type='dimen' name='largePadding' />    <public type='dimen' name='smallPadding' />
          </resources>
          """
            .trimIndent(),
        )
      )
      .issues(UnusedResourceDetector.ISSUE)
      .run()
      .expect(expected)
  }

  fun testPublicLibrary() {
    // Regression test for
    // 187343720: UnusedResources lint check does not work correctly for libraries
    lint()
      .files(
        xml(
          "res/values/resources.xml",
          """
          <resources>
              <style name='Theme.AppCompat' parent='@style/Theme.Other'/>
              <style name='Theme.Other'/>
              <public type='style' name='Theme.AppCompat' />
          </resources>
          """
            .trimIndent(),
        )
      )
      .issues(UnusedResourceDetector.ISSUE)
      .run()
      .expectClean()
  }

  fun testDynamicResources() {
    val expected =
      """
        build.gradle: Warning: The resource R.string.cat appears to be unused [UnusedResources]
        build.gradle: Warning: The resource R.string.dog appears to be unused [UnusedResources]
        0 errors, 2 warnings
        """
        .trimIndent() // Note: R.string.foo should not be here since it is not present in
    // `release` variant.

    val lib =
      project(
          xml("src/main/" + mLayout1.targetRelativePath, mLayout1.contents),
          java(
            """
            package test.pkg;

            import android.app.Activity;
            import android.os.Bundle;
            import android.support.design.widget.Snackbar;

            public class UnusedReferenceDynamic extends Activity {
                @Override
                public void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    setContentView(test.pkg.R.layout.main);
                    Snackbar.make(view, R.string.xyz, Snackbar.LENGTH_LONG);
                }
            }
            """
              .trimIndent()
          ),
          manifest().minSdk(14),
          gradle("// dummy"),
        )
        .type(ProjectDescription.Type.LIBRARY)
        .name("library")

    val app =
      project(
          manifest().minSdk(14),
          gradle(
            """
            android {
                defaultConfig {
                    resValue "string", "cat", "Some Data"
                }
                buildTypes {
                    debug {
                        resValue "string", "foo", "Some Data"
                    }
                    release {
                        resValue "string", "xyz", "Some Data"
                        resValue "string", "dog", "Some Data"
                    }
                }
            }
            """
              .trimIndent()
          ),
        )
        .name("app")
        .type(ProjectDescription.Type.APP)

    app.dependsOn(lib)

    lint()
      .projects(app, lib)
      .variant("release")
      .reportFrom(app)
      .issues(UnusedResourceDetector.ISSUE) // skip UnusedResourceDetector.ISSUE_IDS
      .allowCompilationErrors()
      .run()
      .expect(expected)
  }

  fun testManifestPlaceholders() {
    // Regression test for 78678414
    lint()
      .files(
        manifest(
          """
          <manifest xmlns:android="http://schemas.android.com/apk/res/android"
              package="test.pkg"
              android:versionCode="1"
              android:versionName="1.0" >
              <uses-sdk android:minSdkVersion="14"
                        android:targetSdkVersion="25"/>    <meta-data android:name="account_type" android:value="${"$"}{account_type}" />
          </manifest>
          """
            .trimIndent()
        ),
        gradle(
          """
          android {
            defaultConfig {
              resValue "string", "account_type", "com.google"

              manifestPlaceholders = [ "account_type": "@string/account_type" ]
            }
          }
          """
            .trimIndent()
        ),
      )
      .variant("debug")
      .issues(UnusedResourceDetector.ISSUE) // skip UnusedResourceDetector.ISSUE_IDS
      .allowCompilationErrors()
      .run()
      .expectClean()
  }

  fun testStaticImport() {
    // Regression test for https://code.google.com/p/android/issues/detail?id=40293
    // 40293: Lint reports resource as unused when referenced via "import static"
    lint()
      .files(
        xml(
          "res/values/resources.xml",
          """
          <resources>
              <item type='dimen' name='largePadding'>20dp</item>
              <item type='dimen' name='smallPadding'>15dp</item>
              <item type='string' name='nameFormat'>%1${'$'}s %2${'$'}s</item>
          </resources>
          """
            .trimIndent(),
        ), // Add unit test source which references resources which would otherwise
        // be marked as unused

        java(
          "src/test/pkg/TestCode.java",
          """
          package test.pkg;

          import static test.pkg.R.dimen.*;
          import static test.pkg.R.string.nameFormat;
          import test.pkg.R.dimen;

          public class TestCode {
              public void test() {
                  int x = dimen.smallPadding; // Qualified import
                  int y = largePadding; // Static wildcard import
                  int z = nameFormat; // Static explicit import
              }
          }
          """
            .trimIndent(),
        ),
        rClass("test.pkg", "@dimen/largePadding", "@dimen/smallPadding", "@string/nameFormat"),
      )
      .issues(UnusedResourceDetector.ISSUE)
      .run()
      .expectClean()
  }

  fun testStyles() {
    val expected =
      """
        res/values/styles.xml:4: Warning: The resource R.style.UnusedStyleExtendingFramework appears to be unused [UnusedResources]
           <style name="UnusedStyleExtendingFramework" parent="android:Theme"/>
                  ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        res/values/styles.xml:5: Warning: The resource R.style.UnusedStyle appears to be unused [UnusedResources]
            <style name="UnusedStyle"/>
                   ~~~~~~~~~~~~~~~~~~
        res/values/styles.xml:6: Warning: The resource R.style.UnusedStyle_Sub appears to be unused [UnusedResources]
            <style name="UnusedStyle.Sub"/>
                   ~~~~~~~~~~~~~~~~~~~~~~
        res/values/styles.xml:7: Warning: The resource R.style.UnusedStyle_Something_Sub appears to be unused [UnusedResources]
            <style name="UnusedStyle.Something.Sub" parent="UnusedStyle"/>
                   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        res/values/styles.xml:8: Warning: The resource R.style.ImplicitUsed appears to be unused [UnusedResources]
            <style name="ImplicitUsed" parent="android:Widget.ActionBar"/>
                   ~~~~~~~~~~~~~~~~~~~
        res/values/styles.xml:9: Warning: The resource R.style.EmptyParent appears to be unused [UnusedResources]
            <style name="EmptyParent" parent=""/>
                   ~~~~~~~~~~~~~~~~~~
        0 errors, 6 warnings
        """
        .trimIndent()
    lint()
      .files(
        xml(
          "res/values/styles.xml",
          """
          <resources>


             <style name="UnusedStyleExtendingFramework" parent="android:Theme"/>
              <style name="UnusedStyle"/>
              <style name="UnusedStyle.Sub"/>
              <style name="UnusedStyle.Something.Sub" parent="UnusedStyle"/>
              <style name="ImplicitUsed" parent="android:Widget.ActionBar"/>
              <style name="EmptyParent" parent=""/>
          </resources>
          """
            .trimIndent(),
        )
      )
      .run()
      .expect(expected)
  }

  fun testStylePrefix() {
    // AAPT accepts parent style references that simply start with "style/" (not @style);
    // similarly, it also allows android:style/ rather than @android:style/
    lint()
      .files(
        xml(
          "res/values/styles.xml",
          """
          <resources
                  xmlns:tools="http://schemas.android.com/tools"
                  tools:keep="@style/MyInheritingStyle" >
              <style name="MyStyle">
                  <item name="android:textColor">#ffff00ff</item>
              </style>

              <style name="MyInheritingStyle" parent="style/MyStyle">
                  <item name="android:textSize">24pt</item>
              </style>
          </resources>
          """
            .trimIndent(),
        )
      )
      .run()
      .expectClean()
  }

  fun testThemeFromLayout() {
    lint()
      .files(
        xml(
          "res/values/styles.xml",
          """
          <resources>
              <style name="InlineActionView" />
              <style name="InlineActionView.Like">
              </style>
          </resources>
          """
            .trimIndent(),
        ),
        xml(
          "res/layout/main.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
              android:orientation="vertical" android:layout_width="match_parent"
              android:layout_height="match_parent">

              <Button
                  android:layout_width="wrap_content"
                  android:layout_height="wrap_content"
                  style="@style/InlineActionView.Like"
                  android:layout_gravity="center_horizontal" />
          </LinearLayout>
          """
            .trimIndent(),
        ),
        java(
          "src/my/pkg/MyTest.java",
          """
          package my.pkg;
          class MyTest {
              public void test() {
                  System.out.println(R.layout.main);
              }
          }
          """
            .trimIndent(),
        ),
      )
      .issues(UnusedResourceDetector.ISSUE)
      .run()
      .expectClean()
  }

  fun testReferenceFromObjectLiteralArguments() {
    lint()
      .files(
        xml(
          "res/layout/main.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
              android:orientation="vertical" android:layout_width="match_parent"
              android:layout_height="match_parent" />
          """
            .trimIndent(),
        ),
        java(
          "src/my/pkg/MyTest.java",
          """
          package test.pkg;

          public class MyTest {
              public Object test() {
                  return new Inner<String>(R.layout.main) {
                      @Override
                      public void foo() {
                          super.foo();
                      }
                  };
              }

              private static class Inner<T> {
                  public Inner(int id) {
                  }
                  public void foo() {
                  }
              }
          }
          """
            .trimIndent(),
        ),
      )
      .run()
      .expectClean()
  }

  fun testKeepAndDiscard() {
    lint()
      .files( // By name
        xml("res/raw/keep.xml", "<foo/>"), // By content
        xml(
          "res/raw/used.xml",
          """
          <resources
                  xmlns:tools="http://schemas.android.com/tools"
                  tools:shrinkMode="strict"
                  tools:discard="@raw/unused"
                  tools:keep="@raw/used" />
          """
            .trimIndent(),
        ),
      )
      .issues(UnusedResourceDetector.ISSUE)
      .run()
      .expectClean()
  }

  fun testKeepAndDiscardWithDifferentPrefix() {
    lint()
      .files( // By name
        xml("res/raw/keep.xml", "<foo/>"), // By content
        xml(
          "res/raw/used.xml",
          """
          <resources
                  xmlns:t="http://schemas.android.com/tools"
                  t:shrinkMode="strict"
                  t:discard="@raw/unused"
                  t:keep="@raw/used" />
          """
            .trimIndent(),
        ),
      )
      .issues(UnusedResourceDetector.ISSUE)
      .run()
      .expectClean()
  }

  fun testStringsWithDots() {
    // Regression test for https://code.google.com/p/android/issues/detail?id=214189
    lint()
      .files(
        xml(
          "res/values/strings.xml",
          """
          <resources>
              <string name="foo.bar.your_name">Your Name</string>
          </resources>
          """
            .trimIndent(),
        ),
        java(
          "src/my/pkg/MyTest.java",
          """
          package my.pkg;
          class MyTest {
              public void test() {
                  System.out.println(R.string.foo_bar_your_name);
              }
          }
          """
            .trimIndent(),
        ),
      )
      .issues(UnusedResourceDetector.ISSUE)
      .run()
      .expectClean()
  }

  fun testNavigation() {
    // Regression test for https://issuetracker.google.com/145687664

    lint()
      .files(
        xml(
          "res/navigation/graph.xml",
          """
          <navigation xmlns:android="http://schemas.android.com/apk/res/android"
              xmlns:app="http://schemas.android.com/apk/res-auto"
              xmlns:tools="http://schemas.android.com/tools"
              android:id="@+id/navigation"
              app:startDestination="@id/importFragment">

              <fragment
                  android:id="@+id/importFragment"
                  android:name="com.android.demo.ImportFragment"
                  android:label="main_fragment">
                  <action
                      android:id="@+id/process_import"
                      app:destination="@id/eventListFragment" />
              </fragment>
              <fragment
                  android:id="@id/exportFragment"
                  android:name="com.android.demo.ImportFragment"
                  android:label="main_fragment">
                  <action
                      android:id="@id/process_export"
                      app:destination="@id/eventListFragment" />
              </fragment>
          </navigation>
          """
            .trimIndent(),
        ),
        java(
          "src/my/pkg/MyTest.java",
          """
          package my.pkg;
          class MyTest {
              public void test() {
                  System.out.println(R.id.navigation);
                  System.out.println(R.navigation.graph);
              }
          }
          """
            .trimIndent(),
        ),
        rClass(
          "my.pkg",
          "@id/navigation",
          "@id/importFragment",
          "@id/exportFragment",
          "@id/process_import",
          "@id/process_export",
          "@id/eventListFragment",
          "@navigation/graph",
        ),
      )
      .issues(UnusedResourceDetector.ISSUE_IDS, UnusedResourceDetector.ISSUE)
      .run()
      .expectClean()
  }

  fun testToolsNamespaceReferences() {
    // Regression test for https://code.google.com/p/android/issues/detail?id=226204
    lint()
      .files(
        xml(
          "res/layout/my_layout.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <android.support.constraint.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
              xmlns:app="http://schemas.android.com/apk/res-auto"
              xmlns:tools="http://schemas.android.com/tools"
              android:id="@+id/activity_main"
              android:layout_width="match_parent"
              android:layout_height="match_parent"
              tools:context="test.pkg.myapplication.MainActivity">

              <TextView
                  android:layout_width="wrap_content"
                  android:layout_height="wrap_content"
                  android:text="Hello World!"
                  tools:background="@drawable/my_drawable"
                  app:layout_constraintBottom_toBottomOf="@+id/activity_main"
                  app:layout_constraintLeft_toLeftOf="@+id/activity_main"
                  app:layout_constraintRight_toRightOf="@+id/activity_main"
                  app:layout_constraintTop_toTopOf="@+id/activity_main" />

          </android.support.constraint.ConstraintLayout>
          """
            .trimIndent(),
        ),
        xml(
          "res/drawable/my_drawable.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <selector xmlns:android="http://schemas.android.com/apk/res/android">

          </selector>
          """
            .trimIndent(),
        ), // By content
        xml(
          "res/raw/used.xml",
          """
          <resources
                  xmlns:tools="http://schemas.android.com/tools"
                  tools:shrinkMode="strict"
                  tools:keep="@raw/used,@layout/my_layout" />
          """
            .trimIndent(),
        ),
      )
      .issues(UnusedResourceDetector.ISSUE)
      .run()
      .expectClean()
  }

  fun testReferenceFromDataBinding() {
    // Regression test for https://issuetracker.google.com/38213600
    lint()
      .files( // Data binding layout
        xml(
          "res/layout/added_view.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <layout xmlns:android="http://schemas.android.com/apk/res/android">
              <TextView
                  android:layout_width="match_parent"
                  android:layout_height="match_parent"
                  android:orientation="vertical"
                  android:text="Hello World"/>
          </layout>
          """
            .trimIndent(),
        ),
        xml(
          "res/layout/added_view2.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <layout xmlns:android="http://schemas.android.com/apk/res/android">
              <data class=".IndependentLibraryBinding">
              </data>
              <TextView
                  android:layout_width="match_parent"
                  android:layout_height="match_parent"
                  android:orientation="vertical"
                  android:text="Hello World"/>
          </layout>
          """
            .trimIndent(),
        ),
        xml(
          "res/layout/third_added_view.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <layout xmlns:android="http://schemas.android.com/apk/res/android">
              <data>
              </data>
              <TextView
                  android:layout_width="match_parent"
                  android:layout_height="match_parent"
                  android:orientation="vertical"
                  android:text="Hello World"/>
          </layout>
          """
            .trimIndent(),
        ), // Only usage: data binding class
        java(
          """
          package my.pkg;

          import android.view.LayoutInflater;

          public class Ref {
              public void test(LayoutInflater inflater){
                  final AddedViewBinding addedView = AddedViewBinding.inflate(inflater, null, true);
                  final ThirdAddedViewBinding addedView2 = ThirdAddedViewBinding.inflate(inflater, null, true);
                  final AddedViewBinding addedView3 = IndependentLibraryBinding.inflate(inflater, null, true);
              }
          }
          """
            .trimIndent()
        ), // Stubs to make type resolution work in test without actual data binding
        // code-gen and data binding runtime libraries
        java(
          """
          package my.pkg;

          abstract class AddedViewBinding extends android.databinding.ViewDataBinding {
              public AddedViewBinding(android.databinding.DataBindingComponent bindingComponent,
                                       android.view.View root, int localFieldCount) {
                  super(bindingComponent, root, localFieldCount);
              }

              public static AddedViewBinding inflate(android.view.LayoutInflater inflater,
                                                     android.view.ViewGroup root,
                                                     boolean attachToRoot) {
                  return null;
              }
          }
          """
            .trimIndent()
        ),
        java(
          """
          package my.pkg;

          abstract class IndependentLibraryBinding extends android.databinding.ViewDataBinding {
              public IndependentLibraryBinding(android.databinding.DataBindingComponent bindingComponent,
                                       android.view.View root, int localFieldCount) {
                  super(bindingComponent, root, localFieldCount);
              }

              public static IndependentLibraryBinding inflate(android.view.LayoutInflater inflater,
                                                     android.view.ViewGroup root,
                                                     boolean attachToRoot) {
                  return null;
              }
          }
          """
            .trimIndent()
        ),
        java(
          """
          package my.pkg;

          abstract class ThirdAddedViewBinding extends android.databinding.ViewDataBinding {
              public ThirdAddedViewBinding(android.databinding.DataBindingComponent bindingComponent,
                                       android.view.View root, int localFieldCount) {
                  super(bindingComponent, root, localFieldCount);
              }

              public static ThirdAddedViewBinding inflate(android.view.LayoutInflater inflater,
                                                     android.view.ViewGroup root,
                                                     boolean attachToRoot) {
                  return null;
              }
          }
          """
            .trimIndent()
        ),
        java(
          """
          package android.databinding;
          public abstract class ViewDataBinding {
          }
          """
            .trimIndent()
        ),
      )
      .run()
      .expectClean()
  }

  fun testReferenceFromAndroidxDataBinding() {
    // Regression test for https://issuetracker.google.com/116842158
    lint()
      .files( // Data binding layout
        xml(
          "res/layout/added_view.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <layout xmlns:android="http://schemas.android.com/apk/res/android">
              <TextView
                  android:layout_width="match_parent"
                  android:layout_height="match_parent"
                  android:orientation="vertical"
                  android:text="Hello World"/>
          </layout>
          """
            .trimIndent(),
        ),
        xml(
          "res/layout/added_view2.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <layout xmlns:android="http://schemas.android.com/apk/res/android">
              <data class=".IndependentLibraryBinding">
              </data>
              <TextView
                  android:layout_width="match_parent"
                  android:layout_height="match_parent"
                  android:orientation="vertical"
                  android:text="Hello World"/>
          </layout>
          """
            .trimIndent(),
        ),
        xml(
          "res/layout/third_added_view.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <layout xmlns:android="http://schemas.android.com/apk/res/android">
              <data>
              </data>
              <TextView
                  android:layout_width="match_parent"
                  android:layout_height="match_parent"
                  android:orientation="vertical"
                  android:text="Hello World"/>
          </layout>
          """
            .trimIndent(),
        ), // Only usage: data binding class
        java(
          """
          package my.pkg;

          import android.view.LayoutInflater;

          public class Ref {
              public void test(LayoutInflater inflater){
                  final AddedViewBinding addedView = AddedViewBinding.inflate(inflater, null, true);
                  final ThirdAddedViewBinding addedView2 = ThirdAddedViewBinding.inflate(inflater, null, true);
                  final AddedViewBinding addedView3 = IndependentLibraryBinding.inflate(inflater, null, true);
              }
          }
          """
            .trimIndent()
        ), // Stubs to make type resolution work in test without actual data binding
        // code-gen and data binding runtime libraries
        java(
          """
          package my.pkg;

          abstract class AddedViewBinding extends androidx.databinding.ViewDataBinding {
              public AddedViewBinding(android.databinding.DataBindingComponent bindingComponent,
                                       android.view.View root, int localFieldCount) {
                  super(bindingComponent, root, localFieldCount);
              }

              public static AddedViewBinding inflate(android.view.LayoutInflater inflater,
                                                     android.view.ViewGroup root,
                                                     boolean attachToRoot) {
                  return null;
              }
          }
          """
            .trimIndent()
        ),
        java(
          """
          package my.pkg;

          abstract class IndependentLibraryBinding extends androidx.databinding.ViewDataBinding {
              public IndependentLibraryBinding(android.databinding.DataBindingComponent bindingComponent,
                                       android.view.View root, int localFieldCount) {
                  super(bindingComponent, root, localFieldCount);
              }

              public static IndependentLibraryBinding inflate(android.view.LayoutInflater inflater,
                                                     android.view.ViewGroup root,
                                                     boolean attachToRoot) {
                  return null;
              }
          }
          """
            .trimIndent()
        ),
        java(
          """
          package my.pkg;

          abstract class ThirdAddedViewBinding extends androidx.databinding.ViewDataBinding {
              public ThirdAddedViewBinding(android.databinding.DataBindingComponent bindingComponent,
                                       android.view.View root, int localFieldCount) {
                  super(bindingComponent, root, localFieldCount);
              }

              public static ThirdAddedViewBinding inflate(android.view.LayoutInflater inflater,
                                                     android.view.ViewGroup root,
                                                     boolean attachToRoot) {
                  return null;
              }
          }
          """
            .trimIndent()
        ),
        java(
          """
          package androidx.databinding;
          public abstract class ViewDataBinding {
          }
          """
            .trimIndent()
        ),
      )
      .run()
      .expectClean()
  }

  fun testReferenceFromViewBinding_java() {
    lint()
      .files(
        gradle(
          """
          buildscript {
            dependencies {
              classpath "com.android.tools.build:gradle:3.6.0"
            }
          }

          android {
              buildFeatures {
                  viewBinding true
              }
          }
          """
            .trimIndent()
        ),
        xml(
          "src/main/res/layout/activity_dot_syntax.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
              android:orientation="vertical" android:layout_width="match_parent"
              android:layout_height="match_parent" />
          """
            .trimIndent(),
        ),
        xml(
          "src/main/res/layout/activity_method_reference.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
              android:orientation="vertical" android:layout_width="match_parent"
              android:layout_height="match_parent" />
          """
            .trimIndent(),
        ),
        xml(
          "src/main/res/layout/activity_method_import.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
              android:orientation="vertical" android:layout_width="match_parent"
              android:layout_height="match_parent" />
          """
            .trimIndent(),
        ),
        xml(
          "src/main/res/layout/activity_ignored.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
              xmlns:tools="http://schemas.android.com/tools"
              android:orientation="vertical" android:layout_width="match_parent"
              android:layout_height="match_parent"
              tools:viewBindingIgnore="true" />
          """
            .trimIndent(),
        ), // View Binding usage here will reference activity_dot_syntax.xml
        java(
          """
          package my.pkg;

          import android.view.LayoutInflater;
          import my.pkg.databinding.ActivityDotSyntaxBinding;

          public class DotSyntaxActivity {
              public void test(LayoutInflater inflater){
                  ActivityDotSyntaxBinding.inflate(inflater);
              }
          }
          """
            .trimIndent()
        ), // View Binding usage here will reference activity_method_reference.xml
        java(
          """
          package my.pkg;

          import android.view.LayoutInflater;
          import my.pkg.databinding.ActivityMethodReferenceBinding;

          public class MethodReferenceActivity {
              public void test(LayoutInflater inflater){
                  ActivityMethodReferenceBinding::inflate;
              }
          }
          """
            .trimIndent()
        ), // View Binding usage here will reference activity_method_import.xml
        java(
          """
          package my.pkg;

          import android.view.LayoutInflater;
          import static my.pkg.databinding.ActivityMethodImportBinding.inflate;

          public class MethodImportActivity {
              public void test(LayoutInflater inflater){
                  inflate(inflater);
              }
          }
          """
            .trimIndent()
        ), // Here, we create a fake view binding class in an attempt to trick lint,
        // but it won't work because activity_ignored.xml is skipped due to the
        // viewBindingIgnore attribute.

        java(
          """
          package my.pkg;

          import android.view.LayoutInflater;

          public class IgnoredActivity {
              private class ActivityIgnoredBinding implements androidx.viewbinding.ViewBinding {
                  public static ActivityIgnoredBinding inflate(LayoutInflater inflater) {
                       return this;
                  }
             }

              public void test(LayoutInflater inflater){
                  final ActivityIgnoredBinding binding = ActivityIgnoredBinding.inflate(inflater);
              }
          }
          """
            .trimIndent()
        ), // Here we provide code that would have been generated for view binding /
        // provided by the view binding library

        java(
          """
          package my.pkg.databinding;

          import android.view.LayoutInflater;

          public final class ActivityDotSyntaxBinding implements androidx.viewbinding.ViewBinding {
            public static ActivityDotSyntaxBinding inflate(LayoutInflater inflater) {
              return this;
            }
          }
          """
            .trimIndent()
        ),
        java(
          """
          package my.pkg.databinding;

          import android.view.LayoutInflater;

          public final class ActivityMethodReferenceBinding implements androidx.viewbinding.ViewBinding {
            public static ActivityMethodReferenceBinding inflate(LayoutInflater inflater) {
              return this;
            }
          }
          """
            .trimIndent()
        ),
        java(
          """
          package my.pkg.databinding;

          import android.view.LayoutInflater;

          public final class ActivityMethodImportBinding implements androidx.viewbinding.ViewBinding {
            public static ActivityMethodImportBinding inflate(LayoutInflater inflater) {
              return this;
            }
          }
          """
            .trimIndent()
        ),
        java(
          """
          package androidx.viewbinding;
          public interface ViewBinding {
          }
          """
            .trimIndent()
        ),
      )
      .clientFactory(gradleClientFactory)
      .run()
      .expect(
        """
          src/main/res/layout/activity_ignored.xml:2: Warning: The resource R.layout.activity_ignored appears to be unused [UnusedResources]
          <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
          ^
          0 errors, 1 warnings
          """
          .trimIndent()
      )
  }

  fun testReferenceFromViewBinding_kotlin() {
    lint()
      .files(
        gradle(
          """
          buildscript {
            dependencies {
              classpath "com.android.tools.build:gradle:3.6.0"
            }
          }

          android {
              buildFeatures {
                  viewBinding true
              }
          }
          """
            .trimIndent()
        ),
        xml(
          "src/main/res/layout/activity_dot_syntax.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
              android:orientation="vertical" android:layout_width="match_parent"
              android:layout_height="match_parent" />
          """
            .trimIndent(),
        ),
        xml(
          "src/main/res/layout/activity_method_reference.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
              android:orientation="vertical" android:layout_width="match_parent"
              android:layout_height="match_parent" />
          """
            .trimIndent(),
        ),
        xml(
          "src/main/res/layout/activity_method_import.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
              android:orientation="vertical" android:layout_width="match_parent"
              android:layout_height="match_parent" />
          """
            .trimIndent(),
        ),
        xml(
          "src/main/res/layout/activity_property_type.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
              android:orientation="vertical" android:layout_width="match_parent"
              android:layout_height="match_parent" />
          """
            .trimIndent(),
        ), // View Binding usage here will reference activity_dot_syntax.xml
        kotlin(
          """
          package my.pkg

          import android.view.LayoutInflater
          import my.pkg.databinding.ActivityDotSyntaxBinding

          class DotSyntaxActivity {
              fun test(inflater: LayoutInflater){
                  ActivityDotSyntaxBinding.inflate(inflater)
              }
          }
          """
            .trimIndent()
        ), // View Binding usage here will reference activity_method_reference.xml
        kotlin(
          """
          package my.pkg

          import android.view.LayoutInflater
          import my.pkg.databinding.ActivityMethodReferenceBinding

          class MethodReferenceActivity {
              fun test(inflater: LayoutInflater){
                  ActivityMethodReferenceBinding::inflate
              }
          }
          """
            .trimIndent()
        ), // View Binding usage here will reference activity_method_import.xml
        kotlin(
          """
          package my.pkg

          import android.view.LayoutInflater
          import my.pkg.databinding.ActivityMethodImportBinding.inflate

          class MethodImportActivity {
              fun test(inflater: LayoutInflater){
                  inflate(inflater)
              }
          }
          """
            .trimIndent()
        ), // View Binding usage here will reference activity_property_type.xml
        kotlin(
          """
          package my.pkg

          import android.view.LayoutInflater
          import my.pkg.databinding.ActivityPropertyTypeBinding

          class PropertyTypeBinding {
              private lateinit var binding: ActivityPropertyTypeBinding
          }
          """
            .trimIndent()
        ), // Here we provide code that would have been generated for view binding /
        // provided by the view binding library

        java(
          """
          package my.pkg.databinding;

          import android.view.LayoutInflater;

          public final class ActivityDotSyntaxBinding implements androidx.viewbinding.ViewBinding {
            public static ActivityDotSyntaxBinding inflate(LayoutInflater inflater) {
              return this;
            }
          }
          """
            .trimIndent()
        ),
        java(
          """
          package my.pkg.databinding;

          import android.view.LayoutInflater;

          public final class ActivityMethodReferenceBinding implements androidx.viewbinding.ViewBinding {
            public static ActivityMethodReferenceBinding inflate(LayoutInflater inflater) {
              return this;
            }
          }
          """
            .trimIndent()
        ),
        java(
          """
          package my.pkg.databinding;

          import android.view.LayoutInflater;

          public final class ActivityMethodImportBinding implements androidx.viewbinding.ViewBinding {
            public static ActivityMethodImportBinding inflate(LayoutInflater inflater) {
              return this;
            }
          }
          """
            .trimIndent()
        ),
        java(
          """
          package my.pkg.databinding;

          import android.view.LayoutInflater;

          public final class ActivityPropertyTypeBinding implements androidx.viewbinding.ViewBinding {
            public static ActivityPropertyTypeBinding inflate(LayoutInflater inflater) {
              return this;
            }
          }
          """
            .trimIndent()
        ),
        java(
          """
          package androidx.viewbinding;
          public interface ViewBinding {
          }
          """
            .trimIndent()
        ),
      )
      .clientFactory(gradleClientFactory)
      .run()
      .expectClean()
  }

  fun testViewBindingPropertyDelegation() {
    // Regression test in 203123034: Lint UnusedResources incorrectly fails when using
    // ViewBinding via property delegation
    lint()
      .files(
        gradle(
          """
          buildscript {
            dependencies {
              classpath "com.android.tools.build:gradle:7.0.3"
            }
          }

          android {
              buildFeatures {
                  viewBinding true
              }
          }
          """
            .trimIndent()
        ),
        xml(
          "src/main/res/layout/hello_world.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
              android:orientation="vertical" android:layout_width="match_parent"
              android:layout_height="match_parent" />
          """
            .trimIndent(),
        ),
        kotlin(
          """
          package test.pkg

          import android.content.Context
          import android.util.AttributeSet
          import android.widget.FrameLayout
          import test.pkg.HelloWorldBinding

          class HelloWorldView @JvmOverloads constructor(
              context: Context,
              attrs: AttributeSet? = null,
              defStyleAttr: Int = 0
          ) : FrameLayout(context, attrs, defStyleAttr) {
              private val binding: HelloWorldBinding by viewBinding()

              init {
                  binding.text.setText(R.string.app_name)
              }
          }
          """
            .trimIndent()
        ),
        kotlin(
          """
          package test.pkg

          import android.view.LayoutInflater
          import android.view.ViewGroup
          import androidx.viewbinding.ViewBinding
          import kotlin.properties.ReadOnlyProperty
          import kotlin.reflect.KProperty

          class ViewBindingDelegate<T : ViewBinding>(
              bindingClass: Class<T>,
              view: ViewGroup
          ) : ReadOnlyProperty<ViewGroup, T> {
              private val layoutInflater = LayoutInflater.from(view.context).cloneInContext(view.context)
              private val binding: T = try {
                  val inflateMethod = bindingClass.getMethod("inflate", LayoutInflater::class.java, ViewGroup::class.java, Boolean::class.javaPrimitiveType)
                  inflateMethod.invoke(null, layoutInflater, view, true)!! as T
              } catch (e: NoSuchMethodException) {
                  val inflateMethod = bindingClass.getMethod("inflate", LayoutInflater::class.java, ViewGroup::class.java)
                  inflateMethod.invoke(null, layoutInflater, view)!! as T
              }
              override fun getValue(thisRef: ViewGroup, property: KProperty<*>): T = binding
          }
          inline fun <reified T : ViewBinding> ViewGroup.viewBinding() = ViewBindingDelegate(T::class.java, this)
          """
            .trimIndent()
        ), // Here we provide code that would have been generated for view binding /
        // provided by the view binding library

        java(
          """
          // Generated by view binder compiler. Do not edit!
          package test.pkg;

          import android.view.LayoutInflater;
          import android.view.View;
          import android.view.ViewGroup;
          import android.widget.TextView;
          import androidx.viewbinding.ViewBinding;

          public final class HelloWorldBinding implements ViewBinding {
            public static HelloWorldBinding inflate(LayoutInflater inflater) {
              return null;
            }
            public static HelloWorldBinding inflate(@NonNull LayoutInflater inflater,
                ViewGroup parent, boolean attachToParent) {
              return null;
            }
          }
          """
            .trimIndent()
        ),
        java(
          """
          package androidx.viewbinding;
          public interface ViewBinding {
          }
          """
            .trimIndent()
        ),
      )
      .clientFactory(gradleClientFactory)
      .skipTestModes(TestMode.TYPE_ALIAS)
      .run()
      .expectClean()
  }

  @Suppress("SpellCheckingInspection")
  fun testButterknife() {
    // Regression test for https://issuetracker.google.com/62640956
    lint()
      .files( // Data binding layout
        xml(
          "res/values/colors.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <resources>
              <color name="bgColor">#FF4444</color>
          </resources>
          """
            .trimIndent(),
        ),
        java(
          """
          package my.pkg;
          import butterknife.BindColor;

          public class Reference {
              @BindColor(R2.color.bgColor)
              int bgColor;
          }
          """
            .trimIndent()
        ),
        java(
          """
          package butterknife;
          import java.lang.annotation.*;
          import static java.lang.annotation.ElementType.FIELD;
          import static java.lang.annotation.RetentionPolicy.CLASS;
          @Retention(CLASS) @Target(FIELD)
          public @interface BindColor {
            int value();
          }
          """
            .trimIndent()
        ),
        java(
          """
          package my.pkg;

          public final class R {
              public static final class color {
                  public static final int bgColor=0x7f05001f;
              }
          }
          """
            .trimIndent()
        ),
        java(
          """
          package my.pkg;

          public final class R2 {
              public static final class color {
                  public static final int bgColor=0x7f05001f;
              }
          }
          """
            .trimIndent()
        ),
      )
      .run()
      .expectClean()
  }

  fun testGeneratedResourcesIncluded() {
    // Regression test for https://issuetracker.google.com/72790641
    lint()
      .files(
        gradle(
          """
          android {
              lintOptions {
                  checkGeneratedSources true
              }
          }
          """
            .trimIndent()
        ),
        xml(
          "generated/res/raw/something.xml",
          """
          <resources
                  xmlns:tools="http://schemas.android.com/tools" />
          """
            .trimIndent(),
        ),
      )
      .issues(UnusedResourceDetector.ISSUE)
      .run()
      .expect(
        """
          generated/res/raw/something.xml:1: Warning: The resource R.raw.something appears to be unused [UnusedResources]
          <resources
          ^
          0 errors, 1 warnings
          """
          .trimIndent()
      )
  }

  fun testGeneratedResourcesExcluded() {
    // Regression test for https://issuetracker.google.com/72790641
    lint()
      .files(
        gradle(
          """
          android {
              lintOptions {
                  checkGeneratedSources false
              }
          }
          """
            .trimIndent()
        ),
        xml(
          "generated/res/raw/something.xml",
          """
          <resources
                  xmlns:tools="http://schemas.android.com/tools" />
          """
            .trimIndent(),
        ),
      )
      .issues(UnusedResourceDetector.ISSUE)
      .run()
      .expectClean()
  }

  fun testNoWarningsInGradleLibraries() {
    // Regression test for
    // 78320922: Lint: UnusedResources false positive in library module
    lint()
      .files(
        gradle("apply plugin: 'com.android.library'\n"),
        xml(
          "src/main/res/values/strings.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <resources xmlns:tools="http://schemas.android.com/tools">
              <string name="hello">Hello</string>
          </resources>
          """
            .trimIndent(),
        ),
      )
      .clientFactory(gradleClientFactory)
      .issues(UnusedResourceDetector.ISSUE)
      .run()
      .expectClean()
  }

  fun testSyntheticImports() {
    // Regression test for https://issuetracker.google.com/110175594
    // UnusedIds triggered when using Kotlin Synthetic Properties
    lint()
      .files(
        gradle("apply plugin: 'com.android.application'\n"),
        kotlin(
          """
          package test.pkg
          import android.widget.Button
          import android.widget.TextView
          import kotlinx.android.synthetic.main.fragment_team_list.*
          class Test : android.app.Activity {
              fun test1() {
                  val s = fab1.toString()
              }

              fun test2() {
                  fab2.text = "hello"
                  val hasSelection = fab3.hasSelection()
                  handle(fab4)
                  if (fab5 is Button) {
                      println("weird")
                  }
              }

              fun handle(text: TextView) {
              }
          }
          """
            .trimIndent()
        ),
        xml(
          "src/main/res/values/ids.xml",
          """
          <resources>
              <item name="fab1" type="id"/>
              <item name="fab2" type="id"/>
              <item name="fab3" type="id"/>
              <item name="fab4" type="id"/>
              <item name="fab5" type="id"/>
          </resources>
          """
            .trimIndent(),
        ),
      )
      .clientFactory(gradleClientFactory)
      .issues(UnusedResourceDetector.ISSUE_IDS)
      .run()
      .expectClean()
  }

  fun testFontTags() {
    // Regression test for https://issuetracker.google.com/142182927
    // 142182927: A <font> tag inside a string is treated as an empty resource
    lint()
      .files(
        xml(
          "res/values/strings.xml",
          """
          <resources xmlns:tools="http://schemas.android.com/tools" tools:keep="@string/other">
              <string name="other">Here\'s a <font color="#ffff00">bold</font> prediction</string>
          </resources>
          """
            .trimIndent(),
        )
      )
      .run()
      .expectClean()
  }

  fun testConstraintReferencedIds() {
    // Regression test for
    // 79995034: Lint unused id does not take in account constraint_referenced_ids
    lint()
      .files(
        xml(
          "res/layout/main.xml",
          """
          <merge xmlns:android="http://schemas.android.com/apk/res/android"
              xmlns:app="http://schemas.android.com/apk/res-auto"
              xmlns:tools="http://schemas.android.com/tools"
              tools:keep="@layout/main">
              <Space
                  android:id="@+id/view1"
                  android:layout_width="0dp"
                  android:layout_height="0dp" />

              <androidx.constraintlayout.helper.widget.Flow
                  android:layout_width="0dp"
                  android:layout_height="0dp"
                  app:constraint_referenced_ids="view1"
                  app:flow_maxElementsWrap="3"
                  app:flow_wrapMode="aligned" />
          </merge>
          """
            .trimIndent(),
        )
      )
      .run()
      .expectClean()
  }

  fun testSuspendFunctions() {
    // Regression test for https://issuetracker.google.com/135168818
    lint()
      .files(
        gradle("apply plugin: 'com.android.application'\n"),
        kotlin(
          """
          package test.pkg
          import android.widget.TextView
          class Test : android.app.Activity {
              private suspend fun setUi() {
                  val x = R.string.hello
              }
          }
          """
            .trimIndent()
        ),
        xml(
          "src/main/res/values/strings.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <resources xmlns:tools="http://schemas.android.com/tools">
              <string name="hello">Hello</string>
          </resources>
          """
            .trimIndent(),
        ),
      )
      .issues(UnusedResourceDetector.ISSUE)
      .run()
      .expectClean()
  }

  fun testImportAs() {
    // Regression test for https://issuetracker.google.com/129213521
    lint()
      .files(
        gradle("apply plugin: 'com.android.application'\n"),
        kotlin(
          """
          package test.pkg

          import android.os.Bundle
          import android.app.Activity
          import test.pkg.R as coreR

          class MainActivity : Activity() {

              override fun onCreate(savedInstanceState: Bundle?) {
                  val s = coreR.string.hello
              }
          }
          """
            .trimIndent()
        ),
        java(
          """
          /* AUTO-GENERATED FILE.  DO NOT MODIFY.
           *
           * This class was automatically generated by the
           * aapt tool from the resource data it found.  It
           * should not be modified by hand.
           */

          package test.pkg;

          public final class R {
              public static final class string {
                  public static final int hello=0x7f020000;
              }
          }
          """
            .trimIndent()
        ),
        xml(
          "src/main/res/values/strings.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <resources xmlns:tools="http://schemas.android.com/tools">
              <string name="hello">Hello</string>
          </resources>
          """
            .trimIndent(),
        ),
      )
      .issues(UnusedResourceDetector.ISSUE)
      .run()
      .expectClean()
  }

  fun test120747416() {
    // Regression test for https://issuetracker.google.com/120747416
    // "Unused Resources missing logic for strings with dot in the id"
    lint()
      .files(
        gradle("apply plugin: 'com.android.application'\n"),
        kotlin(
          """
          package test.pkg

          import androidx.annotation.StringRes
          import android.app.Activity
          import android.app.AlertDialog

          fun showDialog(activity: Activity, @StringRes messageId: Int) {
              AlertDialog.Builder(activity)
                      .setMessage(messageId)
                      .create()
                      .show()
          }

          fun test() {
              showDialog(R.string.abc_abc_abc_abc_abc)
          }
          """
            .trimIndent()
        ),
        java(
          """
          package test.pkg;
          public final class R {
              public static final class string {
                  public static final int abc_abc_abc_abc_abc=0x7f020000;
              }
          }
          """
            .trimIndent()
        ),
        xml(
          "src/main/res/values/strings.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <resources xmlns:tools="http://schemas.android.com/tools">
                 <string name="abc_abc.abc.abc_abc">ABC</string>
          </resources>
          """
            .trimIndent(),
        ),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .issues(UnusedResourceDetector.ISSUE)
      .run()
      .expectClean()
  }

  fun test125138962() {
    // Regression test for https://issuetracker.google.com/125138962
    lint()
      .files(
        gradle("apply plugin: 'com.android.application'\n"),
        kotlin(
          """
          package test.pkg

          import android.annotation.SuppressLint
          import android.view.LayoutInflater
          import android.widget.LinearLayout

          class SimpleClass(inflater: LayoutInflater) {
              private var mainContainer: LinearLayout
              init {
                  @SuppressLint("InflateParams")
                  mainContainer = inflater.inflate(R.layout.mosaic_view, null, false) as LinearLayout

              }
          }
          """
            .trimIndent()
        ),
        java(
          """
          package test.pkg;
          public final class R {
              public static final class layout {
                  public static final int mosaic_view=0x7f020000;
              }
          }
          """
            .trimIndent()
        ),
        xml("src/main/res/layout/mosaic_view.xml", "<LinearLayout/>\n"),
      )
      .issues(UnusedResourceDetector.ISSUE)
      .run()
      .expectClean()
  }

  @Throws(Exception::class)
  fun testImportAliases() {
    // Regression test for workaround for https://issuetracker.google.com/188871862
    lint()
      .files(
        manifest().minSdk(21),
        xml(
          "res/values/strings.xml",
          """
          <resources>
              <string name="lib2">String from lib2</string>
          </resources>
          """
            .trimIndent(),
        ),
        kotlin(
          """
          package com.android.tools.test.lib1
          import com.android.tools.test.lib2.R.string.lib2 as String_lib2

          class Lib1 {
              fun test() {
                  println(String_lib2)
              }
          }
          """
            .trimIndent()
        ),
      ) // Deliberately missing imported symbol; this test is checking our fallback handling
      .allowCompilationErrors()
      .run()
      .expectClean()
  }

  fun testDataBinding() {
    // Regression test for 230015328
    lint()
      .files( // Main project
        manifest().pkg("com.android.tools.test.unusedbindingtest").minSdk(21),
        gradle(
          """
          apply plugin: 'com.android.application'
          apply plugin: 'kotlin-android'
          android {
              buildFeatures {
                  viewBinding true
              }
          }
          """
            .trimIndent()
        ),
        xml(
          "src/main/res/layout/unused.xml",
          """
          <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
              android:orientation="vertical"
              android:layout_width="match_parent"
              android:layout_height="match_parent">

          </LinearLayout>
          """
            .trimIndent(),
        ),
        xml(
          "src/main/res/layout/activity_main.xml",
          """
          <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
              android:orientation="vertical"
              android:layout_width="match_parent"
              android:layout_height="match_parent">

          </LinearLayout>
          """
            .trimIndent(),
        ),
        kotlin(
          """
          package com.android.tools.test.unusedbindingtest

          import android.app.Activity
          import android.os.Bundle
          import com.android.tools.test.unusedbindingtest.databinding.ActivityMainBinding

          class MainActivity : Activity() {
              private lateinit var binding: ActivityMainBinding

              override fun onCreate(savedInstanceState: Bundle?) {
                  super.onCreate(savedInstanceState)
                  binding = ActivityMainBinding.inflate(layoutInflater)
                  binding.usedButton.callOnClick()
              }

          }
          """
            .trimIndent()
        ),
        rClass(
          "com.android.tools.test.unusedbindingtest.R",
          "@layout/activity_main",
          "@layout/unused",
        ),
        java(
          """
          // Generated by view binder compiler. Do not edit!
          package com.android.tools.test.unusedbindingtest.databinding;

          import android.view.LayoutInflater;
          import android.view.View;
          import android.view.ViewGroup;
          import android.widget.Button;
          import android.widget.LinearLayout;
          import androidx.annotation.NonNull;
          import androidx.annotation.Nullable;
          import androidx.viewbinding.ViewBinding;
          import androidx.viewbinding.ViewBindings;
          import com.android.tools.test.unusedbindingtest.R;
          import java.lang.NullPointerException;
          import java.lang.Override;
          import java.lang.String;

          public final class ActivityMainBinding implements ViewBinding {
            private LinearLayout rootView;
            public Button usedButton;
            private ActivityMainBinding(@NonNull LinearLayout rootView, @NonNull Button usedButton) {
            }
            @Override
            public LinearLayout getRoot() {
              return rootView;
            }
            public static ActivityMainBinding inflate(@NonNull LayoutInflater inflater) {
              return null;
            }
            public static ActivityMainBinding inflate(@NonNull LayoutInflater inflater,
                @Nullable ViewGroup parent, boolean attachToParent) {
              return null;
            }
            public static ActivityMainBinding bind(@NonNull View rootView) {
               return null;
            }
          }
          """
            .trimIndent()
        ), // View binding stubs
        java(
          """
          package androidx.viewbinding;
          public interface ViewBinding { }
          """
            .trimIndent()
        ),
        java(
          """
          package androidx.viewbinding;
          import android.view.View;
          import android.view.ViewGroup;
          public class ViewBindings {
              public static <T extends View> T findChildViewById(View rootView, int id) {
                  return null;
              }
          }
          """
            .trimIndent()
        ),
        SUPPORT_ANNOTATIONS_JAR, // Library project
        gradle(
          "../lib/build.gradle",
          """
          apply plugin: 'com.android.application'
          apply plugin: 'kotlin-android'
          android {
              buildFeatures {
                  viewBinding false
              }
          }
          """
            .trimIndent(),
        ),
        manifest().pkg("foo.library").minSdk(14).to("../lib/src/main/AndroidManifest.xml"),
        xml(
          "../lib/src/main/res/values/strings.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <resources>

              <string name="string1">String 1</string>

          </resources>
          """
            .trimIndent(),
        ),
      )
      .testModes(TestMode.DEFAULT)
      .run()
      .expect(
        """
          ../lib/src/main/res/values/strings.xml:4: Warning: The resource R.string.string1 appears to be unused [UnusedResources]
              <string name="string1">String 1</string>
                      ~~~~~~~~~~~~~~
          src/main/res/layout/unused.xml:1: Warning: The resource R.layout.unused appears to be unused [UnusedResources]
          <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
          ^
          0 errors, 2 warnings
          """
          .trimIndent()
      )
  }

  fun testBindingClassFieldAccess() {
    lint()
      .files( // Main project
        manifest().pkg("com.android.tools.test.unusedbindingtest").minSdk(21),
        gradle(
          """
          apply plugin: 'com.android.application'
          apply plugin: 'kotlin-android'
          android {
              buildFeatures {
                  viewBinding true
              }
              lintOptions {
                  enable 'UnusedIds'
              }
          }
          """
            .trimIndent()
        ),
        xml(
          "src/main/res/layout/activity_main.xml",
          """
          <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
              xmlns:app="http://schemas.android.com/apk/res-auto"
              xmlns:tools="http://schemas.android.com/tools"
              android:layout_width="match_parent"
              android:layout_height="match_parent"
              tools:context=".MainActivity">

              <TextView
                  android:layout_width="wrap_content"
                  android:id="@+id/label"
                  android:layout_height="wrap_content"
                  android:text="Hello World!"
                  app:layout_constraintBottom_toBottomOf="parent"
                  app:layout_constraintEnd_toEndOf="parent"
                  app:layout_constraintStart_toStartOf="parent"
                  app:layout_constraintTop_toTopOf="parent" />

          </LinearLayout>
          """
            .trimIndent(),
        ),
        kotlin(
          """
          package com.example.bugsample

          import android.os.Bundle
          import com.example.bugsample.databinding.ActivityMainBinding

          class MainActivity : Activity() {
              override fun onCreate(savedInstanceState: Bundle?) {
                  super.onCreate(savedInstanceState)
                  println(ActivityMainBinding.inflate(layoutInflater).label.text)
              }
          }
          """
            .trimIndent()
        ),
        java(
          """
          // Generated by view binder compiler. Do not edit!
          package com.example.bugsample.databinding;

          import android.view.LayoutInflater;
          import android.view.View;
          import android.view.ViewGroup;
          import android.widget.Button;
          import android.widget.LinearLayout;
          import androidx.annotation.NonNull;
          import androidx.annotation.Nullable;
          import androidx.viewbinding.ViewBinding;
          import com.android.tools.test.unusedbindingtest.R;
          import java.lang.NullPointerException;
          import java.lang.Override;
          import java.lang.String;

          public final class ActivityMainBinding implements ViewBinding {
            private LinearLayout rootView;
            public TextView label;
            private ActivityMainBinding(@NonNull LinearLayout rootView, @NonNull TextView label) {
            }
            @Override
            public LinearLayout getRoot() {
              return rootView;
            }
            public static ActivityMainBinding inflate(@NonNull LayoutInflater inflater) {
              return null;
            }
            public static ActivityMainBinding inflate(@NonNull LayoutInflater inflater,
                @Nullable ViewGroup parent, boolean attachToParent) {
              return null;
            }
            public static ActivityMainBinding bind(@NonNull View rootView) {
               return null;
            }
          }
          """
            .trimIndent()
        ),
        java(
          """
          package androidx.viewbinding;
          public abstract class ViewBinding {
          }
          """
            .trimIndent()
        ),
      )
      .testModes(TestMode.DEFAULT)
      .run()
      .expect("No warnings.")
  }

  fun testBindingClassFieldAccessWithImplicitReceiver() {
    lint()
      .files( // Main project
        manifest().pkg("com.android.tools.test.unusedbindingtest").minSdk(21),
        gradle(
          """
          apply plugin: 'com.android.application'
          apply plugin: 'kotlin-android'
          android {
              buildFeatures {
                  viewBinding true
              }
              lintOptions {
                  enable 'UnusedIds'
              }
          }
          """
            .trimIndent()
        ),
        xml(
          "src/main/res/layout/activity_main.xml",
          """
          <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
              xmlns:app="http://schemas.android.com/apk/res-auto"
              xmlns:tools="http://schemas.android.com/tools"
              android:layout_width="match_parent"
              android:layout_height="match_parent"
              tools:context=".MainActivity">

              <TextView
                  android:layout_width="wrap_content"
                  android:id="@+id/label"
                  android:layout_height="wrap_content"
                  android:text="Hello World!"
                  app:layout_constraintBottom_toBottomOf="parent"
                  app:layout_constraintEnd_toEndOf="parent"
                  app:layout_constraintStart_toStartOf="parent"
                  app:layout_constraintTop_toTopOf="parent" />

          </LinearLayout>
          """
            .trimIndent(),
        ),
        kotlin(
          """
          import android.app.Activity
          import android.os.Bundle
          import com.example.bugsample.databinding.ActivityMainBinding

          class MainActivity : Activity() {
              override fun onCreate(savedInstanceState: Bundle?) {
                  super.onCreate(savedInstanceState)
                  with(ActivityMainBinding.inflate(layoutInflater)) {
                      println(label.text)
                  }
              }
          }
          """
            .trimIndent()
        ),
        java(
          """
          // Generated by view binder compiler. Do not edit!
          package com.example.bugsample.databinding;
          import android.widget.TextView;
          import android.view.LayoutInflater;
          import android.view.View;
          import android.view.ViewGroup;
          import android.widget.Button;
          import android.widget.LinearLayout;
          import androidx.annotation.NonNull;
          import androidx.annotation.Nullable;
          import androidx.viewbinding.ViewBinding;

          public final class ActivityMainBinding extends ViewBinding {
            private LinearLayout rootView;
            public TextView label;
            private ActivityMainBinding(@NonNull LinearLayout rootView, @NonNull TextView label) {
            }
            @Override
            public LinearLayout getRoot() {
              return rootView;
            }
            public static ActivityMainBinding inflate(@NonNull LayoutInflater inflater) {
              return null;
            }
            public static ActivityMainBinding inflate(@NonNull LayoutInflater inflater,
                @Nullable ViewGroup parent, boolean attachToParent) {
              return null;
            }
            public static ActivityMainBinding bind(@NonNull View rootView) {
               return null;
            }
          }
          """
            .trimIndent()
        ),
        java(
          """
          package androidx.viewbinding;
          public abstract class ViewBinding {
              public abstract android.widget.LinearLayout getRoot();
          }
          """
            .trimIndent()
        ),
      )
      .run()
      .expectClean()
  }

  fun testSkipLibrary() {
    // Regression test for b/391955627
    val library =
      project(mLibraryManifest, projectProperties().library(true), mLibraryCode, mLibraryStrings)

    lint().projects(library).run().expectClean()
    lint()
      .projects(library)
      .configureOption(UnusedResourceDetector.SKIP_LIBRARIES, true)
      .run()
      .expectClean()
    lint()
      .projects(library)
      .configureOption(UnusedResourceDetector.SKIP_LIBRARIES, false)
      .run()
      .expect(
        """
          res/values/strings.xml:6: Warning: The resource R.string.string2 appears to be unused [UnusedResources]
              <string name="string2">String 2</string>
                      ~~~~~~~~~~~~~~
          0 errors, 1 warning
          """
          .trimIndent()
      )
  }

  // Sample code
  private val mAccessibility =
    xml(
      "res/layout/accessibility.xml",
      """
      <?xml version="1.0" encoding="utf-8"?>
      <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:id="@+id/newlinear" android:orientation="vertical" android:layout_width="match_parent" android:layout_height="match_parent">
          <Button android:text="Button" android:id="@+id/button1" android:layout_width="wrap_content" android:layout_height="wrap_content"></Button>
          <ImageView android:id="@+id/android_logo" android:layout_width="wrap_content" android:layout_height="wrap_content" android:src="@drawable/android_button" android:focusable="false" android:clickable="false" android:layout_weight="1.0" />
          <ImageButton android:importantForAccessibility="yes" android:id="@+id/android_logo2" android:layout_width="wrap_content" android:layout_height="wrap_content" android:src="@drawable/android_button" android:focusable="false" android:clickable="false" android:layout_weight="1.0" />
          <Button android:text="Button" android:id="@+id/button2" android:layout_width="wrap_content" android:layout_height="wrap_content"></Button>
          <Button android:id="@android:id/summary" android:contentDescription="@string/label" />
          <ImageButton android:importantForAccessibility="no" android:layout_width="wrap_content" android:layout_height="wrap_content" android:src="@drawable/android_button" android:focusable="false" android:clickable="false" android:layout_weight="1.0" />
      </LinearLayout>
      """
        .trimIndent(),
    )

  private val mLayout1 = xml("res/layout/main.xml", LAYOUT_XML)
  private val mOther = xml("res/layout/other.xml", LAYOUT_XML)

  // Sample code
  private val mR =
    java(
      """
      package my.pkg;

      public final class R {
          public static final class attr {
          }
          public static final class drawable {
              public static final int ic_launcher=0x7f020000;
          }
          public static final class id {
              public static final int button1=0x7f050000;
              public static final int button2=0x7f050004;
              public static final int imageView1=0x7f050003;
              public static final int include1=0x7f050005;
              public static final int linearLayout1=0x7f050001;
              public static final int linearLayout2=0x7f050002;
          }
          public static final class layout { // Not final: happens in libraries. Make sure we handle it correctly.
              public static int main=0x7f030000;
              public static int other=0x7f030001;
          }
          public static final class string {
              public static final int app_name=0x7f040001;
              public static final int hello=0x7f040000;
          }
      }
      """
        .trimIndent()
    )

  // Sample code
  private val mTest =
    java(
      """
      package my.pgk;

      class Test {
         private static String s = " R.id.button1 \" "; // R.id.button1 should not be considered referenced
         static {
             System.out.println(R.id.button2);
             char c = '"';
             System.out.println(R.id.linearLayout1);
         }
      }
      """
        .trimIndent()
    )

  private val mLibraryManifest =
    xml(
      "AndroidManifest.xml",
      """
      <?xml version="1.0" encoding="utf-8"?>
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
          package="foo.library"
          android:versionCode="1"
          android:versionName="1.0" >

          <uses-sdk android:minSdkVersion="14" />

          <application
              android:icon="@drawable/ic_launcher"
              android:label="@string/app_name" >
              <activity
                  android:name=".LibraryProjectActivity"
                  android:label="@string/app_name" >
                  <intent-filter>
                      <action android:name="android.intent.action.MAIN" />

                      <category android:name="android.intent.category.LAUNCHER" />
                  </intent-filter>
              </activity>

              <!-- Sample string references for unused resource check -->
              <meta-data
                  android:name="com.google.android.backup.api_key"
                  android:value="@string/string3" />
              <meta-data
                  android:name="foo"
                  android:value="@string/string1" />
          </application>

      </manifest>
      """
        .trimIndent(),
    )

  private val mLibraryStrings =
    xml(
      "res/values/strings.xml",
      """
      <?xml version="1.0" encoding="utf-8"?>
      <resources>

          <string name="app_name">LibraryProject</string>
          <string name="string1">String 1</string>
          <string name="string2">String 2</string>
          <string name="string3">String 3</string>

      </resources>
      """
        .trimIndent(),
    )

  private val mLibraryCode =
    java(
      "src/foo/library/LibraryCode.java",
      """
      package foo.library;

      public class LibraryCode {
          static {
              System.out.println(R.string.string1);
          }
      }
      """
        .trimIndent(),
    )

  private val mMainCode =
    java(
      "src/foo/main/MainCode.java",
      """
      package foo.main;

      public class MainCode {
          static {
              System.out.println(R.string.string2);
          }
      }
      """
        .trimIndent(),
    )

  private val mStrings2 =
    xml(
      "res/values/strings2.xml",
      """
      <?xml version="1.0" encoding="utf-8"?>
      <resources>
          <string name="hello">Hello</string>
      </resources>

      """
        .trimIndent(),
    )

  companion object {
    @Language("XML")
    private val LAYOUT_XML =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
          android:layout_width="match_parent"
          android:layout_height="match_parent"
          android:orientation="vertical" >

          <include
              android:layout_width="wrap_content"
              android:layout_height="wrap_content"
              layout="@layout/layout2" />

          <Button
              android:id="@+id/button1"
              android:layout_width="wrap_content"
              android:layout_height="wrap_content"
              android:text="Button" />

          <Button
              android:id="@+id/button2"
              android:layout_width="wrap_content"
              android:layout_height="wrap_content"
              android:text="Button" />

      </LinearLayout>
      """
        .trimIndent()

    private val gradleClientFactory =
      TestLintTask.ClientFactory {
        com.android.tools.lint.checks.infrastructure.TestLintClient(
          LintClient.Companion.CLIENT_GRADLE
        )
      }
  }
}
