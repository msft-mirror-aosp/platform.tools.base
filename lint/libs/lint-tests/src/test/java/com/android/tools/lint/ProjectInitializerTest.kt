/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.tools.lint

import com.android.SdkConstants.ANDROID_MANIFEST_XML
import com.android.SdkConstants.ANDROID_URI
import com.android.SdkConstants.ATTR_NAME
import com.android.SdkConstants.FN_PUBLIC_TXT
import com.android.SdkConstants.FN_RESOURCE_TEXT
import com.android.testutils.TestUtils
import com.android.tools.lint.LintCliFlags.ERRNO_INVALID_ARGS
import com.android.tools.lint.LintCliFlags.ERRNO_SUCCESS
import com.android.tools.lint.checks.AbstractCheckTest.SUPPORT_ANNOTATIONS_JAR
import com.android.tools.lint.checks.AbstractCheckTest.base64gzip
import com.android.tools.lint.checks.AbstractCheckTest.jar
import com.android.tools.lint.checks.infrastructure.KlibTestFile
import com.android.tools.lint.checks.infrastructure.LintDetectorTest.bytes
import com.android.tools.lint.checks.infrastructure.LintDetectorTest.compiled
import com.android.tools.lint.checks.infrastructure.ProjectDescription
import com.android.tools.lint.checks.infrastructure.ProjectDescription.Type.LIBRARY
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles.java
import com.android.tools.lint.checks.infrastructure.TestFiles.klib
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestFiles.kt
import com.android.tools.lint.checks.infrastructure.TestFiles.manifest
import com.android.tools.lint.checks.infrastructure.TestFiles.source
import com.android.tools.lint.checks.infrastructure.TestFiles.xml
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.checks.infrastructure.dos2unix
import com.android.tools.lint.client.api.LintListener
import com.android.tools.lint.client.api.LintListener.EventType.REGISTERED_PROJECT
import com.android.tools.lint.client.api.LintListener.EventType.SCANNING_FILE
import com.android.tools.lint.client.api.LintListener.EventType.STARTING
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Project.DependencyKind
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.detector.api.XmlContext
import com.android.utils.XmlUtils.getFirstSubTagByName
import com.google.common.io.Files
import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.util.asSafely
import java.io.File
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.streams.toList
import kotlin.test.fail
import kotlin.text.Charsets
import org.intellij.lang.annotations.Language
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.fir.utils.KaFirCacheCleaner
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.uast.UBinaryExpression
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UDeclarationsExpression
import org.jetbrains.uast.UFile
import org.jetbrains.uast.ULocalVariable
import org.jetbrains.uast.UUnaryExpression
import org.jetbrains.uast.tryResolve
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectInitializerTest {
  @Test
  fun testManualProject() {
    val library =
      project(
          manifest(
              """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="foo.bar2"
                    android:versionCode="1"
                    android:versionName="1.0" >

                    <uses-sdk android:minSdkVersion="14" />

                    <permission android:name="bar.permission.SEND_SMS"
                        android:label="@string/foo"
                        android:description="@string/foo" />

                    <application
                        android:icon="@drawable/ic_launcher"
                        android:label="@string/app_name" >
                    </application>

                </manifest>"""
            )
            .indented(),
          java(
              "src/test/pkg/Loader.java",
              """
                package test.pkg;

                @SuppressWarnings("ClassNameDiffersFromFileName")
                public abstract class Loader<P> {
                    private P mParam;

                    public abstract void loadInBackground(P val);

                    public void load() {
                        // Invoke a method that takes a generic type.
                        loadInBackground(mParam);
                    }
                }""",
            )
            .indented(),
          java(
              "src/test/pkg/NotInProject.java",
              """
                package test.pkg;

                @SuppressWarnings("ClassNameDiffersFromFileName")
                public class Foo {
                    private String foo = "/sdcard/foo";
                }
                """,
            )
            .indented(),
        )
        .type(LIBRARY)
        .name("Library")

    val main =
      project(
          manifest(
              """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="foo.bar2"
                    android:versionCode="1"
                    android:versionName="1.0" >

                    <uses-sdk android:minSdkVersion="14" />

                    <permission android:name="foo.permission.SEND_SMS"
                        android:label="@string/foo"
                        android:description="@string/foo" />

                    <application
                        android:icon="@drawable/ic_launcher"
                        android:label="@string/app_name" >
                    </application>

                </manifest>
                """
            )
            .indented(),
          xml(
              "res/values/strings.xml",
              """
                <resources>
                    <string name="string1">String 1</string>
                    <string name="string1">String 2</string>
                    <string name="string3">String 3</string>
                    <string name="string3">String 4</string>
                </resources>
                """,
            )
            .indented(),
          xml(
              "res/values/not_in_project.xml",
              """
                <resources>
                    <string name="string2">String 1</string>
                    <string name="string2">String 2</string>
                </resources>
                """,
            )
            .indented(),
          java(
              "test/Test.java",
              """
                @SuppressWarnings({"MethodMayBeStatic", "ClassNameDiffersFromFileName"})
                public class Test {
                  String path = "/sdcard/file";
                }""",
            )
            .indented(),
          java(
              "generated/Generated.java",
              """
                @SuppressWarnings({"MethodMayBeStatic", "ClassNameDiffersFromFileName"})
                public class Test {
                  String path = "/sdcard/file";
                }""",
            )
            .indented(),
        )
        .name("App")
        .dependsOn(library)

    val root = temp.newFolder().canonicalFile.absoluteFile

    val configFile = File(root, "lint.xml")
    @Language("XML")
    val config =
      """
            <lint
                checkTestSources='false'
                ignoreTestSources='false'
                checkGeneratedSources='true'
                explainIssues='false'
            >
                <issue id="CheckResult" severity="error"/>
                <!-- Reduce severity of UniquePermission from error to warning -->
                <issue id="UniquePermission" severity="warning"/>
            </lint>
            """
    configFile.parentFile?.mkdirs()
    configFile.writeText(config.trimIndent())

    val projects = lint().projects(main, library).createProjects(root)
    // 1: test infrastructure will sort projects by dependency graph
    val appProjectDir = projects[1]
    val appProjectPath = appProjectDir.path

    // TO avoid already existing temp folders
    val suffix = if (useFirUast()) "-k2" else "-k1"
    val sdk = temp.newFolder("fake-sdk$suffix")
    val cacheDir = temp.newFolder("cache$suffix")
    @Language("XML")
    val mergedManifestXml =
      """

      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
          package="foo.bar2"
          android:versionCode="1"
          android:versionName="1.0" >

          <uses-sdk android:minSdkVersion="14" />

          <permission
              android:name="foo.permission.SEND_SMS"
              android:description="@string/foo"
              android:label="@string/foo" />
          <permission
              android:name="bar.permission.SEND_SMS"
              android:description="@string/foo"
              android:label="@string/foo" />

          <application
              android:icon="@drawable/ic_launcher"
              android:label="@string/app_name" >
          </application>

      </manifest>
      """
        .trimIndent()

    val mergedManifest = temp.newFile("merged-manifest$suffix")
    Files.asCharSink(mergedManifest, Charsets.UTF_8).write(mergedManifestXml)

    @Language("XML")
    val baselineXml =
      """
      <issues format="4" by="lint unknown">
          <issue
              id="DuplicateDefinition"
              message="`string3` has already been defined in this folder"
              errorLine1="    &lt;string name=&quot;string3&quot;>String 4&lt;/string>"
              errorLine2="            ~~~~~~~~~~~~~~">
              <location
                  file="res/values/strings.xml"
                  line="8"
                  column="13"/>
              <location
                  file="res/values/strings.xml"
                  line="5"
                  column="13"/>
          </issue>
      </issues>
      """
        .trimIndent()
    val baseline = File(appProjectDir, "baseline.xml")
    Files.asCharSink(baseline, Charsets.UTF_8).write(baselineXml)

    @Language("XML")
    val descriptor =
      """
            <project>
            <root dir="$root" />
            <sdk dir='$sdk'/>
            <cache dir='$cacheDir'/>
            <classpath jar="test.jar" />
            <baseline file='$baseline' />
            <module name="$appProjectPath:App" android="true" library="false" compile-sdk-version='18.1'>
              <manifest file="AndroidManifest.xml" />
              <resource file="res/values/strings.xml" />
              <src file="test/Test.java" test="true" />
              <src file="generated/Generated.java" generated="true" />
              <dep module="Library" />
            </module>
            <module name="Library" android="true" library="true" compile-sdk-version='android-M'>
              <manifest file="Library/AndroidManifest.xml" />
              <merged-manifest file='$mergedManifest'/>
              <src file="Library/src/test/pkg/Loader.java" />
            </module>
            </project>
            """
        .trimIndent()
    Files.asCharSink(File(root, "project.xml"), Charsets.UTF_8).write(descriptor)

    var assertionsChecked = 0
    val listener = LintListener { driver, type, project, _ ->
      val client = driver.client
      when (type) {
        REGISTERED_PROJECT -> {
          assertThat(project).isNotNull()
          project!!
          assertThat(project.name).isEqualTo("$appProjectPath:App")
          assertThat(project.buildSdkLevel.toString()).isEqualTo("18.1")
          assertThat(project.buildSdk).isEqualTo(18)
          assertionsChecked++

          // Lib project
          val libProject = project.directLibraries[0]
          assertThat(libProject.name).isEqualTo("Library")

          val manifest = client.getMergedManifest(libProject)
          assertThat(manifest).isNotNull()
          manifest!!
          val permission = getFirstSubTagByName(manifest.documentElement, "permission")!!
          assertThat(permission.getAttributeNS(ANDROID_URI, ATTR_NAME)).isEqualTo("foo.permission.SEND_SMS")
          assertionsChecked++

          // compileSdkVersion=android-M -> build API=23
          assertThat(libProject.buildSdk).isEqualTo(23)
          assertionsChecked++
        }
        STARTING -> {
          // Check extra metadata is handled right
          assertThat(client.getSdkHome()).isEqualTo(sdk)
          assertThat(client.getCacheDir(null, false)).isEqualTo(cacheDir)
          assertionsChecked += 2
        }
        else -> {
          // Ignored
        }
      }
    }

    val canonicalRoot = root.canonicalPath

    // TODO: https://youtrack.jetbrains.com/issue/KT-57715
    val expectedError =
      if (useFirUast()) "WARN: ROOT/test.jar: ROOT/test.jar\n" + "java.nio.file.NoSuchFileException: ROOT/test.jar"
      else "w: Classpath entry points to a non-existent location: ROOT/test.jar"

    MainTest.checkDriver(
      """
      baseline.xml: Hint: 1 error was filtered out because it is listed in the baseline file, baseline.xml [LintBaseline]
      project.xml:5: Error: test.jar (relative to ROOT) does not exist [LintError]
      <classpath jar="test.jar" />
      ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
      res/values/strings.xml:3: Error: string1 has already been defined in this folder [DuplicateDefinition]
          <string name="string1">String 2</string>
                  ~~~~~~~~~~~~~~
          res/values/strings.xml:2: Previously defined here
      generated/Generated.java:3: Warning: Do not hardcode "/sdcard/"; use Environment.getExternalStorageDirectory().getPath() instead [SdCardPath]
        String path = "/sdcard/file";
                      ~~~~~~~~~~~~~~
      ../Library/AndroidManifest.xml:8: Warning: Permission name SEND_SMS is not unique (appears in both foo.permission.SEND_SMS and bar.permission.SEND_SMS) [UniquePermission]
          <permission android:name="bar.permission.SEND_SMS"
                      ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
          AndroidManifest.xml:8: Previous permission here
      2 errors, 2 warnings (and 1 error filtered by baseline baseline.xml)
      """,
      expectedError,

      // Expected exit code
      ERRNO_SUCCESS,

      // Args
      arrayOf(
        if (useFirUast()) "" else "--XuseK1Uast",
        "--check",
        "UniquePermission,DuplicateDefinition,SdCardPath",
        "--config",
        configFile.path,
        "--text",
        "stdout",
        "--project",
        File(root, "project.xml").path,
      ),
      { it.replace(canonicalRoot, "ROOT").replace(root.path, "ROOT").replace(baseline.parentFile.path, "TESTROOT").dos2unix() },
      listener,
      null,
      false,
    )

    // Make sure we hit all our checks with the listener
    assertThat(assertionsChecked).isEqualTo(5)
  }

  @Test
  fun testManualProjectErrorHandling() {
    val root = temp.newFolder().canonicalFile.absoluteFile

    @Language("XML")
    val descriptor =
      """
            <project>
            <sdk dir='${TestUtils.getSdk()}'/>
            <module name="Foo:App" android="true" library="true" javaLanguage="1000" kotlinLanguage="1.3">
              <unknown file="foo.Bar" />
              <resource file="res/values/strings.xml" />
              <dep module="NonExistent" />
            </module>
            </project>
            """
        .trimIndent()
    val folder = File(root, "app")
    folder.mkdirs()
    val projectXml = File(folder, "project.xml")
    Files.asCharSink(projectXml, Charsets.UTF_8).write(descriptor)
    val sourceFile = File(folder, "src/main/java/com/example/Foo.java")
    sourceFile.parentFile.mkdirs()
    Files.asCharSink(sourceFile, Charsets.UTF_8)
      .write(
        """
        package com.example;

        public class Foo {}
        """
          .trimIndent()
      )

    MainTest.checkDriver(
      """
            app: Error: No .class files were found in project "Foo:App", so none of the classfile based checks could be run. Does the project need to be built first? [LintError]
            project.xml:3: Error: Invalid Java language level "1000" [LintError]
            <module name="Foo:App" android="true" library="true" javaLanguage="1000" kotlinLanguage="1.3">
            ^
            project.xml:4: Error: Unexpected tag unknown [LintError]
              <unknown file="foo.Bar" />
              ~~~~~~~~~~~~~~~~~~~~~~~~~~
            3 errors
            """,
      "",
      ERRNO_SUCCESS,
      arrayOf("--project", projectXml.path),
      null,
      null,
    )
  }

  @Test
  fun testManualProjectErrorHandlingWithoutSourceFiles() {
    // Regression test for https://issuetracker.google.com/180408027
    val root = temp.newFolder().canonicalFile.absoluteFile

    @Language("XML")
    val descriptor =
      """
            <project>
            <sdk dir='${TestUtils.getSdk()}'/>
            <module name="Foo:App" android="true" library="true" javaLanguage="1000" kotlinLanguage="1.3">
              <unknown file="foo.Bar" />
              <resource file="res/values/strings.xml" />
              <dep module="NonExistent" />
            </module>
            </project>
            """
        .trimIndent()
    val folder = File(root, "app")
    folder.mkdirs()
    val projectXml = File(folder, "project.xml")
    Files.asCharSink(projectXml, Charsets.UTF_8).write(descriptor)

    MainTest.checkDriver(
      """
            project.xml:3: Error: Invalid Java language level "1000" [LintError]
            <module name="Foo:App" android="true" library="true" javaLanguage="1000" kotlinLanguage="1.3">
            ^
            project.xml:4: Error: Unexpected tag unknown [LintError]
              <unknown file="foo.Bar" />
              ~~~~~~~~~~~~~~~~~~~~~~~~~~
            2 errors
            """,
      "",
      ERRNO_SUCCESS,
      arrayOf("--project", projectXml.path),
      null,
      null,
    )
  }

  @Test
  fun testSimpleProject() {
    val root = temp.newFolder().canonicalFile.absoluteFile
    val projects =
      lint()
        .files(
          java(
              "src/test/pkg/InterfaceMethodTest.java",
              """
                    package test.pkg;

                    @SuppressWarnings({"unused", "ClassNameDiffersFromFileName"})
                    public interface InterfaceMethodTest {
                        void someMethod();
                        default void method2() {
                            System.out.println("test");
                        }
                        static void method3() {
                            System.out.println("test");
                        }
                    }
                    """,
            )
            .indented(),
          java(
              "C.java",
              """
                    import android.app.Fragment;

                    @SuppressWarnings({"MethodMayBeStatic", "ClassNameDiffersFromFileName"})
                    public class C {
                      String path = "/sdcard/file";
                      void test(Fragment fragment) {
                        Object host = fragment.getHost(); // Requires API 23
                      }
                    }""",
            )
            .indented(),
          manifest(
              """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="com.android.tools.lint.test"
                    android:versionCode="1"
                    android:versionName="1.0" >
                    <uses-sdk
                        android:minSdkVersion="15"
                        android:targetSdkVersion="22" />

                </manifest>"""
            )
            .indented(),
          xml(
              "res/values/not_in_project.xml",
              """
                <resources>
                    <string name="string2">String 1</string>
                    <string name="string2">String 2</string>
                </resources>
                """,
            )
            .indented(),
        )
        .createProjects(root)
    val projectDir = projects[0]

    @Language("XML")
    val descriptor =
      """
            <project incomplete="true">
            <sdk dir='${TestUtils.getSdk()}'/>
            <root dir="$projectDir"/>
            <module name="M" android="true" library="true">
                <manifest file="$projectDir/AndroidManifest.xml" />
                <src file="$projectDir/C.java" />
                <src file="$projectDir/src/test/pkg/InterfaceMethodTest.java" />
            </module>
            </project>
            """
        .trimIndent()
    val descriptorFile = File(root, "out1/out2/out3/project.xml")
    descriptorFile.parentFile?.mkdirs()
    Files.asCharSink(descriptorFile, Charsets.UTF_8).write(descriptor)

    MainTest.checkDriver(
      """
            C.java:7: Error: Call requires API level 23 (current min is 15): android.app.Fragment#getHost [NewApi]
                Object host = fragment.getHost(); // Requires API 23
                                       ~~~~~~~
            C.java:5: Warning: Do not hardcode "/sdcard/"; use Environment.getExternalStorageDirectory().getPath() instead [SdCardPath]
              String path = "/sdcard/file";
                            ~~~~~~~~~~~~~~
            AndroidManifest.xml:7: Error: Google Play requires that apps target API level 33 or higher. [ExpiredTargetSdkVersion]
                    android:targetSdkVersion="22" />
                    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
            2 errors, 1 warning
            """,
      "",

      // Expected exit code
      ERRNO_SUCCESS,

      // Args
      arrayOf("--project", descriptorFile.path),
      null,
      null,
    )
  }

  @Test
  fun testPaths() {
    // Regression test for https://issuetracker.google.com/159169803
    val root = temp.newFolder().canonicalFile.absoluteFile
    val projects =
      lint()
        .files(
          xml(
              "layout/AndroidManifest.xml",
              """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="com.android.tools.lint.test"
                    android:versionCode="1"
                    android:versionName="1.0" >
                    <uses-sdk
                        android:minSdkVersion="15"
                        android:targetSdkVersion="29" />

                </manifest>""",
            )
            .indented()
        )
        .createProjects(root)
    val projectDir = projects[0]

    @Language("XML")
    val descriptor =
      """
            <project>
            <sdk dir='${TestUtils.getSdk()}'/>
            <root dir="$projectDir" />
            <module name="M" android="true" library="true">
                <manifest file="layout/AndroidManifest.xml" />
            </module>
            </project>
            """
        .trimIndent()
    val descriptorFile = File(root, "project.xml")
    Files.asCharSink(descriptorFile, Charsets.UTF_8).write(descriptor)

    MainTest.checkDriver(
      "No issues found.",
      "",

      // Expected exit code
      ERRNO_SUCCESS,

      // Args
      arrayOf("--check", "RequiredSize", "--project", descriptorFile.path),
      null,
      null,
    )
  }

  @Test
  fun testGradleDetectorsFiring() { // Regression test for b/132992488
    val root = temp.newFolder().canonicalFile.absoluteFile
    val projects =
      lint()
        .files(
          java(
              "src/main/pkg/MainActivity.java",
              """
                    package pkg;

                    import android.app.Activity;
                    import android.os.Bundle;

                    public class MainActivity extends Activity {
                        @Override
                        public void onCreate(Bundle savedInstanceState) {
                            super.onCreate(savedInstanceState);
                        }
                    }
                    """,
            )
            .indented(),
          manifest(
              """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="com.android.tools.lint.test"
                    android:versionCode="1"
                    android:versionName="1.0" >
                    <uses-sdk
                        android:minSdkVersion="15"
                        android:targetSdkVersion="22" />

                </manifest>"""
            )
            .indented(),
          xml(
              "res/values/strings.xml",
              """
                <resources xmlns:tools="http://schemas.android.com/tools">
                    <string name="nam${'\ufeff'}e">Value</string>
                </resources>""",
            )
            .indented(),
          bytes("res/raw/sample.txt", "a\uFEFFb".toByteArray()),
        )
        .createProjects(root)
    val projectDir = projects[0]

    @Language("XML")
    val descriptor =
      """
            <project incomplete="true">
            <sdk dir='${TestUtils.getSdk()}'/>
            <root dir="$projectDir" />
            <module name="M" android="true" library="true">
                <manifest file="AndroidManifest.xml" />
                <resource file="res/raw/sample.txt" />
                <resource file="res/values/strings.xml" />
                <src file="src/main/pkg/MainActivity.java" />
            </module>
            </project>
            """
        .trimIndent()
    val descriptorFile = File(root, "project.xml")
    Files.asCharSink(descriptorFile, Charsets.UTF_8).write(descriptor)

    MainTest.checkDriver(
      """
            res/values/strings.xml:2: Error: Found byte-order-mark in the middle of a file [ByteOrderMark]
                <string name="nam﻿e">Value</string>
                                 ~
            1 error
            """,
      "",

      // Expected exit code
      ERRNO_SUCCESS,

      // Args
      arrayOf("--check", "ByteOrderMark", "--project", descriptorFile.path),
      null,
      null,
    )
  }

  @Test
  fun testAar() {
    // Check for missing application icon and have that missing icon be supplied by
    // an AAR dependency and make its way into the merged manifest.
    val root = temp.newFolder().canonicalFile.absoluteFile
    val projects =
      lint()
        .files(
          manifest(
              """
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                        package="com.android.tools.lint.test"
                        android:versionCode="1"
                        android:versionName="1.0" >
                        <uses-sdk android:minSdkVersion="14" />
                        <application />

                    </manifest>"""
            )
            .indented(),
          xml(
              "res/values/not_in_project.xml",
              """
                    <resources>
                        <string name="string2">String 1</string>
                        <string name="string2">String 2</string>
                    </resources>
                    """,
            )
            .indented(),
          java(
              "src/main/java/test/pkg/Private.java",
              """package test.pkg;
                    @SuppressWarnings("ClassNameDiffersFromFileName")
                    public class Private {
                        void test() {
                            int x = R.string.my_private_string; // ERROR
                            int y = R.string.my_public_string; // OK
                        }
                    }
                    """,
            )
            .indented(),
        )
        .createProjects(root)
    val projectDir = projects[0]

    val aarFile = temp.newFile("foo-bar.aar")
    aarFile.createNewFile()
    val aar = temp.newFolder("aar-exploded")
    @Language("XML")
    val aarManifest =
      """
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                        package="com.android.tools.lint.test"
                        android:versionCode="1"
                        android:versionName="1.0" >

                        <uses-sdk android:minSdkVersion="14" />
                        <application android:icon='@mipmap/my_application_icon'/>

                    </manifest>"""
    Files.asCharSink(File(aar, "AndroidManifest.xml"), Charsets.UTF_8).write(aarManifest)

    val allResources =
      ("" +
        "int string my_private_string 0x7f040000\n" +
        "int string my_public_string 0x7f040001\n" +
        "int layout my_private_layout 0x7f040002\n" +
        "int id title 0x7f040003\n" +
        "int style Theme_AppCompat_DayNight 0x7f070004")

    val rFile = File(aar, FN_RESOURCE_TEXT)
    Files.asCharSink(rFile, Charsets.UTF_8).write(allResources)

    val publicResources = ("" + "" + "string my_public_string\n" + "style Theme.AppCompat.DayNight\n")

    val publicTxtFile = File(aar, FN_PUBLIC_TXT)
    Files.asCharSink(publicTxtFile, Charsets.UTF_8).write(publicResources)

    @Language("XML")
    val descriptor =
      """
            <project>
            <sdk dir='${TestUtils.getSdk()}'/>
            <root dir="$projectDir" />
                <module name="M" android="true" library="false">
                <manifest file="AndroidManifest.xml" />
                <src file="src/main/java/test/pkg/Private.java" />
                <aar file="$aarFile" extracted="$aar" />
            </module>
            </project>
            """
        .trimIndent()
    val descriptorFile = File(root, "project.xml")
    Files.asCharSink(descriptorFile, Charsets.UTF_8).write(descriptor)

    MainTest.checkDriver(
      "" +
        "src/main/java/test/pkg/Private.java:5: Warning: The resource @string/my_private_string is marked as private in foo-bar.aar [PrivateResource]\n" +
        "                            int x = R.string.my_private_string; // ERROR\n" +
        "                                    ~~~~~~~~~~~~~~~~~~~~~~~~~~\n" +
        "0 errors, 1 warning\n",
      "",

      // Expected exit code
      ERRNO_SUCCESS,

      // Args
      arrayOf("--check", "MissingApplicationIcon,PrivateResource", "--project", descriptorFile.path),
      { it.dos2unix() },
      null,
    )
  }

  @Test
  fun testJar() {
    // Check for missing application icon and have that missing icon be supplied by
    // an AAR dependency and make its way into the merged manifest.
    val root = temp.newFolder().canonicalFile.absoluteFile
    val projects =
      lint()
        .files(
          java(
              "src/test/pkg/Child.java",
              """
                package test.pkg;

                import android.os.Parcel;

                @SuppressWarnings({"ClassNameDiffersFromFileName", "MethodMayBeStatic"})
                public class Child extends Parent {
                    @Override
                    public int describeContents() {
                        return 0;
                    }

                    @Override
                    public void writeToParcel(Parcel dest, int flags) {

                    }
                }
                """,
            )
            .indented()
        )
        .createProjects(root)
    val projectDir = projects[0]

    /*
    Compiled from
        package test.pkg;
        import android.os.Parcelable;
        public abstract class Parent implements Parcelable {
        }
     */
    val jarFile =
      jar(
          "parent.jar",
          base64gzip(
            "test/pkg/Parent.class",
            "" +
              "H4sIAAAAAAAAAF1Pu07DQBCcTRw7cQx5SHwAXaDgipQgmkhUFkRKlP5sn8IF" +
              "cxedL/wXFRJFPoCPQuw5qdBKo53Z2R3tz+/3EcAc0xRdXCYYJRgnmBDiB220" +
              "fyR0ZzcbQrSwlSKMcm3U8+G9UG4ti5qVaW5LWW+k04Gfxci/6oYwyb1qvNi/" +
              "bcVSOmX8PSFd2YMr1ZMOvuFJvtvJD5mhh5gT/q0QxmEqamm24qXYqZKlK2kq" +
              "Z3UlbBNspapDbnSNDn/B8fwScfFBxoSZaDnQu/0CfXLTQZ8xPokYMGbnPsWw" +
              "Xc9a18UfxkO3QyIBAAA=",
          ),
        )
        .createFile(root)

    @Language("XML")
    val descriptor =
      """
            <project>
            <sdk dir='${TestUtils.getSdk()}'/>
            <root dir="$projectDir" />
                <module name="M" android="true" library="false">
                <jar file="$jarFile" />
                <src file="src/test/pkg/Child.java" />
            </module>
            </project>
            """
        .trimIndent()
    val descriptorFile = File(root, "project.xml")
    Files.asCharSink(descriptorFile, Charsets.UTF_8).write(descriptor)

    MainTest.checkDriver(
      "" +
        // We only find this error if we correctly include the jar dependency
        // which provides the parent class which implements Parcelable.
        "src/test/pkg/Child.java:6: Error: This class implements Parcelable but does not provide a CREATOR field [ParcelCreator]\n"
          .replace('/', File.separatorChar) +
        "public class Child extends Parent {\n" +
        "             ~~~~~\n" +
        "1 error\n",
      "",

      // Expected exit code
      ERRNO_SUCCESS,

      // Args
      arrayOf("--check", "ParcelCreator", "--project", descriptorFile.path),
      null,
      null,
    )
  }

  @Test
  fun testClasspathJar() {
    // Ensure that class path jars are properly included for type resolution
    val root = temp.newFolder().canonicalFile.absoluteFile

    val projects =
      lint()
        .files(
          java(
              """
                    package test.pkg;

                    import androidx.annotation.RequiresApi;
                    import android.util.Log;

                    @SuppressWarnings({"ClassNameDiffersFromFileName", "MethodMayBeStatic"})
                    public class RequiresApiFieldTest {
                        @RequiresApi(24)
                        private int Method24() {
                            return 42;
                        }

                        private void ReferenceMethod24() {
                            Log.d("zzzz", "ReferenceField24: " + Method24());
                        }
                    }
                    """
            )
            .indented(),
          SUPPORT_ANNOTATIONS_JAR,
          xml(
              "project.xml",
              """
            <project>
            <sdk dir='${TestUtils.getSdk()}'/>
            <module name="M" android="true" library="false">
            <classpath jar="libs/support-annotations.jar" />
            <src file="src/test/pkg/RequiresApiFieldTest.java" />
            </module>
            </project>
            """,
            )
            .indented(),
        )
        .createProjects(root)
    val projectDir = projects[0]
    val descriptorFile = File(projectDir, "project.xml")

    MainTest.checkDriver(
      "" +
        // We only find this error if we correctly include the jar dependency
        // which provides the parent class which implements Parcelable.
        "src/test/pkg/RequiresApiFieldTest.java:14: Error: Call requires API level 24 (current min is 1): Method24 [NewApi]\n" +
        "        Log.d(\"zzzz\", \"ReferenceField24: \" + Method24());\n" +
        "                                             ~~~~~~~~\n" +
        "1 error\n",
      "",

      // Expected exit code
      ERRNO_SUCCESS,

      // Args
      arrayOf("--check", "NewApi", "--project", descriptorFile.path),
      { it.dos2unix() },
      null,
    )
  }

  @Test
  fun testNonAndroidProject() {
    val root = temp.newFolder().canonicalFile.absoluteFile
    val projects =
      lint()
        .files(
          java(
              "C.java",
              """
                    @SuppressWarnings({"MethodMayBeStatic", "ClassNameDiffersFromFileName"})
                    public class C {
                      String path = "/sdcard/file";
                    }""",
            )
            .indented()
        )
        .createProjects(root)
    val projectDir = projects[0]

    @Language("XML")
    val descriptor =
      """
            <project incomplete="true">
            <sdk dir='${TestUtils.getSdk()}'/>
            <root dir="$projectDir" />
            <module name="M" android="false" library="true">
                <src file="C.java" />
            </module>
            </project>
            """
        .trimIndent()
    val descriptorFile = File(root, "project.xml")
    Files.asCharSink(descriptorFile, Charsets.UTF_8).write(descriptor)

    MainTest.checkDriver(
      "No issues found.",
      "",

      // Expected exit code
      ERRNO_SUCCESS,

      // Args
      arrayOf("--project", descriptorFile.path),
      null,
      null,
    )
  }

  @Test
  fun testJava8Libraries() {
    val root = temp.newFolder().canonicalFile.absoluteFile
    val projects =
      lint()
        .files(
          java(
              "C.java",
              """
                    import java.util.ArrayList;
                    import java.util.Arrays;
                    import java.util.Iterator;
                    import java.util.stream.Stream;

                    @SuppressWarnings({"unused", "SimplifyStreamApiCallChains",
                        "OptionalGetWithoutIsPresent", "OptionalUsedAsFieldOrParameterType",
                        "ClassNameDiffersFromFileName", "MethodMayBeStatic"})
                    public class C {
                        public void utils(java.util.Collection<String> collection) {
                            collection.removeIf(s -> s.length() > 5);
                        }

                        public void streams(ArrayList<String> list, String[] array) {
                            list.stream().forEach(s -> System.out.println(s.length()));
                            Stream<String> stream = Arrays.stream(array);
                        }

                        public void bannedMembers(java.util.Collection collection) {
                            Stream stream = collection.parallelStream(); // ERROR
                        }
                    }
                    """,
            )
            .indented()
        )
        .createProjects(root)
    val projectDir = projects[0]

    @Language("XML")
    val descriptor =
      """
            <project>
            <sdk dir='${TestUtils.getSdk()}'/>
            <root dir="$projectDir" />
            <!-- We could have specified desugar="full" instead of specifying android_java8_libs -->
            <module desugar="default" android_java8_libs="true" name="M" android="true" library="false">
                <src file="C.java" />
            </module>
            </project>
            """
        .trimIndent()
    val descriptorFile = File(root, "project.xml")
    Files.asCharSink(descriptorFile, Charsets.UTF_8).write(descriptor)

    MainTest.checkDriver(
      """
            C.java:20: Error: Call requires API level 24 (current min is 1): java.util.Collection#parallelStream [NewApi]
                    Stream stream = collection.parallelStream(); // ERROR
                                               ~~~~~~~~~~~~~~
            1 error
            """,
      "",

      // Expected exit code
      ERRNO_SUCCESS,

      // Args
      arrayOf("--check", "NewApi", "--project", descriptorFile.path),
      null,
      null,
    )
  }

  @Test
  fun testExternalAnnotations() {
    // Checks that external annotations support works
    val root = temp.newFolder().canonicalFile.absoluteFile

    val projects =
      lint()
        .projects(
          project(
              java(
                  """
                    package test.pkg;
                    import androidx.annotation.WorkerThread;

                    public class Client {
                        @WorkerThread
                        public static void client() {
                            new test.pkg1.Library1().method1();
                            new test.pkg2.Library2().method2();
                        }
                    }
                    """
                )
                .indented(),
              java(
                  """
                package test.pkg1;

                public class Library1 {
                    public void method1() { // externally annotated as @UiThread
                    }
                }
                """
                )
                .indented(),
              java(
                  """
                package test.pkg2;

                public class Library2 {
                    public void method2() { // externally annotated as @UiThread
                    }
                }
                """
                )
                .indented(),
              SUPPORT_ANNOTATIONS_JAR,
              // zip annotations file
              jar(
                "annotations.zip",
                xml(
                    "test/pkg1/annotations.xml",
                    """
                    <root>
                      <item name="test.pkg1.Library1 void method1()">
                        <annotation name="androidx.annotation.UiThread"/>
                      </item>
                    </root>
                    """,
                  )
                  .indented(),
              ),
              // dir annotation files
              xml(
                  "external-annotations/test/pkg2/annotations.xml",
                  """
                <root>
                  <item name="test.pkg2.Library2 void method2()">
                    <annotation name="androidx.annotation.UiThread"/>
                  </item>
                </root>
                """,
                )
                .indented(),
              xml(
                  "project.xml",
                  """
                <project>
                <root dir="$root/project" />
                <sdk dir='${TestUtils.getSdk()}'/>
                <annotations file="annotations.zip"/>
                <annotations dir="external-annotations"/>
                <module name="M" android="true" library="false">
                <classpath jar="libs/support-annotations.jar" />
                <src file="src/test/pkg1/Library1.java" />
                <src file="src/test/pkg2/Library2.java" />
                <src file="src/test/pkg/Client.java" />
                </module>
                </project>
            """,
                )
                .indented(),
            )
            .name("project")
        )
        .createProjects(root)
    val projectDir = projects[0]
    val descriptorFile = File(projectDir, "project.xml")

    MainTest.checkDriver(
      "" +
        "src/test/pkg/Client.java:7: Error: Method method1 must be called from the UI thread, currently inferred thread is worker thread [WrongThread]\n" +
        "        new test.pkg1.Library1().method1();\n" +
        "        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~\n" +
        "src/test/pkg/Client.java:8: Error: Method method2 must be called from the UI thread, currently inferred thread is worker thread [WrongThread]\n" +
        "        new test.pkg2.Library2().method2();\n" +
        "        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~\n" +
        "2 errors",
      "",

      // Expected exit code
      ERRNO_SUCCESS,

      // Args
      arrayOf("--check", "WrongThread", "--project", descriptorFile.path),
      { it.dos2unix() },
      null,
    )
  }

  @Test
  fun testJava14() {
    // Tests Java language support for some recent features, such as
    // switch expressions
    val root = temp.newFolder().canonicalFile.absoluteFile

    val projects =
      lint()
        .projects(
          project(
              java(
                  """
                package test.pkg;
                import androidx.annotation.IntDef;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                public class Java14Test {
                    @IntDef({LENGTH_INDEFINITE, LENGTH_SHORT, LENGTH_LONG})
                    @Retention(RetentionPolicy.SOURCE)
                    public @interface Duration {
                    }
                    public static final int LENGTH_INDEFINITE = -2;
                    public static final int LENGTH_SHORT = -1;
                    public static final int LENGTH_LONG = 0;

                    // Switch expression -- missing one of the cases; should generate warning
                    // when we're correctly handling the AST for this
                    public static boolean test(@Duration int duration) {
                        return switch (duration) {
                            // Missing LENGTH_INDEFINITE handling
                            case LENGTH_SHORT, LENGTH_LONG -> true;
                            default -> throw new IllegalStateException("Unexpected");
                        };
                    }
                }
                """
                )
                .indented(),
              SUPPORT_ANNOTATIONS_JAR,
              xml(
                  "project.xml",
                  """
                <project>
                <root dir="$root/project" />
                <sdk dir='${TestUtils.getSdk()}'/>
                <module name="M" android="true" library="false" javaLanguage="14">
                <classpath jar="libs/support-annotations.jar" />
                <src file="src/test/pkg/Java14Test.java" />
                </module>
                </project>
            """,
                )
                .indented(),
            )
            .name("project")
        )
        .createProjects(root)
    val projectDir = projects[0]
    val descriptorFile = File(projectDir, "project.xml")

    MainTest.checkDriver(
      """
            src/test/pkg/Java14Test.java:17: Warning: Switch statement on an int with known associated constant missing case LENGTH_INDEFINITE [SwitchIntDef]
                    return switch (duration) {
                           ^
            0 errors, 1 warning
            """,
      "",

      // Expected exit code
      ERRNO_SUCCESS,

      // Args
      arrayOf("--check", "SwitchIntDef", "--project", descriptorFile.path),
      { it.dos2unix() },
      null,
    )
  }

  @Test
  fun testProguard() {
    // Tests proguard support
    val root = temp.newFolder().canonicalFile.absoluteFile

    val projects =
      lint()
        .projects(
          project(
              source(
                  "name.some-ext",
                  """
                  -optimizationpasses 5
                  -dontusemixedcaseclassnames
                  -dontskipnonpubliclibraryclasses
                  -dontpreverify
                  -verbose
                  -optimizations !code/simplification/arithmetic,!field/*,!class/merging/*

                  -keep public class * extends android.app.Activity
                  -keep public class * extends android.app.Application
                  -keep public class * extends android.app.Service
                  -keep public class * extends android.content.BroadcastReceiver
                  -keep public class * extends android.content.ContentProvider
                  -keep public class * extends android.app.backup.BackupAgentHelper
                  -keep public class * extends android.preference.Preference
                  -keep public class com.android.vending.licensing.ILicensingService

                  -keepclasseswithmembernames class * {
                      native <methods>;
                  }

                  -keepclasseswithmembernames class * {
                      public <init>(android.content.Context, android.util.AttributeSet);
                  }

                  -keepclasseswithmembernames class * {
                      public <init>(android.content.Context, android.util.AttributeSet, int);
                  }

                  -keepclassmembers enum * {
                      public static **[] values();
                      public static ** valueOf(java.lang.String);
                  }

                  -keep class * implements android.os.Parcelable {
                    public static final android.os.Parcelable${"$"}Creator *;
                  }
                  """,
                )
                .indented(),
              xml(
                  "project.xml",
                  """
                  <project>
                  <root dir="$root/project" />
                  <sdk dir='${TestUtils.getSdk()}'/>
                  <module name="M" android="true" library="false">
                  <proguard file="name.some-ext" />
                  </module>
                  </project>
                  """,
                )
                .indented(),
            )
            .name("project")
        )
        .createProjects(root)
    val projectDir = projects[0]
    val descriptorFile = File(projectDir, "project.xml")

    MainTest.checkDriver(
      """
      name.some-ext:21: Error: Obsolete ProGuard file; use -keepclasseswithmembers instead of -keepclasseswithmembernames [Proguard]
      -keepclasseswithmembernames class * {
      ^
      1 error
      """,
      "",

      // Expected exit code
      ERRNO_SUCCESS,

      // Args
      arrayOf("--check", "Proguard", "--project", descriptorFile.path),
      { it.dos2unix() },
      null,
    )
  }

  @Test
  fun testCrLf() {
    // Regression test for bug handling Windows line endings,
    // https://issuetracker.google.com/149490356
    val root = temp.newFolder().canonicalFile.absoluteFile
    val crlf = File(root, "app/src/main/java/ClassCRLF.java")
    crlf.parentFile.mkdirs()
    crlf.writeText(
      """
      package com.example.foo.notification;

      public class AppNotifBlockedReceiver extends BroadcastReceiver {
          // content removed
      }
      """
        .trimIndent()
        .replace("\n", "\r\n")
    )
    val lf = File(root, "app/src/main/java/ClassLF.java")
    lf.writeText(
      """
      package com.example.foo.tester.ui;

      public class DisableActivity extends Activity {
      // Content removed
      }
      """
        .trimIndent()
    )

    @Language("XML")
    val descriptor =
      """
      <project>
         <module android="true" compile-sdk-version="18" name="app">
            <src file="app/src/main/java/ClassCRLF.java"/>
            <src file="app/src/main/java/ClassLF.java"/>
         </module>
      </project>
      """
        .trimIndent()
    val descriptorFile = File(root, "descriptor.xml")
    descriptorFile.writeText(descriptor.replace("\n", "\r\n"))

    MainTest.checkDriver(
      "No issues found.",
      "The source file ClassCRLF.java does not appear to be in the right project location; its package implies .../com/example/foo/notification/ClassCRLF.java but it was found in ...src/main/java/ClassCRLF.java",

      // Expected exit code
      ERRNO_SUCCESS,

      // Args
      arrayOf("--project", descriptorFile.path),
      { it.dos2unix() },
      null,
    )
  }

  @Test
  fun testInvalidDescriptorFile() {
    // Make sure we give a suitable error message when you pass in a directory instead of
    // an XML file
    val root = temp.newFolder().canonicalFile.absoluteFile

    MainTest.checkDriver(
      "",
      "Project descriptor ROOT should be an XML descriptor file, not a directory",

      // Expected exit code
      ERRNO_INVALID_ARGS,

      // Args
      arrayOf("--project", root.path),
      { it.replace(root.path, "ROOT") },
      null,
    )
  }

  @Test
  fun testLintXmlOutside() {

    val library =
      project(
          manifest(
              """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="foo.bar2"
                    android:versionCode="1"
                    android:versionName="1.0" >

                    <uses-sdk android:minSdkVersion="14" />

                    <permission android:name="bar.permission.SEND_SMS"
                        android:label="@string/foo"
                        android:description="@string/foo" />

                    <application
                        android:icon="@drawable/ic_launcher"
                        android:label="@string/app_name" >
                    </application>

                </manifest>"""
            )
            .indented()
        )
        .type(LIBRARY)
        .name("Library")

    val main =
      project(
          manifest(
              """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="foo.bar2"
                    android:versionCode="1"
                    android:versionName="1.0" >

                    <uses-sdk android:minSdkVersion="14" />

                    <permission android:name="foo.permission.SEND_SMS"
                        android:label="@string/foo"
                        android:description="@string/foo" />

                    <application
                        android:icon="@drawable/ic_launcher"
                        android:label="@string/app_name" >
                    </application>

                </manifest>
                """
            )
            .indented()
        )
        .name("App")
        .dependsOn(library)

    val root = temp.newFolder().canonicalFile.absoluteFile

    val configFile = File(root, "foobar/lint.xml")
    @Language("XML")
    val config =
      """
            <lint>
                <!-- Reduce severity of UniquePermission from error to warning -->
                <issue id="UniquePermission" severity="warning"/>
            </lint>
            """
    configFile.parentFile?.mkdirs()
    configFile.writeText(config.trimIndent())

    val projects = lint().projects(main, library).createProjects(root)
    // create projects will sort directories in dependency order so
    // the app module comes after lib even though it's the second listed
    // project
    val appProjectDir = projects[1]
    assertEquals("App", appProjectDir.name)
    val appProjectPath = appProjectDir.path

    val sdk = temp.newFolder("fake-sdk-dir")
    val cacheDir = temp.newFolder("cache-dir")

    @Language("XML")
    val descriptor =
      """
            <project>
            <root dir="$root" />
            <sdk dir='$sdk'/>
            <cache dir='$cacheDir'/>
            <module name="$appProjectPath:App" android="true" library="false" compile-sdk-version='18'>
              <manifest file="AndroidManifest.xml" />
              <dep module="Library" />
            </module>
            <module name="Library" android="true" library="true" compile-sdk-version='android-M'>
              <manifest file="Library/AndroidManifest.xml" />
            </module>
            </project>
            """
        .trimIndent()
    Files.asCharSink(File(root, "project.xml"), Charsets.UTF_8).write(descriptor)

    val canonicalRoot = root.canonicalPath
    MainTest.checkDriver(
      """
            ../Library/AndroidManifest.xml:8: Warning: Permission name SEND_SMS is not unique (appears in both foo.permission.SEND_SMS and bar.permission.SEND_SMS) [UniquePermission]
                <permission android:name="bar.permission.SEND_SMS"
                            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                AndroidManifest.xml:8: Previous permission here
            0 errors, 1 warning
            """,
      "",

      // Expected exit code
      ERRNO_SUCCESS,

      // Args
      arrayOf("--check", "UniquePermission", "--config", configFile.path, "--text", "stdout", "--project", File(root, "project.xml").path),
      { it.replace(canonicalRoot, "ROOT").replace(root.path, "ROOT").dos2unix() },
      null,
    )

    val newConfigFile = File(root, "default.xml")
    configFile.renameTo(newConfigFile)
    MainTest.checkDriver(
      """
            ../Library/AndroidManifest.xml:8: Warning: Permission name SEND_SMS is not unique (appears in both foo.permission.SEND_SMS and bar.permission.SEND_SMS) [UniquePermission]
                <permission android:name="bar.permission.SEND_SMS"
                            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                AndroidManifest.xml:8: Previous permission here
            0 errors, 1 warning
            """,
      "",

      // Expected exit code
      ERRNO_SUCCESS,

      // Args
      arrayOf(
        "--check",
        "UniquePermission",
        "--config",
        newConfigFile.path,
        "--text",
        "stdout",
        "--project",
        File(root, "project.xml").path,
      ),
      { it.replace(canonicalRoot, "ROOT").replace(root.path, "ROOT").dos2unix() },
      null,
    )
  }

  @Test
  fun testManifestInFolderNamedLayout() {
    // Regression test for b/214409371:
    // Make sure we don't accidentally interpret something in a folder named "layout"
    // ...and make sure that manifests *not* named AndroidManifestXml
    val root = temp.newFolder().canonicalFile.absoluteFile
    val projects =
      lint()
        .files(
          xml(
              "layout/SomethingNamedAndroidManifest.xml",
              """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="com.android.tools.lint.test"
                    android:versionCode="1"
                    android:versionName="1.0" >
                    <application>
                      <uses-sdk android:minSdkVersion="10" android:targetSdkVersion="31" />
                    </application>
                </manifest>
                """,
            )
            .indented(),
          xml(
              "res/layout/layout.xml",
              """
                <merge/>
                """,
            )
            .indented(),
        )
        .createProjects(root)
    val projectDir = projects[0]

    @Language("XML")
    val descriptor =
      """
            <project incomplete="false">
            <sdk dir='${TestUtils.getSdk()}'/>
            <root dir="$projectDir"/>
            <module name="M" android="true" library="true">
                <manifest file="layout/SomethingNamedAndroidManifest.xml" />
                <resource file="res/layout/layout.xml" />
            </module>
            </project>
            """
        .trimIndent()
    val descriptorFile = File(root, "out1/out2/out3/project.xml")
    descriptorFile.parentFile?.mkdirs()
    Files.asCharSink(descriptorFile, Charsets.UTF_8).write(descriptor)

    MainTest.checkDriver(
      """
      layout/SomethingNamedAndroidManifest.xml:6: Error: The <uses-sdk> element must be a direct child of the <manifest> root element [WrongManifestParent]
            <uses-sdk android:minSdkVersion="10" android:targetSdkVersion="31" />
             ~~~~~~~~
      layout/SomethingNamedAndroidManifest.xml:6: Warning: <uses-sdk> tag appears after <application> tag [ManifestOrder]
            <uses-sdk android:minSdkVersion="10" android:targetSdkVersion="31" />
             ~~~~~~~~
      1 error, 1 warning
            """,
      null,

      // Expected exit code
      ERRNO_SUCCESS,

      // Args
      arrayOf("--check", "RequiredSize,ManifestOrder,ContentDescription,WrongManifestParent", "--project", descriptorFile.path),
      null,
      null,
    )
  }

  @Test
  fun testFindPackage() {
    assertEquals("foo.bar", findPackage("package foo.bar;\n", File("Test.java")))
    assertEquals("foo.bar", findPackage("// Copyright 2021\npackage foo.bar;\n", File("Test.java")))
    assertEquals("foo.bar", findPackage("// package wrong; /*\npackage  foo. bar ;\n", File("Test.java")))
    assertEquals("foo.bar", findPackage("/* package wrong; */\npackage  foo .bar ;\n", File("Test.java")))
    assertEquals("foo.bar", findPackage("/* /* nested comment */ package wrong */\npackage foo.bar \n", File("x.kt")))
    // Regression test for 195004772
    @Language("java")
    val source =
      """
      // Copyright 2007, Google Inc.
      /** The classes in this is package provide a variety of utility services. */
      @CheckReturnValue
      @ParametersAreNonnullByDefault
      @NullMarked
      package com.google.common.util;

      import javax.annotation.CheckReturnValue;
      import javax.annotation.ParametersAreNonnullByDefault;
      import org.jspecify.nullness.NullMarked;
      """
        .trimIndent()
    assertEquals("com.google.common.util", findPackage(source, File("package-info.java")))
  }

  @Test
  fun testIsolatedPartialAnalysisWithSingleProjectRoot() {
    // We simulate integration of partial analysis with a build system like Bazel. As such, this
    // test also acts as a guide for integrating lint with partial analysis support with a build
    // system like Bazel. The integration code would need to generate `project.xml` files and
    // invoke lint in a similar way to what is done below.
    //
    // We create 4 projects: `a`, `b`, `c`, and `onlyres`. We analyze each project in isolation,
    // propagating partial results to dependent targets. We use `--analyze-only` mode, then
    // `--report-only` mode on every project. We want to report definite issues for a target
    // immediately, while partial and provisional issues propagate to dependent targets. We use
    // `UnusedResources` to check that partial issues are reported correctly. We use
    // `LongLogTag` and `MissingSuperCall` to check that provisional and definite issues are
    // reported correctly. We use `ExactAlarm` to check that the project's targetSdkVersion is
    // correctly initialized from the merged manifest file. Each project has an unused resource,
    // as well as one resource that is used in each dependent project. Project `c` is the
    // app/binary project.
    //
    // Depends-on: `a` <- `b` <- `c` -> `onlyres`
    //              ^____________/

    val tempDir = temp.newFolder().canonicalFile.absoluteFile

    // We will check that various output files do NOT contain "buildRoot". In other words, there
    // should be no absolute paths in output files.
    val root = File(tempDir, "buildRoot")

    fun checkFilesDoNotContainBuildRoot(dir: File) {
      val badFiles =
        java.nio.file.Files.list(dir.toPath()).filter { it.isRegularFile() && it.readText(Charsets.UTF_8).contains("buildRoot") }.toList()
      assertTrue(
        "The following files contain the buildRoot directory, " + "which should not happen: ${badFiles.joinToString()}",
        badFiles.isEmpty(),
      )
    }

    fun createFile(path: String, content: String): File {
      val trimmedContent = content.trimIndent()
      val file = File(root, path)
      file.parentFile?.mkdirs()
      Files.asCharSink(file, Charsets.UTF_8).write(trimmedContent)
      return file
    }

    fun createXmlFile(path: String, @Language("XML") content: String): File = createFile(path, content)

    fun createJavaFile(path: String, @Language("JAVA") content: String): File = createFile(path, content)

    val configFile =
      createXmlFile(
        "configs/config.xml",
        """
            <lint>
                <issue id="all" severity="ignore" />
                <issue id="MissingClass" severity="error" />
                <issue id="UnusedResources" severity="error" />
                <issue id="LongLogTag" severity="error" />
                <issue id="MissingSuperCall" severity="error" />
                <issue id="ExactAlarm" severity="error" />
            </lint>""",
      )

    // Project a:
    createJavaFile(
      "java/com/google/a/Activity.java",
      """
            package com.google.a;

            import android.util.Log;

            public class Activity extends android.app.Activity {

                private static final String TAG = "SuperSuperLongLogTagThatExceedsMax";

                @Override
                protected void onStart() {
                    // Missing super call.
                    this.setTitle(R.string.a_string_used_in_a);
                    Log.d(TAG, "message");
                }
            }""",
    )
    createXmlFile(
      "java/com/google/a/AndroidManifest.xml",
      """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.google.a">

                <uses-sdk
                    android:minSdkVersion="14"
                    android:targetSdkVersion="28" />

                <application>
                    <activity
                        android:name="com.google.a.Activity"
                        android:exported="false" />
                </application>

            </manifest>""",
    )
    createXmlFile(
      "java/com/google/a/res/values/strings.xml",
      """
            <resources>
                <string name="a_string_used_in_a">a string used in a</string>
                <string name="a_string_used_in_b">a string used in b</string>
                <string name="a_string_used_in_c">a string used in c</string>
                <string name="a_string_unused">a string unused</string>
            </resources>""",
    )
    val projectA =
      createXmlFile(
        "out/java/com/google/a/project.xml",
        """
            <project>
            <root dir="$root" />
            <module
                android="true"
                library="true"
                name="//java/com/google/a:a"
                partial-results-dir="out/java/com/google/a/lint_partial_results"
                desugar="full">
            <manifest file="java/com/google/a/AndroidManifest.xml" />
            <merged-manifest file="java/com/google/a/AndroidManifest.xml" />
            <src file="java/com/google/a/Activity.java" />
            <resource file="java/com/google/a/res/values/strings.xml" />
            </module>
            </project>
            """,
      )
    File(root, "out/java/com/google/a/lint_partial_results").mkdirs()

    // Project b:
    createJavaFile(
      "java/com/google/b/Activity.java",
      """
            package com.google.b;

            import android.util.Log;

            public class Activity extends android.app.Activity {

                private static final String TAG = "SuperSuperLongLogTagThatExceedsMax";

                @Override
                protected void onStart() {
                    // Missing super call.
                    this.setTitle(com.google.a.R.string.a_string_used_in_b);
                    this.setTitle(R.string.b_string_used_in_b);
                    Log.d(TAG, "message");
                }
            }""",
    )
    createXmlFile(
      "java/com/google/b/AndroidManifest.xml",
      """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.google.b">

                <uses-sdk
                    android:minSdkVersion="14"
                    android:targetSdkVersion="28" />

                <application>
                    <activity
                        android:name="com.google.b.Activity"
                        android:exported="false" />
                </application>

            </manifest>""",
    )
    createXmlFile(
      "java/com/google/b/res/values/strings.xml",
      """
            <resources>
                <string name="b_string_used_in_b">b string used in b</string>
                <string name="b_string_used_in_c">b string used in c</string>
                <string name="b_string_unused">b string unused</string>
            </resources>""",
    )
    val projectB =
      createXmlFile(
        "out/java/com/google/b/project.xml",
        """
            <project>
            <root dir="$root" />
            <module
                android="true"
                library="true"
                name="//java/com/google/b:b"
                partial-results-dir="out/java/com/google/b/lint_partial_results"
                desugar="full">
            <manifest file="java/com/google/b/AndroidManifest.xml" />
            <merged-manifest file="java/com/google/b/AndroidManifest.xml" />
            <src file="java/com/google/b/Activity.java" />
            <resource file="java/com/google/b/res/values/strings.xml" />
            <dep module="//java/com/google/a:a" />
            </module>
            <module android="true"
                library="true"
                name="//java/com/google/a:a"
                desugar="full"
                partial-results-dir="out/java/com/google/a/lint_partial_results" />
            </project>
            """,
      )
    File(root, "out/java/com/google/b/lint_partial_results").mkdirs()

    // Project onlyres:
    createXmlFile(
      "java/com/google/onlyres/AndroidManifest.xml",
      """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.google.onlyres">

                <uses-sdk
                    android:minSdkVersion="14"
                    android:targetSdkVersion="28" />

            </manifest>""",
    )
    createXmlFile(
      "java/com/google/onlyres/res/values/strings.xml",
      """
            <resources>
                <string name="onlyres_string_used_in_c">onlyres string used in c</string>
                <string name="onlyres_string_unused">onlyres string unused</string>
            </resources>""",
    )
    val projectOnlyRes =
      createXmlFile(
        "out/java/com/google/onlyres/project.xml",
        """
            <project>
            <root dir="$root" />
            <module
                android="true"
                library="true"
                name="//java/com/google/onlyres:onlyres"
                partial-results-dir="out/java/com/google/onlyres/lint_partial_results"
                desugar="full">
            <manifest file="java/com/google/onlyres/AndroidManifest.xml" />
            <merged-manifest file="java/com/google/onlyres/AndroidManifest.xml" />
            <resource file="java/com/google/onlyres/res/values/strings.xml" />
            </module>
            </project>
            """,
      )
    File(root, "out/java/com/google/onlyres/lint_partial_results").mkdirs()

    // Project c (the app):
    createJavaFile(
      "java/com/google/c/Activity.java",
      """
            package com.google.c;

            import android.util.Log;

            public class Activity extends android.app.Activity {

                private static final String TAG = "SuperSuperLongLogTagThatExceedsMax";

                @Override
                protected void onStart() {
                    // Missing super call.
                    this.setTitle(com.google.a.R.string.a_string_used_in_c);
                    this.setTitle(com.google.b.R.string.b_string_used_in_c);
                    this.setTitle(com.google.onlyres.R.string.onlyres_string_used_in_c);
                    this.setTitle(R.string.c_string_used_in_c);
                    Log.d(TAG, "message");
                }
            }""",
    )
    createXmlFile(
      "java/com/google/c/AndroidManifest.xml",
      """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.google.c">

                <uses-sdk
                    android:minSdkVersion="14"
                    android:targetSdkVersion="33" />

                <!-- This permission will trigger an error if the targetSdkVersion fails to be read -->
                <uses-permission android:name="android.permission.USE_EXACT_ALARM" />

                <application>
                    <activity
                        android:name="com.google.c.Activity"
                        android:exported="true" />
                    <action android:name="android.intent.action.MAIN" />
                    <category android:name="android.intent.category.LAUNCHER" />
                </application>

            </manifest>""",
    )
    createXmlFile(
      "java/com/google/c/res/values/strings.xml",
      """
            <resources>
                <string name="c_string_used_in_c">c string used in c</string>
                <string name="c_string_unused">c string unused</string>
            </resources>""",
    )
    createXmlFile(
      "out/java/com/google/c/AndroidManifestMerged.xml",
      """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.google.c">

                <uses-sdk
                    android:minSdkVersion="14"
                    android:targetSdkVersion="33" />

                <!-- This permission will trigger an error if the targetSdkVersion fails to be read -->
                <uses-permission android:name="android.permission.USE_EXACT_ALARM" />

                <application>
                    <activity
                        android:name="com.google.a.Activity"
                        android:exported="false" />
                    <activity
                        android:name="com.google.b.Activity"
                        android:exported="false" />
                    <activity
                        android:name="com.google.c.Activity"
                        android:exported="true" />
                    <action android:name="android.intent.action.MAIN" />
                    <category android:name="android.intent.category.LAUNCHER" />
                </application>

            </manifest>""",
    )
    val projectC =
      createXmlFile(
        "out/java/com/google/c/project.xml",
        """
            <project>
            <root dir="$root" />
            <module
                android="true"
                library="false"
                name="//java/com/google/c:c"
                partial-results-dir="out/java/com/google/c/lint_partial_results"
                desugar="full">
            <manifest file="java/com/google/c/AndroidManifest.xml" />
            <merged-manifest file="out/java/com/google/c/AndroidManifestMerged.xml" />
            <src file="java/com/google/c/Activity.java" />
            <resource file="java/com/google/c/res/values/strings.xml" />
            <dep module="//java/com/google/a:a" />
            <dep module="//java/com/google/b:b" />
            <dep module="//java/com/google/onlyres:onlyres" />
            </module>
            <module
                android="true"
                library="true"
                name="//java/com/google/a:a"
                desugar="full"
                partial-results-dir="out/java/com/google/a/lint_partial_results" />
            <module
                android="true"
                library="true"
                name="//java/com/google/b:b"
                desugar="full"
                partial-results-dir="out/java/com/google/b/lint_partial_results" />
            <module
                android="true"
                library="true"
                name="//java/com/google/onlyres:onlyres"
                desugar="full"
                partial-results-dir="out/java/com/google/onlyres/lint_partial_results" />
            </project>
            """,
      )
    File(root, "out/java/com/google/c/lint_partial_results").mkdirs()

    // Analyze project a.
    MainTest.checkDriver(
      "",
      "",
      // Expected exit code
      ERRNO_SUCCESS,
      // Args
      arrayOf(
        "--config",
        configFile.toString(),
        "--project",
        projectA.toString(),
        "--analyze-only",
        "--sdk-home",
        TestUtils.getSdk().toString(),
      ),
      null,
      null,
    )

    MainTest.checkDriver(
      """
                java/com/google/a/Activity.java:10: Error: Overriding method should call super.onStart [MissingSuperCall]
                    protected void onStart() {
                                   ~~~~~~~
                java/com/google/a/Activity.java:13: Error: The logging tag can be at most 23 characters, was 34 (SuperSuperLongLogTagThatExceedsMax) [LongLogTag]
                        Log.d(TAG, "message");
                              ~~~
                2 errors""",
      "",
      // Expected exit code
      ERRNO_SUCCESS,
      // Args
      arrayOf(
        "--config",
        configFile.toString(),
        "--project",
        projectA.toString(),
        "--report-only",
        "--sdk-home",
        TestUtils.getSdk().toString(),
      ),
      null,
      null,
    )
    checkFilesDoNotContainBuildRoot(File(root, "out/java/com/google/a/lint_partial_results"))

    // Delete definite issues, as these have definitely already been reported.
    File(root, "out/java/com/google/a/lint_partial_results/lint-definite.xml").delete()

    // Analyze project b.
    MainTest.checkDriver(
      "",
      "",
      // Expected exit code
      ERRNO_SUCCESS,
      // Args
      arrayOf(
        "--config",
        configFile.toString(),
        "--project",
        projectB.toString(),
        "--analyze-only",
        "--sdk-home",
        TestUtils.getSdk().toString(),
      ),
      null,
      null,
    )

    MainTest.checkDriver(
      """
                java/com/google/b/Activity.java:10: Error: Overriding method should call super.onStart [MissingSuperCall]
                    protected void onStart() {
                                   ~~~~~~~
                java/com/google/a/Activity.java:13: Error: The logging tag can be at most 23 characters, was 34 (SuperSuperLongLogTagThatExceedsMax) [LongLogTag]
                        Log.d(TAG, "message");
                              ~~~
                java/com/google/b/Activity.java:14: Error: The logging tag can be at most 23 characters, was 34 (SuperSuperLongLogTagThatExceedsMax) [LongLogTag]
                        Log.d(TAG, "message");
                              ~~~
                3 errors""",
      "",
      // Expected exit code
      ERRNO_SUCCESS,
      // Args
      arrayOf(
        "--config",
        configFile.toString(),
        "--project",
        projectB.toString(),
        "--report-only",
        "--sdk-home",
        TestUtils.getSdk().toString(),
      ),
      null,
      null,
    )
    checkFilesDoNotContainBuildRoot(File(root, "out/java/com/google/b/lint_partial_results"))
    // Delete definite issues.
    File(root, "out/java/com/google/b/lint_partial_results/lint-definite.xml").delete()

    // Analyze project onlyres.
    MainTest.checkDriver(
      "",
      "",
      // Expected exit code
      ERRNO_SUCCESS,
      // Args
      arrayOf(
        "--config",
        configFile.toString(),
        "--project",
        projectOnlyRes.toString(),
        "--analyze-only",
        "--sdk-home",
        TestUtils.getSdk().toString(),
      ),
      null,
      null,
    )

    MainTest.checkDriver(
      "No issues found.",
      "",
      // Expected exit code
      ERRNO_SUCCESS,
      // Args
      arrayOf(
        "--config",
        configFile.toString(),
        "--project",
        projectOnlyRes.toString(),
        "--report-only",
        "--sdk-home",
        TestUtils.getSdk().toString(),
      ),
      null,
      null,
    )
    checkFilesDoNotContainBuildRoot(File(root, "out/java/com/google/onlyres/lint_partial_results"))
    // Delete definite issues.
    File(root, "out/java/com/google/onlyres/lint_partial_results/lint-definite.xml").delete()

    // Analyze project c.
    MainTest.checkDriver(
      "",
      "",
      // Expected exit code
      ERRNO_SUCCESS,
      // Args
      arrayOf(
        "--config",
        configFile.toString(),
        "--project",
        projectC.toString(),
        "--analyze-only",
        "--sdk-home",
        TestUtils.getSdk().toString(),
      ),
      null,
      null,
    )

    MainTest.checkDriver(
      """
                java/com/google/c/Activity.java:10: Error: Overriding method should call super.onStart [MissingSuperCall]
                    protected void onStart() {
                                   ~~~~~~~
                java/com/google/a/Activity.java:13: Error: The logging tag can be at most 23 characters, was 34 (SuperSuperLongLogTagThatExceedsMax) [LongLogTag]
                        Log.d(TAG, "message");
                              ~~~
                java/com/google/b/Activity.java:14: Error: The logging tag can be at most 23 characters, was 34 (SuperSuperLongLogTagThatExceedsMax) [LongLogTag]
                        Log.d(TAG, "message");
                              ~~~
                java/com/google/c/Activity.java:16: Error: The logging tag can be at most 23 characters, was 34 (SuperSuperLongLogTagThatExceedsMax) [LongLogTag]
                        Log.d(TAG, "message");
                              ~~~
                java/com/google/c/res/values/strings.xml:3: Error: The resource R.string.c_string_unused appears to be unused [UnusedResources]
                    <string name="c_string_unused">c string unused</string>
                            ~~~~~~~~~~~~~~~~~~~~~~
                java/com/google/onlyres/res/values/strings.xml:3: Error: The resource R.string.onlyres_string_unused appears to be unused [UnusedResources]
                    <string name="onlyres_string_unused">onlyres string unused</string>
                            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                java/com/google/b/res/values/strings.xml:4: Error: The resource R.string.b_string_unused appears to be unused [UnusedResources]
                    <string name="b_string_unused">b string unused</string>
                            ~~~~~~~~~~~~~~~~~~~~~~
                java/com/google/a/res/values/strings.xml:5: Error: The resource R.string.a_string_unused appears to be unused [UnusedResources]
                    <string name="a_string_unused">a string unused</string>
                            ~~~~~~~~~~~~~~~~~~~~~~
                8 errors""",
      "",
      // Expected exit code
      ERRNO_SUCCESS,
      // Args
      arrayOf(
        "--config",
        configFile.toString(),
        "--project",
        projectC.toString(),
        "--report-only",
        "--sdk-home",
        TestUtils.getSdk().toString(),
      ),
      null,
      null,
    )
    checkFilesDoNotContainBuildRoot(File(root, "out/java/com/google/c/lint_partial_results"))
  }

  @Test
  fun testOverlappingInferred() {
    // Regression test for b/248054901
    val root = temp.newFolder().canonicalFile.absoluteFile
    val projects =
      lint()
        .files(
          xml(
              "project.xml",
              """
                <project>
                <module name="test" android="true" library="false">
                <src file="com/google/b244342092repro/ToBeChecked.java" test="true"/>
                <src file="com/google/b244342092repro/gen/com/google/b244342092repro/ToBeIgnored.java" generated="true"/>
                </module>
                </project>
                """,
            )
            .indented(),
          java(
              "com/google/b244342092repro/ToBeChecked.java",
              """
                package com.google.b244342092repro;
                final class ToBeChecked {
                  final ToBeIgnored ref = new ToBeIgnored();
                  private ToBeChecked() {
                  }
                }
                """,
            )
            .indented(),
          java(
              "com/google/b244342092repro/gen/com/google/b244342092repro/ToBeIgnored.java",
              """
                package com.google.b244342092repro;
                final class ToBeIgnored {
                  @org.junit.Ignore
                  @org.junit.Test
                  public void testFoo() {
                  }
                }
                """,
            )
            .indented(),
        )
        .createProjects(root)
    val descriptorFile = File(projects[0], "project.xml")

    MainTest.checkDriver(
      "No issues found.",
      "",

      // Expected exit code
      ERRNO_SUCCESS,

      // Args
      arrayOf("--check", "IgnoreWithoutReason", "--project", descriptorFile.path),
      null,
      null,
    )
  }

  @Test
  fun testAnalysisAPIServices() {
    assumeTrue(useFirUast())
    val root = temp.newFolder().canonicalFile.absoluteFile
    val projects =
      lint()
        .files(
          xml(
              "project.xml",
              """
              <project>
                <module name="common" library="true" android="false" compute_source_roots="false" kotlinPlatforms="JVM [1.8]">
                  <src file="com/example/Common.kt"/>
                </module>
                <module name="desktop" library="true" android="false" compute_source_roots="false" kotlinPlatforms="JVM [1.8]">
                  <src file="com/example/Desktop.kt"/>
                  <dep module="common" kind="dependsOn" />
                </module>
              </project>
              """,
            )
            .indented(),
          kotlin(
              "com/example/Common.kt",
              """
              package com.example

              interface Platform {
                val name: String
              }
              expect fun getPlatform(): Platform
              """,
            )
            .indented(),
          kotlin(
              "com/example/Desktop.kt",
              """
              package com.example

              class DesktopPlatform : Platform {
                override val name: String
                get() = "Desktop"
              }

              actual fun getPlatform(): Platform = DesktopPlatform()
              """,
            )
            .indented(),
        )
        .createProjects(root)
    val descriptorFile = File(projects[0], "project.xml")

    MainTest.checkDriver(
      "No issues found.",
      "",
      // Expected exit code
      ERRNO_SUCCESS,
      // Args
      arrayOf("--check", "IgnoreWithoutReason", "--project", descriptorFile.path),
      null,
      { driver, type, project, context ->
        when (type) {
          SCANNING_FILE -> {
            context?.project?.ideaProject?.checkAnalysisApiServices()
          }
          else -> {}
        }
      },
    )
  }

  @OptIn(KaExperimentalApi::class)
  @Test
  fun testExpectActualWithJustJvm() {
    assumeTrue(useFirUast())
    val root = temp.newFolder().canonicalFile.absoluteFile
    val projects =
      lint()
        .files(
          xml(
              "project.xml",
              """
              <project>
                <module name="common" library="true" android="false" compute_source_roots="false" kotlinPlatforms="JVM [1.8]">
                  <src file="com/example/Common.kt"/>
                </module>
                <module name="desktop" library="true" android="false" compute_source_roots="false" kotlinPlatforms="JVM [1.8]">
                  <src file="com/example/Desktop.kt"/>
                  <dep module="common" kind="dependsOn" />
                </module>
              </project>
              """,
            )
            .indented(),
          kotlin(
              "com/example/Common.kt",
              """
              package com.example

              interface Platform {
                val name: String
              }
              expect fun getPlatform(): Platform
              """,
            )
            .indented(),
          kotlin(
              "com/example/Desktop.kt",
              """
              package com.example

              class DesktopPlatform : Platform {
                override val name: String
                get() = "Desktop"
              }

              actual fun getPlatform(): Platform = DesktopPlatform()
              """,
            )
            .indented(),
        )
        .createProjects(root)
    val descriptorFile = File(projects[0], "project.xml")

    MainTest.checkDriver(
      "No issues found.",
      "",
      // Expected exit code
      ERRNO_SUCCESS,
      // Args
      arrayOf("--check", "IgnoreWithoutReason", "--project", descriptorFile.path),
      null,
      { driver, type, project, context ->
        when (type) {
          SCANNING_FILE -> {
            context!!
            when (context.file.name) {
              "Common.kt" -> {}
              "Desktop.kt" -> {
                context as JavaContext
                val uFile = context.uastParser.parse(context)!!
                val file = uFile.sourcePsi as KtFile
                val func = file.declarations[1] as KtNamedFunction
                analyze(func) {
                  assertTrue(
                    "KMP should be enabled",
                    (useSiteModule as KaSourceModule).languageVersionSettings.supportsFeature(LanguageFeature.MultiPlatformProjects),
                  )
                  val expectSymbols = func.symbol.getExpectsForActual()
                  assertEquals(1, expectSymbols.size)
                  assertTrue(expectSymbols[0].isExpect)
                }
              }
            }
          }
          else -> {}
        }
      },
    )
  }

  @Test
  fun testKmpEnabledWithJustNative() {
    val root = temp.newFolder().canonicalFile.absoluteFile
    val projects =
      lint()
        .files(
          xml(
              "project.xml",
              """
              <project>
                <module name="ioscode" library="true" android="false" compute_source_roots="false" kotlinPlatforms="Native [ios_arm64]">
                  <src file="com/example/Code.kt"/>
                </module>
              </project>
              """,
            )
            .indented(),
          kotlin(
              "com/example/Code.kt",
              """
              package com.example

              class Code {
                fun hello() {
                  val s = ""
                }
              }
              """,
            )
            .indented(),
        )
        .createProjects(root)
    val descriptorFile = File(projects[0], "project.xml")

    MainTest.checkDriver(
      "No issues found.",
      "",
      // Expected exit code
      ERRNO_SUCCESS,
      // Args
      arrayOf("--check", "IgnoreWithoutReason", "--project", descriptorFile.path),
      null,
      { driver, type, project, context ->
        when (type) {
          SCANNING_FILE -> {
            context!!
            when (context.file.name) {
              "Code.kt" -> {
                context as JavaContext
                val uFile = context.uastParser.parse(context)!!
                val clz = uFile.classes[0].sourcePsi as KtClass
                analyze(clz) {
                  assertTrue(
                    "KMP should be enabled",
                    (useSiteModule as KaSourceModule).languageVersionSettings.supportsFeature(LanguageFeature.MultiPlatformProjects),
                  )
                }
              }
            }
          }
          else -> {}
        }
      },
    )
  }

  @Test
  fun testKmpNativeUastPsi() {
    assumeTrue(useFirUast())
    // Tests the new KlibLightElementProvider.
    // kotlinSourceFile references various symbols from klibSourceFile. We consider different
    // scenarios for where the code in klibSourceFile ends up:
    //  - just a Kotlin source file
    //  - compiled into a klib
    //  - compiled into a jar
    // We access the UAST of kotlinSourceFile to follow the references (yielding Java PSI).
    // We only test the klib case, but provide the option (uncomment) to cross-check with the source
    // and jar case, when developing.

    @Language("kotlin")
    val klibSourceFile =
      """
      package com.klib

      annotation class LibAnnotation

      fun libGlobalMethod(): Int {
        return 2
      }

      fun libGlobalMethod(a: Int): Int {
        return 3
      }

      fun libGlobalMethod2(a: Int): Int {
        return 2
      }

      val Int.globalProperty
        get() = ""

      val String.globalProperty
        get() = 2

      val globalProperty = 2

      val globalProperty2 = 3

      const val LIB_CONST = ""

      open class LibClass {

        val Int.valProp
          get() = 0

        val String.valProp
          get() = 0

        val valProp = 0

        var varProp = 1

        val propWithGetter
          get() = 1

        val mutableListProp: MutableList<String> = mutableListOf()

        var propWithField: Int = 0
          get() = field + 1
          set(value) {
            field = value - 1
          }

        fun libMethod(arg: Int): Int = 1
        fun libMethod(arg: Long): Long = 1

        fun libMethod2(arg: Long): Long = 1

        fun libMethod3(arg: Array<Long>): Array<Long> = arrayOf(1L)

        fun <T> libGenericMethod(arg: T): Array<T>? = null

        operator fun unaryPlus(): LibClass = this

        companion object {
          fun companionFunc(): Int = 2
          val companionProp: Int = 3
        }

        object LibClassObject {
          fun func(): Int = 1
          val prop: Int = 2
        }
      }

      object MyFirstObject {

        fun bar(): Long = 2L

        object MySecondObject {
          val prop: Int = 1
          fun foo(): Int = 2
        }
      }

      enum class ProtocolState {
        WAITING {
          override fun signal() = TALKING
        },

        TALKING {
          override fun signal() = WAITING
        };

        abstract fun signal(): ProtocolState
      }
      """
        .trimIndent()

    @Language("kotlin")
    val kotlinSourceFile =
      """
      package com.example

      import com.klib.LibClass
      import com.klib.MyFirstObject
      import com.klib.LibAnnotation
      import com.klib.globalProperty
      import com.klib.globalProperty2
      import com.klib.libGlobalMethod
      import com.klib.libGlobalMethod2
      import com.klib.LIB_CONST
      import com.klib.ProtocolState

      @LibAnnotation
      class Code {
        fun hello() {
          libGlobalMethod()
          libGlobalMethod(1)
          libGlobalMethod2(1)

          globalProperty
          1.globalProperty
          "".globalProperty
          globalProperty2

          LIB_CONST

          val c = LibClass()
          +c

          LibClass.companionFunc()
          LibClass.companionProp
          LibClass.Companion
          LibClass.Companion.companionFunc()
          LibClass.Companion.companionProp

          LibClass.LibClassObject
          LibClass.LibClassObject.func()
          LibClass.LibClassObject.prop

          MyFirstObject
          MyFirstObject.bar()
          MyFirstObject.MySecondObject
          MyFirstObject.MySecondObject.prop
          MyFirstObject.MySecondObject.foo()

          ProtocolState.WAITING
        }

        fun LibClass.hello2() {
          1.valProp
          "".valProp
          valProp
          varProp = 2
          varProp += 2
          varProp--
          ++varProp
          varProp
          propWithGetter
          mutableListProp += "a"
          propWithField

          libMethod(1)
          libMethod(1L)
          libMethod2(1L)
          libMethod3(arrayOf(1L))

          libGenericMethod(1)
        }
      }

      @LibAnnotation
      class OtherClass : LibClass()
      """
        .trimIndent()

    val jarFile =
      compiled(
        "/mylib.jar",
        kotlin(klibSourceFile),
        0xad5b5f36,
        """
        META-INF/main.kotlin_module:
        H4sIAAAAAAAA/2NgYGBmYGBgAmJGBijgkubiSM7P1cvOyUwS4vfJTHLMy8sv
        SSzJzM/zLlFi0GIAAN24MXQ1AAAA
        """,
        """
        com/klib/LibAnnotation.class:
        H4sIAAAAAAAA/4VQu07DQBCcvRASzMvhERxSQJUSh4gOGpBAsuQACo8m1dk5
        oYsdW8LnCLpUfBQFiij5KMSagoAUiWZ3dm52bnc/Pl/fAByhSaiH6ciNYh24
        vg5OkyQ10ug0qYAI9lCOpRvL5MG9CoYqNBWUCHszVv7o3d+tZcK+P1fVU0Yl
        BTomlMcyzhWh9Y/0Oo11+MwNld7d5a3XPSfU/Cg1sU7crjJyII3kVzEal3gp
        UQQqAggUMf+ki6rNaHBIaEwnVUs4whJ2s/r+IpzppCPadDadFIIOwfHnX4S/
        YEf7D3cQGYJ1k+aPobrQMS/T6OU89Ejd60wHsZpJsxb7Y4EtFovZGDe+o4Nd
        zieM+OSoKizBwjLKWOmj5GHVw5qHddicUfOwgc0+KMMWtvsQGeoZdr4AQXqC
        Bs8BAAA=
        """,
        """
        com/klib/LibAnnotationKt.class:
        H4sIAAAAAAAA/21TQW/TSBT+xk4cx00TN7QLSaEsJUDpLrgEdhcEQmKLWFmE
        gmgVIfWAJqmVTuPYK8+k2r31xA/hzIFdhIT2gCqO/CjEG8fbpkkPnvfmve/7
        3sx746/f/vsM4A7uMZzrxgOvH4qO1xKdR1EUK65EHD1VBTAGd4/vcy/kUc97
        3tkLuhQ1GSoE/yOMOzx8FqjdeIfBXLnuM+RWfG0Yp4+sOwFrMsz1AjUKvUji
        P4NE/c2wQKzWcZ1NlYiod5/hcitOet5eoDoJF5H0+NHhpLcRq41hGBIKNsoM
        8w21K2SjNyU9LaxPaD3Q8Ic2qgxL/ViFIvL29geeiFSQRDz0/EiDpejKAuZJ
        p7sbdPtZ0Rc84YOAgAzXxvVHDbp/SsV2CT/grIMFnGOonnbX8smTl7CIYhEG
        zhN+qmXUx8pJfLOEiyPCjwz2gy7dR6iH6VjaDMWW//vr9ecbm1s0gFZ2XRoJ
        3+GKU3FjsG/SczD0wvQCml+f4n8JvVsjb+cWw8fDg6pzeOAYru0Ytkm2TLZQ
        t9zDg7qxxurlzGlarpEFzPH98oKbI5tfJf/uky9vLauesy23kMUZxfNjcXt5
        fhR/9eXNY4raVDxNFCnhTCdmKFF2S7oCJbYoYVBCH54a5p543Tf7imF2nV6S
        4pFq83AYMCy+HEZKDAI/2hdSdMLgGC/pba/HOwSqtEQUbAwHnSDZ4oTR84y7
        PGzzROh9FmxMah09mhOizmY8TLrBE6E5tYzTnqqOWzTZHI3CRF2/DPLqeuBk
        a2iQbxCCBogKmmQtmt8s9J9RQz7d3U4ZTI8WRv5dOuE7GVKvGlfIcD/r6Wuc
        9S7VPMY5+IXW0ihL6qP6zjQzP8ksn8qcoYiRMm9kTLNaeT9BrY5RTbgpdfyS
        WmYuk2mS1Th7tXrmE2oTx7Do/zvWskmrTvZX+gpsTOz/fp3P+pX7FxcmO3aW
        kEunIS9NImtUaBmXM+SVDFnMf8AF6wMu/XMEd+ikWjhHFAO/pSdfw12yLwlx
        hQZ0dRumj2s+Vnxcxyq5+Mmnpt/YBpO4CW8bsxJ5ibKELeFILKb+jERJYk7C
        krgosSTR+A40EcXyAQYAAA==
        """,
        """
        com/klib/LibClass＄Companion.class:
        H4sIAAAAAAAA/5VSz08TQRT+Zna73S5Flh8qFPFnVUBkgXjDqAghqSlK0DQm
        HMx0WXHo7izZ3RKPPfGHePbCSePBNBz9o4xvtqUQTEy8vB/fm++9me/Nr98/
        fgJ4ggWGaT+OvFYom15dNtdDkabV9Tg6FErGqgjG4B6II+GFQu17b5oHgZ8V
        YTBYT6WS2TMGY3auUUYBlgMTRQYz+yRThpn6P/quMgz7Z8lmW/l5mxrN2g+y
        wantJD5kGP2rTxFXGCrC9wPqeZlQ9Q/LcFF2MIJRhuXZeivOQqm8g6PIkyoL
        EiVCbyP4KNohMVWaJW0/i5MtkbSCZHWu4YDrZ4xX/fPihyivMiz+Xze6/Rlh
        K8jEnsgEYTw6Mkh9rg3TBgysRfhnqbMlivaWGba7nQmHT3KHu92Ow22DAlt7
        +/TYmOx2VvgSe1my+ekXy7S5a7yyXLPClwo7E66l/fvT4w2q2U63UzHtomvr
        vitMTzsXv6dxaSAhrYBkXlMqzkRG+WIro5Wux3sBw0hdquB1O2oGyTvRDAkZ
        q8e+CBsikTrvg+WaUkGSbyqgj+C8jduJH2xKXZvaaatMRkFDppIOn89JsUy6
        m1oMslz/J7rnA8o8rQ75wvw32CdaNzwka+Xgc8ySLfcOoASH/CiGckSTF/pk
        Xvh6ifniApMPmMMD5mKfaX7H2GXu2gWu2eO6jOaP97mPyfP+lSdO8v1qwrUe
        2B+mo6uE0dfHHGVOThpBFVOYzwfexyPym4Rfp7OTuzBqmKqhUsM0bpDHTA03
        cWsXLMVt3NlFKYWT4m6KQgorxVAe30sxnKL8B+qBmeDzAwAA
        """,
        """
        com/klib/LibClass＄LibClassObject.class:
        H4sIAAAAAAAA/4VSTU8TURQ97810Oh0KnWJVKH4goHyoDBB3EhPkIxlTKwFC
        NKxey4ivnc6QmSlx2RU/xLUbZEGiiWlw548y3jeWirBwFu/ee+4997x37/z8
        9fU7gGdwGMbrYctp+rLmVGRt1RdxPHXhvKk1vHqSBWOwG+JIOL4IDpwLVGMw
        lmUgkxcM2szsbh4ZGBZ0ZBn05IOMGSYq/2v+nGrft4N62sJlyB54yWYUHhJ8
        mBrm5jGEQg4cNoO5XPdTSYti0jHd6vbOSnV1PY8SLFV0k2GyEkYHTsNLapGQ
        QeyIIAgTkciQ/GqYVNu+T7LFSjNMqJnz2kvEvkgEYbx1pNFcuDqYOkAXaBL+
        Uapogbz9RYbNbqdk8RFucbvbsbipkWMqa/445iPdzhJfYC9zJj//ZOgmt7VX
        hq2X+UJmq2Qbyr49P16jnGl1O2XdzNqm6rvElFrx2sCymGIY+ndqtA8CVvrv
        mm8SNLbVDhLZ8tzgSMay5nt/87QKfTXc9xgKFRl41Xar5kU7gmoYhithXfi7
        IpIq7oF5Nwi8KFX0iGxth+2o7m1IlRvt6exeU8EiLUBXA8So2ge9Z4Yig+xt
        smW1SLI65TMpOkuRo2ZMNjN3BvMkJc/1SMAaHtOZ/1OAHLUEihhIEUV+0iNz
        /fMV5volJu8zB/vM+R5T/4LiVe7GJa7e45oY7l95mqrVV/gG/u4MN05xK3OK
        4kn6v1y0saiNRhdEOgeOh3hEU3maSk2TvJJhGKFnje5Bc1F2MebiDu6SxT0X
        9zG+BxbjASb2kIthxZiMkYlhxBhI/aEYgzHyvwHBBxvZzQMAAA==
        """,
        """
        com/klib/LibClass.class:
        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
        """,
        """
        com/klib/MyFirstObject＄MySecondObject.class:
        H4sIAAAAAAAA/41STU/bQBB9u3YcxwRwKKUQ+g2Uj7YYUG9FlSgtkqsQECDU
        ipOTuHSJYyOvg8otJ35Iz71QDkitVEX01h9VddaElNIe8GFn5s2+ebMz/vnr
        63cAz+AwTFSjhlMPRMVZPVwRsUzWKnt+NRlfPdz0q1FYOw+zYAz2nnfgOYEX
        7joXqMZgLIpQJC8YtKnp7TwyMCzoyDLoyQchGSZL11J4zpDd9ZP1ONpPS7lU
        YD8NmJtHL/py4Oin1PsoYjAXq0EqaxFKWqZb3txaKi+/zmMQlrp6k2GsFMW7
        zp6fVGJPhNLxwjBKvERE5JejpNwMAlItlOpRQsWcVT/xal7iEcYbBxoNiKuD
        qQPURp3wj0JFc+TV5hnW261Biw9zi9vtlsVNjRxTWfPHER9utxb4HHuZM/nZ
        J0M3ua29MWyzyOcyG4O2ruzbs6NXlDOtdquom4adVXUXmFIb+v/Qshhn6Pt7
        crSYkqgsdR83WydodKMZJqLhu+GBkKIS+H/ytBN9Oar5DP0lEfrlZqPix1se
        3WEYKEVVL9j2YqHiDph3w9CPlwNPSp/I1mbUjKv+ilC5kY7O9j8qmKct6GqK
        GFFLoUdNUWSQvUW2qHZKVqd8JkWnKXLUoMlmZk5hHqfkmQ4JKOMxnfnzC8hR
        SaCAnhRR5NkOWf8C+/MV7tolrt7lFrrcJx0uz1xlrl9i8g7TxEC35UlC1df/
        DfzdKW6cYEg/gX2c/jQXZSxqQCMRpHPgmMAjmsrTtOQkNQ6sED5MzxrZgeai
        6GLUxW3cIYu7Lu7h/g6YxAM83EFOwpIYk8hIGBK9qd8jkZco/AYxB3HN2wMA
        AA==
        """,
        """
        com/klib/MyFirstObject.class:
        H4sIAAAAAAAA/21R30/TUBg997brum7KmAgDVERAAQ0F4otKjEgk6VJmImSJ
        2dPt1uBlXZu03SJve/IP8d0H4gOJJmbRN/8o43e7ib/ow/fj3HPOd+/X7z8+
        fQHwEA8YpltR1+4E0rMPTvdlnKQvvRO/lebBGMonoi/sQITH9i9UYzB2ZCjT
        pwza6lqjhBwMCzryDHr6RiYMVfdyyyek8ESc6Wo5jD7OYO60gszRAlc2plM/
        PNqt770oYRJWgcAKw5Ibxcf2iZ96sZBhYoswjFKRyojqepTWe0FA/pNuJ0rJ
        zD7wU9EWqSCMd/uaGqQCUwEMrEP4W6m6TaraWwyPhoOKxavc4uXhwOKmRkXB
        Gg7Mb+94dTjY5pvsMTOeF0z+9b2hm7ys1YyyPsc3c8pgmynbqwenh34rCtuj
        BzOsXL6J5b95edyhXbvS27141EaH1POvemEqu74T9mUivcD/fU5r1veits8w
        4crQr/e6nh8fCeIwVNyoJYKGiKXqx2DJCUM/3gtEkvgktg6jXtzy96U6mx3P
        afw3BVu0fT1b2az6GZRXqDMoT1HW6DSXdXeps9ViKefWz2GeqZXj3pgM1LBK
        sTQioEBWIMNihijxBrHVmT6FKx/+0bp/aPWx1sTExeAZYquv+Bn89TnKH3Ht
        LAM0rFFUt14iyiK9YT2zXsZ9ys8Iv06XmW5CczDjoOoQY44y5h3cwM0mWIJb
        WGgin8BKcDtBLoGRoJjViz8BtD1vnUcDAAA=
        """,
        """
        com/klib/ProtocolState＄TALKING.class:
        H4sIAAAAAAAA/31TS2/TQBD+1ukjdVP6gJb0ARQaStJCXCpuRUhVq4LBjRCp
        gqCnTbKEbexdab2OOObMrwEuSBxQJG78KMTYDUWiLZY8j52ZzzPfeH/++vYd
        wCNUGW62dOR1Q9n0XhptdUuHdcutKB3tBi/82tNxMIaFi3PGkWMYeyyVtE8o
        qRyc8B73Qq46Xt0aqTo7fqXBkCuncv+icNDVNpTKO+lFnlRWGMVDb1+840lo
        97SKrUlaVptDbrrC7FQaBYwi72IEEwwj9r2MGVaD/w+ww+CWhEqikuKRYJg7
        3wbD1GmGNm1JHTAwnwaLZSdzlsqVS75BlWuBNh3vRNim4VLFHldKU0hS815N
        21oShpQ1/nrXP6JmGIqXQRVwDfMTNNoCw+wfXg6F5W1uOUE4US9HO3NSwVIB
        arNL5x9k6m2R1X7IsD7oT7qDvusUHVIbbNDP//jIioP+trPFnudnnCXSzxbS
        7O1Ll18dckeNn1kzgWzung1X7VrawJ5uE6PTgVSilkRNYY54M8w41i0eNriR
        qT88nKinfNrEkL38KlFWRsJXPRlLCv9FppUWfKWE2Qt5HAty3bpOTEscyBRl
        cVjZOFeHLThEX/o49I5ijPQ6WQd0mlKU39hcYV/hfs4y7pEcIw6BNyiTXD3N
        wSQKGUYeU7iSxfOYxgxVVLI6uhCYxdwQu5pugfTIF1z/9A/u2wy3cBof4m4M
        o1dJ57BJ0iUv7buEIu5nCHfxILuc9LPQFIvHyPlY8rHsYwU3SOOmj1tYPQaL
        cRt3KB5jLcZ8jNnflXyZidkDAAA=
        """,
        """
        com/klib/ProtocolState＄WAITING.class:
        H4sIAAAAAAAA/31TS2/TQBD+1ukjdVP6gJb0QSk0lKSFuFTcipCqVgWDGyFS
        wqGnTbKEbexdab2OOObMrwEuSBxQJG78KMTYDUWiLZY8j52ZzzPfeH/++vYd
        wGNUGVZbOvK6oWx6r4y2uqXDuuVWlN7u+cd+7dk4GMPC5TnjyDGMPZFK2qeU
        VA5OeY97IVcdr26NVJ1dv9JgyJVTeXBZOOhqG0rlnfYiTyorjOKhdyDe8SS0
        +1rF1iQtq80RN11hdiuNAkaRdzGCCYYR+17GDGvB/wfYZXBLQiVRSfFIMMxd
        bINh6ixDm7akDhiYT4PFspM5S+XKFd+gyvVAm453KmzTcKlijyulKSSpea+m
        bS0JQ8oaP94LXlIzDMWroAq4gfkJGm2BYfYPL0fC8ja3nCCcqJejnTmpYKkA
        tdml8w8y9bbJaj9i2Bj0J91B33WKDqlNNujnf3xkxUF/x9lmL/IzzhLp5wtp
        9s6Vy68OuaPGz62ZQDb3zoerdi1tYF+3idHpQCpRS6KmMMe8GWYc6xYPG9zI
        1B8eTtRTPm1iyF5+nSgrI+Grnowlhf8i00oLvlLC7Ic8jgW5bl0npiUOZYqy
        OKxsXKjDNhyiL30cekcxRnqDrEM6TSnKb26tsK9wP2cZ90mOEYfAG5RJrp3l
        YBKFDCOPKVzL4nlMY4YqKlkdXQjMYm6IXU23QHrkC25++ge3keEWzuJD3M1h
        9DrpHLZIuuSlfZdQxIMM4R4eZpeTfhaaYvEEOR9LPpZ9rOAWaaz6uI21E7AY
        d3CX4jHWY8zHmP0NVIYRE9kDAAA=
        """,
        """
        com/klib/ProtocolState.class:
        H4sIAAAAAAAA/5VVWVPbVhT+rrxIVpzEmCxmaRIaN7GdgMAl6WLK7iQKxiTY
        cUrdJpWNQgWyNCPJTB/pS39If0GAmcIk046Hx/6oTs+VzRYwM33QvVfnfuc7
        q47++ffD3wDGoTHcqNsNZcM0asoLx/bsum2WPM3TRTCGTGFd29QUU7PWlLzV
        bEwUzgfnJnMMV05jRQQZwhOGZXiTZCV1gqrkOYa1llPTFYZAKl2JIgxRRggR
        hqD3i+EyJLpZYpCTOtEnLa2hM8TP0jJcbiNsZ9WwNJOBqeSJa6z5L/2pdHfu
        uwXbWVPWda/maIblKppl2XRl2HQu2l6xaZqECm9qZlMnLwdS6Wp3MjFZmSm8
        ypcY+rqioriBmxEISDDEjkNZqq3rdU9EP0OobtoWRXqN/P4UQOqD+EzGAG6J
        uEkWfceW3jE8PCffF8T96CR8ztRcN3ee/ukKk/Uh3OV1S5Kbvmmqzpru5S1S
        4PkZJJ83bM80LIVXxPXVOrdk9fEF1xe2mpTMF8vLKk9tf3cDUWTwgOf2Ia/F
        Yc3E1zNqWS0+jUJBlN+Okqw8U1jwZdm27EuG+fM69tDY+mZDMSxPd6illHn9
        ndY0vTlqEs9p1j3bWdScDd3JpSsycVFT9ybrx5dvG/4tw8j/Y6OwJ+pm53u6
        dX52kp3gRHwrQZGR49a7YTtBi5iUkJUxhUgUI7jOfZ5l6Kl+Wm4R85Tvbule
        oHZ9wnBJPxYxDKfOsFzQEVE8gyrjKZ6T+UPYou5pq5qnUfxCYzNAY0vgC+ML
        6NveIPmvBn+jQgqrYwyzra2oLCQEWYjFZEEKSQe/H/wmJFpbWSkejAujra1R
        NhuRhIM/wkFJiAWeS7FwPxc/u7cuSEF6Qpwpyzh/rGDUZo6mwMiGRyNqzl6l
        Tr9aMCy92GzUdKes1Ux/GNl1zaxojsHfO8JIiQ8er+nQeWC5aXlGQ1etTcM1
        6PqYmZIVVS1Ld/yvj+dOLtlNp64/MThLX0ezckZvfojqFaLwg9N9vKUB2rN8
        j/Xz4eLv9CEQQiIkTVpaV+gtjwB42sTMg8E9yNs8s/iB1jCFDZRRpfVOG4JL
        nNA/XcYV/17EVcRI40dfT6Jh34M43fzUYeilPYJruE5nbu5zXwuQd9D3J263
        cOe9X8BDi2G8IfyQj1vxf06CryHHhcw+vmhBeH/KQ47nHkbbKNwjv7jF+0iR
        z5xhsGMxuIPh07YCpNv2O33SX0rUyJG/twnF9XtDf0FYyAR2MFbKENV4KcPJ
        EOMuPOqA5wgcPMrl423fGvcu0xZfkD9++gpfE4GEb+hpE6q+HjD8EbmV+ERg
        D9/tYuwjplbi08E9zOxifB9zu+ijbLaQ38fCLoa3j4KUfeJXZGyFiN7QOU6S
        KXqySNBUEKhTEp0UBPDW37/Hz7RPE0OB+mmxioCKooolFS/wknYsqyihXAVz
        iblShegi6uK1S/9WflZcZF30/AfNuHOWXAgAAA==
        """,
      )

    val klibFile =
      KlibTestFile(
        "/myklib.klib",
        "" +
          "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" +
          "AAA=",
        -0x6b251479,
        kotlin(klibSourceFile),
      )

    fun getUastInfo(uFile: UFile): String {
      val sb = StringBuilder()
      val expressions = uFile.classes[0].methods[0].uastBody.asSafely<UBlockExpression>()!!.expressions

      fun appendInfo(name: String, method: PsiMethod?) {
        with(sb) {
          appendLine("")
          appendLine("Info for: ${name}")
          appendLine("method?.name: ${method?.name}")
          appendLine("method?.isConstructor: ${method?.isConstructor}")
          appendLine("method?.containingClass?.qualifiedName: ${method?.containingClass?.qualifiedName}")
          appendLine("method?.returnType?.canonicalText: ${method?.returnType?.canonicalText}")
          val params = method?.parameterList?.parameters?.asSequence()?.map { "${it.name}: ${it.type.canonicalText}" }?.joinToString()
          appendLine("params: ${params}")
        }
      }

      fun appendInfo(name: String, cls: PsiClass?) {
        with(sb) {
          appendLine("")
          appendLine("Info for: ${name}:")
          appendLine("cls?.supers?.joinToString(): ${cls?.supers?.joinToString()}")
          appendLine("cls?.superTypes?.joinToString(): ${cls?.superTypes?.joinToString()}")
          appendLine("cls?.superClass: ${cls?.superClass}")
          appendLine("cls?.superClassType: ${cls?.superClassType}")
        }
      }

      fun appendInfo(name: String, field: PsiField?) {
        with(sb) {
          appendLine("")
          appendLine("Info for: ${name}")
          appendLine("field?.containingClass?.qualifiedName: ${field?.containingClass?.qualifiedName}")
          appendLine("field?.type?.canonicalText: ${field?.type?.canonicalText}")
          appendLine("field?.hasModifierProperty(\"private\"): ${field?.hasModifierProperty("private")}")
          appendLine("field?.hasModifierProperty(\"public\"): ${field?.hasModifierProperty("public")}")
        }
      }

      appendInfo("libGlobalMethod()", expressions[0].tryResolve().asSafely<PsiMethod>())

      appendInfo("libGlobalMethod(1)", expressions[1].tryResolve().asSafely<PsiMethod>())

      appendInfo("libGlobalMethod2(1)", expressions[2].tryResolve().asSafely<PsiMethod>())

      appendInfo("globalProperty", expressions[3].tryResolve().asSafely<PsiMethod>())
      appendInfo("1.globalProperty", expressions[4].tryResolve().asSafely<PsiMethod>())
      appendInfo("\"\".globalProperty", expressions[5].tryResolve().asSafely<PsiMethod>())
      appendInfo("globalProperty2", expressions[6].tryResolve().asSafely<PsiMethod>())

      appendInfo("LIB_CONST", expressions[7].tryResolve().asSafely<PsiField>())

      val constructor =
        expressions[8]
          .asSafely<UDeclarationsExpression>()!!
          .declarations[0]
          .asSafely<ULocalVariable>()!!
          .uastInitializer
          .asSafely<UCallExpression>()!!
      appendInfo("LibClass()", constructor.resolve().asSafely<PsiMethod>())

      // Yields null on KMP native because of b/458272425.
      sb.appendLine("UCallExpression.classReference: ${constructor.classReference?.resolve()?.asSafely<PsiClass>()?.qualifiedName}")

      appendInfo("containingClass", constructor.resolve().asSafely<PsiMethod>()?.containingClass)

      appendInfo("+c", expressions[9].asSafely<UUnaryExpression>()!!.resolveOperator()?.asSafely<PsiMethod>())

      appendInfo("LibClass.companionFunc()", expressions[10].tryResolve().asSafely<PsiMethod>())

      appendInfo("LibClass.companionProp", expressions[11].tryResolve().asSafely<PsiMethod>())

      appendInfo("LibClass.Companion", expressions[12].tryResolve().asSafely<PsiClass>())

      appendInfo("LibClass.Companion.companionFunc()", expressions[13].tryResolve().asSafely<PsiMethod>())

      appendInfo("LibClass.Companion.companionProp", expressions[14].tryResolve().asSafely<PsiMethod>())

      appendInfo("LibClass.LibClassObject", expressions[15].tryResolve().asSafely<PsiClass>())

      appendInfo("LibClass.LibClassObject.func()", expressions[16].tryResolve().asSafely<PsiMethod>())

      appendInfo("LibClass.LibClassObject.prop", expressions[17].tryResolve().asSafely<PsiMethod>())

      appendInfo("MyFirstObject", expressions[18].tryResolve().asSafely<PsiClass>())

      appendInfo("MyFirstObject.bar()", expressions[19].tryResolve().asSafely<PsiMethod>())

      appendInfo("MyFirstObject.MySecondObject", expressions[20].tryResolve().asSafely<PsiClass>())

      appendInfo("MyFirstObject.MySecondObject.prop", expressions[21].tryResolve().asSafely<PsiMethod>())

      appendInfo("MyFirstObject.MySecondObject.foo()", expressions[22].tryResolve().asSafely<PsiMethod>())

      appendInfo("ProtocolState.WAITING", expressions[23].tryResolve().asSafely<PsiField>())

      val expressions2 = uFile.classes[0].methods[1].uastBody.asSafely<UBlockExpression>()!!.expressions

      appendInfo("1.valProp", expressions2[0].tryResolve().asSafely<PsiMethod>())

      appendInfo("\"\".valProp", expressions2[1].tryResolve().asSafely<PsiMethod>())

      appendInfo("valProp", expressions2[2].tryResolve().asSafely<PsiMethod>())

      appendInfo("varProp = 2", expressions2[3].asSafely<UBinaryExpression>()!!.leftOperand.tryResolve().asSafely<PsiMethod>())

      appendInfo("varProp = 2 (operator)", expressions2[3].asSafely<UBinaryExpression>()!!.resolveOperator().asSafely<PsiMethod>())

      appendInfo("varProp += 2", expressions2[4].asSafely<UBinaryExpression>()!!.leftOperand.tryResolve().asSafely<PsiMethod>())

      appendInfo("varProp += 2 (operator)", expressions2[4].asSafely<UBinaryExpression>()!!.resolveOperator().asSafely<PsiMethod>())

      appendInfo("varProp--", expressions2[5].asSafely<UUnaryExpression>()!!.operand.tryResolve().asSafely<PsiMethod>())

      appendInfo("varProp-- (operator)", expressions2[5].asSafely<UUnaryExpression>()!!.resolveOperator().asSafely<PsiMethod>())

      appendInfo("++varProp", expressions2[6].asSafely<UUnaryExpression>()!!.operand.tryResolve().asSafely<PsiMethod>())

      appendInfo("++varProp (operator)", expressions2[6].asSafely<UUnaryExpression>()!!.resolveOperator().asSafely<PsiMethod>())

      appendInfo("varProp", expressions2[7].tryResolve().asSafely<PsiMethod>())

      appendInfo("propWithGetter", expressions2[8].tryResolve().asSafely<PsiMethod>())

      appendInfo("mutableListProp += \"a\"", expressions2[9].asSafely<UBinaryExpression>()!!.leftOperand.tryResolve().asSafely<PsiMethod>())

      appendInfo(
        "mutableListProp += \"a\" (operator)",
        expressions2[9].asSafely<UBinaryExpression>()!!.resolveOperator().asSafely<PsiMethod>(),
      )

      appendInfo("propWithField", expressions2[10].tryResolve().asSafely<PsiMethod>())

      appendInfo("libMethod(1)", expressions2[11].tryResolve().asSafely<PsiMethod>())

      appendInfo("libMethod(1L)", expressions2[12].tryResolve().asSafely<PsiMethod>())

      appendInfo("libMethod2(1L)", expressions2[13].tryResolve().asSafely<PsiMethod>())

      appendInfo("libMethod3(arrayOf(1L))", expressions2[14].tryResolve().asSafely<PsiMethod>())

      appendInfo("libGenericMethod(1)", expressions2[15].tryResolve().asSafely<PsiMethod>())

      return sb.toString()
    }

    fun getUastInfo(descriptorFile: File): String? {
      var result: String? = null
      MainTest.checkDriver(
        "No issues found.",
        "",
        // Expected exit code
        ERRNO_SUCCESS,
        // Args
        arrayOf("--project", descriptorFile.path, "--XuseKlibLightElementProvider"),
        null,
        { driver, type, project, context ->
          when (type) {
            SCANNING_FILE -> {
              context!!
              when (context.file.name) {
                "Code.kt" -> {
                  context as JavaContext
                  val uFile = context.uastParser.parse(context)!!
                  result = getUastInfo(uFile)
                }
              }
            }
            else -> {}
          }
        },
      )
      return result?.lineSequence()?.joinToString("\n") { it.trim() }?.trim()
    }

    val sourceWithKlibProject =
      lint()
        .files(
          xml(
              "project.xml",
              """
              <project>
                <module name="mycode" library="true" android="false" compute_source_roots="false" kotlinPlatforms="Native [general]">
                  <src file="com/example/Code.kt"/>
                  <klib file="myklib.klib" />
                </module>
                <!--<module name="fake_module" library="true" android="false" compute_source_roots="false" kotlinPlatforms="JVM [1.8]">
                </module>-->
              </project>
              """,
            )
            .indented(),
          kotlin("com/example/Code.kt", kotlinSourceFile).indented(),
          klibFile,
        )
        .createProjects(temp.newFolder().canonicalFile.absoluteFile)
    val sourceWithKlibInfo = getUastInfo(File(sourceWithKlibProject[0], "project.xml"))

    // TODO(b/450898213): The following lines can be fixed by adding a fake Java module to the
    //  project structure, but this leads to nondeterministic failures, possibly because the native
    //  module ends up (nondeterministically) being treated like a Java module, and the klibs no
    //  longer resolve.
    //  cls?.supers?.joinToString(): [empty] (should be PsiClass:Object)
    //  cls?.superClass: null (should be PsiClass:Object)

    assertEquals(
      """
      Info for: libGlobalMethod()
      method?.name: libGlobalMethod
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.Facade${'$'}libGlobalMethod
      method?.returnType?.canonicalText: int
      params:

      Info for: libGlobalMethod(1)
      method?.name: libGlobalMethod
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.Facade${'$'}libGlobalMethod
      method?.returnType?.canonicalText: int
      params: a: int

      Info for: libGlobalMethod2(1)
      method?.name: libGlobalMethod2
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.Facade${'$'}libGlobalMethod2
      method?.returnType?.canonicalText: int
      params: a: int

      Info for: globalProperty
      method?.name: getGlobalProperty
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.Facade${'$'}globalProperty
      method?.returnType?.canonicalText: int
      params:

      Info for: 1.globalProperty
      method?.name: getGlobalProperty
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.Facade${'$'}globalProperty
      method?.returnType?.canonicalText: java.lang.String
      params: ${'$'}this${'$'}globalProperty: int

      Info for: "".globalProperty
      method?.name: getGlobalProperty
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.Facade${'$'}globalProperty
      method?.returnType?.canonicalText: int
      params: ${'$'}this${'$'}globalProperty: java.lang.String

      Info for: globalProperty2
      method?.name: getGlobalProperty2
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.Facade${'$'}globalProperty2
      method?.returnType?.canonicalText: int
      params:

      Info for: LIB_CONST
      field?.containingClass?.qualifiedName: com.klib.Facade${'$'}LIB_CONST
      field?.type?.canonicalText: java.lang.String
      field?.hasModifierProperty("private"): false
      field?.hasModifierProperty("public"): true

      Info for: LibClass()
      method?.name: LibClass
      method?.isConstructor: true
      method?.containingClass?.qualifiedName: com.klib.LibClass
      method?.returnType?.canonicalText: null
      params:
      UCallExpression.classReference: com.klib.LibClass

      Info for: containingClass:
      cls?.supers?.joinToString():
      cls?.superTypes?.joinToString(): PsiType:Object
      cls?.superClass: null
      cls?.superClassType: PsiType:Object

      Info for: +c
      method?.name: unaryPlus
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.LibClass
      method?.returnType?.canonicalText: com.klib.LibClass
      params:

      Info for: LibClass.companionFunc()
      method?.name: companionFunc
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.LibClass.Companion
      method?.returnType?.canonicalText: int
      params:

      Info for: LibClass.companionProp
      method?.name: getCompanionProp
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.LibClass.Companion
      method?.returnType?.canonicalText: int
      params:

      Info for: LibClass.Companion:
      cls?.supers?.joinToString():
      cls?.superTypes?.joinToString(): PsiType:Object
      cls?.superClass: null
      cls?.superClassType: PsiType:Object

      Info for: LibClass.Companion.companionFunc()
      method?.name: companionFunc
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.LibClass.Companion
      method?.returnType?.canonicalText: int
      params:

      Info for: LibClass.Companion.companionProp
      method?.name: getCompanionProp
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.LibClass.Companion
      method?.returnType?.canonicalText: int
      params:

      Info for: LibClass.LibClassObject:
      cls?.supers?.joinToString():
      cls?.superTypes?.joinToString(): PsiType:Object
      cls?.superClass: null
      cls?.superClassType: PsiType:Object

      Info for: LibClass.LibClassObject.func()
      method?.name: func
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.LibClass.LibClassObject
      method?.returnType?.canonicalText: int
      params:

      Info for: LibClass.LibClassObject.prop
      method?.name: getProp
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.LibClass.LibClassObject
      method?.returnType?.canonicalText: int
      params:

      Info for: MyFirstObject:
      cls?.supers?.joinToString():
      cls?.superTypes?.joinToString(): PsiType:Object
      cls?.superClass: null
      cls?.superClassType: PsiType:Object

      Info for: MyFirstObject.bar()
      method?.name: bar
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.MyFirstObject
      method?.returnType?.canonicalText: long
      params:

      Info for: MyFirstObject.MySecondObject:
      cls?.supers?.joinToString():
      cls?.superTypes?.joinToString(): PsiType:Object
      cls?.superClass: null
      cls?.superClassType: PsiType:Object

      Info for: MyFirstObject.MySecondObject.prop
      method?.name: getProp
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.MyFirstObject.MySecondObject
      method?.returnType?.canonicalText: int
      params:

      Info for: MyFirstObject.MySecondObject.foo()
      method?.name: foo
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.MyFirstObject.MySecondObject
      method?.returnType?.canonicalText: int
      params:

      Info for: ProtocolState.WAITING
      field?.containingClass?.qualifiedName: com.klib.ProtocolState
      field?.type?.canonicalText: com.klib.ProtocolState
      field?.hasModifierProperty("private"): false
      field?.hasModifierProperty("public"): true

      Info for: 1.valProp
      method?.name: getValProp
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.LibClass
      method?.returnType?.canonicalText: int
      params: ${'$'}this${'$'}valProp: int

      Info for: "".valProp
      method?.name: getValProp
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.LibClass
      method?.returnType?.canonicalText: int
      params: ${'$'}this${'$'}valProp: java.lang.String

      Info for: valProp
      method?.name: getValProp
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.LibClass
      method?.returnType?.canonicalText: int
      params:

      Info for: varProp = 2
      method?.name: setVarProp
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.LibClass
      method?.returnType?.canonicalText: void
      params: <set-?>: int

      Info for: varProp = 2 (operator)
      method?.name: setVarProp
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.LibClass
      method?.returnType?.canonicalText: void
      params: <set-?>: int

      Info for: varProp += 2
      method?.name: setVarProp
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.LibClass
      method?.returnType?.canonicalText: void
      params: <set-?>: int

      Info for: varProp += 2 (operator)
      method?.name: plus
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: kotlin.Int
      method?.returnType?.canonicalText: int
      params: other: int

      Info for: varProp--
      method?.name: setVarProp
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.LibClass
      method?.returnType?.canonicalText: void
      params: <set-?>: int

      Info for: varProp-- (operator)
      method?.name: dec
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: kotlin.Int
      method?.returnType?.canonicalText: int
      params:

      Info for: ++varProp
      method?.name: setVarProp
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.LibClass
      method?.returnType?.canonicalText: void
      params: <set-?>: int

      Info for: ++varProp (operator)
      method?.name: inc
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: kotlin.Int
      method?.returnType?.canonicalText: int
      params:

      Info for: varProp
      method?.name: getVarProp
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.LibClass
      method?.returnType?.canonicalText: int
      params:

      Info for: propWithGetter
      method?.name: getPropWithGetter
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.LibClass
      method?.returnType?.canonicalText: int
      params:

      Info for: mutableListProp += "a"
      method?.name: null
      method?.isConstructor: null
      method?.containingClass?.qualifiedName: null
      method?.returnType?.canonicalText: null
      params: null

      Info for: mutableListProp += "a" (operator)
      method?.name: plus
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: kotlin.Facade${'$'}plus
      method?.returnType?.canonicalText: java.lang.String
      params: ${'$'}this${'$'}plus: java.lang.String, other: java.lang.Object

      Info for: propWithField
      method?.name: getPropWithField
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.LibClass
      method?.returnType?.canonicalText: int
      params:

      Info for: libMethod(1)
      method?.name: libMethod
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.LibClass
      method?.returnType?.canonicalText: int
      params: arg: int

      Info for: libMethod(1L)
      method?.name: libMethod
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.LibClass
      method?.returnType?.canonicalText: long
      params: arg: long

      Info for: libMethod2(1L)
      method?.name: libMethod2
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.LibClass
      method?.returnType?.canonicalText: long
      params: arg: long

      Info for: libMethod3(arrayOf(1L))
      method?.name: libMethod3
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.LibClass
      method?.returnType?.canonicalText: java.lang.Long[]
      params: arg: java.lang.Long[]

      Info for: libGenericMethod(1)
      method?.name: libGenericMethod
      method?.isConstructor: false
      method?.containingClass?.qualifiedName: com.klib.LibClass
      method?.returnType?.canonicalText: T[]
      params: arg: T
      """
        .trimIndent(),
      sourceWithKlibInfo,
    )

    // Uncomment to compare against source.
    //    val sourceWithSourceProject =
    //      lint()
    //        .files(
    //          xml(
    //            "project.xml",
    //            """
    //              <project>
    //                <module name="mycode" library="true" android="false"
    // compute_source_roots="false" kotlinPlatforms="JVM [1.8]">
    //                  <src file="com/example/Code.kt"/>
    //                  <src file="com/klib/Code.kt" generated="true" />
    //                </module>
    //              </project>
    //              """,
    //          )
    //            .indented(),
    //          kotlin(
    //            "com/example/Code.kt",
    //            kotlinSourceFile,
    //          )
    //            .indented(),
    //          kotlin(
    //            "com/klib/Code.kt",
    //            klibSourceFile,
    //          )
    //            .indented(),
    //        )
    //        .createProjects(temp.newFolder().canonicalFile.absoluteFile)
    //    val sourceWithSourceInfo = getUastInfo(File(sourceWithSourceProject[0], "project.xml"))
    //    assertEquals(sourceWithSourceInfo, sourceWithKlibInfo)

    // Uncomment to compare against jar.
    //    val sourceWithJarProject =
    //      lint()
    //        .files(
    //          xml(
    //            "project.xml",
    //            """
    //              <project>
    //                <module name="mycode" library="true" android="false"
    // compute_source_roots="false" kotlinPlatforms="JVM [1.8]">
    //                  <src file="com/example/Code.kt"/>
    //                  <jar file="mylib.jar" />
    //                </module>
    //              </project>
    //              """,
    //          )
    //            .indented(),
    //          kotlin(
    //            "com/example/Code.kt",
    //            kotlinSourceFile,
    //          )
    //            .indented(),
    //          jarFile,
    //        )
    //        .createProjects(temp.newFolder().canonicalFile.absoluteFile)
    //    val sourceWithJarInfo = getUastInfo(File(sourceWithJarProject[0], "project.xml"))
    //    assertEquals(sourceWithJarInfo, sourceWithKlibInfo)
  }

  @Test
  fun testGeneratedAndTestFile() {
    // Test/generated sources cannot be in the same root as non-test/non-generated sources with
    // Lint's K1 project structure, so we can only test on K2.
    assumeTrue(useFirUast())
    val root = temp.newFolder().canonicalFile.absoluteFile
    val projects =
      lint()
        .files(
          xml(
              "lint.xml",
              """
              <lint checkTestSources="true" checkGeneratedSources="true">
              </lint>
              """,
            )
            .indented(),
          xml(
              "project.xml",
              """
              <project>
                <module name="test" android="true" library="false" compute_source_roots="false">
                  <src file="com/example/A.java" test="true"/>
                  <src file="com/example/B.java" generated="true"/>
                  <src file="com/example/C.java" test="true" generated="true"/>
                  <src file="com/example/D.java"/>
                </module>
              </project>
              """,
            )
            .indented(),
          java(
              "com/example/A.java",
              """
              package com.example;
              class A {}
              """,
            )
            .indented(),
          java(
              "com/example/B.java",
              """
              package com.example;
              class B {}
              """,
            )
            .indented(),
          java(
              "com/example/C.java",
              """
              package com.example;
              class C {}
              """,
            )
            .indented(),
          java(
              "com/example/D.java",
              """
              package com.example;
              class D {}
              """,
            )
            .indented(),
        )
        .createProjects(root)
    val descriptorFile = File(projects[0], "project.xml")
    val configFile = File(projects[0], "lint.xml")

    var numFilesVisited = 0

    MainTest.checkDriver(
      "No issues found.",
      "",

      // Expected exit code
      ERRNO_SUCCESS,

      // Args
      arrayOf("--check", "SetAndClearCommunicationDevice", "--project", descriptorFile.path, "--config", configFile.path),
      null,
      { driver, type, project, context ->
        when (type) {
          SCANNING_FILE -> {
            context!!
            when (context.file.name) {
              "A.java" -> {
                context as JavaContext
                assertTrue(context.isTestSource)
                assertFalse(context.isGeneratedSource)
                ++numFilesVisited
              }
              "B.java" -> {
                context as JavaContext
                assertFalse(context.isTestSource)
                assertTrue(context.isGeneratedSource)
                ++numFilesVisited
              }
              "C.java" -> {
                context as JavaContext
                assertTrue(context.isTestSource)
                assertTrue(context.isGeneratedSource)
                ++numFilesVisited
              }
              "D.java" -> {
                context as JavaContext
                assertFalse(context.isTestSource)
                assertFalse(context.isGeneratedSource)
                ++numFilesVisited
              }
            }
          }
          else -> {}
        }
      },
    )
    assertEquals(4, numFilesVisited)
  }

  @Test
  fun testGeneratedAndTestFile2() {
    // Test/generated sources cannot be in the same root as non-test/non-generated sources with
    // Lint's K1 project structure, so we can only test on K2.
    assumeTrue(useFirUast())

    // Similar to testGeneratedAndTestFile (above), except we are not checking generated files.
    // In particular, file C (both test and gen) should not be visited.
    val root = temp.newFolder().canonicalFile.absoluteFile
    val projects =
      lint()
        .files(
          xml(
              "lint.xml",
              """
              <lint checkTestSources="true" checkGeneratedSources="false">
              </lint>
              """,
            )
            .indented(),
          xml(
              "project.xml",
              """
              <project>
                <module name="test" android="true" library="false" compute_source_roots="false">
                  <src file="com/example/A.java" test="true"/>
                  <src file="com/example/B.java" generated="true"/>
                  <src file="com/example/C.java" test="true" generated="true"/>
                  <src file="com/example/D.java"/>
                </module>
              </project>
              """,
            )
            .indented(),
          java(
              "com/example/A.java",
              """
              package com.example;
              class A {}
              """,
            )
            .indented(),
          java(
              "com/example/B.java",
              """
              package com.example;
              class B {}
              """,
            )
            .indented(),
          java(
              "com/example/C.java",
              """
              package com.example;
              class C {}
              """,
            )
            .indented(),
          java(
              "com/example/D.java",
              """
              package com.example;
              class D {}
              """,
            )
            .indented(),
        )
        .createProjects(root)
    val descriptorFile = File(projects[0], "project.xml")
    val configFile = File(projects[0], "lint.xml")

    var numFilesVisited = 0

    MainTest.checkDriver(
      "No issues found.",
      "",

      // Expected exit code
      ERRNO_SUCCESS,

      // Args
      arrayOf("--check", "SetAndClearCommunicationDevice", "--project", descriptorFile.path, "--config", configFile.path),
      null,
      { driver, type, project, context ->
        when (type) {
          SCANNING_FILE -> {
            context!!
            when (context.file.name) {
              "A.java" -> {
                context as JavaContext
                assertTrue(context.isTestSource)
                assertFalse(context.isGeneratedSource)
                ++numFilesVisited
              }
              "B.java" -> {
                fail("B.java should not be visited")
              }
              "C.java" -> {
                fail("C.java should not be visited")
              }
              "D.java" -> {
                context as JavaContext
                assertFalse(context.isTestSource)
                assertFalse(context.isGeneratedSource)
                ++numFilesVisited
              }
            }
          }
          else -> {}
        }
      },
    )
    assertEquals(2, numFilesVisited)
  }

  @Test
  fun testGeneratedAndTestFile3() {
    // Test/generated sources cannot be in the same root as non-test/non-generated sources with
    // Lint's K1 project structure, so we can only test on K2.
    assumeTrue(useFirUast())

    // Similar to testGeneratedAndTestFile2 (above), except we are not checking test files.
    // So we only visit normal and generated files (not test files, and not gen+test).
    val root = temp.newFolder().canonicalFile.absoluteFile
    val projects =
      lint()
        .files(
          xml(
              "lint.xml",
              """
              <lint checkTestSources="false" checkGeneratedSources="true">
              </lint>
              """,
            )
            .indented(),
          xml(
              "project.xml",
              """
              <project>
                <module name="test" android="true" library="false" compute_source_roots="false">
                  <src file="com/example/A.java" test="true"/>
                  <src file="com/example/B.java" generated="true"/>
                  <src file="com/example/C.java" test="true" generated="true"/>
                  <src file="com/example/D.java"/>
                </module>
              </project>
              """,
            )
            .indented(),
          java(
              "com/example/A.java",
              """
              package com.example;
              class A {}
              """,
            )
            .indented(),
          java(
              "com/example/B.java",
              """
              package com.example;
              class B {}
              """,
            )
            .indented(),
          java(
              "com/example/C.java",
              """
              package com.example;
              class C {}
              """,
            )
            .indented(),
          java(
              "com/example/D.java",
              """
              package com.example;
              class D {}
              """,
            )
            .indented(),
        )
        .createProjects(root)
    val descriptorFile = File(projects[0], "project.xml")
    val configFile = File(projects[0], "lint.xml")

    var numFilesVisited = 0

    MainTest.checkDriver(
      "No issues found.",
      "",

      // Expected exit code
      ERRNO_SUCCESS,

      // Args
      arrayOf("--check", "SetAndClearCommunicationDevice", "--project", descriptorFile.path, "--config", configFile.path),
      null,
      { driver, type, project, context ->
        when (type) {
          SCANNING_FILE -> {
            context!!
            when (context.file.name) {
              "A.java" -> {
                fail("A.java should not be visited")
              }
              "B.java" -> {
                context as JavaContext
                assertFalse(context.isTestSource)
                assertTrue(context.isGeneratedSource)
                ++numFilesVisited
              }
              "C.java" -> {
                fail("C.java should not be visited")
              }
              "D.java" -> {
                context as JavaContext
                assertFalse(context.isTestSource)
                assertFalse(context.isGeneratedSource)
                ++numFilesVisited
              }
            }
          }
          else -> {}
        }
      },
    )
    assertEquals(2, numFilesVisited)
  }

  @Test
  fun testTestProjectWithXml() {
    // Modules can be marked as test="true", which causes context.isTestSource to be true for
    // manifests and resources. Note that Lint will still visit these "test" XML files, without
    // requiring the TEST_SOURCES scope or checkTestSources flag (unlike test Java/Kotlin sources).
    val root = temp.newFolder().canonicalFile.absoluteFile
    val projects =
      lint()
        .files(
          xml(
              "lint.xml",
              """
              <lint checkTestSources="true" checkGeneratedSources="true">
              </lint>
              """,
            )
            .indented(),
          xml(
              "project.xml",
              """
              <project>
                <module name="test" android="true" library="false" test="true" compute_source_roots="false">
                  <src file="com/example/A.java"/>
                  <src file="com/example/B.java" generated="true"/>
                  <manifest file="com/example/AndroidManifest.xml" />
                  <merged-manifest file="com/example/AndroidManifest.xml" />
                  <resource file="com/example/res/values/strings.xml" />
                </module>
              </project>
              """,
            )
            .indented(),
          xml(
              "com/example/AndroidManifest.xml",
              """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                  package="com.example.app">

                  <uses-sdk
                      android:minSdkVersion="14"
                      android:targetSdkVersion="33" />

                  <application>
                      <activity
                          android:name="com.google.example.Activity"
                          android:exported="true" />
                      <action android:name="android.intent.action.MAIN" />
                      <category android:name="android.intent.category.LAUNCHER" />
                  </application>

              </manifest>
              """,
            )
            .indented(),
          xml(
              "com/example/res/values/strings.xml",
              """
              <resources>
                <string name="string1">String 1</string>
                <string name="string1">String 2</string>
                <string name="string3">String 3</string>
                <string name="string3">String 4</string>
              </resources>
              """,
            )
            .indented(),
          java(
              "com/example/A.java",
              """
              package com.example;
              class A {}
              """,
            )
            .indented(),
          java(
              "com/example/B.java",
              """
              package com.example;
              class B {}
              """,
            )
            .indented(),
        )
        .createProjects(root)
    val descriptorFile = File(projects[0], "project.xml")
    val configFile = File(projects[0], "lint.xml")

    var numFilesVisited = 0

    MainTest.checkDriver(
      "No issues found.",
      "",

      // Expected exit code
      ERRNO_SUCCESS,

      // Args
      // These checks are chosen to get a scope that includes JAVA_FILE, MANIFEST, and
      // RESOURCE_FILE (the values folders), and with no extra phases requested.
      arrayOf("--check", "ButtonOrder,ShortAlarm", "--project", descriptorFile.path, "--config", configFile.path),
      null,
      { driver, type, project, context ->
        when (type) {
          SCANNING_FILE -> {
            context!!
            when (context.file.name) {
              "A.java" -> {
                context as JavaContext
                assertEquals(true, context.project.isTestProject)
                assertTrue(context.isTestSource)
                assertFalse(context.isGeneratedSource)
                ++numFilesVisited
              }
              "B.java" -> {
                context as JavaContext
                assertEquals(true, context.project.isTestProject)
                assertTrue(context.isTestSource)
                assertTrue(context.isGeneratedSource)
                ++numFilesVisited
              }
              "strings.xml" -> {
                context as XmlContext
                assertEquals(true, context.project.isTestProject)
                assertTrue(context.isTestSource)
                ++numFilesVisited
              }
              "AndroidManifest.xml" -> {
                context as XmlContext
                assertEquals(true, context.project.isTestProject)
                assertTrue(context.isTestSource)
                ++numFilesVisited
              }
            }
          }
          else -> {}
        }
      },
    )
    assertEquals(4, numFilesVisited)
  }

  @Test
  fun testKMPProjectK2() {
    assumeTrue(useFirUast())
    val shared =
      project(
          kt(
            "src/commonMain/kotlin/pkg/Platform.kt",
            """
            package pkg
            interface Platform {
                val name: String
            }
            expect fun getPlatform(): Platform
            """
              .trimIndent(),
          ),
          kt(
            "src/commonMain/kotlin/pkg/Greeting.kt",
            """
            package pkg
            class Greeting {
                private val platform: Platform = getPlatform()
            }
            """
              .trimIndent(),
          ),
          kt(
            "src/androidMain/kotlin/pkg/Platform.kt",
            """
            package pkg
            class AndroidPlatform : Platform {
                override val name: String = "Android 34"
            }
            actual fun getPlatform(): Platform = AndroidPlatform()
            """
              .trimIndent(),
          ),
          kt(
            "src/iosMain/kotlin/pkg/Platform.kt",
            """
            package pkg
            import platform.UIKit.UIDevice
            class IOSPlatform: Platform {
                override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
            }
            actual fun getPlatform(): Platform = IOSPlatform()
            """
              .trimIndent(),
          ),
          klib(
            "libs/SomeKlib.klib",
            "" +
              "H4sIAAAAAAAA/52Xe1BTVx7HL3kHApMXDykyCUgKKw0BfKwVuwGUAYI0Vsva" +
              "Gprk5iaQJSSQB4MPmIRUjARcFC1ixbVAi1P7h7Bry0zrlmLSggJaaq11dbYG" +
              "7aqruz62re6a7V7CqvdK7uWmlxkeczmf3/d7zsk5359cRqaEA7OPEEA+DIAD" +
              "QFqd2mawZry6IRKgI1/KMYdFIIbpzcFH8gEGgzFn5AL0SIu+wqi22sxai7jK" +
              "aJ3D0ahUKlClkpB9qwU6KlhKc8XRWye8k17PcKpntG942Pu5d1TkGY3i+eQq" +
              "yvsq5/v87Te5jgPDzG/GUlg6SeZA4bbur6Y+3Xq+6fRzC3KbB8IzB2uZjkpR" +
              "HbTi8KvSE4pLea05sFo64yNK+BYdXLEXV33MM+qtZr2xAlf6KOArFXxPAetg" +
              "6bF7X2B4DPJxVg/v/ist97LX33OTue496y3cDvMr5YrLOX5FeY6/KT/Bf7B8" +
              "2qnoLl91UuE0FGdluW8P3peZs+4n3P+VMN6uu0PKjBjPObJKslLlayo0q37a" +
              "2xfTyYf6yZFFE4sdFys7ujpfIx0X8Ecprzed5GxURAYcRn846miDhflxHcah" +
              "HUJa0FZRZNSZYI8QlkeVA5QAgRpnJZ8URMIv+Lg1+Oga1s01+Msvd4A6gY4M" +
              "1rHPgxxVh2oKjK7UOOTSxNylCm5JoPCBqQ0iETxiA27hhejCevNqrcagNqut" +
              "epPRguewkAJm0BjPUSD7Rqary3EhyrVvO3/o1ysuUPanvDWY1seUFN2tra21" +
              "ZjOP2R+wSE0Fra15LdJ3lrXvIedIUxLYKQnSFOlLTvsiOfOsoOAIlSQ1pEBh" +
              "S/+1682m+HF5VFgU+w+5w+tkJcVvylzuHb27mUd0L36at4ncYbdTnZxkXsBj" +
              "WsyDThes6aNQJlenNwQmV4dlrYYO0sUimk64SZzt33OJ1JBviZBZNDIZOTtf" +
              "Y+ElJ0fI8pP9vh7I7/lkb8+jcb7/Eei3e3qunjvo6Tu44cQtrlBHU0emiWgF" +
              "4gKa0CImP3pvZj9ej4X6neOF8Ia8HnuOfIMesBCXuOxyDVy7A9dCNNoCaIL0" +
              "AQ8g5gYMA9fTGCoKWL5rJbc4XjEiLW75vfvyzn29u9e6cu3rfLsonYLC9h3u" +
              "4+7dO52R6ZSAmILov65ZAUPW4IphI8RUq416ndYyd6OunciMIgnZ0i8PnWz3" +
              "Gs41j4yM7L9QaRgT9HZRaDHNE4nK29eV//2pu4e9/LdJoyu/gUz9X1zbXNat" +
              "v3E9jntxa2l929WvF/fncY5uGlh2ytD20s3udWER5soPix5k31nich7iO8+2" +
              "lB5ulB9o8zRY/tJYxqSSGKzG8wVfDu1vdGTMmFEsWO5rhJUcAfDObS7CDHzm" +
              "mmxmjdYSysHPQQAMemMVpLaqQzn+Y4ONrzZBNoN2DmXaN8icPLP2h8mxksE/" +
              "gsfAtKIXxAM+YPcW6rc3ym7t3Jy25mFXWplC3vxG4wDPydvYxSvhBhb2ztGh" +
              "vKUwYDWuFWEwKTVqTZW6QqtUGyGzSQ+FYk1MhCd5/Bu8navnOgbZYDtYP+x9" +
              "0OJelZnxbupvsqkBR9uaPzdHw//Aw3W0iIACsQljvYObSieIlChNluCWBq+B" +
              "L4/L0k6dKbkCnsr4HowoTC8unbgCnp7MGBsbnwbrxo9e9W1niETX+m5qO2tq" +
              "hUN5v1OUkFiK9AQWqVVhSGgllXBZUo5O8PZrduoobedAkaQwM5Vp7mNmZhVK" +
              "GkDoCpileviZN5wHrmGs+g6s93qptvY3Gu8+LxaJymavvtM/n3HAYvpwpy8J" +
              "z6sV/tiLa6oqQpm8xYSAEiX8PejcpUwMhI9I+AVXtpqMmkf98s7vUn0bL11q" +
              "ICmFFwVTU8Vv0+vqbl2t9PwwPfwZ88BXB9WHLi/8kdu/BPrAd7Z0und7a7b8" +
              "zlv/rr/NO//z4RvmEzvqm168x/r7BdnhpPdcd19f3RBdKfu4beW5ktzJPx3L" +
              "2/U1/UfqlpP+P6/9Vqx6SJqZtuq/HfNsg3W4cKctMZhLs8lkVf7faihTFnQL" +
              "o2AS5bwfnbDAoje52dei4Des0NUj1ygU9anzwiSz6z6fg3cXHR+avaQ+Lqte" +
              "yAVmriE8F8hLyqo2V2itIZ3q8UGGw/pt9cr6ZUtCASXjgvRGjcEGaTFO1flP" +
              "6blEuHfQ12FssF/CqzJZ4T+C8cJI4QBWC8UE0M/Thoo6i8EbHoEavhwxPNBY" +
              "IQh84OmuQ7YrC1CEWjTh2QYLg4dsDmJQvIwwALPlwYAhu4A4FIxDAnC6Cwwc" +
              "MtvzUTgbGodoJDBQyAi9EIX6BxoVrDXAYCIzLVreFBnAiOIYKGQijUah9lEA" +
              "rEiMwUIGQjaKlUQFgiRaAluVi8L8E4FBZkkCIA4KJKEBwTIlhi9kuItFcWqC" +
              "cR5nSwKqhCjaf4LR5sZDDJXIwCZGcWV0AtxnYiIB8YtQRSYIFJlNgljnASIx" +
              "paPQAgYx9NNESEB9EqoEi4lTAhHEMLQjY8tiFLicEBgRyAhIT0RVyA8PUuGZ" +
              "MIQhG5lX0KvZPi/0cSgKWW9YxDwzgq0XmUxSUdDSeaHIGERAM/r8+gKBfxp2" +
              "CGDiURgaay4GGXoIAJNRwJdxgYjwE/JZ9A4u+UkICpl7F5f7JAw94VJpMz8T" +
              "4C8qjMmdueOB/wFP4UXFXBYAAA==",
            0x2a5ba622,
            kotlin(
                """
            package test.pkg
            import android.os.Parcelable
            abstract class Parent : Parcelable
                """
              )
              .indented(),
            kotlin(
                """
                  package android.os
                  interface Parcelable
                  interface Parcel
                """
              )
              .indented(),
          ),
        )
        .type(LIBRARY)
        .name("shared")

    val androidApp =
      project(
          source(
              "src/main/$ANDROID_MANIFEST_XML",
              """
          <manifest xmlns:android="http://schemas.android.com/apk/res/android">

              <uses-permission android:name="android.permission.INTERNET"/>

              <application
                  android:allowBackup="false"
                  android:supportsRtl="true"
                  android:theme="@style/AppTheme">
                  <activity
                      android:name=".MainActivity"
                      android:exported="true">
                      <intent-filter>
                          <action android:name="android.intent.action.MAIN" />
                          <category android:name="android.intent.category.LAUNCHER" />
                      </intent-filter>
                  </activity>
              </application>
          </manifest>
        """,
            )
            .indented(),
          xml(
              "src/main/res/values/styles.xml",
              """
            <resources>
                <style name="AppTheme" parent="android:Theme.Material.NoActionBar"/>
            </resources>
          """,
            )
            .indented(),
          kt(
              "src/main/java/pkg/android/MainActivity.kt",
              """
            package pkg.android

            import android.os.Bundle
            import androidx.activity.ComponentActivity
            import androidx.activity.compose.setContent
            import androidx.compose.foundation.layout.fillMaxSize
            import androidx.compose.material.*
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.tooling.preview.Preview
            import com.example.kmptest.Greeting
            import androidx.compose.runtime.*

            class MainActivity : ComponentActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContent {
                        MyApplicationTheme {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colors.background
                            ) {
                                var text by remember { mutableStateOf("Loading") }
                                LaunchedEffect(true) {
                                    text = try {
                                        Greeting().greet()
                                    } catch (e: Exception) {
                                        e.localizedMessage ?: "error"
                                    }
                                }
                                GreetingView(text)
                            }
                        }
                    }
                }
            }

            @Composable
            fun GreetingView(text: String) {
                Text(text = text)
            }

            @Preview
            @Composable
            fun DefaultPreview() {
                MyApplicationTheme {
                    GreetingView("Hello, Android!")
                }
            }
          """,
            )
            .indented(),
          kt(
              "src/main/java/pkg/android/MyApplicationTheme.kt",
              """
            package pkg.android

            import androidx.compose.foundation.isSystemInDarkTheme
            import androidx.compose.foundation.shape.RoundedCornerShape
            import androidx.compose.material.MaterialTheme
            import androidx.compose.material.Shapes
            import androidx.compose.material.Typography
            import androidx.compose.material.darkColors
            import androidx.compose.material.lightColors
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.graphics.Color
            import androidx.compose.ui.text.TextStyle
            import androidx.compose.ui.text.font.FontFamily
            import androidx.compose.ui.text.font.FontWeight
            import androidx.compose.ui.unit.dp
            import androidx.compose.ui.unit.sp

            @Composable
            fun MyApplicationTheme(
                darkTheme: Boolean = isSystemInDarkTheme(),
                content: @Composable () -> Unit
            ) {
                val colors = if (darkTheme) {
                    darkColors(
                        primary = Color(0xFFBB86FC),
                        primaryVariant = Color(0xFF3700B3),
                        secondary = Color(0xFF03DAC5)
                    )
                } else {
                    lightColors(
                        primary = Color(0xFF6200EE),
                        primaryVariant = Color(0xFF3700B3),
                        secondary = Color(0xFF03DAC5)
                    )
                }
                val typography = Typography(
                    body1 = TextStyle(
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Normal,
                        fontSize = 16.sp
                    )
                )
                val shapes = Shapes(
                    small = RoundedCornerShape(4.dp),
                    medium = RoundedCornerShape(4.dp),
                    large = RoundedCornerShape(0.dp)
                )

                MaterialTheme(
                    colors = colors,
                    typography = typography,
                    shapes = shapes,
                    content = content
                )
            }
          """,
            )
            .indented(),
          kt(
            "src/main/java/pkg/android/expect.kt",
            """
            package pkg
            expect fun getPlatform() : Platform
            """
              .trimIndent(),
          ),
          kt(
            "src/main/java/pkg/android/actual.kt",
            """
            package pkg
            actual fun getPlatform() = TODO()
            """
              .trimIndent(),
          ),
        )
        .name("androidApp")
        .dependsOn(shared, DependencyKind.DependsOn)

    val iosApp =
      project(
          source(
            "iosApp/ContentView.swift",
            """
            import SwiftUI
            import shared

            struct ContentView: View {
                @ObservedObject private(set) var viewModel: ViewModel

                var body: some View {
                    Text(viewModel.text)
                }
            }

            extension ContentView {
                class ViewModel: ObservableObject {
                    @Published var text = "Loading..."
                    init() {
                        Greeting().greet { greeting, error in
                                    DispatchQueue.main.async {
                                        if let greeting = greeting {
                                            self.text = greeting
                                        } else {
                                            self.text = error?.localizedDescription ?? "error"
                                        }
                                    }
                                }
                    }
                }
            }
            """
              .trimIndent(),
          ),
          source(
            "iosApp/iOSApp.swift",
            """
            import SwiftUI

            @main
            struct iOSApp: App {
              var body: some Scene {
                WindowGroup {
                        ContentView(viewModel: ContentView.ViewModel())
                }
              }
            }
            """
              .trimIndent(),
          ),
        )
        .name("iosApp")
        .dependsOn(shared, DependencyKind.DependsOn)

    val root = temp.newFolder().canonicalFile.absoluteFile
    @Language("XML")
    val descriptor =
      """
        <project>
          <sdk dir='${TestUtils.getSdk()}'/>
          <root dir="$root" />

          <module name="androidApp" android="true" library="false" compile-sdk-version='18'>
            <manifest file="androidApp/src/main/AndroidManifest.xml" />
            <resource file="androidApp/src/main/res/values/styles.xml" />
            <src file="androidApp/src/main/java/pkg/android/MainActivity.kt" />
            <src file="androidApp/src/main/java/pkg/android/MyApplicationTheme.kt" />
            <src file="androidApp/src/main/java/pkg/android/expect.kt" />
            <src file="androidApp/src/main/java/pkg/android/actual.kt" />
            <dep module="commonMain" kind="dependsOn" />
          </module>

          <module name="iosApp" android="false" library="false">
            <src file="iosApp/iosApp/ContentView.swift" />
            <src file="iosApp/iosApp/iOSApp.swift" />
            <dep module="commonMain" kind="dependsOn"/>
          </module>

          <module name="commonMain" android="false">
            <src file="shared/src/commonMain/kotlin/pkg/Platform.kt" />
            <src file="shared/src/androidMain/kotlin/pkg/Platform.kt" />
            <src file="shared/src/iosMain/kotlin/pkg/Platform.kt" />
            <klib file="shared/libs/SomeKlib.klib" />
          </module>
        </project>
      """
        .trimIndent()

    val projects = lint().projects(shared, androidApp, iosApp).createProjects(root)
    Files.asCharSink(File(root, "project.xml"), Charsets.UTF_8).write(descriptor)

    MainTest.checkDriver(
      """
        src/main/res/values/styles.xml:2: Error: android:Theme.Material.NoActionBar requires API level 21 (current min is 1) [NewApi]
    <style name="AppTheme" parent="android:Theme.Material.NoActionBar"/>
                           ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
src/main/AndroidManifest.xml:6: Warning: Attribute allowBackup is only used in API level 4 and higher (current min is 1) [UnusedAttribute]
        android:allowBackup="false"
        ~~~~~~~~~~~~~~~~~~~~~~~~~~~
src/main/AndroidManifest.xml:1: Warning: Should set android:versionCode to specify the application version [MissingVersion]
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
 ~~~~~~~~
src/main/AndroidManifest.xml:1: Warning: Should set android:versionName to specify the application version [MissingVersion]
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
 ~~~~~~~~
src/main/AndroidManifest.xml:5: Warning: Should explicitly set android:icon, there is no default [MissingApplicationIcon]
    <application
     ~~~~~~~~~~~
src/main/AndroidManifest.xml:7: Warning: You must set android:targetSdkVersion to at least 17 when enabling RTL support [RtlEnabled]
        android:supportsRtl="true"
                             ~~~~
1 error, 5 warnings
      """,
      "",
      ERRNO_SUCCESS,
      arrayOf("--project", File(root, "project.xml").path),
      { it.replace(root.canonicalPath, "ROOT").replace(root.path, "ROOT").dos2unix() },
      { _, _, _, _ -> },
    )
  }

  @Test
  fun testKMPProjectK2_explicitPlatform() {
    assumeTrue(useFirUast())
    val shared =
      project(
          kt(
            "src/commonMain/kotlin/pkg/Platform.kt",
            """
            package pkg
            interface Platform {
                val name: String
            }
            expect fun getPlatform(): Platform
            """
              .trimIndent(),
          ),
          kt(
            "src/commonMain/kotlin/pkg/Greeting.kt",
            """
            package pkg
            class Greeting {
                private val platform: Platform = getPlatform()
            }
            """
              .trimIndent(),
          ),
          kt(
            "src/androidMain/kotlin/pkg/Platform.kt",
            """
            package pkg
            class AndroidPlatform : Platform {
                override val name: String = "Android 34"
            }
            actual fun getPlatform(): Platform = AndroidPlatform()
            """
              .trimIndent(),
          ),
          kt(
            "src/iosMain/kotlin/pkg/Platform.kt",
            """
            package pkg
            import platform.UIKit.UIDevice
            class IOSPlatform: Platform {
                override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
            }
            actual fun getPlatform(): Platform = IOSPlatform()
            """
              .trimIndent(),
          ),
          klib(
            "libs/SomeKlib.klib",
            "" +
              "H4sIAAAAAAAA/52Xe1BTVx7HL3kHApMXDykyCUgKKw0BfKwVuwGUAYI0Vsva" +
              "Gprk5iaQJSSQB4MPmIRUjARcFC1ixbVAi1P7h7Bry0zrlmLSggJaaq11dbYG" +
              "7aqruz62re6a7V7CqvdK7uWmlxkeczmf3/d7zsk5359cRqaEA7OPEEA+DIAD" +
              "QFqd2mawZry6IRKgI1/KMYdFIIbpzcFH8gEGgzFn5AL0SIu+wqi22sxai7jK" +
              "aJ3D0ahUKlClkpB9qwU6KlhKc8XRWye8k17PcKpntG942Pu5d1TkGY3i+eQq" +
              "yvsq5/v87Te5jgPDzG/GUlg6SeZA4bbur6Y+3Xq+6fRzC3KbB8IzB2uZjkpR" +
              "HbTi8KvSE4pLea05sFo64yNK+BYdXLEXV33MM+qtZr2xAlf6KOArFXxPAetg" +
              "6bF7X2B4DPJxVg/v/ist97LX33OTue496y3cDvMr5YrLOX5FeY6/KT/Bf7B8" +
              "2qnoLl91UuE0FGdluW8P3peZs+4n3P+VMN6uu0PKjBjPObJKslLlayo0q37a" +
              "2xfTyYf6yZFFE4sdFys7ujpfIx0X8Ecprzed5GxURAYcRn846miDhflxHcah" +
              "HUJa0FZRZNSZYI8QlkeVA5QAgRpnJZ8URMIv+Lg1+Oga1s01+Msvd4A6gY4M" +
              "1rHPgxxVh2oKjK7UOOTSxNylCm5JoPCBqQ0iETxiA27hhejCevNqrcagNqut" +
              "epPRguewkAJm0BjPUSD7Rqary3EhyrVvO3/o1ysuUPanvDWY1seUFN2tra21" +
              "ZjOP2R+wSE0Fra15LdJ3lrXvIedIUxLYKQnSFOlLTvsiOfOsoOAIlSQ1pEBh" +
              "S/+1682m+HF5VFgU+w+5w+tkJcVvylzuHb27mUd0L36at4ncYbdTnZxkXsBj" +
              "WsyDThes6aNQJlenNwQmV4dlrYYO0sUimk64SZzt33OJ1JBviZBZNDIZOTtf" +
              "Y+ElJ0fI8pP9vh7I7/lkb8+jcb7/Eei3e3qunjvo6Tu44cQtrlBHU0emiWgF" +
              "4gKa0CImP3pvZj9ej4X6neOF8Ia8HnuOfIMesBCXuOxyDVy7A9dCNNoCaIL0" +
              "AQ8g5gYMA9fTGCoKWL5rJbc4XjEiLW75vfvyzn29u9e6cu3rfLsonYLC9h3u" +
              "4+7dO52R6ZSAmILov65ZAUPW4IphI8RUq416ndYyd6OunciMIgnZ0i8PnWz3" +
              "Gs41j4yM7L9QaRgT9HZRaDHNE4nK29eV//2pu4e9/LdJoyu/gUz9X1zbXNat" +
              "v3E9jntxa2l929WvF/fncY5uGlh2ytD20s3udWER5soPix5k31nich7iO8+2" +
              "lB5ulB9o8zRY/tJYxqSSGKzG8wVfDu1vdGTMmFEsWO5rhJUcAfDObS7CDHzm" +
              "mmxmjdYSysHPQQAMemMVpLaqQzn+Y4ONrzZBNoN2DmXaN8icPLP2h8mxksE/" +
              "gsfAtKIXxAM+YPcW6rc3ym7t3Jy25mFXWplC3vxG4wDPydvYxSvhBhb2ztGh" +
              "vKUwYDWuFWEwKTVqTZW6QqtUGyGzSQ+FYk1MhCd5/Bu8navnOgbZYDtYP+x9" +
              "0OJelZnxbupvsqkBR9uaPzdHw//Aw3W0iIACsQljvYObSieIlChNluCWBq+B" +
              "L4/L0k6dKbkCnsr4HowoTC8unbgCnp7MGBsbnwbrxo9e9W1niETX+m5qO2tq" +
              "hUN5v1OUkFiK9AQWqVVhSGgllXBZUo5O8PZrduoobedAkaQwM5Vp7mNmZhVK" +
              "GkDoCpileviZN5wHrmGs+g6s93qptvY3Gu8+LxaJymavvtM/n3HAYvpwpy8J" +
              "z6sV/tiLa6oqQpm8xYSAEiX8PejcpUwMhI9I+AVXtpqMmkf98s7vUn0bL11q" +
              "ICmFFwVTU8Vv0+vqbl2t9PwwPfwZ88BXB9WHLi/8kdu/BPrAd7Z0und7a7b8" +
              "zlv/rr/NO//z4RvmEzvqm168x/r7BdnhpPdcd19f3RBdKfu4beW5ktzJPx3L" +
              "2/U1/UfqlpP+P6/9Vqx6SJqZtuq/HfNsg3W4cKctMZhLs8lkVf7faihTFnQL" +
              "o2AS5bwfnbDAoje52dei4Des0NUj1ygU9anzwiSz6z6fg3cXHR+avaQ+Lqte" +
              "yAVmriE8F8hLyqo2V2itIZ3q8UGGw/pt9cr6ZUtCASXjgvRGjcEGaTFO1flP" +
              "6blEuHfQ12FssF/CqzJZ4T+C8cJI4QBWC8UE0M/Thoo6i8EbHoEavhwxPNBY" +
              "IQh84OmuQ7YrC1CEWjTh2QYLg4dsDmJQvIwwALPlwYAhu4A4FIxDAnC6Cwwc" +
              "MtvzUTgbGodoJDBQyAi9EIX6BxoVrDXAYCIzLVreFBnAiOIYKGQijUah9lEA" +
              "rEiMwUIGQjaKlUQFgiRaAluVi8L8E4FBZkkCIA4KJKEBwTIlhi9kuItFcWqC" +
              "cR5nSwKqhCjaf4LR5sZDDJXIwCZGcWV0AtxnYiIB8YtQRSYIFJlNgljnASIx" +
              "paPQAgYx9NNESEB9EqoEi4lTAhHEMLQjY8tiFLicEBgRyAhIT0RVyA8PUuGZ" +
              "MIQhG5lX0KvZPi/0cSgKWW9YxDwzgq0XmUxSUdDSeaHIGERAM/r8+gKBfxp2" +
              "CGDiURgaay4GGXoIAJNRwJdxgYjwE/JZ9A4u+UkICpl7F5f7JAw94VJpMz8T" +
              "4C8qjMmdueOB/wFP4UXFXBYAAA==",
            0x2a5ba622,
            kotlin(
                """
            package test.pkg
            import android.os.Parcelable
            abstract class Parent : Parcelable
                """
              )
              .indented(),
            kotlin(
                """
                  package android.os
                  interface Parcelable
                  interface Parcel
                """
              )
              .indented(),
          ),
        )
        .type(LIBRARY)
        .name("project1")

    val androidApp =
      project(
          source(
              "src/main/$ANDROID_MANIFEST_XML",
              """
          <manifest xmlns:android="http://schemas.android.com/apk/res/android">

              <uses-permission android:name="android.permission.INTERNET"/>

              <application
                  android:allowBackup="false"
                  android:supportsRtl="true"
                  android:theme="@style/AppTheme">
                  <activity
                      android:name=".MainActivity"
                      android:exported="true">
                      <intent-filter>
                          <action android:name="android.intent.action.MAIN" />
                          <category android:name="android.intent.category.LAUNCHER" />
                      </intent-filter>
                  </activity>
              </application>
          </manifest>
        """,
            )
            .indented(),
          xml(
              "src/main/res/values/styles.xml",
              """
            <resources>
                <style name="AppTheme" parent="android:Theme.Material.NoActionBar"/>
            </resources>
          """,
            )
            .indented(),
          kt(
              "src/main/java/pkg/android/MainActivity.kt",
              """
            package pkg.android

            import android.os.Bundle
            import androidx.activity.ComponentActivity
            import androidx.activity.compose.setContent
            import androidx.compose.foundation.layout.fillMaxSize
            import androidx.compose.material.*
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.tooling.preview.Preview
            import com.example.kmptest.Greeting
            import androidx.compose.runtime.*

            class MainActivity : ComponentActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContent {
                        MyApplicationTheme {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colors.background
                            ) {
                                var text by remember { mutableStateOf("Loading") }
                                LaunchedEffect(true) {
                                    text = try {
                                        Greeting().greet()
                                    } catch (e: Exception) {
                                        e.localizedMessage ?: "error"
                                    }
                                }
                                GreetingView(text)
                            }
                        }
                    }
                }
            }

            @Composable
            fun GreetingView(text: String) {
                Text(text = text)
            }

            @Preview
            @Composable
            fun DefaultPreview() {
                MyApplicationTheme {
                    GreetingView("Hello, Android!")
                }
            }
          """,
            )
            .indented(),
          kt(
              "src/main/java/pkg/android/MyApplicationTheme.kt",
              """
            package pkg.android

            import androidx.compose.foundation.isSystemInDarkTheme
            import androidx.compose.foundation.shape.RoundedCornerShape
            import androidx.compose.material.MaterialTheme
            import androidx.compose.material.Shapes
            import androidx.compose.material.Typography
            import androidx.compose.material.darkColors
            import androidx.compose.material.lightColors
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.graphics.Color
            import androidx.compose.ui.text.TextStyle
            import androidx.compose.ui.text.font.FontFamily
            import androidx.compose.ui.text.font.FontWeight
            import androidx.compose.ui.unit.dp
            import androidx.compose.ui.unit.sp

            @Composable
            fun MyApplicationTheme(
                darkTheme: Boolean = isSystemInDarkTheme(),
                content: @Composable () -> Unit
            ) {
                val colors = if (darkTheme) {
                    darkColors(
                        primary = Color(0xFFBB86FC),
                        primaryVariant = Color(0xFF3700B3),
                        secondary = Color(0xFF03DAC5)
                    )
                } else {
                    lightColors(
                        primary = Color(0xFF6200EE),
                        primaryVariant = Color(0xFF3700B3),
                        secondary = Color(0xFF03DAC5)
                    )
                }
                val typography = Typography(
                    body1 = TextStyle(
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Normal,
                        fontSize = 16.sp
                    )
                )
                val shapes = Shapes(
                    small = RoundedCornerShape(4.dp),
                    medium = RoundedCornerShape(4.dp),
                    large = RoundedCornerShape(0.dp)
                )

                MaterialTheme(
                    colors = colors,
                    typography = typography,
                    shapes = shapes,
                    content = content
                )
            }
          """,
            )
            .indented(),
          kt(
            "src/main/java/pkg/android/expect.kt",
            """
            package pkg
            expect fun getPlatform() : Platform
            """
              .trimIndent(),
          ),
          kt(
            "src/main/java/pkg/android/actual.kt",
            """
            package pkg
            actual fun getPlatform() = TODO()
            """
              .trimIndent(),
          ),
        )
        .name("project2")
        .dependsOn(shared, DependencyKind.DependsOn)

    val iosApp =
      project(
          source(
            "iosApp/ContentView.swift",
            """
            import SwiftUI
            import shared

            struct ContentView: View {
                @ObservedObject private(set) var viewModel: ViewModel

                var body: some View {
                    Text(viewModel.text)
                }
            }

            extension ContentView {
                class ViewModel: ObservableObject {
                    @Published var text = "Loading..."
                    init() {
                        Greeting().greet { greeting, error in
                                    DispatchQueue.main.async {
                                        if let greeting = greeting {
                                            self.text = greeting
                                        } else {
                                            self.text = error?.localizedDescription ?? "error"
                                        }
                                    }
                                }
                    }
                }
            }
            """
              .trimIndent(),
          ),
          source(
            "iosApp/iOSApp.swift",
            """
            import SwiftUI

            @main
            struct iOSApp: App {
              var body: some Scene {
                WindowGroup {
                        ContentView(viewModel: ContentView.ViewModel())
                }
              }
            }
            """
              .trimIndent(),
          ),
        )
        .name("project3")
        .dependsOn(shared, DependencyKind.DependsOn)

    val root = temp.newFolder().canonicalFile.absoluteFile
    @Language("XML")
    val descriptor =
      """
        <project>
          <sdk dir='${TestUtils.getSdk()}'/>
          <root dir="$root" />

          <module name="project2" android="true" library="false" compile-sdk-version='18' kotlinPlatforms='JVM [1.8]'>
            <manifest file="project2/src/main/AndroidManifest.xml" />
            <resource file="project2/src/main/res/values/styles.xml" />
            <src file="project2/src/main/java/pkg/android/MainActivity.kt" />
            <src file="project2/src/main/java/pkg/android/MyApplicationTheme.kt" />
            <src file="project2/src/main/java/pkg/android/expect.kt" />
            <src file="project2/src/main/java/pkg/android/actual.kt" />
            <dep module="project1" kind="dependsOn" />
          </module>

          <module name="project3" android="false" library="false" kotlinPlatforms='Native [general]'>
            <src file="project3/iosApp/ContentView.swift" />
            <src file="project3/iosApp/iOSApp.swift" />
            <dep module="project1" kind="dependsOn"/>
          </module>

          <module name="project1" android="false" kotlinPlatforms='Native [general]/JVM [1.8]'>
            <src file="project1/src/commonMain/kotlin/pkg/Platform.kt" />
            <src file="project1/src/androidMain/kotlin/pkg/Platform.kt" />
            <src file="project1/src/iosMain/kotlin/pkg/Platform.kt" />
            <klib file="project1/libs/SomeKlib.klib" />
          </module>
        </project>
      """
        .trimIndent()

    lint().projects(shared, androidApp, iosApp).createProjects(root)
    Files.asCharSink(File(root, "project.xml"), Charsets.UTF_8).write(descriptor)

    MainTest.checkDriver(
      """
        src/main/res/values/styles.xml:2: Error: android:Theme.Material.NoActionBar requires API level 21 (current min is 1) [NewApi]
    <style name="AppTheme" parent="android:Theme.Material.NoActionBar"/>
                           ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
src/main/AndroidManifest.xml:6: Warning: Attribute allowBackup is only used in API level 4 and higher (current min is 1) [UnusedAttribute]
        android:allowBackup="false"
        ~~~~~~~~~~~~~~~~~~~~~~~~~~~
src/main/AndroidManifest.xml:1: Warning: Should set android:versionCode to specify the application version [MissingVersion]
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
 ~~~~~~~~
src/main/AndroidManifest.xml:1: Warning: Should set android:versionName to specify the application version [MissingVersion]
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
 ~~~~~~~~
src/main/AndroidManifest.xml:5: Warning: Should explicitly set android:icon, there is no default [MissingApplicationIcon]
    <application
     ~~~~~~~~~~~
src/main/AndroidManifest.xml:7: Warning: You must set android:targetSdkVersion to at least 17 when enabling RTL support [RtlEnabled]
        android:supportsRtl="true"
                             ~~~~
1 error, 5 warnings
      """,
      "",
      ERRNO_SUCCESS,
      arrayOf("--project", File(root, "project.xml").path),
      { it.replace(root.canonicalPath, "ROOT").replace(root.path, "ROOT").dos2unix() },
      { _, _, _, _ -> },
    )
  }

  @Test
  fun testKMPProjectK2_common_klib() {
    assumeTrue(useFirUast())
    val shared =
      project(
          // TODO
          //   Here we leave `androidMain/.../Platform.kt` out of the klib because
          // `kotlinc-native`
          //   gives an error on conflicting overloads of `actual fun getPlatform()`, and it's
          //   strange for the Android-specific file to be passed to `kotlinc-native`.
          //   If we have neither, `kotlinc-native` will complain that there's no
          // corresponding
          //   `actual` to the `expect`.
          klib(
            "build/common.klib",
            "" +
              "H4sIAAAAAAAA/6WXDzzT+R/Hv/N3bJj8G04ZQ8LMn1ZCNyvKQqjLuC4282dh" +
              "Y7ZQd0TKn/zthIQjNS6XSu6a4nR+kjJ/kkJdvy6kf64/SgrxG/e7u+/3bIvf" +
              "77vHY4/H/ryfe79f3/dn79fbc4u0jCLwx4UBwBccUAXoQcFUbjjH8ovtSoA8" +
              "+ENPsWEIUBiDLTpSHYDD4QsitaGR0YwQJpXDZQdF48KYnAWcwICAgOCAAD6y" +
              "5TVxWiv1w/5C6VTnnFBUsDyNUFWIQiEAK7U8FV59Px+tnYzWrk8m2qoZXmjp" +
              "s7P4eDnEgt+Qq4q9ZtDSpngkUJVG25B74HRIAf8+4QN5trrUvRqdSalH22HR" +
              "dZS1FBlKuVx0lMJ+BjPteVjp9K/rP34kh5muL3PIbsly3spzL5l9uztZSrrX" +
              "IF628Gi4Z+WBYlMklyC4XtWpmWx3+c21ZxFNluaNYbK2pV77nWmsl5dK8+Ms" +
              "PeST8moVrGrN8MQM+dr6nIyNylbUOtQGZQuq7c+oAYwJHGHH/9ZKSrmqAKHj" +
              "Fpmgn6SwOwmwT6+s91K3IZWYAEoJmI3vpYVqysOlUkJeyMEA4HMpSepq/kNd" +
              "DpvBDBEtbWbug93NePRVQzUTTTkD5J7HiFElMob3vYyRhave6OnYm1wn91Pt" +
              "HaXN134NGw07nuHm038Vk5I9/aFELblbj/QEwXEKtU3iZk0OqHNUiiiZjXHP" +
              "V5xRIaqUmVw+62jz+YrT3G35Lk9X8RSGbJWP7jBe1ztS9VbZ/M53j4f+3XC7" +
              "w3SnkZdxT0b69I8Uai3166+wGcwhwYsq8lA4fGagJOM3dsO9katdUW7wo9Ya" +
              "5XE+K1TPUm9qDFlfK+4meZIGEBqa97IHXIrirIovrdPP2veg9ebUtoHyumB0" +
              "RZeZu7S6Y48SQh221dzcA9892R92n9pR4/fd3TiAb37UpyXha6IW7dx0qd8p" +
              "9tNnu2Zsxs+O6bW6R77c1gSbE/phORWmLhQ6RaLQaKjQ9CAaN8SVGcwSSk0X" +
              "18Wp8jRN/SwYzUVfOeYNZzzKCw4fU4lUUkzLiGFz3r17G52dAn9sNMLz0xjG" +
              "5p98pPVoVd52TSBu4OleGD63VjAoOCkoFhwhVyedENS2HNl+8EmdoKGlmCIH" +
              "B3xVSf2KKJjVevsmrInNVkv+yZU2lnrZH+Y7J+anddf2CrPokngu1aEFceIi" +
              "JR/Jc1I0jZUdii3hREFWxo8UmX5sYR1F9i5FX31F8rIDnt7L3JCob/upl5ya" +
              "9S0iMx0CVmOP4aTK6djC+Zy2EPw+cxLCqiTmpAfNicHeGBQYTmVTOQwWM1qk" +
              "0kE5W8nSVuqt/ta3ilq9HrT49ZQZCU4cHrBjtvqmrTRgajgmar2S2jaLeeKy" +
              "knlf/RdD/iuDxsQvVTy/YRin6rzXfNk+9ea19H440HoS0aOblpiw+dIOQ2P7" +
              "E5k7E170E6wvmJ/i96XWyDl07uOZHXVrSZ0qvVcUWJ08XXa34PCgU87U3YsF" +
              "Vjj0yZvVMvadnvxy33zXLbvORLomhLa/Nj9+9uUkxs9/LOlq3oE1x+64Vvq/" +
              "LWoo8yiKvdVbmhJ7hOmpxD/O+uI47hBiorC/AF0huJForxPY7rthY8L295o6" +
              "q9Cz0WNZdaSinDu97kTr+1NONDx3/HPssCx2020q0fr5vUr/zcGw692GTqQx" +
              "Zrilvld8aNbG135q+bQveDVcon/XvTx6c/sT3Tn9LaSmBFhhk3fDltATwYzw" +
              "+Z4IFtcTr+QHAf13QzQ6iv24ts/yukX3GlsbAgGPJ9hYr8YT6s7hV9ta1/Za" +
              "4sh3tnZ2dXR0vXEXCG51dVpeh3nLDjydfOH8/fdGx+Ke7h0wIpkjHWph3hlI" +
              "/SGHNhucvsl5rEXTSi36Z2bS2SeOybY5GMvFY3xwutNywSNaanh3MxwsVGe+" +
              "tTAKSeRUYU4fJbaWBrQ0GovOmK+NtqC2iCx3srQT6mD1vpl0MrKE0MIj/uKC" +
              "SU0y1i3ACGAOh00Kj/F7dIeBXcSL0bIq+DVtBJNXRoYncg5NpMc3rFpvQE4N" +
              "VTrVbTI+ax8q4zjG1A5hp5cbmu7Q7qs3CWlmmg59szZv9Dalc6qqO8MsJUnp" +
              "ZUGDUhRGBb3XrE9XrX5sZievS+11w/JnacOZru8jBleGS+8IUMiYLJgJQL5D" +
              "dzvGPIKdMput4Bh5omacnPI6f78FbGqq4d+W3lTd9NkLdIr8pmpKohF+psq9" +
              "IVe7ydal4klrcRT3YePmQETNGUOjH8oOrlv3YE62wlYt3pCw3C0SOwIFki2C" +
              "ymQEB0Uv/Idwzzyv3KyPIk5Mob3dKnBIZWWcB8/Mbti24IrXw9DJD3voe9vb" +
              "95VHe+eP9CVPmq6+/Pj3ccqL7oMJJQ0BxhM4O61EaTOT0/EVd/mpF0Z2e2ga" +
              "nMGer+dZRQFIB7SWTWLZmMuu8/Gux7eYfzMxFXfILV1VQz6BoBm7023Tbypz" +
              "pVh/NX74a2EePECShVkGKkVoP1hcdmBQ9FI8kCoIEM5ghtGpHOpSnJCWqPgI" +
              "Fp0bHrSAMjR4znX+xFy4QDtHw7V5xNCA+W531j34u4bwC2oSU9UT9VOR1MAw" +
              "akiQf2RYyFLSNv4Uy2ruWXiUIhZW0TM86LHVQqHN1dy13fLGo7lCHg/uj66n" +
              "mjeOLsMsxxnDXHAEZTmYsYKcKdV0D0wOcUh+p+A7tC+/EIlmRcLskGevuPxR" +
              "OG84bvlmIdPn/0sWLzZZx25HZjMRJfvbR22WMev4wRtk2ZoqgxqiuoK36sND" +
              "+c367QE5Pzr3YJRzDYd39uZtzxuV4qE7bo1W+pjHl8XcVnRgpjcXjCvy7k5y" +
              "fTdkP/N/xWx81R5xAZZw8fpPbxvHzvnfmv5AGqdtuzL8c5rzT42b+kwr485a" +
              "PHfu0yPZwf7VfH178g+kHR6VE1+O9VtePPNr2Kp/b7zzkpx7Q8XqQEVa8YpQ" +
              "Voevda9nSjnCjZCKVTg5tDanqUstNS5gQjPGlSqQbZtsyMgn+ebNvsVOOmWh" +
              "Ku/NFpcHkXzmpAvc1zZ5R1ihBUxSzywXJR2bxeL4/1e/pTQN9pMwvL/olqGh" +
              "aDm02CtX36cfhs3f+KTDqEfKwk+QEjse/EfFobJDgjhLOts6IsKFeXNj/WMJ" +
              "tksBGUoEMZiB4Vx6EH0pRIxEonCZYuwRc2v+F14YiyN8IYoHk1IExO2UCgD0" +
              "+nvDlP0DIykcAQlfAwqf3zRBBHXg724D70faEEIUlPDPjVMMD7wGaEJ4XTBA" +
              "7I4lBga24GgIbK00IGGPEIMDu2d1CE5BBhDj4sWgwEZQD4IqgaJEmW8xTLAD" +
              "g6anJweIMZRiUGBXogFBvYeiwAZODAtsC1AQliUcEOFqFtGqyyCYSRAG7CgW" +
              "AVKFgOwVAFHOQkxdYAugBeHsE8X502EsIitoQ4yIokFNhJgMwbPaGMK0VvwE" +
              "E2QmxMDB0wwKf/ApOF4kXJwayyFwHaQI+D/Go5iMwRMMC4GGfBL655hcRL7Q" +
              "Hu8Hof8eiIvA6EAwWkoLMeDBuAigIQQYIBEIGpCLIGMg5FqJ5L8G5ZK5UsqS" +
              "uH8NzL+4snLzwgkfpsJ3vOfuPPAfJ0MmAZEVAAA=",
            0x411ab151,
            kt(
              "src/commonMain/kotlin/pkg/Platform.kt",
              """
              package pkg
              interface Platform {
                  val name: String
              }
              expect fun getPlatform(): Platform
              """
                .trimIndent(),
            ),
            kt(
              "src/commonMain/kotlin/pkg/Greeting.kt",
              """
              package pkg
              class Greeting {
                  private val platform: Platform = getPlatform()
              }
              """
                .trimIndent(),
            ),
            kt(
              "src/iosMain/kotlin/pkg/Platform.kt",
              """
              package pkg
              class IOSPlatform: Platform {
                  override val name: String = "iOS platform name"
              }
              actual fun getPlatform(): Platform = IOSPlatform()
              """
                .trimIndent(),
            ),
          ),
          kt(
            "src/androidMain/kotlin/pkg/Platform.kt",
            """
            package pkg
            class AndroidPlatform : Platform {
                override val name: String = "Android 34"
            }
            actual fun getPlatform(): Platform = AndroidPlatform()
            """
              .trimIndent(),
          ),
        )
        .type(LIBRARY)
        .name("shared")

    val androidApp =
      project(
          source(
              "src/main/$ANDROID_MANIFEST_XML",
              """
          <manifest xmlns:android="http://schemas.android.com/apk/res/android">

              <uses-permission android:name="android.permission.INTERNET"/>

              <application
                  android:allowBackup="false"
                  android:supportsRtl="true"
                  android:theme="@style/AppTheme">
                  <activity
                      android:name=".MainActivity"
                      android:exported="true">
                      <intent-filter>
                          <action android:name="android.intent.action.MAIN" />
                          <category android:name="android.intent.category.LAUNCHER" />
                      </intent-filter>
                  </activity>
              </application>
          </manifest>
        """,
            )
            .indented(),
          xml(
              "src/main/res/values/styles.xml",
              """
            <resources>
                <style name="AppTheme" parent="android:Theme.Material.NoActionBar"/>
            </resources>
          """,
            )
            .indented(),
          kt(
              "src/main/java/pkg/android/MainActivity.kt",
              """
            package pkg.android

            import android.os.Bundle
            import androidx.activity.ComponentActivity
            import androidx.activity.compose.setContent
            import androidx.compose.foundation.layout.fillMaxSize
            import androidx.compose.material.*
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.tooling.preview.Preview
            import com.example.kmptest.Greeting
            import androidx.compose.runtime.*

            class MainActivity : ComponentActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContent {
                        MyApplicationTheme {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colors.background
                            ) {
                                var text by remember { mutableStateOf("Loading") }
                                LaunchedEffect(true) {
                                    text = try {
                                        Greeting().greet()
                                    } catch (e: Exception) {
                                        e.localizedMessage ?: "error"
                                    }
                                }
                                GreetingView(text)
                            }
                        }
                    }
                }
            }

            @Composable
            fun GreetingView(text: String) {
                Text(text = text)
            }

            @Preview
            @Composable
            fun DefaultPreview() {
                MyApplicationTheme {
                    GreetingView("Hello, Android!")
                }
            }
          """,
            )
            .indented(),
          kt(
              "src/main/java/pkg/android/MyApplicationTheme.kt",
              """
            package pkg.android

            import androidx.compose.foundation.isSystemInDarkTheme
            import androidx.compose.foundation.shape.RoundedCornerShape
            import androidx.compose.material.MaterialTheme
            import androidx.compose.material.Shapes
            import androidx.compose.material.Typography
            import androidx.compose.material.darkColors
            import androidx.compose.material.lightColors
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.graphics.Color
            import androidx.compose.ui.text.TextStyle
            import androidx.compose.ui.text.font.FontFamily
            import androidx.compose.ui.text.font.FontWeight
            import androidx.compose.ui.unit.dp
            import androidx.compose.ui.unit.sp

            @Composable
            fun MyApplicationTheme(
                darkTheme: Boolean = isSystemInDarkTheme(),
                content: @Composable () -> Unit
            ) {
                val colors = if (darkTheme) {
                    darkColors(
                        primary = Color(0xFFBB86FC),
                        primaryVariant = Color(0xFF3700B3),
                        secondary = Color(0xFF03DAC5)
                    )
                } else {
                    lightColors(
                        primary = Color(0xFF6200EE),
                        primaryVariant = Color(0xFF3700B3),
                        secondary = Color(0xFF03DAC5)
                    )
                }
                val typography = Typography(
                    body1 = TextStyle(
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Normal,
                        fontSize = 16.sp
                    )
                )
                val shapes = Shapes(
                    small = RoundedCornerShape(4.dp),
                    medium = RoundedCornerShape(4.dp),
                    large = RoundedCornerShape(0.dp)
                )

                MaterialTheme(
                    colors = colors,
                    typography = typography,
                    shapes = shapes,
                    content = content
                )
            }
          """,
            )
            .indented(),
          kt(
            "src/main/java/pkg/android/expect.kt",
            """
            package pkg
            expect fun getPlatform() : Platform
            """
              .trimIndent(),
          ),
          kt(
            "src/main/java/pkg/android/actual.kt",
            """
            package pkg
            actual fun getPlatform() = TODO()
            """
              .trimIndent(),
          ),
        )
        .name("androidApp")
        .dependsOn(shared, DependencyKind.DependsOn)

    val iosApp =
      project(
          source(
            "iosApp/ContentView.swift",
            """
            import SwiftUI
            import shared

            struct ContentView: View {
                @ObservedObject private(set) var viewModel: ViewModel

                var body: some View {
                    Text(viewModel.text)
                }
            }

            extension ContentView {
                class ViewModel: ObservableObject {
                    @Published var text = "Loading..."
                    init() {
                        Greeting().greet { greeting, error in
                                    DispatchQueue.main.async {
                                        if let greeting = greeting {
                                            self.text = greeting
                                        } else {
                                            self.text = error?.localizedDescription ?? "error"
                                        }
                                    }
                                }
                    }
                }
            }
            """
              .trimIndent(),
          ),
          source(
            "iosApp/iOSApp.swift",
            """
            import SwiftUI

            @main
            struct iOSApp: App {
              var body: some Scene {
                WindowGroup {
                        ContentView(viewModel: ContentView.ViewModel())
                }
              }
            }
            """
              .trimIndent(),
          ),
        )
        .name("iosApp")
        .dependsOn(shared, DependencyKind.DependsOn)

    val root = temp.newFolder().canonicalFile.absoluteFile
    @Language("XML")
    val descriptor =
      """
        <project>
          <sdk dir='${TestUtils.getSdk()}'/>
          <root dir="$root" />

          <module name="androidApp" android="true" library="false" compile-sdk-version='18'>
            <manifest file="androidApp/src/main/AndroidManifest.xml" />
            <resource file="androidApp/src/main/res/values/styles.xml" />
            <src file="androidApp/src/main/java/pkg/android/MainActivity.kt" />
            <src file="androidApp/src/main/java/pkg/android/MyApplicationTheme.kt" />
            <src file="androidApp/src/main/java/pkg/android/expect.kt" />
            <src file="androidApp/src/main/java/pkg/android/actual.kt" />
            <klib file="shared/build/common.klib" kind="dependsOn" />
          </module>

          <module name="iosApp" android="false" library="false">
            <src file="iosApp/iosApp/ContentView.swift" />
            <src file="iosApp/iosApp/iOSApp.swift" />
            <klib file="shared/build/common.klib" kind="dependsOn"/>
          </module>

          <module name="commonMain">
            <src file="shared/src/androidMain/kotlin/pkg/Platform.kt" />
          </module>
        </project>
      """
        .trimIndent()

    val projects = lint().projects(shared, androidApp, iosApp).createProjects(root)
    Files.asCharSink(File(root, "project.xml"), Charsets.UTF_8).write(descriptor)

    MainTest.checkDriver(
      """
        src/main/res/values/styles.xml:2: Error: android:Theme.Material.NoActionBar requires API level 21 (current min is 1) [NewApi]
    <style name="AppTheme" parent="android:Theme.Material.NoActionBar"/>
                           ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
src/main/AndroidManifest.xml:6: Warning: Attribute allowBackup is only used in API level 4 and higher (current min is 1) [UnusedAttribute]
        android:allowBackup="false"
        ~~~~~~~~~~~~~~~~~~~~~~~~~~~
src/main/AndroidManifest.xml:1: Warning: Should set android:versionCode to specify the application version [MissingVersion]
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
 ~~~~~~~~
src/main/AndroidManifest.xml:1: Warning: Should set android:versionName to specify the application version [MissingVersion]
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
 ~~~~~~~~
src/main/AndroidManifest.xml:5: Warning: Should explicitly set android:icon, there is no default [MissingApplicationIcon]
    <application
     ~~~~~~~~~~~
src/main/AndroidManifest.xml:7: Warning: You must set android:targetSdkVersion to at least 17 when enabling RTL support [RtlEnabled]
        android:supportsRtl="true"
                             ~~~~
1 error, 5 warnings
      """,
      "",
      ERRNO_SUCCESS,
      arrayOf("--project", File(root, "project.xml").path),
      { it.replace(root.canonicalPath, "ROOT").replace(root.path, "ROOT").dos2unix() },
      { _, _, _, _ -> },
    )
  }

  /** Copied from [testKMPProjectK2], with klib removed and `iosApp/Hello.kt` added */
  @Test
  fun testLightClassSupportForNonJvm() {
    assumeTrue(useFirUast())

    val shared =
      project(
          kt(
            "src/commonMain/kotlin/pkg/Platform.kt",
            """
            package pkg
            interface Platform {
                val name: String
            }
            interface Hello {
                fun hello(): String
            }
            expect fun getPlatform(): Platform
            object CommonMainHello : Hello {
                val commonMain = "commonMain"
                override fun hello() = "Hello " + commonMain + "!"
            }
            """
              .trimIndent(),
          ),
          kt(
            "src/commonMain/kotlin/pkg/Greeting.kt",
            """
            package pkg
            class Greeting {
                private val platform: Platform = getPlatform()
            }
            """
              .trimIndent(),
          ),
          kt(
            "src/androidMain/kotlin/pkg/Platform.kt",
            """
            package pkg
            class AndroidPlatform : Platform {
                override val name: String = AndroidMainHello().hello()
            }
            class AndroidMainHello: Hello {
                val androidMain = "androidMain"
                override fun hello() = "Hello " + androidMain + "!"
            }
            actual fun getPlatform(): Platform = AndroidPlatform()
            """
              .trimIndent(),
          ),
          kt(
            "src/iosMain/kotlin/pkg/Platform.kt",
            """
            package pkg

            class IOSPlatform: Platform {
                override val name: String = IosMainHello().hello()
            }
            class IosMainHello: Hello {
                val iosMain = "iosMain"
                override fun hello() = "Hello " + iosMain + "!"
            }
            actual fun getPlatform(): Platform = IOSPlatform()
            """
              .trimIndent(),
          ),
        )
        .type(LIBRARY)
        .name("shared")

    val androidApp =
      project(
          source(
              "src/main/$ANDROID_MANIFEST_XML",
              """
          <manifest xmlns:android="http://schemas.android.com/apk/res/android">

              <uses-permission android:name="android.permission.INTERNET"/>

              <application
                  android:allowBackup="false"
                  android:supportsRtl="true"
                  android:theme="@style/AppTheme">
                  <activity
                      android:name=".MainActivity"
                      android:exported="true">
                      <intent-filter>
                          <action android:name="android.intent.action.MAIN" />
                          <category android:name="android.intent.category.LAUNCHER" />
                      </intent-filter>
                  </activity>
              </application>
          </manifest>
        """,
            )
            .indented(),
          xml(
              "src/main/res/values/styles.xml",
              """
            <resources>
                <style name="AppTheme" parent="android:Theme.Material.NoActionBar"/>
            </resources>
          """,
            )
            .indented(),
          kt(
              "src/main/java/pkg/android/MainActivity.kt",
              """
            package pkg.android

            @Composable
            fun GreetingView(text: String) {
                Text(text = text)
            }

            val androidAppHello = object : pkg.Hello {
                val androidApp = "androidApp"
                override fun hello() = "Hello " + androidApp + "!"
            }

            @Preview
            @Composable
            fun DefaultPreview() {
                MyApplicationTheme {
                    GreetingView(androidAppHello.hello())
                }
            }
          """,
            )
            .indented(),
          kt(
            "src/main/java/pkg/android/expect.kt",
            """
            package pkg
            expect fun getPlatform() : Platform
            """
              .trimIndent(),
          ),
          kt(
            "src/main/java/pkg/android/actual.kt",
            """
            package pkg
            actual fun getPlatform() = TODO()
            """
              .trimIndent(),
          ),
        )
        .name("androidApp")
        .dependsOn(shared, DependencyKind.DependsOn)

    val iosApp =
      project(
          source(
            "iosApp/ContentView.swift",
            """
            import SwiftUI
            import shared

            struct ContentView: View {
                @ObservedObject private(set) var viewModel: ViewModel

                var body: some View {
                    Text(viewModel.text)
                }
            }

            extension ContentView {
                class ViewModel: ObservableObject {
                    @Published var text = "Loading..."
                    init() {
                        Greeting().greet { greeting, error in
                                    DispatchQueue.main.async {
                                        if let greeting = greeting {
                                            self.text = greeting
                                        } else {
                                            self.text = error?.localizedDescription ?? "error"
                                        }
                                    }
                                }
                    }
                }
            }
            """
              .trimIndent(),
          ),
          source(
            "iosApp/iOSApp.swift",
            """
            import SwiftUI

            @main
            struct iOSApp: App {
              var body: some Scene {
                WindowGroup {
                        ContentView(viewModel: ContentView.ViewModel())
                }
              }
            }
            """
              .trimIndent(),
          ),
          kt(
            "src/IosHello.kt",
            """
            class IosHello: pkg.Hello {
                val iosApp = "iosApp"
                fun hello(str: String) = "Hello " + iosApp + "!"
            }
            """
              .trimIndent(),
          ),
        )
        .name("iosApp")
        .dependsOn(shared, DependencyKind.DependsOn)

    val root = temp.newFolder().canonicalFile.absoluteFile
    @Language("XML")
    val descriptor =
      """
        <project>
          <sdk dir='${TestUtils.getSdk()}'/>
          <root dir="$root" />

          <module name="androidApp" android="true" library="false" compile-sdk-version='18'>
            <manifest file="androidApp/src/main/AndroidManifest.xml" />
            <resource file="androidApp/src/main/res/values/styles.xml" />
            <src file="androidApp/src/main/java/pkg/android/MainActivity.kt" />
            <src file="androidApp/src/main/java/pkg/android/expect.kt" />
            <src file="androidApp/src/main/java/pkg/android/actual.kt" />
            <dep module="commonMain" kind="dependsOn" />
          </module>

          <module name="iosApp" android="false" library="false">
            <src file="iosApp/iosApp/ContentView.swift" />
            <src file="iosApp/iosApp/iOSApp.swift" />
            <src file="iosApp/src/IosHello.kt" />
            <dep module="commonMain" kind="dependsOn"/>
          </module>

          <module name="commonMain" android="false">
            <src file="shared/src/commonMain/kotlin/pkg/Platform.kt" />
            <src file="shared/src/androidMain/kotlin/pkg/Platform.kt" />
            <src file="shared/src/iosMain/kotlin/pkg/Platform.kt" />
          </module>
        </project>
      """
        .trimIndent()

    val task = lint().issues(HelloDetector.ISSUE).projects(shared, androidApp, iosApp).allowMissingSdk()
    val projects = task.createProjects(root)
    Files.asCharSink(File(root, "project.xml"), Charsets.UTF_8).write(descriptor)

    MainTest.checkDriver(
      """
        src/main/res/values/styles.xml:2: Error: android:Theme.Material.NoActionBar requires API level 21 (current min is 1) [NewApi]
    <style name="AppTheme" parent="android:Theme.Material.NoActionBar"/>
                           ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
src/main/AndroidManifest.xml:6: Warning: Attribute allowBackup is only used in API level 4 and higher (current min is 1) [UnusedAttribute]
        android:allowBackup="false"
        ~~~~~~~~~~~~~~~~~~~~~~~~~~~
src/main/AndroidManifest.xml:1: Warning: Should set android:versionCode to specify the application version [MissingVersion]
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
 ~~~~~~~~
src/main/AndroidManifest.xml:1: Warning: Should set android:versionName to specify the application version [MissingVersion]
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
 ~~~~~~~~
src/main/AndroidManifest.xml:5: Warning: Should explicitly set android:icon, there is no default [MissingApplicationIcon]
    <application
     ~~~~~~~~~~~
src/main/AndroidManifest.xml:7: Warning: You must set android:targetSdkVersion to at least 17 when enabling RTL support [RtlEnabled]
        android:supportsRtl="true"
                             ~~~~
1 error, 5 warnings
      """,
      "",
      ERRNO_SUCCESS,
      arrayOf("--project", File(root, "project.xml").path),
      { it.replace(root.canonicalPath, "ROOT").replace(root.path, "ROOT").dos2unix() },
      { _, _, _, _ -> },
    )

    task
      .skipTestModes(TestMode.PARTIAL) // TODO no iosApp with `TestMode.PARTIAL`
      .allowDuplicates()
      .run()
      .expect(
        """
        ../iosApp/src/IosHello.kt:1: Warning: This class (with fields [iosApp] and methods [getIosApp, hello, IosHello]) shouldn't extend pkg.Hello [HelloDetectorIssue]
        class IosHello: pkg.Hello {
        ^
        src/main/java/pkg/android/MainActivity.kt:8: Warning: This class (with fields [androidApp] and methods [getAndroidApp, hello, ]) shouldn't extend pkg.Hello [HelloDetectorIssue]
        val androidAppHello = object : pkg.Hello {
                              ^
        ../shared/src/androidMain/kotlin/pkg/Platform.kt:5: Warning: This class (with fields [androidMain] and methods [getAndroidMain, hello, AndroidMainHello]) shouldn't extend pkg.Hello [HelloDetectorIssue]
        class AndroidMainHello: Hello {
        ^
        ../shared/src/iosMain/kotlin/pkg/Platform.kt:6: Warning: This class (with fields [iosMain] and methods [getIosMain, hello, IosMainHello]) shouldn't extend pkg.Hello [HelloDetectorIssue]
        class IosMainHello: Hello {
        ^
        ../shared/src/commonMain/kotlin/pkg/Platform.kt:9: Warning: This class (with fields [commonMain, INSTANCE] and methods [getCommonMain, hello, CommonMainHello]) shouldn't extend pkg.Hello [HelloDetectorIssue]
        object CommonMainHello : Hello {
        ^
        0 errors, 5 warnings
        """
          .trimIndent()
      )
  }

  @After
  fun tearDown() {
    UastEnvironment.disposeApplicationEnvironment()
  }

  @OptIn(KaImplementationDetail::class)
  private fun Project.checkAnalysisApiServices() {
    val cacheCleaner = KaFirCacheCleaner.getInstance(this)
    // Can't test type since all implementations are `private`
    assertEquals("KaFirNoOpCacheCleaner", cacheCleaner::class.simpleName)
  }

  companion object {
    @ClassRule @JvmField var temp = TemporaryFolder()

    fun project(vararg files: TestFile): ProjectDescription = ProjectDescription(*files)
  }

  class HelloDetector : Detector(), SourceCodeScanner {
    override fun applicableSuperClasses() = listOf(commonHello)

    override fun visitClass(context: JavaContext, declaration: UClass) {
      if (declaration.qualifiedName != commonHello) {
        val flds = declaration.fields.joinToString { (it.javaPsi as? PsiField)?.name ?: "??" }
        val mthds = declaration.methods.joinToString { it.javaPsi.name }
        val msg = "This class (with fields [$flds] and methods [$mthds]) shouldn't extend `$commonHello`"
        context.report(ISSUE, declaration, context.getLocation(declaration.javaPsi), msg)
      }
    }

    companion object {
      const val commonHello = "pkg.Hello"
      val ISSUE =
        Issue.create(
          id = "HelloDetectorIssue",
          briefDescription = "Not applicable",
          explanation = "Not applicable",
          category = Category.CORRECTNESS,
          priority = 10,
          severity = Severity.WARNING,
          implementation = Implementation(HelloDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
  }
}
