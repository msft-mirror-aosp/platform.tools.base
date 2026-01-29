/*
 * Copyright (C) 2018 The Android Open Source Project
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

class IntentDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector {
    return IntentDetector()
  }

  fun testBasic() {
    // Regression test for https://issuetracker.google.com/36967533
    lint()
        .files(
            java(
                    """
                package test.pkg;

                import android.content.Intent;
                import android.net.Uri;

                public class IntentTest {
                        public void test1() {
                            // OK: Nulls are allowed
                            Intent intent = new Intent();
                            intent.setData(null); // OK 1
                            intent.setType(null); // OK 2
                        }

                        public void test2(Uri uri, String type) {
                            // Error: Cleared
                            Intent intent = new Intent();
                            intent.setData(uri); // ERROR 1.2
                            intent.setType(type); // ERROR 1.1
                        }

                        public void test3(Uri uri, String type) {
                            // Error: Cleared (reverse order from test2)
                            Intent intent = new Intent();
                            intent.setType(type); // ERROR 2.2
                            intent.setData(uri); // ERROR 2.1
                        }

                        public void test4(Uri uri, String type, boolean setData) {
                            // OK: Different code paths
                            Intent intent = new Intent();
                            if (setData) {
                                intent.setData(uri); // OK 3
                            } else {
                                intent.setType(type); // OK 4
                            }
                            if (setData)
                                intent.setData(uri); // OK 5
                            else
                                intent.setType(type); // OK 6
                        }

                        public void test5(Uri uri, String type) {
                            // Ok: on different objects
                            Intent intent1 = new Intent();
                            Intent intent2 = new Intent();
                            intent1.setData(uri); // OK 7
                            intent2.setType(type); // OK 8
                        }

                        public void test6(boolean setData, Uri dataUri,
                                boolean setType, String mime) {
                            Intent intent = new Intent();
                            if (setData) {
                                intent.setData(dataUri); // OK 9
                            }
                            if (setType) {
                                intent.setType(mime); // OK 10
                            }
                        }

                        public void test7(int flavor, Uri dataUri, String mime) {
                            Intent intent = new Intent();
                            switch (flavor) {
                                case 1:
                                    intent.setData(dataUri); // OK 11
                                    break;
                                case 2:
                                    intent.setType(mime); // OK 12
                                    break;
                            }
                        }
                }"""
                )
                .indented()
        )
        .run()
        .expect(
            """
        src/test/pkg/IntentTest.java:18: Warning: Calling setType after calling setData will clear the data: Call setDataAndType instead? [IntentReset]
                    intent.setType(type); // ERROR 1.1
                           ~~~~~~~~~~~~~
            src/test/pkg/IntentTest.java:17: Originally set here
                    intent.setData(uri); // ERROR 1.2
                    ~~~~~~~~~~~~~~~~~~~
        src/test/pkg/IntentTest.java:25: Warning: Calling setData after calling setType will clear the type: Call setDataAndType instead? [IntentReset]
                    intent.setData(uri); // ERROR 2.1
                           ~~~~~~~~~~~~
            src/test/pkg/IntentTest.java:24: Originally set here
                    intent.setType(type); // ERROR 2.2
                    ~~~~~~~~~~~~~~~~~~~~
        0 errors, 2 warnings
        """
        )
  }

  fun testConstructor() {
    // URI specified in Intent constructor
    // Regression test for https://issuetracker.google.com/73183202
    lint()
        .files(
            java(
                    """
                package test.pkg;

                import android.app.Activity;
                import android.content.Context;
                import android.content.Intent;
                import android.net.Uri;

                public class IntentReset extends Activity {
                    private Context mContext = null;
                    public void test(Uri uri) {
                        Intent myIntent = new Intent(Intent.ACTION_VIEW, uri);
                        myIntent.setType("text/plain");
                        myIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(myIntent);
                    }
                }
                """
                )
                .indented()
        )
        .run()
        .expect(
            """
        src/test/pkg/IntentReset.java:12: Warning: Calling setType after setting URI in Intent constructor will clear the data: Call setDataAndType instead? [IntentReset]
                myIntent.setType("text/plain");
                         ~~~~~~~~~~~~~~~~~~~~~
            src/test/pkg/IntentReset.java:11: Originally set here
                Intent myIntent = new Intent(Intent.ACTION_VIEW, uri);
                                                                 ~~~
        0 errors, 1 warning
        """
        )
  }

  fun testChained() {
    // Regression test for issue 205738500
    lint()
        .files(
            java(
                    """
                package test.pkg;

                import android.content.Intent;
                import android.net.Uri;

                public class IntentTest {
                        public void test(Uri uri, String type) {
                            Intent intent = new Intent();
                            intent.setData(uri).setFlags(0).setType(type); // ERROR 1
                            intent.setType(type).setFlags(0).setData(uri); // ERROR 2
                        }

                }"""
                )
                .indented()
        )
        .run()
        .expect(
            """
        src/test/pkg/IntentTest.java:9: Warning: Calling setType after calling setData will clear the data: Call setDataAndType instead? [IntentReset]
                    intent.setData(uri).setFlags(0).setType(type); // ERROR 1
                                                    ~~~~~~~~~~~~~
            src/test/pkg/IntentTest.java:9: Originally set here
                    intent.setData(uri).setFlags(0).setType(type); // ERROR 1
                    ~~~~~~~~~~~~~~~~~~~
        src/test/pkg/IntentTest.java:10: Warning: Calling setData after calling setType will clear the type: Call setDataAndType instead? [IntentReset]
                    intent.setType(type).setFlags(0).setData(uri); // ERROR 2
                                                     ~~~~~~~~~~~~
            src/test/pkg/IntentTest.java:10: Originally set here
                    intent.setType(type).setFlags(0).setData(uri); // ERROR 2
                    ~~~~~~~~~~~~~~~~~~~~
        0 errors, 2 warnings
        """
        )
  }

  fun testConstructorDifferentBlockKotlin() {
    // Regression test for https://issuetracker.google.com/398098110
    // When the data is set in the constructor, it does not matter that the type is set in a
    // different block; we can still report it.
    lint()
        .files(
            kotlin(
                    """
            package com.example.app

            import android.content.Intent
            import android.net.Uri

            fun foo(uri: Uri) {
              val intent2 = Intent(Intent.ACTION_VIEW, uri).apply {
                type = "image/*"
              }
            }

            """
                )
                .indented()
        )
        // Changing to:
        //   (type) = "image/*"
        // makes this no longer get visited as a call to setType. We could probably handle this case
        // in DataFlowAnalyzer, but it is probably not worth it.
        .skipTestModes(TestMode.PARENTHESIZED)
        .run()
        .expect(
            """
        src/com/example/app/test.kt:8: Warning: Calling setType after setting URI in Intent constructor will clear the data: Call setDataAndType instead? [IntentReset]
            type = "image/*"
            ~~~~~~~~~~~~~~~~
            src/com/example/app/test.kt:7: Originally set here
          val intent2 = Intent(Intent.ACTION_VIEW, uri).apply {
                                                   ~~~
        0 errors, 1 warning
        """
        )
  }

  fun testConstructorDifferentBlockJava() {
    // Regression test for https://issuetracker.google.com/398098110
    // When the data is set in the constructor, it does not matter that the type is set
    // (conditionally) in a different block; we can still report it.
    lint()
        .files(
            java(
                    """
            package com.example.app;

            import android.app.Activity;
            import android.content.Context;
            import android.content.Intent;
            import android.net.Uri;

            public class MyActivity extends Activity {
                private Context mContext = null;
                public void test(Uri uri) {
                    Intent myIntent = new Intent(Intent.ACTION_VIEW, uri);
                    if (mContext == null) {
                      myIntent.setType("text/plain");
                    }
                    startActivity(myIntent);
                }
            }
            """
                )
                .indented()
        )
        .run()
        .expect(
            """
        src/com/example/app/MyActivity.java:13: Warning: Calling setType after setting URI in Intent constructor will clear the data: Call setDataAndType instead? [IntentReset]
                  myIntent.setType("text/plain");
                           ~~~~~~~~~~~~~~~~~~~~~
            src/com/example/app/MyActivity.java:11: Originally set here
                Intent myIntent = new Intent(Intent.ACTION_VIEW, uri);
                                                                 ~~~
        0 errors, 1 warning
        """
        )
  }
}
