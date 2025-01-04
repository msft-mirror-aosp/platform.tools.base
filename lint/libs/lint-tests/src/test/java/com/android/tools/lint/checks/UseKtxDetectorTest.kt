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

package com.android.tools.lint.checks

import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector

class UseKtxDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector {
    return UseKtxDetector()
  }

  override fun lint(): TestLintTask {
    // We don't have stubs for most of the extension functions we're offering here in the
    // tests, so turn off the library requirement. The testObtainStyledAttributesWithImportSettings
    // unit tests makes sure it works with the option on.
    return super.lint().configureOption(UseKtxDetector.REQUIRE_LIBRARY, false)
  }

  fun testObtainStyledAttributes() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.app.Activity
            import android.content.Context
            import android.content.res.TypedArray
            import android.graphics.Color
            import android.view.Menu

            class Test(style: Int, styleable: IntArray, colorIndex: Int) {
                private var backgroundColor: Int
                private fun getContext(): Context = TODO()

                init {
                    val styled = getContext().obtainStyledAttributes(style, styleable) // WARN 1
                    backgroundColor = styled.getColor(colorIndex,Color.argb(128, 80, 80, 80))
                    styled.recycle()
                }
            }

            class MyActivity : Activity() {
                fun test(menu: Menu, id: Int) {
                    val drawableAttr = intArrayOf()
                    val typedArray = obtainStyledAttributes(drawableAttr) // WARN 2
                    menu.findItem(id).icon = typedArray.getDrawable(0)
                    println(typedArray)
                    typedArray.recycle()
                }
            }

            class MyActivity2 : Activity() {
                fun TypedArray.logType(): TypedArray {
                    android.util.Log.w(null, this.toString())
                    return this
                }
                fun test(menu: Menu, id: Int) {
                    val drawableAttr = intArrayOf()
                    val typedArray = obtainStyledAttributes(drawableAttr) // WARN 3
                    menu.findItem(id).icon = typedArray.getDrawable(0)
                    // Make sure we preserve the .logType() and .apply calls when converting
                    // to block!
                    typedArray.logType().apply { println(getDrawable(0)) }.recycle()
                }
            }

            fun cannotExtract1(context: Context, style: Int, attr: IntArray) {
                val styled = context.obtainStyledAttributes(style, attr) // OK 1
                styled.recycle()
                android.util.Log.e(null, styled.toString()) // styled variable referenced outside of block
            }

            fun cannotExtract2(context: Context, style: Int, attr: IntArray) {
                val styled = context.obtainStyledAttributes(style, attr) // OK 2
                var something = ""
                styled.recycle()
                android.util.Log.e(null, something) // variable accessed outside
            }

            fun cannotExtract3(context: Context, style: Int, attr: IntArray) {
                val styled = context.obtainStyledAttributes(style, attr) // OK 3
                if (false) {
                    styled.recycle()
                }
            }
            """
          )
          .indented(),
        kotlin(
            "src/test/pkg/Test2.kt",
            """
            package test.pkg

            import android.content.Context
            import android.graphics.drawable.Drawable
            import android.util.AttributeSet
            import android.widget.ImageView
            import android.widget.RelativeLayout
            import android.widget.TextView

            class MyView(
                context: Context,
                attrs: AttributeSet? = null,
                viewId: Int,
                icon: Int,
                label: Int,
                i1: IntArray,
                icon1: Int,
                desc: Int,
                label2: Int
            ) : RelativeLayout(context, attrs, 0) {
                private val iconView: ImageView
                private val labelView: TextView

                init {
                    inflate(context, viewId, this)
                    iconView = findViewById(icon)
                    labelView = findViewById(label)
                    val array = context.obtainStyledAttributes(attrs, i1) // WARN 4
                    val iconId = array.getResourceId(icon1, -1)
                    val icon: Drawable? = if (iconId > 0) resources.getDrawable(iconId, null) else null
                    val contentDescription = array.getString(desc)
                    val label = array.getString(label2)
                    iconView.setImageDrawable(icon)
                    iconView.contentDescription = contentDescription
                    labelView.text = label
                    array.recycle()
                }
            }
            """,
          )
          .indented(),
      )
      .skipTestModes(TestMode.PARENTHESIZED)
      .run()
      .expect(
        """
        src/test/pkg/Test.kt:14: Warning: Use the KTX extension function Context.withStyledAttributes instead? [UseKtx]
                val styled = getContext().obtainStyledAttributes(style, styleable) // WARN 1
                             ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/Test.kt:23: Warning: Use the KTX extension function Context.withStyledAttributes instead? [UseKtx]
                val typedArray = obtainStyledAttributes(drawableAttr) // WARN 2
                                 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/Test.kt:37: Warning: Use the KTX extension function Context.withStyledAttributes instead? [UseKtx]
                val typedArray = obtainStyledAttributes(drawableAttr) // WARN 3
                                 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/Test2.kt:28: Warning: Use the KTX extension function Context.withStyledAttributes instead? [UseKtx]
                val array = context.obtainStyledAttributes(attrs, i1) // WARN 4
                            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 4 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/Test.kt line 14: Replace with the withStyledAttributes extension function:
        @@ -8 +8
        + import androidx.core.content.withStyledAttributes
        @@ -14 +15
        -         val styled = getContext().obtainStyledAttributes(style, styleable) // WARN 1
        -         backgroundColor = styled.getColor(colorIndex,Color.argb(128, 80, 80, 80))
        -         styled.recycle()
        +         getContext().withStyledAttributes(style, styleable) { // WARN 1
        +             backgroundColor = getColor(colorIndex,Color.argb(128, 80, 80, 80))
        +         }
        Autofix for src/test/pkg/Test.kt line 23: Replace with the withStyledAttributes extension function:
        @@ -8 +8
        + import androidx.core.content.withStyledAttributes
        @@ -23 +24
        -         val typedArray = obtainStyledAttributes(drawableAttr) // WARN 2
        -         menu.findItem(id).icon = typedArray.getDrawable(0)
        -         println(typedArray)
        -         typedArray.recycle()
        +         withStyledAttributes(null, drawableAttr) { // WARN 2
        +             menu.findItem(id).icon = getDrawable(0)
        +             println(this)
        +         }
        Autofix for src/test/pkg/Test.kt line 37: Replace with the withStyledAttributes extension function:
        @@ -8 +8
        + import androidx.core.content.withStyledAttributes
        @@ -37 +38
        -         val typedArray = obtainStyledAttributes(drawableAttr) // WARN 3
        -         menu.findItem(id).icon = typedArray.getDrawable(0)
        -         // Make sure we preserve the .logType() and .apply calls when converting
        -         // to block!
        -         typedArray.logType().apply { println(getDrawable(0)) }.recycle()
        +         withStyledAttributes(null, drawableAttr) { // WARN 3
        +             menu.findItem(id).icon = getDrawable(0)
        +             // Make sure we preserve the .logType() and .apply calls when converting
        +             // to block!
        +         logType().apply { println(getDrawable(0)) }}
        Autofix for src/test/pkg/Test2.kt line 28: Replace with the withStyledAttributes extension function:
        @@ -9 +9
        + import androidx.core.content.withStyledAttributes
        @@ -28 +29
        -         val array = context.obtainStyledAttributes(attrs, i1) // WARN 4
        -         val iconId = array.getResourceId(icon1, -1)
        -         val icon: Drawable? = if (iconId > 0) resources.getDrawable(iconId, null) else null
        -         val contentDescription = array.getString(desc)
        -         val label = array.getString(label2)
        -         iconView.setImageDrawable(icon)
        -         iconView.contentDescription = contentDescription
        -         labelView.text = label
        -         array.recycle()
        +         context.withStyledAttributes(attrs, i1) { // WARN 4
        +             val iconId = getResourceId(icon1, -1)
        +             val icon: Drawable? = if (iconId > 0) resources.getDrawable(iconId, null) else null
        +             val contentDescription = getString(desc)
        +             val label = getString(label2)
        +             iconView.setImageDrawable(icon)
        +             iconView.contentDescription = contentDescription
        +             labelView.text = label
        +         }
        """
      )
  }

  fun testObtainStyledAttributesWithApply() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.app.Activity
            import android.content.Context
            import android.graphics.Color
            import android.view.Menu

            fun testUsingApply(context: Context, style: Int, attr: IntArray) {
                context.obtainStyledAttributes(style, attr).apply { // WARN 1
                    var color = getDrawable(0)
                }.recycle()
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:9: Warning: Use the KTX extension function Context.withStyledAttributes instead? [UseKtx]
            context.obtainStyledAttributes(style, attr).apply { // WARN 1
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
      .verifyFixes()
      .window(1)
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/test.kt line 9: Replace with the withStyledAttributes extension function:
        @@ -7 +7
          import android.view.Menu
        + import androidx.core.content.withStyledAttributes

          fun testUsingApply(context: Context, style: Int, attr: IntArray) {
        -     context.obtainStyledAttributes(style, attr).apply { // WARN 1
        +     context.withStyledAttributes(style, attr) { // WARN 1
                  var color = getDrawable(0)
        -     }.recycle()
        +     }
          }
        """
      )
  }

  fun testObtainStyledAttributesWithImportSettings() {
    val canvasStub =
      kotlin(
          "src/androidx/core/content/Context.kt",
          """
          package androidx.core.content
          import android.content.Context
          import android.content.res.TypedArray
          import android.util.AttributeSet
          public inline fun Context.withStyledAttributes(
              set: AttributeSet? = null, attrs: IntArray,
              defStyleAttr: Int = 0, defStyleRes: Int = 0,
              block: TypedArray.() -> Unit
          ) {
              obtainStyledAttributes(set, attrs, defStyleAttr, defStyleRes).apply(block).recycle()
          }
          public inline fun Context.withStyledAttributes(resourceId: Int, attrs: IntArray, block: TypedArray.() -> Unit) {
              obtainStyledAttributes(resourceId, attrs).apply(block).recycle()
          }
          """,
        )
        .indented()

    val source =
      kotlin(
          """
          package test.pkg

          import android.app.Activity
          import android.content.Context
          import android.graphics.Color
          import android.view.Menu

          fun testUsingApply(context: Context, style: Int, attr: IntArray) {
              context.obtainStyledAttributes(style, attr).apply { // WARN 1
                  var color = getDrawable(0)
              }.recycle()
          }
          """
        )
        .indented()

    // Without the class on the class-path:

    lint().files(source).configureOption(UseKtxDetector.REQUIRE_LIBRARY, true).run().expectClean()

    // *With* the class on the classpath

    lint()
      .files(source, canvasStub)
      .configureOption(UseKtxDetector.REQUIRE_LIBRARY, true)
      .run()
      .expect(
        """
        src/test/pkg/test.kt:9: Warning: Use the KTX extension function Context.withStyledAttributes instead? [UseKtx]
            context.obtainStyledAttributes(style, attr).apply { // WARN 1
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
  }

  fun testObtainStyledAttributesOverloads() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.content.Context
            import android.util.AttributeSet

            fun testUsingApply1(context: Context, style: Int, attrs: IntArray, set: AttributeSet) {
                context.obtainStyledAttributes(style, attrs).apply { // WARN 1
                    var color = getDrawable(0)
                }.recycle()
            }
            fun testUsingApply2(context: Context, style: Int, attrs: IntArray, set: AttributeSet) {
                // Note: the quickfix here has to introduce a null first parameter
                context.obtainStyledAttributes(attrs).apply { // WARN 2
                    var color = getDrawable(0)
                }.recycle()
            }
            fun testUsingApply3(context: Context, style: Int, attrs: IntArray, set: AttributeSet) {
                context.obtainStyledAttributes(set, attrs).apply { // WARN 3
                    var color = getDrawable(0)
                }.recycle()
            }
            fun testUsingApply4(context: Context, style: Int, attrs: IntArray, set: AttributeSet) {
                context.obtainStyledAttributes(set, attrs,0, 0).apply { // WARN 4
                    var color = getDrawable(0)
                }.recycle()
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:7: Warning: Use the KTX extension function Context.withStyledAttributes instead? [UseKtx]
            context.obtainStyledAttributes(style, attrs).apply { // WARN 1
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:13: Warning: Use the KTX extension function Context.withStyledAttributes instead? [UseKtx]
            context.obtainStyledAttributes(attrs).apply { // WARN 2
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:18: Warning: Use the KTX extension function Context.withStyledAttributes instead? [UseKtx]
            context.obtainStyledAttributes(set, attrs).apply { // WARN 3
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:23: Warning: Use the KTX extension function Context.withStyledAttributes instead? [UseKtx]
            context.obtainStyledAttributes(set, attrs,0, 0).apply { // WARN 4
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 4 warnings
        """
      )
      .verifyFixes()
      .window(1)
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/test.kt line 7: Replace with the withStyledAttributes extension function:
        @@ -5 +5
          import android.util.AttributeSet
        + import androidx.core.content.withStyledAttributes

          fun testUsingApply1(context: Context, style: Int, attrs: IntArray, set: AttributeSet) {
        -     context.obtainStyledAttributes(style, attrs).apply { // WARN 1
        +     context.withStyledAttributes(style, attrs) { // WARN 1
                  var color = getDrawable(0)
        -     }.recycle()
        +     }
          }
        Autofix for src/test/pkg/test.kt line 13: Replace with the withStyledAttributes extension function:
        @@ -5 +5
          import android.util.AttributeSet
        + import androidx.core.content.withStyledAttributes

        @@ -13 +14
              // Note: the quickfix here has to introduce a null first parameter
        -     context.obtainStyledAttributes(attrs).apply { // WARN 2
        +     context.withStyledAttributes(null, attrs) { // WARN 2
                  var color = getDrawable(0)
        -     }.recycle()
        +     }
          }
        Autofix for src/test/pkg/test.kt line 18: Replace with the withStyledAttributes extension function:
        @@ -5 +5
          import android.util.AttributeSet
        + import androidx.core.content.withStyledAttributes

        @@ -18 +19
          fun testUsingApply3(context: Context, style: Int, attrs: IntArray, set: AttributeSet) {
        -     context.obtainStyledAttributes(set, attrs).apply { // WARN 3
        +     context.withStyledAttributes(set, attrs) { // WARN 3
                  var color = getDrawable(0)
        -     }.recycle()
        +     }
          }
        Autofix for src/test/pkg/test.kt line 23: Replace with the withStyledAttributes extension function:
        @@ -5 +5
          import android.util.AttributeSet
        + import androidx.core.content.withStyledAttributes

        @@ -23 +24
          fun testUsingApply4(context: Context, style: Int, attrs: IntArray, set: AttributeSet) {
        -     context.obtainStyledAttributes(set, attrs,0, 0).apply { // WARN 4
        +     context.withStyledAttributes(set, attrs,0, 0) { // WARN 4
                  var color = getDrawable(0)
        -     }.recycle()
        +     }
          }
        """
      )
  }

  fun testSharedPreferences() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.content.SharedPreferences

            fun testSharedPref(sharedPreferences: SharedPreferences, value: Boolean) {
                sharedPreferences.edit() // WARN 1
                    .putBoolean("key", value)
                    .apply()
            }

            fun testSharedPref2(sharedPreferences: SharedPreferences, value: Boolean) {
                sharedPreferences.edit() // WARN 2
                    .putBoolean("key", value)
                    .commit()
            }

            fun testSharedPref3(sharedPreferences: SharedPreferences, value: Boolean) {
                val editor = sharedPreferences.edit() // WARN 3
                editor.putBoolean("key", value)
                editor.apply()
            }

            fun testSharedPref4(sharedPreferences: SharedPreferences, value: Boolean) {
                val editor = sharedPreferences.edit() // WARN 4
                editor.putBoolean("key", value)
                editor.commit()
            }
            """
          )
          .indented()
      )
      .skipTestModes(TestMode.PARENTHESIZED)
      .run()
      .expect(
        """
        src/test/pkg/test.kt:6: Warning: Use the KTX extension function SharedPreferences.edit instead? [UseKtx]
            sharedPreferences.edit() // WARN 1
            ~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:12: Warning: Use the KTX extension function SharedPreferences.edit instead? [UseKtx]
            sharedPreferences.edit() // WARN 2
            ~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:18: Warning: Use the KTX extension function SharedPreferences.edit instead? [UseKtx]
            val editor = sharedPreferences.edit() // WARN 3
                         ~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:24: Warning: Use the KTX extension function SharedPreferences.edit instead? [UseKtx]
            val editor = sharedPreferences.edit() // WARN 4
                         ~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 4 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/test.kt line 6: Replace with the edit extension function:
        @@ -4 +4
        + import androidx.core.content.edit
        @@ -6 +7
        -     sharedPreferences.edit() // WARN 1
        -         .putBoolean("key", value)
        -         .apply()
        +     sharedPreferences.edit() { // WARN 1
        +             putBoolean("key", value)
        +         }
        Autofix for src/test/pkg/test.kt line 12: Replace with the edit extension function:
        @@ -4 +4
        + import androidx.core.content.edit
        @@ -12 +13
        -     sharedPreferences.edit() // WARN 2
        -         .putBoolean("key", value)
        -         .commit()
        +     sharedPreferences.edit(commit = true) { // WARN 2
        +             putBoolean("key", value)
        +         }
        Autofix for src/test/pkg/test.kt line 18: Replace with the edit extension function:
        @@ -4 +4
        + import androidx.core.content.edit
        @@ -18 +19
        -     val editor = sharedPreferences.edit() // WARN 3
        -     editor.putBoolean("key", value)
        -     editor.apply()
        +     sharedPreferences.edit() { // WARN 3
        +         putBoolean("key", value)
        +     }
        Autofix for src/test/pkg/test.kt line 24: Replace with the edit extension function:
        @@ -4 +4
        + import androidx.core.content.edit
        @@ -24 +25
        -     val editor = sharedPreferences.edit() // WARN 4
        -     editor.putBoolean("key", value)
        -     editor.commit()
        +     sharedPreferences.edit(commit = true) { // WARN 4
        +         putBoolean("key", value)
        +     }
        """
      )
  }

  fun testCanvas() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.graphics.Canvas
            import android.graphics.Matrix
            import android.graphics.Paint
            import android.graphics.Path
            import android.graphics.Rect
            import android.graphics.RectF
            import android.graphics.Region

            // Canvas tests
            fun canvas1(canvas: Canvas, paint: Paint) {
                canvas.save() // WARN 1
                canvas.translate(200f, 300f)
                canvas.drawCircle(10f, 10f, 10f, paint)
                canvas.restore()
            }

            fun canvas2(canvas: Canvas, paint: Paint) {
                // Two methods in the same one; make sure we don't get confused
                // about variables
                val state = canvas.save() // WARN 2
                canvas.translate(200f, 300f)
                canvas.drawCircle(10f, 10f, 10f, paint)
                canvas.restoreToCount(state)

                val state2 = canvas.save() // WARN 3
                canvas.drawCircle(10f, 10f, 10f, paint)
                canvas.restoreToCount(state2)
            }

            fun canvas3(canvas: Canvas, paint: Paint, restore: Int) {
                val state = canvas.save() // OK - wrong restore variable
                canvas.translate(200f, 300f)
                canvas.drawCircle(10f, 10f, 10f, paint)
                canvas.restoreToCount(restore)
            }

            fun canvasNesting(canvas: Canvas, paint: Paint) {
                val translateCheckpoint = canvas.save() // OK -- not currently handling nesting
                canvas.translate(200f, 300f)
                canvas.drawCircle(10f, 10f, 10f, paint)
                val rotateCheckpoint = canvas.save() // WARN 4 -- innermost nest is allowed
                canvas.rotate(45f)
                canvas.restoreToCount(rotateCheckpoint)
                canvas.restoreToCount(translateCheckpoint)
            }

            fun noOverlapping(canvas: Canvas, canvas2: Canvas, paint: Paint) {
                canvas2.save() // OK
                canvas2.translate(200f, 300f)
                canvas2.drawCircle(10f, 10f, 10f, paint)
                canvas.save() // OK
                canvas.translate(200f, 300f)
                canvas.drawCircle(10f, 10f, 10f, paint)
                canvas.restore()
                canvas2.restore()
            }

            // Test other types of drawing operations
            fun canvasScale(canvas: Canvas, paint: Paint) {
                canvas.save() // WARN 5
                canvas.scale(1F, 2F)
                canvas.drawCircle(10f, 10f, 10f, paint)
                canvas.restore()
            }

            fun canvasSkew(canvas: Canvas, paint: Paint) {
                canvas.save() // WARN 6
                canvas.skew(10F, 20F)
                canvas.drawCircle(10f, 10f, 10f, paint)
                canvas.restore()
            }

            fun canvasMatrix(canvas: Canvas, paint: Paint, matrix: Matrix) {
                canvas.save() // WARN 7
                canvas.concat(matrix)
                canvas.drawCircle(10f, 10f, 10f, paint)
                canvas.restore()
            }

            fun canvasClipRectF(canvas: Canvas, paint: Paint, rect: RectF) {
                canvas.save() // WARN 8
                canvas.clipRect(rect)
                canvas.drawRect(rect, paint)
                canvas.restore()
            }

            fun canvasClipRect1(canvas: Canvas, paint: Paint, rect: Rect) {
                canvas.save() // WARN 9
                canvas.clipRect(rect)
                canvas.drawRect(rect, paint)
                canvas.restore()
            }

            fun canvasClipRect2(canvas: Canvas, paint: Paint, rect: Rect) {
                canvas.save() // WARN 10
                canvas.clipRect(0f, 0f, 100f, 100f)
                canvas.drawRect(rect, paint)
                canvas.restore()
            }

            fun canvasClipRect3(canvas: Canvas, paint: Paint, rect: Rect) {
                canvas.save() // WARN 11
                canvas.clipRect(0, 0, 100, 100)
                canvas.drawRect(rect, paint)
                canvas.restore()
            }

            fun canvasClipRectWithOperator(canvas: Canvas, paint: Paint, rect: Rect) {
                canvas.save() // OK: can't use operators with extension
                canvas.clipRect(0f, 0f, 100f, 100f, Region.Op.DIFFERENCE)
                canvas.drawRect(rect, paint)
                canvas.restore()
            }

            fun canvasClipPath(canvas: Canvas, paint: Paint, path: Path) {
                canvas.save() // WARN 12
                canvas.clipPath(path)
                canvas.drawPath(path, paint)
                canvas.restore()
            }

            fun canvasClipPathWithOperator(canvas: Canvas, paint: Paint, path: Path) {
                canvas.save() // OK
                canvas.clipPath(path, Region.Op.DIFFERENCE)
                canvas.drawPath(path, paint)
                canvas.restore()
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:13: Warning: Use the KTX extension function Canvas.withTranslation instead? [UseKtx]
            canvas.save() // WARN 1
            ~~~~~~~~~~~~~
        src/test/pkg/test.kt:22: Warning: Use the KTX extension function Canvas.withTranslation instead? [UseKtx]
            val state = canvas.save() // WARN 2
                        ~~~~~~~~~~~~~
        src/test/pkg/test.kt:27: Warning: Use the KTX extension function Canvas.withSave instead? [UseKtx]
            val state2 = canvas.save() // WARN 3
                         ~~~~~~~~~~~~~
        src/test/pkg/test.kt:43: Warning: Use the KTX extension function Canvas.withRotation instead? [UseKtx]
            val rotateCheckpoint = canvas.save() // WARN 4 -- innermost nest is allowed
                                   ~~~~~~~~~~~~~
        src/test/pkg/test.kt:62: Warning: Use the KTX extension function Canvas.withScale instead? [UseKtx]
            canvas.save() // WARN 5
            ~~~~~~~~~~~~~
        src/test/pkg/test.kt:69: Warning: Use the KTX extension function Canvas.withSkew instead? [UseKtx]
            canvas.save() // WARN 6
            ~~~~~~~~~~~~~
        src/test/pkg/test.kt:76: Warning: Use the KTX extension function Canvas.withMatrix instead? [UseKtx]
            canvas.save() // WARN 7
            ~~~~~~~~~~~~~
        src/test/pkg/test.kt:83: Warning: Use the KTX extension function Canvas.withClip instead? [UseKtx]
            canvas.save() // WARN 8
            ~~~~~~~~~~~~~
        src/test/pkg/test.kt:90: Warning: Use the KTX extension function Canvas.withClip instead? [UseKtx]
            canvas.save() // WARN 9
            ~~~~~~~~~~~~~
        src/test/pkg/test.kt:97: Warning: Use the KTX extension function Canvas.withClip instead? [UseKtx]
            canvas.save() // WARN 10
            ~~~~~~~~~~~~~
        src/test/pkg/test.kt:104: Warning: Use the KTX extension function Canvas.withClip instead? [UseKtx]
            canvas.save() // WARN 11
            ~~~~~~~~~~~~~
        src/test/pkg/test.kt:118: Warning: Use the KTX extension function Canvas.withClip instead? [UseKtx]
            canvas.save() // WARN 12
            ~~~~~~~~~~~~~
        0 errors, 12 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/test.kt line 13: Replace with the withTranslation extension function:
        @@ -11 +11
        + import androidx.core.graphics.withTranslation
        @@ -13 +14
        -     canvas.save() // WARN 1
        -     canvas.translate(200f, 300f)
        -     canvas.drawCircle(10f, 10f, 10f, paint)
        -     canvas.restore()
        +     canvas.withTranslation(200f, 300f) { // WARN 1
        +         drawCircle(10f, 10f, 10f, paint)
        +     }
        Autofix for src/test/pkg/test.kt line 22: Replace with the withTranslation extension function:
        @@ -11 +11
        + import androidx.core.graphics.withTranslation
        @@ -22 +23
        -     val state = canvas.save() // WARN 2
        -     canvas.translate(200f, 300f)
        -     canvas.drawCircle(10f, 10f, 10f, paint)
        -     canvas.restoreToCount(state)
        +     canvas.withTranslation(200f, 300f) { // WARN 2
        +         canvas.drawCircle(10f, 10f, 10f, paint)
        +     }
        Autofix for src/test/pkg/test.kt line 27: Replace with the withSave extension function:
        @@ -11 +11
        + import androidx.core.graphics.withSave
        @@ -27 +28
        -     val state2 = canvas.save() // WARN 3
        -     canvas.drawCircle(10f, 10f, 10f, paint)
        -     canvas.restoreToCount(state2)
        +     canvas.withSave() { // WARN 3
        +         canvas.drawCircle(10f, 10f, 10f, paint)
        +     }
        Autofix for src/test/pkg/test.kt line 43: Replace with the withRotation extension function:
        @@ -11 +11
        + import androidx.core.graphics.withRotation
        @@ -43 +44
        -     val rotateCheckpoint = canvas.save() // WARN 4 -- innermost nest is allowed
        -     canvas.rotate(45f)
        -     canvas.restoreToCount(rotateCheckpoint)
        +     canvas.withRotation(45f) { // WARN 4 -- innermost nest is allowed
        +     }
        Autofix for src/test/pkg/test.kt line 62: Replace with the withScale extension function:
        @@ -11 +11
        + import androidx.core.graphics.withScale
        @@ -62 +63
        -     canvas.save() // WARN 5
        -     canvas.scale(1F, 2F)
        -     canvas.drawCircle(10f, 10f, 10f, paint)
        -     canvas.restore()
        +     canvas.withScale(1F, 2F) { // WARN 5
        +         drawCircle(10f, 10f, 10f, paint)
        +     }
        Autofix for src/test/pkg/test.kt line 69: Replace with the withSkew extension function:
        @@ -11 +11
        + import androidx.core.graphics.withSkew
        @@ -69 +70
        -     canvas.save() // WARN 6
        -     canvas.skew(10F, 20F)
        -     canvas.drawCircle(10f, 10f, 10f, paint)
        -     canvas.restore()
        +     canvas.withSkew(10F, 20F) { // WARN 6
        +         drawCircle(10f, 10f, 10f, paint)
        +     }
        Autofix for src/test/pkg/test.kt line 76: Replace with the withMatrix extension function:
        @@ -11 +11
        + import androidx.core.graphics.withMatrix
        @@ -76 +77
        -     canvas.save() // WARN 7
        -     canvas.concat(matrix)
        -     canvas.drawCircle(10f, 10f, 10f, paint)
        -     canvas.restore()
        +     canvas.withMatrix(matrix) { // WARN 7
        +         drawCircle(10f, 10f, 10f, paint)
        +     }
        Autofix for src/test/pkg/test.kt line 83: Replace with the withClip extension function:
        @@ -11 +11
        + import androidx.core.graphics.withClip
        @@ -83 +84
        -     canvas.save() // WARN 8
        -     canvas.clipRect(rect)
        -     canvas.drawRect(rect, paint)
        -     canvas.restore()
        +     canvas.withClip(rect) { // WARN 8
        +         drawRect(rect, paint)
        +     }
        Autofix for src/test/pkg/test.kt line 90: Replace with the withClip extension function:
        @@ -11 +11
        + import androidx.core.graphics.withClip
        @@ -90 +91
        -     canvas.save() // WARN 9
        -     canvas.clipRect(rect)
        -     canvas.drawRect(rect, paint)
        -     canvas.restore()
        +     canvas.withClip(rect) { // WARN 9
        +         drawRect(rect, paint)
        +     }
        Autofix for src/test/pkg/test.kt line 97: Replace with the withClip extension function:
        @@ -11 +11
        + import androidx.core.graphics.withClip
        @@ -97 +98
        -     canvas.save() // WARN 10
        -     canvas.clipRect(0f, 0f, 100f, 100f)
        -     canvas.drawRect(rect, paint)
        -     canvas.restore()
        +     canvas.withClip(0f, 0f, 100f, 100f) { // WARN 10
        +         drawRect(rect, paint)
        +     }
        Autofix for src/test/pkg/test.kt line 104: Replace with the withClip extension function:
        @@ -11 +11
        + import androidx.core.graphics.withClip
        @@ -104 +105
        -     canvas.save() // WARN 11
        -     canvas.clipRect(0, 0, 100, 100)
        -     canvas.drawRect(rect, paint)
        -     canvas.restore()
        +     canvas.withClip(0, 0, 100, 100) { // WARN 11
        +         drawRect(rect, paint)
        +     }
        Autofix for src/test/pkg/test.kt line 118: Replace with the withClip extension function:
        @@ -11 +11
        + import androidx.core.graphics.withClip
        @@ -118 +119
        -     canvas.save() // WARN 12
        -     canvas.clipPath(path)
        -     canvas.drawPath(path, paint)
        -     canvas.restore()
        +     canvas.withClip(path) { // WARN 12
        +         drawPath(path, paint)
        +     }
        """
      )
  }

  fun testCanvasConditionalClose() {
    // Don't suggest closing method if not on the same level as the open
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.graphics.Rect
            import android.graphics.Canvas

            private const val DEBUG: Boolean = true

            fun test(
                viewportClippedRect: Rect,
                viewportBoundsInWindow: Rect,
                canvas: Canvas
            ) {
                canvas.save()
                canvas.translate(
                    -viewportClippedRect.left.toFloat(),
                    -viewportClippedRect.top.toFloat()
                )
                if (DEBUG) {
                    canvas.restore()
                }
            }
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testCanvasComments() {
    // Make sure we attach the comments to the right elements
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.graphics.Rect
            import android.view.View
            import android.graphics.Canvas as AndroidCanvas

            private const val DEBUG: Boolean = true

            fun test(
                viewportClippedRect: Rect,
                viewportBoundsInWindow: Rect,
                composeView: View,
                canvas: AndroidCanvas
            ) {
                if (DEBUG) {
                    canvas.drawDebugBackground()
                }
                canvas.save() // WARN 1
                canvas.translate(
                    -viewportClippedRect.left.toFloat(),
                    -viewportClippedRect.top.toFloat()
                )

                // slide the viewPort over to make it window-relative
                canvas.translate(
                    -viewportBoundsInWindow.left.toFloat(),
                    -viewportBoundsInWindow.top.toFloat()
                )
                // draw the content from the root view (DecorView) including the window background
                composeView.rootView.draw(canvas)

                if (DEBUG) {
                    canvas.drawDebugOverlay()
                }
                canvas.restore()
            }

            private fun AndroidCanvas.drawDebugBackground() {
            }

            private fun AndroidCanvas.drawDebugOverlay() {
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:18: Warning: Use the KTX extension function Canvas.withTranslation instead? [UseKtx]
            canvas.save() // WARN 1
            ~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/test.kt line 18: Replace with the withTranslation extension function:
        @@ -6 +6
        + import androidx.core.graphics.withTranslation
        @@ -18 +19
        -     canvas.save() // WARN 1
        -     canvas.translate(
        +     canvas.withTranslation(
        @@ -22 +22
        -     )
        -
        -     // slide the viewPort over to make it window-relative
        -     canvas.translate(
        -         -viewportBoundsInWindow.left.toFloat(),
        -         -viewportBoundsInWindow.top.toFloat()
        -     )
        -     // draw the content from the root view (DecorView) including the window background
        -     composeView.rootView.draw(canvas)
        +     ) { // WARN 1
        +         // slide the viewPort over to make it window-relative
        +         translate(
        +             -viewportBoundsInWindow.left.toFloat(),
        +             -viewportBoundsInWindow.top.toFloat()
        +         )
        +         // draw the content from the root view (DecorView) including the window background
        +         composeView.rootView.draw(this)
        @@ -32 +31
        -     if (DEBUG) {
        -         canvas.drawDebugOverlay()
        +         if (DEBUG) {
        +             drawDebugOverlay()
        +         }
        @@ -35 +35
        -     canvas.restore()
        """
      )
  }

  fun testSqlDatabase() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.content.Context
            import android.database.sqlite.SQLiteDatabase

            const val VERSION = 1
            const val DATABASE = "name"

            class SqlTest {
                private fun migrate(db: SQLiteDatabase?) {}
                private fun create(db: SQLiteDatabase) {}

                private fun open(context: Context): SQLiteDatabase {
                    val database = context.openOrCreateDatabase(DATABASE, Context.MODE_PRIVATE, null)
                    database.beginTransaction() // WARN 1
                    try {
                        if (database.version > VERSION) {
                            error("Downgrade not supported")
                        }
                        if (database.version < 1) {
                            create(database)
                        } else {
                            migrate(database)
                        }
                        database.version = VERSION
                        database.setTransactionSuccessful()
                    } finally {
                        database.endTransaction()
                    }
                    if (database.version != VERSION) {
                        error("Couldn't upgrade")
                    }
                    return database
                }

                private fun testNonExclusive(database: SQLiteDatabase): SQLiteDatabase {
                    database.beginTransactionNonExclusive() // WARN 2
                    try {
                        create(database)
                        database.setTransactionSuccessful()
                    } finally {
                        database.endTransaction()
                    }
                    return database
                }
            }
            """
          )
          .indented(),
        kotlin(
            """
            package test.pkg

            import android.database.sqlite.SQLiteDatabase

            private fun initDatabase(db: SQLiteDatabase) {
                db.beginTransaction() // WARN 3
                db.execSQL("DROP TABLE IF EXISTS folders")
                db.execSQL(
                    "CREATE TABLE folders (" +
                            "id INTEGER PRIMARY KEY," +
                            "name TEXT, " +
                            "last_updated INTEGER, " +
                            "poll_class TEXT, " +
                            "push_class TEXT, " +
                            "display_class TEXT, " +
                            "notify_class TEXT default 'INHERITED', " +
                            "more_messages TEXT default \"unknown\"" +
                            ")"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS folder_name ON folders (name)")
                db.execSQL("DROP TABLE IF EXISTS messages")
                db.version = 61
                db.setTransactionSuccessful()
                db.endTransaction()
            }
            """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/test/pkg/SqlTest.kt:15: Warning: Use the KTX extension function SQLiteDatabase.transaction instead? [UseKtx]
                database.beginTransaction() // WARN 1
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/SqlTest.kt:37: Warning: Use the KTX extension function SQLiteDatabase.transaction instead? [UseKtx]
                database.beginTransactionNonExclusive() // WARN 2
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:6: Warning: Use the KTX extension function SQLiteDatabase.transaction instead? [UseKtx]
            db.beginTransaction() // WARN 3
            ~~~~~~~~~~~~~~~~~~~~~
        0 errors, 3 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/SqlTest.kt line 15: Replace with the transaction extension function:
        @@ -5 +5
        + import androidx.core.database.sqlite.transaction
        @@ -15 +16
        -         database.beginTransaction() // WARN 1
        -         try {
        -             if (database.version > VERSION) {
        -                 error("Downgrade not supported")
        -             }
        -             if (database.version < 1) {
        -                 create(database)
        -             } else {
        -                 migrate(database)
        +         database.transaction() { // WARN 1
        +             try {
        +                 if (version > VERSION) {
        +                     error("Downgrade not supported")
        +                 }
        +                 if (version < 1) {
        +                     create(this)
        +                 } else {
        +                     migrate(this)
        +                 }
        +                 version = VERSION
        +             } finally {
        @@ -25 +29
        -             database.version = VERSION
        -             database.setTransactionSuccessful()
        -         } finally {
        -             database.endTransaction()
        Autofix for src/test/pkg/SqlTest.kt line 37: Replace with the transaction extension function:
        @@ -5 +5
        + import androidx.core.database.sqlite.transaction
        @@ -37 +38
        -         database.beginTransactionNonExclusive() // WARN 2
        -         try {
        -             create(database)
        -             database.setTransactionSuccessful()
        -         } finally {
        -             database.endTransaction()
        +         database.transaction(exclusive = false) { // WARN 2
        +             try {
        +                 create(this)
        +             } finally {
        +             }
        Autofix for src/test/pkg/test.kt line 6: Replace with the transaction extension function:
        @@ -4 +4
        + import androidx.core.database.sqlite.transaction
        @@ -6 +7
        -     db.beginTransaction() // WARN 3
        -     db.execSQL("DROP TABLE IF EXISTS folders")
        -     db.execSQL(
        -         "CREATE TABLE folders (" +
        -                 "id INTEGER PRIMARY KEY," +
        -                 "name TEXT, " +
        -                 "last_updated INTEGER, " +
        -                 "poll_class TEXT, " +
        -                 "push_class TEXT, " +
        -                 "display_class TEXT, " +
        -                 "notify_class TEXT default 'INHERITED', " +
        -                 "more_messages TEXT default \"unknown\"" +
        -                 ")"
        -     )
        -     db.execSQL("CREATE INDEX IF NOT EXISTS folder_name ON folders (name)")
        -     db.execSQL("DROP TABLE IF EXISTS messages")
        -     db.version = 61
        -     db.setTransactionSuccessful()
        -     db.endTransaction()
        +     db.transaction() { // WARN 3
        +         execSQL("DROP TABLE IF EXISTS folders")
        +         execSQL(
        +             "CREATE TABLE folders (" +
        +                     "id INTEGER PRIMARY KEY," +
        +                     "name TEXT, " +
        +                     "last_updated INTEGER, " +
        +                     "poll_class TEXT, " +
        +                     "push_class TEXT, " +
        +                     "display_class TEXT, " +
        +                     "notify_class TEXT default 'INHERITED', " +
        +                     "more_messages TEXT default \"unknown\"" +
        +                     ")"
        +         )
        +         execSQL("CREATE INDEX IF NOT EXISTS folder_name ON folders (name)")
        +         execSQL("DROP TABLE IF EXISTS messages")
        +         version = 61
        +     }
        """
      )
  }

  fun testDocumentationExampleUseKtx() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg
            import android.text.TextUtils

            fun test() {
              val html = TextUtils.htmlEncode("Is x > y ?")
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:5: Warning: Use the KTX extension function String.htmlEncode instead? [UseKtx]
          val html = TextUtils.htmlEncode("Is x > y ?")
                     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/test.kt line 5: Replace with the htmlEncode extension function:
        @@ -3 +3
        + import androidx.core.text.htmlEncode
        @@ -5 +6
        -   val html = TextUtils.htmlEncode("Is x > y ?")
        +   val html = "Is x > y ?".htmlEncode()
        """
      )
  }

  fun testHtmlCompat() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg
            import android.text.Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE.*
            import android.text.Spanned
            import android.text.TextUtils
            import androidx.core.text.HtmlCompat

            fun test(spanned: Spanned) {
                val html1 = HtmlCompat.toHtml(spanned, TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)
                val html2 = HtmlCompat.toHtml(spanned, TO_HTML_PARAGRAPH_LINES_INDIVIDUAL)
            }
            """
          )
          .indented(),
        java(
            """
            // Stub
            /* HIDE-FROM-DOCUMENTATION */
            package androidx.core.text;
            import android.text.Spanned;
            public final class HtmlCompat {
              public static final int TO_HTML_PARAGRAPH_LINES_CONSECUTIVE = 0;
              public static final int TO_HTML_PARAGRAPH_LINES_INDIVIDUAL = 1;
              public static String toHtml(Spanned text, int options) { return null; }
            }
            """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:8: Warning: Use the KTX extension function Spanned.toHtml instead? [UseKtx]
            val html1 = HtmlCompat.toHtml(spanned, TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)
                        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:9: Warning: Use the KTX extension function Spanned.toHtml instead? [UseKtx]
            val html2 = HtmlCompat.toHtml(spanned, TO_HTML_PARAGRAPH_LINES_INDIVIDUAL)
                        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 2 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/test.kt line 8: Replace with the toHtml extension function:
        @@ -6 +6
        + import androidx.core.text.toHtml
        @@ -8 +9
        -     val html1 = HtmlCompat.toHtml(spanned, TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)
        +     val html1 = spanned.toHtml()
        Autofix for src/test/pkg/test.kt line 9: Replace with the toHtml extension function:
        @@ -6 +6
        + import androidx.core.text.toHtml
        @@ -9 +10
        -     val html2 = HtmlCompat.toHtml(spanned, TO_HTML_PARAGRAPH_LINES_INDIVIDUAL)
        +     val html2 = spanned.toHtml(TO_HTML_PARAGRAPH_LINES_INDIVIDUAL)
        """
      )
  }

  fun testAlreadyImported() {
    // Make sure that if we have already imported the method, that
    // symbol isn't taken as a conflict
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.content.Context
            import android.util.AttributeSet
            import androidx.core.content.withStyledAttributes

            fun testUsingApply1(context: Context, style: Int, attrs: IntArray, set: AttributeSet) {
                context.withStyledAttributes(style, attrs) {
                    apply {
                        var color = getDrawable(0)
                    }
                }
            }

            fun testUsingApply2(context: Context, style: Int, attrs: IntArray, set: AttributeSet) {
                context.obtainStyledAttributes(attrs).apply { // WARN 1
                    var color = getDrawable(0)
                }.recycle()
            }
            """
          )
          .indented(),
        // Stub
        kotlin(
            """
            // HIDE-FROM-DOCUMENTATION
            package androidx.core.content

            import android.content.Context
            import android.content.res.TypedArray
            import android.util.AttributeSet

            public inline fun Context.withStyledAttributes(
                set: AttributeSet? = null, attrs: IntArray,
                defStyleAttr: Int = 0, defStyleRes: Int = 0,
                block: TypedArray.() -> Unit
            ) {
                obtainStyledAttributes(set, attrs, defStyleAttr, defStyleRes).apply(block).recycle()
            }
            public inline fun Context.withStyledAttributes(resourceId: Int, attrs: IntArray, block: TypedArray.() -> Unit) {
                obtainStyledAttributes(resourceId, attrs).apply(block).recycle()
            }
          """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:16: Warning: Use the KTX extension function Context.withStyledAttributes instead? [UseKtx]
            context.obtainStyledAttributes(attrs).apply { // WARN 1
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
  }

  fun testConflictingImport() {
    // Make sure that stdlib functions which are not in implicitly imported packages
    // include explicit imports in the quickfix
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.app.Activity
            import android.content.Context
            import android.graphics.Color
            import android.view.Menu
            import some.pkg.link as edit // prevents SharedPrefs suggestion
            import android.content.SharedPreferences

            // Conflict: prevents withStyledAttributes obtainStyledAttribute suggestion
            fun withStyledAttributes() = "conflict"

            fun testSharedPref(sharedPreferences: SharedPreferences, value: Boolean) {
                sharedPreferences.edit() // OK
                    .putBoolean("key", value)
                    .apply()
            }

            class MyActivity : Activity() {
                fun test(menu: Menu, id: Int) {
                    val drawableAttr = intArrayOf()
                    val typedArray = obtainStyledAttributes(drawableAttr) // OK
                    menu.findItem(id).icon = typedArray.getDrawable(0)
                    println(typedArray)
                    typedArray.recycle()
                }
            }
            """
          )
          .indented(),
        kotlin(
            """
            package some.pkg
            fun isReadable(s: String) = s.length > 3
            fun link() { }
          """
          )
          .indented(),
      )
      .run()
      .expectClean()
  }

  fun testConflictingImport3() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.view.View

            fun View.show() {
                this.visibility = View.VISIBLE
            }
            // Note -- the equivalent extensions are properties, not functions
            fun View.isVisible() = visibility == View.VISIBLE
            fun View.isInvisible() = visibility == View.INVISIBLE
            fun View.isHidden() = visibility == View.GONE
            """
          )
          .indented()
      )
      .allowDuplicates()
      .run()
      .expect(
        """
        src/test/pkg/test.kt:11: Warning: Use the KTX extension property View.isGone instead? [UseKtx]
        fun View.isHidden() = visibility == View.GONE
                              ~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
  }

  fun testContainsIdentifier() {
    assertTrue("".containsIdentifier(""))
    assertTrue("test".containsIdentifier("test"))
    assertTrue("foo bar baz".containsIdentifier("foo"))
    assertTrue("foo bar baz".containsIdentifier("bar"))
    assertTrue("foo bar baz".containsIdentifier("baz"))
    assertFalse("".containsIdentifier("test"))
    assertFalse("test".containsIdentifier("tst"))
    assertFalse("foo bar baz".containsIdentifier("ba"))
    assertFalse("foo bar baz".containsIdentifier("fo"))
    assertFalse("foo bar baz".containsIdentifier("az"))
  }

  fun testMultipleSuggestions() {
    // Make sure that if we have multiple violations, and we apply
    // one fix, we continue to get additional warnings (e.g. that
    // the conflicting-symbol checker doesn't conclude we already
    // have a conflicting "toUri" definition that prevents the import)
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import androidx.core.net.toUri

            fun test(url: String) {
                url.toUri()
                android.net.Uri.parse(url)
            }
            """
          )
          .indented(),
        // AndroidX stub
        kotlin(
          """
          package androidx.core.net
          import android.net.Uri
          inline fun String.toUri(): Uri = Uri.parse(this)
          """
        ),
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:7: Warning: Use the KTX extension function String.toUri instead? [UseKtx]
            android.net.Uri.parse(url)
            ~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/test.kt line 7: Replace with the toUri extension function:
        @@ -7 +7
        -     android.net.Uri.parse(url)
        +     url.toUri()
        """
      )
  }

  fun testTextUtils() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg
            import android.text.TextUtils

            fun test() {
              val html = TextUtils.htmlEncode("Is x > y ?")
              val digits = TextUtils.isDigitsOnly(html)
              val length = android.text.TextUtils.getTrimmedLength(html)
              TextUtils.isDigitsOnly(html).not()
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:5: Warning: Use the KTX extension function String.htmlEncode instead? [UseKtx]
          val html = TextUtils.htmlEncode("Is x > y ?")
                     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:6: Warning: Use the KTX extension function CharSequence.isDigitsOnly instead? [UseKtx]
          val digits = TextUtils.isDigitsOnly(html)
                       ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:7: Warning: Use the KTX extension function CharSequence.trimmedLength instead? [UseKtx]
          val length = android.text.TextUtils.getTrimmedLength(html)
                       ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:8: Warning: Use the KTX extension function CharSequence.isDigitsOnly instead? [UseKtx]
          TextUtils.isDigitsOnly(html).not()
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 4 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/test.kt line 5: Replace with the htmlEncode extension function:
        @@ -3 +3
        + import androidx.core.text.htmlEncode
        @@ -5 +6
        -   val html = TextUtils.htmlEncode("Is x > y ?")
        +   val html = "Is x > y ?".htmlEncode()
        Autofix for src/test/pkg/test.kt line 6: Replace with the isDigitsOnly extension function:
        @@ -3 +3
        + import androidx.core.text.isDigitsOnly
        @@ -6 +7
        -   val digits = TextUtils.isDigitsOnly(html)
        +   val digits = html.isDigitsOnly()
        Autofix for src/test/pkg/test.kt line 7: Replace with the trimmedLength extension function:
        @@ -3 +3
        + import androidx.core.text.trimmedLength
        @@ -7 +8
        -   val length = android.text.TextUtils.getTrimmedLength(html)
        +   val length = html.trimmedLength()
        Autofix for src/test/pkg/test.kt line 8: Replace with the isDigitsOnly extension function:
        @@ -3 +3
        + import androidx.core.text.isDigitsOnly
        @@ -8 +9
        -   TextUtils.isDigitsOnly(html).not()
        +   html.isDigitsOnly().not()
        """
      )
  }

  fun testStaticImport() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg
            import android.text.TextUtils.getTrimmedLength

            fun test() {
              val length = getTrimmedLength("123")
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:5: Warning: Use the KTX extension function CharSequence.trimmedLength instead? [UseKtx]
          val length = getTrimmedLength("123")
                       ~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/test.kt line 5: Replace with the trimmedLength extension function:
        @@ -3 +3
        + import androidx.core.text.trimmedLength
        @@ -5 +6
        -   val length = getTrimmedLength("123")
        +   val length = "123".trimmedLength()
        """
      )
  }

  fun testHtml() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg
            import androidx.core.text.HtmlCompat
            import androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
            import android.text.Html.ImageGetter
            import android.text.Html.TagHandler

            fun test(imageGetter: ImageGetter, tagHandler: TagHandler) {
                HtmlCompat.fromHtml("<html>", HtmlCompat.FROM_HTML_MODE_COMPACT, imageGetter, tagHandler) // WARN 1
                HtmlCompat.fromHtml("<html>", HtmlCompat.FROM_HTML_MODE_COMPACT, imageGetter, null) // WARN 2
                androidx.core.text.HtmlCompat.fromHtml("<html>", HtmlCompat.FROM_HTML_MODE_COMPACT, null, null) // WARN 3
                HtmlCompat.fromHtml("<html>", HtmlCompat.FROM_HTML_MODE_LEGACY, null, null)  // WARN 4
                HtmlCompat.fromHtml("<html>", FROM_HTML_MODE_LEGACY, null, null) // WARN 5
            }
            """
          )
          .indented(),
        // AndroidX stub
        kotlin(
            """
            // HIDE-FROM-DOCUMENTATION
            @file:Suppress("UseKtx") // Stub, don't flag code below
            package androidx.core.text
            import android.text.Html
            import android.text.Spanned
            import androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY

            inline fun String.parseAsHtml(
                flags: Int = FROM_HTML_MODE_LEGACY,
                imageGetter: Html.ImageGetter? = null,
                tagHandler: Html.TagHandler? = null
            ): Spanned = HtmlCompat.fromHtml(this, flags, imageGetter, tagHandler)
            """
          )
          .indented(),
        java(
            """
            // HIDE-FROM-DOCUMENTATION
            package androidx.core.text;
            import android.text.Html;
            import android.text.Html.ImageGetter;
            import android.text.Html.TagHandler;
            import android.text.Spanned;
            public class HtmlCompat {
              public static final int FROM_HTML_MODE_LEGACY = Html.FROM_HTML_MODE_LEGACY;
              public static final int FROM_HTML_MODE_COMPACT = Html.FROM_HTML_MODE_COMPACT;
              public static Spanned fromHtml(String source, int flags) {
                return null;
              }
              public static Spanned fromHtml(String source, int flags,
                  ImageGetter imageGetter, TagHandler tagHandler) {
                return null;
              }
              public static String toHtml(Spanned text, int options) {
                return null;
              }
            }
            """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:8: Warning: Use the KTX extension function String.parseAsHtml instead? [UseKtx]
            HtmlCompat.fromHtml("<html>", HtmlCompat.FROM_HTML_MODE_COMPACT, imageGetter, tagHandler) // WARN 1
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:9: Warning: Use the KTX extension function String.parseAsHtml instead? [UseKtx]
            HtmlCompat.fromHtml("<html>", HtmlCompat.FROM_HTML_MODE_COMPACT, imageGetter, null) // WARN 2
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:10: Warning: Use the KTX extension function String.parseAsHtml instead? [UseKtx]
            androidx.core.text.HtmlCompat.fromHtml("<html>", HtmlCompat.FROM_HTML_MODE_COMPACT, null, null) // WARN 3
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:11: Warning: Use the KTX extension function String.parseAsHtml instead? [UseKtx]
            HtmlCompat.fromHtml("<html>", HtmlCompat.FROM_HTML_MODE_LEGACY, null, null)  // WARN 4
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:12: Warning: Use the KTX extension function String.parseAsHtml instead? [UseKtx]
            HtmlCompat.fromHtml("<html>", FROM_HTML_MODE_LEGACY, null, null) // WARN 5
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 5 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/test.kt line 8: Replace with the parseAsHtml extension function:
        @@ -6 +6
        + import androidx.core.text.parseAsHtml
        @@ -8 +9
        -     HtmlCompat.fromHtml("<html>", HtmlCompat.FROM_HTML_MODE_COMPACT, imageGetter, tagHandler) // WARN 1
        +     "<html>".parseAsHtml(HtmlCompat.FROM_HTML_MODE_COMPACT, imageGetter, tagHandler) // WARN 1
        Autofix for src/test/pkg/test.kt line 9: Replace with the parseAsHtml extension function:
        @@ -6 +6
        + import androidx.core.text.parseAsHtml
        @@ -9 +10
        -     HtmlCompat.fromHtml("<html>", HtmlCompat.FROM_HTML_MODE_COMPACT, imageGetter, null) // WARN 2
        +     "<html>".parseAsHtml(HtmlCompat.FROM_HTML_MODE_COMPACT, imageGetter) // WARN 2
        Autofix for src/test/pkg/test.kt line 10: Replace with the parseAsHtml extension function:
        @@ -6 +6
        + import androidx.core.text.parseAsHtml
        @@ -10 +11
        -     androidx.core.text.HtmlCompat.fromHtml("<html>", HtmlCompat.FROM_HTML_MODE_COMPACT, null, null) // WARN 3
        +     "<html>".parseAsHtml(HtmlCompat.FROM_HTML_MODE_COMPACT) // WARN 3
        Autofix for src/test/pkg/test.kt line 11: Replace with the parseAsHtml extension function:
        @@ -6 +6
        + import androidx.core.text.parseAsHtml
        @@ -11 +12
        -     HtmlCompat.fromHtml("<html>", HtmlCompat.FROM_HTML_MODE_LEGACY, null, null)  // WARN 4
        +     "<html>".parseAsHtml()  // WARN 4
        Autofix for src/test/pkg/test.kt line 12: Replace with the parseAsHtml extension function:
        @@ -6 +6
        + import androidx.core.text.parseAsHtml
        @@ -12 +13
        -     HtmlCompat.fromHtml("<html>", FROM_HTML_MODE_LEGACY, null, null) // WARN 5
        +     "<html>".parseAsHtml() // WARN 5
        """
      )
  }

  fun testParensNeeded() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.content.Intent
            import android.net.Uri

            fun Intent.makeUnique(): Intent {
                return setData((Uri.parse("custom://" + System.currentTimeMillis())))
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:7: Warning: Use the KTX extension function String.toUri instead? [UseKtx]
            return setData((Uri.parse("custom://" + System.currentTimeMillis())))
                            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/test.kt line 7: Replace with the toUri extension function:
        @@ -5 +5
        + import androidx.core.net.toUri
        @@ -7 +8
        -     return setData((Uri.parse("custom://" + System.currentTimeMillis())))
        +     return setData((("custom://" + System.currentTimeMillis()).toUri()))
        """
      )
  }

  fun testBitmap() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.graphics.Bitmap

            private fun snapshotMessage(
                videoBitmap: Bitmap?,
                originalScale: Float
            ) {
                var scaledVideoBitmap = videoBitmap
                if (videoBitmap != null) {
                    scaledVideoBitmap = Bitmap.createScaledBitmap(
                        videoBitmap,
                        (videoBitmap.width / originalScale).toInt(),
                        (videoBitmap.height / originalScale).toInt(),
                        true
                    )
                }
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:11: Warning: Use the KTX extension function Bitmap.scale instead? [UseKtx]
                scaledVideoBitmap = Bitmap.createScaledBitmap(
                                    ^
        0 errors, 1 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/test.kt line 11: Replace with the scale extension function:
        @@ -4 +4
        + import androidx.core.graphics.scale
        @@ -11 +12
        -         scaledVideoBitmap = Bitmap.createScaledBitmap(
        -             videoBitmap,
        -             (videoBitmap.width / originalScale).toInt(),
        -             (videoBitmap.height / originalScale).toInt(),
        -             true
        -         )
        +         scaledVideoBitmap = videoBitmap.scale((videoBitmap.width / originalScale).toInt(), (videoBitmap.height / originalScale).toInt())
        """
      )
  }

  fun testBinaryExpressions() {
    lint()
      .files(
        kotlin(
            """
            @file:Suppress("UnusedEquals")

            package test.pkg

            import android.util.SparseArray
            import android.util.SparseLongArray

            fun testSparseArray(array: SparseArray<String>, longArray: SparseLongArray) {
                array.indexOfValue("test") >= 0 // WARN 1: expect array.containsValue("test")
                array.indexOfValue("test") != -1 // WARN 2: expect array.containsValue("test")
                array.indexOfValue("test") == 0 // OK
                array.indexOfValue("test") <= 0 // OK
                longArray.indexOfValue(0L) >= 0 // WARN 3

                val isEmpty = array.size() == 0 // WARN 4: expect array.isEmpty
                val isNotEmpty = array.size() > 0 // WARN 5: expect array.isNotEmpty
                val isNotEmpty = array.size() != 0 // WARN 6: expect array.isNotEmpty
                val isEmpty2 = longArray.size() == 0 // WARN 7: expect longArray.isEmpty

                val arraySize = array.size() // WARN 8: switch to property syntax
                val arraySize = array.size() == 5 // WARN 9: switch to property syntax
            }
            """
          )
          .indented(),
        kotlin(
            """
            package test.pkg
            import android.view.View
            import android.view.ViewGroup

            fun viewGroupTest(group: ViewGroup, child: View) {
                val contains = group.indexOfChild(child) != -1 // WARN 10
                val contains2 = group.indexOfChild(child) >= 0 // WARN 11
                val count = group.getChildCount() == 0 // WARN 12
                val empty = group.childCount == 0 // WARN 13
                val notEmpty = group.childCount != 0 // WARN 14
                // We're not currently looking up property aliases
                val count2 = group.childCount // WARN 15 -- but not yet working
            }
            """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:9: Warning: Use the KTX extension function SparseArray.containsValue instead? [UseKtx]
            array.indexOfValue("test") >= 0 // WARN 1: expect array.containsValue("test")
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:10: Warning: Use the KTX extension function SparseArray.containsValue instead? [UseKtx]
            array.indexOfValue("test") != -1 // WARN 2: expect array.containsValue("test")
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:13: Warning: Use the KTX extension function SparseLongArray.containsValue instead? [UseKtx]
            longArray.indexOfValue(0L) >= 0 // WARN 3
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:15: Warning: Use the KTX extension function SparseArray.isEmpty instead? [UseKtx]
            val isEmpty = array.size() == 0 // WARN 4: expect array.isEmpty
                          ~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:16: Warning: Use the KTX extension function SparseArray.isNotEmpty instead? [UseKtx]
            val isNotEmpty = array.size() > 0 // WARN 5: expect array.isNotEmpty
                             ~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:17: Warning: Use the KTX extension function SparseArray.isNotEmpty instead? [UseKtx]
            val isNotEmpty = array.size() != 0 // WARN 6: expect array.isNotEmpty
                             ~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:18: Warning: Use the KTX extension function SparseLongArray.isEmpty instead? [UseKtx]
            val isEmpty2 = longArray.size() == 0 // WARN 7: expect longArray.isEmpty
                           ~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:20: Warning: Use the KTX extension property SparseArray.size instead? [UseKtx]
            val arraySize = array.size() // WARN 8: switch to property syntax
                            ~~~~~~~~~~~~
        src/test/pkg/test.kt:21: Warning: Use the KTX extension property SparseArray.size instead? [UseKtx]
            val arraySize = array.size() == 5 // WARN 9: switch to property syntax
                            ~~~~~~~~~~~~
        src/test/pkg/test2.kt:6: Warning: Use the KTX extension function ViewGroup.contains instead? [UseKtx]
            val contains = group.indexOfChild(child) != -1 // WARN 10
                           ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test2.kt:7: Warning: Use the KTX extension function ViewGroup.contains instead? [UseKtx]
            val contains2 = group.indexOfChild(child) >= 0 // WARN 11
                            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test2.kt:8: Warning: Use the KTX extension function ViewGroup.isEmpty instead? [UseKtx]
            val count = group.getChildCount() == 0 // WARN 12
                        ~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test2.kt:9: Warning: Use the KTX extension function ViewGroup.isEmpty instead? [UseKtx]
            val empty = group.childCount == 0 // WARN 13
                        ~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test2.kt:10: Warning: Use the KTX extension function ViewGroup.isNotEmpty instead? [UseKtx]
            val notEmpty = group.childCount != 0 // WARN 14
                           ~~~~~~~~~~~~~~~~~~~~~
        0 errors, 14 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/test.kt line 9: Replace with the containsValue extension function:
        @@ -7 +7
        + import androidx.core.util.containsValue
        @@ -9 +10
        -     array.indexOfValue("test") >= 0 // WARN 1: expect array.containsValue("test")
        +     array.containsValue("test") // WARN 1: expect array.containsValue("test")
        Autofix for src/test/pkg/test.kt line 10: Replace with the containsValue extension function:
        @@ -7 +7
        + import androidx.core.util.containsValue
        @@ -10 +11
        -     array.indexOfValue("test") != -1 // WARN 2: expect array.containsValue("test")
        +     array.containsValue("test") // WARN 2: expect array.containsValue("test")
        Autofix for src/test/pkg/test.kt line 13: Replace with the containsValue extension function:
        @@ -7 +7
        + import androidx.core.util.containsValue
        @@ -13 +14
        -     longArray.indexOfValue(0L) >= 0 // WARN 3
        +     longArray.containsValue(0L) // WARN 3
        Autofix for src/test/pkg/test.kt line 15: Replace with the isEmpty extension function:
        @@ -7 +7
        + import androidx.core.util.isEmpty
        @@ -15 +16
        -     val isEmpty = array.size() == 0 // WARN 4: expect array.isEmpty
        +     val isEmpty = array.isEmpty() // WARN 4: expect array.isEmpty
        Autofix for src/test/pkg/test.kt line 16: Replace with the isNotEmpty extension function:
        @@ -7 +7
        + import androidx.core.util.isNotEmpty
        @@ -16 +17
        -     val isNotEmpty = array.size() > 0 // WARN 5: expect array.isNotEmpty
        +     val isNotEmpty = array.isNotEmpty() // WARN 5: expect array.isNotEmpty
        Autofix for src/test/pkg/test.kt line 17: Replace with the isNotEmpty extension function:
        @@ -7 +7
        + import androidx.core.util.isNotEmpty
        @@ -17 +18
        -     val isNotEmpty = array.size() != 0 // WARN 6: expect array.isNotEmpty
        +     val isNotEmpty = array.isNotEmpty() // WARN 6: expect array.isNotEmpty
        Autofix for src/test/pkg/test.kt line 18: Replace with the isEmpty extension function:
        @@ -7 +7
        + import androidx.core.util.isEmpty
        @@ -18 +19
        -     val isEmpty2 = longArray.size() == 0 // WARN 7: expect longArray.isEmpty
        +     val isEmpty2 = longArray.isEmpty() // WARN 7: expect longArray.isEmpty
        Autofix for src/test/pkg/test.kt line 20: Replace with the size extension property:
        @@ -7 +7
        + import androidx.core.util.size
        @@ -20 +21
        -     val arraySize = array.size() // WARN 8: switch to property syntax
        +     val arraySize = array.size // WARN 8: switch to property syntax
        Autofix for src/test/pkg/test.kt line 21: Replace with the size extension property:
        @@ -7 +7
        + import androidx.core.util.size
        @@ -21 +22
        -     val arraySize = array.size() == 5 // WARN 9: switch to property syntax
        +     val arraySize = array.size == 5 // WARN 9: switch to property syntax
        Autofix for src/test/pkg/test2.kt line 6: Replace with the contains extension function:
        @@ -4 +4
        + import androidx.core.view.contains
        @@ -6 +7
        -     val contains = group.indexOfChild(child) != -1 // WARN 10
        +     val contains = group.contains(child) // WARN 10
        Autofix for src/test/pkg/test2.kt line 7: Replace with the contains extension function:
        @@ -4 +4
        + import androidx.core.view.contains
        @@ -7 +8
        -     val contains2 = group.indexOfChild(child) >= 0 // WARN 11
        +     val contains2 = group.contains(child) // WARN 11
        Autofix for src/test/pkg/test2.kt line 8: Replace with the isEmpty extension function:
        @@ -4 +4
        + import androidx.core.view.isEmpty
        @@ -8 +9
        -     val count = group.getChildCount() == 0 // WARN 12
        +     val count = group.isEmpty() // WARN 12
        Autofix for src/test/pkg/test2.kt line 9: Replace with the isEmpty extension function:
        @@ -4 +4
        + import androidx.core.view.isEmpty
        @@ -9 +10
        -     val empty = group.childCount == 0 // WARN 13
        +     val empty = group.isEmpty() // WARN 13
        Autofix for src/test/pkg/test2.kt line 10: Replace with the isNotEmpty extension function:
        @@ -4 +4
        + import androidx.core.view.isNotEmpty
        @@ -10 +11
        -     val notEmpty = group.childCount != 0 // WARN 14
        +     val notEmpty = group.isNotEmpty() // WARN 14
        """
      )
  }

  fun testArrays() {
    lint()
      .files(
        kotlin(
            """
            @file:Suppress("UnusedEquals")

            package test.pkg

            import android.util.LongSparseArray
            import android.util.SparseBooleanArray
            import android.util.SparseIntArray
            import android.util.SparseLongArray
            import android.view.Menu

            // SparseArray is tested in testBinaryExpressions

            fun testLongArray(longArray: SparseLongArray) {
                longArray.size() // WARN 1
                longArray.size() == 0 // WARN 2
                longArray.size() != 0 // WARN 3
                longArray.indexOfValue(0L) >= 0 // WARN 4
            }

            fun testBooleanArray(
                booleanArray: SparseBooleanArray,
            ) {
                booleanArray.size() // WARN 5
                booleanArray.size() == 0 // WARN 6
                booleanArray.size() != 0 // WARN 7
                booleanArray.indexOfValue(true) != -1 // WARN 8
            }

            fun testIntArray(
                intArray: SparseIntArray,
            ) {
                intArray.size() // WARN 9
                intArray.size() == 0 // WARN 10
                intArray.size() != 0 // WARN 11
                intArray.indexOfValue(42) >= 0 // WARN 12
            }

            fun testMenu(menu: Menu) {
                menu.size() // WARN 13
                menu.size() == 0 // WARN 14
                menu.size() != 0 // WARN 15
            }

            fun testLongSparseArray(longArray: LongSparseArray<String>) {
                longArray.size() // WARN 16
                longArray.size() == 0 // WARN 17
                longArray.size() != 0 // WARN 18
                longArray.indexOfValue("Test") >= 0 // WARN 19
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:14: Warning: Use the KTX extension property SparseLongArray.size instead? [UseKtx]
            longArray.size() // WARN 1
            ~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:15: Warning: Use the KTX extension function SparseLongArray.isEmpty instead? [UseKtx]
            longArray.size() == 0 // WARN 2
            ~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:16: Warning: Use the KTX extension function SparseLongArray.isNotEmpty instead? [UseKtx]
            longArray.size() != 0 // WARN 3
            ~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:17: Warning: Use the KTX extension function SparseLongArray.containsValue instead? [UseKtx]
            longArray.indexOfValue(0L) >= 0 // WARN 4
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:23: Warning: Use the KTX extension property SparseBooleanArray.size instead? [UseKtx]
            booleanArray.size() // WARN 5
            ~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:24: Warning: Use the KTX extension function SparseBooleanArray.isEmpty instead? [UseKtx]
            booleanArray.size() == 0 // WARN 6
            ~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:25: Warning: Use the KTX extension function SparseBooleanArray.isNotEmpty instead? [UseKtx]
            booleanArray.size() != 0 // WARN 7
            ~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:26: Warning: Use the KTX extension function SparseBooleanArray.containsValue instead? [UseKtx]
            booleanArray.indexOfValue(true) != -1 // WARN 8
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:32: Warning: Use the KTX extension property SparseIntArray.size instead? [UseKtx]
            intArray.size() // WARN 9
            ~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:33: Warning: Use the KTX extension function SparseIntArray.isEmpty instead? [UseKtx]
            intArray.size() == 0 // WARN 10
            ~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:34: Warning: Use the KTX extension function SparseIntArray.isNotEmpty instead? [UseKtx]
            intArray.size() != 0 // WARN 11
            ~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:35: Warning: Use the KTX extension function SparseIntArray.containsValue instead? [UseKtx]
            intArray.indexOfValue(42) >= 0 // WARN 12
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:39: Warning: Use the KTX extension property Menu.size instead? [UseKtx]
            menu.size() // WARN 13
            ~~~~~~~~~~~
        src/test/pkg/test.kt:40: Warning: Use the KTX extension function Menu.isEmpty instead? [UseKtx]
            menu.size() == 0 // WARN 14
            ~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:41: Warning: Use the KTX extension function Menu.isNotEmpty instead? [UseKtx]
            menu.size() != 0 // WARN 15
            ~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:45: Warning: Use the KTX extension property LongSparseArray.size instead? [UseKtx]
            longArray.size() // WARN 16
            ~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:46: Warning: Use the KTX extension function LongSparseArray.isEmpty instead? [UseKtx]
            longArray.size() == 0 // WARN 17
            ~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:47: Warning: Use the KTX extension function LongSparseArray.isNotEmpty instead? [UseKtx]
            longArray.size() != 0 // WARN 18
            ~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:48: Warning: Use the KTX extension function LongSparseArray.containsValue instead? [UseKtx]
            longArray.indexOfValue("Test") >= 0 // WARN 19
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 19 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/test.kt line 14: Replace with the size extension property:
        @@ -10 +10
        + import androidx.core.util.size
        @@ -14 +15
        -     longArray.size() // WARN 1
        +     longArray.size // WARN 1
        Autofix for src/test/pkg/test.kt line 15: Replace with the isEmpty extension function:
        @@ -10 +10
        + import androidx.core.util.isEmpty
        @@ -15 +16
        -     longArray.size() == 0 // WARN 2
        +     longArray.isEmpty() // WARN 2
        Autofix for src/test/pkg/test.kt line 16: Replace with the isNotEmpty extension function:
        @@ -10 +10
        + import androidx.core.util.isNotEmpty
        @@ -16 +17
        -     longArray.size() != 0 // WARN 3
        +     longArray.isNotEmpty() // WARN 3
        Autofix for src/test/pkg/test.kt line 17: Replace with the containsValue extension function:
        @@ -10 +10
        + import androidx.core.util.containsValue
        @@ -17 +18
        -     longArray.indexOfValue(0L) >= 0 // WARN 4
        +     longArray.containsValue(0L) // WARN 4
        Autofix for src/test/pkg/test.kt line 23: Replace with the size extension property:
        @@ -10 +10
        + import androidx.core.util.size
        @@ -23 +24
        -     booleanArray.size() // WARN 5
        +     booleanArray.size // WARN 5
        Autofix for src/test/pkg/test.kt line 24: Replace with the isEmpty extension function:
        @@ -10 +10
        + import androidx.core.util.isEmpty
        @@ -24 +25
        -     booleanArray.size() == 0 // WARN 6
        +     booleanArray.isEmpty() // WARN 6
        Autofix for src/test/pkg/test.kt line 25: Replace with the isNotEmpty extension function:
        @@ -10 +10
        + import androidx.core.util.isNotEmpty
        @@ -25 +26
        -     booleanArray.size() != 0 // WARN 7
        +     booleanArray.isNotEmpty() // WARN 7
        Autofix for src/test/pkg/test.kt line 26: Replace with the containsValue extension function:
        @@ -10 +10
        + import androidx.core.util.containsValue
        @@ -26 +27
        -     booleanArray.indexOfValue(true) != -1 // WARN 8
        +     booleanArray.containsValue(true) // WARN 8
        Autofix for src/test/pkg/test.kt line 32: Replace with the size extension property:
        @@ -10 +10
        + import androidx.core.util.size
        @@ -32 +33
        -     intArray.size() // WARN 9
        +     intArray.size // WARN 9
        Autofix for src/test/pkg/test.kt line 33: Replace with the isEmpty extension function:
        @@ -10 +10
        + import androidx.core.util.isEmpty
        @@ -33 +34
        -     intArray.size() == 0 // WARN 10
        +     intArray.isEmpty() // WARN 10
        Autofix for src/test/pkg/test.kt line 34: Replace with the isNotEmpty extension function:
        @@ -10 +10
        + import androidx.core.util.isNotEmpty
        @@ -34 +35
        -     intArray.size() != 0 // WARN 11
        +     intArray.isNotEmpty() // WARN 11
        Autofix for src/test/pkg/test.kt line 35: Replace with the containsValue extension function:
        @@ -10 +10
        + import androidx.core.util.containsValue
        @@ -35 +36
        -     intArray.indexOfValue(42) >= 0 // WARN 12
        +     intArray.containsValue(42) // WARN 12
        Autofix for src/test/pkg/test.kt line 39: Replace with the size extension property:
        @@ -10 +10
        + import androidx.core.view.size
        @@ -39 +40
        -     menu.size() // WARN 13
        +     menu.size // WARN 13
        Autofix for src/test/pkg/test.kt line 40: Replace with the isEmpty extension function:
        @@ -10 +10
        + import androidx.core.view.isEmpty
        @@ -40 +41
        -     menu.size() == 0 // WARN 14
        +     menu.isEmpty() // WARN 14
        Autofix for src/test/pkg/test.kt line 41: Replace with the isNotEmpty extension function:
        @@ -10 +10
        + import androidx.core.view.isNotEmpty
        @@ -41 +42
        -     menu.size() != 0 // WARN 15
        +     menu.isNotEmpty() // WARN 15
        Autofix for src/test/pkg/test.kt line 45: Replace with the size extension property:
        @@ -10 +10
        + import androidx.core.util.size
        @@ -45 +46
        -     longArray.size() // WARN 16
        +     longArray.size // WARN 16
        Autofix for src/test/pkg/test.kt line 46: Replace with the isEmpty extension function:
        @@ -10 +10
        + import androidx.core.util.isEmpty
        @@ -46 +47
        -     longArray.size() == 0 // WARN 17
        +     longArray.isEmpty() // WARN 17
        Autofix for src/test/pkg/test.kt line 47: Replace with the isNotEmpty extension function:
        @@ -10 +10
        + import androidx.core.util.isNotEmpty
        @@ -47 +48
        -     longArray.size() != 0 // WARN 18
        +     longArray.isNotEmpty() // WARN 18
        Autofix for src/test/pkg/test.kt line 48: Replace with the containsValue extension function:
        @@ -10 +10
        + import androidx.core.util.containsValue
        @@ -48 +49
        -     longArray.indexOfValue("Test") >= 0 // WARN 19
        +     longArray.containsValue("Test") // WARN 19
        """
      )
  }

  fun testProperties() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.text.TextUtils
            import android.text.TextUtils.getLayoutDirectionFromLocale
            import android.view.View
            import java.util.Locale

            fun localeTest() {
                if (TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) == View.LAYOUT_DIRECTION_LTR) { // WARN 1
                }
                val dir = getLayoutDirectionFromLocale(Locale.getDefault()) // WARN 2
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:9: Warning: Use the KTX extension property Locale.layoutDirection instead? [UseKtx]
            if (TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) == View.LAYOUT_DIRECTION_LTR) { // WARN 1
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:11: Warning: Use the KTX extension property Locale.layoutDirection instead? [UseKtx]
            val dir = getLayoutDirectionFromLocale(Locale.getDefault()) // WARN 2
                      ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 2 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/test.kt line 9: Replace with the layoutDirection extension property:
        @@ -6 +6
        + import androidx.core.text.layoutDirection
        @@ -9 +10
        -     if (TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) == View.LAYOUT_DIRECTION_LTR) { // WARN 1
        +     if (Locale.getDefault().layoutDirection == View.LAYOUT_DIRECTION_LTR) { // WARN 1
        Autofix for src/test/pkg/test.kt line 11: Replace with the layoutDirection extension property:
        @@ -6 +6
        + import androidx.core.text.layoutDirection
        @@ -11 +12
        -     val dir = getLayoutDirectionFromLocale(Locale.getDefault()) // WARN 2
        +     val dir = Locale.getDefault().layoutDirection // WARN 2
        """
      )
  }

  fun testSizeProperty() {
    lint()
      .files(
        kotlin(
            "src/test/pkg/test3.kt",
            """
            package test.pkg

            import android.util.SparseArray
            import android.view.autofill.AutofillValue

            fun AutofillValue.performAutofill(values: SparseArray<AutofillValue>) {
                for (index in 0 until values.size()) {
                    val itemId = values.keyAt(index)
                }
            }
            """,
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/test3.kt:7: Warning: Use the KTX extension property SparseArray.size instead? [UseKtx]
            for (index in 0 until values.size()) {
                                  ~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/test3.kt line 7: Replace with the size extension property:
        @@ -5 +5
        + import androidx.core.util.size
        @@ -7 +8
        -     for (index in 0 until values.size()) {
        +     for (index in 0 until values.size) {
        """
      )
  }

  fun testNonExtensionFunctions() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.graphics.Bitmap

            class BitmapTest {
                private lateinit var bitmapBuffer: Bitmap
                fun test(measuredWidth: Int, measuredHeight: Int) {
                    this.bitmapBuffer = Bitmap.createBitmap(measuredWidth, measuredHeight, Bitmap.Config.ARGB_8888)
                }
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/BitmapTest.kt:8: Warning: Use the KTX function createBitmap instead? [UseKtx]
                this.bitmapBuffer = Bitmap.createBitmap(measuredWidth, measuredHeight, Bitmap.Config.ARGB_8888)
                                    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/BitmapTest.kt line 8: Replace with the createBitmap function:
        @@ -4 +4
        + import androidx.core.graphics.createBitmap
        @@ -8 +9
        -         this.bitmapBuffer = Bitmap.createBitmap(measuredWidth, measuredHeight, Bitmap.Config.ARGB_8888)
        +         this.bitmapBuffer = createBitmap(measuredWidth, measuredHeight)
        """
      )
  }

  fun testRemoveDefaultsOptionOff() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.graphics.Bitmap

            class BitmapTest {
                private lateinit var bitmapBuffer: Bitmap
                fun test(measuredWidth: Int, measuredHeight: Int) {
                    this.bitmapBuffer = Bitmap.createBitmap(measuredWidth, measuredHeight, Bitmap.Config.ARGB_8888)
                }
            }
            """
          )
          .indented()
      )
      .configureOption(UseKtxDetector.REMOVE_DEFAULTS, false)
      .run()
      .expect(
        """
        src/test/pkg/BitmapTest.kt:8: Warning: Use the KTX function createBitmap instead? [UseKtx]
                this.bitmapBuffer = Bitmap.createBitmap(measuredWidth, measuredHeight, Bitmap.Config.ARGB_8888)
                                    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/BitmapTest.kt line 8: Replace with the createBitmap function:
        @@ -4 +4
        + import androidx.core.graphics.createBitmap
        @@ -8 +9
        -         this.bitmapBuffer = Bitmap.createBitmap(measuredWidth, measuredHeight, Bitmap.Config.ARGB_8888)
        +         this.bitmapBuffer = createBitmap(measuredWidth, measuredHeight, Bitmap.Config.ARGB_8888)
        """
      )

    // Also check option on (the default)
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.graphics.Bitmap

            class BitmapTest {
                private lateinit var bitmapBuffer: Bitmap
                fun test(measuredWidth: Int, measuredHeight: Int) {
                    this.bitmapBuffer = Bitmap.createBitmap(measuredWidth, measuredHeight, Bitmap.Config.ARGB_8888)
                }
            }
            """
          )
          .indented()
      )
      .configureOption(UseKtxDetector.REMOVE_DEFAULTS, true)
      .run()
      .expect(
        """
        src/test/pkg/BitmapTest.kt:8: Warning: Use the KTX function createBitmap instead? [UseKtx]
                this.bitmapBuffer = Bitmap.createBitmap(measuredWidth, measuredHeight, Bitmap.Config.ARGB_8888)
                                    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/BitmapTest.kt line 8: Replace with the createBitmap function:
        @@ -4 +4
        + import androidx.core.graphics.createBitmap
        @@ -8 +9
        -         this.bitmapBuffer = Bitmap.createBitmap(measuredWidth, measuredHeight, Bitmap.Config.ARGB_8888)
        +         this.bitmapBuffer = createBitmap(measuredWidth, measuredHeight)
        """
      )
  }

  fun testViewInvisible() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg
            import android.view.View

            fun viewVisible(view: View) {
                if (view.visibility == View.VISIBLE) { // WARN 1
                    // something
                } else if (view.visibility == View.INVISIBLE) { // WARN 2
                    // something
                } else if (view.visibility == View.GONE) { // WARN 3
                    // something
                }

                // I could potentially convert these and insert an "!" in front of the fix!
                // e.g. !view.isVisible
                if (view.visibility != View.VISIBLE) {
                    // something
                } else if (view.visibility != View.INVISIBLE) {
                    // something
                } else if (view.visibility != View.GONE) {
                    // something
                }
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:5: Warning: Use the KTX extension property View.isVisible instead? [UseKtx]
            if (view.visibility == View.VISIBLE) { // WARN 1
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:7: Warning: Use the KTX extension property View.isInvisible instead? [UseKtx]
            } else if (view.visibility == View.INVISIBLE) { // WARN 2
                       ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:9: Warning: Use the KTX extension property View.isGone instead? [UseKtx]
            } else if (view.visibility == View.GONE) { // WARN 3
                       ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 3 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/test.kt line 5: Replace with the isVisible extension property:
        @@ -3 +3
        + import androidx.core.view.isVisible
        @@ -5 +6
        -     if (view.visibility == View.VISIBLE) { // WARN 1
        +     if (view.isVisible) { // WARN 1
        Autofix for src/test/pkg/test.kt line 7: Replace with the isInvisible extension property:
        @@ -3 +3
        + import androidx.core.view.isInvisible
        @@ -7 +8
        -     } else if (view.visibility == View.INVISIBLE) { // WARN 2
        +     } else if (view.isInvisible) { // WARN 2
        Autofix for src/test/pkg/test.kt line 9: Replace with the isGone extension property:
        @@ -3 +3
        + import androidx.core.view.isGone
        @@ -9 +10
        -     } else if (view.visibility == View.GONE) { // WARN 3
        +     } else if (view.isGone) { // WARN 3
        """
      )
  }

  fun testInfix() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.util.Range

            fun testInfix(range1: Range<Int>, range2: Range<Int>, targetFrameRate: Int, targetFrameRate2: Int) {
                // Replacement should use infix syntax: range1 and range2
                val anded = range1.intersect(range2) // WARN 1
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:7: Warning: Use the KTX extension function Range.and instead? [UseKtx]
            val anded = range1.intersect(range2) // WARN 1
                        ~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/test.kt line 7: Replace with the and extension function:
        @@ -4 +4
        + import androidx.core.util.and
        @@ -7 +8
        -     val anded = range1.intersect(range2) // WARN 1
        +     val anded = range1 and range2 // WARN 1
        """
      )
  }

  fun testArraySyntax() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.graphics.Bitmap
            import android.view.Menu

            fun testBitmap(bitmap: Bitmap) {
                for (x in 0 until 100) {
                    for (y in 0 until 100) {
                        // Replacement should use array syntax: bitmap[x, y] rather than bitmap.get(x, y)
                        val pixel = bitmap.getPixel(x, y) // WARN 1
                        bitmap.setPixel(x - 1, y + 1, pixel) // WARN 2
                    }
                }
            }

            fun testMenuArray(menu: Menu) {
                val item = menu.getItem(0) // WARN 3
                // Expect: (via import androidx.core.view.get)
                //val item = menu[0]
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:10: Warning: Use the KTX extension function Bitmap.get instead? [UseKtx]
                    val pixel = bitmap.getPixel(x, y) // WARN 1
                                ~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:11: Warning: Use the KTX extension function Bitmap.set instead? [UseKtx]
                    bitmap.setPixel(x - 1, y + 1, pixel) // WARN 2
                    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:17: Warning: Use the KTX extension function Menu.get instead? [UseKtx]
            val item = menu.getItem(0) // WARN 3
                       ~~~~~~~~~~~~~~~
        0 errors, 3 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/test.kt line 10: Replace with the get extension function:
        @@ -5 +5
        + import androidx.core.graphics.get
        @@ -10 +11
        -             val pixel = bitmap.getPixel(x, y) // WARN 1
        +             val pixel = bitmap[x, y] // WARN 1
        Autofix for src/test/pkg/test.kt line 11: Replace with the set extension function:
        @@ -5 +5
        + import androidx.core.graphics.set
        @@ -11 +12
        -             bitmap.setPixel(x - 1, y + 1, pixel) // WARN 2
        +             bitmap[x - 1, y + 1] = pixel // WARN 2
        Autofix for src/test/pkg/test.kt line 17: Replace with the get extension function:
        @@ -5 +5
        + import androidx.core.view.get
        @@ -17 +18
        -     val item = menu.getItem(0) // WARN 3
        +     val item = menu[0] // WARN 3
        """
      )
  }

  fun testColorProperties() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.graphics.Color

            fun testColors() {
                val color: Int = Color.parseColor("#000000") // WARN 1
                val colorLong = color.toLong()
                Color.red(colorLong) // WARN 2
                Color.green(colorLong) // WARN 3
                Color.blue(colorLong) // WARN 4
                Color.alpha(colorLong) // WARN 5
                Color.toArgb(colorLong) // WARN 6
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:6: Warning: Use the KTX extension function String.toColorInt instead? [UseKtx]
            val color: Int = Color.parseColor("#000000") // WARN 1
                             ~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:8: Warning: Use the KTX extension property Long.red instead? [UseKtx]
            Color.red(colorLong) // WARN 2
            ~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:9: Warning: Use the KTX extension property Long.green instead? [UseKtx]
            Color.green(colorLong) // WARN 3
            ~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:10: Warning: Use the KTX extension property Long.blue instead? [UseKtx]
            Color.blue(colorLong) // WARN 4
            ~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:11: Warning: Use the KTX extension property Long.alpha instead? [UseKtx]
            Color.alpha(colorLong) // WARN 5
            ~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:12: Warning: Use the KTX extension function Long.toColorInt instead? [UseKtx]
            Color.toArgb(colorLong) // WARN 6
            ~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 6 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/test.kt line 6: Replace with the toColorInt extension function:
        @@ -4 +4
        + import androidx.core.graphics.toColorInt
        @@ -6 +7
        -     val color: Int = Color.parseColor("#000000") // WARN 1
        +     val color: Int = "#000000".toColorInt() // WARN 1
        Autofix for src/test/pkg/test.kt line 8: Replace with the red extension property:
        @@ -4 +4
        + import androidx.core.graphics.red
        @@ -8 +9
        -     Color.red(colorLong) // WARN 2
        +     colorLong.red // WARN 2
        Autofix for src/test/pkg/test.kt line 9: Replace with the green extension property:
        @@ -4 +4
        + import androidx.core.graphics.green
        @@ -9 +10
        -     Color.green(colorLong) // WARN 3
        +     colorLong.green // WARN 3
        Autofix for src/test/pkg/test.kt line 10: Replace with the blue extension property:
        @@ -4 +4
        + import androidx.core.graphics.blue
        @@ -10 +11
        -     Color.blue(colorLong) // WARN 4
        +     colorLong.blue // WARN 4
        Autofix for src/test/pkg/test.kt line 11: Replace with the alpha extension property:
        @@ -4 +4
        + import androidx.core.graphics.alpha
        @@ -11 +12
        -     Color.alpha(colorLong) // WARN 5
        +     colorLong.alpha // WARN 5
        Autofix for src/test/pkg/test.kt line 12: Replace with the toColorInt extension function:
        @@ -4 +4
        + import androidx.core.graphics.toColorInt
        @@ -12 +13
        -     Color.toArgb(colorLong) // WARN 6
        +     colorLong.toColorInt() // WARN 6
        """
      )
  }

  fun testToDrawable() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.content.Context
            import android.graphics.Bitmap
            import android.graphics.drawable.BitmapDrawable
            import android.graphics.drawable.ColorDrawable

            fun testDrawable(context: Context, bitmap: Bitmap, color: Int) {
                val bitmapDrawable = BitmapDrawable(context.resources, bitmap) // WARN 1
                val colorDrawable = ColorDrawable(color) // WARN 2
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:9: Warning: Use the KTX extension function Bitmap.toDrawable instead? [UseKtx]
            val bitmapDrawable = BitmapDrawable(context.resources, bitmap) // WARN 1
                                 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:10: Warning: Use the KTX extension function Int.toDrawable instead? [UseKtx]
            val colorDrawable = ColorDrawable(color) // WARN 2
                                ~~~~~~~~~~~~~~~~~~~~
        0 errors, 2 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/test.kt line 9: Replace with the toDrawable extension function:
        @@ -7 +7
        + import androidx.core.graphics.drawable.toDrawable
        @@ -9 +10
        -     val bitmapDrawable = BitmapDrawable(context.resources, bitmap) // WARN 1
        +     val bitmapDrawable = bitmap.toDrawable(context.resources) // WARN 1
        Autofix for src/test/pkg/test.kt line 10: Replace with the toDrawable extension function:
        @@ -7 +7
        + import androidx.core.graphics.drawable.toDrawable
        @@ -10 +11
        -     val colorDrawable = ColorDrawable(color) // WARN 2
        +     val colorDrawable = color.toDrawable() // WARN 2
        """
      )
  }

  fun testFindNavController() {
    // Here we have a static method in a class that we're replacing with an instance method
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.app.Activity
            import androidx.navigation.Navigation

            fun testNav(activity: Activity, viewId: Int) {
                Navigation.findNavController(activity, viewId)
            }
            """
          )
          .indented(),
        kotlin(
          // Stub
          """
          // HIDE-FROM-DOCUMENTATION
          package androidx.navigation

          import android.app.Activity
          import android.view.View
          object Navigation {
              @JvmStatic
              fun findNavController(activity: Activity, viewId: Int): NavController = error("Not yet implemented")
          }
          class NavController
          """
        ),
      )
      // We check for exact method signatures, and this test mode can
      // change the test signatures (by adding extra parameters) in stub methods.
      .skipTestModes(TestMode.JVM_OVERLOADS)
      .run()
      .expect(
        """
        src/test/pkg/test.kt:7: Warning: Use the KTX extension function Activity.findNavController instead? [UseKtx]
            Navigation.findNavController(activity, viewId)
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/test.kt line 7: Replace with the findNavController extension function:
        @@ -5 +5
        + import androidx.navigation.findNavController
        @@ -7 +8
        -     Navigation.findNavController(activity, viewId)
        +     activity.findNavController(viewId)
        """
      )
  }

  fun testEqualsIgnoringWhitespace() {
    assertTrue("".equalsIgnoringSpace(""))
    assertTrue("".equalsIgnoringSpace(" "))
    assertTrue(" ".equalsIgnoringSpace(""))
    assertTrue("foo".equalsIgnoringSpace("foo"))
    assertTrue("foo".equalsIgnoringSpace(" foo "))
    assertTrue(" foo ".equalsIgnoringSpace("foo"))
    assertTrue("foo.bar".equalsIgnoringSpace("foo . bar"))
    assertFalse("foo.bar".equalsIgnoringSpace("foo . ba"))
    assertFalse("foo.ba".equalsIgnoringSpace("foo . bar"))
    assertFalse("fooo.bar".equalsIgnoringSpace("foo . bar"))
  }
}
