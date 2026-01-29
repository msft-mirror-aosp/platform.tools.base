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

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

/**
 * A fake [GoogleMavenRepositoryV2Host] used for testing. It is configured to avoid fetching data from the network and use [readDefaultData]
 * to return sample packages.
 */
class FakeGoogleMavenRepositoryV2Host : GoogleMavenRepositoryV2Host {

  override val cacheDir: Path? = null

  override fun readUrlData(url: String, timeout: Int, lastModified: Long): NetworkCache.ReadUrlDataResult =
    throw IllegalStateException("Should not be called")

  override fun readDefaultData(relative: String): InputStream? {
    val samplePackages =
      """
      {
        "packages": [
          {
            "packageId": "com.android.support",
            "artifacts": [
              {
                "artifactId": "appcompat",
                "versions": [
                  {
                    "version": "1.0.0"
                  },
                  {
                    "version": "1.0.1-preview"
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
    GZIPOutputStream(byteArrayOutputStream).use { gzipOutputStream -> gzipOutputStream.write(samplePackages.toByteArray(Charsets.UTF_8)) }
    return ByteArrayInputStream(byteArrayOutputStream.toByteArray())
  }

  override fun error(throwable: Throwable, message: String?) {}
}
