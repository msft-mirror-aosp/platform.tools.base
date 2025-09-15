/*
 * Copyright (C) 20233 The Android Open Source Project
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

package com.android.tools.lint.checks.optional

import com.android.testutils.TestUtils
import com.android.tools.lint.LintCliFlags
import com.android.tools.lint.MainTest
import com.android.tools.lint.checks.AbstractCheckTest.SUPPORT_ANNOTATIONS_JAR
import com.android.tools.lint.checks.ApiDetector
import com.android.tools.lint.checks.ApiLookupTest
import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles
import com.android.tools.lint.checks.infrastructure.TestFiles.binaryStub
import com.android.tools.lint.checks.infrastructure.TestFiles.java
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import java.io.File
import org.intellij.lang.annotations.Language

class FlaggedApiDetectorTest : LintDetectorTest() {
  override fun getIssues(): List<Issue> = listOf(FlaggedApiDetector.ISSUE)

  override fun getDetector(): Detector {
    return FlaggedApiDetector()
  }

  override fun lint(): TestLintTask {
    return super.lint().allowMissingSdk()
  }

  fun testDocumentationExample() {
    lint()
      .files(
        java(
            """
            package test.api;
            import android.annotation.FlaggedApi;
            import com.example.foobar.Flags;

            @FlaggedApi(Flags.FLAG_FOOBAR)
            public class MyApi {
              public void apiMethod() { }
              public int apiField = 42;
            }
            """
          )
          .indented(),
        java(
            """
            package test.pkg;
            import test.api.MyApi;
            import com.example.foobar.Flags;

            public class Test {
              public void test(MyApi api) {
                if (Flags.foobar()) {
                  api.apiMethod(); // OK
                  int val = api.apiField; // OK
                }
                api.apiMethod(); // ERROR 1
                int val = api.apiField; // ERROR 2
                Object o = MyApi.class; // ERROR 3
              }
            }
            """
          )
          .indented(),
        // Generated
        java(
            """
            package com.example.foobar;

            public class Flags {
                public static final String FLAG_FOOBAR = "com.example.foobar.foobar";
                public static boolean foobar() { return true; }
            }
            """
          )
          .indented(),
        flaggedApiAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:11: Error: Method apiMethod() is a flagged API and should be inside an if (Flags.foobar()) check (or annotate the surrounding method test with @FlaggedApi(Flags.FLAG_FOOBAR) to transfer requirement to caller) [FlaggedApi]
            api.apiMethod(); // ERROR 1
            ~~~~~~~~~~~~~~~
        src/test/pkg/Test.java:12: Error: Field apiField is a flagged API and should be inside an if (Flags.foobar()) check (or annotate the surrounding method test with @FlaggedApi(Flags.FLAG_FOOBAR) to transfer requirement to caller) [FlaggedApi]
            int val = api.apiField; // ERROR 2
                          ~~~~~~~~
        src/test/pkg/Test.java:13: Error: Class MyApi is a flagged API and should be inside an if (Flags.foobar()) check (or annotate the surrounding method test with @FlaggedApi(Flags.FLAG_FOOBAR) to transfer requirement to caller) [FlaggedApi]
            Object o = MyApi.class; // ERROR 3
                       ~~~~~~~~~~~
        3 errors, 0 warnings
        """
      )
  }

  fun testNoFlagsClassPresent() {
    // Make sure the lint checks still flag API usages even if you don't
    // have the Flags class on the classpath
    lint()
      .files(
        bytecode(
          "libs/annotation.jar",
          flaggedApiAnnotationStub,
          0x81415584,
          """
          android/annotation/FlaggedApi.class:
          H4sIAAAAAAAA/4WRwU4CMRCG/yLLKqigookHo/FA9OIePXja4BJJcJfsVhPj
          wRRoNiWlS5ZCwqt58AF8KOOsJsKBxEP/Tjrf/O1MP7/ePwDc4sTFkYumi2MG
          ZyH0XDI0r657Y7EQnhYm9RKbK5PeMVSTbJ4PZUdpYuodLdJUjvypuilYhtN4
          bqyayGc1UwMtfWMyK6zKzIzhbM1P/CU8LvJUWrK+3JwPtJxIY/lyKgkq85d+
          wFB5DPhDdM9Qa0dhwuOnNo9ienynG/TotO6HYcR93o3Ct9+Ci83msbTkTRFZ
          t/5B+plWwyWBTrvnJwk1JMwoz9RonV5NhKGx8osGYzm0DOcbr1iNqcXAUKK1
          RR/DytQvHIpKqPyoi23aPYp2iCm/wpGoolbIbiF7hewXUkejICQOcPgNDPA2
          HOgBAAA=
          """,
        ),
        bytecode(
          "libs/api.jar",
          // Generated
          java(
              """
              package com.android.aconfig.test;

              public class Flags {
                  public static final String FLAG_DISABLED_RO = "com.android.aconfig.test.ExportedFlags.disabledRo";
                  public static boolean disabledRo() {
                      return true; // not the real implementation
                  }
              }
              """
            )
            .indented(),
          0xa6233539,
          """
            com/android/aconfig/test/Flags.class:
            H4sIAAAAAAAA/11Qy0oDQRCsztuYmBi9KCh4Uw+7ePKgCDEPERYDieTgJUx2
            x2VkMyO7E/GjvHgSPPgBfpTYOwYCHqa6p6aqu5jvn88vAOfYraOIdhXbVXQI
            7WHQvZn1byfd62DQn41HhE7wJF6Enwgd+xObKh1fEJo9ozMrtJ2KZClr2CFU
            LpVW9opQPD6ZEko9E0lCK1Ba3i0Xc5nei3nCTD1SWd5FY+O0D0xNzDIN5VC5
            92Ei4szLlzZQwwbhMDQLX+goNSryRWj0o4p9KzPrOymnXicczZ9kaAln7PFW
            Hm/l8XKPN3h9NqmV0d+adRgcocBfARD2UEKZa4VvBVT5UJ6Esc7MAVfiWj79
            AL07wyZjxZFFljXQXEn3HcdDSm//dDluufGtX5zQZkOLAQAA
            """,
        ),
        bytecode(
          "libs/api.jar",
          java(
              """
              package test.api;
              import android.annotation.FlaggedApi;
              import com.android.aconfig.test.Flags;

              @FlaggedApi(Flags.FLAG_DISABLED_RO)
              public class MyApi {
                public void apiMethod() { }
                public int apiField = 42;
              }
              """
            )
            .indented(),
          0x3ababae6,
          """
            test/api/MyApi.class:
            H4sIAAAAAAAA/0VQTUvDQBScbdOmTWtbP0E8iCe1hwRPHhShiIVCq1DF+za7
            xi3pbkk2RX+WBxE8+AP8UeLLIvXwHvvmzcwO7/vn8wvAOfYCeNhqooptHzs+
            dhkafKmGSqaCgY0Y6pdKK3vFUD05fWTwro2QDN2x0vK2WMxk9sBnKSFNkk2k
            fTakC+5NkcVyqMpFMHkdLFU45yvOcDAttFULOdIrlSsSDrQ2lltldM5wOOZa
            ZEaJiK/haJjyJJGCPC4YaiueFmR6FptF+EcOeWz0k0pCK3Mb3rwsTWalKGV5
            KFRexhNT04aPRhs11Bk6JTOiwJHLxtAr00Up10l0N5vL2OKITuLRiegIpYZe
            FXqRBfUmTcduBlr9D7B+r/+OypujB9QDWoJEnqO31qJ9x6D6p9Yd4JOg7f7Y
            QKd0JbRL1cux+QtqrrJkrAEAAA==
            """,
        ),
        java(
            """
            package test.pkg;
            import test.api.MyApi;

            public class Test {
              public void test(MyApi api) {
                api.apiMethod(); // ERROR 1
                // Wrong flags class
                if (Flags.disabledRo()) {
                  int val = api.apiField; // ERROR 2
                }
              }
            }

            class Flags {
                static final String FLAG_DISABLED_RO = "test.pkg.Flags.disabled_ro";
                static boolean disabledRo() {
                    return true; // not the real implementation
                }
            }
            """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:6: Error: Method apiMethod() is a flagged API and should be inside an if (ExportedFlags.disabledro()) check (or annotate the surrounding method test with @FlaggedApi(ExportedFlags.disabledRo) to transfer requirement to caller) [FlaggedApi]
            api.apiMethod(); // ERROR 1
            ~~~~~~~~~~~~~~~
        src/test/pkg/Test.java:9: Error: Field apiField is a flagged API and should be inside an if (ExportedFlags.disabledro()) check (or annotate the surrounding method test with @FlaggedApi(ExportedFlags.disabledRo) to transfer requirement to caller) [FlaggedApi]
              int val = api.apiField; // ERROR 2
                            ~~~~~~~~
        2 errors
        """
      )
  }

  fun testCompiled() {
    lint()
      .files(
        compiled(
          "libs/annotation.jar",
          flaggedApiAnnotationStub,
          0x81415584,
          """
          android/annotation/FlaggedApi.class:
          H4sIAAAAAAAA/4WRwU4CMRCG/yLLKqigookHo/FA9OIePXja4BJJcJfsVhPj
          wRRoNiWlS5ZCwqt58AF8KOOsJsKBxEP/Tjrf/O1MP7/ePwDc4sTFkYumi2MG
          ZyH0XDI0r657Y7EQnhYm9RKbK5PeMVSTbJ4PZUdpYuodLdJUjvypuilYhtN4
          bqyayGc1UwMtfWMyK6zKzIzhbM1P/CU8LvJUWrK+3JwPtJxIY/lyKgkq85d+
          wFB5DPhDdM9Qa0dhwuOnNo9ienynG/TotO6HYcR93o3Ct9+Ci83msbTkTRFZ
          t/5B+plWwyWBTrvnJwk1JMwoz9RonV5NhKGx8osGYzm0DOcbr1iNqcXAUKK1
          RR/DytQvHIpKqPyoi23aPYp2iCm/wpGoolbIbiF7hewXUkejICQOcPgNDPA2
          HOgBAAA=
          """,
        ),
        compiled(
          "libs/api.jar",
          // Generated
          java(
              """
              package com.android.aconfig.test;

              public class Flags {
                  public static final String FLAG_DISABLED_RO = "com.android.aconfig.test.disabled_ro";
                  public static boolean disabledRo() {
                      return true; // not the real implementation
                  }
              }
              """
            )
            .indented(),
          0xc07ff6ad,
          """
          com/android/aconfig/test/Flags.class:
          H4sIAAAAAAAA/11PPUsDQRScl29jYmK0UVAQLNTirkyhCDExIhwGEklhEzZ3
          67Hhsgt3e/4qGyvBwh/gjxLfLRHBYue9nZ158/br++MTQB/7TZTRrWO3jh6h
          Ow4Gd4vR/WxwE9yOFtMJoResxIvwE6Fjf2ZTpeNLQntodGaFtnOR5LKBPULt
          Smllrwnls/M5oTI0kSR0AqXlQ75eyvRRLBNmmpHKii6aGqd9Ympm8jSUY+Xe
          x4mIM68IbaGBLcJxaNa+0FFqVOSL0OhnFftWZtZ3Ut76b8PJciVDSzhlj7fx
          eBuPV3i83/hFanCCEv8eIByggirXGt9KqPOhIpyxycwRV+JavXgHvTnDNmPN
          kWWWtdDeSA8dx0Mqr/90Be648Z0fKhia7H4BAAA=
          """,
        ),
        compiled(
          "libs/api.jar",
          java(
              """
              package test.api;
              import android.annotation.FlaggedApi;
              import com.android.aconfig.test.Flags;

              @FlaggedApi(Flags.FLAG_DISABLED_RO)
              public class MyApi {
                public void apiMethod() { }
                public int apiField = 42;
              }
              """
            )
            .indented(),
          0x6f573c19,
          """
          test/api/MyApi.class:
          H4sIAAAAAAAA/0VQTUvDQBB926ZNm9a2foJ4EEFQc0iOHhShCIVCq6DiVbbZ
          NW5Jd0uyKfizPIjgwR/gjxIni9TDDDNv3ns7O98/n18AzrEXwMNWG3Vs+9jx
          scvQ4ks1UjITDGzM0LxUWtkrhvrp2SODd22EZOhPlJY35WIm8wc+ywhpk2wq
          7YshXXBvyjyRI1UNgunrcKmiOV9xhoO7Ulu1kGO9UoUi4VBrY7lVRhcMhxOu
          RW6UiPkajkcZT1MpyOOCobHiWUmmx4lZRH/kiCdGP6s0srKwkVBFtZB4yk0X
          PlpdNNBk6FXDmHaM3ToMg2qhOOM6jW9nc5lYHNEVPLoK/bvSUFWjiiwot6k7
          cT3QCT/AwkH4jtqboweUAxqCRJ6jd9aifceg+Kc2HeCToOve2ECvciW0TzEo
          sPkLyuJbCJ8BAAA=
          """,
        ),
        java(
            """
            package test.pkg;
            import test.api.MyApi;
            import com.android.aconfig.test.Flags;

            public class Test {
              public void test(MyApi api) {
                if (Flags.disabledRo()) {
                  api.apiMethod(); // OK
                  int val = api.apiField; // OK
                }
                api.apiMethod(); // ERROR 1
                int val = api.apiField; // ERROR 2
                Object o = MyApi.class; // ERROR 3
              }
            }
            """
          )
          .indented(),
      )
      .skipTestModes(TestMode.SOURCE_ONLY)
      .run()
      .expect(
        """
        src/test/pkg/Test.java:11: Error: Method apiMethod() is a flagged API and should be inside an if (Flags.disabledRo()) check (or annotate the surrounding method test with @FlaggedApi(Flags.FLAG_DISABLED_RO) to transfer requirement to caller) [FlaggedApi]
            api.apiMethod(); // ERROR 1
            ~~~~~~~~~~~~~~~
        src/test/pkg/Test.java:12: Error: Field apiField is a flagged API and should be inside an if (Flags.disabledRo()) check (or annotate the surrounding method test with @FlaggedApi(Flags.FLAG_DISABLED_RO) to transfer requirement to caller) [FlaggedApi]
            int val = api.apiField; // ERROR 2
                          ~~~~~~~~
        src/test/pkg/Test.java:13: Error: Class MyApi is a flagged API and should be inside an if (Flags.disabledRo()) check (or annotate the surrounding method test with @FlaggedApi(Flags.FLAG_DISABLED_RO) to transfer requirement to caller) [FlaggedApi]
            Object o = MyApi.class; // ERROR 3
                       ~~~~~~~~~~~
        3 errors, 0 warnings
        """
      )
  }

  fun testCamelCaseFlagName() {
    lint()
      .files(
        java(
            """
            package test.pkg;
            import test.api.MyApi;
            import com.android.aconfig.test.Flags;

            public class Test {
              public void test(MyApi api) {
                if (Flags.enabledFixedRo()) {
                  api.apiMethod(); // OK
                }
                api.apiMethod(); // ERROR 1
              }
            }
            """
          )
          .indented(),
        java(
            """
            package test.api;
            import android.annotation.FlaggedApi;
            import com.android.aconfig.test.Flags;

            public class MyApi {
              @FlaggedApi(Flags.FLAG_ENABLED_FIXED_RO)
              public void apiMethod() { }
            }
            """
          )
          .indented(),
        // Generated code:
        java(
            """
            package com.android.aconfig.test;
            public final class Flags {
                public static final String FLAG_DISABLED_RO = "com.android.aconfig.test.disabled_ro";
                public static final String FLAG_DISABLED_RW = "com.android.aconfig.test.disabled_rw";
                public static final String FLAG_ENABLED_FIXED_RO = "com.android.aconfig.test.enabled_fixed_ro";
                public static final String FLAG_ENABLED_RO = "com.android.aconfig.test.enabled_ro";
                public static final String FLAG_ENABLED_RW = "com.android.aconfig.test.enabled_rw";

                public static boolean disabledRo() {
                    return FEATURE_FLAGS.disabledRo();
                }

                public static boolean disabledRw() {
                    return FEATURE_FLAGS.disabledRw();
                }

                public static boolean enabledFixedRo() {
                    return FEATURE_FLAGS.enabledFixedRo();
                }

                public static boolean enabledRo() {
                    return FEATURE_FLAGS.enabledRo();
                }
                public static boolean enabledRw() {
                    return FEATURE_FLAGS.enabledRw();
                }
            }
            """
          )
          .indented(),
        flaggedApiAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:10: Error: Method apiMethod() is a flagged API and should be inside an if (Flags.enabledFixedRo()) check (or annotate the surrounding method test with @FlaggedApi(Flags.FLAG_ENABLED_FIXED_RO) to transfer requirement to caller) [FlaggedApi]
            api.apiMethod(); // ERROR 1
            ~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  fun testPartOfApi() {
    // Make sure we don't flag calls to APIs from within other parts of the
    // same API (e.g. also annotated with the same annotation)
    lint()
      .files(
        java(
            """
            package test.api;
            import android.annotation.FlaggedApi;
            import com.example.foobar.Flags;

            @FlaggedApi(Flags.FLAG_FOOBAR)
            public class MyApi {
              public void apiMethod() { }
            }
            """
          )
          .indented(),
        java(
            """
            package test.api;
            import android.annotation.FlaggedApi;
            import com.example.foobar.Flags;

            @FlaggedApi(Flags.FLAG_FOOBAR)
            public class MyApi2 {
              public void apiMethod(MyApi api) {
                  api.apiMethod(); // OK
              }
            }
            """
          )
          .indented(),
        java(
            """
            package test.api;
            import android.annotation.FlaggedApi;
            import com.example.foobar.Flags;

            @FlaggedApi(Flags.FLAG_UNRELATED)
            public class Test {
              public void apiMethod(MyApi api) {
                  api.apiMethod(); // ERROR: Flagged, but different API so still an error
              }
            }
            """
          )
          .indented(),
        // Generated
        java(
            """
            package com.example.foobar;

            public class Flags {
                public static final String FLAG_FOOBAR = "foobar";
                public static final String FLAG_UNRELATED = "unrelated";
                public static boolean foobar() { return true; }
                public static boolean unrelated() { return true; }
            }
            """
          )
          .indented(),
        flaggedApiAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/api/Test.java:8: Error: Method apiMethod() is a flagged API and should be inside an if (Flags.foobar()) check (or annotate the surrounding method apiMethod with @FlaggedApi(Flags.FLAG_FOOBAR) to transfer requirement to caller) [FlaggedApi]
              api.apiMethod(); // ERROR: Flagged, but different API so still an error
              ~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  fun testBasic() {
    // Test case from b/303434307#comment2
    lint()
      .files(
        java(
          """
          package test.pkg;

          public final class Flags {
              public static final String FLAG_MY_FLAG = "myFlag";
              public static boolean myFlag() { return true; }
          }
          """
        ),
        java(
            """
            package test.pkg;

            import android.annotation.FlaggedApi;

            public class JavaTest {
                @FlaggedApi(Flags.FLAG_MY_FLAG)
                class Foo {
                    public void someMethod() { }
                }

                public void testValid1() {
                    if (Flags.myFlag()) {
                        Foo f = new Foo(); // OK 1
                        f.someMethod();    // OK 2
                    }
                }
            }
            """
          )
          .indented(),
        flaggedApiAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testInterprocedural() {
    // Test case from b/303434307#comment2
    lint()
      .files(
        java(
          """
          package test.pkg;

          public final class Flags {
              public static final String FLAG_MY_FLAG = "myFlag";
              public static boolean myFlag() { return true; }
          }
          """
        ),
        java(
            """
            package test.pkg;

            import android.annotation.FlaggedApi;

            public class JavaTest {
                static class Foo {
                    @FlaggedApi(Flags.FLAG_MY_FLAG)
                    static void flaggedApi() {
                    }
                }

                void outer() {
                    if (Flags.myFlag()) {
                        inner();
                    }
                }

                void inner() {
                    // In theory valid because FLAG_MY_FLAG was checked earlier in the call-chain,
                    // but we don't do inter procedural analysis
                    Foo.flaggedApi(); // ERROR
                }
            }
            """
          )
          .indented(),
        flaggedApiAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/JavaTest.java:21: Error: Method flaggedApi() is a flagged API and should be inside an if (Flags.myFlag()) check (or annotate the surrounding method inner with @FlaggedApi(Flags.FLAG_MY_FLAG) to transfer requirement to caller) [FlaggedApi]
                Foo.flaggedApi(); // ERROR
                ~~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  fun testApiGating() {
    // Test case from b/303434307#comment2
    lint()
      .files(
        java(
          """
          package test.pkg;

          public final class Flags {
              public static final String FLAG_MY_FLAG = "myFlag";
              public static boolean myFlag() { return true; }
          }
          """
        ),
        java(
            """
            package test.pkg;

            import android.annotation.FlaggedApi;

            public class JavaTest {
                interface MyInterface {
                    void bar();
                }

                static class OldImpl implements MyInterface {
                    @Override
                    public void bar() {
                    }
                }

                @FlaggedApi(Flags.FLAG_MY_FLAG)
                static class NewImpl implements MyInterface {
                    @Override
                    public void bar() {
                    }
                 }

                 void test(MyInterface f) {
                     MyInterface obj = null;
                     if (Flags.myFlag()) {
                         obj = new NewImpl();
                     } else {
                         obj = new OldImpl();
                     }
                     f.bar();
                 }
            }
            """
          )
          .indented(),
        flaggedApiAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testFinalFields() {
    // Test case from b/303434307#comment2
    lint()
      .files(
        java(
          """
          package test.pkg;

          public final class Flags {
              public static final String FLAG_MY_FLAG = "myFlag";
              public static boolean myFlag() { return true; }
          }
          """
        ),
        java(
            """
            package test.pkg;

            import android.annotation.FlaggedApi;

            public class JavaTest {
                static class Bar {
                    @FlaggedApi(Flags.FLAG_MY_FLAG)
                    public void bar() { }
                }
                static class Foo {
                    private static final boolean useNewStuff = Flags.myFlag();
                    private final Bar mBar = new Bar();

                    void someMethod() {
                        if (useNewStuff) {
                            // OK because flags can't change value without a reboot, though this might change in
                            // the future and in that case caching the flag value would be an error. We can restart
                            // apps due to a server push of new flag values but restarting the framework would be
                            // too disruptive
                            mBar.bar(); // OK
                        }
                    }
                }
            }
            """
          )
          .indented(),
        flaggedApiAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testInverseLogic() {
    lint()
      .files(
        java(
          """
          package test.pkg;

          public final class Flags {
              public static final String FLAG_MY_FLAG = "myFlag";
              public static boolean myFlag() { return true; }
          }
          """
        ),
        java(
            """
            package test.pkg;

            import android.annotation.FlaggedApi;

            public class JavaTest {
                @FlaggedApi(Flags.FLAG_MY_FLAG)
                class Foo {
                    public void someMethod() { }
                }

                public void testInverse() {
                    if (!Flags.myFlag()) {
                        // ...
                    } else {
                        Foo f = new Foo(); // OK 1
                        f.someMethod();    // OK 2
                    }
                }
            }
            """
          )
          .indented(),
        flaggedApiAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testAnded() {
    lint()
      .files(
        java(
          """
          package test.pkg;

          public final class Flags {
              public static final String FLAG_MY_FLAG = "myFlag";
              public static boolean myFlag() { return true; }
          }
          """
        ),
        java(
            """
            package test.pkg;

            import android.annotation.FlaggedApi;
            import static test.pkg.Flags.myFlag;

            /** @noinspection InstantiationOfUtilityClass, AccessStaticViaInstance , ResultOfMethodCallIgnored , StatementWithEmptyBody */
            public class JavaTest {
                @FlaggedApi(Flags.FLAG_MY_FLAG)
                public static class Foo {
                    public static boolean someMethod() { return true; }
                }

                public void testValid1(boolean something) {
                    if (true && something && Flags.myFlag()) {
                        Foo f = new Foo(); // OK 1
                        f.someMethod();    // OK 2
                    }
                }

                public void testValid2(boolean something) {
                    if (something || !Flags.myFlag()) {
                    } else {
                        Foo f = new Foo(); // OK 3
                        f.someMethod();    // OK 4
                    }
                }

                public void testValid3(Foo f, boolean something) {
                    // b/b/383061307
                    if (Flags.myFlag() && f.someMethod()) { // OK 5
                    }
                    if (myFlag() && f.someMethod()) { // OK 6
                    }
                    if (Flags.myFlag() && something && f.someMethod()) { // OK 7
                    }
                }
            }
            """
          )
          .indented(),
        flaggedApiAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testEarlyReturns() {
    lint()
      .files(
        java(
          """
          package test.pkg;

          public final class Flags {
              public static final String FLAG_MY_FLAG = "myFlag";
              public static boolean myFlag() { return true; }
          }
          """
        ),
        java(
            """
            package test.pkg;

            import android.annotation.FlaggedApi;

            public class JavaTest {
                @FlaggedApi(Flags.FLAG_MY_FLAG)
                class Foo {
                    public void someMethod() { }
                }

                public void testSimpleEarlyReturn() {
                    if (!Flags.myFlag()) {
                        return;
                    }
                    Foo f = new Foo(); // OK 1
                    f.someMethod();    // OK 2
                }

                public void testEarlyReturn() {
                    int log;
                    {
                        if (!Flags.myFlag()) {
                            return;
                        }
                    }
                    // These are fine -- but we don't do more complex
                    // flow analysis here as in the SDK_INT version checker
                    // here, we only check very simple scenarios
                    Foo f = new Foo(); // ERROR 1
                    f.someMethod();    // ERROR 2
                }
            }
            """
          )
          .indented(),
        flaggedApiAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/JavaTest.java:29: Error: Method null() is a flagged API and should be inside an if (Flags.myFlag()) check (or annotate the surrounding method testEarlyReturn with @FlaggedApi(Flags.FLAG_MY_FLAG) to transfer requirement to caller) [FlaggedApi]
                Foo f = new Foo(); // ERROR 1
                        ~~~~~~~~~
        src/test/pkg/JavaTest.java:30: Error: Method someMethod() is a flagged API and should be inside an if (Flags.myFlag()) check (or annotate the surrounding method testEarlyReturn with @FlaggedApi(Flags.FLAG_MY_FLAG) to transfer requirement to caller) [FlaggedApi]
                f.someMethod();    // ERROR 2
                ~~~~~~~~~~~~~~
        2 errors, 0 warnings
        """
      )
  }

  fun testIgnoringStringFlags() {
    lint()
      .files(
        java(
            """
            package test.pkg;

            import android.annotation.FlaggedApi;

            public class JavaTest {
                @SuppressWarnings("FlaggedApi") // Don't warn about deprecation of raw strings here
                @FlaggedApi("flag.package.flag.name")
                class Foo {
                    public void someMethod() { }
                }

                public void testValid1(boolean something) {
                    f.someMethod();    // OK: String flags are ignored for now
                }
            }
            """
          )
          .indented(),
        flaggedApiAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testAnnotations() {
    lint()
      .files(
        java(
            """
            package test.pkg;

            import android.annotation.FlaggedApi;

            @FlaggedApi("test.pkg.FLAG_MY_FLAG")
            public class JavaTest {
                @FlaggedApi("FLAG_MY_FLAG")
                class Foo {
                    public void someMethod() { }
                }
            }
            """
          )
          .indented(),
        flaggedApiAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/JavaTest.java:7: Error: Invalid @FlaggedApi descriptor; should be package.name [FlaggedApi]
            @FlaggedApi("FLAG_MY_FLAG")
                        ~~~~~~~~~~~~~~
        src/test/pkg/JavaTest.java:5: Warning: @FlaggedApi should specify an actual flag constant; raw strings are discouraged (and more importantly, not enforced) [FlaggedApi]
        @FlaggedApi("test.pkg.FLAG_MY_FLAG")
                    ~~~~~~~~~~~~~~~~~~~~~~~
        1 errors, 1 warnings
        """
      )
  }

  fun testTypedefs() {
    // Test case for b/316198280 -- don't flag Typedef references
    lint()
      .files(
        java(
          """
          package test.pkg;

          public final class Flags {
              public static final String FLAG_MY_FLAG = "myFlag";
              public static boolean myFlag() { return true; }
          }
          """
        ),
        java(
            """
            package test.pkg;
            import android.annotation.FlaggedApi;
            @FlaggedApi(Flags.FLAG_MY_FLAG)
            public final class Constants {
              public static final int MY_INT_CONSTANT = 1;
              public static final int MY_LONG_CONSTANT = 1L;
              public static final int MY_STRING_CONSTANT = "1";
            }
            """
          )
          .indented(),
        kotlin(
          """
          package test.pkg
          import androidx.annotation.IntDef
          import androidx.annotation.LongDef
          import androidx.annotation.StringDef
          import test.pkg.Constants.MY_INT_CONSTANT
          import test.pkg.Constants.MY_LONG_CONSTANT
          import test.pkg.Constants.MY_STRING_CONSTANT

          @IntDef(MY_INT_CONSTANT)
          @Retention(AnnotationRetention.SOURCE)
          annotation class MyKotlinTypeDe1

          @StringDef(Constants.MY_STRING_CONSTANT)
          @Retention(AnnotationRetention.SOURCE)
          annotation class MyKotlinTypeDef2

          @LongDef(MY_LONG_CONSTANT)
          @Retention(AnnotationRetention.SOURCE)
          annotation class MyKotlinTypeDef3
          """
        ),
        java(
            """
            package test.pkg;
            import androidx.annotation.IntDef;
            import test.pkg.Constants.MY_INT_CONSTANT;

            public class JavaTest {
                @IntDef({
                    STATUS_AVAILABLE, MY_INT_CONSTANT, STATUS_UNAVAILABLE
                })
                @Retention(RetentionPolicy.SOURCE)
                public @interface Status {
                }
                public static final int STATUS_AVAILABLE = 1;
                public static final int STATUS_UNAVAILABLE = 3;
            }
            """
          )
          .indented(),
        flaggedApiAnnotationStub,
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expectClean()
  }

  fun testUsingCommandLineFlag() {
    // Ensure that the --include-aosp-issues flag pulls this check in
    // (and that without it, it's not included)
    val project =
      getProjectDir(
        null,
        java(
            """
            package test.api;
            import android.annotation.FlaggedApi;
            import com.example.foobar.Flags;

            @FlaggedApi(Flags.FLAG_FOOBAR)
            public class MyApi {
              public void apiMethod() { }
              public int apiField = 42;
            }
            """
          )
          .indented(),
        java(
            """
            package test.pkg;
            import test.api.MyApi;
            import com.example.foobar.Flags;

            public class Test {
              public void test(MyApi api) {
                if (Flags.foobar()) {
                  api.apiMethod(); // OK
                  int val = api.apiField; // OK
                }
                api.apiMethod(); // ERROR 1
                int val = api.apiField; // ERROR 2
                Object o = MyApi.class; // ERROR 3
              }
            }
            """
          )
          .indented(),
        // Generated
        java(
            """
            package com.example.foobar;

            public class Flags {
                public static final String FLAG_FOOBAR = "foobar";
                public static boolean foobar() { return true; }
            }
            """
          )
          .indented(),
        flaggedApiAnnotationStub,
      )

    // No warnings by default
    MainTest.checkDriver(
      "No issues found.",
      "",
      // Expected exit code
      LintCliFlags.ERRNO_SUCCESS,
      arrayOf("-q", "--check", "FlaggedApi", "--disable", "LintError", project.path),
      null,
      null,
    )

    // No warnings by default
    MainTest.checkDriver(
      """
      src/test/pkg/Test.java:11: Error: Method apiMethod() is a flagged API and should be inside an if (Flags.foobar()) check (or annotate the surrounding method test with @FlaggedApi(Flags.FLAG_FOOBAR) to transfer requirement to caller) [FlaggedApi]
          api.apiMethod(); // ERROR 1
          ~~~~~~~~~~~~~~~
      src/test/pkg/Test.java:12: Error: Field apiField is a flagged API and should be inside an if (Flags.foobar()) check (or annotate the surrounding method test with @FlaggedApi(Flags.FLAG_FOOBAR) to transfer requirement to caller) [FlaggedApi]
          int val = api.apiField; // ERROR 2
                        ~~~~~~~~
      src/test/pkg/Test.java:13: Error: Class MyApi is a flagged API and should be inside an if (Flags.foobar()) check (or annotate the surrounding method test with @FlaggedApi(Flags.FLAG_FOOBAR) to transfer requirement to caller) [FlaggedApi]
          Object o = MyApi.class; // ERROR 3
                     ~~~~~~~~~~~
      3 errors
      """
        .trimIndent(),
      "",
      // Expected exit code
      LintCliFlags.ERRNO_ERRORS,
      arrayOf(
        "--include-aosp-issues",
        "--exit-code",
        "-q",
        "--check",
        "FlaggedApi",
        "--disable",
        "LintError",
        project.path,
      ),
      null,
      null,
    )

    // project.xml checks
    @Language("XML") val root = project
    val sdk = TestUtils.getSdk().toFile()
    val descriptor =
      """
        <project>
        <root dir="$root" />
        <sdk dir='$sdk'/>
        <module name="App:App" android="true">
          <manifest file="AndroidManifest.xml" />
          <src file="src/test/api/MyApi.java" />
          <src file="src/test/pkg/Test.java" />
          <src file="src/android/annotation/FlaggedApi.java" />
          <src file="src/com/example/foobar/Flags.java" />
        </module>
        </project>
        """
        .trimIndent()

    val projectXml = File(project, "project.xml")
    projectXml.writeText(descriptor)

    MainTest.checkDriver(
      """
      src/test/pkg/Test.java:11: Error: Method apiMethod() is a flagged API and should be inside an if (Flags.foobar()) check (or annotate the surrounding method test with @FlaggedApi(Flags.FLAG_FOOBAR) to transfer requirement to caller) [FlaggedApi]
          api.apiMethod(); // ERROR 1
          ~~~~~~~~~~~~~~~
      src/test/pkg/Test.java:12: Error: Field apiField is a flagged API and should be inside an if (Flags.foobar()) check (or annotate the surrounding method test with @FlaggedApi(Flags.FLAG_FOOBAR) to transfer requirement to caller) [FlaggedApi]
          int val = api.apiField; // ERROR 2
                        ~~~~~~~~
      src/test/pkg/Test.java:13: Error: Class MyApi is a flagged API and should be inside an if (Flags.foobar()) check (or annotate the surrounding method test with @FlaggedApi(Flags.FLAG_FOOBAR) to transfer requirement to caller) [FlaggedApi]
          Object o = MyApi.class; // ERROR 3
                     ~~~~~~~~~~~
      3 errors
      """
        .trimIndent(),
      "",
      // Expected exit code
      LintCliFlags.ERRNO_ERRORS,
      arrayOf(
        "--exit-code",
        "-q",
        "--check",
        "FlaggedApi",
        "--disable",
        "LintError",
        "--project",
        projectXml.path,
      ),
      null,
      null,
    )

    // Redundantly also add the --include-aosp-issues to verify that we don't duplicate the warnings
    MainTest.checkDriver(
      """
      src/test/pkg/Test.java:11: Error: Method apiMethod() is a flagged API and should be inside an if (Flags.foobar()) check (or annotate the surrounding method test with @FlaggedApi(Flags.FLAG_FOOBAR) to transfer requirement to caller) [FlaggedApi]
          api.apiMethod(); // ERROR 1
          ~~~~~~~~~~~~~~~
      src/test/pkg/Test.java:12: Error: Field apiField is a flagged API and should be inside an if (Flags.foobar()) check (or annotate the surrounding method test with @FlaggedApi(Flags.FLAG_FOOBAR) to transfer requirement to caller) [FlaggedApi]
          int val = api.apiField; // ERROR 2
                        ~~~~~~~~
      src/test/pkg/Test.java:13: Error: Class MyApi is a flagged API and should be inside an if (Flags.foobar()) check (or annotate the surrounding method test with @FlaggedApi(Flags.FLAG_FOOBAR) to transfer requirement to caller) [FlaggedApi]
          Object o = MyApi.class; // ERROR 3
                     ~~~~~~~~~~~
      3 errors
      """
        .trimIndent(),
      "",
      // Expected exit code
      LintCliFlags.ERRNO_ERRORS,
      arrayOf(
        "--exit-code",
        "--include-aosp-issues",
        "-q",
        "--check",
        "FlaggedApi",
        "--disable",
        "LintError",
        "--project",
        projectXml.path,
      ),
      null,
      null,
    )

    // Don't enable it if it's not an Android build
    projectXml.writeText(descriptor.replace("""android="true"""", """android="false""""))

    MainTest.checkDriver(
      """
      No issues found.
      """
        .trimIndent(),
      "",
      // Expected exit code
      LintCliFlags.ERRNO_SUCCESS,
      arrayOf(
        "--exit-code",
        "-q",
        "--check",
        "FlaggedApi",
        "--disable",
        "LintError",
        "--project",
        projectXml.path,
      ),
      null,
      null,
    )
  }

  fun testExportedFlags() {
    // Regression test for b/404565190
    lint()
      .files(
        java(
            """
            package test.api;
            import android.annotation.FlaggedApi;
            import com.example.foobar.Flags;

            @FlaggedApi(Flags.FLAG_FOOBAR)
            public class MyApi {
              public void apiMethod() { }
              public int apiField = 42;
            }
            """
          )
          .indented(),
        java(
            """
            package test.pkg;
            import test.api.MyApi;
            import com.example.foobar.ExportedFlags;

            public class Test {
              public void test(MyApi api) {
                if (ExportedFlags.foobar()) {
                  api.apiMethod(); // OK
                  int val = api.apiField; // OK
                }
                api.apiMethod(); // ERROR 1
                int val = api.apiField; // ERROR 2
                Object o = MyApi.class; // ERROR 3
              }
            }
            """
          )
          .indented(),
        // Generated
        java(
            """
            package com.example.foobar;

            public class Flags {
                public static final String FLAG_FOOBAR = "com.example.foobar.foobar";
                public static boolean foobar() { return true; }
            }
            """
          )
          .indented(),
        java(
            """
            package com.example.foobar;

            public class ExportedFlags {
                public static final String FLAG_FOOBAR = "com.example.foobar.foobar";
                public static boolean foobar() { return true; }
            }
            """
          )
          .indented(),
        flaggedApiAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:11: Error: Method apiMethod() is a flagged API and should be inside an if (Flags.foobar()) check (or annotate the surrounding method test with @FlaggedApi(Flags.FLAG_FOOBAR) to transfer requirement to caller) [FlaggedApi]
            api.apiMethod(); // ERROR 1
            ~~~~~~~~~~~~~~~
        src/test/pkg/Test.java:12: Error: Field apiField is a flagged API and should be inside an if (Flags.foobar()) check (or annotate the surrounding method test with @FlaggedApi(Flags.FLAG_FOOBAR) to transfer requirement to caller) [FlaggedApi]
            int val = api.apiField; // ERROR 2
                          ~~~~~~~~
        src/test/pkg/Test.java:13: Error: Class MyApi is a flagged API and should be inside an if (Flags.foobar()) check (or annotate the surrounding method test with @FlaggedApi(Flags.FLAG_FOOBAR) to transfer requirement to caller) [FlaggedApi]
            Object o = MyApi.class; // ERROR 3
                       ~~~~~~~~~~~
        3 errors
        """
      )
  }

  fun testFlaggedApiAppearsInApiVersionsXml() {
    // Regression test for b/437399045
    ApiLookupTest.runLintWithCustomLookup(
        """
        <api version="4">
          <class name="java/lang/Object" since="2">
            <method name="&lt;init>()V"/>
            <method name="equals(Ljava/lang/Object;)Z"/>
            <method name="hashCode()I"/>
            <method name="toString()Ljava/lang/String;"/>
          </class>
          <class name="test/api/FooManager" since="30">
            <extends name="java/lang/Object"/>
            <method name="&lt;init>()V"/>
            <method name="someMethod()V" since="35"/>
            <method name="newUnfinalizedMethod()V" since="10000"/>
            <method name="newUnflaggedUnfinalizedMethod()V" since="10000"/>
            <method name="newFinalizedMethod()V" since="37"/>
          </class>
        </api>
        """,
        false,
        {
          lint()
            .files(
              manifest().minSdk(30),
              // Use binary stub for the API file since lint's API check
              // ignores API elements found in source files
              binaryStub(
                "libs/api.jar",
                java(
                    """
                    package test.api;
                    import android.annotation.FlaggedApi;
                    import com.example.foobar.Flags;

                    class FooManager {
                       void someMethod();
                       @FlaggedApi(Flags.FLAG_FOOBAR)
                       void newUnfinalizedMethod();
                       @FlaggedApi(Flags.FLAG_FOOBAR)
                       void newFinalizedMethod();
                       void newUnflaggedUnfinalizedMethod();
                    }
                    """
                  )
                  .indented(),
                // Generated
                java(
                    """
                    package com.example.foobar;
                    import androidx.annotation.ChecksSdkIntAtLeast;

                    public class Flags {
                        public static final String FLAG_FOOBAR = "com.example.foobar.foobar";
                        @ChecksSdkIntAtLeast(api=37)
                        public static boolean foobar() { return true; }
                    }
                    """
                  )
                  .indented(),
                flaggedApiAnnotationStub,
                TestFiles.java(
                    """
                    package androidx.annotation;
                    import static java.lang.annotation.ElementType.FIELD;
                    import static java.lang.annotation.ElementType.METHOD;
                    import static java.lang.annotation.RetentionPolicy.CLASS;
                    import java.lang.annotation.Documented;
                    import java.lang.annotation.Retention;
                    import java.lang.annotation.Target;
                    @Documented
                    @Retention(CLASS)
                    @Target({METHOD, FIELD})
                    public @interface ChecksSdkIntAtLeast {
                        int api() default -1;
                        String codename() default "";
                        int parameter() default -1;
                        int lambda() default -1;
                        int extension() default 0;
                    }
                    """
                  )
                  .indented(),
              ),
              // Usage
              java(
                  """
                  package test.pkg;
                  import test.api.FooManager;
                  import com.example.foobar.Flags;
                  import android.os.Build;

                  public class Test {
                    public void test(FooManager fooManager) {
                      // Flagged API not yet finalized: should *only* be reported as FlaggedApi:
                      fooManager.newUnfinalizedMethod(); // ERROR 1 (FlaggedApi)

                      if (Flags.foobar()) {
                        fooManager.newUnfinalizedMethod(); // OK 1: properly checked by flag
                        fooManager.newFinalizedMethod(); // OK 2, thanks to @ChecksSdkIntAtLeast(37)
                      }

                      if (Build.VERSION.SDK_INT >= 37) {
                        fooManager.someMethod(); // OK 3
                        fooManager.newFinalizedMethod(); // OK 4
                        // Unfinalized API: should only be reported as FlaggedApi
                        fooManager.newUnfinalizedMethod(); // ERROR 2 (FlaggedAPi)
                      }

                      // Finalized API; should only be reported as NewApi
                      fooManager.newFinalizedMethod(); // ERROR 3 (NewApi)
                      // Not flagged API (just flagged by NewApi)
                      fooManager.someMethod(); // ERROR 4 (NewApi)

                      fooManager.newUnflaggedUnfinalizedMethod(); // ERROR 5
                      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CUR_DEVELOPMENT) {
                        fooManager.newUnflaggedUnfinalizedMethod(); // OK 5
                      }
                    }
                  }
                  """
                )
                .indented(),
              flaggedApiAnnotationStub,
            )
        },
        FlaggedApiDetector.ISSUE,
        ApiDetector.UNSUPPORTED,
        ApiDetector.INLINED,
        ApiDetector.OBSOLETE_SDK,
      )
      .expect(
        """
        src/test/pkg/Test.java:9: Error: Method newUnfinalizedMethod() is a flagged API and should be inside an if (Flags.foobar()) check (or annotate the surrounding method test with @FlaggedApi(Flags.FLAG_FOOBAR) to transfer requirement to caller) [FlaggedApi]
            fooManager.newUnfinalizedMethod(); // ERROR 1 (FlaggedApi)
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/Test.java:20: Error: Method newUnfinalizedMethod() is a flagged API and should be inside an if (Flags.foobar()) check (or annotate the surrounding method test with @FlaggedApi(Flags.FLAG_FOOBAR) to transfer requirement to caller) [FlaggedApi]
              fooManager.newUnfinalizedMethod(); // ERROR 2 (FlaggedAPi)
              ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/Test.java:24: Error: Call requires API level 37 (current min is 30): test.api.FooManager#newFinalizedMethod [NewApi]
            fooManager.newFinalizedMethod(); // ERROR 3 (NewApi)
                       ~~~~~~~~~~~~~~~~~~
        src/test/pkg/Test.java:26: Error: Call requires API level 35 (current min is 30): test.api.FooManager#someMethod [NewApi]
            fooManager.someMethod(); // ERROR 4 (NewApi)
                       ~~~~~~~~~~
        src/test/pkg/Test.java:28: Error: Call requires API level CUR_DEVELOPMENT/10000 (current min is 30): test.api.FooManager#newUnflaggedUnfinalizedMethod [NewApi]
            fooManager.newUnflaggedUnfinalizedMethod(); // ERROR 5
                       ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        5 errors
        """
      )
  }
}

private val flaggedApiAnnotationStub: TestFile =
  java(
      """
      package android.annotation; // HIDE-FROM-DOCUMENTATION

      import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
      import static java.lang.annotation.ElementType.CONSTRUCTOR;
      import static java.lang.annotation.ElementType.FIELD;
      import static java.lang.annotation.ElementType.METHOD;
      import static java.lang.annotation.ElementType.TYPE;

      import java.lang.annotation.Retention;
      import java.lang.annotation.RetentionPolicy;
      import java.lang.annotation.Target;

      @Target({TYPE, METHOD, CONSTRUCTOR, FIELD, ANNOTATION_TYPE})
      @Retention(RetentionPolicy.CLASS)
      public @interface FlaggedApi {
          String value();
      }
      """
    )
    .indented()
