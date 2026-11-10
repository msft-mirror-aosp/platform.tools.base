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
  }

  @Test
  fun testMismatchedDimensionUnitsProduceError() {
    assertSingleError("spec:width=411dp,height=891px", "'width', 'height' must use the same unit", "width=411dp, height=891px")
    assertSingleError(
      "spec:width=411dp,height=891dp,chinSize=30px",
      "'width', 'height', 'chinSize' must use the same unit",
      "width=411dp, height=891dp, chinSize=30px",
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
    assertSingleError("spec:parent=pixel_5,foo=bar", "Unknown device spec parameter 'foo'", "Supported parameters: 'parent', 'orientation'")
  }

  @Test
  fun testCustomParametersCombinedWithParentProduceErrors() {
    assertSingleError("spec:parent=pixel_5,width=411dp", "'width' cannot be combined with 'parent'", "Parameters supported with 'parent'")
    assertSingleError("spec:parent=pixel_5,dpi=420", "'dpi' cannot be combined with 'parent'")
    assertSingleError("spec:parent=pixel_5,isRound=true", "'isRound' cannot be combined with 'parent'")
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
  }

  @Test
  fun testRepeatedParametersProduceError() {
    assertSingleError("spec:width=411dp,height=891dp,width=500dp", "'width' is specified more than once")
    assertSingleError("spec:parent=pixel_5,parent=pixel_4", "'parent' is specified more than once")
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
}
