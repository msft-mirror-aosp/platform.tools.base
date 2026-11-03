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

package com.android.build.gradle.integration.bundle

import com.android.SdkConstants
import com.android.build.gradle.integration.application.SigningTest
import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThat
import com.android.build.gradle.integration.common.utils.SigningHelper
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.build.gradle.options.OptionalBooleanOption
import com.android.build.gradle.options.StringOption
import com.google.common.io.Resources
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import org.junit.Rule
import org.junit.Test

class DynamicAppSigningConfigTest {

  @get:Rule val project: GradleTestProject = GradleTestProject.builder().fromTestProject("dynamicApp").create()

  @Test
  fun testSyncWarning() {
    project
      .getSubproject("feature1")
      .buildFile
      .appendText(
        """
        android {
            signingConfigs {
                myConfig {
                    storeFile = file("foo.keystore")
                    storePassword = "bar"
                    keyAlias = "foo"
                    keyPassword = "bar"
                }
            }
            buildTypes {
                debug.signingConfig = signingConfigs.myConfig
            }
        }
        """
          .trimIndent()
      )

    val syncIssues = project.modelV2().ignoreSyncIssues().fetchModels().container.getProject(":feature1").issues?.syncIssues!!

    assertThat(syncIssues).hasSize(1)
    val singleSyncIssue = syncIssues.single()
    assertThat(singleSyncIssue.severity).isEqualTo(com.android.builder.model.v2.ide.SyncIssue.SEVERITY_WARNING)
    assertThat(singleSyncIssue.type).isEqualTo(com.android.builder.model.v2.ide.SyncIssue.TYPE_SIGNING_CONFIG_DECLARED_IN_DYNAMIC_FEATURE)
    assertThat(singleSyncIssue.message)
      .contains(
        "Signing configuration should not be declared in build types of dynamic-feature. Dynamic-features use the signing configuration declared in the application module."
      )
  }

  @Test
  fun testNoSyncWarning() {
    val issues = project.modelV2().ignoreSyncIssues().fetchModels().container.getProject(":feature1").issues?.syncIssues!!
    assertThat(issues).hasSize(0)
  }

  @Test
  fun `assemble with injected signing config`() {
    val keystoreFile = project.file("keystore.jks")
    val keystoreContents = Resources.toByteArray(Resources.getResource(SigningTest::class.java, "SigningTest/rsa_keystore.jks"))
    Files.write(keystoreFile.toPath(), keystoreContents)

    val result =
      project
        .executor()
        .with(StringOption.IDE_SIGNING_STORE_FILE, keystoreFile.path)
        .with(StringOption.IDE_SIGNING_STORE_PASSWORD, STORE_PASSWORD)
        .with(StringOption.IDE_SIGNING_KEY_ALIAS, ALIAS_NAME)
        .with(StringOption.IDE_SIGNING_KEY_PASSWORD, KEY_PASSWORD)
        .with(OptionalBooleanOption.SIGNING_V1_ENABLED, true)
        .with(OptionalBooleanOption.SIGNING_V2_ENABLED, true)
        .run("assembleRelease")

    for (subProjectName in listOf("app", "feature1", "feature2")) {
      val subProject = project.getSubproject(subProjectName)

      // Check for signing file inside the APK
      val apk = subProject.getApk(GradleTestProject.ApkType.RELEASE_SIGNED)
      assertThat(apk).contains("META-INF/CERT.RSA")
      assertThat(apk).contains("META-INF/CERT.SF")
    }
    // Check that signing config is not written to disk when passed from the IDE (bug 137210434)
    assertThat(result.tasks).doesNotContain(":app:signingConfigWriterRelease")
    assertThat(result.tasks).contains(":app:writeReleaseSigningConfigVersions")
  }

  @Test
  fun testAllApksUseSigningConfigVersionsFromBase() {
    val keystoreFile = project.file("keystore.jks")
    val keystoreContents = Resources.toByteArray(Resources.getResource(SigningTest::class.java, "SigningTest/rsa_keystore.jks"))
    Files.write(keystoreFile.toPath(), keystoreContents)

    project
      .getSubproject("app")
      .buildFile
      .appendText(
        """
        androidComponents {
            onVariants(selector().withName('debug'), {
                signingConfig.enableV1Signing.set(true)
                signingConfig.enableV2Signing.set(true)
                signingConfig.enableV3Signing.set(true)
                signingConfig.enableV4Signing.set(true)
            })
            onVariants(selector().all(), {
                it.androidTest?.signingConfig?.enableV1Signing?.set(true)
                it.androidTest?.signingConfig?.enableV2Signing?.set(true)
                it.androidTest?.signingConfig?.enableV3Signing?.set(true)
                it.androidTest?.signingConfig?.enableV4Signing?.set(true)
            })
        }
        """
          .trimIndent()
      )

    project
      .executor()
      .with(StringOption.IDE_SIGNING_STORE_FILE, keystoreFile.path)
      .with(StringOption.IDE_SIGNING_STORE_PASSWORD, STORE_PASSWORD)
      .with(StringOption.IDE_SIGNING_KEY_ALIAS, ALIAS_NAME)
      .with(StringOption.IDE_SIGNING_KEY_PASSWORD, KEY_PASSWORD)
      .run("assembleDebug", "assembleDebugAndroidTest")

    for (subProjectName in listOf("app", "feature1", "feature2")) {
      val subProject = project.getSubproject(subProjectName)

      // Check the APK's signatures
      val apk = subProject.getApk(GradleTestProject.ApkType.DEBUG)
      assertThat(apk).contains("META-INF/CERT.RSA")
      assertThat(apk).contains("META-INF/CERT.SF")
      assertThat(apk).containsApkSigningBlock()
      val result = SigningHelper.assertApkSignaturesVerify(apk, 23)
      assertThat(result.isVerifiedUsingV1Scheme).isTrue()
      assertThat(result.isVerifiedUsingV2Scheme).isTrue()
      assertThat(result.isVerifiedUsingV3Scheme).isTrue()
      assertThat(result.isVerifiedUsingV4Scheme).isTrue()

      // Check the android test APK's signatures
      val androidTestApk = subProject.getApk(GradleTestProject.ApkType.ANDROIDTEST_DEBUG)
      assertThat(androidTestApk).contains("META-INF/CERT.RSA")
      assertThat(androidTestApk).contains("META-INF/CERT.SF")
      assertThat(androidTestApk).containsApkSigningBlock()
      val androidTestResult = SigningHelper.assertApkSignaturesVerify(androidTestApk, 23)
      assertThat(androidTestResult.isVerifiedUsingV1Scheme).isTrue()
      assertThat(androidTestResult.isVerifiedUsingV2Scheme).isTrue()
      assertThat(androidTestResult.isVerifiedUsingV3Scheme).isTrue()
      assertThat(androidTestResult.isVerifiedUsingV4Scheme).isTrue()
    }
  }

  @Test
  fun `assemble dynamic feature with dsl signing config encrypts passwords on disk`() {
    val keystoreFile = project.file("keystore.jks")
    val keystoreContents = Resources.toByteArray(Resources.getResource(SigningTest::class.java, "SigningTest/rsa_keystore.jks"))
    Files.write(keystoreFile.toPath(), keystoreContents)

    project
      .getSubproject("app")
      .buildFile
      .appendText(
        """
        android {
            signingConfigs {
                release {
                    storeFile = file("${keystoreFile.invariantSeparatorsPath}")
                    storePassword = "$STORE_PASSWORD"
                    keyAlias = "$ALIAS_NAME"
                    keyPassword = "$KEY_PASSWORD"
                }
            }
            buildTypes {
                release {
                    signingConfig = signingConfigs.release
                }
            }
        }
        """
          .trimIndent()
      )

    val executor = project.executor().withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
    val result1 = executor.run(":feature1:assembleRelease")
    result1.assertConfigurationCacheMiss()
    assertThat(result1.didWorkTasks).contains(":app:signingConfigWriterRelease")

    val signingConfigDataFile =
      project.getSubproject("app").getIntermediateFile("signing_config_data/release/signingConfigWriterRelease/signing-config-data.json")
    assertThat(signingConfigDataFile.exists()).isTrue()

    val rawJson = signingConfigDataFile.readText(StandardCharsets.UTF_8)
    assertThat(rawJson).doesNotContain(STORE_PASSWORD)
    assertThat(rawJson).doesNotContain(KEY_PASSWORD)

    if (SdkConstants.CURRENT_PLATFORM != SdkConstants.PLATFORM_WINDOWS) {
      assertThat(Files.getPosixFilePermissions(signingConfigDataFile.toPath()))
        .containsExactly(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    }

    val feature1 = project.getSubproject("feature1")
    feature1.getApk(GradleTestProject.ApkType.RELEASE_SIGNED).use { apk ->
      assertThat(apk).contains("META-INF/CERT.RSA")
      assertThat(apk).contains("META-INF/CERT.SF")
      SigningHelper.assertApkSignaturesVerify(apk, 23)
    }

    // Incremental build hitting Configuration Cache: signingConfigWriterRelease stays UP-TO-DATE
    // and :feature1:packageRelease decrypts signing-config-data.json using the cached service key.
    val stringsFile = feature1.file("src/main/res/values/strings.xml")
    stringsFile.writeText(
      """
      <resources>
          <string name="feature_title">Updated Feature 1</string>
      </resources>
      """
        .trimIndent()
    )
    val result2 = executor.run(":feature1:assembleRelease")
    result2.assertConfigurationCacheHit()
    assertThat(result2.upToDateTasks).contains(":app:signingConfigWriterRelease")
    feature1.getApk(GradleTestProject.ApkType.RELEASE_SIGNED).use { apk ->
      SigningHelper.assertApkSignaturesVerify(apk, 23)
    }

    // Non-cached build with a fresh ephemeral encryption key (no clean): encryptionKeyId input
    // invalidates signingConfigWriterRelease so it re-encrypts with the new key.
    stringsFile.writeText(
      """
      <resources>
          <string name="feature_title">Updated Feature 1 Again</string>
      </resources>
      """
        .trimIndent()
    )
    val result3 = project.executor().withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.OFF).run(":feature1:assembleRelease")
    assertThat(result3.didWorkTasks).contains(":app:signingConfigWriterRelease")
    assertThat(signingConfigDataFile.readText(StandardCharsets.UTF_8)).doesNotContain(STORE_PASSWORD)
    feature1.getApk(GradleTestProject.ApkType.RELEASE_SIGNED).use { apk ->
      SigningHelper.assertApkSignaturesVerify(apk, 23)
    }
  }

  @Test
  fun `assemble dynamic feature with ValueSource signing config encrypts passwords on disk`() {
    val keystoreFile = project.file("keystore.jks")
    val keystoreContents = Resources.toByteArray(Resources.getResource(SigningTest::class.java, "SigningTest/rsa_keystore.jks"))
    Files.write(keystoreFile.toPath(), keystoreContents)

    val app = project.getSubproject("app")
    app.buildFile.appendText(
      """
      abstract class LazySigningSource implements org.gradle.api.provider.ValueSource<com.android.build.api.variant.SigningConfigInfo, org.gradle.api.provider.ValueSourceParameters.None> {
          @Override
          com.android.build.api.variant.SigningConfigInfo obtain() {
              return new com.android.build.api.variant.SigningConfigInfo(
                  new File("${keystoreFile.invariantSeparatorsPath}"),
                  "$STORE_PASSWORD",
                  "$ALIAS_NAME",
                  "$KEY_PASSWORD",
                  "jks"
              )
          }
      }
      androidComponents {
          onVariants(selector().withBuildType("release")) { variant ->
              variant.signingConfig.from(providers.of(LazySigningSource) {})
          }
      }
      """
        .trimIndent()
    )

    val executor = project.executor().withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
    val result1 = executor.run(":feature1:assembleRelease")
    result1.assertConfigurationCacheMiss()
    assertThat(result1.didWorkTasks).contains(":app:signingConfigWriterRelease")

    val signingConfigDataFile = app.getIntermediateFile("signing_config_data/release/signingConfigWriterRelease/signing-config-data.json")
    assertThat(signingConfigDataFile.exists()).isTrue()
    val rawJson = signingConfigDataFile.readText(StandardCharsets.UTF_8)
    assertThat(rawJson).doesNotContain(STORE_PASSWORD)
    assertThat(rawJson).doesNotContain(KEY_PASSWORD)

    val feature1 = project.getSubproject("feature1")
    feature1.getApk(GradleTestProject.ApkType.RELEASE_SIGNED).use { apk ->
      SigningHelper.assertApkSignaturesVerify(apk, 23)
    }

    feature1
      .file("src/main/res/values/strings.xml")
      .writeText(
        """
        <resources>
            <string name="feature_title">Lazy ValueSource Feature</string>
        </resources>
        """
          .trimIndent()
      )
    val result2 = executor.run(":feature1:assembleRelease")
    result2.assertConfigurationCacheHit()
    assertThat(result2.upToDateTasks).contains(":app:signingConfigWriterRelease")
    feature1.getApk(GradleTestProject.ApkType.RELEASE_SIGNED).use { apk ->
      SigningHelper.assertApkSignaturesVerify(apk, 23)
    }
  }

  @Test
  fun `encryption service is not initialized when signing config encryption is unnecessary`() {
    val keystoreFile = project.file("keystore.jks")
    val keystoreContents = Resources.toByteArray(Resources.getResource(SigningTest::class.java, "SigningTest/rsa_keystore.jks"))
    Files.write(keystoreFile.toPath(), keystoreContents)

    project
      .getSubproject("app")
      .buildFile
      .appendText(
        """
        android {
            signingConfigs {
                release {
                    storeFile = file("${keystoreFile.invariantSeparatorsPath}")
                    storePassword = "$STORE_PASSWORD"
                    keyAlias = "$ALIAS_NAME"
                    keyPassword = "$KEY_PASSWORD"
                }
            }
            buildTypes {
                release {
                    signingConfig = signingConfigs.release
                }
            }
        }
        """
          .trimIndent()
      )

    val inspectServicesInitScript = project.file("inspect-services.init.gradle")
    inspectServicesInitScript.writeText(
      """
      def isServiceInstantiated = { reg ->
          Class<?> c = reg.service.class
          while (c != null) {
              def f = c.declaredFields.find { it.name == "instance" }
              if (f != null) {
                  f.accessible = true
                  return f.get(reg.service) != null
              }
              c = c.superclass
          }
          throw new AssertionError("Expected instance field on " + reg.service.class)
      }
      def countInstantiatedEncryptionServices = {
          gradle.sharedServices.registrations.count { reg ->
              (reg.name.contains("SigningConfigEncryptionBuildService") ||
                  reg.name.contains("GlobalSigningConfigKeyService")) &&
                  isServiceInstantiated(reg)
          }
      }
      gradle.taskGraph.whenReady { graph ->
          println("INSTANTIATED_BEFORE_EXEC=" + countInstantiatedEncryptionServices())
          graph.allTasks.last().doLast {
              println("INSTANTIATED_AFTER_EXEC=" + countInstantiatedEncryptionServices())
          }
      }
      """
        .trimIndent()
    )

    // 1. Building base app APK/bundle directly never needs intermediate signing config files.
    val appOnlyResult =
      project
        .executor()
        .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.OFF)
        .withArgument("--init-script")
        .withArgument(inspectServicesInitScript.absolutePath)
        .run(":app:assembleRelease", ":app:bundleRelease")
    appOnlyResult.assertOutputContains("INSTANTIATED_BEFORE_EXEC=0")
    appOnlyResult.assertOutputContains("INSTANTIATED_AFTER_EXEC=0")

    // 2. Building dynamic feature with IDE-injected signing options never uses intermediate signing config files.
    val ideInjectedResult =
      project
        .executor()
        .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.OFF)
        .with(StringOption.IDE_SIGNING_STORE_FILE, keystoreFile.path)
        .with(StringOption.IDE_SIGNING_STORE_PASSWORD, STORE_PASSWORD)
        .with(StringOption.IDE_SIGNING_KEY_ALIAS, ALIAS_NAME)
        .with(StringOption.IDE_SIGNING_KEY_PASSWORD, KEY_PASSWORD)
        .withArgument("--init-script")
        .withArgument(inspectServicesInitScript.absolutePath)
        .run(":feature1:assembleRelease")
    ideInjectedResult.assertOutputContains("INSTANTIATED_BEFORE_EXEC=0")
    ideInjectedResult.assertOutputContains("INSTANTIATED_AFTER_EXEC=0")

    // 3. Building dynamic feature with DSL release passwords instantiates the encryption & global key services.
    val encryptedFeatureResult =
      project
        .executor()
        .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.OFF)
        .withArgument("--init-script")
        .withArgument(inspectServicesInitScript.absolutePath)
        .run(":feature1:assembleRelease")
    encryptedFeatureResult.assertOutputContains("INSTANTIATED_BEFORE_EXEC=0")
    encryptedFeatureResult.assertOutputContains("INSTANTIATED_AFTER_EXEC=2")

    // 4. Running signingConfigWriterRelease and feature packaging for an unsigned release variant never initializes encryption services.
    TestFileUtils.appendToFile(
      project.getSubproject("app").buildFile,
      """
      android {
          buildTypes {
              release {
                  signingConfig = null
              }
          }
      }
      """
        .trimIndent(),
    )
    val unsignedWriterResult =
      project
        .executor()
        .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.OFF)
        .withArgument("--init-script")
        .withArgument(inspectServicesInitScript.absolutePath)
        .run(":app:signingConfigWriterRelease")
    assertThat(unsignedWriterResult.didWorkTasks).contains(":app:signingConfigWriterRelease")
    unsignedWriterResult.assertOutputContains("INSTANTIATED_BEFORE_EXEC=0")
    unsignedWriterResult.assertOutputContains("INSTANTIATED_AFTER_EXEC=0")

    val unsignedFeatureResult =
      project
        .executor()
        .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.OFF)
        .withArgument("--init-script")
        .withArgument(inspectServicesInitScript.absolutePath)
        .run(":feature1:assembleRelease")
    assertThat(unsignedFeatureResult.didWorkTasks).contains(":feature1:packageRelease")
    unsignedFeatureResult.assertOutputContains("INSTANTIATED_BEFORE_EXEC=0")
    unsignedFeatureResult.assertOutputContains("INSTANTIATED_AFTER_EXEC=0")
  }

  @Test
  fun `dynamic feature package stays up-to-date on repeated non-configuration-cache builds`() {
    val keystoreFile = project.file("keystore.jks")
    val keystoreContents = Resources.toByteArray(Resources.getResource(SigningTest::class.java, "SigningTest/rsa_keystore.jks"))
    Files.write(keystoreFile.toPath(), keystoreContents)

    TestFileUtils.appendToFile(
      project.getSubproject("app").buildFile,
      """
      android {
          signingConfigs {
              release {
                  storeFile = file("${keystoreFile.invariantSeparatorsPath}")
                  storePassword = "$STORE_PASSWORD"
                  keyAlias = "$ALIAS_NAME"
                  keyPassword = "$KEY_PASSWORD"
              }
          }
          buildTypes {
              release {
                  signingConfig = signingConfigs.release
              }
          }
      }
      """
        .trimIndent(),
    )

    val executor = project.executor().withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.OFF)

    val result1 = executor.run(":feature1:assembleRelease", ":feature1:assembleDebug")
    assertThat(result1.didWorkTasks)
      .containsAtLeast(
        ":app:signingConfigWriterRelease",
        ":feature1:packageRelease",
        ":app:signingConfigWriterDebug",
        ":feature1:packageDebug",
      )

    // Without Configuration Cache, ephemeral encryption keys rotate so the writer re-runs,
    // while feature packaging stays UP-TO-DATE because the fingerprint file is unchanged.
    val result2 = executor.run(":feature1:assembleRelease", ":feature1:assembleDebug")
    assertThat(result2.didWorkTasks).containsAtLeast(":app:signingConfigWriterRelease", ":app:signingConfigWriterDebug")
    assertThat(result2.upToDateTasks).containsAtLeast(":feature1:packageRelease", ":feature1:packageDebug")

    // When a feature input changes on a non-CC build, writer runs first so packaging decrypts with the new key.
    val featureActivity = project.getSubproject("feature1").file("src/main/java/com/example/feature1/FeatureActivity.java")
    TestFileUtils.searchAndReplace(featureActivity, "\"Resource used: \"", "\"Updated resource used: \"")
    val result3 = executor.run(":feature1:assembleRelease")
    assertThat(result3.didWorkTasks).containsAtLeast(":app:signingConfigWriterRelease", ":feature1:packageRelease")
    project.getSubproject("feature1").getApk(GradleTestProject.ApkType.RELEASE_SIGNED).use { apk ->
      SigningHelper.assertApkSignaturesVerify(apk, 23)
    }

    // When keystore file contents are rotated at the same path, the fingerprint changes and
    // dynamic feature packaging re-runs with the new key instead of staying UP-TO-DATE.
    val rotatedKeystoreContents = Resources.toByteArray(Resources.getResource(SigningTest::class.java, "SigningTest/ec_keystore.jks"))
    Files.write(keystoreFile.toPath(), rotatedKeystoreContents)
    val rotatedKeystoreResult = executor.run(":app:assembleRelease", ":feature1:assembleRelease")
    assertThat(rotatedKeystoreResult.didWorkTasks).containsAtLeast(":app:signingConfigWriterRelease", ":feature1:packageRelease")
    project.getSubproject("feature1").getApk(GradleTestProject.ApkType.RELEASE_SIGNED).use { apk ->
      assertThat(apk).contains("META-INF/CERT.EC")
      SigningHelper.assertApkSignaturesVerify(apk, 23)
    }

    // When signing configuration itself changes, the fingerprint changes and feature packaging re-runs.
    TestFileUtils.appendToFile(
      project.getSubproject("app").buildFile,
      """
      android {
          buildTypes {
              release {
                  signingConfig = null
              }
          }
      }
      """
        .trimIndent(),
    )
    val result4 = executor.run(":feature1:assembleRelease")
    assertThat(result4.didWorkTasks).containsAtLeast(":app:signingConfigWriterRelease", ":feature1:packageRelease")
  }
}

private const val STORE_PASSWORD = "store_password"
private const val ALIAS_NAME = "alias_name"
private const val KEY_PASSWORD = "key_password"
