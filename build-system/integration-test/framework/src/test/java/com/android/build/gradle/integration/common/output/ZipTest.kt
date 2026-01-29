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

package com.android.build.gradle.integration.common.output

import com.google.common.truth.Truth
import kotlin.io.path.readText
import org.junit.Assert
import org.junit.Test

@SuppressWarnings("PathAsIterable")
class ZipTest : BaseZipSubjectTest() {

  @Test
  fun getEntries() {
    createJar("temp.zip") {
        addTextFile("/foo.txt", "foo")
        addTextFile("/something/bar.txt", "bar")
      }
      .use { zip -> Truth.assertThat(zip.getEntries()).containsExactly("foo.txt", "something/bar.txt") }
  }

  @Test
  fun getEntry() {
    createJar("temp.zip") {
        addTextFile("/foo.txt", "foo")
        addTextFile("/something/bar.txt", "bar")
      }
      .use { zip ->
        val path = zip.getEntry("foo.txt")

        Truth.assertThat(path).isNotNull()
        Truth.assertThat(path!!.readText()).isEqualTo("foo")
      }
  }

  @Test
  fun textFile() {
    createJar("temp.zip") {
        addTextFile("/foo.txt", "foo")
        addTextFile("/something/bar.txt", "bar")
      }
      .use { zip -> Truth.assertThat(zip.textFile("foo.txt")).isEqualTo("foo") }
  }

  @Test
  fun binaryFile() {
    createJar("temp.zip") {
        addBinaryFile("/foo.data", FAKE_CLASS)
        addTextFile("/something/bar.txt", "bar")
      }
      .use { zip -> Truth.assertThat(zip.binaryFile("foo.data")).isEqualTo(FAKE_CLASS) }
  }

  @Test
  fun innerZip() {
    createAar("temp.zip") {
        withMainJar {
          addEmptyClasses("com/example/SomeClass")
          addBinaryFile("/file.dat", FAKE_CLASS)
        }
      }
      .use { zip ->
        val innerZip = zip.innerZip("classes.jar")
        Truth.assertWithMessage("innerZip(classes.jar)").that(innerZip).isNotNull()

        Truth.assertWithMessage("innerZip(classes.jar)")
          .that(innerZip!!.getEntries())
          .containsExactly("file.dat", "com/example/SomeClass.class")

        val nestedContent = innerZip.binaryFile("file.dat")

        Truth.assertWithMessage("innerZip(classes.jar).binaryFile(file.dat)").that(nestedContent).isEqualTo(FAKE_CLASS)
      }
  }

  @Test
  fun testNotExist() {
    val notExist = temporaryFolder.newFolder().toPath().resolve("not_exist")

    SimpleZip(notExist).use { zip ->
      Truth.assertThat(zip.getEntries()).isEmpty()
      Truth.assertThat(zip.exists()).isFalse()
    }
  }

  @Test
  fun invalidFileSystem() {
    createAar("temp.zip") {
        withMainJar {
          addEmptyClasses("com/example/SomeClass")
          addBinaryFile("/file.dat", FAKE_CLASS)
        }
        addResource("values/values.xml", "values file content")
      }
      .use { zip ->
        val entry = zip.getEntry("classes.jar")!!
        Assert.assertNotNull(entry)
        try {
          val innerZip = SimpleZip(entry)
          Assert.fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
          Truth.assertThat(e.toString()).contains("getEntryAsZip")
        }
      }
  }
}
