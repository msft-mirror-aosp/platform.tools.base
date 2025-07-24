package com.android.aaptcompiler

import com.android.aaptcompiler.android.ResTableConfig
import com.google.common.truth.Truth
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.test.assertFailsWith

class ConfigDescriptionTest {

  private fun testParse(input: String): ConfigDescription? {
    return try {
      parse(input)
    } catch (e: Exception) {
      null
    }
  }

  @Test
  fun failWhenQualifiersAreOutOfOrder() {
    Truth.assertThat(testParse("en-sw600dp-ldrtl")).isNull()
    Truth.assertThat(testParse("land-en")).isNull()
    Truth.assertThat(testParse("hdpi-320dpi")).isNull()
  }

  @Test
  fun failWhenQualifiersAreNotMatched() {
    Truth.assertThat(testParse("en-sw600dp-ILLEGAL")).isNull()
  }

  @Test
  fun failWhenQualifiersHaveTrailingDelimiter() {
    Truth.assertThat(testParse("en-sw600dp-land-")).isNull()
  }

  @Test
  fun testParseBasic() {
    val default = testParse("")
    Truth.assertThat(default).isNotNull()
    Truth.assertThat(default.toString()).isEqualTo("DEFAULT")

    val frenchLandscape = testParse("fr-land")
    Truth.assertThat(frenchLandscape).isNotNull()
    Truth.assertThat(frenchLandscape.toString()).isEqualTo("fr-land")

    val longConfig =
      testParse("mcc310-pl-sw720dp-normal-long-port-night-xhdpi-keyssoft-qwerty-navexposed-nonav")
    Truth.assertThat(longConfig).isNotNull()
    Truth.assertThat(longConfig.toString())
      .isEqualTo(
        "mcc310-pl-sw720dp-normal-long-port-night-xhdpi-keyssoft-qwerty-navexposed-nonav-v13")
  }

  @Test
  fun testLocales() {
    val localeConfig = testParse("en-rUS")
    Truth.assertThat(localeConfig).isNotNull()
    Truth.assertThat(localeConfig.toString()).isEqualTo("en-rUS")
  }

  @Test
  fun testQualifierAddedInApi13() {
    val widthConfig = testParse("sw600dp")
    Truth.assertThat(widthConfig).isNotNull()
    Truth.assertThat(widthConfig.toString()).isEqualTo("sw600dp-v13")

    val widthConfigWithLowerVersion = testParse("sw600dp-v8")
    Truth.assertThat(widthConfigWithLowerVersion).isNotNull()
    Truth.assertThat(widthConfigWithLowerVersion.toString()).isEqualTo("sw600dp-v13")
  }

  @Test
  fun testParseCarAttribute() {
    val config = testParse("car")
    Truth.assertThat(config).isNotNull()
    Truth.assertThat(config!!.uiMode).isEqualTo(ResTableConfig.UI_MODE.TYPE_CAR)
  }

  @Test
  fun testParseVersionOnly() {
    val config = testParse("v26")
    Truth.assertThat(config).isNotNull()
    Truth.assertThat(config!!.sdkVersion).isEqualTo(26)
  }

  @Test
  fun testParsingRoundQualifier() {
    val roundConfig = testParse("round")
    Truth.assertThat(roundConfig).isNotNull()
    Truth.assertThat(roundConfig!!.layoutRound())
      .isEqualTo(ResTableConfig.SCREEN_LAYOUT2.SCREENROUND_YES)
    Truth.assertThat(roundConfig.sdkVersion).isEqualTo(23)
    Truth.assertThat(roundConfig.toString()).isEqualTo("round-v23")

    val notRoundConfig = testParse("notround")
    Truth.assertThat(notRoundConfig).isNotNull()
    Truth.assertThat(notRoundConfig!!.layoutRound())
      .isEqualTo(ResTableConfig.SCREEN_LAYOUT2.SCREENROUND_NO)
    Truth.assertThat(notRoundConfig.sdkVersion).isEqualTo(23)
    Truth.assertThat(notRoundConfig.toString()).isEqualTo("notround-v23")
  }

  @Test
  fun testWideColorGamutQualifier() {
    val wideConfig = testParse("widecg")
    Truth.assertThat(wideConfig).isNotNull()
    Truth.assertThat(wideConfig!!.wideColorGamut())
      .isEqualTo(ResTableConfig.COLOR_MODE.WIDE_GAMUT_YES)
    Truth.assertThat(wideConfig.sdkVersion).isEqualTo(26)
    Truth.assertThat(wideConfig.toString()).isEqualTo("widecg-v26")

    val noWideConfig = testParse("nowidecg")
    Truth.assertThat(noWideConfig).isNotNull()
    Truth.assertThat(noWideConfig!!.wideColorGamut())
      .isEqualTo(ResTableConfig.COLOR_MODE.WIDE_GAMUT_NO)
    Truth.assertThat(noWideConfig.sdkVersion).isEqualTo(26)
    Truth.assertThat(noWideConfig.toString()).isEqualTo("nowidecg-v26")
  }

  @Test
  fun testHdrQualifier() {
    val highDRConfig = testParse("highdr")
    Truth.assertThat(highDRConfig).isNotNull()
    Truth.assertThat(highDRConfig!!.hdr()).isEqualTo(ResTableConfig.COLOR_MODE.HDR_YES)
    Truth.assertThat(highDRConfig.sdkVersion).isEqualTo(26)
    Truth.assertThat(highDRConfig.toString()).isEqualTo("highdr-v26")

    val lowDRConfig = testParse("lowdr")
    Truth.assertThat(lowDRConfig).isNotNull()
    Truth.assertThat(lowDRConfig!!.hdr()).isEqualTo(ResTableConfig.COLOR_MODE.HDR_NO)
    Truth.assertThat(lowDRConfig.sdkVersion).isEqualTo(26)
    Truth.assertThat(lowDRConfig.toString()).isEqualTo("lowdr-v26")
  }

  @Test
  fun testParseInvalidVersionQualifier() {
    assertFailsWith<Exception> { parse("-v34") }
    assertFailsWith<Exception> { parse("v0.3") }
    assertFailsWith<Exception> { parse("v3x") }
    assertFailsWith<Exception> { parse("v34.") }
    assertFailsWith<Exception> { parse("v34.x") }
    assertFailsWith<Exception> { parse("v34.1x") }
    assertFailsWith<Exception> { parse("v3x.1") }
    assertFailsWith<Exception> { parse("v34.1.1") }
    assertFailsWith<Exception> { parse("v.37") }
    assertFailsWith<Exception> { parse("300x200-v") }
    assertFailsWith<Exception> { parse("300x200-v0.3") }
    assertFailsWith<Exception> { parse("300x200-v3x") }
    assertFailsWith<Exception> { parse("300x200-v34.") }
    assertFailsWith<Exception> { parse("300x200-v34.x") }
    assertFailsWith<Exception> { parse("300x200-v34.1x") }
    assertFailsWith<Exception> { parse("300x200-v3x.1") }
    assertFailsWith<Exception> { parse("300x200-v34.1.1") }
    assertFailsWith<Exception> { parse("300x200-v.37") }
  }

    @Test
    fun testParseValidVersionQualifier() {
        assertThat(parse("v34"))
            .isEqualTo(ConfigDescription(ResTableConfig(sdkVersion = 34, minorVersion = 0)))
        assertThat(parse("v0"))
            .isEqualTo(ConfigDescription(ResTableConfig(sdkVersion = 0, minorVersion = 0)))
        assertThat(parse("v34.0"))
            .isEqualTo(ConfigDescription(ResTableConfig(sdkVersion = 34, minorVersion = 0)))
        assertThat(parse("v19876"))
            .isEqualTo(ConfigDescription(ResTableConfig(sdkVersion = 19876, minorVersion = 0)))
        assertThat(parse("v19876.000"))
            .isEqualTo(ConfigDescription(ResTableConfig(sdkVersion = 19876, minorVersion = 0)))
        assertThat(parse("v19876.23450"))
            .isEqualTo(ConfigDescription(ResTableConfig(sdkVersion = 19876, minorVersion = 23450)))
        assertThat(parse("v019876.023450"))
            .isEqualTo(ConfigDescription(ResTableConfig(sdkVersion = 19876, minorVersion = 23450)))
        assertThat(parse("v34.1"))
            .isEqualTo(ConfigDescription(ResTableConfig(sdkVersion = 34, minorVersion = 1)))


    }

}
