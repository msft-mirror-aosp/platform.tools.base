/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.resources

import com.google.common.truth.Truth.assertThat
import java.text.Bidi
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

class PseudolocalizerTest {
  private fun simpleTest(input: String, expected: String, method: Pseudolocalizer.Method) {
    val localizer = Pseudolocalizer(method)
    assertThat(localizer.localize(input)).isEqualTo(expected)
  }

  private fun compoundTest(
    expected: String,
    method: Pseudolocalizer.Method,
    vararg inputs: String,
  ) {
    val localizer = Pseudolocalizer(method)
    val result = StringBuilder().append(localizer.start())
    for (input in inputs) {
      result.append(localizer.text(input))
    }
    result.append(localizer.end())
    assertThat(result.toString()).isEqualTo(expected)
  }

  @Test
  fun testNoPseudolocalization() {
    simpleTest("", "", Pseudolocalizer.Method.NONE)
    simpleTest("Hello, world", "Hello, world", Pseudolocalizer.Method.NONE)
    compoundTest("Hello, world", Pseudolocalizer.Method.NONE, "Hello,", " world")
  }

  @Test
  fun testPlainTextAccent() {
    simpleTest("", "[]", Pseudolocalizer.Method.ACCENT)
    simpleTest("Android", "[Åñðŕöîð one two]", Pseudolocalizer.Method.ACCENT)
    simpleTest("Hello, world", "[Ĥéļļö, ŵöŕļð one two]", Pseudolocalizer.Method.ACCENT)
    simpleTest("Hello, %1d", "[Ĥéļļö, »%1d« one two]", Pseudolocalizer.Method.ACCENT)
    simpleTest("Battery %1d%%", "[βåţţéŕý »%1d«%% one two]", Pseudolocalizer.Method.ACCENT)
    simpleTest("^1 %", "[^1 % one]", Pseudolocalizer.Method.ACCENT)
    compoundTest("[]", Pseudolocalizer.Method.ACCENT, "", "", "")
    compoundTest("[Ĥéļļö, ŵöŕļð one two]", Pseudolocalizer.Method.ACCENT, "Hello,", " world", "")
  }

  @Test
  fun testPlainTextBidi() {
    simpleTest("", "", Pseudolocalizer.Method.BIDI)
    simpleTest("word", "${BIDI_WORD_START}word$BIDI_WORD_END", Pseudolocalizer.Method.BIDI)
    simpleTest("  word  ", "  ${BIDI_WORD_START}word$BIDI_WORD_END  ", Pseudolocalizer.Method.BIDI)
    simpleTest(
      "hello\n world\n",
      "${BIDI_WORD_START}hello$BIDI_WORD_END\n ${BIDI_WORD_START}world$BIDI_WORD_END\n",
      Pseudolocalizer.Method.BIDI,
    )
    simpleTest(
      "Don\\'t\\nstop now",
      "${BIDI_WORD_START}Don\\'t$BIDI_WORD_END\\n${BIDI_WORD_START}stop$BIDI_WORD_END ${BIDI_WORD_START}now$BIDI_WORD_END",
      Pseudolocalizer.Method.BIDI,
    )
    compoundTest(
      "${BIDI_WORD_START}hello$BIDI_WORD_END\n ${BIDI_WORD_START}world$BIDI_WORD_END\n",
      Pseudolocalizer.Method.BIDI,
      "hello",
      "\n ",
      "world\n",
    )
  }

  @Test
  fun testBidiVisualOrderAndPunctuation() {
    val localizer = Pseudolocalizer(Pseudolocalizer.Method.BIDI)

    val cancelBidi = localizer.localize("Cancel")
    assertThat(cancelBidi).isEqualTo("${BIDI_WORD_START}Cancel$BIDI_WORD_END")
    assertThat(renderBidiVisualOrder(cancelBidi)).isEqualTo("lecnaC")

    val multiWordBidi = localizer.localize("Hello Android")
    assertThat(multiWordBidi).isEqualTo("${BIDI_WORD_START}Hello$BIDI_WORD_END ${BIDI_WORD_START}Android$BIDI_WORD_END")
    assertThat(renderBidiVisualOrder(multiWordBidi)).isEqualTo("diordnA olleH")

    // Sentence-ending punctuation attached to the final word is included inside that word's
    // RLO...PDF block, so in RTL visual order "pseudolocales rule!" displays as
    // "!elur selacoloduesp" (punctuation at the left end of the RTL sentence).
    // See https://developer.android.com/guide/topics/resources/pseudolocales#spot_localization_issues
    val punctuatedBidi = localizer.localize("pseudolocales rule!")
    assertThat(punctuatedBidi).isEqualTo("${BIDI_WORD_START}pseudolocales$BIDI_WORD_END ${BIDI_WORD_START}rule!$BIDI_WORD_END")
    assertThat(renderBidiVisualOrder(punctuatedBidi)).isEqualTo("!elur selacoloduesp")

    // Conversely, if an app hardcodes "!" outside the localized resource in a forced-LTR container,
    // the exclamation mark stays on the right ("elur selacoloduesp!"), exposing the RTL bug.
    val hardcodedPunctuationInLtr = localizer.localize("pseudolocales rule") + "!"
    assertThat(renderBidiVisualOrder(hardcodedPunctuationInLtr, Bidi.DIRECTION_LEFT_TO_RIGHT)).isEqualTo("elur selacoloduesp!")

    // Mid-sentence punctuation (comma, question mark) also mirrors with its word across RTL flow.
    val questionBidi = localizer.localize("Ready, set, go?")
    assertThat(questionBidi)
      .isEqualTo(
        "${BIDI_WORD_START}Ready,$BIDI_WORD_END " + "${BIDI_WORD_START}set,$BIDI_WORD_END " + "${BIDI_WORD_START}go?$BIDI_WORD_END"
      )
    assertThat(renderBidiVisualOrder(questionBidi)).isEqualTo("?og ,tes ,ydaeR")
  }

  @Test
  fun testSimpleICU() {
    // Single-fragment messages.
    simpleTest("{placeholder}", "[»{placeholder}«]", Pseudolocalizer.Method.ACCENT)
    simpleTest("{USER} is offline", "[»{USER}« îš öƒƒļîñé one two]", Pseudolocalizer.Method.ACCENT)
    simpleTest(
      "Copy from {path1} to {path2}",
      "[Çöþý ƒŕöḿ »{path1}« ţö »{path2}« one two three]",
      Pseudolocalizer.Method.ACCENT,
    )
    simpleTest(
      "Today is {1,date} {1,time}",
      "[Ţöðåý îš »{1,date}« »{1,time}« one two]",
      Pseudolocalizer.Method.ACCENT,
    )

    // Multi-fragment messages.
    compoundTest(
      "[»{USER}« îš öƒƒļîñé one two]",
      Pseudolocalizer.Method.ACCENT,
      "{USER}",
      " ",
      "is offline",
    )
    compoundTest(
      "[Çöþý ƒŕöḿ »{path1}« ţö »{path2}« one two three]",
      Pseudolocalizer.Method.ACCENT,
      "Copy from ",
      "{path1}",
      " to",
      " {path2}",
    )
  }

  @Test
  fun testICUBidi() {
    // Single-fragment messages.
    simpleTest(
      "{placeholder}",
      "$BIDI_WORD_START{placeholder}$BIDI_WORD_END",
      Pseudolocalizer.Method.BIDI,
    )
    simpleTest(
      "{USER} is offline",
      "$BIDI_WORD_START{USER}$BIDI_WORD_END ${BIDI_WORD_START}is$BIDI_WORD_END ${BIDI_WORD_START}offline$BIDI_WORD_END",
      Pseudolocalizer.Method.BIDI,
    )
    assertThat(renderBidiVisualOrder(Pseudolocalizer(Pseudolocalizer.Method.BIDI).localize("{USER} is offline")))
      .isEqualTo("enilffo si }RESU{")

    simpleTest(
      "Copy from {path1} to {path2}!",
      "${BIDI_WORD_START}Copy$BIDI_WORD_END ${BIDI_WORD_START}from$BIDI_WORD_END " +
        "$BIDI_WORD_START{path1}$BIDI_WORD_END ${BIDI_WORD_START}to$BIDI_WORD_END " +
        "$BIDI_WORD_START{path2}$BIDI_WORD_END${BIDI_WORD_START}!$BIDI_WORD_END",
      Pseudolocalizer.Method.BIDI,
    )

    simpleTest(
      "{COUNT, plural, one {one} other {other}}",
      "{COUNT, plural, one {${BIDI_WORD_START}one$BIDI_WORD_END} " + "other {${BIDI_WORD_START}other$BIDI_WORD_END}}",
      Pseudolocalizer.Method.BIDI,
    )
    simpleTest(
      "{COUNT, plural, one {Delete a file} other {Delete {COUNT} files}}",
      "{COUNT, plural, " +
        "one {${BIDI_WORD_START}Delete$BIDI_WORD_END ${BIDI_WORD_START}a$BIDI_WORD_END ${BIDI_WORD_START}file$BIDI_WORD_END} " +
        "other {${BIDI_WORD_START}Delete$BIDI_WORD_END $BIDI_WORD_START{COUNT}$BIDI_WORD_END ${BIDI_WORD_START}files$BIDI_WORD_END}}",
      Pseudolocalizer.Method.BIDI,
    )
  }

  @Test
  fun testEscaping() {
    // Single-fragment messages.
    simpleTest(
      "'{USER'} is offline",
      "['{ÛŠÉŔ'} îš öƒƒļîñé one two three]",
      Pseudolocalizer.Method.ACCENT,
    )

    // Multi-fragment messages.
    compoundTest(
      "['{ÛŠÉŔ} ''îš öƒƒļîñé one two three]",
      Pseudolocalizer.Method.ACCENT,
      "'{USER}",
      " ",
      "''is offline",
    )
  }

  @Test
  fun testPluralsAndSelects() {
    simpleTest(
      "{COUNT, plural, one {Delete a file} other {Delete {COUNT} files}}",
      "[{COUNT, plural, one {Ðéļéţé å ƒîļé one two} other {Ðéļéţé »{COUNT}« ƒîļéš one two}}]",
      Pseudolocalizer.Method.ACCENT,
    )
    simpleTest(
      "Distance is {COUNT, plural, one {# mile} other {# miles}}",
      "[Ðîšţåñçé îš {COUNT, plural, one {# ḿîļé one two} other {# ḿîļéš one two}}]",
      Pseudolocalizer.Method.ACCENT,
    )

    simpleTest(
      "{1, select, female {{1} added you} male {{1} added you} other {{1} added you}}",
      "[{1, select, female {»{1}« åððéð ýöû one two} male {»{1}« åððéð ýöû one two} " + "other {»{1}« åððéð ýöû one two}}]",
      Pseudolocalizer.Method.ACCENT,
    )

    compoundTest(
      "[{COUNT, plural, one {Ðéļéţé å ƒîļé one two} other {Ðéļéţé »{COUNT}« ƒîļéš one two}}]",
      Pseudolocalizer.Method.ACCENT,
      "{COUNT, plural, one {Delete a file} ",
      "other {Delete ",
      "{COUNT}",
      " files}}",
    )
  }

  @Test
  fun testNestedICU() {
    simpleTest(
      "{person, select, female {{num_circles, plural,=0{{person} didn't add you " +
        "to any of her circles.}=1{{person} added you to one of her circles.}other{{person} added " +
        "you to her # circles.}}}male {{num_circles, plural,=0{{person} didn't add you to any of " +
        "his circles.}=1{{person} added you to one of his circles.}other{{person} added you to his " +
        "# circles.}}}other {{num_circles, plural,=0{{person} didn't add you to any of their " +
        "circles.}=1{{person} added you to one of their circles.}other{{person} added you to their " +
        "# circles.}}}}",
      "[{person, select, female {{num_circles, plural,=0{»{person}« ðîðñ'ţ åðð ýöû ţö åñý öƒ " +
        "ĥéŕ çîŕçļéš. one two three four five}=1{»{person}« åððéð ýöû ţö öñé öƒ ĥéŕ çîŕçļéš. one " +
        "two three four}other{»{person}« åððéð ýöû ţö ĥéŕ # çîŕçļéš. one two three four}}}male " +
        "{{num_circles, plural,=0{»{person}« ðîðñ'ţ åðð ýöû ţö åñý öƒ ĥîš çîŕçļéš. one two three " +
        "four five}=1{»{person}« åððéð ýöû ţö öñé öƒ ĥîš çîŕçļéš. one two three four}other" +
        "{»{person}« åððéð ýöû ţö ĥîš # çîŕçļéš. one two three four}}}other {{num_circles, " +
        "plural,=0{»{person}« ðîðñ'ţ åðð ýöû ţö åñý öƒ ţĥéîŕ çîŕçļéš. one two three four five}" +
        "=1{»{person}« åððéð ýöû ţö öñé öƒ ţĥéîŕ çîŕçļéš. one two three four}other{»{person}« " +
        "åððéð ýöû ţö ţĥéîŕ # çîŕçļéš. one two three four}}}}]",
      Pseudolocalizer.Method.ACCENT,
    )
  }

  @Test
  fun testRedefineMidLocalization() {
    val localizer = Pseudolocalizer(Pseudolocalizer.Method.ACCENT)
    val result = StringBuilder(localizer.text("Hello, "))
    localizer.setMethod(Pseudolocalizer.Method.NONE)
    result.append(localizer.text("world!"))
    assertThat(result.toString()).isEqualTo("Ĥéļļö, world!")
  }

  @Test
  fun testNonHtmlTags() {
    simpleTest(
      "Started &lt; 1 minute ago",
      "[Šţåŕţéð &ļţ; 1 ḿîñûţé åĝö one two three]",
      Pseudolocalizer.Method.ACCENT,
    )
    simpleTest(
      "Started &lt;",
      "[Šţåŕţéð &ļţ; one two]",
      Pseudolocalizer.Method.ACCENT,
    )
    simpleTest(
      "Started < 1 minute ago",
      "[Šţåŕţéð < 1 ḿîñûţé åĝö one two three]",
      Pseudolocalizer.Method.ACCENT,
    )
    simpleTest(
      "Started &gt; 1 minute ago",
      "[Šţåŕţéð &gt; 1 ḿîñûţé åĝö one two three]",
      Pseudolocalizer.Method.ACCENT,
    )
    simpleTest(
      "Started > 1 minute ago",
      "[Šţåŕţéð > 1 ḿîñûţé åĝö one two three]",
      Pseudolocalizer.Method.ACCENT,
    )
    simpleTest(
      "Started &lt; 1 minute ago",
      "Started &lt; 1 minute ago",
      Pseudolocalizer.Method.NONE,
    )
    simpleTest("Started < 1 minute ago", "Started < 1 minute ago", Pseudolocalizer.Method.NONE)

    simpleTest(
      "hello\n &lt;\n world\n",
      "${BIDI_WORD_START}hello$BIDI_WORD_END\n " + "${BIDI_WORD_START}&lt;$BIDI_WORD_END\n " + "${BIDI_WORD_START}world$BIDI_WORD_END\n",
      Pseudolocalizer.Method.BIDI,
    )
    simpleTest(
      "hello\n <\n world\n",
      "${BIDI_WORD_START}hello$BIDI_WORD_END\n " + "${BIDI_WORD_START}<$BIDI_WORD_END\n " + "${BIDI_WORD_START}world$BIDI_WORD_END\n",
      Pseudolocalizer.Method.BIDI,
    )
  }

  @Test
  fun testHtmlTags() {
    // Words should be translated, but html tags and their contents should not.
    simpleTest(
      "hello <i>happy</i> world",
      "[ĥéļļö <i>ĥåþþý</i> ŵöŕļð one two three]",
      Pseudolocalizer.Method.ACCENT,
    )
    simpleTest(
      "hello &lt;i&gt;happy&lt;/i&gt; world",
      "[ĥéļļö &lt;i&gt;ĥåþþý&lt;/i&gt; ŵöŕļð one two three]",
      Pseudolocalizer.Method.ACCENT,
    )

    // The tag (i.e. "a href="idk"") should not be translated, but the inner content (i.e."world")
    // should be.
    simpleTest(
      "hello <a href=\"idk\"/>world</a>",
      "[ĥéļļö <a href=\"idk\"/>ŵöŕļð</a> one two]",
      Pseudolocalizer.Method.ACCENT,
    )

    // Even if from HTML's point of view the tags are placed incorrectly, the pseudolocalizer should
    // be able to handle them: not translate the tags, and only translate the contents outside and
    // between them.
    simpleTest(
      "Incorrectly <b> nested <i> tags </b> should </i> still <a> work",
      "[Îñçöŕŕéçţļý <b> ñéšţéð <i> ţåĝš </b> šĥöûļð </i> šţîļļ <a> ŵöŕķ one two three four five six]",
      Pseudolocalizer.Method.ACCENT,
    )
  }
}
