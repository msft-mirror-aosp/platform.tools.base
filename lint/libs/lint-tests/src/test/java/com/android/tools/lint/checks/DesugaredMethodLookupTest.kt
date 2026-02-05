/*
 * Copyright (C) 2021 The Android Open Source Project
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

import com.android.sdklib.IAndroidTarget
import com.android.testutils.TestUtils
import com.android.tools.lint.LintCliClient
import com.android.tools.lint.checks.infrastructure.TestFiles
import com.android.tools.lint.detector.api.Project
import com.android.tools.lint.detector.api.SourceSetType
import com.android.tools.lint.model.LintModelAndroidArtifact
import com.android.tools.lint.model.LintModelVariant
import com.android.utils.SdkUtils.fileToUrl
import java.io.File
import kotlin.test.assertNotEquals
import org.junit.After
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class DesugaredMethodLookupTest {
  @get:Rule var temporaryFolder = TemporaryFolder()

  @After
  fun tearDown() {
    DesugaredMethodLookup.reset()
  }

  @Test
  fun `test merging desugaring files for bug 327670482`() {
    val desc1 =
      "" +
        "java/util/Map#ofEntries([Ljava/util/Map\$Entry;)Ljava/util/Map;\n" +
        "java/util/Objects#checkFromIndexSize(JJJ)J\n" +
        "java/util/Objects#checkFromToIndex(JJJ)J\n" +
        "java/util/Objects#checkIndex(JJ)J\n" +
        "java/util/Set#copyOf(Ljava/util/Collection;)Ljava/util/Set;\n" +
        "java/util/Set#of()Ljava/util/Set;\n"

    val desc2 =
      "" +
        "java/util/Map\$Entry#comparingByValue(Ljava/util/Comparator;)Ljava/util/Comparator;\n" +
        "java/util/Objects\n" +
        "java/util/Optional\n" +
        "java/util/OptionalDouble\n"

    val file1 = temporaryFolder.newFile().apply { writeText(desc1) }
    val file2 = temporaryFolder.newFile().apply { writeText(desc2) }
    try {
      val lookup = DesugaredMethodLookup.createDesugaredMethodLookup(listOf(file1, file2))
      assertEquals(
        "" +
          "java/util/Map#ofEntries([Ljava/util/Map\$Entry;)Ljava/util/Map;\n" +
          "java/util/Map\$Entry#comparingByValue(Ljava/util/Comparator;)Ljava/util/Comparator;\n" +
          "java/util/Objects\n" +
          "java/util/Optional\n" +
          "java/util/OptionalDouble\n" +
          "java/util/Set#copyOf(Ljava/util/Collection;)Ljava/util/Set;\n" +
          "java/util/Set#of()Ljava/util/Set;",
        lookup.methodDescriptors.joinToString("\n"),
      )
      assertTrue(lookup.isDesugaredClass("java/util/Objects"))
      assertTrue(lookup.isDesugaredMethod("java/util/Objects", "requireNonNullElse", "(Ljava.lang.Object;Ljava.lang.Object;)"))
    } finally {
      DesugaredMethodLookup.reset()
    }
  }

  @Test
  fun `test desugaring works with inner classes`() {
    val desc1 =
      "" +
        "java/util/Map\$Entry#comparingByKey()Ljava/util/Comparator;\n" +
        "java/util/Map\$Entry#comparingByKey(Ljava/util/Comparator;)Ljava/util/Comparator;\n" +
        "java/util/Map\$Entry#comparingByValue()Ljava/util/Comparator;\n" +
        "java/util/Map\$Entry#comparingByValue(Ljava/util/Comparator;)Ljava/util/Comparator;\n"

    val file1 = temporaryFolder.newFile().apply { writeText(desc1) }
    try {
      DesugaredMethodLookup.setDesugaredMethods(listOf(file1.path))
      assertTrue(
        DesugaredMethodLookup.isDesugaredMethod(
          "java/util/Map\$Entry",
          "comparingByValue",
          "(Ljava/util/Comparator;)",
          SourceSetType.INSTRUMENTATION_TESTS,
        )
      )
      assertTrue(
        DesugaredMethodLookup.isDesugaredMethod("java/util/Map\$Entry", "comparingByValue", "()", SourceSetType.INSTRUMENTATION_TESTS)
      )
      assertFalse(DesugaredMethodLookup.isDesugaredMethod("java/util/Map\$Entry", "", "", SourceSetType.INSTRUMENTATION_TESTS))
      assertFalse(DesugaredMethodLookup.isDesugaredMethod("java/util/Map", "comparingByValue", "()", SourceSetType.INSTRUMENTATION_TESTS))
    } finally {
      DesugaredMethodLookup.reset()
    }
  }

  @Test
  fun `test simple use case with CollectionStream`() {
    val desc1 =
      "" +
        "java/util/Collection#spliterator()Ljava/util/Spliterator;\n" +
        "java/util/Collections#emptyEnumeration()Ljava/util/Enumeration;\n" +
        "java/util/Collection#stream()Ljava/util/stream/Stream;\n"

    val file1 = temporaryFolder.newFile().apply { writeText(desc1) }
    try {
      DesugaredMethodLookup.setDesugaredMethods(listOf(file1.path))
      assertTrue(DesugaredMethodLookup.isDesugaredMethod("java.util.Collection", "stream", "()", SourceSetType.INSTRUMENTATION_TESTS))
    } finally {
      DesugaredMethodLookup.reset()
    }
  }

  @Test
  fun `test binary search with case sensitivity`() {
    // java/util/concurrent/* sorts after java/util/Set in the lookup table created with the command
    // listed in DesugaredMethodLookup#defaultDesugaredMethods -- make sure this is also handled
    // the same way by lookup
    assertTrue(DesugaredMethodLookup.isDesugaredMethod("java/util/Set", "of", "([Ljava/lang/Object;)", SourceSetType.MAIN))
    assertTrue(
      DesugaredMethodLookup.isDesugaredMethod(
        "java/util/concurrent/atomic/AtomicReferenceFieldUpdater",
        "compareAndSet",
        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)",
        SourceSetType.MAIN,
      )
    )
    assertTrue(
      DesugaredMethodLookup.isDesugaredMethod(
        "sun/misc/Unsafe",
        "compareAndSwapObject",
        "(Ljava/lang/Object;JLjava/lang/Object;Ljava/lang/Object;)",
        SourceSetType.MAIN,
      )
    )
  }

  @Test
  fun `test field lookup`() {
    val desc1 = "" + "java/nio/charset/StandardCharsets#UTF_8\n" + "java/nio/file/Files\n" + "java/nio/file/StandardOpenOption\n"

    val file1 = temporaryFolder.newFile().apply { writeText(desc1) }
    try {
      DesugaredMethodLookup.setDesugaredMethods(listOf(file1.path))
      assertTrue(DesugaredMethodLookup.isDesugaredField("java.nio.charset.StandardCharsets", "UTF_8", SourceSetType.MAIN))
      assertFalse(DesugaredMethodLookup.isDesugaredMethod("java.nio.charset.StandardCharsets", "UTF_8", "()", SourceSetType.MAIN))
      assertTrue(DesugaredMethodLookup.isDesugaredField("java.nio.file.StandardOpenOption", "APPEND", SourceSetType.MAIN))
      assertTrue(DesugaredMethodLookup.isDesugaredMethod("java.nio.file.Files", "write", "()", SourceSetType.MAIN))
    } finally {
      DesugaredMethodLookup.reset()
    }
  }

  @Test
  fun `test class lookup`() {
    val desc1 = "" + "java/nio/charset/StandardCharsets#UTF_8\n" + "java/nio/file/Files\n" + "java/nio/file/StandardOpenOption\n"

    val file1 = temporaryFolder.newFile().apply { writeText(desc1) }
    try {
      DesugaredMethodLookup.setDesugaredMethods(listOf(file1.path))
      assertFalse(DesugaredMethodLookup.isDesugaredClass("java.nio.charset.StandardCharsets", SourceSetType.MAIN))
      assertTrue(DesugaredMethodLookup.isDesugaredClass("java.nio.file.StandardOpenOption", SourceSetType.MAIN))
      assertTrue(DesugaredMethodLookup.isDesugaredClass("java.nio.file.Files", SourceSetType.MAIN))
    } finally {
      DesugaredMethodLookup.reset()
    }
  }

  @Test
  fun `test complex use case with CollectionStream - right of pivot`() {
    val desc1 =
      "" +
        "java/util/Collections#emptyIterator()Ljava/util/Iterator;\n" +
        "java/util/Collections#emptyListIterator()Ljava/util/ListIterator;\n" +
        "java/util/Collections#synchronizedMap(Ljava/util/Map;)Ljava/util/Map;\n" +
        "java/util/Collection#spliterator()Ljava/util/Spliterator;\n" +
        "java/util/Collections#emptyEnumeration()Ljava/util/Enumeration;\n" +
        "java/util/Collection#stream()Ljava/util/stream/Stream;\n" +
        "java/util/Arrays#stream([Ljava/lang/Object;II)Ljava/util/stream/Stream;\n" +
        "java/util/Arrays#stream([Ljava/lang/Object;II)Ljava/util/stream/Stream;\n" +
        "java/util/Calendar#toInstant()Ljava/time/Instant;\n" +
        "java/util/Collections#synchronizedMap(Ljava/util/Map;)Ljava/util/Map;\n" +
        "java/util/Collections#synchronizedSortedMap(Ljava/util/SortedMap;)Ljava/util/SortedMap;\n" +
        "java/util/Collections#synchronizedSortedMap(Ljava/util/SortedMap;)Ljava/util/SortedMap;\n"

    val file1 = temporaryFolder.newFile().apply { writeText(desc1) }
    try {
      DesugaredMethodLookup.setDesugaredMethods(listOf(file1.path))
      DesugaredMethodLookup.isDesugaredMethod("java.util.Collection", "stream", "()", SourceSetType.INSTRUMENTATION_TESTS)
    } finally {
      DesugaredMethodLookup.reset()
    }
  }

  @Test
  fun `test complex use case with CollectionStream - left of pivot`() {
    val desc1 =
      "" +
        "java/util/Arrays#stream([Ljava/lang/Object;II)Ljava/util/stream/Stream;\n" +
        "java/util/Arrays#stream([Ljava/lang/Object;II)Ljava/util/stream/Stream;\n" +
        "java/util/Arrays#stream([Ljava/lang/Object;II)Ljava/util/stream/Stream;\n" +
        "java/util/Arrays#stream([Ljava/lang/Object;II)Ljava/util/stream/Stream;\n" +
        "java/util/Collections#emptyIterator()Ljava/util/Iterator;\n" +
        "java/util/Collections#emptyListIterator()Ljava/util/ListIterator;\n" +
        "java/util/Collections#synchronizedMap(Ljava/util/Map;)Ljava/util/Map;\n" +
        "java/util/Collect#spliterator()Ljava/util/Spliterator;\n" +
        "java/util/Collections#emptyEnumeration()Ljava/util/Enumeration;\n" +
        "java/util/Collection#stream()Ljava/util/stream/Stream;\n" +
        "java/util/Arrays#stream([Ljava/lang/Object;II)Ljava/util/stream/Stream;\n" +
        "java/util/Arrays#stream([Ljava/lang/Object;II)Ljava/util/stream/Stream;\n" +
        "java/util/Calendar#toInstant()Ljava/time/Instant;\n" +
        "java/util/Collections#synchronizedMap(Ljava/util/Map;)Ljava/util/Map;\n" +
        "java/util/Collections#synchronizedSortedMap(Ljava/util/SortedMap;)Ljava/util/SortedMap;\n" +
        "java/util/Collections#synchronizedSortedMap(Ljava/util/SortedMap;)Ljava/util/SortedMap;\n"

    val file1 = temporaryFolder.newFile().apply { writeText(desc1) }
    try {
      DesugaredMethodLookup.setDesugaredMethods(listOf(file1.path))
      assertTrue(DesugaredMethodLookup.isDesugaredMethod("java.util.Collection", "stream", "()", SourceSetType.INSTRUMENTATION_TESTS))
    } finally {
      DesugaredMethodLookup.reset()
    }
  }

  @Test
  fun `test merge signature lists`() {
    val entries = DesugaredMethodLookup.getBundledLibraryDesugaringRules(19)

    // Make sure it's sorted
    var prev = ""
    for (entry in entries) {
      assertTrue(entry > prev)
      prev = entry
    }

    // Make sure that if we support a whole class, we don't then
    // go on to also include individual entries
    prev = ""
    for (entry in entries) {
      val index = entry.indexOf('#')
      if (index != -1 && prev == entry.substring(0, index)) {
        Assert.fail("$prev and $entry are both present; $prev should have implied all entries are included")
      }
      prev = entry
    }
  }

  @Test
  fun `test find all`() {
    assertTrue(DesugaredMethodLookup.isDesugaredMethod("java/lang/Character", "compare", "(CC)", SourceSetType.INSTRUMENTATION_TESTS))

    val signatureLists =
      listOf(DesugaredMethodLookup.defaultDesugaredMethods, DesugaredMethodLookup.getBundledLibraryDesugaringRules(24).toTypedArray())

    val client =
      object : LintCliClient(CLIENT_UNIT_TESTS) {
        override fun getSdkHome(): File? = TestUtils.getSdk().toFile()
      }
    val lookup = client.getPlatformLookup()!!
    val xmlFile = File(lookup.getLatestSdkTarget()!!.getPath(IAndroidTarget.DATA).toFile(), "api-versions.xml")
    val info = Api.parseApi(xmlFile)

    for (signatures in signatureLists) {
      val lookup = DesugaredMethodLookup(signatures)
      for (entry in signatures) {
        val sharp = entry.indexOf("#")
        if (sharp == -1) {
          // full class: make up some names; they *should* match
          assertTrue(entry, lookup.isDesugaredMethod(entry, "foo", "(I)"))
          assertTrue(entry, lookup.isDesugaredClass(entry))
          assertFalse(entry, lookup.isDesugaredClass(entry.substring(0, entry.length - 2)))
          continue
        }
        assertNotEquals(-1, sharp)
        val owner = entry.substring(0, sharp).replace("/", ".").replace("\$", ".")
        val paren = entry.indexOf('(', sharp + 1)
        val partialClassSupport = signatures.any { it.startsWith(entry.substring(0, sharp + 1)) }

        if (paren == -1) {
          // field -- has name, but no desc.
          val name = entry.substring(sharp + 1)
          assertTrue(entry, lookup.isDesugaredField(owner, name))
          // Make sure we don't match on partial field name (unless we support
          // the whole class; in that case, it doesn't list individual fields)
          if (partialClassSupport) {
            if (name.length > 1) {
              assertFalse(entry, lookup.isDesugaredField(owner, name.substring(0, name.length - 1)))
            }
          }
          continue
        }
        assertNotEquals(-1, paren)
        val name = entry.substring(sharp + 1, paren)
        val desc = entry.substring(paren, entry.indexOf(")") + 1)
        assertTrue(entry, lookup.isDesugaredMethod(owner, name, desc))
        // Make sure we don't match on partial field name (unless we support
        // the whole class; in that case, it doesn't list individual fields)
        if (partialClassSupport) {
          assertFalse(entry, lookup.isDesugaredMethod(owner, name, desc.substring(0, desc.length - 2) + "JJ)"))
        }
      }

      // Negative tests: make sure nothing in the Android SDK surface shows up
      // as matching unless it's present in the signature list
      val allowed =
        signatures
          .map {
            // Drop return type to simplify below lookup
            val rp = it.lastIndexOf(')')
            if (rp != -1) {
              it.substring(0, rp + 1)
            } else {
              it
            }
          }
          .toSet()
      for (apiClass in info.classes.values) {
        val name = apiClass.name
        val fields = apiClass.fields
        val methods = apiClass.methods

        if (!allowed.contains(name)) {
          assertFalse(name, lookup.isDesugaredClass(name))
        }

        for (methodWithDesc in methods) {
          val entry = "$name#$methodWithDesc"
          if (!allowed.contains(name) && !allowed.contains(entry)) {
            val index = methodWithDesc.indexOf('(')
            val methodName = methodWithDesc.substring(0, index)
            val desc = methodWithDesc.substring(index)
            if (methodName == "<init>") {
              // The signature lists are missing constructors. We have an approximation
              // that we consider a constructor backported if one or more methods/fields
              // are present in the signature file, so match the same logic here.
              val isPresent = signatures.contains(name) || signatures.any { it.startsWith("$name#") }
              assertEquals(entry, isPresent, lookup.isDesugaredMethod(name, methodName, desc))
            } else {
              assertFalse(entry, lookup.isDesugaredMethod(name, methodName, desc))
            }
          }
        }

        for (field in fields) {
          val entry = "$name#$field"
          if (!allowed.contains(name) && !allowed.contains(entry)) {
            assertFalse(entry, lookup.isDesugaredField(name, field))
          }
        }
      }
    }
  }

  @Test
  fun `test not desugared methods`() {
    assertFalse(DesugaredMethodLookup.isDesugaredMethod("foo.bar.Baz", "foo", "(I)", SourceSetType.INSTRUMENTATION_TESTS))
    assertFalse(DesugaredMethodLookup.isDesugaredMethod("java/lang/Character", "wrongmethod", "(I)", SourceSetType.INSTRUMENTATION_TESTS))
    assertFalse(DesugaredMethodLookup.isDesugaredMethod("java/lang/Character", "compare", "(JJJJ)", SourceSetType.INSTRUMENTATION_TESTS))
    assertFalse(DesugaredMethodLookup.isDesugaredMethod("java/lang/Character", "compare", "()", SourceSetType.INSTRUMENTATION_TESTS))
  }

  @Test
  fun `test file`() {
    val desc1 = "" + "abc/def/GHI\$JKL#abc(III)Z\n" + "def/gh/IJ\n"
    val desc2 = "" + "g/hijk/l/MN#op\n" + "hij/kl/mn/O#pQr()Z\n"

    fun check() {
      assertFalse(DesugaredMethodLookup.isDesugaredMethod("foo/Bar", "baz", "()", SourceSetType.INSTRUMENTATION_TESTS))
      assertTrue(DesugaredMethodLookup.isDesugaredMethod("abc/def/GHI\$JKL", "abc", "(III)", SourceSetType.INSTRUMENTATION_TESTS))
      assertFalse(DesugaredMethodLookup.isDesugaredMethod("abc/def/GHI", "abc", "(III)", SourceSetType.INSTRUMENTATION_TESTS))
      assertFalse(DesugaredMethodLookup.isDesugaredMethod("abc/def/GHI\$JKL", "ab", "(III)", SourceSetType.INSTRUMENTATION_TESTS))
      assertTrue(DesugaredMethodLookup.isDesugaredMethod("hij/kl/mn/O", "pQr", "()", SourceSetType.INSTRUMENTATION_TESTS))

      // Don't match partial names
      assertFalse(DesugaredMethodLookup.isDesugaredMethod("def/gh/I", "name", "()", SourceSetType.INSTRUMENTATION_TESTS))

      // Match methods where the descriptor just lists the class name
      assertTrue(DesugaredMethodLookup.isDesugaredMethod("def/gh/IJ", "name", "()", SourceSetType.INSTRUMENTATION_TESTS))

      /* No longer done: it looks like the descriptor files contain
         all valid inner classes.
      // Match inner classes where the descriptor just lists the top level class name
      assertTrue(
        DesugaredMethodLookup.isDesugaredMethod(
          "def/gh/IJ\$Inner",
          "name",
          "()",
          SourceSetType.INSTRUMENTATION_TESTS,
        )
      )
       */

      assertFalse(DesugaredMethodLookup.isDesugaredMethod("g/hijk/l/MN", "wrongname", "()", SourceSetType.INSTRUMENTATION_TESTS))
    }

    // Test single plain file
    val file1 = temporaryFolder.newFile()
    val file2 = temporaryFolder.newFile()
    file1.writeText(desc1 + desc2)
    try {
      assertNull(DesugaredMethodLookup.setDesugaredMethods(listOf(file1.path)))
      check()
    } finally {
      DesugaredMethodLookup.reset()
    }

    // Test 2 files
    file1.writeText(desc1)
    file2.writeText(desc2)
    try {
      // Reverse order to test our sorting as well
      assertNull(DesugaredMethodLookup.setDesugaredMethods(listOf(file2.path, file1.path)))
      check()
    } finally {
      DesugaredMethodLookup.reset()
    }

    // Test file URL paths
    file1.writeText(desc1 + desc2)
    try {
      assertNull(DesugaredMethodLookup.setDesugaredMethods(listOf(fileToUrl(file1).toExternalForm())))
      check()
    } finally {
      DesugaredMethodLookup.reset()
    }

    // Test JAR URL
    val source = TestFiles.source("foo/bar/baz.txt", desc1 + desc2)
    val jar = TestFiles.jar("myjar.jar", source)
    val jarFile = jar.createFile(file1.parentFile)
    jarFile.deleteOnExit()

    try {
      val jarPath = "jar:" + fileToUrl(jarFile).toExternalForm() + "!/foo/bar/baz.txt"
      assertNull(DesugaredMethodLookup.setDesugaredMethods(listOf(jarPath)))
      check()
    } finally {
      DesugaredMethodLookup.reset()
    }

    // Test error handling
    file1.delete()
    val missingFile = file1.path
    assertEquals(missingFile, DesugaredMethodLookup.setDesugaredMethods(listOf(missingFile)))
  }

  @Test
  fun `test desugaring from model - fallback desugaredMethodsFiles`() {
    val desc1 = "" + "abc/def/GHI\$JKL#abc(III)Z\n" + "def/gh/IJ\n"
    val desc2 = "" + "g/hijk/l/MN#op\n" + "hij/kl/mn/O#pQr()Z\n"

    val file1 = temporaryFolder.newFile().apply { writeText(desc1) }
    val file2 = temporaryFolder.newFile().apply { writeText(desc2) }
    val mainArtifact: LintModelAndroidArtifact = mock { on { desugaredMethodsFiles } doReturn listOf(file2, file1) }
    val variant: LintModelVariant = mock { on { this.mainArtifact } doReturn mainArtifact }
    val project: Project = mock { on { buildVariant } doReturn variant }

    assertFalse(DesugaredMethodLookup.isDesugaredMethod("foo/Bar", "baz", "()", SourceSetType.MAIN, project))
    assertTrue(DesugaredMethodLookup.isDesugaredMethod("abc/def/GHI\$JKL", "abc", "(III)", SourceSetType.MAIN, project))
    assertFalse(DesugaredMethodLookup.isDesugaredMethod("abc/def/GHI\$JKL", "ab", "(III)", SourceSetType.MAIN, project))
    assertTrue(DesugaredMethodLookup.isDesugaredMethod("hij/kl/mn/O", "pQr", "()", SourceSetType.MAIN, project))
    // Make sure we're *NOT* picking up metadata from the fallback list
    assertFalse(DesugaredMethodLookup.isDesugaredMethod("java/lang/Character", "compare", "(CC)", SourceSetType.MAIN, project))

    // Make sure we handle missing desugared-metadata gracefully
    whenever(variant.desugaredMethodsFiles).thenReturn(null)
    val variant2: LintModelVariant = mock { on { desugaredMethodsFiles } doReturn emptyList() }
    val project2: Project = mock { on { buildVariant } doReturn variant2 }
    assertFalse(DesugaredMethodLookup.isDesugaredMethod("foo/Bar", "baz", "()", SourceSetType.MAIN, project2))
    assertFalse(DesugaredMethodLookup.isDesugaredMethod("abc/def/GHI\$JKL", "abc", "(III)", SourceSetType.MAIN, project2))
    // make sure we're picking up the defaults in that case
    assertTrue(DesugaredMethodLookup.isDesugaredMethod("java/lang/Character", "compare", "(CC)", SourceSetType.MAIN, project2))
  }

  @Test
  fun `test desugaring from model when source set desugaredMethodsFiles is not null`() {
    val desc1 = "" + "abc/def/GHI\$JKL#abc(III)Z\n" + "def/gh/IJ\n"
    val desc2 = "" + "g/hijk/l/MN#op\n" + "hij/kl/mn/O#pQr()Z\n"

    val file1 = temporaryFolder.newFile().apply { writeText(desc1) }
    val file2 = temporaryFolder.newFile().apply { writeText(desc2) }
    val mainArtifact: LintModelAndroidArtifact = mock { on { desugaredMethodsFiles } doReturn listOf(file2, file1) }
    val variant: LintModelVariant = mock {
      on { this.mainArtifact } doReturn mainArtifact
      doReturn(null).whenever(it).desugaredMethodsFiles
    }
    val project: Project = mock { on { buildVariant } doReturn variant }

    assertFalse(DesugaredMethodLookup.isDesugaredMethod("foo/Bar", "baz", "()", SourceSetType.MAIN, project))
    assertTrue(DesugaredMethodLookup.isDesugaredMethod("abc/def/GHI\$JKL", "abc", "(III)", SourceSetType.MAIN, project))
    assertFalse(DesugaredMethodLookup.isDesugaredMethod("abc/def/GHI\$JKL", "ab", "(III)", SourceSetType.MAIN, project))
    assertTrue(DesugaredMethodLookup.isDesugaredMethod("hij/kl/mn/O", "pQr", "()", SourceSetType.MAIN, project))
  }
}
