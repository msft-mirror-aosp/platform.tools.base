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

package com.android.build.api.variant.impl

import com.android.build.api.variant.SigningConfigInfo
import com.android.build.gradle.internal.services.createVariantPropertiesApiServices
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class SigningConfigProviderTest {

  @Test
  fun testSetConfigProvider() {
    val services = createVariantPropertiesApiServices()
    val signingConfigImpl = SigningConfigImpl(null, services, 1, null)

    val expectedStoreFile = File("/path/to/test.keystore")
    val expectedStorePassword = "testStorePassword"
    val expectedKeyAlias = "testKeyAlias"
    val expectedKeyPassword = "testKeyPassword"
    val expectedStoreType = "JKS"

    val signingConfigInfo =
      SigningConfigInfo(
        storeFile = expectedStoreFile,
        storePassword = expectedStorePassword,
        keyAlias = expectedKeyAlias,
        keyPassword = expectedKeyPassword,
        storeType = expectedStoreType,
      )

    val provider = services.provider { signingConfigInfo }
    signingConfigImpl.from(provider)

    assertThat(signingConfigImpl.storeFile.get().absolutePath).isEqualTo(expectedStoreFile.absolutePath)
    assertThat(signingConfigImpl.storePassword.get()).isEqualTo(expectedStorePassword)
    assertThat(signingConfigImpl.keyAlias.get()).isEqualTo(expectedKeyAlias)
    assertThat(signingConfigImpl.keyPassword.get()).isEqualTo(expectedKeyPassword)
    assertThat(signingConfigImpl.storeType.get()).isEqualTo(expectedStoreType)
  }

  @Test
  fun testSetConfigProviderWithSlowFetch() {
    val services = createVariantPropertiesApiServices()
    val signingConfigImpl = SigningConfigImpl(null, services, 1, null)

    val expectedStoreFile = File("/path/to/test.keystore")
    val expectedStorePassword = "slowTestStorePassword"
    val expectedKeyAlias = "slowTestKeyAlias"
    val expectedKeyPassword = "slowTestKeyPassword"

    fun fetchProperties(): Map<String, String> {
      Thread.sleep(100)
      return mapOf(
        "storeFile" to expectedStoreFile.absolutePath,
        "storePassword" to expectedStorePassword,
        "keyAlias" to expectedKeyAlias,
        "keyPassword" to expectedKeyPassword,
      )
    }

    signingConfigImpl.from(
      services.provider {
        val slowFetch = fetchProperties()
        SigningConfigInfo(
          File(slowFetch["storeFile"] as String),
          slowFetch["storePassword"] as String,
          slowFetch["keyAlias"] as String,
          slowFetch["keyPassword"] as String,
          java.security.KeyStore.getDefaultType(),
        )
      }
    )

    assertThat(signingConfigImpl.storeFile.get().absolutePath).isEqualTo(expectedStoreFile.absolutePath)
    assertThat(signingConfigImpl.storePassword.get()).isEqualTo(expectedStorePassword)
    assertThat(signingConfigImpl.keyAlias.get()).isEqualTo(expectedKeyAlias)
    assertThat(signingConfigImpl.keyPassword.get()).isEqualTo(expectedKeyPassword)
    assertThat(signingConfigImpl.storeType.get()).isEqualTo("pkcs12")
  }

  @Test
  fun testHasConfigLazyEvaluation() {
    val services = createVariantPropertiesApiServices()
    val signingConfigImpl = SigningConfigImpl(null, services, 1, null)

    var slowFetchCalled = false
    val expectedStoreFile = File("/path/to/test.keystore")
    val expectedStorePassword = "lazyTestStorePassword"
    val expectedKeyAlias = "lazyTestKeyAlias"
    val expectedKeyPassword = "lazyTestKeyPassword"

    signingConfigImpl.from(
      services.provider {
        slowFetchCalled = true
        SigningConfigInfo(
          expectedStoreFile,
          expectedStorePassword,
          expectedKeyAlias,
          expectedKeyPassword,
          java.security.KeyStore.getDefaultType(),
        )
      }
    )

    // hasConfig() and isSigningReady() should be true since provider is set,
    // but they should NOT evaluate the provider (which would set slowFetchCalled to true)
    assertThat(signingConfigImpl.hasConfig()).isTrue()
    assertThat(slowFetchCalled).isFalse()

    // Only when we resolve the property values should the provider be evaluated
    assertThat(signingConfigImpl.storeFile.get().absolutePath).isEqualTo(expectedStoreFile.absolutePath)
    assertThat(slowFetchCalled).isTrue()
  }
}
