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

package com.android.build.gradle.integration.multiplatform.v2

import com.android.build.gradle.integration.common.fixture.GradleTestProjectBuilder
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.ide.common.signing.KeystoreHelper
import com.android.testutils.truth.PathSubject
import com.github.javaparser.utils.StringEscapeUtils
import org.junit.Rule
import org.junit.Test

class KotlinMultiplatformSigningTest {

    @get:Rule
    val project = GradleTestProjectBuilder()
        .fromTestProject("kotlinMultiplatform")
        .create()

    @Test
    fun testDefaultSigningConfig() {
        TestFileUtils.appendToFile(
            project.getSubproject("kmpSecondLib").ktsBuildFile,
            """
                kotlin {
                    android {
                        withDeviceTestBuilder {}
                    }
                }
            """.trimIndent()
        )

        project.executor().run(":kmpSecondLib:assembleAndroidTest", ":kmpSecondLib:signingConfigWriterAndroidDeviceTest")
        project.getSubproject("kmpSecondLib").assertApk(
            ApkSelector.NO_BUILD_TYPE.forTestSuite("androidTest")
        ) {
            javaResources().contains("META-INF/CERT.RSA")
            javaResources().contains("META-INF/CERT.SF")
        }

        val signingConfigData = project.getSubproject("kmpSecondLib").getIntermediateFile(
            "signing_config_data", "androidDeviceTest", "signingConfigWriterAndroidDeviceTest", "signing-config-data.json")
        PathSubject.assertThat(signingConfigData).contains("\"keyAlias\":\"AndroidDebugKey\"")
    }

    @Test
    fun testSingingConfigWithDsl() {
        val storePassword = "storePassword"
        val keyPassword = "keyPassword"
        val keyAlias = "key0"

        val keyStoreFile = project.file("keystore")
        KeystoreHelper.createNewStore(
            "jks",
            keyStoreFile,
            storePassword,
            keyPassword,
            keyAlias,
            "CN=Bundle signing test",
            100
        )

        TestFileUtils.appendToFile(
            project.getSubproject("kmpSecondLib").ktsBuildFile,
            """
                kotlin {
                    android {
                        withDeviceTestBuilder {

                        }.configure {
                            signing.storeFile = File("${StringEscapeUtils.escapeJava(keyStoreFile.absolutePath)}")
                            signing.storePassword = "$storePassword"
                            signing.keyAlias = "$keyAlias"
                            signing.keyPassword = "$keyPassword"
                        }
                    }
                }
            """.trimIndent()
        )
        project.executor().run(":kmpSecondLib:assembleAndroidTest", ":kmpSecondLib:signingConfigWriterAndroidDeviceTest")

        project.getSubproject("kmpSecondLib").assertApk(
            ApkSelector.NO_BUILD_TYPE.forTestSuite("androidTest")
        ) {
            javaResources().contains("META-INF/CERT.RSA")
            javaResources().contains("META-INF/CERT.SF")
        }

        val signingConfigData = project.getSubproject("kmpSecondLib").getIntermediateFile(
            "signing_config_data", "androidDeviceTest", "signingConfigWriterAndroidDeviceTest", "signing-config-data.json")
        PathSubject.assertThat(signingConfigData).contains("\"keyAlias\":\"key0\"")
    }
}
