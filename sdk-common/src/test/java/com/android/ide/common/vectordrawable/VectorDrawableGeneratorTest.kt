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
package com.android.ide.common.vectordrawable

import com.android.ide.common.util.GeneratorTester
import com.android.io.readImage
import com.android.testutils.TestResources
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.Locale
import junit.framework.TestCase

/** Tests for [Svg2Vector] and [VdPreview] classes. */
class VectorDrawableGeneratorTest : TestCase() {
  private enum class FileType {
    SVG,
    XML,
  }

  /** Checks conversion and returns contents of the error log. */
  private fun checkVectorConversion(
    testFileName: String,
    type: FileType,
    dumpXml: Boolean,
    expectedError: String?,
    imageSize: Int = IMAGE_SIZE,
  ): String? {
    val incomingFileName = if (type == FileType.SVG) "$testFileName.svg" else "$testFileName.xml"
    val imageName = "$testFileName.png"

    val parentDirName = "vectordrawable" + File.separator
    val parentDir = TestResources.getDirectory(javaClass, "/testData/vectordrawable").toPath()
    val incomingFile = parentDir.resolve(incomingFileName)
    val xmlContent: String
    var errorLog: String? = null
    if (type == FileType.SVG) {
      val outStream = ByteArrayOutputStream()
      errorLog = Svg2Vector.parseSvgToXml(incomingFile, outStream)
      if (expectedError != null) {
        assertNotNull(errorLog)
        assertFalse(errorLog.isEmpty())
        assertTrue(errorLog.contains(expectedError))
      }
      xmlContent = outStream.toString()
      checkNoTrailingSpaces(xmlContent)
      if (xmlContent.isEmpty()) {
        if (expectedError == null) {
          fail("Empty XML file.")
        }
        return errorLog
      }
      if (dumpXml) {
        val tempXmlFile = parentDir.resolve("$imageName.xml")
        PrintWriter(tempXmlFile.toFile(), UTF_8.name()).use { writer -> writer.println(xmlContent) }
      }
      if (INVALID_XML_PATTERN.containsMatchIn(xmlContent)) {
        fail("Invalid VectorDrawable produced")
      }
    } else {
      xmlContent = String(Files.readAllBytes(incomingFile), UTF_8)
    }

    val imageTargetSize = VdPreview.TargetSize.createFromMaxDimension(imageSize)
    val builder = StringBuilder()
    val image = VdPreview.getPreviewFromVectorXml(imageTargetSize, xmlContent, builder)

    val imageNameWithParent = parentDirName + imageName
    val pngFile = parentDir.resolve(imageName)
    if (Files.notExists(pngFile)) {
      var golden = imageNameWithParent
      val path = parentDir.toString()
      val pos = path.replace('\\', '/').indexOf("/tools/base/")
      if (pos > 0) {
        golden = path.substring(0, pos) + File.separator + GENERATOR_TESTER.testDataRelPath + File.separator + imageName
      }
      GENERATOR_TESTER.generateGoldenImage(image, golden, imageName)
      fail("Golden file $golden didn't exist, created by the test.")
    } else {
      val goldenImage = pngFile.readImage()
      GeneratorTester.assertImageSimilar(imageNameWithParent, goldenImage, image, DIFF_THRESHOLD_PERCENT)
    }

    return errorLog
  }

  private fun checkNoTrailingSpaces(xmlContent: String) {
    for (line in xmlContent.split(LINE_SEPARATORS)) {
      assertEquals("Unexpected trailing whitespace in: \"$line\"", 0, trailingWhiteSpaces(line))
    }
  }

  private fun trailingWhiteSpaces(line: String): Int = line.length - line.trimEnd().length

  /** Checks SVG conversion and returns contents of the error log. */
  private fun checkSvgConversion(filename: String): String? = checkVectorConversion(filename, FileType.SVG, false, null)

  private fun checkXmlConversion(filename: String) {
    checkVectorConversion(filename, FileType.XML, false, null)
  }

  private fun checkSvgConversionAndContainsError(filename: String, expectedError: String?) {
    checkVectorConversion(filename, FileType.SVG, false, expectedError)
  }

  /** Checks SVG conversion and returns contents of the error log. */
  @Suppress("unused") // Method intended for debugging.
  private fun checkSvgConversionDebug(filename: String): String? = checkVectorConversion(filename, FileType.SVG, true, null)

  //////////////////////////////////////////////////////////
  // Tests start here:
  fun testSvgFillAlpha() {
    checkSvgConversion("ic_add_to_notepad_black")
  }

  fun testSvgArcto1() {
    checkSvgConversion("test_arcto_1")
  }

  fun testSvgArcto2() {
    checkSvgConversion("test_arcto_2")
  }

  fun testSvgControlPoints01() {
    checkSvgConversion("test_control_points_01")
  }

  fun testSvgControlPoints02() {
    checkSvgConversion("test_control_points_02")
  }

  fun testSvgControlPoints03() {
    checkSvgConversion("test_control_points_03")
  }

  fun testSvgContentCut() {
    checkSvgConversion("ic_content_cut_24px")
  }

  fun testSvgInput() {
    checkSvgConversion("ic_input_24px")
  }

  fun testSvgLiveHelp() {
    checkSvgConversion("ic_live_help_24px")
  }

  fun testSvgLocalLibrary() {
    checkSvgConversion("ic_local_library_24px")
  }

  fun testSvgLocalPhone() {
    checkSvgConversion("ic_local_phone_24px")
  }

  fun testSvgMicOff() {
    checkSvgConversion("ic_mic_off_24px")
  }

  fun testSvgShapes() {
    checkSvgConversion("ic_shapes")
  }

  fun testSvgEllipse() {
    checkSvgConversion("test_ellipse")
  }

  fun testSvgTempHigh() {
    checkSvgConversion("ic_temp_high")
  }

  fun testSvgPlusSign() {
    checkSvgConversion("ic_plus_sign")
  }

  fun testSvgPolylineStrokeWidth() {
    checkSvgConversion("ic_polyline_strokewidth")
  }

  fun testSvgStrokeWidthUniformTransform() {
    checkSvgConversion("ic_strokewidth_uniform_transform")
  }

  fun testSvgStrokeWidthNonuniformTransform() {
    checkSvgConversionAndContainsError(
      "ic_strokewidth_nonuniform_transform",
      "Scaling of the stroke width is approximate",
    )
  }

  fun testSvgSemiTransparentMaskNotValid() {
    checkSvgConversionAndContainsError(
      "ic_semitransparent_mask",
      "Semitransparent mask cannot be represented by a vector drawable",
    )
  }

  fun testSvgEmptyAttributes() {
    checkSvgConversion("ic_empty_attributes")
  }

  fun testSvgEmptyPathData() {
    checkSvgConversion("ic_empty_path_data")
  }

  fun testSvgSimpleGroupInfo() {
    checkSvgConversion("ic_simple_group_info")
  }

  fun testSvgContainsError() {
    checkSvgConversionAndContainsError(
      "ic_contains_ignorable_error",
      "ERROR @ line 16: <switch> is not supported\n" + "ERROR @ line 17: <foreignObject> is not supported",
    )
  }

  fun testParseError() {
    checkSvgConversionAndContainsError(
      "test_parse_error",
      "ERROR: Element type \"path\" must be followed by either attribute specifications," + " \">\" or \"/>\".",
    )
  }

  fun testSvgLineToMoveTo() {
    checkSvgConversion("test_lineto_moveto")
  }

  fun testSvgLineToMoveTo2() {
    checkSvgConversion("test_lineto_moveto2")
  }

  fun testSvgLineToMoveToViewbox1() {
    checkSvgConversion("test_lineto_moveto_viewbox1")
  }

  fun testSvgLineToMoveToViewbox2() {
    checkSvgConversion("test_lineto_moveto_viewbox2")
  }

  fun testSvgLineToMoveToViewbox3() {
    checkSvgConversion("test_lineto_moveto_viewbox3")
  }

  // It seems like different implementations has different results on this svg.
  fun testSvgLineToMoveToViewbox4() {
    checkSvgConversion("test_lineto_moveto_viewbox4")
  }

  fun testSvgLineToMoveToViewbox5() {
    checkSvgConversion("test_lineto_moveto_viewbox5")
  }

  fun testSvgImplicitLineToAfterMoveTo() {
    checkSvgConversion("test_implicit_lineto_after_moveto")
  }

  fun testRoundRectPercentage() {
    checkSvgConversion("test_round_rect_percentage")
  }

  fun testSvgColorFormats() {
    checkSvgConversion("test_color_formats")
  }

  fun testSvgPaintOrder() {
    checkSvgConversion("test_paint_order")
  }

  fun testSvgTransformArcComplex1() {
    checkSvgConversion("test_transform_arc_complex1")
  }

  fun testSvgTransformArcComplex2() {
    checkSvgConversion("test_transform_arc_complex2")
  }

  fun testSvgTransformArcRotateScaleTranslate() {
    checkSvgConversion("test_transform_arc_rotate_scale_translate")
  }

  fun testSvgTransformArcScale() {
    checkSvgConversion("test_transform_arc_scale")
  }

  fun testSvgTransformArcScaleRotate() {
    checkSvgConversion("test_transform_arc_scale_rotate")
  }

  fun testSvgTransformArcSkewx() {
    checkSvgConversion("test_transform_arc_skewx")
  }

  fun testSvgTransformArcSkewy() {
    checkSvgConversion("test_transform_arc_skewy")
  }

  fun testSvgTransformBigArcComplex() {
    checkSvgConversion("test_transform_big_arc_complex")
  }

  fun testSvgTransformBigArcComplexViewbox() {
    checkSvgConversion("test_transform_big_arc_complex_viewbox")
  }

  fun testSvgTransformBigArcScale() {
    checkSvgConversion("test_transform_big_arc_translate_scale")
  }

  fun testSvgTransformDegenerateArc() {
    checkSvgConversion("test_transform_degenerate_arc")
  }

  fun testSvgArcWithoutSeparatorBetweenFlags() {
    checkSvgConversion("test_arc_without_separator_between_flags")
  }

  fun testSvgTransformCircleRotate() {
    checkSvgConversion("test_transform_circle_rotate")
  }

  fun testSvgTransformCircleScale() {
    checkSvgConversion("test_transform_circle_scale")
  }

  fun testSvgTransformCircleMatrix() {
    checkSvgConversion("test_transform_circle_matrix")
  }

  fun testSvgTransformRectMatrix() {
    checkSvgConversion("test_transform_rect_matrix")
  }

  fun testSvgTransformRoundRectMatrix() {
    checkSvgConversion("test_transform_round_rect_matrix")
  }

  fun testSvgTransformRectRotate() {
    checkSvgConversion("test_transform_rect_rotate")
  }

  fun testSvgTransformRectScale() {
    checkSvgConversion("test_transform_rect_scale")
  }

  fun testSvgTransformRectSkewx() {
    checkSvgConversion("test_transform_rect_skewx")
  }

  fun testSvgTransformRectSkewy() {
    checkSvgConversion("test_transform_rect_skewy")
  }

  fun testSvgTransformRectTranslate() {
    checkSvgConversion("test_transform_rect_translate")
  }

  fun testSvgTransformHVLoopBasic() {
    checkSvgConversion("test_transform_h_v_loop_basic")
  }

  fun testSvgTransformHVLoopTranslate() {
    checkSvgConversion("test_transform_h_v_loop_translate")
  }

  fun testSvgTransformHVLoopMatrix() {
    checkSvgConversion("test_transform_h_v_loop_matrix")
  }

  fun testSvgTransformHVACComplex() {
    checkSvgConversion("test_transform_h_v_a_c_complex")
  }

  fun testSvgTransformHVAComplex() {
    checkSvgConversion("test_transform_h_v_a_complex")
  }

  fun testSvgTransformHVCQ() {
    checkSvgConversion("test_transform_h_v_c_q")
  }

  fun testSvgTransformHVCQComplex() {
    checkSvgConversion("test_transform_h_v_c_q_complex")
  }

  // Preview broken on linux, fine on chrome browser
  fun testSvgTransformHVLoopComplex() {
    checkSvgConversion("test_transform_h_v_loop_complex")
  }

  fun testSvgTransformHVSTComplex() {
    checkSvgConversion("test_transform_h_v_s_t_complex")
  }

  fun testSvgTransformHVSTComplex2() {
    checkSvgConversion("test_transform_h_v_s_t_complex2")
  }

  fun testSvgTransformCQNoMove() {
    checkSvgConversion("test_transform_c_q_no_move")
  }

  // Preview broken on linux, fine on chrome browser
  fun testSvgTransformMultiple1() {
    checkSvgConversion("test_transform_multiple_1")
  }

  // Preview broken on linux, fine on chrome browser
  fun testSvgTransformMultiple2() {
    checkSvgConversion("test_transform_multiple_2")
  }

  // Preview broken on linux, fine on chrome browser
  fun testSvgTransformMultiple3() {
    checkSvgConversion("test_transform_multiple_3")
  }

  fun testSvgTransformMultiple4() {
    checkSvgConversion("test_transform_multiple_4")
  }

  fun testSvgTransformGroup1() {
    checkSvgConversion("test_transform_group_1")
  }

  fun testSvgTransformGroup2() {
    checkSvgConversion("test_transform_group_2")
  }

  fun testSvgTransformGroup3() {
    checkSvgConversion("test_transform_group_3")
  }

  fun testSvgTransformGroup4() {
    checkSvgConversion("test_transform_group_4")
  }

  fun testSvgTransformEllipseRotateScaleTranslate() {
    checkSvgConversion("test_transform_ellipse_rotate_scale_translate")
  }

  fun testSvgTransformEllipseComplex() {
    checkSvgConversion("test_transform_ellipse_complex")
  }

  fun testSvgMoveAfterClose1() {
    checkSvgConversion("test_move_after_close1")
  }

  fun testSvgMoveAfterClose2() {
    checkSvgConversion("test_move_after_close2")
  }

  fun testSvgMoveAfterClose3() {
    checkSvgConversion("test_move_after_close3")
  }

  fun testSvgMoveAfterCloseTransform() {
    checkSvgConversion("test_move_after_close_transform")
  }

  fun testSvgFillRuleEvenOdd() {
    checkSvgConversion("test_fill_type_evenodd")
  }

  fun testSvgFillRuleNonzero() {
    checkSvgConversion("test_fill_type_nonzero")
  }

  fun testSvgFillRuleNoRule() {
    checkSvgConversion("test_fill_type_no_rule")
  }

  fun testSvgBlackFill() {
    checkSvgConversion("test_black_fill")
  }

  fun testSvgDefsUseTransform() {
    checkSvgConversion("test_defs_use_shape2")
  }

  fun testSvgDefsUseColors() {
    checkSvgConversion("test_defs_use_colors")
  }

  fun testSvgDefsUseNoGroup() {
    checkSvgConversion("test_defs_use_no_group")
  }

  fun testSvgDefsUseNestedGroups() {
    checkSvgConversion("test_defs_use_nested_groups")
  }

  fun testSvgDefsUseNestedGroups2() {
    checkSvgConversion("test_defs_use_nested_groups2")
  }

  fun testSvgUseWithoutDefs() {
    checkSvgConversion("test_use_no_defs")
  }

  fun testSvgDefsUseMultiAttrib() {
    checkSvgConversion("test_defs_use_multi_attr")
  }

  fun testSvgDefsUseTransformRotate() {
    checkSvgConversion("test_defs_use_transform")
  }

  fun testSvgDefsUseTransformInDefs() {
    checkSvgConversion("test_defs_use_transform2")
  }

  fun testSvgDefsUseOrderMatters() {
    checkSvgConversion("test_defs_use_use_first")
  }

  fun testSvgDefsUseIndirect() {
    checkSvgConversion("test_defs_use_chain")
  }

  fun testSvgDefsUseCircularDependency() {
    checkSvgConversionAndContainsError(
      "test_defs_use_circular_dependency",
      "ERROR @ line 6: Circular dependency of <use> nodes: hhh -> hhh\n" +
        "ERROR @ line 9: Circular dependency of <use> nodes: ccc -> ddd (line 11) ->" +
        " eee (line 10) -> ccc\n" +
        "ERROR @ line 12: Circular dependency of <use> nodes: ggg -> fff (line 8) ->" +
        " ggg",
    )
  }

  fun testSvgUnsupportedElement() {
    val errors = checkSvgConversion("test_unsupported_element")
    assertThat(errors).isEqualTo("ERROR @ line 4: <foreignObject> is not supported")
  }

  // Text tests. To make the produced images independent of the fonts installed on the computer
  // running the tests, the test files use fonts that are bundled with JetBrains Runtime.
  fun testSvgText() {
    assertThat(checkSvgConversion("test_text")).isEmpty()
  }

  fun testSvgTextEmpty() {
    checkSvgConversionAndContainsError("test_text_empty", "ERROR: No vector content found")
  }

  fun testSvgTextFontSize() {
    assertThat(checkSvgConversion("test_text_font_size")).isEmpty()
  }

  fun testSvgTextFontShorthand() {
    val errors = checkSvgConversion("test_text_font_shorthand")
    assertThat(errors)
      .isEqualTo(
        "WARNING @ line 3: \"font-stretch: condensed\" is not supported\n" +
          "WARNING @ line 4: \"font-stretch: semi-expanded\" is not" +
          " supported"
      )
  }

  fun testSvgTextCssSpecificity() {
    assertThat(checkSvgConversion("test_text_css_specificity")).isEmpty()
  }

  fun testSvgTextGroupStyle() {
    assertThat(checkSvgConversion("test_text_group_style")).isEmpty()
  }

  fun testSvgTextOpacity() {
    assertThat(checkSvgConversion("test_text_opacity")).isEmpty()
  }

  fun testSvgTextAnchor() {
    assertThat(checkSvgConversion("test_text_anchor")).isEmpty()
  }

  fun testSvgTextTspan() {
    assertThat(checkSvgConversion("test_text_tspan")).isEmpty()
  }

  fun testSvgTextTransform() {
    assertThat(checkSvgConversion("test_text_transform")).isEmpty()
  }

  fun testSvgTextUse() {
    assertThat(checkSvgConversion("test_text_use")).isEmpty()
  }

  fun testSvgTextHidden() {
    assertThat(checkSvgConversion("test_text_hidden")).isEmpty()
  }

  fun testSvgTextPosition() {
    assertThat(checkSvgConversion("test_text_position")).isEmpty()
  }

  fun testSvgTextLineBreak() {
    assertThat(checkSvgConversion("test_text_line_break")).isEmpty()
  }

  fun testSvgTextHiddenPosition() {
    assertThat(checkSvgConversion("test_text_hidden_position")).isEmpty()
  }

  fun testSvgTextGoogle() {
    assertThat(checkSvgConversion("test_text_google")).isEmpty()
  }

  fun testSvgTextFontFallback() {
    assertThat(checkSvgConversion("test_text_font_fallback")).isEmpty()
  }

  fun testSvgTextMissingCharacter() {
    val errors = checkSvgConversion("test_text_missing_character")
    assertThat(errors).isEqualTo("WARNING @ line 3: No font is available for the following characters:" + " U+0378")
  }

  fun testSvgTextInvalidNumbers() {
    val errors = checkSvgConversion("test_text_invalid_numbers")
    assertThat(errors)
      .isEqualTo(
        "ERROR @ line 5: Invalid font size \"NaN\"\n" +
          "ERROR @ line 6: Invalid font size \"Infinity\"\n" +
          "ERROR @ line 9: Invalid font size \"1e400\"\n" +
          "ERROR @ line 10: Invalid font size \"0x10p0\"\n" +
          "ERROR @ line 14: Invalid attribute value: x=\"Infinity\"\n" +
          "ERROR @ line 15: Invalid attribute value: dx=\"NaN\"\n" +
          "ERROR @ line 19: Invalid attribute value: dy=\"-Infinity\"\n" +
          "ERROR @ line 20: Invalid attribute value: dy=\"1e400\"\n" +
          "ERROR @ line 25: Text position or size is out of range\n" +
          "ERROR @ line 26: Text position or size is out of range"
      )
  }

  fun testSvgTextUnsupportedProperties() {
    val errors = checkSvgConversion("test_text_unsupported_properties")
    assertThat(errors)
      .isEqualTo(
        "WARNING @ line 3: \"text-decoration: underline\" is not supported\n" +
          "WARNING @ line 4: \"word-spacing: 4px\" is not supported\n" +
          "WARNING @ line 5: \"textLength\" attribute is not supported\n" +
          "WARNING @ line 6: \"writing-mode: tb\" is not supported\n" +
          "WARNING @ line 7: \"direction: rtl\" is not supported\n" +
          "WARNING @ line 8: \"baseline-shift: super\" is not supported\n" +
          "WARNING @ line 9: \"font-variant: small-caps\" is not supported\n" +
          "WARNING @ line 10: \"font-stretch: condensed\" is not supported\n" +
          "WARNING @ line 16: \"direction: rtl\" is not supported"
      )
  }

  fun testSvgTextUnavailableFont() {
    val errors = checkSvgConversion("test_text_unavailable_font")
    assertThat(errors)
      .isEqualTo("WARNING @ line 4: Font \"'No Such Font 1', NoSuchFont2\" is not" + " available, a sans-serif font is used instead")
  }

  fun testSvgTextPathUnsupported() {
    val errors = checkSvgConversion("test_text_path_unsupported")
    assertThat(errors).isEqualTo("ERROR @ line 4: <textPath> is not supported")
  }

  fun testSvgImageOnly() {
    checkSvgConversionAndContainsError(
      "test_image_only",
      "ERROR @ line 11: <image> is not supported",
    )
  }

  fun testSvgEmptyAttribute() {
    checkSvgConversion("test_empty_attribute")
  }

  // Clip Path Tests
  fun testSvgClipPathGroup() {
    checkSvgConversion("test_clip_path_group")
  }

  fun testSvgClipPathGroup2() {
    checkSvgConversion("test_clip_path_group_2")
  }

  fun testSvgClipPathTranslateChildren() {
    checkSvgConversion("test_clip_path_translate_children")
  }

  fun testSvgClipPathTranslateAffected() {
    checkSvgConversion("test_clip_path_translate_affected")
  }

  fun testSvgClipPathIsGroup() {
    checkSvgConversion("test_clip_path_is_group")
  }

  fun testSvgClipPathMultiShapeClip() {
    checkSvgConversion("test_clip_path_mult_clip")
  }

  fun testSvgClipPathOverGroup() {
    checkSvgConversion("test_clip_path_over_group")
  }

  fun testSvgClipPathRect() {
    checkSvgConversion("test_clip_path_rect")
  }

  fun testSvgClipPathRectOverClipPath() {
    checkSvgConversion("test_clip_path_rect_over_circle")
  }

  fun testSvgClipPathTwoRect() {
    checkSvgConversion("test_clip_path_two_rect")
  }

  fun testSvgClipPathSinglePath() {
    checkSvgConversion("test_clip_path_path_over_rect")
  }

  fun testSvgClipPathOrdering() {
    checkSvgConversion("test_clip_path_ordering")
  }

  fun testSvgClipEvenOdd() {
    checkSvgConversion("test_clip_path_evenodd")
  }

  fun testSvgClipEvenOddAndNonZero() {
    checkSvgConversion("test_clip_path_evenodd_and_nonzero")
  }

  fun testSvgClipRuleOutsideOfClipPath() {
    checkSvgConversion("test_clip_rule_outside_of_clippath")
  }

  fun testSvgMask() {
    checkSvgConversion("test_mask")
  }

  fun testSvgMaskUnsupported() {
    checkVectorConversion(
      "test_mask_unsupported",
      FileType.SVG,
      false,
      "Semitransparent mask cannot be represented by a vector drawable",
    )
  }

  // Style tests start here
  fun testSvgStyleBasicShapes() {
    checkSvgConversion("test_style_basic_shapes")
  }

  fun testSvgStyleBlobfish() {
    checkSvgConversion("test_style_blobfish")
  }

  fun testSvgStyleCircle() {
    checkSvgConversion("test_style_circle")
  }

  fun testSvgStyleGroup() {
    checkSvgConversion("test_style_group")
  }

  fun testSvgStyleGroupClipPath() {
    checkSvgConversion("test_style_group_clip_path")
  }

  fun testSvgStyleGroupDuplicateAttr() {
    checkSvgConversion("test_style_group_duplicate_attr")
  }

  fun testSvgStyleMultiClass() {
    checkSvgConversion("test_style_multi_class")
  }

  fun testSvgStyleTwoShapes() {
    checkSvgConversion("test_style_two_shapes")
  }

  fun testSvgStylePathClassNames() {
    checkSvgConversion("test_style_path_class_names")
  }

  fun testSvgStyleShortVersion() {
    checkSvgConversion("test_style_short_version")
  }

  fun testSvgStyleCombined() {
    checkSvgConversion("test_style_combined")
  }

  // Gradient tests start here
  // The following gradient test files currently fail and do not have corresponding test cases:
  // test_gradient_linear_transform_matrix
  // test_gradient_linear_transform_matrix_2
  // test_gradient_linear_transform_scale_rotate
  // test_gradient_linear_transform_scale_translate_rotate
  fun testSvgGradientLinearCoordinatesNegativePercentage() {
    checkSvgConversion("test_gradient_linear_coordinates_negative_percentage")
  }

  fun testSvgGradientLinearNoCoordinates() {
    checkSvgConversion("test_gradient_linear_no_coordinates")
  }

  fun testSvgGradientLinearNoUnits() {
    checkSvgConversion("test_gradient_linear_no_units")
  }

  fun testSvgGradientLinearObjectBoundingBox() {
    checkSvgConversion("test_gradient_linear_object_bounding_box")
  }

  fun testSvgGradientLinearOffsetDecreasing() {
    checkSvgConversion("test_gradient_linear_offset_decreasing")
  }

  fun testSvgGradientLinearOffsetOutOfBounds() {
    checkSvgConversion("test_gradient_linear_offset_out_of_bounds")
  }

  fun testSvgGradientLinearOffsetUndefined() {
    checkSvgConversion("test_gradient_linear_offset_undefined")
  }

  fun testSvgGradientLinearOneStop() {
    checkSvgConversion("test_gradient_linear_one_stop")
  }

  fun testSvgGradientLinearOverlappingStops() {
    checkSvgConversion("test_gradient_linear_overlapping_stops")
  }

  fun testSvgGradientLinearSpreadPad() {
    checkSvgConversion("test_gradient_linear_spread_pad")
  }

  fun testSvgGradientLinearSpreadReflect() {
    checkSvgConversion("test_gradient_linear_spread_reflect")
  }

  fun testSvgGradientLinearSpreadRepeat() {
    checkSvgConversion("test_gradient_linear_spread_repeat")
  }

  fun testSvgGradientLinearStopOpacity() {
    checkSvgConversion("test_gradient_linear_stop_opacity")
  }

  fun testSvgGradientLinearStopOpacityHalf() {
    checkSvgConversion("test_gradient_linear_stop_opacity_half")
  }

  fun testSvgGradientLinearStroke() {
    checkSvgConversion("test_gradient_linear_stroke")
  }

  fun testSvgGradientLinearThreeStops() {
    checkSvgConversion("test_gradient_linear_three_stops")
  }

  fun testSvgGradientLinearTransformGroupScaleTranslate() {
    checkSvgConversion("test_gradient_linear_transform_group_scale_translate")
  }

  fun testSvgGradientLinearTransformMatrix3() {
    checkSvgConversion("test_gradient_linear_transform_matrix_3")
  }

  fun testSvgGradientLinearTransformMatrixScale() {
    checkSvgConversion("test_gradient_linear_transform_matrix_scale")
  }

  fun testSvgGradientLinearTransformRotate() {
    checkSvgConversion("test_gradient_linear_transform_rotate")
  }

  fun testSvgGradientLinearTransformRotateScale() {
    checkSvgConversion("test_gradient_linear_transform_rotate_scale")
  }

  fun testSvgGradientLinearTransformRotateTranslateScale() {
    checkSvgConversion("test_gradient_linear_transform_rotate_translate_scale")
  }

  fun testSvgGradientLinearTransformScale() {
    checkSvgConversion("test_gradient_linear_transform_scale")
  }

  fun testSvgGradientLinearTransformTranslate() {
    checkSvgConversion("test_gradient_linear_transform_translate")
  }

  fun testSvgGradientLinearTransformTranslateRotate() {
    checkSvgConversion("test_gradient_linear_transform_translate_rotate")
  }

  fun testSvgGradientLinearTransformTranslateRotateScale() {
    checkSvgConversion("test_gradient_linear_transform_translate_rotate_scale")
  }

  fun testSvgGradientLinearTransformTranslateScale() {
    checkSvgConversion("test_gradient_linear_transform_translate_scale")
  }

  fun testSvgGradientLinearTransformTranslateScaleShapeTransform() {
    checkSvgConversion("test_gradient_linear_transform_translate_scale_shape_transform")
  }

  fun testSvgGradientLinearUserSpaceOnUse() {
    checkSvgConversion("test_gradient_linear_user_space_on_use")
  }

  fun testSvgGradientLinearXYNumbers() {
    checkSvgConversion("test_gradient_linear_x_y_numbers")
  }

  fun testSvgGradientLinearHref() {
    checkSvgConversion("test_gradient_linear_href")
  }

  fun testSvgGradientLinearUnsupportedColor() {
    val testFileName = "test_gradient_linear_unsupported_color"
    val incomingFileName = "$testFileName.svg"
    val parentDir = TestResources.getDirectory(javaClass, "/testData/vectordrawable").toPath()
    val incomingFile = parentDir.resolve(incomingFileName)

    val outStream = ByteArrayOutputStream()
    val errorLog = Svg2Vector.parseSvgToXml(incomingFile, outStream)
    val xmlContent = outStream.toString()

    assertFalse("VectorDrawable XML should not be empty", xmlContent.isEmpty())
    assertTrue("Expected warning about unsupported color", errorLog.contains("Unsupported color value ?android:color/white"))
  }

  fun testSvgGradientTransform() {
    checkSvgConversion("test_gradient_transform")
  }

  fun testGradientObjectTransformation() {
    checkSvgConversion("test_gradient_object_transformation")
  }

  fun testSvgGradientComplex() {
    checkSvgConversion("test_gradient_complex")
  }

  fun testSvgGradientComplex2() {
    checkSvgConversion("test_gradient_complex_2")
  }

  fun testSvgGradientRadialCoordinates() {
    checkSvgConversion("test_gradient_radial_coordinates")
  }

  fun testSvgGradientRadialNoCoordinates() {
    checkSvgConversion("test_gradient_radial_no_coordinates")
  }

  fun testSvgGradientRadialNoStops() {
    checkVectorConversion(
      "test_gradient_radial_no_stops",
      FileType.SVG,
      false,
      "has no stop info",
    )
  }

  fun testSvgGradientRadialNoUnits() {
    checkSvgConversion("test_gradient_radial_no_units")
  }

  fun testSvgGradientRadialObjectBoundingBox() {
    checkSvgConversion("test_gradient_radial_object_bounding_box")
  }

  fun testSvgGradientRadialOneStop() {
    checkSvgConversion("test_gradient_radial_one_stop")
  }

  fun testSvgGradientRadialOverlappingStops() {
    checkSvgConversion("test_gradient_radial_overlapping_stops")
  }

  fun testSvgGradientRadialRNegative() {
    checkSvgConversion("test_gradient_radial_r_negative")
  }

  fun testSvgGradientRadialRZero() {
    checkSvgConversion("test_gradient_radial_r_zero")
  }

  fun testSvgGradientRadialSpreadPad() {
    checkSvgConversion("test_gradient_radial_spread_pad")
  }

  fun testSvgGradientRadialSpreadReflect() {
    checkSvgConversion("test_gradient_radial_spread_reflect")
  }

  fun testSvgGradientRadialSpreadRepeat() {
    checkSvgConversion("test_gradient_radial_spread_repeat")
  }

  fun testSvgGradientRadialStopOpacity() {
    checkSvgConversion("test_gradient_radial_stop_opacity")
  }

  fun testSvgGradientRadialStopOpacityFraction() {
    checkSvgConversion("test_gradient_radial_stop_opacity_fraction")
  }

  fun testSvgGradientRadialStroke() {
    checkSvgConversion("test_gradient_radial_stroke")
  }

  fun testSvgGradientRadialThreeStops() {
    checkSvgConversion("test_gradient_radial_three_stops")
  }

  fun testSvgGradientRadialUserSpaceOnUse() {
    checkSvgConversion("test_gradient_radial_user_space_on_use")
  }

  fun testSvgGradientRadialUserSpace2() {
    checkSvgConversion("test_gradient_radial_user_space_2")
  }

  fun testSvgGradientRadialTransformTranslate() {
    checkSvgConversion("test_gradient_radial_transform_translate")
  }

  fun testSvgGradientRadialTransformTranslateUserSpace() {
    checkSvgConversion("test_gradient_radial_transform_translate_userspace")
  }

  fun testSvgGradientRadialTransformTranslateScale() {
    checkSvgConversion("test_gradient_radial_transform_translate_scale")
  }

  fun testSvgGradientRadialTransformScaleTranslate() {
    checkSvgConversion("test_gradient_radial_transform_scale_translate")
  }

  fun testSvgGradientRadialTransformMatrix() {
    checkSvgConversion("test_gradient_radial_transform_matrix")
  }

  fun testSvgGradientRadialTransformRotate() {
    checkSvgConversion("test_gradient_radial_transform_rotate")
  }

  fun testSvgGradientRadialTransformRotateScale() {
    checkSvgConversion("test_gradient_radial_transform_rotate_scale")
  }

  fun testSvgGradientRadialTransformRotateScaleTranslate() {
    checkSvgConversion("test_gradient_radial_transform_rotate_scale_translate")
  }

  fun testSvgGradientRadialTransformRotateTranslate() {
    checkSvgConversion("test_gradient_radial_transform_rotate_translate")
  }

  fun testSvgGradientRadialTransformRotateTranslate2() {
    checkSvgConversion("test_gradient_radial_transform_rotate_translate2")
  }

  fun testSvgGradientRadialTransformRotateTranslateScale() {
    checkSvgConversion("test_gradient_radial_transform_rotate_translate_scale")
  }

  fun testSvgGradientRadialTransformScale() {
    checkSvgConversion("test_gradient_radial_transform_scale")
  }

  fun testSvgGradientRadialTransformScaleRotate() {
    checkSvgConversion("test_gradient_radial_transform_scale_rotate")
  }

  fun testSvgGradientRadialTransformScaleRotateTranslate() {
    checkSvgConversion("test_gradient_radial_transform_scale_rotate_translate")
  }

  fun testSvgGradientRadialTransformScaleTranslateRotate() {
    checkSvgConversion("test_gradient_radial_transform_scale_translate_rotate")
  }

  fun testSvgGradientRadialTransformTranslateRotate() {
    checkSvgConversion("test_gradient_radial_transform_translate_rotate")
  }

  fun testSvgGradientRadialTransformTranslateRotateScale() {
    checkSvgConversion("test_gradient_radial_transform_translate_rotate_scale")
  }

  fun testSvgGradientRadialTransformTranslateScaleRotate() {
    checkSvgConversion("test_gradient_radial_transform_translate_scale_rotate")
  }

  fun testSvgGradientRadialTransformTranslateGroupScaleTranslate() {
    checkSvgConversion("test_gradient_radial_transform_translate_group_scale_translate")
  }

  fun testSvgGradientRadialUnitsAsNumbers() {
    checkSvgConversion("test_gradient_radial_units_as_numbers")
  }

  fun testSvgGradientRadialCoordinatesNegativePercentage() {
    checkSvgConversion("test_gradient_radial_coordinates_negative_percentage")
  }

  fun testLocaleWithDecimalComma() {
    val defaultLocale = Locale.getDefault()
    Locale.setDefault(Locale.FRANCE)
    try {
      checkSvgConversion("test_locale_with_decimal_comma")
    } finally {
      Locale.setDefault(defaultLocale)
    }
  }

  fun testLocaleWithDashAsMinus() {
    val defaultLocale = Locale.getDefault()
    Locale.setDefault(Locale.forLanguageTag("se-SV"))
    try {
      checkSvgConversion("test_locale_with_dash_as_minus")
    } finally {
      Locale.setDefault(defaultLocale)
    }
  }

  fun testClipPathOrder() {
    checkSvgConversion("ic_clip_path_ordering")
  }

  fun testSvgNestedUse() {
    checkSvgConversion("test_nested_use")
  }

  // XML files start here.
  fun testXmlIconSizeOpacity() {
    checkXmlConversion("ic_size_opacity")
  }

  fun testXmlTintAndOpacity() {
    checkXmlConversion("test_tint_and_opacity")
  }

  fun testXmlColorFormats() {
    checkXmlConversion("test_xml_color_formats")
  }

  fun testXmlColorAlpha() {
    checkXmlConversion("test_fill_stroke_alpha")
  }

  fun testXmlTransformation1() {
    checkXmlConversion("test_xml_transformation_1")
  }

  fun testXmlTransformation2() {
    checkXmlConversion("test_xml_transformation_2")
  }

  fun testXmlTransformation3() {
    checkXmlConversion("test_xml_transformation_3")
  }

  fun testXmlTransformation4() {
    checkXmlConversion("test_xml_transformation_4")
  }

  fun testXmlTransformation5() {
    checkXmlConversion("test_xml_transformation_5")
  }

  fun testXmlTransformation6() {
    checkXmlConversion("test_xml_transformation_6")
  }

  fun testXmlScaleStroke1() {
    checkXmlConversion("test_xml_scale_stroke_1")
  }

  fun testXmlScaleStroke2() {
    checkXmlConversion("test_xml_scale_stroke_2")
  }

  fun testXmlRenderOrder1() {
    checkXmlConversion("test_xml_render_order_1")
  }

  fun testXmlRenderOrder2() {
    checkXmlConversion("test_xml_render_order_2")
  }

  fun testXmlRepeatedA1() {
    checkXmlConversion("test_xml_repeated_a_1")
  }

  fun testXmlRepeatedA2() {
    checkXmlConversion("test_xml_repeated_a_2")
  }

  fun testXmlRepeatedCQ() {
    checkXmlConversion("test_xml_repeated_cq")
  }

  fun testXmlRepeatedST() {
    checkXmlConversion("test_xml_repeated_st")
  }

  fun testXmlStroke1() {
    checkXmlConversion("test_xml_stroke_1")
  }

  fun testXmlStroke2() {
    checkXmlConversion("test_xml_stroke_2")
  }

  fun testXmlStroke3() {
    checkXmlConversion("test_xml_stroke_3")
  }

  fun testPathDataInStringResource() {
    try {
      checkXmlConversion("test_pathData_in_string_resource")
      fail("Expecting exception.")
    } catch (e: ResourcesNotSupportedException) {
      assertEquals("@string/pathDataAsString", e.value)
      assertEquals("android:pathData", e.name)
    }
  }

  /**
   * We aren't really interested in the content of the image produced in this test, we're just testing that resource references aren't
   * touched when they're in the tools: attribute namespace.
   */
  fun testPathDataInStringToolsResource() {
    checkXmlConversion("test_pathData_in_string_tools_resource")
  }

  fun testSmallVectorWithTint() {
    checkVectorConversion("test_small_image_with_tint", FileType.XML, false, null, 16)
  }

  fun testLegacyArcFlags() {
    checkVectorConversion("test_legacy_arc_flags", FileType.XML, false, null, 16)
  }

  companion object {
    private const val IMAGE_SIZE = 64

    /** Due to rendering differences between AWT implementations on different operating systems. */
    private const val DIFF_THRESHOLD_PERCENT = 1.25f

    private val INVALID_XML_PATTERN = Regex("pathData=\"\\s*\"")
    private val LINE_SEPARATORS = Regex("(\r|\n|\r\n)+")

    private val GENERATOR_TESTER: GeneratorTester =
      GeneratorTester.withTestDataRelativePath("tools/base/sdk-common/src/test/resources/testData/vectordrawable")
  }
}
