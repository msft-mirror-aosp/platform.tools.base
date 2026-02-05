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

package com.android.build.gradle.internal.tasks

import com.android.bundle.DeviceGroup
import com.android.bundle.DeviceGroupConfig
import com.android.bundle.DeviceId
import com.android.bundle.DeviceRam
import com.android.bundle.SystemFeature
import com.android.bundle.SystemOnChip
import java.io.StringReader
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException

class DeviceTargetingConfigParserTest {

  lateinit var documentBuilder: DocumentBuilder

  @Before
  fun setUp() {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    documentBuilder = factory.newDocumentBuilder()
  }

  @Test
  fun testParseCustomConfig_allElements() {
    val customConfigXML =
      """
            <config:device-targeting-config
              xmlns:config="http://schemas.android.com/apk/config">

              <config:device-group name="allOptions">
                <config:device-selector ram-min-bytes="1024" ram-max-bytes="1234567">
                  <config:included-device-id brand="google" device="redfin"/>
                  <config:included-device-id brand="google" device="sailfish"/>
                  <config:included-device-id brand="good-brand"/>
                  <config:excluded-device-id brand="brand-only"/>
                  <config:excluded-device-id brand="google" device="caiman"/>
                  <config:required-system-feature name="android.hardware.bluetooth"/>
                  <config:required-system-feature name="android.hardware.location"/>
                  <config:forbidden-system-feature name="android.hardware.camera"/>
                  <config:forbidden-system-feature name="mindcontrol.laser"/>
                  <config:system-on-chip manufacturer="Sinclair" model="ZX80"/>
                  <config:system-on-chip manufacturer="Commodore" model="C64"/>
                </config:device-selector>
              </config:device-group>

            </config:device-targeting-config>
            """
    val customConfigDoc = documentBuilder.parse(InputSource(StringReader(customConfigXML)))

    val allOptionsGroup = DeviceGroup.newBuilder().setName("allOptions")
    allOptionsGroup
      .addDeviceSelectorsBuilder()
      .setDeviceRam(DeviceRam.newBuilder().setMinBytes(1024L).setMaxBytes(1234567L))
      .addIncludedDeviceIds(DeviceId.newBuilder().setBuildBrand("google").setBuildDevice("redfin"))
      .addIncludedDeviceIds(DeviceId.newBuilder().setBuildBrand("google").setBuildDevice("sailfish"))
      .addIncludedDeviceIds(DeviceId.newBuilder().setBuildBrand("good-brand"))
      .addExcludedDeviceIds(DeviceId.newBuilder().setBuildBrand("brand-only"))
      .addExcludedDeviceIds(DeviceId.newBuilder().setBuildBrand("google").setBuildDevice("caiman"))
      .addRequiredSystemFeatures(SystemFeature.newBuilder().setName("android.hardware.bluetooth"))
      .addRequiredSystemFeatures(SystemFeature.newBuilder().setName("android.hardware.location"))
      .addForbiddenSystemFeatures(SystemFeature.newBuilder().setName("android.hardware.camera"))
      .addForbiddenSystemFeatures(SystemFeature.newBuilder().setName("mindcontrol.laser"))
      .addSystemOnChips(SystemOnChip.newBuilder().setManufacturer("Sinclair").setModel("ZX80"))
      .addSystemOnChips(SystemOnChip.newBuilder().setManufacturer("Commodore").setModel("C64"))

    val customConfigProto = DeviceGroupConfig.newBuilder().addDeviceGroups(allOptionsGroup).build()

    val parser = DeviceTargetingConfigParser(customConfigDoc)
    val parsedCustomConfigProto = parser.parseConfig()

    assertEquals(customConfigProto, parsedCustomConfigProto)
  }

  @Test
  fun testParseCustomConfig_multipleSelectors() {
    val customConfigXML =
      """
            <config:device-targeting-config
              xmlns:config="http://schemas.android.com/apk/config">

              <config:device-group name="multipleSelectors">
                <config:device-selector ram-min-bytes="12345678">
                  <config:included-device-id brand="google"/>
                  <config:excluded-device-id brand="google" device="caiman"/>
                </config:device-selector>
                <config:device-selector ram-min-bytes="24567890">
                  <config:included-device-id brand="samsung"/>
                </config:device-selector>
              </config:device-group>

            </config:device-targeting-config>
            """
    val customConfigDoc = documentBuilder.parse(InputSource(StringReader(customConfigXML)))

    val multipleSelectorsGroup = DeviceGroup.newBuilder().setName("multipleSelectors")
    multipleSelectorsGroup
      .addDeviceSelectorsBuilder()
      .setDeviceRam(DeviceRam.newBuilder().setMinBytes(12345678L))
      .addIncludedDeviceIds(DeviceId.newBuilder().setBuildBrand("google"))
      .addExcludedDeviceIds(DeviceId.newBuilder().setBuildBrand("google").setBuildDevice("caiman"))
    multipleSelectorsGroup
      .addDeviceSelectorsBuilder()
      .setDeviceRam(DeviceRam.newBuilder().setMinBytes(24567890L))
      .addIncludedDeviceIds(DeviceId.newBuilder().setBuildBrand("samsung"))

    val customConfigProto = DeviceGroupConfig.newBuilder().addDeviceGroups(multipleSelectorsGroup).build()

    val parser = DeviceTargetingConfigParser(customConfigDoc)
    val parsedCustomConfigProto = parser.parseConfig()

    assertEquals(customConfigProto, parsedCustomConfigProto)
  }

  @Test
  fun testParseCustomConfig_ram() {
    val customConfigXML =
      """
            <config:device-targeting-config
              xmlns:config="http://schemas.android.com/apk/config">

              <config:device-group name="highRam">
                <config:device-selector ram-min-bytes="500"/>
              </config:device-group>

              <config:device-group name="mediumRam">
                <config:device-selector ram-min-bytes="300" ram-max-bytes="500"/>
              </config:device-group>

              <config:device-group name="lowRam">
                <config:device-selector ram-max-bytes="300"/>
              </config:device-group>

              <config:device-group name="anyRam">
                <config:device-selector/>
              </config:device-group>

            </config:device-targeting-config>
            """
    val customConfigDoc = documentBuilder.parse(InputSource(StringReader(customConfigXML)))

    val highRamGroup = DeviceGroup.newBuilder().setName("highRam")
    highRamGroup.addDeviceSelectorsBuilder().setDeviceRam(DeviceRam.newBuilder().setMinBytes(500L))

    val mediumRamGroup = DeviceGroup.newBuilder().setName("mediumRam")
    mediumRamGroup.addDeviceSelectorsBuilder().setDeviceRam(DeviceRam.newBuilder().setMinBytes(300L).setMaxBytes(500L))

    val lowRamGroup = DeviceGroup.newBuilder().setName("lowRam")
    lowRamGroup.addDeviceSelectorsBuilder().setDeviceRam(DeviceRam.newBuilder().setMaxBytes(300L))

    val anyRamGroup = DeviceGroup.newBuilder().setName("anyRam")
    anyRamGroup.addDeviceSelectorsBuilder().setDeviceRam(DeviceRam.getDefaultInstance())

    val customConfigProto =
      DeviceGroupConfig.newBuilder()
        .addDeviceGroups(highRamGroup)
        .addDeviceGroups(mediumRamGroup)
        .addDeviceGroups(lowRamGroup)
        .addDeviceGroups(anyRamGroup)
        .build()

    val parser = DeviceTargetingConfigParser(customConfigDoc)
    val parsedCustomConfigProto = parser.parseConfig()

    assertEquals(customConfigProto, parsedCustomConfigProto)
  }

  @Test
  fun testParseCustomConfig_invalidElement() {
    val customConfigXML =
      """
        <config:device-targeting-config
              xmlns:config="http://schemas.android.com/apk/config">
        </config:device-targeting-config>
        """
    val customConfigDoc = documentBuilder.parse(InputSource(StringReader(customConfigXML)))

    val parser = DeviceTargetingConfigParser(customConfigDoc)
    val exception = assertFailsWith<DeviceTargetingConfigParser.InvalidDeviceTargetingConfigException> { parser.parseConfig() }
    assertEquals(exception.message, "The DeviceTargetingConfig xml provided is invalid.")
    assertTrue(exception.cause is SAXParseException)
    assertContains(
      (exception.cause as SAXParseException).message!!,
      "The content of element 'config:device-targeting-config' is not complete.",
    )
  }
}
