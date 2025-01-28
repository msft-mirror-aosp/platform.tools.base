/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.build.gradle.integration.databinding

import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.options.BooleanOption
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KaptGenerateStubs
import org.junit.Rule
import org.junit.Test

/** Checks that Data Binding works in KMP projects. */
class DataBindingKmpTest {
    @get:Rule
    val rule = GradleRule.configure()
        .withGradleOptions {
            // this is necessary because KMP does not work with project Isolation.
            // There were some tests where it worked but that's because they used the root
            // project, and it's fine in the root (the KMP plugin accesses things in the root
            // folder so if it's already there it's fine)
            withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
        }.from {
            androidLibrary(":app", createMinimumProject = false) {
                applyPlugin(PluginType.KOTLIN_MPP)
                applyPlugin(PluginType.KAPT)
                pluginCallbacks += Callback::class.java
                android {
                    compileSdk = DEFAULT_COMPILE_SDK_VERSION
                    namespace = "com.example.app"
                    buildFeatures {
                        dataBinding = true
                    }
                }
                dependencies {
                    implementation(project(":lib"))
                }
                files {
                    setupMinimumManifest()
                    add(
                        "src/main/java/com/example/app/MainActivity.java",
                        //language=java
                        """
                            package com.example.app;

                            import android.os.Bundle;
                            import android.app.Activity;
                            import androidx.databinding.DataBindingUtil;
                            import com.example.lib.LibData;
                            import com.example.app.databinding.ActivityMainBinding;

                            public class MainActivity extends Activity {

                                @Override
                                protected void onCreate(Bundle savedInstanceState) {
                                    super.onCreate(savedInstanceState);

                                    ActivityMainBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_main);
                                    binding.setLibData(new LibData());
                                }
                            }
                        """.trimIndent()
                    )
                    add(
                        "src/main/res/layout/activity_main.xml",
                        //language=xml
                        """
                            <?xml version="1.0" encoding="utf-8"?>
                            <layout xmlns:android="http://schemas.android.com/apk/res/android">
                                <data>
                                    <variable name="libData" type="com.example.lib.LibData" />
                                </data>
                                <TextView
                                    android:layout_width="wrap_content"
                                    android:layout_height="wrap_content"
                                    android:dataFromLib="@{libData}" />
                            </layout>
                        """.trimIndent()
                    )                }
            }
            androidLibrary(":lib", createMinimumProject = false) {
                applyPlugin(PluginType.KOTLIN_MPP)
                applyPlugin(PluginType.KAPT)
                pluginCallbacks += Callback::class.java
                android {
                    compileSdk = DEFAULT_COMPILE_SDK_VERSION
                    namespace = "com.example.lib"
                    buildFeatures {
                        dataBinding = true
                    }
                }
                files.add(
                    "src/main/java/com/example/lib/LibData.java",
                    //language=java
                    """
                        package com.example.lib;

                        import android.widget.TextView;
                        import androidx.databinding.BindingAdapter;

                        public class LibData {
                            public final String greetings = "Hello from lib!";

                            @BindingAdapter("android:dataFromLib")
                            public static void bindLibData(TextView textView, LibData libData) {
                                textView.setText(libData.greetings);
                            }
                        }
                    """.trimIndent()
                )
            }
            gradleProperties {
                add(BooleanOption.USE_ANDROID_X, true)
            }
        }

    class Callback: GenericCallback {
        override fun handleProject(project: Project) {
            val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
            kotlin.apply {
                androidTarget()
                androidTarget { target ->
                    target.compilations.all { compilation ->
                        compilation.compilerOptions.options.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
                    }
                }
            }

            project.tasks.withType(KaptGenerateStubs::class.java) {
                it.compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
                }
            }
        }
    }

    // Regression test for bug 238964168
    @Test
    fun testCompilation() {
        rule.build.executor.run("clean", "compileDebugJavaWithJavac")
    }
}
