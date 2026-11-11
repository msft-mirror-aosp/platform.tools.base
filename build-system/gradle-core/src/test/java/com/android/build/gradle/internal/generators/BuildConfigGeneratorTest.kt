/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.build.gradle.internal.generators

import com.android.build.api.variant.BuildConfigField
import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.base.Charsets
import com.google.common.io.Files
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.StandardLocation
import javax.tools.ToolProvider
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuildConfigGeneratorTest {
  @get:Rule var mTemporaryFolder = TemporaryFolder()

  @Test
  fun testFalse() {
    val tempDir = mTemporaryFolder.newFolder()
    val buildConfigData =
      BuildConfigData.Builder().setOutputPath(tempDir.toPath()).setNamespace("my.app.pkg").addBooleanField("DEBUG", false).build()
    val generator = BuildConfigGenerator(buildConfigData)
    generator.generate()
    val file = generator.generatedFilePath
    Assert.assertTrue(file.exists())
    val actual = Files.toString(file, Charsets.UTF_8)
    Assert.assertEquals(
      """
      /*
       * Automatically generated file. DO NOT MODIFY
       */
      package my.app.pkg;

      public final class BuildConfig {
        public static final boolean DEBUG = false;
      }
      """
        .trimIndent(),
      actual.trim(),
    )
  }

  @Test
  fun testTrue() {
    val tempDir = mTemporaryFolder.newFolder()
    val buildConfigData =
      BuildConfigData.Builder().setOutputPath(tempDir.toPath()).setNamespace("my.app.pkg").addBooleanField("DEBUG", true).build()
    val generator = BuildConfigGenerator(buildConfigData)
    generator.generate()
    val file = generator.generatedFilePath
    Assert.assertTrue(file.exists())
    val actual = Files.toString(file, Charsets.UTF_8)
    Assert.assertEquals(
      """
      /*
       * Automatically generated file. DO NOT MODIFY
       */
      package my.app.pkg;

      public final class BuildConfig {
        public static final boolean DEBUG = Boolean.parseBoolean("true");
      }
      """
        .trimIndent(),
      actual.trim(),
    )
  }

  @Test
  fun testDebugTrueWithStringValue() {
    val tempDir = mTemporaryFolder.newFolder()
    val buildConfigData =
      BuildConfigData.Builder()
        .setOutputPath(tempDir.toPath())
        .setNamespace("my.app.pkg")
        .addItem("DEBUG", BuildConfigField("boolean", "true", null))
        .build()
    val generator = BuildConfigGenerator(buildConfigData)
    generator.generate()
    val file = generator.generatedFilePath
    assertThat(file).exists()
    val actual = Files.asCharSource(file, Charsets.UTF_8).read().trim()
    assertThat(actual)
      .isEqualTo(
        """
        /*
         * Automatically generated file. DO NOT MODIFY
         */
        package my.app.pkg;

        public final class BuildConfig {
          public static final boolean DEBUG = Boolean.parseBoolean("true");
        }
        """
          .trimIndent()
      )
  }

  @Test
  fun testLong() {
    val tempDir = mTemporaryFolder.newFolder()
    val buildConfigData =
      BuildConfigData.Builder().setOutputPath(tempDir.toPath()).setNamespace("my.app.pkg").addLongField("TIME_STAMP", 12343434L).build()
    val generator = BuildConfigGenerator(buildConfigData)
    generator.generate()
    val file = generator.generatedFilePath
    Assert.assertTrue(file.exists())
    val actual = Files.toString(file, Charsets.UTF_8)
    Assert.assertEquals(
      """
      /*
       * Automatically generated file. DO NOT MODIFY
       */
      package my.app.pkg;

      public final class BuildConfig {
        public static final long TIME_STAMP = 12343434L;
      }
      """
        .trimIndent(),
      actual.trim(),
    )
  }

  @Test
  fun testExtra() {
    val tempDir = mTemporaryFolder.newFolder()
    val buildConfigData =
      BuildConfigData.Builder().setOutputPath(tempDir.toPath()).setNamespace("my.app.pkg").addIntField("EXTRA", 42, "Extra line").build()
    val generator = BuildConfigGenerator(buildConfigData)
    generator.generate()
    val file = generator.generatedFilePath
    Assert.assertTrue(file.exists())
    val actual = Files.toString(file, Charsets.UTF_8)
    Assert.assertEquals(
      """
      /*
       * Automatically generated file. DO NOT MODIFY
       */
      package my.app.pkg;

      public final class BuildConfig {
        // Extra line
        public static final int EXTRA = 42;
      }
      """
        .trimIndent(),
      actual.trim(),
    )
  }

  @Test
  fun testTypeSpecialHandling() {
    val tempDir = mTemporaryFolder.newFolder()
    val buildConfigData =
      BuildConfigData.Builder()
        .setOutputPath(tempDir.toPath())
        .setNamespace("my.app.pkg")
        .addItem("COMMENT", BuildConfigField("String", "\"String with comment\"", "A string!"))
        .addItem("INT1", BuildConfigField("int", 1, null))
        .addItem("INT2", BuildConfigField("int", "2", null))
        .addItem("LONG1", BuildConfigField("long", 3, null))
        .addItem("LONG2", BuildConfigField("long", 4L, null))
        .addItem("LONG3", BuildConfigField("long", "5", null))
        .addItem("LONG4", BuildConfigField("long", "6L", null))
        .addItem("LONG5", BuildConfigField("long", "calculateLong()", null))
        .addItem("BOOLEAN1", BuildConfigField("boolean", "true", null))
        .addItem("BOOLEAN2", BuildConfigField("boolean", "false", null))
        .addItem("BOOLEAN3", BuildConfigField("boolean", true, null))
        .addItem("BOOLEAN4", BuildConfigField("boolean", false, null))
        .addItem("FLOAT1", BuildConfigField("float", 7.7f, null))
        .addItem("FLOAT2", BuildConfigField("float", 8.8, null))
        .addItem("FLOAT3", BuildConfigField("float", "9.9", null))
        .addItem("FLOAT3", BuildConfigField("float", "9.9f", null))
        .addItem("FLOAT4", BuildConfigField("float", "calculateFloat()", null))
        .addItem("FLOAT5", BuildConfigField("float", "10", null))
        .addItem("FLOAT6", BuildConfigField("float", 11, null))
        .addItem("DOUBLE1", BuildConfigField("double", 12.12f, null))
        .addItem("DOUBLE2", BuildConfigField("double", 13.13, null))
        .addItem("DOUBLE3", BuildConfigField("double", "14.14", null))
        .addItem("DOUBLE4", BuildConfigField("double", "calculateDouble()", null))
        .addItem("STRING1", BuildConfigField("String", "\"String 1\"", null))
        .addItem("STRING2", BuildConfigField("String", "calculateString()", null))
        .addItem("STRING_ARRAY", BuildConfigField("String[]", "new String[]{}", null))
        .build()
    val generator = BuildConfigGenerator(buildConfigData)
    generator.generate()
    val file = generator.generatedFilePath
    assertThat(file).exists()
    val actual = Files.asCharSource(file, Charsets.UTF_8).read().trim()
    assertThat(actual)
      .isEqualTo(
        """
        /*
         * Automatically generated file. DO NOT MODIFY
         */
        package my.app.pkg;

        public final class BuildConfig {
          // A string!
          public static final String COMMENT = "String with comment";
          public static final int INT1 = 1;
          public static final int INT2 = 2;
          public static final long LONG1 = 3L;
          public static final long LONG2 = 4L;
          public static final long LONG3 = 5L;
          public static final long LONG4 = 6L;
          public static final long LONG5 = calculateLong();
          public static final boolean BOOLEAN1 = true;
          public static final boolean BOOLEAN2 = false;
          public static final boolean BOOLEAN3 = true;
          public static final boolean BOOLEAN4 = false;
          public static final float FLOAT1 = 7.7f;
          public static final float FLOAT2 = 8.8f;
          public static final float FLOAT3 = 9.9f;
          public static final float FLOAT4 = calculateFloat();
          public static final float FLOAT5 = 10f;
          public static final float FLOAT6 = 11f;
          public static final double DOUBLE1 = 12.12;
          public static final double DOUBLE2 = 13.13;
          public static final double DOUBLE3 = 14.14;
          public static final double DOUBLE4 = calculateDouble();
          public static final String STRING1 = "String 1";
          public static final String STRING2 = calculateString();
          public static final String[] STRING_ARRAY = new String[]{};
        }
        """
          .trimIndent()
      )
  }

  /**
   * Regression test for b/567587949: javac 23+ (JDK-8303689) reports a Javadoc comment before the package declaration under
   * -Xlint:dangling-doc-comments, so a Javadoc-style file header breaks `-Xlint:all -Werror` builds.
   */
  @Test
  fun testGeneratedSourceCompilesWithAllLintWarningsAsErrors() {
    val tempDir = mTemporaryFolder.newFolder()
    val buildConfigData =
      BuildConfigData.Builder()
        .setOutputPath(tempDir.toPath())
        .setNamespace("my.app.pkg")
        .addBooleanField("DEBUG", true)
        .addItem("COMMENT", BuildConfigField("String", "\"String with comment\"", "A string!"))
        .build()
    val generator = BuildConfigGenerator(buildConfigData)
    generator.generate()
    val file = generator.generatedFilePath

    // JDK-independent guard: the file header (before `package`) must not be a doc comment.
    assertThat(Files.asCharSource(file, Charsets.UTF_8).read().substringBefore("package ")).doesNotContain("/**")

    // Compile with the JDK running the test; on JDK 23+ this exercises the dangling-doc-comments lint.
    val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "Test must run on a JDK, not a JRE" }
    val diagnostics = DiagnosticCollector<JavaFileObject>()
    val classesDir = mTemporaryFolder.newFolder()
    val success =
      compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8).use { fileManager ->
        // Hermetic: don't inherit the test runner's classpath (or annotation processors on it).
        fileManager.setLocation(StandardLocation.CLASS_PATH, emptyList())
        compiler
          .getTask(
            null,
            fileManager,
            diagnostics,
            listOf("-proc:none", "-Xlint:all", "-Werror", "-d", classesDir.path),
            null,
            fileManager.getJavaFileObjects(file),
          )
          .call()
      }
    // Intentional canary: users compile this generated file with -Xlint:all -Werror, so a new javac
    // lint that fires here would break them too. Fix the generator; don't suppress the lint here.
    assertWithMessage("Generated BuildConfig.java must compile warning-free (b/567587949):\n" + diagnostics.diagnostics.joinToString("\n"))
      .that(success)
      .isTrue()
  }

  @Test
  fun testEscapingDollarSigns() {
    val tempDir = mTemporaryFolder.newFolder()
    val buildConfigData =
      BuildConfigData.Builder()
        .setOutputPath(tempDir.toPath())
        .setNamespace("my.app.pkg")
        .addItem(
          "DOLLAR_FIELD",
          BuildConfigField(
            "String",
            "\"Value with \$100 and \$L \$S \$T \$N\"",
            "Comment with \$100 and \$L \$S \$T \$N",
          ),
        )
        .addItem(
          "FORMAT_EXPR",
          BuildConfigField(
            "String",
            "String.format(\"VALUE=%1\$d\", 42)",
            null,
          ),
        )
        .build()
    val generator = BuildConfigGenerator(buildConfigData)
    generator.generate()
    val actual = Files.asCharSource(generator.generatedFilePath, Charsets.UTF_8).read().trim()
    assertThat(actual)
      .isEqualTo(
        """
        /*
         * Automatically generated file. DO NOT MODIFY
         */
        package my.app.pkg;

        public final class BuildConfig {
          // Comment with ${'$'}100 and ${'$'}L ${'$'}S ${'$'}T ${'$'}N
          public static final String DOLLAR_FIELD = "Value with ${'$'}100 and ${'$'}L ${'$'}S ${'$'}T ${'$'}N";
          public static final String FORMAT_EXPR = String.format("VALUE=%1${'$'}d", 42);
        }
        """
          .trimIndent()
      )
    assertCompilesWarningFree(generator.generatedFilePath)
  }

  @Test
  fun testPrimitivesAndRecursiveArrays() {
    val tempDir = mTemporaryFolder.newFolder()
    val buildConfigData =
      BuildConfigData.Builder()
        .setOutputPath(tempDir.toPath())
        .setNamespace("my.app.pkg")
        .addItem("BYTE_VAL", BuildConfigField("byte", "1", null))
        .addItem("SHORT_VAL", BuildConfigField("short", "2", null))
        .addItem("CHAR_VAL", BuildConfigField("char", "'a'", null))
        .addItem("INT_ARRAY", BuildConfigField("int[]", "new int[]{1, 2}", null))
        .addItem("BYTE_2D_ARRAY", BuildConfigField("byte[][]", "new byte[][]{{1}, {2}}", null))
        .addItem("SPACED_ARRAY", BuildConfigField("int [] []", "new int[][]{}", null))
        .addItem("STRING_2D_ARRAY", BuildConfigField("String[][]", "new String[][]{}", null))
        .addItem("BOXED_INT_ARRAY", BuildConfigField("Integer[]", "new Integer[]{1}", null))
        .addItem("FQ_JAVA_LANG_ARRAY", BuildConfigField("java.lang.Long[]", "new Long[]{1L}", null))
        .addItem("OPTIONAL_INT_ARRAY", BuildConfigField("java.util.OptionalInt[]", "new java.util.OptionalInt[]{}", null))
        .build()
    val generator = BuildConfigGenerator(buildConfigData)
    generator.generate()
    val actual = Files.asCharSource(generator.generatedFilePath, Charsets.UTF_8).read().trim()
    assertThat(actual)
      .isEqualTo(
        """
        /*
         * Automatically generated file. DO NOT MODIFY
         */
        package my.app.pkg;

        public final class BuildConfig {
          public static final byte BYTE_VAL = 1;
          public static final short SHORT_VAL = 2;
          public static final char CHAR_VAL = 'a';
          public static final int[] INT_ARRAY = new int[]{1, 2};
          public static final byte[][] BYTE_2D_ARRAY = new byte[][]{{1}, {2}};
          public static final int [] [] SPACED_ARRAY = new int[][]{};
          public static final String[][] STRING_2D_ARRAY = new String[][]{};
          public static final Integer[] BOXED_INT_ARRAY = new Integer[]{1};
          public static final Long[] FQ_JAVA_LANG_ARRAY = new Long[]{1L};
          public static final java.util.OptionalInt[] OPTIONAL_INT_ARRAY = new java.util.OptionalInt[]{};
        }
        """
          .trimIndent()
      )
    assertCompilesWarningFree(generator.generatedFilePath)
  }

  @Test
  fun testGenericsWildcardsAndNestedClasses() {
    val tempDir = mTemporaryFolder.newFolder()
    val buildConfigData =
      BuildConfigData.Builder()
        .setOutputPath(tempDir.toPath())
        .setNamespace("my.app.pkg")
        .addItem("STRING_LIST", BuildConfigField("java.util.List<String>", "java.util.Collections.emptyList()", null))
        .addItem("FQ_JAVA_LANG_ARG", BuildConfigField("java.util.List<java.lang.String>", "java.util.Collections.emptyList()", null))
        .addItem("WILDCARD_MAP", BuildConfigField("java.util.Map<String, ? extends Number>", "java.util.Collections.emptyMap()", null))
        .addItem("SUPER_WILDCARD", BuildConfigField("java.util.List<? super Integer>", "null", null))
        .addItem("UNBOUNDED_WILDCARD", BuildConfigField("java.util.List<?>", "null", null))
        .addItem("DEEP_GENERICS", BuildConfigField("java.util.Map<String, java.util.List<java.util.Set<?>>>", "null", null))
        .addItem("PRIMITIVE_ARRAY_ARG", BuildConfigField("java.util.List<int[]>", "null", null))
        .addItem("WILDCARD_ARRAY_BOUND", BuildConfigField("java.util.List<? extends byte[][]>", "null", null))
        .addItem("ENTRY_ARRAY", BuildConfigField("java.util.Map.Entry<String, Integer>[]", "null", null))
        .addItem("NESTED_CLASS", BuildConfigField("com.example.Outer.StaticInner", "null", null))
        .addItem("GENERIC_NESTED", BuildConfigField("com.example.Outer<String>.Inner<Integer>", "null", null))
        .addItem("GENERIC_OUTER_RAW_INNER", BuildConfigField("com.example.Outer<String>.RawInner", "null", null))
        .addItem("THREAD_STATE", BuildConfigField("java.lang.Thread.State", "Thread.State.NEW", null))
        .addItem("SAME_PKG_TYPE", BuildConfigField("CustomHelper", "null", null))
        .addItem("FQ_SAME_PKG_GENERIC", BuildConfigField("my.app.pkg.SamePkgOuter<String>.Inner<Integer>", "null", null))
        .addItem("COLLIDING_EXTERNAL_TYPE", BuildConfigField("com.other.CustomHelper", "null", null))
        .build()
    val generator = BuildConfigGenerator(buildConfigData)
    generator.generate()
    val actual = Files.asCharSource(generator.generatedFilePath, Charsets.UTF_8).read().trim()
    assertThat(actual)
      .isEqualTo(
        """
        /*
         * Automatically generated file. DO NOT MODIFY
         */
        package my.app.pkg;

        public final class BuildConfig {
          public static final java.util.List<String> STRING_LIST = java.util.Collections.emptyList();
          public static final java.util.List<String> FQ_JAVA_LANG_ARG = java.util.Collections.emptyList();
          public static final java.util.Map<String, ? extends Number> WILDCARD_MAP = java.util.Collections.emptyMap();
          public static final java.util.List<? super Integer> SUPER_WILDCARD = null;
          public static final java.util.List<?> UNBOUNDED_WILDCARD = null;
          public static final java.util.Map<String, java.util.List<java.util.Set<?>>> DEEP_GENERICS = null;
          public static final java.util.List<int[]> PRIMITIVE_ARRAY_ARG = null;
          public static final java.util.List<? extends byte[][]> WILDCARD_ARRAY_BOUND = null;
          public static final java.util.Map.Entry<String, Integer>[] ENTRY_ARRAY = null;
          public static final com.example.Outer.StaticInner NESTED_CLASS = null;
          public static final com.example.Outer<String>.Inner<Integer> GENERIC_NESTED = null;
          public static final com.example.Outer<String>.RawInner GENERIC_OUTER_RAW_INNER = null;
          public static final Thread.State THREAD_STATE = Thread.State.NEW;
          public static final CustomHelper SAME_PKG_TYPE = null;
          public static final SamePkgOuter<String>.Inner<Integer> FQ_SAME_PKG_GENERIC = null;
          public static final com.other.CustomHelper COLLIDING_EXTERNAL_TYPE = null;
        }
        """
          .trimIndent()
      )

    val stubsDir = mTemporaryFolder.newFolder()
    val outerStub =
      stubsDir.resolve("com/example/Outer.java").apply {
        parentFile.mkdirs()
        writeText(
          """
          package com.example;
          public class Outer<T> {
            public static class StaticInner {}
            public class Inner<U> {}
            public class RawInner {}
          }
          """
            .trimIndent()
        )
      }
    val samePkgHelperStub =
      stubsDir.resolve("my/app/pkg/CustomHelper.java").apply {
        parentFile.mkdirs()
        writeText("package my.app.pkg;\npublic class CustomHelper {}\n")
      }
    val samePkgOuterStub =
      stubsDir.resolve("my/app/pkg/SamePkgOuter.java").apply {
        parentFile.mkdirs()
        writeText("package my.app.pkg;\npublic class SamePkgOuter<T> { public class Inner<U> {} }\n")
      }
    val externalHelperStub =
      stubsDir.resolve("com/other/CustomHelper.java").apply {
        parentFile.mkdirs()
        writeText("package com.other;\npublic class CustomHelper {}\n")
      }
    assertCompilesWarningFree(
      generator.generatedFilePath,
      outerStub,
      samePkgHelperStub,
      samePkgOuterStub,
      externalHelperStub,
    )
  }

  private fun assertCompilesWarningFree(vararg sources: java.io.File) {
    val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "Test must run on a JDK, not a JRE" }
    val diagnostics = DiagnosticCollector<JavaFileObject>()
    val classesDir = mTemporaryFolder.newFolder()
    val success =
      compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8).use { fileManager ->
        fileManager.setLocation(StandardLocation.CLASS_PATH, emptyList())
        compiler
          .getTask(
            null,
            fileManager,
            diagnostics,
            listOf("-proc:none", "-Xlint:all", "-Werror", "-d", classesDir.path),
            null,
            fileManager.getJavaFileObjects(*sources),
          )
          .call()
      }
    assertWithMessage("Generated BuildConfig.java must compile warning-free:\n" + diagnostics.diagnostics.joinToString("\n"))
      .that(success)
      .isTrue()
  }
}
