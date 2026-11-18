/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.manifmerger

import com.android.SdkConstants
import com.android.ide.common.blame.SourceFile
import com.android.ide.common.blame.SourceFile.UNKNOWN
import com.android.ide.common.blame.SourceFilePosition
import com.android.ide.common.blame.SourcePosition
import com.android.manifmerger.NavGraphExpander.expandNavGraphs
import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

/** Tests for [NavGraphExpander] */
class NavGraphExpanderTest {

  private val model = ManifestModel()
  private val fakeSourceFilePosition = SourceFilePosition(UNKNOWN, SourcePosition(0, 0, 0))
  private val actionRecorder: ActionRecorder = mock()
  private val mergingReportBuilder: MergingReport.Builder = mock { on { actionRecorder } doReturn actionRecorder }

  @Test
  fun testExpandNavGraphs() {

    val navigationId1 = "nav1"
    val navigationString1 =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <include app:graph="@navigation/nav2" />
      |    <deepLink app:uri="www.example.com"
      |            android:autoVerify="true" />
      |    <navigation>
      |        <deepLink app:uri="http://www.example.com:120/foo/{placeholder}" />
      |    </navigation>
      |</navigation>
      """
        .trimMargin()

    val navigationId2 = "nav2"
    val navigationString2 =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <deepLink app:uri="https://.*.example.com/.*/{placeholder}"
      |              app:action="" />
      |</navigation>
      """
        .trimMargin()

    val inputManifestString =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<manifest
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1">
      |    <application android:name="TheApp">
      |        <activity android:name=".MainActivity">
      |            <nav-graph android:value="@navigation/nav1" />
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    val expectedOutputManifestString =
      """
      |<?xml version="1.0" encoding="utf-8"?>
      |<manifest xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1" >
      |    <application android:name="com.example.app1.TheApp" >
      |        <activity android:name="com.example.app1.MainActivity" >
      |            <intent-filter android:autoVerify="true" >
      |                <action android:name="android.intent.action.VIEW" />
      |                <category android:name="android.intent.category.DEFAULT" />
      |                <category android:name="android.intent.category.BROWSABLE" />
      |                <data android:scheme="http" />
      |                <data android:scheme="https" />
      |                <data android:host="www.example.com" />
      |                <data android:path="/" />
      |            </intent-filter>
      |            <intent-filter>
      |                <action android:name="android.intent.action.VIEW" />
      |                <category android:name="android.intent.category.DEFAULT" />
      |                <category android:name="android.intent.category.BROWSABLE" />
      |                <data android:scheme="http" />
      |                <data android:host="www.example.com" />
      |                <data android:port="120" />
      |                <data android:pathPrefix="/foo/" />
      |            </intent-filter>
      |            <intent-filter>
      |                <category android:name="android.intent.category.DEFAULT" />
      |                <category android:name="android.intent.category.BROWSABLE" />
      |                <data android:scheme="https" />
      |                <data android:host="*.example.com" />
      |                <data android:pathPattern="/.*/.*" />
      |            </intent-filter>
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    val xmlDocument = TestUtils.xmlDocumentFromString(UNKNOWN, inputManifestString, model)

    val loadedNavigationMap: Map<String, NavigationXmlDocument> =
      mapOf(
        Pair(navigationId1, NavigationXmlLoader.load(UNKNOWN, navigationString1)),
        Pair(navigationId2, NavigationXmlLoader.load(UNKNOWN, navigationString2)),
      )

    val expandedXmlDocument = expandNavGraphs(xmlDocument, loadedNavigationMap, mergingReportBuilder)

    val expectedXmlDocument = TestUtils.xmlDocumentFromString(UNKNOWN, expectedOutputManifestString, model)

    assertThat(expandedXmlDocument.prettyPrint()).isEqualTo(expectedXmlDocument.prettyPrint())

    // test that actions are recorded with final NodeKeys.
    // first make lists of the intent-filters' final NodeKeys (including descendant nodes).
    val elementNodeKeys = mutableListOf<XmlNode.NodeKey>()
    val attributeNodeKeys = mutableListOf<XmlNode.NodeKey>()
    val intentFilterXmlElements =
      expandedXmlDocument.rootNode
        .getAllNodesByType(ManifestModel.NodeTypes.APPLICATION)
        .flatMap { applicationXmlElement -> applicationXmlElement.getAllNodesByType(ManifestModel.NodeTypes.ACTIVITY) }
        .flatMap { activityXmlElement -> activityXmlElement.getAllNodesByType(ManifestModel.NodeTypes.INTENT_FILTER) }
    for (intentFilterXmlElement in intentFilterXmlElements) {
      elementNodeKeys.add(intentFilterXmlElement.id)
      for (intentFilterXmlAttribute in intentFilterXmlElement.attributes) {
        attributeNodeKeys.add(intentFilterXmlAttribute.id)
      }
      for (childXmlElement in intentFilterXmlElement.mergeableElements) {
        elementNodeKeys.add(childXmlElement.id)
        for (childXmlAttribute in childXmlElement.attributes) {
          attributeNodeKeys.add(childXmlAttribute.id)
        }
      }
    }
    // then check that the recorded NodeKeys match the final NodeKeys.
    val nodeRecordCaptor = argumentCaptor<Actions.NodeRecord>()
    val attributeRecordCaptor = argumentCaptor<Actions.AttributeRecord>()
    verify(actionRecorder, atLeast(1)).recordNodeAction(any(), nodeRecordCaptor.capture())
    verify(actionRecorder, atLeast(1)).recordAttributeAction(any(), attributeRecordCaptor.capture())
    assertThat(elementNodeKeys).containsExactlyElementsIn(nodeRecordCaptor.allValues.map { it.targetId })
    assertThat(attributeNodeKeys).containsExactlyElementsIn(attributeRecordCaptor.allValues.map { it.targetId })
  }

  @Test
  fun testCircularReferenceNavGraphException() {

    val navigationId1 = "nav1"
    val navigationString1 =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <include app:graph="@navigation/nav2" />
      |</navigation>
      """
        .trimMargin()

    val navigationId2 = "nav2"
    val navigationString2 =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <include app:graph="@navigation/nav1" />
      |    <deepLink app:uri="www.example.com" />
      |</navigation>
      """
        .trimMargin()

    val inputManifestString =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<manifest
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1">
      |    <application android:name="TheApp">
      |        <activity android:name=".MainActivity">
      |            <nav-graph android:value="@navigation/nav1" />
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    val xmlDocument = TestUtils.xmlDocumentFromString(UNKNOWN, inputManifestString, model)

    val loadedNavigationMap: Map<String, NavigationXmlDocument> =
      mapOf(
        Pair(navigationId1, NavigationXmlLoader.load(UNKNOWN, navigationString1)),
        Pair(navigationId2, NavigationXmlLoader.load(UNKNOWN, navigationString2)),
      )

    expandNavGraphs(xmlDocument, loadedNavigationMap, mergingReportBuilder)

    // verify the error was recorded.
    verify(mergingReportBuilder)
      .addMessage(
        any<SourceFilePosition>(),
        eq(MergingReport.Record.Severity.ERROR),
        eq("Illegal circular reference among navigation files when traversing " + "navigation file references: nav1 > nav2 > nav1."),
      )
  }

  @Test
  fun testDuplicateNavigationFileWarning() {

    val navigationId1 = "nav1"
    val navigationString1 =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <include app:graph="@navigation/nav2" />
      |    <include app:graph="@navigation/nav3" />
      |</navigation>
      """
        .trimMargin()

    val navigationId2 = "nav2"
    val navigationString2 =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <include app:graph="@navigation/nav3" />
      |    <deepLink app:uri="www.example.com" />
      |</navigation>
      """
        .trimMargin()

    val navigationId3 = "nav3"
    val navigationString3 =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <deepLink app:uri="www.example.com/foo" />
      |</navigation>
      """
        .trimMargin()

    val inputManifestString =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<manifest
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1">
      |    <application android:name="TheApp">
      |        <activity android:name=".MainActivity">
      |            <nav-graph android:value="@navigation/nav1" />
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    val xmlDocument = TestUtils.xmlDocumentFromString(UNKNOWN, inputManifestString, model)

    val loadedNavigationMap: Map<String, NavigationXmlDocument> =
      mapOf(
        Pair(navigationId1, NavigationXmlLoader.load(UNKNOWN, navigationString1)),
        Pair(navigationId2, NavigationXmlLoader.load(UNKNOWN, navigationString2)),
        Pair(navigationId3, NavigationXmlLoader.load(UNKNOWN, navigationString3)),
      )

    expandNavGraphs(xmlDocument, loadedNavigationMap, mergingReportBuilder)

    // verify the warning was recorded.
    verify(mergingReportBuilder)
      .addMessage(
        any<SourceFilePosition>(),
        eq(MergingReport.Record.Severity.WARNING),
        eq(
          "The navigation file with ID \"nav3\" is included multiple times in " +
            "the navigation graph, but only deep links on the first instance will be " +
            "triggered at runtime. Consider consolidating these instances into a " +
            "single <include> at a higher level of your navigation graph hierarchy."
        ),
      )
  }

  @Test
  fun testFindDeepLinks() {

    val navigationId1 = "nav1"
    val navigationString1 =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <include app:graph="@navigation/nav2" />
      |    <deepLink app:uri="http://www.example.com?param=foo#fragment" />
      |</navigation>
      """
        .trimMargin()

    val navigationId2 = "nav2"
    val navigationString2 =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <deepLink app:uri="www.example.com/foo/?param={bar}" />
      |</navigation>
      """
        .trimMargin()

    val loadedNavigationMap: Map<String, NavigationXmlDocument> =
      mapOf(
        Pair(navigationId1, NavigationXmlLoader.load(UNKNOWN, navigationString1)),
        Pair(navigationId2, NavigationXmlLoader.load(UNKNOWN, navigationString2)),
      )

    val deepLinks = NavGraphExpander.findDeepLinks(navigationId1, loadedNavigationMap, mergingReportBuilder, fakeSourceFilePosition)
    assertThat(deepLinks)
      .containsExactly(
        DeepLink(
          ImmutableList.of("http"),
          "www.example.com",
          -1,
          "/",
          "param=foo",
          "fragment",
          SourceFilePosition(UNKNOWN, SourcePosition(5, 4, 220, 5, 68, 284)),
          false,
        ),
        DeepLink(
          ImmutableList.of("http", "https"),
          "www.example.com",
          -1,
          "/foo/",
          "param=.*",
          null,
          SourceFilePosition(UNKNOWN, SourcePosition(4, 4, 175, 4, 59, 230)),
          false,
        ),
      )
  }

  @Test
  fun testDuplicateDeepLinkNavGraphException() {

    val navigationId1 = "nav1"
    val navigationString1 =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <include app:graph="@navigation/nav2" />
      |    <deepLink app:uri="http://www.example.com"
      |        app:action="android.intent.action.APP_ACTION"
      |        app:mimeType="app/image/jpg" />
      |</navigation>
      """
        .trimMargin()

    val navigationId2 = "nav2"
    val navigationString2 =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <deepLink app:uri="http://www.example.com"
      |        app:action="android.intent.action.APP_ACTION"
      |        app:mimeType="app/image/jpg" />
      |</navigation>
      """
        .trimMargin()

    val inputManifestString =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<manifest
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1">
      |    <application android:name="TheApp">
      |        <activity android:name=".MainActivity">
      |            <nav-graph android:value="@navigation/nav1" />
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    val xmlDocument = TestUtils.xmlDocumentFromString(UNKNOWN, inputManifestString, model)

    val loadedNavigationMap: Map<String, NavigationXmlDocument> =
      mapOf(
        Pair(navigationId1, NavigationXmlLoader.load(UNKNOWN, navigationString1)),
        Pair(navigationId2, NavigationXmlLoader.load(UNKNOWN, navigationString2)),
      )

    expandNavGraphs(xmlDocument, loadedNavigationMap, mergingReportBuilder)

    // verify the error was recorded.
    verify(mergingReportBuilder)
      .addMessage(
        any<SourceFilePosition>(),
        eq(MergingReport.Record.Severity.ERROR),
        eq(
          "Multiple destinations found with a deep link containing " +
            "uri:http://www.example.com/, " +
            "action:android.intent.action.APP_ACTION, " +
            "mimeType:app/image/jpg."
        ),
      )
  }

  @Test
  fun testDeepLinksDifferInQueryGoToOneIntentFilter() {
    val navigationString1 =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <include app:graph="@navigation/nav2" />
      |    <deepLink app:uri="http://www.example.com?foo=bar" />
      |</navigation>
      """
        .trimMargin()

    val navigationString2 =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <deepLink app:uri="http://www.example.com" />
      |</navigation>
      """
        .trimMargin()

    val expectedOutputManifestString =
      """
      |<?xml version="1.0" encoding="utf-8"?>
      |<manifest xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1" >
      |    <application android:name="com.example.app1.TheApp" >
      |        <activity android:name="com.example.app1.MainActivity" >
      |            <intent-filter>
      |                <action android:name="android.intent.action.VIEW" />
      |                <category android:name="android.intent.category.DEFAULT" />
      |                <category android:name="android.intent.category.BROWSABLE" />
      |                <data android:scheme="http" />
      |                <data android:host="www.example.com" />
      |                <data android:path="/" />
      |            </intent-filter>
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    validateDeepLinksGoToOneIntentFilter(expectedOutputManifestString, navigationString1, navigationString2)
  }

  @Test
  fun testDuplicateDeepLinkWithQueryNavGraphException() {

    val navigationId1 = "nav1"
    val navigationString1 =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <include app:graph="@navigation/nav2" />
      |    <deepLink app:uri="http://www.example.com?foo={bar}" />
      |</navigation>
      """
        .trimMargin()

    val navigationId2 = "nav2"
    val navigationString2 =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <deepLink app:uri="www.example.com?foo={qwe}" />
      |</navigation>
      """
        .trimMargin()

    val inputManifestString =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<manifest
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1">
      |    <application android:name="TheApp">
      |        <activity android:name=".MainActivity">
      |            <nav-graph android:value="@navigation/nav1" />
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    val xmlDocument = TestUtils.xmlDocumentFromString(UNKNOWN, inputManifestString, model)

    val loadedNavigationMap: Map<String, NavigationXmlDocument> =
      mapOf(
        Pair(navigationId1, NavigationXmlLoader.load(UNKNOWN, navigationString1)),
        Pair(navigationId2, NavigationXmlLoader.load(UNKNOWN, navigationString2)),
      )

    expandNavGraphs(xmlDocument, loadedNavigationMap, mergingReportBuilder)

    // verify the error was recorded.
    verify(mergingReportBuilder)
      .addMessage(
        any<SourceFilePosition>(),
        eq(MergingReport.Record.Severity.ERROR),
        eq(
          "Multiple destinations found with a deep link containing " +
            "uri:http://www.example.com/?foo=.*, action:android.intent.action.VIEW."
        ),
      )
  }

  @Test
  fun testDeepLinksDifferInFragmentGoToOneIntentFilter() {
    val navigationString1 =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <include app:graph="@navigation/nav2" />
      |    <deepLink app:uri="http://www.example.com#fragment" />
      |</navigation>
      """
        .trimMargin()

    val navigationString2 =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <deepLink app:uri="http://www.example.com" />
      |</navigation>
      """
        .trimMargin()

    val expectedOutputManifestString =
      """
      |<?xml version="1.0" encoding="utf-8"?>
      |<manifest xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1" >
      |    <application android:name="com.example.app1.TheApp" >
      |        <activity android:name="com.example.app1.MainActivity" >
      |            <intent-filter>
      |                <action android:name="android.intent.action.VIEW" />
      |                <category android:name="android.intent.category.DEFAULT" />
      |                <category android:name="android.intent.category.BROWSABLE" />
      |                <data android:scheme="http" />
      |                <data android:host="www.example.com" />
      |                <data android:path="/" />
      |            </intent-filter>
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    validateDeepLinksGoToOneIntentFilter(expectedOutputManifestString, navigationString1, navigationString2)
  }

  @Test
  fun testLinksDifferOnlyInSchemeGoToOneIntentFilter() {
    val navigationString1 =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <include app:graph="@navigation/nav2" />
      |    <deepLink app:uri="http://www.example.com" />
      |</navigation>
      """
        .trimMargin()

    val navigationString2 =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <deepLink app:uri="https://www.example.com" />
      |</navigation>
      """
        .trimMargin()

    val expectedOutputManifestString =
      """
      |<?xml version="1.0" encoding="utf-8"?>
      |<manifest xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1" >
      |    <application android:name="com.example.app1.TheApp" >
      |        <activity android:name="com.example.app1.MainActivity" >
      |            <intent-filter>
      |                <action android:name="android.intent.action.VIEW" />
      |                <category android:name="android.intent.category.DEFAULT" />
      |                <category android:name="android.intent.category.BROWSABLE" />
      |                <data android:scheme="http" />
      |                <data android:scheme="https" />
      |                <data android:host="www.example.com" />
      |                <data android:path="/" />
      |            </intent-filter>
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    validateDeepLinksGoToOneIntentFilter(expectedOutputManifestString, navigationString1, navigationString2)
  }

  @Test
  fun testLinksDifferInSchemeAndQueryGoToOneIntentFilter() {
    val navigationString1 =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <include app:graph="@navigation/nav2" />
      |    <deepLink app:uri="http://www.example.com" />
      |</navigation>
      """
        .trimMargin()

    val navigationString2 =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <deepLink app:uri="https://www.example.com" />
      |    <include app:graph="@navigation/nav3" />
      |</navigation>
      """
        .trimMargin()

    val navigationString3 =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <deepLink app:uri="www.example.com?foo=bar" />
      |</navigation>
      """
        .trimMargin()

    val expectedOutputManifestString =
      """
      |<?xml version="1.0" encoding="utf-8"?>
      |<manifest xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1" >
      |    <application android:name="com.example.app1.TheApp" >
      |        <activity android:name="com.example.app1.MainActivity" >
      |            <intent-filter>
      |                <action android:name="android.intent.action.VIEW" />
      |                <category android:name="android.intent.category.DEFAULT" />
      |                <category android:name="android.intent.category.BROWSABLE" />
      |                <data android:scheme="http" />
      |                <data android:scheme="https" />
      |                <data android:host="www.example.com" />
      |                <data android:path="/" />
      |            </intent-filter>
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    validateDeepLinksGoToOneIntentFilter(expectedOutputManifestString, navigationString1, navigationString2, navigationString3)
  }

  @Test
  fun testMultipleErrorsReported() {
    val navigationId1 = "nav1"
    val navigationString1 =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <include app:graph="@navigation/nav2" />
      |    <include app:graph="@navigation/nav3" />
      |</navigation>
      """
        .trimMargin()

    val navigationId2 = "nav2"
    val navigationString2 =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <include app:graph="@navigation/nav1" />
      |</navigation>
      """
        .trimMargin()

    // nav3 is missing from loadedNavigationMap

    val inputManifestString =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<manifest
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1">
      |    <application android:name="TheApp">
      |        <activity android:name=".MainActivity">
      |            <nav-graph android:value="@navigation/nav1" />
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    val xmlDocument = TestUtils.xmlDocumentFromString(UNKNOWN, inputManifestString, model)

    val loadedNavigationMap: Map<String, NavigationXmlDocument> =
      mapOf(
        Pair(navigationId1, NavigationXmlLoader.load(UNKNOWN, navigationString1)),
        Pair(navigationId2, NavigationXmlLoader.load(UNKNOWN, navigationString2)),
      )

    expandNavGraphs(xmlDocument, loadedNavigationMap, mergingReportBuilder)

    // verify both errors were recorded.
    verify(mergingReportBuilder)
      .addMessage(
        any<SourceFilePosition>(),
        eq(MergingReport.Record.Severity.ERROR),
        eq("Illegal circular reference among navigation files when traversing navigation file references: nav1 > nav2 > nav1."),
      )
    verify(mergingReportBuilder)
      .addMessage(
        any<SourceFilePosition>(),
        eq(MergingReport.Record.Severity.ERROR),
        eq("Referenced navigation file with navigationXmlId = nav3 not found"),
      )
  }

  @Test
  fun testMalformedNavGraphDoesNotSkipValidSiblings() {
    val validNavigationString1 =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <deepLink app:uri="https://www.example.com/first" />
      |</navigation>
      """
        .trimMargin()
    val validNavigationString2 =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <deepLink app:uri="https://www.example.com/second" />
      |</navigation>
      """
        .trimMargin()

    val inputManifestString =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<manifest
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1">
      |    <application android:name="TheApp">
      |        <activity android:name=".MainActivity">
      |            <nav-graph android:value="@navigation/nav1" />
      |            <nav-graph />
      |            <nav-graph android:value="malformed" />
      |            <nav-graph android:value="@navigation/nav2" />
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    val expectedOutputManifestString =
      """
      |<?xml version="1.0" encoding="utf-8"?>
      |<manifest xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1" >
      |    <application android:name="com.example.app1.TheApp" >
      |        <activity android:name="com.example.app1.MainActivity" >
      |            <intent-filter>
      |                <action android:name="android.intent.action.VIEW" />
      |                <category android:name="android.intent.category.DEFAULT" />
      |                <category android:name="android.intent.category.BROWSABLE" />
      |                <data android:scheme="https" />
      |                <data android:host="www.example.com" />
      |                <data android:path="/first" />
      |            </intent-filter>
      |            <intent-filter>
      |                <action android:name="android.intent.action.VIEW" />
      |                <category android:name="android.intent.category.DEFAULT" />
      |                <category android:name="android.intent.category.BROWSABLE" />
      |                <data android:scheme="https" />
      |                <data android:host="www.example.com" />
      |                <data android:path="/second" />
      |            </intent-filter>
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    val xmlDocument = TestUtils.xmlDocumentFromString(UNKNOWN, inputManifestString, model)
    val loadedNavigationMap =
      mapOf(
        "nav1" to NavigationXmlLoader.load(UNKNOWN, validNavigationString1),
        "nav2" to NavigationXmlLoader.load(UNKNOWN, validNavigationString2),
      )

    val expandedXmlDocument = expandNavGraphs(xmlDocument, loadedNavigationMap, mergingReportBuilder)

    val expectedXmlDocument = TestUtils.xmlDocumentFromString(UNKNOWN, expectedOutputManifestString, model)
    assertThat(expandedXmlDocument.prettyPrint()).isEqualTo(expectedXmlDocument.prettyPrint())

    val expectedErrorMessage =
      "Missing or malformed attribute in <nav-graph> element. " +
        "Android manifest <nav-graph> element must contain a android:${SdkConstants.ATTR_VALUE} " +
        "attribute with a value beginning with \"${SdkConstants.NAVIGATION_PREFIX}\"."
    val errorPositionCaptor = argumentCaptor<SourceFilePosition>()
    verify(mergingReportBuilder, times(2))
      .addMessage(
        errorPositionCaptor.capture(),
        eq(MergingReport.Record.Severity.ERROR),
        eq(expectedErrorMessage),
      )
    assertThat(errorPositionCaptor.allValues.map { it.position.startLine }).containsExactly(7, 8).inOrder()

    verify(actionRecorder, times(2)).recordNodeAction(any(), eq(Actions.ActionType.CONVERTED))
    verify(actionRecorder, times(2)).recordNodeAction(any(), eq(Actions.ActionType.REJECTED))
  }

  @Test
  fun testMalformedNavGraphIsRemovedFromOutput() {
    val inputManifestString =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<manifest
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1">
      |    <application android:name="TheApp">
      |        <activity android:name=".MainActivity">
      |            <nav-graph />
      |            <nav-graph android:value="malformed" />
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    val expectedOutputManifestString =
      """
      |<?xml version="1.0" encoding="utf-8"?>
      |<manifest xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1" >
      |    <application android:name="com.example.app1.TheApp" >
      |        <activity android:name="com.example.app1.MainActivity" >
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    val xmlDocument = TestUtils.xmlDocumentFromString(UNKNOWN, inputManifestString, model)

    val expandedXmlDocument = expandNavGraphs(xmlDocument, emptyMap(), mergingReportBuilder)

    val expectedXmlDocument = TestUtils.xmlDocumentFromString(UNKNOWN, expectedOutputManifestString, model)
    assertThat(expandedXmlDocument.prettyPrint()).isEqualTo(expectedXmlDocument.prettyPrint())
    val activity =
      expandedXmlDocument.rootNode
        .getAllNodesByType(ManifestModel.NodeTypes.APPLICATION)
        .single()
        .getAllNodesByType(ManifestModel.NodeTypes.ACTIVITY)
        .single()
    assertThat(activity.getAllNodesByType(ManifestModel.NodeTypes.NAV_GRAPH)).isEmpty()
    assertThat(activity.getAllNodesByType(ManifestModel.NodeTypes.INTENT_FILTER)).isEmpty()

    val expectedErrorMessage =
      "Missing or malformed attribute in <nav-graph> element. " +
        "Android manifest <nav-graph> element must contain a android:${SdkConstants.ATTR_VALUE} " +
        "attribute with a value beginning with \"${SdkConstants.NAVIGATION_PREFIX}\"."
    val errorPositionCaptor = argumentCaptor<SourceFilePosition>()
    verify(mergingReportBuilder, times(2))
      .addMessage(
        errorPositionCaptor.capture(),
        eq(MergingReport.Record.Severity.ERROR),
        eq(expectedErrorMessage),
      )
    assertThat(errorPositionCaptor.allValues.map { it.position.startLine }).containsExactly(6, 7).inOrder()

    verify(actionRecorder, never()).recordNodeAction(any(), eq(Actions.ActionType.CONVERTED))
    verify(actionRecorder, times(2)).recordNodeAction(any(), eq(Actions.ActionType.REJECTED))
  }

  @Test
  fun testExpandNavGraphsContinuesAfterNavigationXmlAndDeepLinkErrors() {
    val nav1SourceFile = SourceFile(File("nav1.xml"), "nav1")
    val nav2SourceFile = SourceFile(File("nav2.xml"), "nav2")
    val nav1String =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <deepLink app:uri="https://www.example.com/first" />
      |    <deepLink />
      |    <deepLink app:uri="https://www.example.com/blank-mime" app:mimeType=" " />
      |    <deepLink app:uri="http://www.*.com" />
      |    <include />
      |    <include app:graph="@navigation/nav2" />
      |</navigation>
      """
        .trimMargin()
    val nav2String =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <deepLink />
      |    <include />
      |    <deepLink app:uri="https://www.example.com/second" />
      |</navigation>
      """
        .trimMargin()

    val inputManifestString =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<manifest
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1">
      |    <application android:name="TheApp">
      |        <activity android:name=".MainActivity">
      |            <nav-graph android:value="@navigation/nav1" />
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    val expectedOutputManifestString =
      """
      |<?xml version="1.0" encoding="utf-8"?>
      |<manifest xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1" >
      |    <application android:name="com.example.app1.TheApp" >
      |        <activity android:name="com.example.app1.MainActivity" >
      |            <intent-filter>
      |                <action android:name="android.intent.action.VIEW" />
      |                <category android:name="android.intent.category.DEFAULT" />
      |                <category android:name="android.intent.category.BROWSABLE" />
      |                <data android:scheme="https" />
      |                <data android:host="www.example.com" />
      |                <data android:path="/first" />
      |            </intent-filter>
      |            <intent-filter>
      |                <action android:name="android.intent.action.VIEW" />
      |                <category android:name="android.intent.category.DEFAULT" />
      |                <category android:name="android.intent.category.BROWSABLE" />
      |                <data android:scheme="https" />
      |                <data android:host="www.example.com" />
      |                <data android:path="/second" />
      |            </intent-filter>
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    val xmlDocument = TestUtils.xmlDocumentFromString(UNKNOWN, inputManifestString, model)
    val loadedNavigationMap =
      mapOf(
        "nav1" to NavigationXmlLoader.load(nav1SourceFile, nav1String),
        "nav2" to NavigationXmlLoader.load(nav2SourceFile, nav2String),
      )

    val expandedXmlDocument = expandNavGraphs(xmlDocument, loadedNavigationMap, mergingReportBuilder)

    val expectedXmlDocument = TestUtils.xmlDocumentFromString(UNKNOWN, expectedOutputManifestString, model)
    assertThat(expandedXmlDocument.prettyPrint()).isEqualTo(expectedXmlDocument.prettyPrint())

    val posCaptor = argumentCaptor<SourceFilePosition>()
    val msgCaptor = argumentCaptor<String>()
    verify(mergingReportBuilder, times(6))
      .addMessage(
        posCaptor.capture(),
        eq(MergingReport.Record.Severity.ERROR),
        msgCaptor.capture(),
      )
    assertThat(posCaptor.allValues.map { it.file })
      .containsExactly(
        nav1SourceFile,
        nav1SourceFile,
        nav1SourceFile,
        nav1SourceFile,
        nav2SourceFile,
        nav2SourceFile,
      )
      .inOrder()
    // PositionXmlParser reports 0-based startLine numbers (e.g., index 5 is the 6th line of the XML).
    assertThat(posCaptor.allValues.map { it.position.startLine }).containsExactly(5, 6, 7, 8, 4, 5).inOrder()
    assertThat(msgCaptor.allValues)
      .containsExactly(
        "Navigation XML document <deepLink> element must contain a app:${SdkConstants.ATTR_URI} attribute.",
        "Navigation XML document <deepLink> element ${SdkConstants.ATTR_DEEPLINK_MIMETYPE} cannot be blank.",
        "Improper use of wildcards and/or placeholders in deeplink URI host",
        "Navigation XML document <include> element must contain a app:${SdkConstants.ATTR_GRAPH} attribute, " +
          "whose value begins with ${SdkConstants.NAVIGATION_PREFIX}.",
        "Navigation XML document <deepLink> element must contain a app:${SdkConstants.ATTR_URI} attribute.",
        "Navigation XML document <include> element must contain a app:${SdkConstants.ATTR_GRAPH} attribute, " +
          "whose value begins with ${SdkConstants.NAVIGATION_PREFIX}.",
      )
      .inOrder()
  }

  @Test
  fun testExpandNavGraphsAllDeepLinksInvalid() {
    val nav1SourceFile = SourceFile(File("nav1.xml"), "nav1")
    val nav1String =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <deepLink />
      |    <deepLink app:uri="%http://www.example.com" />
      |</navigation>
      """
        .trimMargin()

    val inputManifestString =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<manifest
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1">
      |    <application android:name="TheApp">
      |        <activity android:name=".MainActivity">
      |            <nav-graph android:value="@navigation/nav1" />
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    val expectedOutputManifestString =
      """
      |<?xml version="1.0" encoding="utf-8"?>
      |<manifest xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1" >
      |    <application android:name="com.example.app1.TheApp" >
      |        <activity android:name="com.example.app1.MainActivity" >
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    val xmlDocument = TestUtils.xmlDocumentFromString(UNKNOWN, inputManifestString, model)
    val loadedNavigationMap = mapOf("nav1" to NavigationXmlLoader.load(nav1SourceFile, nav1String))

    val expandedXmlDocument = expandNavGraphs(xmlDocument, loadedNavigationMap, mergingReportBuilder)

    val expectedXmlDocument = TestUtils.xmlDocumentFromString(UNKNOWN, expectedOutputManifestString, model)
    assertThat(expandedXmlDocument.prettyPrint()).isEqualTo(expectedXmlDocument.prettyPrint())

    val posCaptor = argumentCaptor<SourceFilePosition>()
    val msgCaptor = argumentCaptor<String>()
    verify(mergingReportBuilder, times(2))
      .addMessage(
        posCaptor.capture(),
        eq(MergingReport.Record.Severity.ERROR),
        msgCaptor.capture(),
      )
    assertThat(posCaptor.allValues.map { it.file }).containsExactly(nav1SourceFile, nav1SourceFile).inOrder()
    assertThat(posCaptor.allValues.map { it.position.startLine }).containsExactly(4, 5).inOrder()
    assertThat(msgCaptor.allValues[0])
      .isEqualTo("Navigation XML document <deepLink> element must contain a app:${SdkConstants.ATTR_URI} attribute.")
    // URISyntaxException failures are prefixed with <deepLink> context without the Java class-name prefix.
    assertThat(msgCaptor.allValues[1]).startsWith("Navigation XML document <deepLink> element app:${SdkConstants.ATTR_URI} is invalid: ")
    assertThat(msgCaptor.allValues[1]).doesNotContain("java.net.URISyntaxException")
    assertThat(msgCaptor.allValues[1]).contains("%http://www.example.com")

    // The <nav-graph> element itself is well-formed, so it is recorded as CONVERTED (matching valid
    // navigation graphs with zero deep links) rather than REJECTED.
    verify(actionRecorder).recordNodeAction(any(), eq(Actions.ActionType.CONVERTED))
    verify(actionRecorder, never()).recordNodeAction(any(), eq(Actions.ActionType.REJECTED))
  }

  @Test
  fun testExpandNavGraphsCombinesElementErrorsAndGraphReferenceErrorsWithoutDuplicatingAcrossActivities() {
    val manifestSourceFile = SourceFile(File("AndroidManifest.xml"), "main")
    val nav1SourceFile = SourceFile(File("nav1.xml"), "nav1")
    val nav2SourceFile = SourceFile(File("nav2.xml"), "nav2")
    val nav1String =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <deepLink app:uri="https://www.example.com/first" />
      |    <deepLink />
      |    <include />
      |    <include app:graph="@navigation/missing_nav" />
      |    <include app:graph="@navigation/nav2" />
      |</navigation>
      """
        .trimMargin()
    val nav2String =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <deepLink app:uri="https://www.example.com/second" />
      |    <include app:graph="@navigation/nav1" />
      |</navigation>
      """
        .trimMargin()

    val inputManifestString =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<manifest
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1">
      |    <application android:name="TheApp">
      |        <activity android:name=".MainActivity">
      |            <nav-graph android:value="@navigation/nav1" />
      |        </activity>
      |        <activity android:name=".SecondActivity">
      |            <nav-graph android:value="@navigation/nav1" />
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    val expectedOutputManifestString =
      """
      |<?xml version="1.0" encoding="utf-8"?>
      |<manifest xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1" >
      |    <application android:name="com.example.app1.TheApp" >
      |        <activity android:name="com.example.app1.MainActivity" >
      |            <intent-filter>
      |                <action android:name="android.intent.action.VIEW" />
      |                <category android:name="android.intent.category.DEFAULT" />
      |                <category android:name="android.intent.category.BROWSABLE" />
      |                <data android:scheme="https" />
      |                <data android:host="www.example.com" />
      |                <data android:path="/first" />
      |            </intent-filter>
      |            <intent-filter>
      |                <action android:name="android.intent.action.VIEW" />
      |                <category android:name="android.intent.category.DEFAULT" />
      |                <category android:name="android.intent.category.BROWSABLE" />
      |                <data android:scheme="https" />
      |                <data android:host="www.example.com" />
      |                <data android:path="/second" />
      |            </intent-filter>
      |        </activity>
      |        <activity android:name="com.example.app1.SecondActivity" >
      |            <intent-filter>
      |                <action android:name="android.intent.action.VIEW" />
      |                <category android:name="android.intent.category.DEFAULT" />
      |                <category android:name="android.intent.category.BROWSABLE" />
      |                <data android:scheme="https" />
      |                <data android:host="www.example.com" />
      |                <data android:path="/first" />
      |            </intent-filter>
      |            <intent-filter>
      |                <action android:name="android.intent.action.VIEW" />
      |                <category android:name="android.intent.category.DEFAULT" />
      |                <category android:name="android.intent.category.BROWSABLE" />
      |                <data android:scheme="https" />
      |                <data android:host="www.example.com" />
      |                <data android:path="/second" />
      |            </intent-filter>
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    val xmlDocument = TestUtils.xmlDocumentFromString(manifestSourceFile, inputManifestString, model)
    val loadedNavigationMap =
      mapOf(
        "nav1" to NavigationXmlLoader.load(nav1SourceFile, nav1String),
        "nav2" to NavigationXmlLoader.load(nav2SourceFile, nav2String),
      )

    val expandedXmlDocument = expandNavGraphs(xmlDocument, loadedNavigationMap, mergingReportBuilder)

    val expectedXmlDocument = TestUtils.xmlDocumentFromString(UNKNOWN, expectedOutputManifestString, model)
    assertThat(expandedXmlDocument.prettyPrint()).isEqualTo(expectedXmlDocument.prettyPrint())

    // Element-level errors in nav1.xml are recorded once at their exact lines despite two activities referencing nav1.
    val deepLinkPosCaptor = argumentCaptor<SourceFilePosition>()
    verify(mergingReportBuilder)
      .addMessage(
        deepLinkPosCaptor.capture(),
        eq(MergingReport.Record.Severity.ERROR),
        eq("Navigation XML document <deepLink> element must contain a app:${SdkConstants.ATTR_URI} attribute."),
      )
    assertThat(deepLinkPosCaptor.firstValue.file).isEqualTo(nav1SourceFile)
    assertThat(deepLinkPosCaptor.firstValue.position.startLine).isEqualTo(5)

    val includePosCaptor = argumentCaptor<SourceFilePosition>()
    verify(mergingReportBuilder)
      .addMessage(
        includePosCaptor.capture(),
        eq(MergingReport.Record.Severity.ERROR),
        eq(
          "Navigation XML document <include> element must contain a app:${SdkConstants.ATTR_GRAPH} attribute, " +
            "whose value begins with ${SdkConstants.NAVIGATION_PREFIX}."
        ),
      )
    assertThat(includePosCaptor.firstValue.file).isEqualTo(nav1SourceFile)
    assertThat(includePosCaptor.firstValue.position.startLine).isEqualTo(6)

    // Missing navigation file and circular reference errors are recorded per activity at each activity's position.
    val missingNavPosCaptor = argumentCaptor<SourceFilePosition>()
    verify(mergingReportBuilder, times(2))
      .addMessage(
        missingNavPosCaptor.capture(),
        eq(MergingReport.Record.Severity.ERROR),
        eq("Referenced navigation file with navigationXmlId = missing_nav not found"),
      )
    assertThat(missingNavPosCaptor.allValues.map { it.file }).containsExactly(manifestSourceFile, manifestSourceFile).inOrder()
    assertThat(missingNavPosCaptor.allValues.map { it.position.startLine }).containsExactly(5, 8).inOrder()

    val circularPosCaptor = argumentCaptor<SourceFilePosition>()
    verify(mergingReportBuilder, times(2))
      .addMessage(
        circularPosCaptor.capture(),
        eq(MergingReport.Record.Severity.ERROR),
        eq("Illegal circular reference among navigation files when traversing navigation file references: nav1 > nav2 > nav1."),
      )
    assertThat(circularPosCaptor.allValues.map { it.file }).containsExactly(manifestSourceFile, manifestSourceFile).inOrder()
    assertThat(circularPosCaptor.allValues.map { it.position.startLine }).containsExactly(5, 8).inOrder()

    verify(mergingReportBuilder, times(6)).addMessage(any<SourceFilePosition>(), eq(MergingReport.Record.Severity.ERROR), any())
  }

  @Test
  fun testExpandNavGraphsReportsErrorsOnEachMergePass() {
    val nav1SourceFile = SourceFile(File("nav1.xml"), "nav1")
    val nav1String =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <deepLink />
      |    <deepLink app:uri="https://www.example.com/ok" />
      |</navigation>
      """
        .trimMargin()
    val inputManifestString =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<manifest
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1">
      |    <application android:name="TheApp">
      |        <activity android:name=".MainActivity">
      |            <nav-graph android:value="@navigation/nav1" />
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    val sharedLoadedNavigationMap = mapOf("nav1" to NavigationXmlLoader.load(nav1SourceFile, nav1String))
    val firstBuilder: MergingReport.Builder = mock { on { actionRecorder } doReturn actionRecorder }
    val secondBuilder: MergingReport.Builder = mock { on { actionRecorder } doReturn actionRecorder }

    expandNavGraphs(TestUtils.xmlDocumentFromString(UNKNOWN, inputManifestString, model), sharedLoadedNavigationMap, firstBuilder)
    expandNavGraphs(TestUtils.xmlDocumentFromString(UNKNOWN, inputManifestString, model), sharedLoadedNavigationMap, secondBuilder)

    val expectedErrorMessage = "Navigation XML document <deepLink> element must contain a app:${SdkConstants.ATTR_URI} attribute."
    verify(firstBuilder).addMessage(any<SourceFilePosition>(), eq(MergingReport.Record.Severity.ERROR), eq(expectedErrorMessage))
    verify(secondBuilder).addMessage(any<SourceFilePosition>(), eq(MergingReport.Record.Severity.ERROR), eq(expectedErrorMessage))
  }

  @Test
  fun testDuplicateDeepLinkDetectedAfterInvalidSiblingDeepLink() {
    val nav1SourceFile = SourceFile(File("nav1.xml"), "nav1")
    val nav1String =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <deepLink />
      |    <deepLink app:uri="https://www.example.com/dup" />
      |    <include app:graph="@navigation/nav2" />
      |</navigation>
      """
        .trimMargin()
    val nav2String =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <deepLink app:uri="https://www.example.com/dup" />
      |</navigation>
      """
        .trimMargin()
    val inputManifestString =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<manifest
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1">
      |    <application android:name="TheApp">
      |        <activity android:name=".MainActivity">
      |            <nav-graph android:value="@navigation/nav1" />
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    val xmlDocument = TestUtils.xmlDocumentFromString(UNKNOWN, inputManifestString, model)
    val loadedNavigationMap =
      mapOf(
        "nav1" to NavigationXmlLoader.load(nav1SourceFile, nav1String),
        "nav2" to NavigationXmlLoader.load(UNKNOWN, nav2String),
      )

    expandNavGraphs(xmlDocument, loadedNavigationMap, mergingReportBuilder)

    verify(mergingReportBuilder)
      .addMessage(
        any<SourceFilePosition>(),
        eq(MergingReport.Record.Severity.ERROR),
        eq("Navigation XML document <deepLink> element must contain a app:${SdkConstants.ATTR_URI} attribute."),
      )
    verify(mergingReportBuilder)
      .addMessage(
        any<SourceFilePosition>(),
        eq(MergingReport.Record.Severity.ERROR),
        eq("Multiple destinations found with a deep link containing uri:https://www.example.com/dup, action:android.intent.action.VIEW."),
      )
    verify(mergingReportBuilder, times(2)).addMessage(any<SourceFilePosition>(), eq(MergingReport.Record.Severity.ERROR), any())
  }

  @Test
  fun testSingleActivityWithMultipleNavGraphsReferencingSameFileDeduplicatesElementErrors() {
    val nav1SourceFile = SourceFile(File("nav1.xml"), "nav1")
    val nav1String =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <deepLink />
      |    <deepLink app:uri="https://www.example.com/shared" />
      |</navigation>
      """
        .trimMargin()
    val nav2String =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <include app:graph="@navigation/nav1" />
      |</navigation>
      """
        .trimMargin()
    val inputManifestString =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<manifest
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1">
      |    <application android:name="TheApp">
      |        <activity android:name=".MainActivity">
      |            <nav-graph android:value="@navigation/nav1" />
      |            <nav-graph android:value="@navigation/nav2" />
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    val xmlDocument = TestUtils.xmlDocumentFromString(UNKNOWN, inputManifestString, model)
    val loadedNavigationMap =
      mapOf(
        "nav1" to NavigationXmlLoader.load(nav1SourceFile, nav1String),
        "nav2" to NavigationXmlLoader.load(UNKNOWN, nav2String),
      )

    expandNavGraphs(xmlDocument, loadedNavigationMap, mergingReportBuilder)

    // Even though MainActivity expands two <nav-graph> elements that both reach nav1.xml,
    // the invalid <deepLink /> in nav1.xml is reported only once.
    verify(mergingReportBuilder)
      .addMessage(
        any<SourceFilePosition>(),
        eq(MergingReport.Record.Severity.ERROR),
        eq("Navigation XML document <deepLink> element must contain a app:${SdkConstants.ATTR_URI} attribute."),
      )
    verify(mergingReportBuilder).addMessage(any<SourceFilePosition>(), eq(MergingReport.Record.Severity.ERROR), any())
  }

  @Test
  fun testFindDeepLinksReportsElementErrorsAndReturnsValidDeepLinks() {
    val nav1SourceFile = SourceFile(File("nav1.xml"), "nav1")
    val nav1String =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <deepLink />
      |    <deepLink app:uri="https://www.example.com/partial" />
      |</navigation>
      """
        .trimMargin()
    val loadedNavigationMap = mapOf("nav1" to NavigationXmlLoader.load(nav1SourceFile, nav1String))

    val deepLinks = NavGraphExpander.findDeepLinks("nav1", loadedNavigationMap, mergingReportBuilder, fakeSourceFilePosition)

    assertThat(deepLinks.map { it.path }).containsExactly("/partial")
    verify(mergingReportBuilder)
      .addMessage(
        any<SourceFilePosition>(),
        eq(MergingReport.Record.Severity.ERROR),
        eq("Navigation XML document <deepLink> element must contain a app:${SdkConstants.ATTR_URI} attribute."),
      )
  }

  @Test
  fun testDuplicateIncludeDeduplicatesElementErrorsAndPreservesWarning() {
    val manifestSourceFile = SourceFile(File("AndroidManifest.xml"), "main")
    val nav2SourceFile = SourceFile(File("nav2.xml"), "nav2")
    val nav1String =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <include app:graph="@navigation/nav2" />
      |    <include app:graph="@navigation/nav2" />
      |</navigation>
      """
        .trimMargin()
    val nav2String =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <deepLink />
      |    <deepLink app:uri="https://www.example.com/from_nav2" />
      |</navigation>
      """
        .trimMargin()
    val inputManifestString =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<manifest
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1">
      |    <application android:name="TheApp">
      |        <activity android:name=".MainActivity">
      |            <nav-graph android:value="@navigation/nav1" />
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    val xmlDocument = TestUtils.xmlDocumentFromString(manifestSourceFile, inputManifestString, model)
    val loadedNavigationMap =
      mapOf(
        "nav1" to NavigationXmlLoader.load(UNKNOWN, nav1String),
        "nav2" to NavigationXmlLoader.load(nav2SourceFile, nav2String),
      )

    expandNavGraphs(xmlDocument, loadedNavigationMap, mergingReportBuilder)

    val errorPosCaptor = argumentCaptor<SourceFilePosition>()
    verify(mergingReportBuilder)
      .addMessage(
        errorPosCaptor.capture(),
        eq(MergingReport.Record.Severity.ERROR),
        eq("Navigation XML document <deepLink> element must contain a app:${SdkConstants.ATTR_URI} attribute."),
      )
    assertThat(errorPosCaptor.firstValue.file).isEqualTo(nav2SourceFile)
    assertThat(errorPosCaptor.firstValue.position.startLine).isEqualTo(4)
    verify(mergingReportBuilder).addMessage(any<SourceFilePosition>(), eq(MergingReport.Record.Severity.ERROR), any())

    val warningPosCaptor = argumentCaptor<SourceFilePosition>()
    verify(mergingReportBuilder)
      .addMessage(
        warningPosCaptor.capture(),
        eq(MergingReport.Record.Severity.WARNING),
        eq(
          "The navigation file with ID \"nav2\" is included multiple times in " +
            "the navigation graph, but only deep links on the first instance will be " +
            "triggered at runtime. Consider consolidating these instances into a " +
            "single <include> at a higher level of your navigation graph hierarchy."
        ),
      )
    assertThat(warningPosCaptor.firstValue.file).isEqualTo(manifestSourceFile)
  }

  private fun validateDeepLinksGoToOneIntentFilter(expectedOutputManifestString: String, vararg navigationStrings: String) {
    val inputManifestString =
      """
      |"|<?xml version="1.0" encoding="UTF-8"?>
      |<manifest
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    package="com.example.app1">
      |    <application android:name="TheApp">
      |        <activity android:name=".MainActivity">
      |            <nav-graph android:value="@navigation/nav1" />
      |        </activity>
      |    </application>
      |</manifest>
      """
        .trimMargin()

    val xmlDocument = TestUtils.xmlDocumentFromString(UNKNOWN, inputManifestString, model)

    val loadedNavigationMap: MutableMap<String, NavigationXmlDocument> = mutableMapOf()

    navigationStrings.forEachIndexed { index, navigationString ->
      loadedNavigationMap["nav${index + 1}"] = NavigationXmlLoader.load(UNKNOWN, navigationString)
    }

    val expandedXmlDocument = expandNavGraphs(xmlDocument, loadedNavigationMap, mergingReportBuilder)

    val expectedXmlDocument = TestUtils.xmlDocumentFromString(UNKNOWN, expectedOutputManifestString, model)

    assertThat(expandedXmlDocument.prettyPrint()).isEqualTo(expectedXmlDocument.prettyPrint())

    // verify no error was recorded.
    verify(mergingReportBuilder, never()).addMessage(any<SourceFilePosition>(), eq(MergingReport.Record.Severity.ERROR), any())
  }
}
