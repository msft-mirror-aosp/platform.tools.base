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

package com.android.build.gradle.internal.tasks

import com.android.build.api.variant.impl.SigningConfigImpl
import com.android.build.gradle.internal.fixtures.FakeGradleProvider
import com.android.build.gradle.internal.fixtures.FakeNoOpAnalyticsService
import com.android.build.gradle.internal.signing.SigningConfigDataProvider
import com.android.builder.signing.DefaultSigningConfig
import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.hash.Hashing
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.testfixtures.ProjectBuilder
import org.junit.AfterClass
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

const val PRE_BUILD_TASKNAME = "preBuildTask"

class ValidateSigningTaskTest {

  companion object {
    @ClassRule @JvmField var temporaryFolder = TemporaryFolder()
    private var project: Project? = null

    @BeforeClass
    @JvmStatic
    fun createProject() {
      project =
        ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build().also { project ->
          project.tasks.register(PRE_BUILD_TASKNAME)
        }
    }

    @AfterClass
    @JvmStatic
    fun cleanup() {
      project = null
    }
  }

  lateinit var outputDirectory: File
  private lateinit var defaultDebugKeystore: File

  @Before
  fun createDebugKeystoreFile() {
    defaultDebugKeystore = File(temporaryFolder.newFolder(), "debug.keystore")
    outputDirectory = temporaryFolder.newFolder()
  }

  @Test
  fun testErrorIfNoKeystoreFileSet() {
    val task = project!!.tasks.create("validateSigning", ValidateSigningTask::class.java)
    task.dummyOutputDirectory.set(outputDirectory)
    task.analyticsService.set(FakeNoOpAnalyticsService())

    val signingConfig = mock<SigningConfigImpl>()
    whenever(signingConfig.name).thenReturn("release")
    whenever(signingConfig.storeFile).thenReturn(FakeGradleProvider(null))
    whenever(signingConfig.storePassword).thenReturn(FakeGradleProvider("store password"))
    whenever(signingConfig.keyAlias).thenReturn(FakeGradleProvider("key alias"))
    whenever(signingConfig.keyPassword).thenReturn(FakeGradleProvider("key password"))
    whenever(signingConfig.storeType).thenReturn(FakeGradleProvider(null))
    task.signingConfig.set(
      createSigningConfigDataProvider(
        name = FakeGradleProvider(signingConfig.name.orEmpty()),
        storeFile = signingConfig.storeFile,
        storeType = signingConfig.storeType,
        keyAlias = signingConfig.keyAlias,
        storePassword = signingConfig.storePassword,
        keyPassword = signingConfig.keyPassword,
      )
    )

    assertThat(task.forceRerun()).named("forceRerun").isTrue()
    // If no config file set, throws InvalidUserDataException
    try {
      task.actions.single().execute(task)
      fail("Expected failure")
    } catch (e: InvalidUserDataException) {
      assertThat(e).hasMessageThat().isEqualTo("Keystore file not set for signing config release")
    }
  }

  @Test
  fun testErrorIfCustomKeystoreFileDoesNotExist() {
    val task = project!!.tasks.create("validateGreenSigning", ValidateSigningTask::class.java)

    val signingConfig = mock<SigningConfigImpl>()
    whenever(signingConfig.name).thenReturn("release")
    whenever(signingConfig.storeFile).thenReturn(FakeGradleProvider(File(temporaryFolder.newFolder(), "does_not_exist")))
    whenever(signingConfig.storePassword).thenReturn(FakeGradleProvider("store password"))
    whenever(signingConfig.keyAlias).thenReturn(FakeGradleProvider("key alias"))
    whenever(signingConfig.keyPassword).thenReturn(FakeGradleProvider("key password"))
    whenever(signingConfig.storeType).thenReturn(FakeGradleProvider(null))
    task.signingConfig.set(
      createSigningConfigDataProvider(
        name = FakeGradleProvider(signingConfig.name.orEmpty()),
        storeFile = signingConfig.storeFile,
        storeType = signingConfig.storeType,
        keyAlias = signingConfig.keyAlias,
        storePassword = signingConfig.storePassword,
        keyPassword = signingConfig.keyPassword,
      )
    )

    task.dummyOutputDirectory.set(outputDirectory)
    task.analyticsService.set(FakeNoOpAnalyticsService())

    assertThat(task.forceRerun()).named("forceRerun").isTrue()
    // If no config file set, throws InvalidUserDataException
    try {
      task.actions.single().execute(task)
      fail("Expected failure")
    } catch (e: InvalidUserDataException) {
      assertThat(e.message).matches("^Keystore file .* not found for signing config 'release'.$")
    }
  }

  @Test
  fun testDefaultDebugKeystoreIsCreatedAutomatically() {
    val task = project!!.tasks.create("validateRedSigning", ValidateSigningTask::class.java)
    val signingConfig = mock<SigningConfigImpl>()
    whenever(signingConfig.name).thenReturn("debug")
    whenever(signingConfig.storeFile).thenReturn(FakeGradleProvider(defaultDebugKeystore))
    whenever(signingConfig.storePassword).thenReturn(FakeGradleProvider(DefaultSigningConfig.DEFAULT_PASSWORD))
    whenever(signingConfig.keyAlias).thenReturn(FakeGradleProvider(DefaultSigningConfig.DEFAULT_ALIAS))
    whenever(signingConfig.keyPassword).thenReturn(FakeGradleProvider(DefaultSigningConfig.DEFAULT_PASSWORD))
    whenever(signingConfig.storeType).thenReturn(FakeGradleProvider(KeyStore.getDefaultType()))
    whenever(signingConfig.hasConfig()).thenReturn(true)

    task.signingConfig.set(
      createSigningConfigDataProvider(
        name = FakeGradleProvider(signingConfig.name.orEmpty()),
        storeFile = signingConfig.storeFile,
        storeType = signingConfig.storeType,
        keyAlias = signingConfig.keyAlias,
        storePassword = signingConfig.storePassword,
        keyPassword = signingConfig.keyPassword,
      )
    )
    task.dummyOutputDirectory.set(outputDirectory)
    task.analyticsService.set(FakeNoOpAnalyticsService())
    task.defaultDebugKeystoreLocation.set(defaultDebugKeystore)

    // Sanity check
    assertThat(defaultDebugKeystore).doesNotExist()
    assertThat(task.forceRerun()).named("forceRerun").isTrue()

    // Run task action to generate debug keystore.
    task.actions.single().execute(task)

    // Check the keystore is created
    assertThat(defaultDebugKeystore.toPath()).isFile()
    assertThat(task.forceRerun()).named("forceRerun").isFalse()
    // Check that re-run does not rewrite the keystore. The keystore contains cryptographic
    // keys, so it will be different each time it is generated.
    val debugKeystoreHash = Hashing.sha512().hashBytes(Files.readAllBytes(defaultDebugKeystore.toPath()))
    task.actions.single().execute(task)
    assertThat(Hashing.sha512().hashBytes(Files.readAllBytes(defaultDebugKeystore.toPath()))).isEqualTo(debugKeystoreHash)
    assertThat(task.forceRerun()).named("forceRerun").isFalse()

    // Check that a new, different keystore can be created if it is deleted.
    Files.delete(defaultDebugKeystore.toPath())
    assertThat(task.forceRerun()).named("forceRerun").isTrue()
    task.actions.single().execute(task)
    assertThat(Hashing.sha512().hashBytes(Files.readAllBytes(defaultDebugKeystore.toPath()))).isNotEqualTo(debugKeystoreHash)
  }

  private fun createSigningConfigDataProvider(
    name: Provider<String>,
    storeFile: Provider<File>,
    storeType: Provider<String>,
    keyAlias: Provider<String>,
    storePassword: Provider<String>,
    keyPassword: Provider<String>,
  ): SigningConfigDataProvider {
    val proj = project!!
    val provider = proj.objects.newInstance(SigningConfigDataProvider::class.java)
    with(proj.providers) {
      provider.name.set(provider<String> { name.orNull })
      provider.storeFile.set(provider<File> { storeFile.orNull })
      provider.storeType.set(provider<String> { storeType.orNull })
      provider.keyAlias.set(provider<String> { keyAlias.orNull })
      provider.storePassword.set(provider<String> { storePassword.orNull })
      provider.keyPassword.set(provider<String> { keyPassword.orNull })
    }
    return provider
  }
}
