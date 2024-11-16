/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.build.gradle.integration.common.fixture.project.prebuilts

import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.BaseGradleProjectDefinition
import com.android.testutils.TestUtils.KOTLIN_VERSION_FOR_TESTS

/**
 * Utility methods to create basic Android project content into existing [GradleProject] instances.
 */
class HelloWorldAndroid {
    companion object {
        fun setupJava(project: AndroidProjectFiles) {
            project.apply {
                add(
                    "src/main/java/$namespaceAsPath/HelloWorld.java",
                    // language=java
                    """
                        package $namespace;

                        import android.app.Activity;
                        import android.os.Bundle;

                        public class HelloWorld extends Activity {
                            /** Called when the activity is first created. */
                            @Override
                            public void onCreate(Bundle savedInstanceState) {
                                super.onCreate(savedInstanceState);
                                setContentView(R.layout.main);
                            }
                        }
                    """.trimIndent()
                )

                add(
                    "src/main/res/values/strings.xml",
                    // language=xml
                    """
                        <?xml version="1.0" encoding="utf-8"?>
                        <resources>
                            <string name="app_name">HelloWorld</string>
                        </resources>
                    """.trimIndent()
                )

                add(
                    "src/main/res/layout/main.xml",
                    // language=xml
                    """
                        <?xml version="1.0" encoding="utf-8"?>
                        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                            android:orientation="vertical"
                            android:layout_width="fill_parent"
                            android:layout_height="fill_parent"
                            >
                        <TextView
                            android:layout_width="fill_parent"
                            android:layout_height="wrap_content"
                            android:text="hello world!"
                            android:id="@+id/text"
                            />
                        </LinearLayout>
                    """.trimIndent()
                )

                add(
                    "src/main/AndroidManifest.xml",
                    // language=xml
                    """
                        <?xml version="1.0" encoding="utf-8"?>
                        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                              android:versionCode="1"
                              android:versionName="1.0">
                            <application android:label="@string/app_name">
                                <activity android:name=".HelloWorld"
                                          android:exported="true"
                                          android:label="@string/app_name">
                                    <intent-filter>
                                        <action android:name="android.intent.action.MAIN" />
                                        <category android:name="android.intent.category.LAUNCHER" />
                                    </intent-filter>
                                </activity>
                            </application>
                        </manifest>
                    """.trimIndent()
                )
            }
        }

        fun setupKotlin(project: AndroidProjectFiles) {
            project.apply {
                add(
                    "src/main/java/$namespaceAsPath/HelloWorld.kt",
                    // language=kotlin
                    """
                        package $namespace

                        import android.app.Activity
                        import android.os.Bundle

                        class HelloWorld: Activity() {
                            /** Called when the activity is first created. */
                            override fun onCreate(savedInstanceState: Bundle?) {
                                super.onCreate(savedInstanceState)
                                setContentView(R.layout.main)
                            }
                        }
                    """.trimIndent()
                )

                add(
                    "src/main/res/values/strings.xml",
                    // language=xml
                    """
                        <?xml version="1.0" encoding="utf-8"?>
                        <resources>
                            <string name="app_name">HelloWorld</string>
                        </resources>
                    """.trimIndent()
                )

                add(
                    "src/main/res/layout/main.xml",
                    // language=xml
                    """
                        <?xml version="1.0" encoding="utf-8"?>
                        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                            android:orientation="vertical"
                            android:layout_width="fill_parent"
                            android:layout_height="fill_parent"
                            >
                        <TextView
                            android:layout_width="fill_parent"
                            android:layout_height="wrap_content"
                            android:text="hello world!"
                            android:id="@+id/text"
                            />
                        </LinearLayout>
                    """.trimIndent()
                )

                add(
                    "src/main/AndroidManifest.xml",
                    // language=xml
                    """
                        <?xml version="1.0" encoding="utf-8"?>
                        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                              android:versionCode="1"
                              android:versionName="1.0">
                            <application android:label="@string/app_name">
                                <activity android:name=".HelloWorld"
                                          android:exported="true"
                                          android:label="@string/app_name">
                                    <intent-filter>
                                        <action android:name="android.intent.action.MAIN" />
                                        <category android:name="android.intent.category.LAUNCHER" />
                                    </intent-filter>
                                </activity>
                            </application>
                        </manifest>
                    """.trimIndent()
                )
            }
        }

        fun setupKotlinDependencies(project: BaseGradleProjectDefinition) {
            project.dependencies {
                api("org.jetbrains.kotlin:kotlin-stdlib:$KOTLIN_VERSION_FOR_TESTS")
                androidTestImplementation("org.jetbrains.kotlin:kotlin-stdlib:$KOTLIN_VERSION_FOR_TESTS")
                testImplementation("org.jetbrains.kotlin:kotlin-stdlib:$KOTLIN_VERSION_FOR_TESTS")
            }
        }
    }
}
