/*
 * Copyright (C) 2014 The Android Open Source Project
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

import com.android.tools.lint.detector.api.Detector

class CustomViewDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector {
    return CustomViewDetector()
  }

  fun testDocumentationExample() {
    lint()
      .files(
        java(
            """
            package test.pkg;

            import android.content.Context;
            import android.util.AttributeSet;
            import android.widget.Button;
            import android.widget.LinearLayout;

            public class CustomView1 extends Button {
                public CustomView1(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
                    super(context, attrs, defStyleAttr);
                    // OK
                    context.obtainStyledAttributes(R.styleable.CustomView1);
                    context.obtainStyledAttributes(defStyleRes, R.styleable.CustomView1);
                    context.obtainStyledAttributes(attrs, R.styleable.CustomView1);
                    context.obtainStyledAttributes(attrs, R.styleable.CustomView1, defStyleAttr, defStyleRes);

                    // Wrong:
                    context.obtainStyledAttributes(R.styleable.MyDeclareStyleable);
                    context.obtainStyledAttributes(defStyleRes, R.styleable.MyDeclareStyleable);
                    context.obtainStyledAttributes(attrs, R.styleable.MyDeclareStyleable);
                    context.obtainStyledAttributes(attrs, R.styleable.MyDeclareStyleable, defStyleAttr,
                            defStyleRes);

                    // Unknown: Not flagged
                    int[] dynamic = getStyleable();
                    context.obtainStyledAttributes(dynamic);
                    context.obtainStyledAttributes(defStyleRes, dynamic);
                    context.obtainStyledAttributes(attrs, dynamic);
                    context.obtainStyledAttributes(attrs, dynamic, defStyleAttr, defStyleRes);
                }

                private int[] getStyleable() {
                    return new int[0];
                }

                public static class MyLayout extends LinearLayout {
                    public MyLayout(Context context, AttributeSet attrs, int defStyle) {
                        super(context, attrs, defStyle);
                        context.obtainStyledAttributes(R.styleable.MyLayout);
                    }

                    public static class MyLayoutParams extends LinearLayout.LayoutParams {
                        public MyLayoutParams(Context context, AttributeSet attrs) {
                            super(context, attrs);
                            context.obtainStyledAttributes(R.styleable.MyLayout_Layout); // OK
                            context.obtainStyledAttributes(R.styleable.MyLayout); // Wrong
                            context.obtainStyledAttributes(R.styleable.MyDeclareStyleable); // Wrong
                        }
                    }
                }
            }
            """
          )
          .indented(),
        java(
            """
            package test.pkg;

            public final class R {
                public static final class attr {
                    public static final int layout_myWeight=0x7f010001;
                    public static final int myParam=0x7f010000;
                }
                public static final class dimen {
                    public static final int activity_horizontal_margin=0x7f040000;
                    public static final int activity_vertical_margin=0x7f040001;
                }
                public static final class drawable {
                    public static final int ic_launcher=0x7f020000;
                }
                public static final class id {
                    public static final int action_settings=0x7f080000;
                }
                public static final class layout {
                    public static final int activity_my=0x7f030000;
                }
                public static final class menu {
                    public static final int my=0x7f070000;
                }
                public static final class string {
                    public static final int action_settings=0x7f050000;
                    public static final int app_name=0x7f050001;
                    public static final int hello_world=0x7f050002;
                }
                public static final class style {
                    public static final int AppTheme=0x7f060000;
                }
                public static final class styleable {
                    public static final int[] CustomView1 = {

                    };
                    public static final int[] MyDeclareStyleable = {

                    };
                    public static final int[] MyLayout = {
                            0x010100c4, 0x7f010000
                    };
                    public static final int MyLayout_android_orientation = 0;
                    public static final int MyLayout_myParam = 1;
                    public static final int[] MyLayout_Layout = {
                            0x010100f4, 0x010100f5, 0x7f010001
                    };
                    public static final int MyLayout_Layout_android_layout_height = 1;
                    public static final int MyLayout_Layout_android_layout_width = 0;
                    public static final int MyLayout_Layout_layout_myWeight = 2;
                }
            }
            """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/test/pkg/CustomView1.java:18: Warning: By convention, the custom view (CustomView1) and the declare-styleable (MyDeclareStyleable) should have the same name (various editor features rely on this convention) [CustomViewStyleable]
                context.obtainStyledAttributes(R.styleable.MyDeclareStyleable);
                                               ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/CustomView1.java:19: Warning: By convention, the custom view (CustomView1) and the declare-styleable (MyDeclareStyleable) should have the same name (various editor features rely on this convention) [CustomViewStyleable]
                context.obtainStyledAttributes(defStyleRes, R.styleable.MyDeclareStyleable);
                                                            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/CustomView1.java:20: Warning: By convention, the custom view (CustomView1) and the declare-styleable (MyDeclareStyleable) should have the same name (various editor features rely on this convention) [CustomViewStyleable]
                context.obtainStyledAttributes(attrs, R.styleable.MyDeclareStyleable);
                                                      ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/CustomView1.java:21: Warning: By convention, the custom view (CustomView1) and the declare-styleable (MyDeclareStyleable) should have the same name (various editor features rely on this convention) [CustomViewStyleable]
                context.obtainStyledAttributes(attrs, R.styleable.MyDeclareStyleable, defStyleAttr,
                                                      ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/CustomView1.java:46: Warning: By convention, the declare-styleable (MyLayout) for a layout parameter class (MyLayoutParams) is expected to be the surrounding class (MyLayout) plus "_Layout", e.g. MyLayout_Layout. (Various editor features rely on this convention.) [CustomViewStyleable]
                        context.obtainStyledAttributes(R.styleable.MyLayout); // Wrong
                                                       ~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/CustomView1.java:47: Warning: By convention, the declare-styleable (MyDeclareStyleable) for a layout parameter class (MyLayoutParams) is expected to be the surrounding class (MyLayout) plus "_Layout", e.g. MyLayout_Layout. (Various editor features rely on this convention.) [CustomViewStyleable]
                        context.obtainStyledAttributes(R.styleable.MyDeclareStyleable); // Wrong
                                                       ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 6 warnings
        """
      )
  }

  fun testObtainOnCall() {
    // Regression test for case where we're calling obtainStyledAttributes on a context via
    // a call rather than a variable reference

    lint()
      .files(
        java(
            """
            package test.pkg;

            import android.content.Context;
            import android.content.res.TypedArray;
            import android.util.AttributeSet;
            import android.view.View;

            @SuppressWarnings({"unused", "FieldCanBeLocal"})
            public class AppBulletView extends View {
                public AppBulletView(Context context, AttributeSet attrs, int defStyle) {
                    super(context, attrs, defStyle);
                    parseAttributes(attrs);
                }

                private void parseAttributes(AttributeSet attrs) {
                    TypedArray array = getContext().obtainStyledAttributes(attrs, R.styleable.Bullet);
                    array.recycle();
                }
            }
            """
          )
          .indented(),
        java(
            """
            package test.pkg;

            public final class R {
                public static final class styleable {
                    public static final int[] Bullet = {
                        0x7f010000, 0x7f010001, 0x7f010002, 0x7f010003
                    };
                }
            }
            """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/test/pkg/AppBulletView.java:16: Warning: By convention, the custom view (AppBulletView) and the declare-styleable (Bullet) should have the same name (various editor features rely on this convention) [CustomViewStyleable]
                TypedArray array = getContext().obtainStyledAttributes(attrs, R.styleable.Bullet);
                                                                              ~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
  }
}
