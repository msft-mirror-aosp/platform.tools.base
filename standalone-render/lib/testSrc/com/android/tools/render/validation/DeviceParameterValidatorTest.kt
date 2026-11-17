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

package com.android.tools.render.validation

import com.android.sdklib.devices.Device
import com.android.sdklib.devices.DeviceManager
import com.android.sdklib.devices.DeviceResourceTable
import com.android.utils.NullLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceParameterValidatorTest {

  private fun assertValid(device: String?) {
    val issues = DeviceParameterValidator.validate(device)
    assertTrue("Device '$device' should be valid but got: $issues", issues.isEmpty())
  }

  /** Asserts that [device] is rejected with exactly one error whose message contains every entry of [expectedMessageParts]. */
  private fun assertSingleError(device: String, vararg expectedMessageParts: String): ValidationIssue {
    val issues = DeviceParameterValidator.validate(device)
    assertEquals("Device '$device' should produce exactly one issue but got: $issues", 1, issues.size)
    val issue = issues.single()
    assertEquals(ValidationSeverity.ERROR, issue.severity)
    assertEquals(ValidationCategory.PARAMETER, issue.category)
    assertEquals("device", issue.target)
    assertEquals(device, issue.value)
    for (part in expectedMessageParts) {
      assertTrue("Message for '$device' should contain '$part' but was: ${issue.message}", issue.message.contains(part))
    }
    return issue
  }

  @Test
  fun testEmptyDeviceUsesDefaultDevice() {
    assertValid(null)
    assertValid("")
    assertValid("   ")
  }

  @Test
  fun testDeviceReferencesByIdOrNameAreValid() {
    assertValid("id:pixel_5")
    assertValid("id:wearos_small_round")
    assertValid("name:Pixel 5")
    assertValid("name:Nexus 7 (2012)")
  }

  @Test
  fun testEmptyOrPaddedDeviceReferenceProducesError() {
    assertSingleError("id:", "missing the device id after 'id:'")
    assertSingleError("name:  ", "missing the device name after 'name:'")
    // The renderer looks the reference up verbatim, so surrounding whitespace never matches a device.
    assertSingleError("id: pixel_5", "whitespace", "'id: pixel_5'")
    assertSingleError("name:Pixel 5 ", "whitespace", "'name:Pixel 5 '")
  }

  @Test
  fun testUnsupportedDeviceFormProducesErrorWithHint() {
    assertSingleError(
      "pixel_5",
      "must be empty, 'id:<device id>', 'name:<device name>' or 'spec:<device spec>'",
      "did you mean 'id:pixel_5'?",
    )
    assertSingleError("Pixel 5", "did you mean 'name:Pixel 5'?")
    assertSingleError("ID:pixel_5", "'id:' prefix is case-sensitive")
    assertSingleError("Spec:width=411dp,height=891dp", "'spec:' prefix is case-sensitive")
    val issue = assertSingleError("device:pixel_5", "got: 'device:pixel_5'")
    assertTrue(!issue.message.contains("did you mean"))
  }

  @Test
  fun testReferenceDeviceSpecsAreValid() {
    // Devices.PHONE, Devices.FOLDABLE, Devices.TABLET and Devices.DESKTOP from androidx.compose.ui.tooling.preview.
    assertValid("spec:width=411dp,height=891dp")
    assertValid("spec:width=673dp,height=841dp")
    assertValid("spec:width=1280dp,height=800dp,dpi=240")
    assertValid("spec:width=1920dp,height=1080dp,dpi=160")
  }

  @Test
  fun testCustomDeviceSpecsAreValid() {
    assertValid("spec:width=1080px,height=2400px,dpi=420")
    assertValid("spec:width=411.5dp,height=891dp")
    assertValid("spec:width=411dp,height=891dp,orientation=landscape,cutout=punch_hole,navigation=buttons")
    assertValid("spec:width=200dp,height=200dp,isRound=true,chinSize=30dp")
    assertValid("spec:width=200dp,height=200dp,isRound=false,chinSize=0dp")
    assertValid("spec:width=411dp,height=891dp,windowPosture=tabletop,pointerPrecision=fine,keyboardKind=physical")
    assertValid("spec:width=411dp,height=891dp,hasCamera=false,hasMicrophone=false,viewingDistance=far")
    assertValid("spec:width=411dp,height=891dp,expand=vertical,maxHeight=2000dp,maxWidth=1000dp")
    assertValid("spec:width=1080px,height=2400px,expand=HORIZONTAL,maxHeight=5000PX,maxWidth=3000px")
    assertValid("spec:width=411dp,height=891dp,expand=Vertical,maxHeight=0.5dp")
    assertValid("spec:width=411dp,height=891dp,scrollTo=end")
    assertValid("spec:width=411dp,height=891dp,scrollTo=END")
    assertValid("spec:width=411dp,height=891dp,expand=vertical,maxHeight=2000dp,scrollTo=end")
    assertValid("spec:id=my_device,width=900px,height=1900px")
    // Whitespace around names, values and separators is trimmed by the renderer, trailing separators are ignored.
    assertValid("spec: width = 411dp , height = 891dp ,")
  }

  @Test
  fun testParentBasedDeviceSpecsAreValid() {
    assertValid("spec:parent=pixel_5")
    assertValid("spec:parent=pixel_5,orientation=landscape")
    assertValid("spec:parent=pixel_fold,windowPosture=book,cutout=none,navigation=gesture")
    assertValid("spec:parent=pixel_5,hasCamera=true,hasMicrophone=false,keyboardKind=none,pointerPrecision=coarse,viewingDistance=near")
    assertValid("spec:parent=pixel_5,expand=vertical,maxHeight=2000dp")
    assertValid("spec:parent=pixel_5,expand=HORIZONTAL,maxWidth=3000PX,maxHeight=5000px")
    assertValid("spec:parent=pixel_5,scrollTo=end")
    assertValid("spec:parent=pixel_5,scrollTo=END")
  }

  @Test
  fun testMissingRequiredDimensionsProduceErrors() {
    assertSingleError("spec:width=411dp", "must declare 'height'", "spec:width=411dp,height=891dp")
    assertSingleError("spec:height=891dp,dpi=420", "must declare 'width'")

    val issues = DeviceParameterValidator.validate("spec:")
    assertEquals(2, issues.size)
    assertTrue(issues.any { it.message.contains("must declare 'width'") })
    assertTrue(issues.any { it.message.contains("must declare 'height'") })
  }

  @Test
  fun testLegacyDeviceSpecFormatProducesActionableErrors() {
    // Devices.PHONE in old versions of ui-tooling; the renderer ignores it entirely and falls back to the default device.
    val issues = DeviceParameterValidator.validate("spec:shape=Normal,width=360,height=640,unit=dp,dpi=480")
    assertEquals(4, issues.size)
    assertTrue(issues.all { it.severity == ValidationSeverity.ERROR && it.target == "device" })
    assertTrue(issues.any { it.message.contains("'shape' belongs to the legacy device spec format") })
    assertTrue(issues.any { it.message.contains("'unit' belongs to the legacy device spec format") })
    val dimensionMessage = "must be a positive dimension with a 'dp' or 'px' suffix"
    assertTrue(issues.any { it.message.contains("'width' $dimensionMessage") && it.message.contains("got: '360'") })
    assertTrue(issues.any { it.message.contains("'height' $dimensionMessage") && it.message.contains("got: '640'") })
    assertTrue(issues.first { it.message.contains("legacy") }.message.contains("spec:width=360dp,height=640dp,dpi=480"))
  }

  @Test
  fun testInvalidDimensionValuesProduceErrors() {
    assertSingleError("spec:width=411,height=891dp", "'width' must be a positive dimension", "got: '411'")
    assertSingleError("spec:width=411dp,height=891pt", "'height' must be a positive dimension", "got: '891pt'")
    assertSingleError("spec:width=411DP,height=891dp", "'width' must be a positive dimension", "got: '411DP'")
    assertSingleError("spec:width=0dp,height=891dp", "'width' must be a positive dimension", "got: '0dp'")
    assertSingleError("spec:width=-411dp,height=891dp", "'width' must be a positive dimension", "got: '-411dp'")
    assertSingleError("spec:width=NaNdp,height=891dp", "'width' must be a positive dimension", "got: 'NaNdp'")
    assertSingleError("spec:width=Infinitydp,height=891dp", "'width' must be a positive dimension", "got: 'Infinitydp'")
    assertSingleError("spec:width=411dp,height=891dp,chinSize=-1dp", "'chinSize' must be a non-negative dimension", "got: '-1dp'")
    assertSingleError("spec:width=411dp,height=891dp,chinSize=30", "'chinSize' must be a non-negative dimension", "got: '30'")
    assertSingleError("spec:width=411dp,height=891dp,maxHeight=0dp", "'maxHeight' must be a positive dimension", "got: '0dp'")
    assertSingleError("spec:width=411dp,height=891dp,maxHeight=0.2dp", "'maxHeight' must be a positive dimension", "got: '0.2dp'")
    assertSingleError("spec:parent=pixel_5,maxWidth=0.49px", "'maxWidth' must be a positive dimension", "got: '0.49px'")
    assertSingleError("spec:parent=pixel_5,maxHeight=-10dp", "'maxHeight' must be a positive dimension", "got: '-10dp'")
    assertSingleError("spec:parent=pixel_5,maxWidth=2000", "'maxWidth' must be a positive dimension", "got: '2000'")
    assertSingleError("spec:parent=pixel_5,maxHeight=2000pt", "'maxHeight' must be a positive dimension", "got: '2000pt'")
    assertSingleError("spec:parent=pixel_5,maxHeight=NaNdp", "'maxHeight' must be a positive dimension", "got: 'NaNdp'")
    assertSingleError("spec:parent=pixel_5,maxWidth=Infinitypx", "'maxWidth' must be a positive dimension", "got: 'Infinitypx'")
  }

  @Test
  fun testMismatchedDimensionUnitsProduceError() {
    assertSingleError("spec:width=411dp,height=891px", "'width', 'height' must use the same unit", "width=411dp, height=891px")
    assertSingleError(
      "spec:width=411dp,height=891dp,chinSize=30px",
      "'width', 'height', 'chinSize' must use the same unit",
      "width=411dp, height=891dp, chinSize=30px",
    )
    assertSingleError(
      "spec:width=411dp,height=891dp,maxHeight=2000px",
      "'width', 'height', 'maxHeight' must use the same unit",
      "width=411dp, height=891dp, maxHeight=2000px",
    )
    assertSingleError(
      "spec:width=411dp,height=891dp,maxHeight=2000PX",
      "'width', 'height', 'maxHeight' must use the same unit",
      "width=411dp, height=891dp, maxHeight=2000PX",
    )
    assertSingleError(
      "spec:width=411dp,height=891dp,maxWidth=1000px",
      "'width', 'height', 'maxWidth' must use the same unit",
      "width=411dp, height=891dp, maxWidth=1000px",
    )
    assertSingleError(
      "spec:parent=pixel_5,maxHeight=2000dp,maxWidth=1000px",
      "'maxHeight', 'maxWidth' must use the same unit",
      "maxHeight=2000dp, maxWidth=1000px",
    )
    assertSingleError(
      "spec:parent=pixel_5,maxHeight=2000DP,maxWidth=1000PX",
      "'maxHeight', 'maxWidth' must use the same unit",
      "maxHeight=2000DP, maxWidth=1000PX",
    )
  }

  @Test
  fun testInvalidOptionalValuesProduceErrors() {
    assertSingleError("spec:width=411dp,height=891dp,dpi=high", "'dpi' must be a positive integer", "got: 'high'")
    assertSingleError("spec:width=411dp,height=891dp,dpi=0", "'dpi' must be a positive integer", "got: '0'")
    assertSingleError("spec:width=411dp,height=891dp,dpi=420.5", "'dpi' must be a positive integer", "got: '420.5'")
    // Booleans are parsed with toBooleanStrictOrNull, so only the lowercase literals are accepted.
    assertSingleError("spec:width=411dp,height=891dp,isRound=True", "'isRound' must be 'true' or 'false'", "got: 'True'")
    assertSingleError("spec:width=411dp,height=891dp,hasCamera=yes", "'hasCamera' must be 'true' or 'false'", "got: 'yes'")
    // Enum values are case-sensitive and the message lists the accepted ones.
    assertSingleError(
      "spec:width=411dp,height=891dp,orientation=Landscape",
      "'orientation' must be one of 'portrait', 'landscape'",
      "got: 'Landscape'",
    )
    assertSingleError(
      "spec:width=411dp,height=891dp,cutout=notch",
      "'cutout' must be one of 'none', 'corner', 'double', 'punch_hole', 'tall'",
    )
    assertSingleError("spec:width=411dp,height=891dp,navigation=swipe", "'navigation' must be one of 'buttons', 'gesture'")
    assertSingleError("spec:parent=pixel_5,windowPosture=folded", "'windowPosture' must be one of 'flat', 'tabletop', 'book'")
    assertSingleError("spec:parent=pixel_5,pointerPrecision=mouse", "'pointerPrecision' must be one of 'coarse', 'fine', 'blunt', 'none'")
    assertSingleError("spec:parent=pixel_5,keyboardKind=qwerty", "'keyboardKind' must be one of 'virtual', 'physical', 'none'")
    assertSingleError("spec:parent=pixel_5,viewingDistance=close", "'viewingDistance' must be one of 'near', 'medium', 'far'")
    assertSingleError("spec:width=411dp,height=891dp,expand=both", "'expand' must be one of 'vertical', 'horizontal'", "got: 'both'")
    assertSingleError("spec:parent=pixel_5,expand=none", "'expand' must be one of 'vertical', 'horizontal'", "got: 'none'")
    assertSingleError("spec:width=411dp,height=891dp,scrollTo=middle", "'scrollTo' must be 'end'", "got: 'middle'")
    assertSingleError("spec:parent=pixel_5,scrollTo=none", "'scrollTo' must be 'end'", "got: 'none'")
  }

  @Test
  fun testUnknownParametersProduceErrors() {
    assertSingleError(
      "spec:width=411dp,height=891dp,density=420",
      "Unknown device spec parameter 'density'",
      "Supported parameters: 'width', 'height', 'dpi'",
    )
    // Parameter names are case-sensitive; a case-insensitive match is suggested.
    assertSingleError("spec:Width=411dp,height=891dp,width=411dp", "Unknown device spec parameter 'Width' (did you mean 'width'?)")
    assertSingleError("spec:parent=pixel_5,EXPAND=vertical", "Unknown device spec parameter 'EXPAND' (did you mean 'expand'?)")
    assertSingleError("spec:parent=pixel_5,SCROLLTO=end", "Unknown device spec parameter 'SCROLLTO' (did you mean 'scrollTo'?)")
    assertSingleError("spec:parent=pixel_5,foo=bar", "Unknown device spec parameter 'foo'", "Supported parameters: 'parent', 'orientation'")
  }

  @Test
  fun testCustomParametersCombinedWithParentProduceErrors() {
    assertSingleError("spec:parent=pixel_5,width=411dp", "'width' cannot be combined with 'parent'", "Parameters supported with 'parent'")
    assertSingleError("spec:parent=pixel_5,dpi=420", "'dpi' cannot be combined with 'parent'")
    assertSingleError("spec:parent=pixel_5,isRound=true", "'isRound' cannot be combined with 'parent'")
    // An unsupported custom dimension alongside 'parent' must not trigger a spurious unit-mismatch error with maxHeight.
    assertSingleError("spec:parent=pixel_5,width=411dp,maxHeight=2000px", "'width' cannot be combined with 'parent'")
  }

  @Test
  fun testMalformedSegmentsProduceErrors() {
    // A wrong separator makes the rest of the string part of the first value, so `height` is also missing.
    val wrongSeparatorIssues = DeviceParameterValidator.validate("spec:width=411dp;height=891dp")
    assertEquals(2, wrongSeparatorIssues.size)
    assertTrue(
      wrongSeparatorIssues.any {
        it.message.contains("'width' must be a positive dimension") && it.message.contains("got: '411dp;height=891dp'")
      }
    )
    assertTrue(wrongSeparatorIssues.any { it.message.contains("must declare 'height'") })

    assertSingleError("spec:width=411dp,height=891dp,landscape", "'landscape' must have the form '<name>=<value>'")
    assertSingleError("spec:width=411dp,height=891dp,=420", "'=420' must have the form '<name>=<value>'")
    assertSingleError("spec:width=411dp,height=891dp,dpi=", "'dpi' has no value")
    assertSingleError("spec:width=,height=891dp", "'width' has no value")
    assertSingleError("spec:parent=", "'parent' has no value")
    assertSingleError("spec:parent=pixel_5,scrollTo=", "'scrollTo' has no value")
  }

  @Test
  fun testRepeatedParametersProduceError() {
    assertSingleError("spec:width=411dp,height=891dp,width=500dp", "'width' is specified more than once")
    assertSingleError("spec:parent=pixel_5,parent=pixel_4", "'parent' is specified more than once")
    assertSingleError("spec:parent=pixel_5,scrollTo=end,scrollTo=end", "'scrollTo' is specified more than once")
  }

  @Test
  fun testMultipleIssuesAreAllReported() {
    val issues = DeviceParameterValidator.validate("spec:width=411,dpi=high,foo=1")
    assertEquals(4, issues.size)
    assertTrue(issues.any { it.message.contains("'width' must be a positive dimension") })
    assertTrue(issues.any { it.message.contains("'dpi' must be a positive integer") })
    assertTrue(issues.any { it.message.contains("Unknown device spec parameter 'foo'") })
    assertTrue(issues.any { it.message.contains("must declare 'height'") })
    assertTrue(issues.all { it.value == "spec:width=411,dpi=high,foo=1" })
  }

  @Test
  fun testPreviewValidatorReportsDeviceIssues() {
    val validator = PreviewValidator()
    assertTrue(validator.validateParams(mapOf("device" to "id:pixel_5")).issues.isEmpty())
    assertTrue(validator.validateParams(mapOf("device" to "spec:width=411dp,height=891dp")).issues.isEmpty())

    val result = validator.validateParams(mapOf("device" to "pixel_5", "widthDp" to "100"))
    assertEquals(1, result.errors.size)
    assertEquals("device", result.errors.single().target)
  }

  @Test
  fun testParameterValidatorsDelegatesToDeviceParameterValidator() {
    assertTrue(ParameterValidators.validateDevice("id:pixel_5").isEmpty())
    val issues = ParameterValidators.validateDevice("pixel_5")
    assertEquals(1, issues.size)
    assertEquals("device", issues.single().target)
  }

  @Test
  fun testDevicesInTheCatalogAreValid() {
    for (device in DEVICES_IN_ANDROIDX_PREVIEW_CONSTANTS + TV_DEVICES) {
      val issues = DeviceParameterValidator.validate(device, catalog)
      assertTrue("Device '$device' should be in the catalog but got: $issues", issues.isEmpty())
    }
  }

  @Test
  fun testSpecsWithAParentInTheCatalogAreValid() {
    assertTrue(DeviceParameterValidator.validate("spec:parent=tv_1080p", catalog).isEmpty())
    assertTrue(DeviceParameterValidator.validate("spec:parent=pixel_5,orientation=landscape", catalog).isEmpty())
    assertTrue(DeviceParameterValidator.validate("spec:parent=pixel_5,expand=vertical,maxHeight=2000dp", catalog).isEmpty())
    assertTrue(DeviceParameterValidator.validate("spec:parent=pixel_5,scrollTo=end", catalog).isEmpty())
  }

  @Test
  fun testUnknownDeviceIdIsAnErrorWithASuggestion() {
    val issue = DeviceParameterValidator.validate("id:tv_1080", catalog).single()

    assertEquals(ValidationSeverity.ERROR, issue.severity)
    assertEquals("device", issue.target)
    assertEquals("id:tv_1080", issue.value)
    assertEquals(
      "Parameter 'device' references an unknown device id, got: 'id:tv_1080' (did you mean 'id:tv_1080p'?). " +
        "The preview would be rendered on the default device instead. Only the built-in device definitions are available, " +
        "not devices defined in Android Studio's Device Manager",
      issue.message,
    )
  }

  @Test
  fun testUnknownDeviceIdWithOmittedTokenSuggestsContainingDevice() {
    val issue = DeviceParameterValidator.validate("id:pixel_9_xl", catalog).single()

    assertTrue(issue.message, issue.message.contains("(did you mean 'id:pixel_9_pro_xl'?)"))
  }

  @Test
  fun testUnknownDeviceIdWithMultipleTiedSuggestionsListsThemAll() {
    val twoTies = DeviceParameterValidator.validate("id:3.5in WQVGA", catalog).single()
    assertTrue(twoTies.message, twoTies.message.contains("(did you mean 'id:3.3in WQVGA' or 'id:3.4in WQVGA'?)"))

    val tokenTies = DeviceParameterValidator.validate("id:wearos_round", catalog).single()
    assertTrue(
      tokenTies.message,
      tokenTies.message.contains("(did you mean 'id:wearos_large_round', 'id:wearos_small_round' or 'id:wearos_xl_round'?)"),
    )

    val fourTies = DeviceParameterValidator.validate("id:pixel_5_pro", catalog).single()
    assertTrue(
      fourTies.message,
      fourTies.message.contains("(did you mean 'id:pixel_6_pro', 'id:pixel_7_pro', 'id:pixel_8_pro' or 'id:pixel_9_pro'?)"),
    )

    // All equally close devices are listed, none is hidden by alphabetical order.
    val sixTies = DeviceParameterValidator.validate("name:Nexus 8", catalog).single()
    assertTrue(
      sixTies.message,
      sixTies.message.contains(
        "(did you mean 'name:Nexus 4', 'name:Nexus 5', 'name:Nexus 6', 'name:Nexus 7', 'name:Nexus 9' or 'name:Nexus S'?)"
      ),
    )
  }

  @Test
  fun testUnknownDevicesWithParenthesesOrFamilyNamesHaveSuggestions() {
    fun hintOf(device: String) = DeviceParameterValidator.validate(device, catalog).single().message.substringBefore(". The preview")

    assertTrue(hintOf("name:Television 1080p"), hintOf("name:Television 1080p").endsWith("(did you mean 'name:Television (1080p)'?)"))
    assertTrue(
      hintOf("name:Automotive (1024p)"),
      hintOf("name:Automotive (1024p)").endsWith("(did you mean 'name:Automotive (1024p landscape)'?)"),
    )
    assertTrue(
      hintOf("name:Television"),
      hintOf("name:Television").endsWith("(did you mean 'name:Television (1080p)', 'name:Television (4K)' or 'name:Television (720p)'?)"),
    )
    assertTrue(hintOf("id:tv"), hintOf("id:tv").endsWith("(did you mean 'id:tv_1080p', 'id:tv_4k' or 'id:tv_720p'?)"))
    assertTrue(hintOf("id:nexus_one"), hintOf("id:nexus_one").endsWith("(did you mean 'id:Nexus One'?)"))
  }

  @Test
  fun testUnknownDeviceIdWithoutSimilarIdHasNoSuggestion() {
    val issue = DeviceParameterValidator.validate("id:does_not_exist", catalog).single()

    assertEquals(
      "Parameter 'device' references an unknown device id, got: 'id:does_not_exist'. " +
        "The preview would be rendered on the default device instead. Only the built-in device definitions are available, " +
        "not devices defined in Android Studio's Device Manager",
      issue.message,
    )
  }

  @Test
  fun testDeviceIdIsCaseSensitive() {
    val issue = DeviceParameterValidator.validate("id:TV_1080p", catalog).single()

    assertTrue(issue.message, issue.message.contains("(did you mean 'id:tv_1080p'?)"))
  }

  @Test
  fun testUnknownDeviceNameIsAnErrorWithASuggestion() {
    val issue = DeviceParameterValidator.validate("name:Pixel5", catalog).single()

    assertEquals(ValidationSeverity.ERROR, issue.severity)
    assertTrue(issue.message, issue.message.contains("references an unknown device name, got: 'name:Pixel5'"))
    assertTrue(issue.message, issue.message.contains("(did you mean 'name:Pixel 5'?)"))
  }

  @Test
  fun testDeviceIdIsNotAcceptedAsDeviceName() {
    val issue = DeviceParameterValidator.validate("name:tv_1080p", catalog).single()

    assertTrue(issue.message, issue.message.contains("references an unknown device name, got: 'name:tv_1080p'"))
  }

  @Test
  fun testUnknownSpecParentIsAnErrorWithASuggestion() {
    val issue = DeviceParameterValidator.validate("spec:parent=tv_1080,orientation=landscape", catalog).single()

    assertEquals(ValidationSeverity.ERROR, issue.severity)
    assertEquals(
      "Device spec parameter 'parent' references an unknown device id 'tv_1080' (did you mean 'tv_1080p'?) in: " +
        "'spec:parent=tv_1080,orientation=landscape'. It must be the id of a built-in device definition, as accepted by 'id:' " +
        "(not a device defined in Android Studio's Device Manager), otherwise the preview is rendered on the default device",
      issue.message,
    )
  }

  @Test
  fun testSyntaxErrorsAreReportedInsteadOfUnknownDevices() {
    val issue = DeviceParameterValidator.validate("id: tv_1080", catalog).single()

    assertTrue(issue.message, issue.message.contains("must not have whitespace"))
  }

  @Test
  fun testUnknownDevicesAreNotCheckedWithoutACatalog() {
    assertValid("id:does_not_exist")
    assertValid("name:Does not exist")
    assertValid("spec:parent=does_not_exist")
  }

  @Test
  fun testPreviewValidatorChecksDevicesInItsCatalog() {
    val validator = PreviewValidator(catalog)

    assertTrue(validator.validateParams(mapOf("device" to "id:tv_1080p")).issues.isEmpty())
    val result = validator.validateParams(mapOf("device" to "id:tv_1080"))
    assertEquals(1, result.errors.size)
    assertEquals("device", result.errors.single().target)
  }

  @Test
  fun testParameterValidatorsChecksDevicesInTheCatalog() {
    assertTrue(ParameterValidators.validateDevice("id:tv_1080p", catalog).isEmpty())
    assertTrue(ParameterValidators.validateDevice("id:tv_1080p", DeviceCatalog(catalog)).isEmpty())
    assertEquals("device", ParameterValidators.validateDevice("id:tv_1080", catalog).single().target)
    assertEquals("device", ParameterValidators.validateDevice("id:tv_1080", DeviceCatalog(catalog)).single().target)
  }

  @Test
  fun testPreviewValidatorIndexesItsCatalogOnceAndOnlyWhenNeeded() {
    var iterations = 0
    val countingCatalog =
      object : AbstractCollection<Device>() {
        override val size: Int
          get() = catalog.size

        override fun iterator(): Iterator<Device> {
          iterations++
          return catalog.iterator()
        }
      }
    val validator = PreviewValidator(countingCatalog)

    // Custom specs don't reference catalog devices.
    validator.validateParams(mapOf("device" to "spec:width=411dp,height=891dp"))
    assertEquals(0, iterations)

    // Ids are indexed once and shared by `id:` and `spec:parent=`.
    repeat(3) {
      validator.validateParams(mapOf("device" to "id:tv_1080"))
      validator.validateParams(mapOf("device" to "spec:parent=tv_1080"))
    }
    assertEquals(1, iterations)

    // Names are indexed once too.
    repeat(3) { validator.validateParams(mapOf("device" to "name:Pixel5")) }
    assertEquals(2, iterations)
  }

  @Test
  fun testFindSimilarDeviceReferences() {
    val known = listOf("tv_720p", "tv_1080p", "tv_4k", "pixel_5", "pixel_9_pro")

    assertEquals(listOf("tv_1080p"), findSimilarDeviceReferences("tv_1080", known))
    assertEquals(listOf("tv_1080p"), findSimilarDeviceReferences("TV_1080P", known))
    assertEquals(listOf("pixel_5"), findSimilarDeviceReferences("pixel5", known))
    assertEquals(listOf("pixel_9_pro"), findSimilarDeviceReferences("pixel_9pro", known))
    assertEquals(listOf("tv_1080p"), findSimilarDeviceReferences("tv-1080p", known))
    assertEquals(listOf("Pixel 5"), findSimilarDeviceReferences("Pixel5", listOf("Pixel", "Pixel 5", "Pixel 5a")))
    assertEquals(
      listOf("pixel_9_pro_xl"),
      findSimilarDeviceReferences("pixel_9_xl", listOf("pixel_xl", "pixel_2_xl", "pixel_3_xl", "pixel_9_pro", "pixel_9_pro_xl")),
    )
    assertEquals(
      listOf("pixel_2_xl", "pixel_3_xl"),
      findSimilarDeviceReferences("pixel_1_xl", listOf("pixel_2_xl", "pixel_3_xl", "pixel_9_pro_xl")),
    )
    // All ties are returned, sorted whatever the catalog order.
    assertEquals(
      listOf("pixel_2_xl", "pixel_3_xl", "pixel_4_xl", "pixel_xl"),
      findSimilarDeviceReferences("pixel_1_xl", listOf("pixel_xl", "pixel_4_xl", "pixel_2_xl", "pixel_3_xl")),
    )
    // Entries containing all tokens prefer the fewest extra tokens.
    assertEquals(listOf("pixel_9_pro"), findSimilarDeviceReferences("pixel_9", listOf("pixel_9_pro_xl", "pixel_9_pro")))
    // Tokens must appear in order.
    assertEquals(
      listOf("pixel_9_pro_xl"),
      findSimilarDeviceReferences("pixel_9_xl", listOf("xl_9_pro_pixel", "pixel_9_pro_xl")),
    )
    assertTrue(findSimilarDeviceReferences("xl_pixel_9", listOf("pixel_9_pro_xl")).isEmpty())
    assertTrue(findSimilarDeviceReferences("automotive", known).isEmpty())
    assertTrue(findSimilarDeviceReferences("x", emptyList()).isEmpty())
    // A reference without any letter or digit has no suggestion, even for very short ids.
    assertTrue(findSimilarDeviceReferences("___", listOf("tv", "pixel_5")).isEmpty())
    // Duplicates and entries without any letter or digit are ignored.
    assertEquals(listOf("tv_1080p"), findSimilarDeviceReferences("tv_1080", listOf("tv_1080p", "tv_1080p", "")))
    assertTrue(findSimilarDeviceReferences("x", listOf("", "()")).isEmpty())
  }

  private companion object {
    /** The device catalog of the renderer, see `StandaloneConfigurationSettings`. */
    val catalog: Collection<Device> =
      DeviceResourceTable(NullLogger.getLogger(), isSupportedDevice = { true }, DeviceManager.VENDOR_DEVICE_RESOURCES + "devices")
        .getDevices()
        .values()

    /**
     * The `id:` and `name:` values of `androidx.compose.ui.tooling.preview.Devices` (ui-tooling-preview 1.12.1).
     *
     * This is a snapshot: constants added to `Devices` later are not covered until they are added here, but the list cannot fail on its
     * own. It guards against built-in device definitions that `Devices` refers to being removed or renamed in the catalog.
     */
    val DEVICES_IN_ANDROIDX_PREVIEW_CONSTANTS =
      listOf(
        "id:Nexus 7",
        "id:Nexus 7 2013",
        "id:Nexus 5",
        "id:Nexus 6",
        "id:Nexus 9",
        "name:Nexus 10",
        "id:Nexus 5X",
        "id:Nexus 6P",
        "id:pixel_c",
        "id:pixel",
        "id:pixel_xl",
        "id:pixel_2",
        "id:pixel_2_xl",
        "id:pixel_3",
        "id:pixel_3_xl",
        "id:pixel_3a",
        "id:pixel_3a_xl",
        "id:pixel_4",
        "id:pixel_4_xl",
        "id:pixel_4a",
        "id:pixel_5",
        "id:pixel_6",
        "id:pixel_6_pro",
        "id:pixel_6a",
        "id:pixel_7",
        "id:pixel_7_pro",
        "id:pixel_7a",
        "id:pixel_8",
        "id:pixel_8_pro",
        "id:pixel_8a",
        "id:pixel_9",
        "id:pixel_9_pro",
        "id:pixel_9_pro_fold",
        "id:pixel_9_pro_xl",
        "id:pixel_fold",
        "id:pixel_tablet",
        "id:automotive_1024p_landscape",
        "id:wearos_large_round",
        "id:wearos_small_round",
        "id:wearos_square",
        "id:wearos_rect",
        "spec:width=411dp,height=891dp",
        "spec:width=1280dp,height=800dp,dpi=240",
        "spec:width=1920dp,height=1080dp",
      )

    val TV_DEVICES = listOf("id:tv_720p", "id:tv_1080p", "id:tv_4k", "name:Television (1080p)")
  }
}
