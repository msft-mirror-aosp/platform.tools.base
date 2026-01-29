/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.build.gradle.integration.common.fixture.project.builder

import com.google.common.truth.Truth
import java.io.File
import java.nio.file.Path
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class GradleProjectFilesTest(private val checker: ImplementationChecker) {

  /** Checker interface to abstract some test action to support the different implementations of [GradleProjectFiles] */
  interface ImplementationChecker {
    fun getInstance(location: Path): GradleProjectFiles

    fun checkEmpty(instance: GradleProjectFiles)

    fun checkContent(instance: GradleProjectFiles, expectedNameAndValues: List<Pair<String, Any>>)

    fun checkContent(instance: GradleProjectFiles, vararg expectedNameAndValues: Pair<String, Any>) {
      checkContent(instance, expectedNameAndValues.toList())
    }
  }

  /** checker for [DelayedGradleProjectFiles] */
  private class DelayedChecker : ImplementationChecker {
    override fun getInstance(location: Path): GradleProjectFiles = DelayedGradleProjectFiles()

    override fun checkEmpty(instance: GradleProjectFiles) {
      instance as? DelayedGradleProjectFiles ?: throw RuntimeException("Wrong instance type")
      Truth.assertThat(instance.sourceFiles).isEmpty()
    }

    override fun checkContent(instance: GradleProjectFiles, expectedNameAndValues: List<Pair<String, Any>>) {
      instance as? DelayedGradleProjectFiles ?: throw RuntimeException("Wrong instance type")
      Truth.assertThat(instance.sourceFiles.map { it.key to it.value }).containsExactlyElementsIn(expectedNameAndValues)
    }

    override fun toString(): String {
      return "DelayedGradleProjectFiles"
    }
  }

  /** checker for [DirectGradleProjectFiles] */
  private class DirectChecker : ImplementationChecker {
    override fun getInstance(location: Path): GradleProjectFiles = DirectGradleProjectFiles(location)

    override fun checkEmpty(instance: GradleProjectFiles) {
      instance as? DirectGradleProjectFiles ?: throw RuntimeException("Wrong instance type")
      val file = instance.location.toFile()
      Truth.assertWithMessage("${instance.location} is empty").that(file.list()!!).isEmpty()
    }

    override fun checkContent(instance: GradleProjectFiles, expectedNameAndValues: List<Pair<String, Any>>) {
      val expectedContentMap = expectedNameAndValues.associateBy({ it.first }, { it.second })

      instance as? DirectGradleProjectFiles ?: throw RuntimeException("Wrong instance type")
      val file = instance.location.toFile()
      val nameToContentActual =
        file
          .walkTopDown()
          .filter { it.isFile }
          .map { it: File ->
            val relativePath = it.relativeTo(file).invariantSeparatorsPath
            val expectedValue = expectedContentMap[relativePath] ?: fail("unexpected result: ${it.name}")
            relativePath to
              when (expectedValue) {
                is String -> it.readText()
                is ByteArray -> it.readBytes()
                else -> fail("Unexpected actual type for key: ${it.name}")
              }
          }
          .toList()

      // because the content can be a byte array we have to manually tests the results.
      // Using Truth to check the content of the list isn't going to work, unless we check
      // just the keys
      // check the keys
      Truth.assertWithMessage("list of ${instance.location}")
        .that(nameToContentActual.map { it.first })
        .containsExactlyElementsIn(expectedNameAndValues.map { it.first })
      nameToContentActual.forEach { (key, value) ->
        // this should not fail due to the check above
        val expectedValue = expectedContentMap[key] ?: fail("Can't find expected value for $key")

        val assertWithMsg = Truth.assertWithMessage("Value for '$key'")

        when (expectedValue) {
          is String -> assertWithMsg.that(value as String).isEqualTo(expectedValue)
          is ByteArray -> assertWithMsg.that(value as ByteArray).isEqualTo(expectedValue)
          else -> fail("Unexpected actual type for key: $key") // should not happen, already checked above
        }
      }
    }

    override fun toString(): String {
      return "DirectGradleProjectFiles"
    }
  }

  /** checker for [DelayedGradleProjectFiles] after call to [DelayedGradleProjectFiles.makeDirect] */
  private class DelayedMadeDirectChecker : ImplementationChecker {
    override fun getInstance(location: Path): GradleProjectFiles = DelayedGradleProjectFiles().also { it.makeDirect(location) }

    override fun checkEmpty(instance: GradleProjectFiles) {
      instance as? DelayedGradleProjectFiles ?: throw RuntimeException("Wrong instance type")
      val file = instance.directFiles?.location?.toFile() ?: throw RuntimeException("DelayedGradleProjectFiles.directFiles is null")

      Truth.assertWithMessage("$file is empty").that(file.list()!!).isEmpty()
    }

    override fun checkContent(instance: GradleProjectFiles, expectedNameAndValues: List<Pair<String, Any>>) {
      val expectedContentMap = expectedNameAndValues.associateBy({ it.first }, { it.second })

      instance as? DelayedGradleProjectFiles ?: throw RuntimeException("Wrong instance type")
      val file = instance.directFiles?.location?.toFile() ?: throw RuntimeException("DelayedGradleProjectFiles.directFiles is null")

      val nameToContentActual =
        file
          .walkTopDown()
          .filter { it.isFile }
          .map { it ->
            val relativePath = it.relativeTo(file).invariantSeparatorsPath
            val actual = expectedContentMap[relativePath] ?: fail("unexpected result: ${it.name}")
            relativePath to
              when (actual) {
                is String -> it.readText()
                is ByteArray -> it.readBytes()
                else -> fail("Unexpected actual type for key: ${it.name}")
              }
          }
          .toList()

      // because the content can be a byte array we have to manually tests the results.
      // Using Truth to check the content of the list isn't going to work, unless we check
      // just the keys
      // check the keys
      Truth.assertWithMessage("list of $file")
        .that(nameToContentActual.map { it.first })
        .containsExactlyElementsIn(expectedNameAndValues.map { it.first })
      nameToContentActual.forEach { (key, value) ->
        // this should not fail due to the check above
        val expectedValue = expectedContentMap[key] ?: fail("Can't find expected value for $key")

        val assertWithMsg = Truth.assertWithMessage("Value for '$key'")

        when (expectedValue) {
          is String -> assertWithMsg.that(value as String).isEqualTo(expectedValue)
          is ByteArray -> assertWithMsg.that(value as ByteArray).isEqualTo(expectedValue)
          else -> fail("Unexpected actual type for key: $key") // should not happen, already checked above
        }
      }
    }

    override fun toString(): String {
      return "DelayedGradleProjectFiles.makeDirect()"
    }
  }

  companion object {
    @Parameterized.Parameters(name = "impl = {0}")
    @JvmStatic
    fun data(): List<ImplementationChecker> {
      return listOf(DelayedChecker(), DirectChecker(), DelayedMadeDirectChecker())
    }
  }

  @get:Rule val temporaryFolder = TemporaryFolder()

  @get:Rule val exceptionRule: ExpectedException = ExpectedException.none()

  @Test
  fun add() {
    val files = getInstance()
    files.add("foo", "bar")
    files.add("foo2", "bar2")
    checker.checkContent(files, "foo" to "bar", "foo2" to "bar2")
  }

  @Test
  fun addBytes() {
    val contentArray = "some content".toByteArray()
    val files = getInstance()
    files.add("foo", contentArray)
    checker.checkContent(files, "foo" to contentArray)
  }

  @Test
  fun addExisting() {
    val files = getInstance()
    files.add("foo", "bar")
    exceptionRule.expect(RuntimeException::class.java)
    files.add("foo", "bar2")
  }

  @Test
  fun addExistingBytes() {
    val files = getInstance()
    files.add("foo", "some content".toByteArray())
    exceptionRule.expect(RuntimeException::class.java)
    files.add("foo", "other content".toByteArray())
  }

  @Test
  fun addExistingHybrid() {
    val files = getInstance()
    files.add("foo", "bar")
    exceptionRule.expect(RuntimeException::class.java)
    files.add("foo", "bar2".toByteArray())
  }

  @Test
  fun addExistingHybridReversed() {
    val files = getInstance()
    files.add("foo", "bar".toByteArray())
    exceptionRule.expect(RuntimeException::class.java)
    files.add("foo", "bar2")
  }

  @Test
  fun remove() {
    val files = getInstance()
    files.add("foo", "bar")
    files.remove("foo")
    checker.checkEmpty(files)
  }

  @Test
  fun removeMissingFile() {
    val files = getInstance()
    files.remove("foo")
    checker.checkEmpty(files)
  }

  @Test
  fun append() {
    val files = getInstance()
    files.add("foo", "bar")
    files.update("foo").append("bar")
    checker.checkContent(files, "foo" to "barbar")
  }

  @Test
  fun appendMissingFile() {
    val files = getInstance()
    files.update("foo").append("bar")
    checker.checkContent(files, "foo" to "bar")
  }

  @Test
  fun replaceWith() {
    val files = getInstance()
    files.add("foo", "bar")
    files.update("foo").replaceWith("baz")
    checker.checkContent(files, "foo" to "baz")
  }

  @Test
  fun replaceMissingFile() {
    val files = getInstance()
    files.update("foo").replaceWith("baz")
    checker.checkContent(files, "foo" to "baz")
  }

  @Test
  fun searchAndReplace() {
    val files = getInstance()
    files.add("foo", "some text with some content")
    files.update("foo").searchAndReplace("some", "my")
    checker.checkContent(files, "foo" to "my text with my content")
  }

  @Test
  fun searchAndReplaceWithRegex() {
    val files = getInstance()
    files.add("foo", "<color></colour>")
    files.update("foo").searchAndReplace(Regex("colou?r"), "blue")
    checker.checkContent(files, "foo" to "<blue></blue>")
  }

  @Test
  fun searchAndReplaceMissingFile() {
    val files = getInstance()
    exceptionRule.expect(RuntimeException::class.java)
    files.update("foo").searchAndReplace("some", "my")
  }

  @Test
  fun searchAndReplaceWithNoOccurrences() {
    val files = getInstance()
    files.add("foo", "some text with some content")
    exceptionRule.expect(AssertionError::class.java)
    files.update("foo").searchAndReplace("zzzz", "my")
  }

  @Test
  fun searchAndReplaceWithNoOccurrencesButLenient() {
    val files = getInstance()
    files.add("foo", "some text with some content")
    files.update("foo").searchAndReplace("zzzz", "my", lenient = true)
    checker.checkContent(files, "foo" to "some text with some content")
  }

  @Test
  fun searchAndReplaceAndAppend() {
    val files = getInstance()
    files.add("foo", "some text with some content")
    files.update("foo").searchAndReplace("some", "my").append(" with more content again")
    checker.checkContent(files, "foo" to "my text with my content with more content again")
  }

  @Test
  fun transform() {
    val files = getInstance()
    files.add("foo", "some text with some content")
    files.update("foo").transform { "/*$it*/" }
    checker.checkContent(files, "foo" to "/*some text with some content*/")
  }

  @Test
  fun transformMissingFile() {
    val files = getInstance()
    exceptionRule.expect(RuntimeException::class.java)
    files.update("foo").transform { "/*$it*/" }
  }

  @Test
  fun updateAction() {
    val files = getInstance()
    files.add("foo", "some text with some content")
    files.update("foo") { searchAndReplace("some", "my") }
    checker.checkContent(files, "foo" to "my text with my content")
  }

  @Test
  fun updateActionWithLogic() {
    val files = getInstance()
    files.update("foo") {
      if (!exists) {
        replaceWith("some text with some content")
      } else {
        throw RuntimeException("should not have content")
      }
    }
    checker.checkContent(files, "foo" to "some text with some content")

    files.update("foo") {
      if (!exists) {
        throw RuntimeException("should have content")
      } else {
        searchAndReplace("some", "my")
      }
    }

    checker.checkContent(files, "foo" to "my text with my content")
  }

  @Test
  fun updateMoveTo() {
    val files = getInstance()
    files.add("original/path/to/a.txt", "content")
    files.update("original/path/to/a.txt").moveTo("new/path/to/a.txt")
    checker.checkContent(files, "new/path/to/a.txt" to "content")
  }

  @Test
  fun updateReplaceWithBytes() {
    val files = getInstance()
    files.add("res/a.raw", byteArrayOf(0, 1))
    val replacement = byteArrayOf(2, 3, 4)
    files.update("res/a.raw").replaceWith(replacement)
    checker.checkContent(files, "res/a.raw" to replacement)
  }

  @Test
  fun addJavaMethod() {
    val files = getInstance()

    files.add(
      "Main.java",
      """
      package pkg;

      class Main {
          void method1() {
          }
      }
      """
        .trimIndent(),
    )
    files.update("Main.java").appendMethod("public int useFoo() { return R.id.foo; }")
    checker.checkContent(
      files,
      "Main.java" to
        // language=java
        """
        package pkg;

        class Main {
            void method1() {
            }
            public int useFoo() { return R.id.foo; }

        }
        """
          .trimIndent(),
    )
  }

  @Test
  fun addKotlinMethod() {
    val files = getInstance()

    files.add(
      "Main.kt",
      // language=kotlin
      """
      package pkg

      class Main {
          fun method1() {
          }
      }
      """
        .trimIndent(),
    )
    files.update("Main.kt").appendMethod("fun useFoo(): Int = R.id.foo")

    checker.checkContent(
      files,
      "Main.kt" to
        // language=kotlin
        """
        package pkg

        class Main {
            fun method1() {
            }
            fun useFoo(): Int = R.id.foo

        }
        """
          .trimIndent(),
    )

    files.update("Main.kt").appendMethod("fun getBar(): String { return \"bar\" }")

    checker.checkContent(
      files,
      "Main.kt" to
        // language=kotlin
        """
        package pkg

        class Main {
            fun method1() {
            }
            fun useFoo(): Int = R.id.foo

            fun getBar(): String { return "bar" }

        }
        """
          .trimIndent(),
    )
  }

  private fun getInstance() = checker.getInstance(temporaryFolder.newFolder().toPath())
}
