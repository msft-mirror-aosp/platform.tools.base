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

import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector

class UseKtxDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector {
    return UseKtxDetector()
  }

  fun testDocumentationExampleUseKtx() {
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

            // TODO: Place *two* obtainCalls within the same method/block to make sure I
            // properly pair each open with each close
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
}
