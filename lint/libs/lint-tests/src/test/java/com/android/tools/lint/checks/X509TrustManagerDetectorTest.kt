/*
 * Copyright (C) 2015 The Android Open Source Project
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

class X509TrustManagerDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector {
    return X509TrustManagerDetector()
  }

  fun testTrustsAll() {
    lint()
        .files(
            manifest(
                    """

                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="test.pkg"
                    android:versionCode="1"
                    android:versionName="1.0" >

                    <uses-sdk android:minSdkVersion="14" />

                    <application
                        android:icon="@drawable/ic_launcher"
                        android:label="@string/app_name" >
                        <service
                            android:name=".InsecureTLSIntentService" >
                        </service>
                    </application>

                </manifest>

                """
                )
                .indented(),
            java(
                    """
                package test.pkg;

                import android.app.IntentService;
                import android.content.Intent;

                import java.security.GeneralSecurityException;
                import java.security.cert.CertificateException;

                import javax.net.ssl.HttpsURLConnection;
                import javax.net.ssl.SSLContext;
                import javax.net.ssl.TrustManager;
                import javax.net.ssl.X509TrustManager;

                public class InsecureTLSIntentService extends IntentService {
                    TrustManager[] trustAllCerts = new TrustManager[]{ new X509TrustManager() {
                        @Override
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }

                        @Override
                        public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) throws CertificateException {
                        }
                    }};

                    public InsecureTLSIntentService() {
                        super("InsecureTLSIntentService");
                    }

                    @Override
                    protected void onHandleIntent(Intent intent) {
                        try {
                            SSLContext sc = SSLContext.getInstance("TLSv1.2");
                            sc.init(null, trustAllCerts, new java.security.SecureRandom());
                            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
                        } catch (GeneralSecurityException e) {
                            System.out.println(e.getStackTrace());
                        }
                    }
                }
                """
                )
                .indented(),
        )
        .run()
        .expect(
            """
            src/test/pkg/InsecureTLSIntentService.java:22: Warning: checkClientTrusted is empty, which could cause insecure network traffic due to trusting arbitrary TLS/SSL certificates presented by peers [TrustAllX509TrustManager]
                    public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {
                                ~~~~~~~~~~~~~~~~~~
            src/test/pkg/InsecureTLSIntentService.java:26: Warning: checkServerTrusted is empty, which could cause insecure network traffic due to trusting arbitrary TLS/SSL certificates presented by peers [TrustAllX509TrustManager]
                    public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) throws CertificateException {
                                ~~~~~~~~~~~~~~~~~~
            src/test/pkg/InsecureTLSIntentService.java:15: Warning: Implementing a custom X509TrustManager is error-prone and likely to be insecure. It is likely to disable certificate validation altogether, and is non-trivial to implement correctly without calling Android's default implementation. [CustomX509TrustManager]
                TrustManager[] trustAllCerts = new TrustManager[]{ new X509TrustManager() {
                                                                       ~~~~~~~~~~~~~~~~
            0 errors, 3 warnings
            """
        )

    // TODO: Test bytecode check via library jar?
    // "bytecode/InsecureTLSIntentService.java.txt=>src/test/pkg/InsecureTLSIntentService.java",
    // "bytecode/InsecureTLSIntentService.class.data=>bin/classes/test/pkg/InsecureTLSIntentService.class",
    // "bytecode/InsecureTLSIntentService$1.class.data=>bin/classes/test/pkg/InsecureTLSIntentService$1.class"));
  }

  fun testCustom() {
    lint()
        .files(
            manifest(
                    """

                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="test.pkg"
                    android:versionCode="1"
                    android:versionName="1.0" >

                    <uses-sdk android:minSdkVersion="14" />

                    <application
                        android:icon="@drawable/ic_launcher"
                        android:label="@string/app_name" >
                        <service
                            android:name=".ExampleTLSIntentService" >
                        </service>
                    </application>

                </manifest>
                """
                )
                .indented(),
            java(
                    """
                package test.pkg;

                import android.app.IntentService;
                import android.content.Intent;

                import java.io.BufferedInputStream;
                import java.io.FileInputStream;
                import java.security.GeneralSecurityException;
                import java.security.cert.CertificateException;
                import java.security.cert.CertificateFactory;
                import java.security.cert.X509Certificate;

                import javax.net.ssl.HttpsURLConnection;
                import javax.net.ssl.SSLContext;
                import javax.net.ssl.TrustManager;
                import javax.net.ssl.X509TrustManager;

                public class ExampleTLSIntentService extends IntentService {
                    TrustManager[] trustManagerExample;

                    {
                        trustManagerExample = new TrustManager[]{new X509TrustManager() {
                            @Override
                            public X509Certificate[] getAcceptedIssuers() {
                                try {
                                    FileInputStream fis = new FileInputStream("testcert.pem");
                                    BufferedInputStream bis = new BufferedInputStream(fis);
                                    CertificateFactory cf = CertificateFactory.getInstance("X.509");
                                    X509Certificate cert = (X509Certificate) cf.generateCertificate(bis);
                                    return new X509Certificate[]{cert};
                                } catch (Exception e) {
                                    throw new RuntimeException("Could not load cert");
                                }
                            }

                            @Override
                            public void checkClientTrusted(X509Certificate[] certs, String authType) throws CertificateException {
                                throw new CertificateException("Not trusted");
                            }

                            @Override
                            public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException {
                                throw new CertificateException("Not trusted");
                            }
                        }};
                    }

                    public ExampleTLSIntentService() {
                        super("ExampleTLSIntentService");
                    }

                    @Override
                    protected void onHandleIntent(Intent intent) {
                        try {
                            SSLContext sc = SSLContext.getInstance("TLSv1.2");
                            sc.init(null, trustManagerExample, new java.security.SecureRandom());
                            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
                        } catch (GeneralSecurityException e) {
                            System.out.println(e.getStackTrace());
                        }
                    }
                }
                """
                )
                .indented(),
        )
        .run()
        .expect(
            """
            src/test/pkg/ExampleTLSIntentService.java:22: Warning: Implementing a custom X509TrustManager is error-prone and likely to be insecure. It is likely to disable certificate validation altogether, and is non-trivial to implement correctly without calling Android's default implementation. [CustomX509TrustManager]
                    trustManagerExample = new TrustManager[]{new X509TrustManager() {
                                                                 ~~~~~~~~~~~~~~~~
            0 errors, 1 warnings
            """
        )
  }

  @Suppress("removal")
  fun testInterfaceMethods() {
    // Regression test for
    // https://issuetracker.google.com/270065082
    lint()
        .files(
            compiled(
                "libs/library.jar",
                java(
                        """
              package test.pkg;

              import java.util.List;

              import javax.net.ssl.X509TrustManager;
              import javax.security.cert.CertificateException;
              import javax.security.cert.X509Certificate;

              public interface ExtendedX509TrustManager extends X509TrustManager {
                  List<X509Certificate> checkServerTrusted(X509Certificate[] chain, String authType, String host)
                          throws CertificateException;
              }
              """
                    )
                    .indented(),
                0x2a622729,
                """
            test/pkg/ExtendedX509TrustManager.class:
            H4sIAAAAAAAA/7WRP0sDQRDF38SYM/FfsFewi41rIyKRNBKriMVZCNqsm/Hc
            5NiE3blw1n4rCz+AH0rcS0AFI9hYDLx5vJn9Mfv2/vIK4ATtBBsJNhNsEXbM
            I5txyn7G/toXQXhIuOvcDkZ6pksV2BTeypMy7EXdHB+dnkdhH6zRwt15SOXa
            ZSoVb122xDlYWIXYXA1skC6h1S8NT8VOXEiwTWimNnNaCs+E5398+uxPm3sV
            YTopvOELm0ek3X4p7IY8rJLzG11qpzP2h9U6wr5wEDUdZ+q3IKH9BXd1P2Ij
            hL0FjGNRIeTq50xnGe030s8rNgiEWqyV+L1UJ9SxGvtG7OrRTbAWVQ3NqFtz
            tf4BCHqzYQsCAAA=
            """,
            )
        )
        .issues(X509TrustManagerDetector.TRUSTS_ALL)
        .run()
        .expectClean()
  }

  @Suppress("RedundantThrows")
  fun testBytecodeSuppress() {
    lint()
        .files(
            bytecode(
                "libs/library.jar",
                java(
                        """
              package test.pkg;

              import javax.net.ssl.X509TrustManager;
              import java.security.cert.CertificateException;
              import java.security.cert.X509Certificate;

              public class MyTestX509TrustManager implements X509TrustManager {

                  @SuppressLint("TrustAllX509TrustManager")
                  @Override
                  public void checkClientTrusted(X509Certificate[] chain, String authType)
                      throws CertificateException {}

                  @SuppressLint("TrustAllX509TrustManager")
                  @Override
                  public void checkServerTrusted(X509Certificate[] chain, String authType)
                      throws CertificateException {}

                  @Override
                  public X509Certificate[] getAcceptedIssuers() {
                    return null;
                  }
              }
              """
                    )
                    .indented(),
                0x6b722ea6,
                """
          test/pkg/MyTestX509TrustManager.class:
          H4sIAAAAAAAA/41STW8TMRB9k6+FEPqRAi2NAPXUhAPLhQMEIUURSJVSkEhU
          IfXkOMPidutEtjdq/1U5VeqBH8CPQswuoaBQqRw882w9v5nn8fcfl98A9NCq
          o4RyhEoDVdQIa0dqruJU2ST+MD5iHQi118aa8IZQbncOItwiPAnsQzw7TuL9
          s5HATy+evxy5zId9ZVXCLkKd8DhXOo0th9j7NF7mECr96YQJqwNj+X12MmY3
          UuNUTpr6C+vjfmrYhuIKTwiv2oeDojfPOnMmnMWaXShk+wLMZ6NV4O7gT//D
          4IxNup0DQv3tqeZZMFPrI6wTdq9R+kvlik1ofcxsMCe8Z+fGG2mvZ+00qEKK
          sDm4eolhNps59l7chC6hOldpJl62CgO9NP3X/y+bQ3Zzcf7bZjPh0NN5eZ7s
          eZ+xkzK77c5/mRejw2nmNL8z+Tu2rp/Os1wJO4hk8oTb8g0qkmX6Eu/I7pFk
          klx9egH6KoDQkFgrDiOJd7GCckF9KKucM5ZpDawudNfQFLQiaEPWvUOQx31B
          D24WWb9RZBNbi6a3F02X6HxJJeflJUrY/glTTopv9gIAAA==
          """,
            ),
            bytecode(
                "libs/library.jar",
                java(
                        """
              package test.pkg;

              import static java.lang.annotation.ElementType.CONSTRUCTOR;
              import static java.lang.annotation.ElementType.FIELD;
              import static java.lang.annotation.ElementType.LOCAL_VARIABLE;
              import static java.lang.annotation.ElementType.METHOD;
              import static java.lang.annotation.ElementType.PARAMETER;
              import static java.lang.annotation.ElementType.TYPE;

              import java.lang.annotation.Retention;
              import java.lang.annotation.RetentionPolicy;
              import java.lang.annotation.Target;

              @Target({TYPE, FIELD, METHOD, PARAMETER, CONSTRUCTOR, LOCAL_VARIABLE})
              @Retention(RetentionPolicy.CLASS)
              public @interface SuppressLint {
                  String[] value();
              }
              """
                    )
                    .indented(),
                0xecef59c8,
                """
          test/pkg/SuppressLint.class:
          H4sIAAAAAAAA/4WRQU/CMBTHXxGYgCKoaDwYjQeiF3fx5qnOEUmqI9skMRzM
          IC9LcXTL1pHw1Tz4AfxQxlcP4oHEQ//9v/bX92/az6/3DwDgcGxBhUFPY6Ht
          7C22gzLLciwKIZW2oMqgM4+WkZ1EKra96RxntFpncLZejZRKdaRlqmz+axnU
          llFSIrW+vJqINR3oXKr4lkEzSMt8hgOZENT9G3ttaAYnfqm0XOBYFnKa4Lp3
          weBUbMwPozxGTc0vNu+7CS5Q6XCVIUHV8GXk0kUHQ1fcM6g/uuGDR6Yx4j6n
          wvUZtBzvKQj9Zyf0qGoLz+Hidcz9Ib8TdPZ8c46PmmLIUUr/H2SUJnK2IrDm
          CB4EfQYMtmjU6HsYvb8F2+Qq0PjRJrRoviG3Q4w1gTrCLrSN7BnpGOka2Tdy
          AIcGQ+jB0Td2Z2BM8wEAAA==
          """,
            ),
        )
        .issues(X509TrustManagerDetector.TRUSTS_ALL)
        .skipTestModes(TestMode.PARTIAL)
        .run()
        .expectClean()
  }
}
