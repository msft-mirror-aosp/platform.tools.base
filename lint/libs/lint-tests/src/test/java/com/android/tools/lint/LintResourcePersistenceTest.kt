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

package com.android.tools.lint

import com.android.ide.common.rendering.api.AttributeFormat
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.StyleableResourceValue
import com.android.resources.ResourceType
import com.android.testutils.TestUtils
import com.android.tools.lint.LintResourceRepository.Companion.get
import com.android.tools.lint.checks.infrastructure.TestFiles.manifest
import com.android.tools.lint.checks.infrastructure.TestLintClient
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.android.tools.lint.client.api.LintClient
import com.android.tools.lint.client.api.ResourceRepositoryScope
import com.android.tools.lint.detector.api.Issue.IgnoredIdProvider
import com.android.tools.lint.detector.api.Location.LocationAware
import com.android.tools.lint.detector.api.Project
import com.android.tools.lint.model.PathVariables
import com.android.utils.Base128InputStream.StreamFormatException
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Persistence is also tested a bit in [LintResourceRepositoryTest] */
class LintResourcePersistenceTest {
  @get:Rule var temporaryFolder = TemporaryFolder()

  @Before
  fun setUp() {
    LintClient.clientName = LintClient.CLIENT_UNIT_TESTS
  }

  private fun getPathVariables(): PathVariables {
    val pathVariables = PathVariables()
    pathVariables.add("ROOT", temporaryFolder.root)
    return pathVariables
  }

  private fun createRepository(vararg files: Pair<String, String>): LintResourceRepository {
    val res = File(temporaryFolder.root, "app/res")
    for ((path, contents) in files) {
      val file = File(res, path)
      file.parentFile?.mkdirs()
      file.writeText(contents.trimIndent())
    }
    val client = LintCliClient(LintClient.CLIENT_UNIT_TESTS)
    return LintResourceRepository.createFromFolder(client, sequenceOf(res), null, null, ResourceNamespace.TODO())
  }

  @Test
  fun testRoundTrip() {
    val repository =
      createRepository(
        "values-b+sr+Latn/values.xml" to
          """
          <resources xmlns:tools="http://schemas.android.com/tools">
              <string name="string1" tools:ignore="Typos">Værdi 1</string>
              <declare-styleable name="ContentFrame">
                  <attr name="content" format="reference" />
                  <attr name="contentId" format="reference" />
                  <attr name="windowSoftInputMode">
                      <flag name="stateUnspecified" value="0" />
                      <flag name="stateUnchanged" value="1" />
                  </attr>
                  <attr name="fastScrollOverlayPosition">
                      <enum name="floating" value="0" />
                      <enum name="atThumb" value="1" />
                      <enum name="aboveThumb" value="2" />
                  </attr>
              </declare-styleable>
          </resources>
          """
      )

    val pathVariables = getPathVariables()
    val serialized = repository.serialize(pathVariables, null, sort = true)
    val deserialized = LintResourcePersistence.deserialize(serialized, pathVariables, null, null)

    // Equivalent content
    assertEquals(repository.prettyPrint(temporaryFolder.root), deserialized.prettyPrint(temporaryFolder.root))

    // Spot check the lazily computed resource values in the deserialized repository
    val styleable =
      deserialized.getResources(ResourceNamespace.TODO(), ResourceType.STYLEABLE, "ContentFrame").single().resourceValue
        as StyleableResourceValue
    val attrs = styleable.allAttributes.associateBy { it.name }
    assertEquals(setOf(AttributeFormat.REFERENCE), attrs["content"]?.formats)
    assertEquals(mapOf("stateUnspecified" to 0, "stateUnchanged" to 1), attrs["windowSoftInputMode"]?.attributeValues)
    assertEquals(mapOf("floating" to 0, "atThumb" to 1, "aboveThumb" to 2), attrs["fastScrollOverlayPosition"]?.attributeValues)

    // The tools:ignore attribute is persisted
    val string = deserialized.getResources(ResourceNamespace.TODO(), ResourceType.STRING, "string1").single()
    assertEquals("Typos", (string as IgnoredIdProvider).getIgnoredIds())
    assertEquals("Værdi 1", string.resourceValue?.value)

    // Positions are persisted
    val original = repository.getResources(ResourceNamespace.TODO(), ResourceType.STRING, "string1").single()
    val originalLocation = (original as LocationAware).getLocation()
    val deserializedLocation = (string as LocationAware).getLocation()
    assertEquals(originalLocation.start?.offset, deserializedLocation.start?.offset)
    assertEquals(originalLocation.start?.line, deserializedLocation.start?.line)
    assertEquals(originalLocation.end?.offset, deserializedLocation.end?.offset)

    // Serialization is deterministic and round-trip stable
    val reserialized = (deserialized as LintResourceRepository).serialize(pathVariables, null, sort = true)
    assertTrue("Serialization is not round-trip stable", serialized.contentEquals(reserialized))
  }

  @Test
  fun testLargeFileOffsets() {
    // Regression test for b/533056758: offsets above 64K used to be truncated to 16 bits
    // by the previous (text-based) persistence format, leading to corrupt locations (and
    // downstream crashes, e.g. in SarifReporter) when serializing large values files.
    val padding = "x".repeat(70000)
    val repository =
      createRepository(
        "values/values.xml" to
          """
          <resources>
              <!-- $padding -->
              <string name="big">Big</string>
          </resources>
          """
      )

    val original = repository.getResources(ResourceNamespace.TODO(), ResourceType.STRING, "big").single()
    val originalStart = (original as LocationAware).getLocation().start!!
    assertTrue("Test setup problem: expected offset above 64K, was ${originalStart.offset}", originalStart.offset > 0xFFFF)

    val pathVariables = getPathVariables()
    val serialized = repository.serialize(pathVariables, null, sort = true)
    val deserialized = LintResourcePersistence.deserialize(serialized, pathVariables, null, null)

    val restored = deserialized.getResources(ResourceNamespace.TODO(), ResourceType.STRING, "big").single()
    val location = (restored as LocationAware).getLocation()
    assertEquals(originalStart.offset, location.start!!.offset)
    assertEquals(originalStart.line, location.start!!.line)
    assertEquals((original as LocationAware).getLocation().end!!.offset, location.end!!.offset)
  }

  @Test
  fun testInvalidContentRejected() {
    // Files in the old text-based format (or otherwise corrupt files) should be rejected
    // with an exception (which lint catches to gracefully recover by recreating the
    // repository; see LintResourceRepositoryTest#testCheckRecovery)
    val legacy =
      "http://schemas.android.com/apk/res-auto;;app/res/values-b\\+sr\\+Latn/values.xml," +
        "+styleable:ContentFrame,0,V42,50,;-content:reference:"
    assertThrows(StreamFormatException::class.java) {
      LintResourcePersistence.deserialize(legacy.toByteArray(), getPathVariables(), null, null)
    }

    // Empty content deserializes to the empty repository rather than throwing
    assertTrue(
      LintResourcePersistence.deserialize(ByteArray(0), getPathVariables(), null, null) === LintResourceRepository.Companion.EmptyRepository
    )
  }

  @Test
  fun testFrameworkResources() {
    // This test uses a massive amount of memory and fails when run from Gradle with
    //     java.lang.OutOfMemoryError at LintResourcePersistenceTest.kt:73
    // Just run this when running lint locally (and leave trace to remember to do this)
    if (System.getenv("INCLUDE_EXPENSIVE_LINT_TESTS") == null) {
      println("Skipping ${this.javaClass.simpleName}.testFrameworkResources: Resource intensive")
      return
    }

    // Serialize and deserialize the entire framework resource folder (which is massive
    // and uses pretty much all the resource capabilities) and diff the two

    val androidHome = TestUtils.getSdk().toFile().path ?: return

    val task = lint().files(manifest().minSdk(19)).sdkHome(File(androidHome))
    val client = TestLintClient()
    val dir = File(temporaryFolder.root, "framework-project")
    val projectDir = task.createProjects(dir)[0]
    client.setLintTask(task)

    val project = Project.create(client, projectDir, projectDir)
    client.registerProject(projectDir, project)
    project.directLibraries = emptyList()
    val folderRepository = get(client, project, ResourceRepositoryScope.ANDROID)

    // Test serialization too -- serialize and deserialize the repositories and
    // make sure they work the same
    val serialized = LintResourcePersistence.serialize(folderRepository as LintResourceRepository, client.pathVariables)
    val deserialized = LintResourcePersistence.deserialize(serialized, client.pathVariables)

    // If both methods returned empty string the above would equal, so also perform
    // some spot checks on the resource repositories.
    // Also wanted to compare this against the AGP resource repository, but
    // that repository is unable to handle framework resources (e.g. complains
    // about duplicate resource items which happens in the framework (with for example
    // alternate strings provided for phone vs tablet, attempting to read
    // <public> elements etc.

    for (resources in listOf(folderRepository, deserialized)) {
      assertFalse(resources.hasResources(ResourceNamespace.ANDROID, ResourceType.STRING, "ok123"))
      val okItems = resources.getResources(ResourceNamespace.ANDROID, ResourceType.STRING, "ok")
      assertTrue(okItems.size > 50)
      val defaultOk = okItems.first { it.configuration.isDefault }
      assertEquals("OK", defaultOk.resourceValue?.value)

      val smsItems = resources.getResources(ResourceNamespace.ANDROID, ResourceType.STRING, "sms_short_code_details")
      val smsEn = smsItems.first() { it.configuration.isDefault }
      // Note -- this string can change in the platform; if it does, update the test
      // to match it.
      assertEquals("This may cause charges on your mobile account.", smsEn.resourceValue!!.value)
      assertEquals("This <b>may cause charges</b> on your mobile account.", smsEn.resourceValue!!.rawXmlValue)
      val smsNo = smsItems.first() { it.configuration.localeQualifier?.value == "nb" }
      assertEquals("Dette kan føre til kostnader på mobilabonnementet ditt.", smsNo.resourceValue!!.value)
      assertEquals("\"Dette \"<b>\"kan føre til kostnader\"</b>\" på mobilabonnementet ditt.\"", smsNo.resourceValue!!.rawXmlValue)

      val mimeItems = resources.getResources(ResourceNamespace.ANDROID, ResourceType.STRING, "mime_type_document_ext")
      val mimeEn = mimeItems.first() { it.configuration.isDefault }
      assertEquals("(PDF) document", mimeEn.resourceValue!!.value)
      assertEquals("<xliff:g example=\"PDF\" id=\"extension\">%1\$s</xliff:g> document", mimeEn.resourceValue!!.rawXmlValue)
    }

    // Make sure all the locales are present too; there's something like 86 translations of this
    // one:
    assertEquals(
      folderRepository.getResources(ResourceNamespace.ANDROID, ResourceType.STRING, "ok").size,
      deserialized.getResources(ResourceNamespace.ANDROID, ResourceType.STRING, "ok").size,
    )

    val deserializedPrint = deserialized.prettyPrint()
    val folderPrint = folderRepository.prettyPrint()
    assertEquals(folderPrint, deserializedPrint)
  }
}
