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

import com.android.SdkConstants
import com.android.build.gradle.internal.signing.SigningConfigData
import com.android.build.gradle.internal.signing.SigningConfigEncryptionBuildService
import com.android.build.gradle.internal.signing.SigningConfigVersions
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Tests for [SigningConfigUtils]. */
class SigningConfigUtilsTest {
  @Rule @JvmField var temporaryFolder = TemporaryFolder()

  lateinit var outputFile: File
  lateinit var storeFile: File
  private val encryptionService = SigningConfigEncryptionBuildService.createForTesting()

  @Before
  @Throws(IOException::class)
  fun setUp() {
    outputFile = temporaryFolder.newFile()
    storeFile = temporaryFolder.newFile()
  }

  @Test
  @Throws(IOException::class)
  fun testSigningConfigDataSaveAndLoad() {
    val signingConfigData =
      SigningConfigData(
        name = "sampleName",
        storeType = "sampleStoreType",
        storeFile = storeFile,
        storePassword = "sampleStorePassword",
        keyAlias = "sampleKeyAlias",
        keyPassword = "sampleKeyPassword",
      )
    SigningConfigUtils.saveSigningConfigData(outputFile, signingConfigData, encryptionService)

    val rawJson = outputFile.readText(StandardCharsets.UTF_8)
    assertThat(rawJson).doesNotContain("sampleStorePassword")
    assertThat(rawJson).doesNotContain("sampleKeyPassword")
    assertThat(rawJson).doesNotContain("\"storePassword\"")
    assertThat(rawJson).doesNotContain("\"keyPassword\"")

    val config = SigningConfigUtils.loadSigningConfigData(outputFile, encryptionService)
    assertThat(config).isEqualTo(signingConfigData)
  }

  @Test
  fun testSigningConfigDataRequiresEncryptionServiceForPasswords() {
    val signingConfigData =
      SigningConfigData(
        name = "sampleName",
        storeType = "sampleStoreType",
        storeFile = storeFile,
        storePassword = "sampleStorePassword",
        keyAlias = "sampleKeyAlias",
        keyPassword = "sampleKeyPassword",
      )

    assertThrows(IllegalStateException::class.java) {
      SigningConfigUtils.saveSigningConfigData(outputFile, signingConfigData, encryptionService = null)
    }

    SigningConfigUtils.saveSigningConfigData(outputFile, signingConfigData, encryptionService)

    assertThrows(IllegalStateException::class.java) {
      SigningConfigUtils.loadSigningConfigData(outputFile, encryptionService = null)
    }

    val otherKeyService = SigningConfigEncryptionBuildService.createForTesting()
    assertThrows(IllegalStateException::class.java) {
      SigningConfigUtils.loadSigningConfigData(outputFile, otherKeyService)
    }

    // Tampering with AAD-bound metadata (e.g., keyAlias) fails GCM authentication.
    val originalJson = outputFile.readText(StandardCharsets.UTF_8)
    outputFile.writeText(originalJson.replace("sampleKeyAlias", "tamperedKeyAlias"), StandardCharsets.UTF_8)
    assertThrows(IllegalStateException::class.java) {
      SigningConfigUtils.loadSigningConfigData(outputFile, encryptionService)
    }
  }

  @Test
  fun testNullSigningConfigDataSaveAndLoad() {
    SigningConfigUtils.saveSigningConfigData(outputFile, null, encryptionService)
    assertThat(SigningConfigUtils.loadSigningConfigData(outputFile, encryptionService)).isNull()
  }

  @Test
  fun testSigningConfigDataWithoutPasswordsDoesNotInitializeEncryptionService() {
    val withoutPasswords =
      SigningConfigData(
        name = "unsigned",
        storeType = "jks",
        storeFile = storeFile,
        storePassword = null,
        keyAlias = "releaseKey",
        keyPassword = null,
      )

    SigningConfigUtils.saveSigningConfigData(outputFile, withoutPasswords) {
      throw AssertionError("Encryption service should not be initialized when no passwords are present")
    }

    val loaded =
      SigningConfigUtils.loadSigningConfigData(outputFile) {
        throw AssertionError("Encryption service should not be initialized when no encrypted passwords are present")
      }
    assertThat(loaded).isEqualTo(withoutPasswords)
  }

  @Test
  fun testSigningConfigFingerprintIsDeterministicAndSecretFree() {
    val fingerprintFile = temporaryFolder.newFile()
    storeFile.writeBytes(byteArrayOf(1, 2, 3, 4))
    val config =
      SigningConfigData(
        name = "release",
        storeType = "jks",
        storeFile = storeFile,
        storePassword = "superSecretStorePassword",
        keyAlias = "releaseKey",
        keyPassword = "superSecretKeyPassword",
      )

    SigningConfigUtils.saveSigningConfigFingerprint(fingerprintFile, config)
    val firstFingerprint = fingerprintFile.readText(StandardCharsets.UTF_8)
    assertThat(firstFingerprint).matches("^[0-9a-f]{64}$")
    assertThat(firstFingerprint).doesNotContain("superSecretStorePassword")
    assertThat(firstFingerprint).doesNotContain("superSecretKeyPassword")
    assertThat(firstFingerprint).doesNotContain("releaseKey")
    assertThat(firstFingerprint).doesNotContain(storeFile.path)

    SigningConfigUtils.saveSigningConfigFingerprint(fingerprintFile, config)
    assertThat(fingerprintFile.readText(StandardCharsets.UTF_8)).isEqualTo(firstFingerprint)

    // Rotating keystore contents at the same path must change the fingerprint.
    storeFile.writeBytes(byteArrayOf(5, 6, 7, 8))
    SigningConfigUtils.saveSigningConfigFingerprint(fingerprintFile, config)
    val rotatedKeystoreFingerprint = fingerprintFile.readText(StandardCharsets.UTF_8)
    assertThat(rotatedKeystoreFingerprint).isNotEqualTo(firstFingerprint)

    // Missing file vs empty file must also be distinguished at the same path.
    assertThat(storeFile.delete()).isTrue()
    SigningConfigUtils.saveSigningConfigFingerprint(fingerprintFile, config)
    val missingKeystoreFingerprint = fingerprintFile.readText(StandardCharsets.UTF_8)
    assertThat(missingKeystoreFingerprint).isNotEqualTo(rotatedKeystoreFingerprint)

    assertThat(storeFile.createNewFile()).isTrue()
    SigningConfigUtils.saveSigningConfigFingerprint(fingerprintFile, config)
    val emptyKeystoreFingerprint = fingerprintFile.readText(StandardCharsets.UTF_8)
    assertThat(emptyKeystoreFingerprint).isNotEqualTo(missingKeystoreFingerprint)

    SigningConfigUtils.saveSigningConfigFingerprint(fingerprintFile, null)
    val nullFingerprint = fingerprintFile.readText(StandardCharsets.UTF_8)
    assertThat(nullFingerprint).isNotEqualTo(emptyKeystoreFingerprint)

    val variants =
      listOf(
        config.copy(name = "debug"),
        config.copy(storeType = "pkcs12"),
        config.copy(storeType = null),
        config.copy(storeFile = File(temporaryFolder.root, "other.keystore")),
        config.copy(storeFile = null),
        config.copy(storePassword = "otherStorePassword"),
        config.copy(storePassword = null),
        config.copy(keyAlias = "otherKey"),
        config.copy(keyAlias = null),
        config.copy(keyPassword = "otherKeyPassword"),
        config.copy(keyPassword = null),
      )
    for (variant in variants) {
      SigningConfigUtils.saveSigningConfigFingerprint(fingerprintFile, variant)
      assertThat(fingerprintFile.readText(StandardCharsets.UTF_8)).isNotEqualTo(emptyKeystoreFingerprint)
    }
  }

  @Test
  fun testSigningConfigDataToStringRedactsPasswords() {
    val withPasswords =
      SigningConfigData(
        name = "release",
        storeType = "jks",
        storeFile = storeFile,
        storePassword = "superSecretStorePassword",
        keyAlias = "releaseKey",
        keyPassword = "superSecretKeyPassword",
      )
    val redactedString = withPasswords.toString()
    assertThat(redactedString).doesNotContain("superSecretStorePassword")
    assertThat(redactedString).doesNotContain("superSecretKeyPassword")
    assertThat(redactedString).contains("storePassword=<redacted>")
    assertThat(redactedString).contains("keyPassword=<redacted>")

    val withoutPasswords =
      SigningConfigData(
        name = "empty",
        storeType = null,
        storeFile = null,
        storePassword = null,
        keyAlias = null,
        keyPassword = null,
      )
    val nullString = withoutPasswords.toString()
    assertThat(nullString).contains("storePassword=null")
    assertThat(nullString).contains("keyPassword=null")
  }

  @Test
  @Throws(IOException::class)
  fun testSigningConfigDataSaveDoesNotFollowExistingSymlink() {
    if (SdkConstants.CURRENT_PLATFORM == SdkConstants.PLATFORM_WINDOWS) {
      return
    }
    val externalTarget = temporaryFolder.newFile("external_target.txt")
    externalTarget.writeText("untouched-content", StandardCharsets.UTF_8)
    val worldReadable =
      setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.OTHERS_READ,
      )
    Files.setPosixFilePermissions(externalTarget.toPath(), worldReadable)

    Files.deleteIfExists(outputFile.toPath())
    Files.createSymbolicLink(outputFile.toPath(), externalTarget.toPath())

    val signingConfigData =
      SigningConfigData(
        name = "sampleName",
        storeType = "sampleStoreType",
        storeFile = storeFile,
        storePassword = "sampleStorePassword",
        keyAlias = "sampleKeyAlias",
        keyPassword = "sampleKeyPassword",
      )
    SigningConfigUtils.saveSigningConfigData(outputFile, signingConfigData, encryptionService)

    assertThat(Files.isSymbolicLink(outputFile.toPath())).isFalse()
    assertThat(externalTarget.readText(StandardCharsets.UTF_8)).isEqualTo("untouched-content")
    assertThat(Files.getPosixFilePermissions(externalTarget.toPath())).containsExactlyElementsIn(worldReadable)
    assertThat(SigningConfigUtils.loadSigningConfigData(outputFile, encryptionService)).isEqualTo(signingConfigData)
  }

  @Test
  @Throws(IOException::class)
  fun testSigningConfigVersionsSaveAndLoad() {
    val signingConfigVersions =
      SigningConfigVersions(enableV1Signing = false, enableV2Signing = true, enableV3Signing = false, enableV4Signing = true)
    SigningConfigUtils.saveSigningConfigVersions(outputFile, signingConfigVersions)

    val loadedSigningConfigVersions = SigningConfigUtils.loadSigningConfigVersions(outputFile)
    assertThat(loadedSigningConfigVersions).isEqualTo(signingConfigVersions)
  }

  @Test
  @Throws(IOException::class)
  fun testSigningConfigDataFileIsReadWriteByOwnerOnly() {
    val signingConfig =
      SigningConfigData(
        name = "sampleName",
        storeType = "sampleStoreType",
        storeFile = storeFile,
        storePassword = "sampleStorePassword",
        keyAlias = "sampleKeyAlias",
        keyPassword = "sampleKeyPassword",
      )
    SigningConfigUtils.saveSigningConfigData(outputFile, signingConfig, encryptionService)

    if (SdkConstants.CURRENT_PLATFORM != SdkConstants.PLATFORM_WINDOWS) {
      val perms = Files.getPosixFilePermissions(outputFile.toPath())
      assertThat(perms).containsExactly(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    } else {
      // Windows special handling, check that we can read and write.
      val view = Files.getFileAttributeView(outputFile.toPath(), AclFileAttributeView::class.java)
      val expectedEntry =
        AclEntry.newBuilder()
          .setType(AclEntryType.ALLOW)
          .setPrincipal(view.owner)
          .setPermissions(
            AclEntryPermission.READ_ACL,
            AclEntryPermission.READ_NAMED_ATTRS,
            AclEntryPermission.READ_DATA,
            AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.WRITE_ACL,
            AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA,
            AclEntryPermission.WRITE_NAMED_ATTRS,
            AclEntryPermission.WRITE_ATTRIBUTES,
            AclEntryPermission.WRITE_OWNER,
            AclEntryPermission.SYNCHRONIZE,
            AclEntryPermission.DELETE,
          )
          .build()
      assertThat(view.acl).containsExactly(expectedEntry)
    }
  }
}
