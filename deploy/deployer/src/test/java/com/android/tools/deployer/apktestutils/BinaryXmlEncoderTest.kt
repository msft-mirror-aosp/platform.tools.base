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
package com.android.tools.deployer.apktestutils

import com.android.tools.deployer.model.ApkParser
import com.android.tools.manifest.parser.ManifestInfo
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BinaryXmlEncoderTest {

  @Rule @JvmField val tmpFolder = TemporaryFolder()

  @Test
  fun testEncodeSimpleManifest() {
    val manifestBytes = ManifestBuilder("com.example.simpleapp").apply { activity(".MainActivity", isLauncher = true) }.buildBinaryXml()

    val manifest = ManifestInfo.parseBinaryFromStream(ByteArrayInputStream(manifestBytes))
    assertEquals("com.example.simpleapp", manifest.applicationId)
    assertEquals(1, manifest.activities().size)

    val activity = manifest.activities()[0]
    assertEquals("com.example.simpleapp.MainActivity", activity.qualifiedName)
    assertTrue(activity.isEnabled)
    assertTrue(activity.isExported)
    assertTrue(activity.hasAction("android.intent.action.MAIN"))
    assertTrue(activity.hasCategory("android.intent.category.LAUNCHER"))
  }

  @Test
  fun testEncodeMultipleActivitiesAndAliases() {
    val manifestBytes =
      ManifestBuilder("com.example.multiapp")
        .apply {
          activity(".MainActivity", isLauncher = true)
          activity(".PrivateActivity") { exported = false }
          activity(".AliasActivity") {
            aliasOf(".MainActivity")
            exported = true
          }
        }
        .buildBinaryXml()

    val manifest = ManifestInfo.parseBinaryFromStream(ByteArrayInputStream(manifestBytes))
    assertEquals("com.example.multiapp", manifest.applicationId)
    assertEquals(3, manifest.activities().size)

    val main = manifest.activities().first { it.qualifiedName == "com.example.multiapp.MainActivity" }
    assertTrue(main.isExported)
    assertFalse(main.isAlias)

    val privateAct = manifest.activities().first { it.qualifiedName == "com.example.multiapp.PrivateActivity" }
    assertFalse(privateAct.isExported)
    assertFalse(privateAct.isAlias)

    val alias = manifest.activities().first { it.qualifiedName == "com.example.multiapp.AliasActivity" }
    assertTrue(alias.isExported)
    assertTrue(alias.isAlias)
    assertEquals("com.example.multiapp.MainActivity", alias.targetActivity)
  }

  @Test
  fun testApkWithDynamicManifest() {
    val apkFile = tmpFolder.newFile("custom.apk")
    Apk {
        Manifest("com.custom.dynamic") {
          activity(".DynamicLauncher", isLauncher = true)
          activity(".SecondaryActivity")
        }
        BinaryFile("classes.dex", ByteArray(1024))
      }
      .writeTo(apkFile)

    val apk = ApkParser.parse(apkFile.absolutePath)
    assertEquals("com.custom.dynamic", apk.packageName)
    assertEquals(2, apk.activities.size)

    val launcher = apk.activities.first { it.qualifiedName == "com.custom.dynamic.DynamicLauncher" }
    assertTrue(launcher.hasAction("android.intent.action.MAIN"))
    assertTrue(launcher.hasCategory("android.intent.category.LAUNCHER"))
  }
}
