/*
 * Copyright (C) 2018 The Android Open Source Project
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

import com.android.build.gradle.internal.services.getBuildService
import com.android.build.gradle.internal.signing.SigningConfigData
import com.android.build.gradle.internal.signing.SigningConfigEncryptionBuildService
import com.android.build.gradle.internal.utils.setDisallowChanges
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Tests for the [SigningConfigWriterTask] */
class SigningConfigWriterTaskTest {
  @Rule @JvmField var temporaryFolder = TemporaryFolder()

  internal lateinit var project: Project
  internal lateinit var task: SigningConfigWriterTask
  lateinit var outputFile: File

  @Before
  @Throws(IOException::class)
  fun setUp() {
    val testDir = temporaryFolder.newFolder()
    outputFile = temporaryFolder.newFile()
    project = ProjectBuilder.builder().withProjectDir(testDir).build()

    task = project.tasks.create("test", SigningConfigWriterTask::class.java)
    UsesSigningConfigEncryptionBuildService.ConfigureAction.configure(task)
    task.outputFile.set(outputFile)
  }

  @Test
  @Throws(IOException::class)
  fun testTask() {
    task.signingConfigData.set(
      SigningConfigData(
        name = "signingConfig_name",
        storePassword = "foobar",
        storeFile = null,
        keyAlias = null,
        keyPassword = null,
        storeType = null,
      )
    )

    task.doTaskAction()

    val encryptionService =
      getBuildService<SigningConfigEncryptionBuildService, SigningConfigEncryptionBuildService.Params>(project.gradle.sharedServices).get()
    assertThat(task.encryptionKeyId.get()).isEqualTo(encryptionService.getKeyId())

    val rawJson = outputFile.readText(StandardCharsets.UTF_8)
    assertThat(rawJson).doesNotContain("foobar")

    val loadedSigningConfigData = SigningConfigUtils.loadSigningConfigData(outputFile, encryptionService)
    assertThat(loadedSigningConfigData).isEqualTo(task.signingConfigData.get())
  }

  @Test
  fun testEncryptionServiceSharesKeyAcrossSubprojects() {
    val rootProject = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build()
    val appProject = ProjectBuilder.builder().withName("app").withParent(rootProject).build()
    val featureProject = ProjectBuilder.builder().withName("feature1").withParent(rootProject).build()

    val writerTask = appProject.tasks.register("signingConfigWriterRelease", SigningConfigWriterTask::class.java).get()
    UsesSigningConfigEncryptionBuildService.ConfigureAction.configure(writerTask)
    val featureServiceProvider = SigningConfigEncryptionBuildService.RegistrationAction(featureProject).execute()

    val appService =
      getBuildService<SigningConfigEncryptionBuildService, SigningConfigEncryptionBuildService.Params>(appProject.gradle.sharedServices)
        .get()
    val featureService = featureServiceProvider.get()
    assertThat(appService.getKeyId()).isEqualTo(featureService.getKeyId())

    val sharedOutputFile = temporaryFolder.newFile()
    writerTask.outputFile.set(sharedOutputFile)
    writerTask.signingConfigData.set(
      SigningConfigData(
        name = "release",
        storeType = "jks",
        storeFile = temporaryFolder.newFile(),
        storePassword = "secretStorePassword",
        keyAlias = "releaseKey",
        keyPassword = "secretKeyPassword",
      )
    )
    writerTask.doTaskAction()

    val decryptedFromFeature = SigningConfigUtils.loadSigningConfigData(sharedOutputFile, featureService)
    assertThat(decryptedFromFeature).isEqualTo(writerTask.signingConfigData.get())
  }

  @Test
  fun testTaskWithoutPasswordsDoesNotInitializeEncryptionServices() {
    val keyGenerationCount = AtomicInteger(0)
    val isolatedProject = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build()
    val isolatedTask = isolatedProject.tasks.register("signingConfigWriterRelease", SigningConfigWriterTask::class.java).get()
    val serviceProvider =
      SigningConfigEncryptionBuildService.RegistrationAction(isolatedProject) {
          keyGenerationCount.incrementAndGet()
          SigningConfigEncryptionBuildService.generateEncodedKey()
        }
        .execute()
    isolatedTask.signingConfigEncryptionService.setDisallowChanges(serviceProvider)
    val isolatedOutput = temporaryFolder.newFile()
    isolatedTask.outputFile.set(isolatedOutput)

    isolatedTask.signingConfigData.set(
      SigningConfigData(
        name = "unsigned",
        storeType = "jks",
        storeFile = temporaryFolder.newFile(),
        storePassword = null,
        keyAlias = "releaseKey",
        keyPassword = null,
      )
    )

    assertThat(keyGenerationCount.get()).isEqualTo(0)
    assertThat(isolatedTask.encryptionKeyId.orNull).isNull()
    isolatedTask.doTaskAction()
    assertThat(keyGenerationCount.get()).isEqualTo(0)

    val loaded =
      SigningConfigUtils.loadSigningConfigData(isolatedOutput) {
        throw AssertionError("Encryption service should not be initialized when no encrypted passwords are present")
      }
    assertThat(loaded).isEqualTo(isolatedTask.signingConfigData.get())

    // Once passwords are provided, the encryption key is generated lazily once.
    isolatedTask.signingConfigData.set(
      SigningConfigData(
        name = "release",
        storeType = "jks",
        storeFile = temporaryFolder.newFile(),
        storePassword = "storePassword",
        keyAlias = "releaseKey",
        keyPassword = "keyPassword",
      )
    )
    assertThat(isolatedTask.encryptionKeyId.orNull).isNotEmpty()
    assertThat(keyGenerationCount.get()).isEqualTo(1)
    isolatedTask.doTaskAction()
    assertThat(keyGenerationCount.get()).isEqualTo(1)
  }

  @Test
  fun testFingerprintFileStaysUnchangedAcrossKeyRotations() {
    val config =
      SigningConfigData(
        name = "release",
        storeType = "jks",
        storeFile = temporaryFolder.newFile(),
        storePassword = "storePassword",
        keyAlias = "releaseKey",
        keyPassword = "keyPassword",
      )

    val project1 = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build()
    val task1 = project1.tasks.register("signingConfigWriterRelease", SigningConfigWriterTask::class.java).get()
    val output1 = temporaryFolder.newFile()
    val fingerprint1 = temporaryFolder.newFile()
    task1.signingConfigEncryptionService.setDisallowChanges(SigningConfigEncryptionBuildService.RegistrationAction(project1).execute())
    task1.outputFile.set(output1)
    task1.fingerprintFile.set(fingerprint1)
    task1.signingConfigData.set(config)
    task1.doTaskAction()

    val encryptedBytes1 = output1.readBytes()
    val fingerprintBytes1 = fingerprint1.readBytes()

    // Rotate to a new ephemeral key in another build without changing signing config data.
    val project2 = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build()
    val task2 = project2.tasks.register("signingConfigWriterRelease", SigningConfigWriterTask::class.java).get()
    val output2 = temporaryFolder.newFile()
    val fingerprint2 = temporaryFolder.newFile()
    task2.signingConfigEncryptionService.setDisallowChanges(SigningConfigEncryptionBuildService.RegistrationAction(project2).execute())
    task2.outputFile.set(output2)
    task2.fingerprintFile.set(fingerprint2)
    task2.signingConfigData.set(config)
    task2.doTaskAction()

    val encryptedBytes2 = output2.readBytes()
    val fingerprintBytes2 = fingerprint2.readBytes()
    assertThat(encryptedBytes2).isNotEqualTo(encryptedBytes1)
    assertThat(fingerprintBytes2).isEqualTo(fingerprintBytes1)

    // Changing any signing config field updates the fingerprint file.
    task2.signingConfigData.set(config.copy(keyPassword = "rotatedPassword"))
    task2.doTaskAction()
    val updatedFieldFingerprint = fingerprint2.readBytes()
    assertThat(updatedFieldFingerprint).isNotEqualTo(fingerprintBytes1)

    // Rotating the keystore contents at the same path also updates the fingerprint file.
    config.storeFile!!.writeBytes(byteArrayOf(9, 8, 7, 6))
    task2.signingConfigData.set(config)
    task2.doTaskAction()
    assertThat(fingerprint2.readBytes()).isNotEqualTo(fingerprintBytes1)
    assertThat(fingerprint2.readBytes()).isNotEqualTo(updatedFieldFingerprint)
  }
}
