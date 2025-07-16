/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.build.gradle.integration.common.fixture.testprojects.prebuilts.privacysandbox

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.DynamicFeatureExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.gradle.integration.common.fixture.ANDROIDX_APPCOMPAT_APPCOMPAT_VERSION
import com.android.build.gradle.integration.common.fixture.COM_GOOGLE_ANDROID_MATERIAL_MATERIAL_VERSION
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.PrivacySandboxSdkDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition.Companion.DEFAULT_COMPILE_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.internal.privaysandboxsdk.PrivacySandboxSdkConstants.androidxPrivacySandboxActivityVersion
import com.android.build.gradle.internal.privaysandboxsdk.PrivacySandboxSdkConstants.androidxPrivacySandboxSdkRuntimeVersion
import com.android.build.gradle.internal.privaysandboxsdk.PrivacySandboxSdkConstants.androidxPrivacySandboxSdkUiVersion
import com.android.build.gradle.internal.privaysandboxsdk.PrivacySandboxSdkConstants.androidxPrivacySandboxVersion
import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.options.StringOption
import com.android.testutils.MavenRepoGenerator
import com.android.testutils.TestInputsGenerator
import com.android.testutils.generateAarWithContent
import com.google.common.collect.ImmutableList
import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Kotlin version used for privacy-sandbox tests.
 *
 * Privacy-sandbox tests are not always able to run with the latest version of Kotlin, so we might
 * need to use a lower version than [com.android.testutils.TestUtils.KOTLIN_VERSION_FOR_TESTS]
 * (b/412597247).
 */
const val KOTLIN_VERSION_FOR_PRIVACY_SANDBOX_TESTS = "2.2.0"

/**
 * KSP version used for privacy-sandbox tests.
 *
 * Privacy-sandbox tests are not always able to run with the latest version of Kotlin, so we might
 * need to use a lower version than [com.android.testutils.TestUtils.KSP_VERSION_FOR_TESTS]
 * (b/412597247).
 */
const val KSP_VERSION_FOR_PRIVACY_SANDBOX_TESTS = "2.2.0-2.0.2"

fun GradleBuildDefinition.createGradleProjectWithPrivacySandboxLibrary(
    action: GradleBuildDefinition.() -> Unit
) {
    privacySandboxSdk(":privacy-sandbox-sdk", createMinimumProject = false) {
        android {
            compileSdk = DEFAULT_COMPILE_SDK_VERSION
            namespace = "com.example.privacysandboxsdk"
            minSdk = 23
            bundle {
                applicationId = "com.example.privacysandboxsdk"
                sdkProviderClassName = "Test"
                setVersion(1, 2, 3)
            }
        }
        dependencies {
            include(project(":privacy-sandbox-sdk-impl"))
        }
    }
    androidLibrary(":privacy-sandbox-sdk-impl", createMinimumProject = false) {
        android {
            namespace = "com.example.privacysandboxsdk"
            compileSdk = DEFAULT_COMPILE_SDK_VERSION
            defaultConfig.minSdk = 23
        }
    }
    gradleProperties {
        add(BooleanOption.PRIVACY_SANDBOX_SDK_SUPPORT, true)
    }
    action()
}

private val mavenRepo: List<MavenRepoGenerator.Library> = listOf(
    MavenRepoGenerator.Library(
        "com.externaldep:externaljar:1",
        "jar",
        TestInputsGenerator.jarWithEmptyClasses(
            ImmutableList.of("com/externaldep/externaljar/ExternalClass")
        )
    ),
    MavenRepoGenerator.Library(
        "com.externaldep:externalaar:1",
        "aar",
        generateAarWithContent(
            "com.externaldep.externalaar",
            // language=xml
            manifest = """
                 <manifest package="com.externaldex.externalaar" xmlns:android="http://schemas.android.com/apk/res/android">
                     <uses-sdk android:minSdkVersion="21" />
                     <!-- Permission that needs to be removed before ASB packaging -->
                     <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
                 </manifest>
            """.trimIndent()
        )
    )
)

fun privacySandboxSampleProjectWithSeparateTest(): GradleRule {
    return GradleRule.configure()
        .withMavenRepository {
            // FIXME if we can move it here, or change to a utility method on the MavenRepo builder
            mavenRepo.forEach {
                library(it)
            }
        }.withProfileOutput()
        .from {
            buildPrivacySandboxSampleProject()
            androidTest(":example-app-test", createMinimumProject = false) {
                android {
                    compileSdk = DEFAULT_COMPILE_SDK_VERSION
                    namespace = "com.example.privacysandboxsdk.consumer.test"
                    targetProjectPath = ":example-app"
                    privacySandbox {
                        enable = true
                    }
                }
                files.add(
                    "src/main/java/com/privacysandboxsdk/consumer/test/HelloWorldTest.kt",
                    // language=kotlin
                    """
                    package com.example.privacysandboxsdk.consumer.test

                    class HelloWorldTest {
                        fun doSomething() {
                            // The line below should compile if classes from another SDK are in the
                            // same compile classpath.
                            println(1 + 1)
                        }
                    }
                """.trimIndent()
                )

            }
            buildExampleApp(":example-app") {}
            gradleProperties {
                add(BooleanOption.USE_ANDROID_X, true)
            }
        }
}

fun GradleBuildDefinition.buildFeature(
    path: String,
    applicationModulePath:String,
    action: AndroidProjectDefinition<DynamicFeatureExtension>.() -> Unit
) {
    androidFeature(path, createMinimumProject = false) {
        android {
            namespace = "com.example.test.feature"
            compileSdk = DEFAULT_COMPILE_SDK_VERSION
            defaultConfig {
                minSdk = 23
            }
        }
        files.add("src/main/AndroidManifest.xml",
            // language=XML
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    |        xmlns:dist="http://schemas.android.com/apk/distribution">
                    |    <dist:module> <dist:fusing dist:include="true"/>
                    |        <dist:delivery>
                    |           <dist:install-time/>
                    |        </dist:delivery>
                    |    </dist:module>
                    |    <application />
                    |</manifest>""".trimMargin())
        dependencies {
            implementation(project(applicationModulePath))
        }
        action()
    }
}

fun privacySandboxSampleProjectWithDynamicFeature(): GradleRule {
    return GradleRule.configure()
        .withMavenRepository {
            // FIXME if we can move it here, or change to a utility method on the MavenRepo builder
            mavenRepo.forEach {
                library(it)
            }
        }.withProfileOutput()
        .from {
            buildPrivacySandboxSampleProject()
            buildFeature(":feature", applicationModulePath = ":example-app") {
            }
            buildExampleApp(":example-app") {
                android {
                    dynamicFeatures += ":feature"
                }
            }
            gradleProperties {
                add(BooleanOption.USE_ANDROID_X, true)
            }
        }
}

fun privacySandboxSampleProject(): GradleRule {
    return GradleRule.configure()
        .withMavenRepository {
            // FIXME if we can move it here, or change to a utility method on the MavenRepo builder
            mavenRepo.forEach {
                library(it)
            }
        }.withProfileOutput()
        .from {
            buildPrivacySandboxSampleProject()
            buildExampleApp(":example-app") { }

            gradleProperties {
                add(BooleanOption.USE_ANDROID_X, true)
            }
        }
}

fun GradleBuildDefinition.buildPrivacySandboxSampleProject() {
    /*
        :example-app
             ▲
             │
        :privacy-sandbox-sdk  ◄──────
             ▲                      │
             │                      │
        :sdk-impl-a            :android-lib
             ▲
             │
        :privacy-sandbox-sdk-b
             ▲
             │
        :sdk-impl-b
 */
    // An SDK module used by the :example-app application.
    privacySandboxSdk(":privacy-sandbox-sdk", createMinimumProject = false) {
        android {
            compileSdk = DEFAULT_COMPILE_SDK_VERSION
            minSdk = 23
            bundle {
                applicationId = "com.example.privacysandboxsdk"
                sdkProviderClassName = "Test"
                compatSdkProviderClassName = "Test"
                setVersion(1, 2, 3)
            }
        }
        dependencies {
            include(project(":sdk-impl-a"))
            include(project(":android-lib"))
            include("com.externaldep:externaljar:1")
            include("com.externaldep:externalaar:1")

            requiredSdk(project(":privacy-sandbox-sdk-b"))
        }
    }
    // A library module included in the :privacy-sandbox-sdk SDK module.
    privacySandboxSdkLibraryProject(":android-lib") {
        android {
            namespace = "com.example.androidlib"
            defaultConfig.minSdk = 23
        }
        files.add(
            "src/main/java/com/example/androidlib/Example.java",
            // language=java
            """
                package com.example.androidlib;

                class Example {

                    public Example() {}

                    public void f2() {}
                }
            """.trimIndent()
        )
        // Have an empty manifest as a regression test of b/237279793
        files.setupMinimumManifest()
    }
    // An SDK used by the :sdk-impl-a library module.
    privacySandboxSdk(":privacy-sandbox-sdk-b") {
        android {
            namespace = "com.example.privacysandboxsdkb"
            minSdk = 23
            bundle {
                applicationId = "com.example.privacysandboxsdkb"
                sdkProviderClassName = "Test"
                compatSdkProviderClassName = "Test"
                setVersion(1, 2, 3)
            }
        }
        dependencies {
            include(project(":sdk-impl-b"))
        }
    }
    // A library included in :privacy-sandbox-sdk-b (SDK module).
    privacySandboxSdkLibraryProject(":sdk-impl-b") {
        android {
            namespace = "com.example.sdkImplB"
            defaultConfig.minSdk = 14
        }
        dependencies {
        }
        files {
            add(
                "src/main/java/com/example/sdkImplB/Example.java",
                // language=java
                """
                    package com.example.sdkImplB;

                    class Example {

                        public Example() {}

                        public void f1() {}
                    }
                """.trimIndent()
            )
            add(
                "src/main/java/com/example/sdkImplB/MySdkB.kt",
                // language=kotlin
                """
                    package com.example.sdkImplB

                    import androidx.privacysandbox.tools.PrivacySandboxService

                    @PrivacySandboxService
                    interface MySdkB {
                        suspend fun f1(p1: Int): Int
                    }
                """.trimIndent()
            )
            add(
                "src/main/res/values/strings.xml",
                //language=xml
                """
                    <resources>
                        <string name="string_from_sdk_impl_b">fromSdkImplB</string>
                    </resources>""".trimIndent()
            )
            add("src/main/resources/my_java_resource.txt", "some java resource")
            add("src/main/assets/asset_from_sdkImplB.txt", "some asset")
        }
    }
    // A library module included in the :privacy-sandbox-sdk SDK.
    privacySandboxSdkLibraryProject(":sdk-impl-a") {
        android {
            namespace = "com.example.sdkImplA"
            defaultConfig.minSdk = 23
        }
        dependencies {
            implementation(project(":privacy-sandbox-sdk-b"))
        }
        files {

            update("src/main/AndroidManifest.xml").replaceWith(
                //language=xml
                """
                    <?xml version="1.0" encoding="utf-8"?>
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android"  xmlns:tools="http://schemas.android.com/tools">
                        <uses-permission android:name="android.permission.FOREGROUND_SERVICE" tools:node="remove" />
                        <uses-permission android:name="android.permission.INTERNET" />
                    </manifest>""".trimIndent()
            )
            add(
                "src/main/res/values/strings.xml",
                //language=xml
                """
                    <resources>
                        <string name="string_from_sdk_impl_a">fromSdkImplA</string>
                    </resources>""".trimIndent()
            )
            add(
                "src/main/java/com/example/sdkImplA/Example.kt",
                // language=kotlin
                """
                    package com.example.sdkImplA

                    class Example {

                        fun f1() {}
                        // The line below should compile if classes from another SDK are in the
                        // same compile classpath.
                        fun f2() {}
                        companion object {}
                    }
                 """.trimIndent()
            )
            add("src/main/resources/my_java_resource.txt", "some java resource")
            add("src/main/assets/asset_from_sdkImplA.txt", "some asset")
        }
    }
    rootProject {
        applyPlugin(PluginType.KSP, version = KSP_VERSION_FOR_PRIVACY_SANDBOX_TESTS)
    }
}

fun GradleBuildDefinition.buildExampleApp(
    path: String,
    action: AndroidProjectDefinition<ApplicationExtension>.() -> Unit
) {
    androidApplication(path, createMinimumProject = false) {
        android {
            compileSdk = DEFAULT_COMPILE_SDK_VERSION
            namespace = "com.example.privacysandboxsdk.consumer"
            defaultConfig {
                minSdk = 23
                versionCode = 4
            }
        }
        dependencies {
            implementation(project(":privacy-sandbox-sdk"))
        }
        files.add(
            "src/main/java/com/privacysandboxsdk/consumer/HelloWorld.kt",
            // language=kotlin
            """
                package com.example.privacysandboxsdk.consumer

                class HelloWorld {

                    fun doSomething() {
                        // The line below should compile if classes from another SDK are in the
                        // same compile classpath.
                        com.example.sdkImplA.Example().f1()
                    }
                }
            """.trimIndent()
        )
        files.setupMinimumManifest()
        action()
    }
}

fun privacySandboxSdkAppLargeSampleProjectWithFeatures(
    action: GradleBuildDefinition.() -> Unit
): GradleRule {
    return GradleRule.configure()
        .withProfileOutput()
        .from {
            buildExampleSdkConsumerApp(":client-app") {
                android {
                    dynamicFeatures += ":feature"
                }
            }
            addSdkSubprojects()
            buildFeature(":feature", ":client-app") {
                this@from.configurePrivacySandboxTestProject()
            }
            action()
        }
}

fun privacySandboxSdkAppLargeSampleProjectWithTestModule(
    action: GradleBuildDefinition.() -> Unit
): GradleRule {
    return GradleRule.configure()
        .withProfileOutput()
        .from {
            buildPrivacySandboxSdkAppLargeSampleProject()
            androidTest(":client-app-test", createMinimumProject = false) {
                applyPlugin(PluginType.KOTLIN_ANDROID, version = KOTLIN_VERSION_FOR_PRIVACY_SANDBOX_TESTS)
                android {
                    namespace = "com.example.privacysandbox.client.test"
                    targetProjectPath = ":client-app"
                    compileSdk = DEFAULT_COMPILE_SDK_VERSION
                    defaultConfig {
                        minSdk = 34
                        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                    }
                    privacySandbox {
                        enable = true
                    }
                    compileOptions {
                        sourceCompatibility = JavaVersion.VERSION_17
                        targetCompatibility = JavaVersion.VERSION_17
                    }
                }
                kotlin {
                    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
                }
                dependencies {
                    implementation("androidx.appcompat:appcompat:$ANDROIDX_APPCOMPAT_APPCOMPAT_VERSION")
                    implementation("org.jetbrains.kotlin:kotlin-stdlib:$KOTLIN_VERSION_FOR_PRIVACY_SANDBOX_TESTS")
                    implementation("androidx.test:core:1.5.0")
                    implementation("androidx.test:core-ktx:1.5.0")
                    implementation("androidx.test.ext:junit:1.1.5")
                    implementation("androidx.test.ext:junit-ktx:1.1.4")
                    implementation("androidx.test:runner:1.5.0")
                    implementation("androidx.test.espresso:espresso-core:3.4.0")
                }
                files.add("src/androidTest/java/com/example/client.test",
                    getAndroidTestSource("package com.example.client.test"))
            }
            configurePrivacySandboxTestProject()
            action()
        }
}

fun privacySandboxSdkAppLargeSampleProject(action: GradleBuildDefinition.() -> Unit): GradleRule {
    return GradleRule.configure()
        .withProfileOutput()
        .from {
            buildPrivacySandboxSdkAppLargeSampleProject()
            configurePrivacySandboxTestProject()
            action()
        }
}


fun GradleBuildDefinition.configurePrivacySandboxTestProject() {
    gradleProperties {
        add(BooleanOption.USE_ANDROID_X, true)
        add(BooleanOption.NON_TRANSITIVE_R_CLASS, true)
        add(BooleanOption.PRIVACY_SANDBOX_SDK_SUPPORT, true)
        add(BooleanOption.PRIVACY_SANDBOX_SDK_PLUGIN_SUPPORT, true)
        add(BooleanOption.PRIVACY_SANDBOX_SDK_REQUIRE_SERVICES, false)
        add(BooleanOption.USE_NON_FINAL_RES_IDS, false)
    }
}

fun GradleBuildDefinition.buildPrivacySandboxSdkAppLargeSampleProject() {
    buildExampleSdkConsumerApp(":client-app") { }
    addSdkSubprojects()
}
fun GradleBuildDefinition.addSdkSubprojects() {
    buildExampleSdkSandboxSdk(":example-sdk") { }
    buildExampleSdkSandboxSdkBundle(":example-sdk-bundle") { }
    rootProject {
        applyPlugin(PluginType.KSP, version = KSP_VERSION_FOR_PRIVACY_SANDBOX_TESTS)
    }
}

fun GradleBuildDefinition.buildExampleSdkConsumerApp(
    path: String,
    action: AndroidProjectDefinition<ApplicationExtension>.() -> Unit
) {
    androidApplication(path, createMinimumProject = false) {
        applyPlugin(PluginType.KOTLIN_ANDROID, version = KOTLIN_VERSION_FOR_PRIVACY_SANDBOX_TESTS)
        android {
            namespace = "com.example.privacysandbox.client"
            compileSdk = DEFAULT_COMPILE_SDK_VERSION
            defaultConfig {
                applicationId = "com.example.privacysandbox.client"
                minSdk = 23
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                targetSdk = 34
                versionCode = 2
                versionName = "1.01"
            }
            privacySandbox {
                enable = true
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
        kotlin {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        }
        dependencies {
            implementation(project(":example-sdk-bundle"))
            implementation("androidx.appcompat:appcompat:$ANDROIDX_APPCOMPAT_APPCOMPAT_VERSION")
            implementation("com.google.android.material:material:$COM_GOOGLE_ANDROID_MATERIAL_MATERIAL_VERSION")
            implementation("androidx.privacysandbox.sdkruntime:sdkruntime-client:$androidxPrivacySandboxSdkRuntimeVersion")
            implementation("androidx.privacysandbox.ui:ui-core:$androidxPrivacySandboxSdkUiVersion")
            implementation("androidx.privacysandbox.ui:ui-client:$androidxPrivacySandboxSdkUiVersion")
            implementation("androidx.privacysandbox.activity:activity-core:$androidxPrivacySandboxActivityVersion")
            implementation("androidx.privacysandbox.activity:activity-client:$androidxPrivacySandboxVersion")
            androidTestImplementation("androidx.appcompat:appcompat:$ANDROIDX_APPCOMPAT_APPCOMPAT_VERSION")
            androidTestImplementation("org.jetbrains.kotlin:kotlin-stdlib:$KOTLIN_VERSION_FOR_PRIVACY_SANDBOX_TESTS")
            androidTestImplementation("androidx.test:core:1.5.0")
            androidTestImplementation("androidx.test:core-ktx:1.5.0")
            androidTestImplementation("androidx.test.ext:junit:1.1.5")
            androidTestImplementation("androidx.test.ext:junit-ktx:1.1.4")
            androidTestImplementation("androidx.test:runner:1.5.0")
            androidTestImplementation("androidx.test.espresso:espresso-core:3.4.0")
        }
        files {
            add(
                "src/main/java/com/example/client/BannerAd.kt",
                // language=kotlin
                """
                    package com.example.client

                    import android.content.Context
                    import android.util.AttributeSet
                    import android.view.View
                    import android.widget.LinearLayout
                    import android.widget.TextView
                    import androidx.appcompat.app.AppCompatActivity
                    import androidx.privacysandbox.activity.client.createManagedSdkActivityLauncher
                    import androidx.privacysandbox.ui.client.view.SandboxedSdkView
                    import androidx.privacysandbox.ui.core.SandboxedUiAdapter
                    import com.example.api.SdkBannerRequest

                    class BannerAd(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {

                        // This method could divert a percentage of requests to a sandboxed SDK and fallback to
                        // existing ad logic. For this example, we send all requests to the sandboxed SDK as long as it
                        // exists.
                        suspend fun loadAd(
                            baseActivity: AppCompatActivity,
                            clientMessage: String,
                            allowSdkActivityLaunch: () -> Boolean) {
                            val bannerAd = getBannerAdFromRuntimeEnabledSdkIfExists(
                                baseActivity,
                                clientMessage,
                                allowSdkActivityLaunch
                            )
                            if (bannerAd != null) {
                                val sandboxedSdkView = SandboxedSdkView(context)
                                addViewToLayout(sandboxedSdkView)
                                sandboxedSdkView.setAdapter(bannerAd)
                                return
                            }

                            val textView = TextView(context)
                            textView.text = "Ad from SDK in the app"
                            addViewToLayout(textView)
                        }

                        private suspend fun getBannerAdFromRuntimeEnabledSdkIfExists(
                            baseActivity: AppCompatActivity,
                            message: String,
                            allowSdkActivityLaunch: () -> Boolean): SandboxedUiAdapter? {
                            if (!SdkClient.isSdkLoaded()) {
                                return null
                            }

                            val launcher = baseActivity.createManagedSdkActivityLauncher { allowSdkActivityLaunch() }
                            val request = SdkBannerRequest(message, launcher)
                            return SdkClient.loadSdkIfNeeded(context)?.getBanner(request)
                        }

                        private fun addViewToLayout(view: View) {
                            removeAllViews()
                            view.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                            super.addView(view)
                        }
                    }
                """.trimIndent())
            add(
                "src/main/java/com/example/client/SdkClient.kt",
                // language=kotlin
                """
                    package com.example.client

                    import android.content.Context
                    import android.os.Bundle
                    import android.util.Log
                    import androidx.privacysandbox.sdkruntime.client.SdkSandboxManagerCompat
                    import androidx.privacysandbox.sdkruntime.core.LoadSdkCompatException
                    import com.example.api.SdkService
                    import com.example.api.SdkServiceFactory

                    class SdkClient(private val context: Context) {

                        /**
                         * Initialize the SDK and In-App adapters. If the SDK failed to initialize, return false, else
                         * true.
                         */
                        suspend fun initialize(): Boolean {
                            return loadSdkIfNeeded(context) != null
                        }

                        /** Keeps a reference to a sandboxed SDK and makes sure it's only loaded once. */
                        internal companion object Loader {

                            private const val TAG = "ExistingSdk"

                            /**
                             * Name of the SDK to be loaded.
                             *
                             * (needs to be the one defined in example-sdk-bundle/build.gradle)
                             */
                            private const val SDK_NAME = "com.example.sdk"

                            private var remoteInstance: SdkService? = null

                            suspend fun loadSdkIfNeeded(context: Context): SdkService? {
                                try {
                                    // First we need to check if the SDK is already loaded. If it is we just return it.
                                    // The sandbox manager will throw an exception if we try to load an SDK that is
                                    // already loaded.
                                    if (remoteInstance != null) return remoteInstance

                                    // An [SdkSandboxManagerCompat], used to communicate with the sandbox and load SDKs.
                                    val sandboxManagerCompat = SdkSandboxManagerCompat.from(context)

                                    val sandboxedSdk = sandboxManagerCompat.loadSdk(SDK_NAME, Bundle.EMPTY)
                                    remoteInstance = SdkServiceFactory.wrapToSdkService(sandboxedSdk.getInterface()!!)
                                    return remoteInstance
                                } catch (e: LoadSdkCompatException) {
                                    Log.e(TAG, "Failed to load SDK, error code: ${'$'}{e.loadSdkErrorCode}", e)
                                    return null
                                }
                            }

                            fun isSdkLoaded(): Boolean {
                                return remoteInstance != null
                            }
                        }
                    }
                """.trimIndent()
            )
            add(
                "src/main/java/com/example/client/MainActivity.kt",
                // language=kotlin
                """
                    package com.example.client

                    import android.os.Bundle
                    import android.widget.Button
                    import android.widget.Toast
                    import androidx.appcompat.app.AppCompatActivity
                    import androidx.lifecycle.lifecycleScope
                    import com.example.privacysandbox.client.R
                    import kotlinx.coroutines.launch

                    class MainActivity : AppCompatActivity() {
                        /** Container for rendering content from the SDK. */
                        private lateinit var bannerAd: BannerAd

                        private val sdkClient = SdkClient(this)

                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)
                            setContentView(R.layout.activity_main)

                            bannerAd = findViewById(R.id.banner_ad)

                            findViewById<Button>(R.id.initialize_sdk_button).setOnClickListener {
                                onInitializeSkButtonPressed()
                            }
                            findViewById<Button>(R.id.request_banner_button).setOnClickListener {
                                onRequestBannerButtonPressed()
                            }
                        }

                        private fun onInitializeSkButtonPressed() = lifecycleScope.launch {
                            if (!sdkClient.initialize()) {
                                makeToast("Failed to initialize SDK")
                            } else {
                                makeToast("Initialized SDK!")
                            }
                        }

                        private fun onRequestBannerButtonPressed() = lifecycleScope.launch {
                                bannerAd.loadAd(
                                    this@MainActivity,
                                    PACKAGE_NAME,
                                    shouldStartActivityPredicate(),
                                )
                        }

                        private fun shouldStartActivityPredicate() : () -> Boolean {
                            return { true }
                        }

                        private fun makeToast(message: String) {
                            runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show() }
                        }

                        companion object {
                            private const val TAG = "SandboxClient"

                            /**
                             * Package name of this app. This is something that the SDK might use the identify this
                             * particular app client.
                             *
                             * (Note that in this particular sample it's used to build the banner view label).
                             */
                            private const val PACKAGE_NAME = "com.example.privacysandbox.client"
                        }
                    }

                """.trimIndent()
            )
            add(
                "src/main/res/layout/activity_main.xml",
                // language=xml
                """
                    <?xml version="1.0" encoding="utf-8"?>
                    <androidx.constraintlayout.widget.ConstraintLayout
                        android:id="@+id/top_layout"
                        xmlns:android="http://schemas.android.com/apk/res/android"
                        xmlns:app="http://schemas.android.com/apk/res-auto"
                        xmlns:tools="http://schemas.android.com/tools"
                        android:layout_width="match_parent"
                        android:layout_height="match_parent"
                        tools:context="com.example.client.MainActivity">

                        <LinearLayout
                            android:id="@+id/linearLayout"
                            style="?android:attr/buttonBarButtonStyle"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginStart="16dp"
                            android:layout_marginTop="16dp"
                            android:layout_marginEnd="16dp"
                            android:orientation="vertical"
                            app:layout_constraintEnd_toEndOf="parent"
                            app:layout_constraintStart_toStartOf="parent"
                            app:layout_constraintTop_toTopOf="parent">

                            <Button
                                android:id="@+id/initialize_sdk_button"
                                style="?android:attr/buttonBarButtonStyle"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="@string/initialize_sdk_button_label" />

                            <Button
                                android:id="@+id/request_banner_button"
                                style="?android:attr/buttonBarButtonStyle"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="@string/request_banner_view_button_label"
                                android:backgroundTint="#FFFFFF"/>
                        </LinearLayout>

                        <ScrollView
                            android:id="@+id/scroll_view"
                            android:layout_width="match_parent"
                            android:layout_weight="4"
                            android:layout_height="500dp"
                            android:orientation="vertical"
                            app:layout_constraintTop_toBottomOf="@+id/linearLayout"
                            >
                        <LinearLayout
                            android:id="@+id/ad_layout"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:orientation="vertical">
                        <com.example.client.BannerAd
                            android:id="@+id/banner_ad"
                            android:layout_width="match_parent"
                            android:layout_height="150dp"
                            android:layout_gravity="end"
                            />
                        </LinearLayout>
                        </ScrollView>
                    </androidx.constraintlayout.widget.ConstraintLayout>

                """.trimIndent()
            )
            add(
                "src/main/res/values/strings.xml",
                //language=xml
                """
                    <?xml version="1.0" encoding="utf-8"?>
                    <resources>
                        <string name="app_name">Privacy Sandbox App Sample</string>
                        <string name="initialize_sdk_button_label">Initialize SDK</string>
                        <string name="request_banner_view_button_label">Show banner view</string>
                    </resources>
                """.trimIndent()
            )
            add(
                "src/main/AndroidManifest.xml",
                // language=xml
                """
                    <?xml version="1.0" encoding="utf-8"?>
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android">

                    <uses-permission android:name="android.permission.INTERNET"/>
                    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>

                        <application
                                android:allowBackup="true"
                                android:label="@string/app_name"
                                android:supportsRtl="true"
                                android:theme="@style/Theme.AppCompat.Light">
                            <activity
                                    android:name="com.example.client.MainActivity"
                                    android:exported="true">
                                <intent-filter>
                                    <action android:name="android.intent.action.MAIN" />
                                    <category android:name="android.intent.category.LAUNCHER" />
                                </intent-filter>
                            </activity>
                        </application>
                    </manifest>
                """.trimIndent()
            )
            add(
                "src/androidTest/java/com/example/client",
                getAndroidTestSource("package com.example.client")
            )
            this@androidApplication.action()
        }
    }
}

fun getAndroidTestSource(packageDeclaration:String):String =
    // language=kotlin
    """
            $packageDeclaration

            import android.app.Activity
            import android.util.Log
            import android.view.SurfaceView
            import android.view.View
            import android.widget.LinearLayout
            import androidx.core.view.isVisible
            import androidx.privacysandbox.ui.client.view.SandboxedSdkView
            import androidx.test.espresso.Espresso.onView
            import androidx.test.espresso.IdlingPolicies
            import androidx.test.espresso.IdlingRegistry
            import androidx.test.espresso.IdlingResource
            import androidx.test.espresso.action.ViewActions.click
            import androidx.test.espresso.assertion.ViewAssertions.matches
            import androidx.test.espresso.matcher.ViewMatchers.withId
            import androidx.test.ext.junit.rules.ActivityScenarioRule
            import androidx.test.ext.junit.rules.activityScenarioRule
            import androidx.test.ext.junit.runners.AndroidJUnit4
            import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
            import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
            import androidx.test.runner.lifecycle.Stage
            import com.example.privacysandbox.client.R
            import java.util.concurrent.TimeUnit
            import org.hamcrest.Description
            import org.hamcrest.Matcher
            import org.hamcrest.TypeSafeMatcher
            import org.junit.Rule
            import org.junit.Before
            import org.junit.Test
            import org.junit.runner.RunWith

            @RunWith(AndroidJUnit4::class)
            class MainActivityTest {
                @get:Rule
                var activityScenarioRule = activityScenarioRule<MainActivity>()
                private var idlingResource: IdlingResource? = null

                @Before
                fun setUp() {
                    // Increase the timeout to 2 minutes
                    IdlingPolicies.setMasterPolicyTimeout(120, TimeUnit.SECONDS)
                    IdlingPolicies.setIdlingResourceTimeout(120, TimeUnit.SECONDS)
                }

                @Test
                fun loadSdkBannerViewTest() {
                    onView(withId(R.id.initialize_sdk_button)).perform(click())
                    // Click the "Request Banner" button
                    onView(withId(R.id.request_banner_button)).perform(click())
                    // Register idling resource for BannerAd LinearLayout
                    idlingResource = BannerAdIdlingResource(activityScenarioRule)
                    IdlingRegistry.getInstance().register(idlingResource)
                    onView(withId(R.id.banner_ad)).check(matches(hasVisibleSurfaceView()))
                    onView(withId(R.id.banner_ad)).perform(click())
                    IdlingRegistry.getInstance().unregister(idlingResource)
                    // Wait until the MainActivity is stopped. This indicate that the activity from SDK is in displayed.
                    idlingResource = MainActivityStoppedIdlingResource()
                    // Unregister the idling resource
                    IdlingRegistry.getInstance().unregister(idlingResource)
                }

                private fun hasVisibleSurfaceView(): Matcher<View> {
                    return object : TypeSafeMatcher<View>() {
                        override fun describeTo(description: Description) {
                            description.appendText("has visible SurfaceView")
                        }

                        override fun matchesSafely(view: View): Boolean {
                            val bannerAd = view as? LinearLayout
                            val sandboxedSdkView = bannerAd?.getChildAt(0) as? SandboxedSdkView
                            val surfaceView = sandboxedSdkView?.getChildAt(0) as? SurfaceView
                            return surfaceView != null && surfaceView.isVisible
                        }
                    }
                }

                class MainActivityStoppedIdlingResource : IdlingResource {
                    private var callback: IdlingResource.ResourceCallback? = null
                    override fun getName() = "MainActivity in Stopped State Idling Resource"
                    override fun isIdleNow(): Boolean {
                        var mainActivity: Activity? = null
                        getInstrumentation().runOnMainSync {
                            run {
                                mainActivity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STOPPED).filter { it.javaClass.equals(MainActivity::class.java) }.first()
                            }
                        }
                        return mainActivity != null
                    }

                    override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback?) {
                        this.callback = callback
                    }
                }

                class BannerAdIdlingResource(private val activityRule: ActivityScenarioRule<MainActivity>) : IdlingResource {
                    private var callback: IdlingResource.ResourceCallback? = null
                    private var isIdle = false

                    override fun getName() = "BannerAd Idling Resource"

                    override fun isIdleNow(): Boolean {
                        activityRule.scenario.onActivity {
                            val view = it.findViewById<LinearLayout>(R.id.banner_ad)
                            if (view != null && view.childCount > 0) {
                                Log.w("BannerAdIdlingResource", "Found view: ${'$'}{view.getChildAt(0)}")
                                val sandboxedSdkView = view.getChildAt(0) as? SandboxedSdkView
                                Log.w("BannerAdIdlingResource", "Found SandboxedSdkView: ${'$'}{sandboxedSdkView?.getChildAt(0)}")
                                val surfaceView = sandboxedSdkView?.getChildAt(0) as? SurfaceView
                                if (surfaceView != null) {
                                        isIdle = true
                                        callback?.onTransitionToIdle()
                                }
                            }
                        }
                        return isIdle
                    }

                    override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback?) {
                        this.callback = callback
                    }
                }
            }
""".trimIndent()

fun GradleBuildDefinition.buildExampleSdkSandboxSdkBundle(
    path: String,
    action: PrivacySandboxSdkDefinition.() -> Unit
) {
    privacySandboxSdk(path) {
        android {
            compileSdk = DEFAULT_COMPILE_SDK_VERSION
            minSdk = 21
            bundle {
                // This package name is used to load this SDK in the Privacy Sandbox later on.
                packageName = "com.example.sdk"

                setVersion(1, 0, 0)
                // SDK provider defined in the SDK Runtime library. This is an important part of the
                // future backwards compatibility support, so most SDKs won't need to change it.
                sdkProviderClassName = "androidx.privacysandbox.sdkruntime.provider.SandboxedSdkProviderAdapter"
                // SDK provider defined by the example-sdk itself.
                compatSdkProviderClassName = "com.example.implementation.SdkProvider"
            }
        }
        dependencies {
            include(project(":example-sdk"))
        }
        action()
    }
}

fun GradleBuildDefinition.buildExampleSdkSandboxSdk(
    path: String,
    action: AndroidProjectDefinition<LibraryExtension>.() -> Unit
) {
    androidXPrivacySandboxLibrary(path, createMinimumProject = false) {
        applyPlugin(PluginType.KOTLIN_ANDROID, version = KOTLIN_VERSION_FOR_PRIVACY_SANDBOX_TESTS)
        android {
            namespace = "com.example"
            compileSdk = DEFAULT_COMPILE_SDK_VERSION
            defaultConfig {
                minSdk = 21
                targetSdk = 34
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
        kotlin {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        }

        dependencies {
            implementation("androidx.activity:activity-ktx:1.8.2")
            ksp("androidx.annotation:annotation:1.8.1")
            implementation("androidx.lifecycle:lifecycle-common:2.7.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

            implementation("androidx.privacysandbox.activity:activity-client:$androidxPrivacySandboxActivityVersion")
            implementation("androidx.privacysandbox.activity:activity-core:$androidxPrivacySandboxActivityVersion")
            implementation("androidx.privacysandbox.activity:activity-provider:$androidxPrivacySandboxVersion")

            implementation("androidx.privacysandbox.ui:ui-core:$androidxPrivacySandboxSdkUiVersion")
            implementation("androidx.privacysandbox.ui:ui-provider:$androidxPrivacySandboxSdkUiVersion")
            implementation("androidx.privacysandbox.ui:ui-client:$androidxPrivacySandboxSdkUiVersion")

            implementation("androidx.privacysandbox.sdkruntime:sdkruntime-core:$androidxPrivacySandboxSdkRuntimeVersion")
            implementation("androidx.privacysandbox.sdkruntime:sdkruntime-client:$androidxPrivacySandboxSdkRuntimeVersion")
            implementation("androidx.privacysandbox.sdkruntime:sdkruntime-provider:$androidxPrivacySandboxSdkRuntimeVersion")

            ksp("androidx.privacysandbox.tools:tools-apicompiler:$androidxPrivacySandboxVersion")
            implementation("androidx.privacysandbox.tools:tools:$androidxPrivacySandboxVersion")
        }
        files {
            add(
                "src/main/java/com/example/api/SdkBannerRequest.kt",
                // language=kotlin
                """
                package com.example.api

                import androidx.privacysandbox.tools.PrivacySandboxValue
                import androidx.privacysandbox.activity.core.SdkActivityLauncher

                @PrivacySandboxValue
                data class SdkBannerRequest(
                    /** The package name of the app. */
                    val appPackageName: String,
                    /**
                     *  An [SdkActivityLauncher] that will be used to launch an activity when the banner is clicked.
                     */
                    val activityLauncher: SdkActivityLauncher,
                )
            """.trimIndent()
            )
            add(
                "src/main/java/com/example/api/SdkSandboxedUiAdapter.kt",
                // language=kotlin
                """
                package com.example.api

                import androidx.privacysandbox.tools.PrivacySandboxInterface
                import androidx.privacysandbox.ui.core.SandboxedUiAdapter

                @PrivacySandboxInterface
                interface SdkSandboxedUiAdapter : SandboxedUiAdapter
            """.trimIndent()
            )
            add(
                "src/main/java/com/example/api/SdkService.kt",
                // language=kotlin
                """
                package com.example.api

                import androidx.privacysandbox.tools.PrivacySandboxService

                @PrivacySandboxService
                interface SdkService {
                    suspend fun getMessage(): String

                    suspend fun getBanner(request: SdkBannerRequest): SdkSandboxedUiAdapter?
                }
            """.trimIndent()
            )
            add(
                "src/main/java/com/example/implementation/SdkProvider.kt",
                // language=kotlin
                """
                package com.example.implementation

                import android.content.Context
                import com.example.api.AbstractSandboxedSdkProviderCompat
                import com.example.api.SdkService

                /** Provides an [SdkService] implementation when the SDK is loaded. */
                class SdkProvider : AbstractSandboxedSdkProviderCompat() {

                    /**
                     * Returns the [SdkService] implementation. Called when the SDK is loaded.
                     *
                     * This method signature (and the [AbstractSandboxedSdkProviderCompat] class) is generated by
                     * the Privacy Sandbox API Compiler plugin as the entry point for the app/SDK communication.
                     */
                    override fun createSdkService(context: Context): SdkService = SdkServiceImpl(context)
                }
            """.trimIndent()
            )
            add(
                "src/main/java/com/example/implementation/SdkSandboxedUiAdapterImpl.kt",
                // language=kotlin
                """
                package com.example.implementation

                import android.content.Context
                import android.content.res.Configuration
                import android.os.Bundle
                import android.os.IBinder
                import android.view.View
                import android.widget.TextView
                import androidx.privacysandbox.sdkruntime.core.activity.ActivityHolder
                import androidx.privacysandbox.sdkruntime.core.activity.SdkSandboxActivityHandlerCompat
                import androidx.privacysandbox.sdkruntime.core.controller.SdkSandboxControllerCompat
                import androidx.privacysandbox.ui.core.SandboxedUiAdapter
                import androidx.privacysandbox.ui.core.SessionData
                import androidx.privacysandbox.ui.provider.AbstractSandboxedUiAdapter
                import com.example.R
                import com.example.api.SdkBannerRequest
                import com.example.api.SdkSandboxedUiAdapter
                import kotlinx.coroutines.CoroutineScope
                import kotlinx.coroutines.Job
                import kotlinx.coroutines.asCoroutineDispatcher
                import kotlinx.coroutines.cancel
                import kotlinx.coroutines.launch
                import java.util.concurrent.Executor

                /**
                 * Implementation of [SdkSandboxedUiAdapter] that handles banner ad requests.
                 *
                 * This class extends [AbstractSandboxedUiAdapter] and provides the functionality to open
                 * UI sessions. The usage of [AbstractSandboxedUiAdapter] simplifies the implementation.
                 *
                 * @param sdkContext The context of the SDK.
                 * @param request The banner ad request.
                 */
                class SdkSandboxedUiAdapterImpl(
                    private val sdkContext: Context,
                    private val request: SdkBannerRequest
                ) : AbstractSandboxedUiAdapter(), SdkSandboxedUiAdapter {

                    /**
                     * Opens a new UI session to handle notifications from and to the client.
                     *
                     * @param context The context of the client.
                     * @param windowInputToken The input token of the window.
                     * @param initialWidth The initial width of the ad view.
                     * @param initialHeight The initial height of the ad view.
                     * @param isZOrderOnTop Whether the ad view should be on top of other content.
                     * @param clientExecutor The executor to use for client callbacks.
                     * @param client SessionClient of this single session.
                     */
                    override fun openSession(
                        context: Context,
                        sessionData: SessionData,
                        initialWidth: Int,
                        initialHeight: Int,
                        isZOrderOnTop: Boolean,
                        clientExecutor: Executor,
                        client: SandboxedUiAdapter.SessionClient,
                    ) {
                        val session = SdkUiSession(clientExecutor, sdkContext, request)
                        clientExecutor.execute {
                            client.onSessionOpened(session)
                        }
                    }
                }

                /**
                 * Implementation of [SandboxedUiAdapter.Session], used for banner ad requests.
                 * This class extends [AbstractSandboxedUiAdapter.AbstractSession] to provide the functionality in
                 * cohesion with [AbstractSandboxedUiAdapter]
                 *
                 * @param clientExecutor The executor to use for client callbacks.
                 * @param sdkContext The context of the SDK.
                 * @param request The banner ad request.
                 */
                private class SdkUiSession(
                    clientExecutor: Executor,
                    private val sdkContext: Context,
                    private val request: SdkBannerRequest
                ) : AbstractSandboxedUiAdapter.AbstractSession() {

                    private val controller = SdkSandboxControllerCompat.from(sdkContext)

                    /** A scope for launching coroutines in the client executor. */
                    private val scope = CoroutineScope(clientExecutor.asCoroutineDispatcher() + Job())

                    override val view: View = getAdView()

                    private fun getAdView() : View {
                        return View.inflate(sdkContext, R.layout.banner, null).apply {
                            val textView = findViewById<TextView>(R.id.banner_header_view)
                            textView.text =
                                context.getString(R.string.banner_ad_label, request.appPackageName)

                            setOnClickListener {
                                launchActivity()
                            }
                        }
                    }

                    override fun close() {
                        // Notifies that the client has closed the session. It's a good opportunity to dispose
                        // any resources that were acquired to maintain the session.
                        scope.cancel()
                    }

                    override fun notifyConfigurationChanged(configuration: Configuration) {
                        // Notifies that the device configuration has changed and affected the app.
                    }

                    override fun notifyResized(width: Int, height: Int) {
                        // Notifies that the size of the presentation area in the app has changed.
                    }

                    override fun notifyUiChanged(uiContainerInfo: Bundle) {
                        // Notify the session when the presentation state of its UI container has changed.
                    }

                    override fun notifyZOrderChanged(isZOrderOnTop: Boolean) {
                        // Notifies that the Z order has changed for the UI associated by this session.
                    }

                    private fun launchActivity() = scope.launch {
                        val handler = object : SdkSandboxActivityHandlerCompat {
                            override fun onActivityCreated(activityHolder: ActivityHolder) {
                                val contentView = View.inflate(sdkContext, R.layout.full_screen, null)
                                //contentView.setBackgroundColor(Color.GREEN)
                                activityHolder.getActivity().setContentView(contentView)
                            }
                        }

                        val token = controller.registerSdkSandboxActivityHandler(handler)
                        val launched = request.activityLauncher.launchSdkActivity(token)
                        if (!launched) controller.unregisterSdkSandboxActivityHandler(handler)
                    }
                }
            """.trimIndent()
            )
            add(
                "src/main/java/com/example/implementation/SdkServiceImpl.kt",
                // language=kotlin
                """
                package com.example.implementation

                import android.content.Context
                import android.os.Bundle
                import android.util.Log
                import com.example.api.SdkBannerRequest
                import com.example.api.SdkService
                import androidx.privacysandbox.ui.core.SandboxedSdkViewUiInfo
                import androidx.privacysandbox.ui.core.SessionObserver
                import androidx.privacysandbox.ui.core.SessionObserverContext
                import androidx.privacysandbox.ui.core.SessionObserverFactory
                import com.example.api.SdkSandboxedUiAdapter

                class SdkServiceImpl(private val context: Context) : SdkService {
                    override suspend fun getMessage(): String = "Hello from Privacy Sandbox!"

                    override suspend fun getBanner(
                        request: SdkBannerRequest
                    ): SdkSandboxedUiAdapterImpl {
                            val bannerAdAdapter = SdkSandboxedUiAdapterImpl(context, request)
                            bannerAdAdapter.addObserverFactory(SessionObserverFactoryImpl())
                            return bannerAdAdapter
                    }
                }

                /**
                 * A factory for creating [SessionObserver] instances.
                 *
                 * This class provides a way to create observers that can monitor the lifecycle of UI sessions
                 * and receive updates about UI container changes.
                 */
                private class SessionObserverFactoryImpl : SessionObserverFactory {
                    override fun create(): SessionObserver {
                        return SessionObserverImpl()
                    }

                    /**
                     * An implementation of [SessionObserver] that logs session lifecycle events and UI container
                     * information.
                     */
                    private inner class SessionObserverImpl : SessionObserver {
                        override fun onSessionOpened(sessionObserverContext: SessionObserverContext) {
                            Log.i("SessionObserver", "onSessionOpened ${'$'}sessionObserverContext")
                        }

                        /**
                         * Called when the UI container associated with a session changes.
                         *
                         * @param uiContainerInfo A Bundle containing information about the UI container,
                         * including on-screen geometry, width, height, and opacity.
                         */
                        override fun onUiContainerChanged(uiContainerInfo: Bundle) {
                            val sandboxedSdkViewUiInfo = SandboxedSdkViewUiInfo.fromBundle(uiContainerInfo)
                            val onScreen = sandboxedSdkViewUiInfo.onScreenGeometry
                            val width = sandboxedSdkViewUiInfo.uiContainerWidth
                            val height = sandboxedSdkViewUiInfo.uiContainerHeight
                            val opacity = sandboxedSdkViewUiInfo.uiContainerOpacityHint
                            Log.i("SessionObserver", "UI info: " +
                                    "On-screen geometry: ${'$'}onScreen, width: ${'$'}width, height: ${'$'}height," +
                                    " opacity: ${'$'}opacity")
                        }

                        override fun onSessionClosed() {
                            Log.i("SessionObserver", "onSessionClosed")
                        }
                    }
                }
            """.trimIndent()
            )
            add(
                "src/main/res/layout/banner.xml",
                // language=xml
                """
                <?xml version="1.0" encoding="utf-8"?>
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                    android:orientation="vertical"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:padding="4dp"
                    android:gravity="center_horizontal"
                    android:background="@android:color/holo_purple">

                    <LinearLayout
                        android:id="@+id/ad_layout"
                        android:layout_width="match_parent"
                        android:layout_height="match_parent"
                        android:background="@android:color/background_light"
                        android:orientation="vertical"
                        android:padding="16dp">

                        <TextView
                            android:id="@+id/banner_header_view"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:paddingBottom="8dp"
                            android:text="@string/banner_ad_label" />

                        <TextView
                            android:id="@+id/click_ad_header"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:text="@string/banner_ad_click_prompt" />
                    </LinearLayout>
                </LinearLayout>
            """.trimIndent()
            )
            add(
                "src/main/res/layout/full_screen.xml",
                // language=xml
                """
                <?xml version="1.0" encoding="utf-8"?>
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:gravity="center_horizontal"
                    android:orientation="vertical">

                    <TextView
                        android:id="@+id/rendered_text"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:padding="16dp"
                        android:text="@string/full_screen_ad_label" />
                </LinearLayout>
            """.trimIndent()
            )
            add(
                "src/main/res/values/strings.xml",
                // language=xml
                """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="banner_ad_label">Rendered from the SDK Runtime for %1${'$'}s</string>
                    <string name="banner_ad_click_prompt">Click this banner to launch activity from SDK Runtime.</string>
                    <string name="full_screen_ad_label">Rendered from an Activity launched and controlled by the SDK Runtime</string>
                </resources>
            """.trimIndent()
            )
            add(
                "src/main/AndroidManifest.xml",
                // language=xml
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                </manifest>
            """.trimIndent()
            )
        }
        action()
    }
}
