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
package com.android.ide.common.resources

import com.android.ide.common.rendering.api.PluralsResourceValue
import com.android.ide.common.rendering.api.PluralsResourceValueImpl
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.TextResourceValue
import com.android.ide.common.rendering.api.TextResourceValueImpl
import com.android.ide.common.resources.configuration.FolderConfiguration
import com.android.ide.common.resources.configuration.LocaleQualifier
import com.android.resources.Pseudolocalizer
import com.android.resources.ResourceType
import com.google.common.truth.Truth.assertThat
import java.text.Bidi
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val BIDI_WORD_START = "\u200f\u202e"
private const val BIDI_WORD_END = "\u202c\u200f"

/**
 * Simulates Unicode BiDi visual layout (UAX #9) using [java.text.Bidi] and strips invisible BiDi control characters (`RLM`, `RLO`, `PDF`),
 * returning the left-to-right visual character order as displayed on screen.
 */
private fun renderBidiVisualOrder(
  logicalText: String,
  flags: Int = Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT,
): String {
  if (logicalText.isEmpty()) return ""
  val bidi = Bidi(logicalText, flags)
  val levels = ByteArray(logicalText.length) { i -> bidi.getLevelAt(i).toByte() }
  val chars = Array(logicalText.length) { i -> logicalText[i] }
  Bidi.reorderVisually(levels, 0, chars, 0, chars.size)
  return buildString {
    for (ch in chars) {
      if (ch != '\u200f' && ch != '\u202e' && ch != '\u202c') {
        append(ch)
      }
    }
  }
}

class ResourceRepositoryUtilPseudolocaleTest {
  private val resourceFixture = ResourceRepositoryFixture()

  @Before
  fun setUp() {
    resourceFixture.setUp()
  }

  @After
  fun tearDown() {
    resourceFixture.tearDown()
  }

  @Test
  fun testLocaleQualifierMethodMapping() {
    assertThat(Locale.create("en-rXA").qualifier.getPseudolocalizerMethod()).isEqualTo(Pseudolocalizer.Method.ACCENT)
    assertThat(Locale.create("b+en+XA").qualifier.getPseudolocalizerMethod()).isEqualTo(Pseudolocalizer.Method.ACCENT)
    assertThat(LocaleQualifier(null, "en", null, "Enxa").getPseudolocalizerMethod()).isEqualTo(Pseudolocalizer.Method.ACCENT)
    assertThat(Locale.create("ar-rXB").qualifier.getPseudolocalizerMethod()).isEqualTo(Pseudolocalizer.Method.BIDI)
    assertThat(Locale.create("b+ar+XB").qualifier.getPseudolocalizerMethod()).isEqualTo(Pseudolocalizer.Method.BIDI)
    assertThat(LocaleQualifier(null, "ar", null, "Arxb").getPseudolocalizerMethod()).isEqualTo(Pseudolocalizer.Method.BIDI)
    assertThat(Locale.create("en-rUS").qualifier.getPseudolocalizerMethod()).isEqualTo(Pseudolocalizer.Method.NONE)
    assertThat(Locale.create("ar").qualifier.getPseudolocalizerMethod()).isEqualTo(Pseudolocalizer.Method.NONE)
  }

  @Test
  fun testResourceReferencesAreNotPseudolocalized() {
    assertThat(pseudolocalizeString("@string/other_greeting", Pseudolocalizer.Method.ACCENT)).isEqualTo("@string/other_greeting")
    assertThat(pseudolocalizeString("?attr/textAppearance", Pseudolocalizer.Method.BIDI)).isEqualTo("?attr/textAppearance")
  }

  @Test
  fun testPseudolocalizeResourceValueAccentAndBidi() {
    val textValue =
      TextResourceValueImpl(
          ResourceNamespace.RES_AUTO,
          "xliff_greeting",
          "Hello (Android)",
          "Hello <xliff:g id=\"name\" example=\"Android\">%1\$s</xliff:g>",
          null,
        )
        .apply { renderingValue = "Hello %1\$s" }

    val accentText = pseudolocalizeResourceValue(textValue, Pseudolocalizer.Method.ACCENT) as TextResourceValue
    assertThat(accentText.value).isEqualTo("[Ĥéļļö (Åñðŕöîð) one two three]")
    assertThat(accentText.renderingValue).isEqualTo("[Ĥéļļö »%1\$s« one two]")

    val bidiText = pseudolocalizeResourceValue(textValue, Pseudolocalizer.Method.BIDI) as TextResourceValue
    assertThat(bidiText.value).isEqualTo("${BIDI_WORD_START}Hello$BIDI_WORD_END ${BIDI_WORD_START}(Android)$BIDI_WORD_END")
    assertThat(renderBidiVisualOrder(bidiText.value!!)).isEqualTo(")diordnA( olleH")
    assertThat(bidiText.renderingValue).isEqualTo("${BIDI_WORD_START}Hello$BIDI_WORD_END ${BIDI_WORD_START}%1\$s$BIDI_WORD_END")
    assertThat(renderBidiVisualOrder(bidiText.renderingValue!!)).isEqualTo("s\$1% olleH")

    val cdataValue =
      TextResourceValueImpl(
          ResourceNamespace.RES_AUTO,
          "cdata_greeting",
          "Hello",
          "<![CDATA[Hello]]>",
          null,
        )
        .apply { renderingValue = "\n  Hello\n" }
    val accentCdata = pseudolocalizeResourceValue(cdataValue, Pseudolocalizer.Method.ACCENT) as TextResourceValue
    assertThat(accentCdata.value).isEqualTo("[Ĥéļļö one]")
    assertThat(accentCdata.renderingValue).isEqualTo("[Ĥéļļö one]")

    val bidiCdata = pseudolocalizeResourceValue(cdataValue, Pseudolocalizer.Method.BIDI) as TextResourceValue
    assertThat(bidiCdata.value).isEqualTo("${BIDI_WORD_START}Hello$BIDI_WORD_END")
    assertThat(renderBidiVisualOrder(bidiCdata.value!!)).isEqualTo("olleH")
    assertThat(bidiCdata.renderingValue).isEqualTo("${BIDI_WORD_START}Hello$BIDI_WORD_END")

    val plainTextWithExplicitRawAndRendering =
      TextResourceValueImpl(
          ResourceNamespace.RES_AUTO,
          "plain_greeting",
          "Hello",
          "Hello",
          null,
        )
        .apply { renderingValue = "Hello" }
    val expectedPlainCopy =
      TextResourceValueImpl(
          ResourceNamespace.RES_AUTO,
          "plain_greeting",
          "[Ĥéļļö one]",
          "[Ĥéļļö one]",
          null,
        )
        .apply { renderingValue = "[Ĥéļļö one]" }
    val accentPlain = pseudolocalizeResourceValue(plainTextWithExplicitRawAndRendering, Pseudolocalizer.Method.ACCENT)
    assertThat(accentPlain).isEqualTo(expectedPlainCopy)

    val pluralsValue =
      PluralsResourceValueImpl(ResourceNamespace.RES_AUTO, "items", null, null).apply {
        addPlural("one", "%d item")
        addPlural("other", "%d items")
      }
    val accentPlurals = pseudolocalizeResourceValue(pluralsValue, Pseudolocalizer.Method.ACCENT) as PluralsResourceValue
    assertThat(accentPlurals.getValue("one")).isEqualTo("[»%d« îţéḿ one]")
    assertThat(accentPlurals.getValue("other")).isEqualTo("[»%d« îţéḿš one two]")

    val bidiPlurals = pseudolocalizeResourceValue(pluralsValue, Pseudolocalizer.Method.BIDI) as PluralsResourceValue
    assertThat(bidiPlurals.getValue("one")).isEqualTo("${BIDI_WORD_START}%d$BIDI_WORD_END ${BIDI_WORD_START}item$BIDI_WORD_END")
    assertThat(renderBidiVisualOrder(bidiPlurals.getValue("one"))).isEqualTo("meti d%")
    assertThat(bidiPlurals.getValue("other")).isEqualTo("${BIDI_WORD_START}%d$BIDI_WORD_END ${BIDI_WORD_START}items$BIDI_WORD_END")
    assertThat(renderBidiVisualOrder(bidiPlurals.getValue("other"))).isEqualTo("smeti d%")
  }

  @Test
  fun testGetConfiguredResourcesWithPseudolocales() {
    val repository =
      resourceFixture.createTestResources(
        ResourceNamespace.TODO(),
        arrayOf(
          "values/strings.xml",
          // language=XML
          """
          <resources>
            <string name="greeting">Android</string>
            <string name="tagline">pseudolocales rule!</string>
            <string name="custom_override">Default</string>
            <string name="alias_greeting">@string/greeting</string>
            <plurals name="items">
              <item quantity="one">%d item</item>
              <item quantity="other">%d items</item>
            </plurals>
          </resources>
          """
            .trimIndent(),
          // Regional translations (values-en-rUS and values-ar) must NOT prevent pseudolocalizing
          // default strings for en-rXA and ar-rXB.
          "values-en-rUS/strings.xml",
          // language=XML
          """
          <resources>
            <string name="greeting">US Android</string>
          </resources>
          """
            .trimIndent(),
          "values-ar/strings.xml",
          // language=XML
          """
          <resources>
            <string name="greeting">أندرويد</string>
          </resources>
          """
            .trimIndent(),
          // Explicit pseudolocale override in values-en-rXA must be preserved without
          // re-pseudolocalizing.
          "values-en-rXA/strings.xml",
          // language=XML
          """
          <resources>
            <string name="custom_override">Explicit EnXA</string>
          </resources>
          """
            .trimIndent(),
        ),
      )

    val enXaConfig =
      FolderConfiguration.createDefault().apply {
        localeQualifier = Locale.create("en-rXA").qualifier
      }
    val enXaTable = repository.getConfiguredResources(enXaConfig)
    val enXaStrings = enXaTable[ResourceNamespace.TODO(), ResourceType.STRING]!!
    assertThat(enXaStrings["greeting"]?.value).isEqualTo("[Åñðŕöîð one two]")
    assertThat(enXaStrings["tagline"]?.value).isEqualTo("[þšéûðöļöçåļéš ŕûļé¡ one two three]")
    assertThat(enXaStrings["custom_override"]?.value).isEqualTo("Explicit EnXA")
    assertThat(enXaStrings["alias_greeting"]?.value).isEqualTo("@string/greeting")
    val enXaPlurals = enXaTable[ResourceNamespace.TODO(), ResourceType.PLURALS]!!["items"] as PluralsResourceValue
    assertThat(enXaPlurals.getValue("other")).isEqualTo("[»%d« îţéḿš one two]")
    assertThat(repository.getConfiguredValue(ResourceType.STRING, "greeting", enXaConfig)?.value).isEqualTo("[Åñðŕöîð one two]")

    val arXbConfig =
      FolderConfiguration.createDefault().apply {
        localeQualifier = Locale.create("ar-rXB").qualifier
      }
    val arXbTable = repository.getConfiguredResources(arXbConfig)
    val arXbStrings = arXbTable[ResourceNamespace.TODO(), ResourceType.STRING]!!
    assertThat(arXbStrings["greeting"]?.value).isEqualTo("${BIDI_WORD_START}Android$BIDI_WORD_END")
    assertThat(renderBidiVisualOrder(arXbStrings["greeting"]!!.value!!)).isEqualTo("diordnA")

    assertThat(arXbStrings["tagline"]?.value)
      .isEqualTo("${BIDI_WORD_START}pseudolocales$BIDI_WORD_END ${BIDI_WORD_START}rule!$BIDI_WORD_END")
    assertThat(renderBidiVisualOrder(arXbStrings["tagline"]!!.value!!)).isEqualTo("!elur selacoloduesp")

    assertThat(arXbStrings["custom_override"]?.value).isEqualTo("${BIDI_WORD_START}Default$BIDI_WORD_END")
    assertThat(renderBidiVisualOrder(arXbStrings["custom_override"]!!.value!!)).isEqualTo("tluafeD")
    assertThat(arXbStrings["alias_greeting"]?.value).isEqualTo("@string/greeting")
    assertThat(repository.getConfiguredValue(ResourceType.STRING, "tagline", arXbConfig)?.value)
      .isEqualTo("${BIDI_WORD_START}pseudolocales$BIDI_WORD_END ${BIDI_WORD_START}rule!$BIDI_WORD_END")
  }
}
