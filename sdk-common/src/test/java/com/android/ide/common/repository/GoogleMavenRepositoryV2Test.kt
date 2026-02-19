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
package com.android.ide.common.repository

import com.android.ide.common.gradle.Dependency
import com.android.ide.common.gradle.RichVersion
import com.android.ide.common.gradle.Version
import com.android.ide.common.resources.BaseTestCase
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.function.Predicate
import java.util.zip.GZIPOutputStream
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Before
import org.junit.Test

class GoogleMavenRepositoryV2Test : BaseTestCase() {

  // Mocks the Google Maven Repository to return sample data.
  private lateinit var mockMavenRepository: GoogleMavenRepositoryV2

  // Test Google Maven Repository that uses files stored in the /testdata folder to return package
  // related data.
  private lateinit var testMavenRepository: GoogleMavenRepositoryV2
  private var errorMessage: String? = null
  private var readDataCallCount = 0

  @Before
  fun setUp() {
    errorMessage = null
    readDataCallCount = 0
    mockMavenRepository = GoogleMavenRepositoryV2.create(FakeGoogleMavenRepositoryV2Host())
    testMavenRepository =
      GoogleMavenRepositoryV2.create(
        object : GoogleMavenRepositoryV2Host {
          override val cacheDir: Path? = null

          override fun readUrlData(url: String, timeout: Int, lastModified: Long): NetworkCache.ReadUrlDataResult =
            throw IllegalStateException("Should not be called")

          override fun readDefaultData(relative: String): InputStream? {
            readDataCallCount++
            return GoogleMavenRepositoryV2Test::class.java.getResourceAsStream("/testData/$relative")
          }

          override fun error(throwable: Throwable, message: String?) {
            errorMessage = message
          }
        }
      )
  }

  @Test
  fun findVersion_withOfflineVersions_returnsVersion() {
    val offlineMavenRepository =
      GoogleMavenRepositoryV2.create(
        object : GoogleMavenRepositoryV2Host {
          override val cacheDir: Path? = null

          override fun readUrlData(url: String, timeout: Int, lastModified: Long): NetworkCache.ReadUrlDataResult =
            throw IllegalStateException("Should not be called")

          override fun error(throwable: Throwable, message: String?) {}
        }
      )

    assertNotNull(offlineMavenRepository.findVersion("android.arch.core", "core-testing", null as Predicate<Version>?, compileSdk = 0))
  }

  /**
   * Verifies that `findVersion` can successfully parse the Lorry index file and find versions even if the JSON data contains unknown fields
   * not defined in the data classes. This ensures forward compatibility if the index schema evolves.
   */
  @Test
  fun findVersion_withUnknownField_succeeds() {
    val offlineMavenRepository =
      GoogleMavenRepositoryV2.create(
        object : GoogleMavenRepositoryV2Host {
          override val cacheDir: Path? = null

          override fun readUrlData(url: String, timeout: Int, lastModified: Long): NetworkCache.ReadUrlDataResult =
            throw IllegalStateException("Should not be called")

          override fun readDefaultData(relative: String): InputStream? {
            val samplePackages =
              """
              {
                "packages": [
                  {
                    "packageId": "androidx.activity",
                    "unknownPackageField": "abc",
                    "artifacts": [
                      {
                        "artifactId": "activity",
                        "unknownArtifactField": "abc",
                        "versions": [
                          {
                            "version": "1.0.0"
                          },
                          {
                            "version": "1.2.0",
                            "properties": {
                              "minCompileSdk": "36",
                              "minCompileSdkExtension": "0",
                              "aarMetadataVersion": "1.0",
                              "aarFormatVersion": "1.0",
                              "coreLibraryDesugaringEnabled": "false",
                              "minAndroidGradlePluginVersion": "8.9.1",
                              "unknownVersionField": "abc"
                            }
                          },
                          {
                            "version": "1.3.0",
                            "properties": {
                              "unknownField": "8.9.1"
                            }
                          }
                        ]
                      }
                    ]
                  }
                ]
              }
              """
                .trimIndent()
            val byteArrayOutputStream = ByteArrayOutputStream()
            GZIPOutputStream(byteArrayOutputStream).use { gzipOutputStream ->
              gzipOutputStream.write(samplePackages.toByteArray(Charsets.UTF_8))
            }
            return ByteArrayInputStream(byteArrayOutputStream.toByteArray())
          }

          override fun error(throwable: Throwable, message: String?) {}
        }
      )

    assertNotNull(offlineMavenRepository.findVersion("androidx.activity", "activity", null as Predicate<Version>?, compileSdk = 0))
  }

  @Test
  fun findVersion_withMinCompileSdk_returnsCompatibleVersion() {
    val offlineMavenRepository =
      GoogleMavenRepositoryV2.create(
        object : GoogleMavenRepositoryV2Host {
          override val cacheDir: Path? = null

          override fun readUrlData(url: String, timeout: Int, lastModified: Long): NetworkCache.ReadUrlDataResult =
            throw IllegalStateException("Should not be called")

          override fun readDefaultData(relative: String): InputStream? {
            val samplePackages =
              """
              {
                "packages": [
                  {
                    "packageId": "androidx.activity",
                    "artifacts": [
                      {
                        "artifactId": "activity",
                        "versions": [
                          {
                            "version": "1.0.0"
                          },
                          {
                            "version": "1.2.0",
                            "properties": {
                              "minCompileSdk": "36",
                              "minCompileSdkExtension": "0",
                              "aarMetadataVersion": "1.0",
                              "aarFormatVersion": "1.0",
                              "coreLibraryDesugaringEnabled": "false",
                              "minAndroidGradlePluginVersion": "8.9.1"
                            }
                          }
                        ]
                      }
                    ]
                  }
                ]
              }
              """
                .trimIndent()
            val byteArrayOutputStream = ByteArrayOutputStream()
            GZIPOutputStream(byteArrayOutputStream).use { gzipOutputStream ->
              gzipOutputStream.write(samplePackages.toByteArray(Charsets.UTF_8))
            }
            return ByteArrayInputStream(byteArrayOutputStream.toByteArray())
          }

          override fun error(throwable: Throwable, message: String?) {}
        }
      )

    assertEquals(
      Version.parse("1.0.0"),
      offlineMavenRepository.findVersion("androidx.activity", "activity", null as Predicate<Version>?, compileSdk = 35),
    )
  }

  @Test
  fun findVersion_withNullPredicate_returnsVersion() {
    assertEquals(
      mockMavenRepository.findVersion("com.android.support", "appcompat", null as Predicate<Version>?, compileSdk = 0),
      Version.parse("1.0.0"),
    )
  }

  @Test
  fun findVersion_withGroupIdArtifactIdAndPredicate_returnsVersion() {
    assertEquals(
      mockMavenRepository.findVersion("com.android.support", "appcompat", Predicate { true }, compileSdk = 0),
      Version.parse("1.0.0"),
    )
  }

  @Test
  fun findVersion_withMissingGroup_returnsNull() {
    assertNull(mockMavenRepository.findVersion("com.android.missing", "appcompat", { true }, compileSdk = 0))
  }

  @Test
  fun findVersion_withMissingArtifact_returnsNull() {
    assertNull(mockMavenRepository.findVersion("com.android.support", "missing", { true }, compileSdk = 0))
  }

  @Test
  fun findVersion_withNullFilter_returnsVersion() {
    assertEquals(
      mockMavenRepository.findVersion("com.android.support", "appcompat", null as ((Version) -> Boolean)?, compileSdk = 0),
      Version.parse("1.0.0"),
    )
  }

  @Test
  fun findVersion_withAllowPreview_returnsPreviewVersion() {
    assertEquals(
      mockMavenRepository.findVersion("com.android.support", "appcompat", null as ((Version) -> Boolean)?, true, compileSdk = 0),
      Version.parse("1.0.1-preview"),
    )
  }

  @Test
  fun findVersion_withGroupIdArtifactIdAndFilter_returnsVersion() {
    assertEquals(mockMavenRepository.findVersion("com.android.support", "appcompat", { true }, compileSdk = 0), Version.parse("1.0.0"))
  }

  @Test
  fun findDependencies_parsesPomFileForRuntime_Successfully() {
    assertContentEquals(
      testMavenRepository.findDependencies("androidx.activity", "activity-compose", Version.parse("1.10.1"), "runtime"),
      listOf(
        Dependency("org.jetbrains.kotlin", "kotlin-stdlib", RichVersion.parse("1.8.22")),
        Dependency("androidx.lifecycle", "lifecycle-common", RichVersion.parse("2.6.1")),
      ),
    )
  }

  @Test
  fun findDependencies_forRepeatedDependencyLookup_usesCache() {
    testMavenRepository.findDependencies("androidx.activity", "activity-compose", Version.parse("1.10.1"), "runtime")
    assertEquals(readDataCallCount, 1)

    testMavenRepository.findDependencies("androidx.activity", "activity-compose", Version.parse("1.10.1"), "runtime")

    assertEquals(readDataCallCount, 1)
  }

  @Test
  fun findDependencies_forMalformedFile_returnsEmpty() {
    assertThat(testMavenRepository.findDependencies("androidx.activity", "activity-compose", Version.parse("1.10.2"), "runtime")).hasSize(0)
    assertNotNull(errorMessage)
    assertContains(errorMessage!!, "Problem reading POM file")
  }

  @Test
  fun findCompileDependencies_parsesPomFile_Successfully() {
    assertThat(testMavenRepository.findCompileDependencies("androidx.activity", "activity-compose", Version.parse("1.10.1"))).hasSize(6)
  }
}
