/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.BuildFileType
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.utils.SigningHelper
import com.android.ide.common.signing.KeystoreHelper
import com.android.testutils.apk.Apk
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Properties
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SigningConfigValueSourceTest {

  @get:Rule
  val rule = GradleRule.from {
    buildFileType = BuildFileType.KTS
    androidApplication {}
  }

  @Before
  fun setUp() {
    rule.getMainBuildDirectory().resolve(".gradle/configuration-cache").toFile().deleteRecursively()
  }

  @Test
  fun testLazySigningConfigWithConfigurationCache() {
    val build = rule.build
    val app = build.androidApplication()

    // Keystore A setup
    val keyStoreFileA = build.directory.resolve("keystoreA.jks").toFile()
    KeystoreHelper.createNewStore("jks", keyStoreFileA, "storePassword", "keyPassword", "keyAlias", "CN=Keystore A", 100)

    // Keystore B setup
    val keyStoreFileB = build.directory.resolve("keystoreB.jks").toFile()
    KeystoreHelper.createNewStore("jks", keyStoreFileB, "storePassword", "keyPassword", "keyAlias", "CN=Keystore B", 100)

    // Initial properties file setup pointing to Keystore A
    val propsFile = build.directory.resolve("signing.properties").toFile()
    val properties =
      Properties().apply {
        setProperty("storeFile", keyStoreFileA.absolutePath)
        setProperty("storePassword", "storePassword")
        setProperty("keyAlias", "keyAlias")
        setProperty("keyPassword", "keyPassword")
        setProperty("storeType", "jks")
      }
    propsFile.outputStream().use { properties.store(it, null) }

    // Update build.gradle.kts with the lazy signing config code
    app.files.update("build.gradle.kts").transform { original ->
      """
      import com.android.build.api.variant.SigningConfigInfo
      import java.util.Properties

      """
        .trimIndent() +
        original +
        """
        abstract class SigningConfigValueSource : ValueSource<SigningConfigInfo, SigningConfigValueSource.Params> {
            interface Params : ValueSourceParameters {
                val propertiesFile: RegularFileProperty
            }
            override fun obtain(): SigningConfigInfo? {
                val file = parameters.propertiesFile.orNull?.asFile ?: return null
                if (!file.exists()) return null
                val props = Properties().apply {
                    file.inputStream().use { load(it) }
                }
                return SigningConfigInfo(
                    File(props.getProperty("storeFile")),
                    props.getProperty("storePassword"),
                    props.getProperty("keyAlias"),
                    props.getProperty("keyPassword"),
                    props.getProperty("storeType")
                )
            }
        }
        android {
             compileSdk = ${GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION}
        }
        androidComponents {
            onVariants { variant ->
                val lazySigningConfig = providers.of(SigningConfigValueSource::class.java) {
                    parameters.propertiesFile.set(layout.projectDirectory.file("../signing.properties"))
                }
                variant.signingConfig.from(lazySigningConfig)
            }
        }
        """
          .trimIndent()
    }

    // First Run: Write cache
    val executor = build.executor.withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
    val result1 = executor.run("assembleDebug")
    result1.assertConfigurationCacheMiss()

    // Verify signing details on the APK (should have metadata matching Keystore A)
    app.assertApk(ApkSelector.DEBUG) {
      contains("META-INF/CERT.SF")
      contains("META-INF/CERT.RSA")
    }
    val apkPath1 = app.getApkLocationForCopy(ApkSelector.DEBUG)
    val verificationResult1 = SigningHelper.assertApkSignaturesVerify(Apk(apkPath1), 30)
    assertThat(verificationResult1.signerCertificates.first().subjectX500Principal.name).isEqualTo("CN=Keystore A")

    // Dynamic change: key B will now replace deleted key A
    keyStoreFileA.delete()
    keyStoreFileB.renameTo(File(keyStoreFileB.parent, "keystoreA.jks"))

    // Second Run: Configuration Cache is successfully resolved and reused despite different key
    val result2 = executor.run("assembleDebug")
    result2.assertConfigurationCacheHit()

    // Verify dynamic lazy resolution succeeded
    app.assertApk(ApkSelector.DEBUG) {
      contains("META-INF/CERT.SF")
      contains("META-INF/CERT.RSA")
    }
    val apkPath2 = app.getApkLocationForCopy(ApkSelector.DEBUG)
    val verificationResult2 = SigningHelper.assertApkSignaturesVerify(Apk(apkPath2), 30)
    assertThat(verificationResult2.signerCertificates.first().subjectX500Principal.name).isEqualTo("CN=Keystore B")
  }

  @Test
  fun testHelpDoesNotResolveSigningConfig() {
    val build = rule.build
    val app = build.androidApplication()

    // Keystore A setup
    val keyStoreFileA = build.directory.resolve("keystoreA.jks").toFile()
    KeystoreHelper.createNewStore("jks", keyStoreFileA, "storePassword", "keyPassword", "keyAlias", "CN=Keystore A", 100)

    // Initial properties file setup pointing to Keystore A
    val propsFile = build.directory.resolve("signing.properties").toFile()
    val properties =
      Properties().apply {
        setProperty("storeFile", keyStoreFileA.absolutePath)
        setProperty("storePassword", "storePassword")
        setProperty("keyAlias", "keyAlias")
        setProperty("keyPassword", "keyPassword")
        setProperty("storeType", "jks")
      }
    propsFile.outputStream().use { properties.store(it, null) }

    // Update build.gradle.kts with the lazy signing config code
    app.files.update("build.gradle.kts").transform { original ->
      """
      import com.android.build.api.variant.SigningConfigInfo
      import java.util.Properties

      """
        .trimIndent() +
        original +
        """
        abstract class SigningConfigValueSource : ValueSource<SigningConfigInfo, SigningConfigValueSource.Params> {
            interface Params : ValueSourceParameters {
                val propertiesFile: RegularFileProperty
            }
            override fun obtain(): SigningConfigInfo? {
                println("=== SigningConfigValueSource.obtain() called ===")
                val file = parameters.propertiesFile.orNull?.asFile ?: return null
                if (!file.exists()) return null
                val props = Properties().apply {
                    file.inputStream().use { load(it) }
                }
                return SigningConfigInfo(
                    File(props.getProperty("storeFile")),
                    props.getProperty("storePassword"),
                    props.getProperty("keyAlias"),
                    props.getProperty("keyPassword"),
                    props.getProperty("storeType")
                )
            }
        }
        android {
             compileSdk = ${GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION}
        }
        androidComponents {
            onVariants { variant ->
                val lazySigningConfig = providers.of(SigningConfigValueSource::class.java) {
                    parameters.propertiesFile.set(layout.projectDirectory.file("../signing.properties"))
                }
                variant.signingConfig.from(lazySigningConfig)
            }
        }
        """
          .trimIndent()
    }

    val executor = build.executor
    val result = executor.run("help")
    result.assertOutputDoesNotContain("=== SigningConfigValueSource.obtain() called ===")
  }

  @Test
  fun testLazySigningConfigForReleaseNotUsedInDebug() {
    val build = rule.build
    val app = build.androidApplication()

    // Update build.gradle.kts with a failing ValueSource registered only for release
    app.files.update("build.gradle.kts").transform { original ->
      """
      import com.android.build.api.variant.SigningConfigInfo
      import java.util.Properties

      """
        .trimIndent() +
        original +
        """
        abstract class FailingSigningConfigValueSource : ValueSource<SigningConfigInfo, ValueSourceParameters.None> {
            override fun obtain(): SigningConfigInfo? {
                throw RuntimeException("FailingSigningConfigValueSource should not be evaluated")
            }
        }
        android {
             compileSdk = ${GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION}
        }
        androidComponents {
            onVariants { variant ->
                if (variant.name == "release") {
                    val lazySigningConfig = providers.of(FailingSigningConfigValueSource::class.java) {}
                    variant.signingConfig.from(lazySigningConfig)
                }
            }
        }
        """
          .trimIndent()
    }

    val executor = build.executor
    // This should succeed because assembleDebug does not need the release signing config
    executor.run("assembleDebug")

    // This should fail because assembleRelease needs the release signing config
    val failure = executor.expectFailure().run("assembleRelease")
    failure.assertErrorContains("FailingSigningConfigValueSource should not be evaluated")
  }
}
