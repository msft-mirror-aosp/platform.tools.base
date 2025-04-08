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

package com.android.build.gradle.integration.common.output

import com.google.common.truth.ExpectFailure
import com.google.common.truth.SimpleSubjectBuilder
import com.google.common.truth.Truth
import org.jetbrains.annotations.CheckReturnValue
import org.junit.Test

class NodeSubjectTest {

    @Test
    fun namespaces() {
        Truth.assertAbout(NodeSubject.nodes())
            .that(parseManifestToNodes(MANIFEST_EXAMPLE_1.split("\n")))
            .containsExactlyNamespaces(
                "android=http://schemas.android.com/apk/res/android",
                "dist=http://schemas.android.com/apk/distribution"
            )
    }

    @Test
    fun mainNodes() {
        Truth.assertAbout(NodeSubject.nodes())
            .that(parseManifestToNodes(MANIFEST_EXAMPLE_1.split("\n")))
            .containsExactlyNodes("manifest")
    }

    @Test
    fun rootNode() {
        val node = Truth.assertAbout(NodeSubject.nodes())
            .that(parseManifestToNodes(MANIFEST_EXAMPLE_1.split("\n")))
            .node("manifest")

        node.containsExactlyAttributesAndValues(
            "http://schemas.android.com/apk/res/android:compileSdkVersion=35",
            "http://schemas.android.com/apk/res/android:versionCode=1",
            "http://schemas.android.com/apk/res/android:compileSdkVersionCodename=\"15\"",
            "package=\"com.profilabletest.app\"",
            "platformBuildVersionCode=35",
            "platformBuildVersionName=15",
        )

        node.containsExactlyNodes("uses-sdk", "application")

        node.hasNoNamespaces()
    }

    @Test
    fun chainedNodes() {
        Truth.assertAbout(NodeSubject.nodes())
            .that(parseManifestToNodes(MANIFEST_EXAMPLE_1.split("\n")))
            .node("manifest")
            .node("application")
            .node("profileable")
            .containsAttributeAndValue(
                "http://schemas.android.com/apk/res/android:enabled",
                "true",
            )
    }

    @Test
    fun nodeByAttribute() {
        val manifestContentAsNodes = parseManifestToNodes(MANIFEST_EXAMPLE_2.split("\n"))
        Truth.assertAbout(NodeSubject.nodes())
            .that(manifestContentAsNodes)
            .node("manifest")
            .node("application")
            .nodeByNameAndAttribute(
                "uses-sdk-library",
                "com.example.privacysandboxsdk"
            ).containsAttributeAndValue(
                "http://schemas.android.com/apk/res/android:name",
                "\"com.example.privacysandboxsdk\""
            )

        // test failure
        expectFailure {
            it.that(manifestContentAsNodes)
                .node("manifest")
                .node("application")
                .nodeByNameAndAttribute(
                    "uses-sdk-library",
                    "foo"
                )
        }.assert {
            factKeys()
                .containsExactly("value of", "expected to contain", "but was", "node was")
                .inOrder()
            factValue("value of")
                .isEqualTo("node.node(manifest).node(application).nodeByNameAndAttribute(uses-sdk-library, foo)")
            factValue("expected to contain").isEqualTo("foo")
            factValue("but was").isEqualTo("[com.example.privacysandboxsdk, com.example.privacysandboxsdkb]")
            factValue("node was").isEqualTo("""
                    Node(name='application')
                    >> Node content:
                     >      E: application
                     >        A: http://schemas.android.com/apk/res/android:hasCode=false
                     >        A: http://schemas.android.com/apk/res/android:appComponentFactory="androidx.core.app.CoreComponentFactory"
                     >          E: meta-data
                     >            A: http://schemas.android.com/apk/res/android:name="shadow.bundletool.com.android.vending.sdk.patch.version.comexampleprivacysandboxsdk"
                     >            A: http://schemas.android.com/apk/res/android:value=3
                     >          E: uses-sdk-library
                     >            A: http://schemas.android.com/apk/res/android:name="com.example.privacysandboxsdk"
                     >            A: http://schemas.android.com/apk/res/android:certDigest="33:7A:9B:C6:D8:10:B8:C0:A6:75:6C:83:79:B2:F9:C1:D8:9A:BF:BB:6E:21:66:BC:8C:0B:43:9A:F9:BA:18:FF"
                     >            A: http://schemas.android.com/apk/res/android:versionMajor=10002
                     >          E: uses-sdk-library
                     >            A: http://schemas.android.com/apk/res/android:name="com.example.privacysandboxsdkb"
                     >            A: http://schemas.android.com/apk/res/android:certDigest="33:7A:9B:C6:D8:10:B8:C0:A6:75:6C:83:79:B2:F9:C1:D8:9A:BF:BB:6E:21:66:BC:8C:0B:43:9A:F9:BA:18:FF"
                     >            A: http://schemas.android.com/apk/res/android:versionMajor=10002
                    << End Node content

            """.trimIndent())
        }
    }

    @Test
    fun attributeQuery() {
        Truth.assertAbout(NodeSubject.nodes())
            .that(parseManifestToNodes(MANIFEST_EXAMPLE_2.split("\n")))
            .node("manifest")
            .node("application")
            .nodeByNameAndAttribute(
                "uses-sdk-library",
                "com.example.privacysandboxsdk"
            ).attribute("http://schemas.android.com/apk/res/android:certDigest")
            .isEqualTo("\"33:7A:9B:C6:D8:10:B8:C0:A6:75:6C:83:79:B2:F9:C1:D8:9A:BF:BB:6E:21:66:BC:8C:0B:43:9A:F9:BA:18:FF\"")
    }

    // ---------

    @CheckReturnValue
    private fun expectFailure(action: (SimpleSubjectBuilder<NodeSubject, Node>) -> Unit): AssertionError {
        return ExpectFailure.expectFailureAbout(NodeSubject.nodes(), action)
    }

}

val MANIFEST_EXAMPLE_1 = """
N: android=http://schemas.android.com/apk/res/android
  N: dist=http://schemas.android.com/apk/distribution
    E: manifest
      A: http://schemas.android.com/apk/res/android:versionCode=1
      A: http://schemas.android.com/apk/res/android:compileSdkVersion=35
      A: http://schemas.android.com/apk/res/android:compileSdkVersionCodename="15"
      A: package="com.profilabletest.app"
      A: platformBuildVersionCode=35
      A: platformBuildVersionName=15
        E: uses-sdk
          A: http://schemas.android.com/apk/res/android:minSdkVersion=14
          A: http://schemas.android.com/apk/res/android:targetSdkVersion=14
        E: application
          A: http://schemas.android.com/apk/res/android:extractNativeLibs=true
            E: profileable
              A: http://schemas.android.com/apk/res/android:enabled=true
              A: http://schemas.android.com/apk/res/android:shell=true
""".trimIndent()

val MANIFEST_EXAMPLE_2 = """
N: android=http://schemas.android.com/apk/res/android
  E: manifest
    A: http://schemas.android.com/apk/res/android:versionCode=4
    A: http://schemas.android.com/apk/res/android:isFeatureSplit=true
    A: package="com.example.privacysandboxsdk.consumer"
    A: split="comexampleprivacysandboxsdk"
      E: uses-permission
        A: http://schemas.android.com/apk/res/android:name="android.permission.INTERNET"
      E: uses-permission
        A: http://schemas.android.com/apk/res/android:name="com.example.privacysandboxsdkb.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
      E: uses-permission
        A: http://schemas.android.com/apk/res/android:name="com.example.privacysandboxsdk.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
      E: application
        A: http://schemas.android.com/apk/res/android:hasCode=false
        A: http://schemas.android.com/apk/res/android:appComponentFactory="androidx.core.app.CoreComponentFactory"
          E: meta-data
            A: http://schemas.android.com/apk/res/android:name="shadow.bundletool.com.android.vending.sdk.patch.version.comexampleprivacysandboxsdk"
            A: http://schemas.android.com/apk/res/android:value=3
          E: uses-sdk-library
            A: http://schemas.android.com/apk/res/android:name="com.example.privacysandboxsdk"
            A: http://schemas.android.com/apk/res/android:certDigest="33:7A:9B:C6:D8:10:B8:C0:A6:75:6C:83:79:B2:F9:C1:D8:9A:BF:BB:6E:21:66:BC:8C:0B:43:9A:F9:BA:18:FF"
            A: http://schemas.android.com/apk/res/android:versionMajor=10002
          E: uses-sdk-library
            A: http://schemas.android.com/apk/res/android:name="com.example.privacysandboxsdkb"
            A: http://schemas.android.com/apk/res/android:certDigest="33:7A:9B:C6:D8:10:B8:C0:A6:75:6C:83:79:B2:F9:C1:D8:9A:BF:BB:6E:21:66:BC:8C:0B:43:9A:F9:BA:18:FF"
            A: http://schemas.android.com/apk/res/android:versionMajor=10002
      E: uses-sdk
        A: http://schemas.android.com/apk/res/android:minSdkVersion=23
      E: http://schemas.android.com/apk/distribution:module
          E: http://schemas.android.com/apk/distribution:delivery
              E: http://schemas.android.com/apk/distribution:install-time
                  E: http://schemas.android.com/apk/distribution:removable
                    A: http://schemas.android.com/apk/distribution:value=true
          E: http://schemas.android.com/apk/distribution:fusing
            A: http://schemas.android.com/apk/distribution:include=true
""".trimIndent()
