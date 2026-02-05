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

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.GradleTestProject.Companion.builder
import com.android.build.gradle.integration.common.fixture.app.HelloWorldApp
import com.android.build.gradle.integration.common.truth.AabSubject.Companion.assertThat
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.ide.common.signing.KeystoreHelper
import com.android.tools.build.apkzlib.sign.SignatureAlgorithm
import org.junit.Rule
import org.junit.Test

class BundleSigningTest {

  var minSdkVersion: Int = SignatureAlgorithm.RSA.minSdkVersion
  @get:Rule var project = builder().fromTestApp(HelloWorldApp.noBuildFile()).create()

  @Test
  @Throws(java.lang.Exception::class)
  fun generateSignedBundleFromDebuggableVariant() {
    val storePassword = "storePassword"
    val keyPassword = "keyPassword"
    val keyAlias = "key0"

    val keyStoreFile = project.file("keystore")
    KeystoreHelper.createNewStore("jks", keyStoreFile, storePassword, keyPassword, keyAlias, "CN=Bundle signing test", 100)

    TestFileUtils.appendToFile(
      project.buildFile,
      """
            apply plugin: 'com.android.application'
            android {
                 namespace = '${HelloWorldApp.NAMESPACE}'
                 compileSdk = ${GradleTestProject.DEFAULT_COMPILE_SDK_VERSION}
                 buildToolsVersion '${GradleTestProject.DEFAULT_BUILD_TOOL_VERSION}'

                defaultConfig {
                    minSdk = $minSdkVersion
                }
                signingConfigs {
                    myConfig {
                        storeFile = file('keystore')
                        storePassword = '$storePassword'
                        keyAlias = '$keyAlias'
                        keyPassword = '$keyPassword'
                    }
                }
                buildTypes {
                   debug {
                      signingConfig = signingConfigs.myConfig
                      debuggable = true
                   }
                }
            }
            """
        .trimIndent(),
    )
    project.executor().run(":bundleDebug")
    val aab = project.getBundle(GradleTestProject.ApkType.DEBUG)
    assertThat(aab).contains("META-INF/KEY0.SF")
    assertThat(aab).contains("META-INF/KEY0.RSA")
  }
}
