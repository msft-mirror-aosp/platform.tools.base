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
import com.android.manifmerger.DeepLink.DeepLinkException
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.net.URISyntaxException
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

/** Tests for [NavigationXmlDocument] */
class NavigationXmlDocumentTest {

  @Test
  fun testGetNavigationXmlIds() {

    val input =
      """
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <include app:graph="@navigation/foo" />
      |    <include app:graph="@navigation/bar" />
      |    <deepLink app:uri="www.example1.com"
      |            android:autoVerify="true" />
      |    <navigation>
      |        <include app:graph="@navigation/bar" />
      |        <deepLink app:uri="www.example2.com" />
      |    </navigation>
      |</navigation>
      """
        .trimMargin()

    val navigationXmlDocument = NavigationXmlLoader.load(UNKNOWN, input)
    assertThat(navigationXmlDocument.navigationXmlIds).containsExactly("foo", "bar", "bar")
  }

  @Test
  fun testGetDeepLinks() {

    val input =
      """
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <include app:graph="@navigation/foo" />
      |    <include app:graph="@navigation/bar" />
      |    <deepLink app:uri="www.example1.com"
      |            android:autoVerify="true" />
      |    <navigation>
      |        <include app:graph="@navigation/bar" />
      |        <deepLink app:uri="www.example2.com" />
      |    </navigation>
      |</navigation>
      """
        .trimMargin()

    val navigationXmlDocument = NavigationXmlLoader.load(UNKNOWN, input)
    val deepLinks = navigationXmlDocument.deepLinks
    assertThat(deepLinks.size).isEqualTo(2)
    var foundExample1 = false
    var foundExample2 = false
    for (deepLink in deepLinks) {
      if (deepLink.host == "www.example1.com") {
        foundExample1 = true
        assertThat(deepLink.isAutoVerify).isTrue()
        assertThat(deepLink.sourceFilePosition.file).isEqualTo(UNKNOWN)
        assertThat(deepLink.sourceFilePosition.position.toString()).isEqualTo("6:5-7:41")
      }
      if (deepLink.host == "www.example2.com") {
        foundExample2 = true
        assertThat(deepLink.isAutoVerify).isFalse()
        assertThat(deepLink.sourceFilePosition.file).isEqualTo(UNKNOWN)
        assertThat(deepLink.sourceFilePosition.position.toString()).isEqualTo("10:9-48")
      }
    }
    assertThat(foundExample1).isTrue()
    assertThat(foundExample2).isTrue()
  }

  @Test
  fun testCustomNamespacePrefix() {

    val input =
      """
      |<navigation
      |    xmlns:custom1="http://schemas.android.com/apk/res/android"
      |    xmlns:custom2="http://schemas.android.com/apk/res-auto">
      |    <include custom2:graph="@navigation/foo" />
      |    <deepLink custom2:uri="www.example1.com"
      |            custom1:autoVerify="true" />
      |</navigation>
      """
        .trimMargin()

    val navigationXmlDocument = NavigationXmlLoader.load(UNKNOWN, input)
    assertThat(navigationXmlDocument.navigationXmlIds).containsExactly("foo")
    val deepLinks = navigationXmlDocument.deepLinks
    assertThat(deepLinks.size).isEqualTo(1)
    val deepLink = deepLinks[0]
    assertThat(deepLink.host).isEqualTo("www.example1.com")
    assertThat(deepLink.isAutoVerify).isTrue()
    assertThat(deepLink.sourceFilePosition.file).isEqualTo(UNKNOWN)
    assertThat(deepLink.sourceFilePosition.position.toString()).isEqualTo("5:5-6:41")
  }

  @Test
  fun testEmptyElementExceptions() {

    val input =
      """
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <include />
      |    <deepLink />
      |</navigation>
      """
        .trimMargin()

    val navigationXmlDocument = NavigationXmlLoader.load(UNKNOWN, input)

    try {
      navigationXmlDocument.navigationXmlIds
      fail("Expecting NavigationXmlDocumentException")
    } catch (e: NavigationXmlDocument.NavigationXmlDocumentException) {
      // should throw NavigationXmlDocumentException because of empty <include> element
    }

    try {
      navigationXmlDocument.deepLinks
      fail("Expecting NavigationXmlDocumentException")
    } catch (e: NavigationXmlDocument.NavigationXmlDocumentException) {
      // should throw NavigationXmlDocumentException because of empty <deepLink> element
    }
  }

  @Test
  fun testEmptyMimeType() {

    val input =
      """
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <include app:graph="@navigation/foo" />
      |    <include app:graph="@navigation/bar" />
      |    <deepLink app:uri="www.example1.com"
      |            app:mimeType="" />
      |    <navigation>
      |        <include app:graph="@navigation/bar" />
      |        <deepLink app:uri="www.example2.com" />
      |    </navigation>
      |</navigation>
      """
        .trimMargin()

    val navigationXmlDocument = NavigationXmlLoader.load(UNKNOWN, input)

    try {
      navigationXmlDocument.deepLinks
      fail("Expecting NavigationXmlDocumentException")
    } catch (e: NavigationXmlDocument.NavigationXmlDocumentException) {
      // should throw NavigationXmlDocumentException because of empty mimeType
    }
  }

  @Test
  fun testValidNavigationXmlIdsAndReportElementErrors() {
    val navSourceFile = SourceFile(File("nav_main.xml"), "nav_main")
    val input =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <include app:graph="@navigation/foo" />
      |    <include />
      |    <include app:graph="@navigation/bar" />
      |    <navigation>
      |        <include app:graph="invalid_prefix" />
      |        <include app:graph="@navigation/baz" />
      |    </navigation>
      |</navigation>
      """
        .trimMargin()

    val navigationXmlDocument = NavigationXmlLoader.load(navSourceFile, input)
    val mergingReportBuilder: MergingReport.Builder = mock()

    assertThat(navigationXmlDocument.validNavigationXmlIds).containsExactly("foo", "bar", "baz").inOrder()
    navigationXmlDocument.appendElementErrorsTo(mergingReportBuilder)

    val expectedErrorMessage =
      "Navigation XML document <include> element must contain a app:${SdkConstants.ATTR_GRAPH} " +
        "attribute, whose value begins with ${SdkConstants.NAVIGATION_PREFIX}."
    val posCaptor = argumentCaptor<SourceFilePosition>()
    verify(mergingReportBuilder, times(2))
      .addMessage(
        posCaptor.capture(),
        eq(MergingReport.Record.Severity.ERROR),
        eq(expectedErrorMessage),
      )
    assertThat(posCaptor.allValues.map { it.file }).containsExactly(navSourceFile, navSourceFile).inOrder()
    assertThat(posCaptor.allValues.map { it.position.startLine }).containsExactly(5, 8).inOrder()

    // Property accessor and convertToData used by AGP tasks throw a fresh exception on each access.
    val firstException =
      assertThrows(NavigationXmlDocument.NavigationXmlDocumentException::class.java) {
        navigationXmlDocument.navigationXmlIds
      }
    val secondException =
      assertThrows(NavigationXmlDocument.NavigationXmlDocumentException::class.java) {
        navigationXmlDocument.navigationXmlIds
      }
    assertThat(firstException.message).isEqualTo(expectedErrorMessage)
    assertNotSame(firstException, secondException)
    assertThrows(NavigationXmlDocument.NavigationXmlDocumentException::class.java) {
      navigationXmlDocument.convertToData(emptyMap(), false)
    }
  }

  @Test
  fun testValidDeepLinksAndReportElementErrors() {
    val navSourceFile = SourceFile(File("nav_main.xml"), "nav_main")
    val input =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <deepLink app:uri="https://www.example.com/first" />
      |    <deepLink />
      |    <deepLink app:uri="https://www.example.com/blank-mime" app:mimeType=" " />
      |    <deepLink app:uri="http://www.*.com" />
      |    <deepLink app:uri="" />
      |    <navigation>
      |        <deepLink app:uri="%http://www.example.com" />
      |        <deepLink app:uri="https://www.example.com/second" />
      |    </navigation>
      |</navigation>
      """
        .trimMargin()

    val navigationXmlDocument = NavigationXmlLoader.load(navSourceFile, input)
    val mergingReportBuilder: MergingReport.Builder = mock()

    assertThat(navigationXmlDocument.validDeepLinks.map { it.path }).containsExactly("/first", "/second").inOrder()
    navigationXmlDocument.appendElementErrorsTo(mergingReportBuilder)

    val posCaptor = argumentCaptor<SourceFilePosition>()
    val msgCaptor = argumentCaptor<String>()
    verify(mergingReportBuilder, times(5))
      .addMessage(
        posCaptor.capture(),
        eq(MergingReport.Record.Severity.ERROR),
        msgCaptor.capture(),
      )
    assertThat(posCaptor.allValues.map { it.file })
      .containsExactly(navSourceFile, navSourceFile, navSourceFile, navSourceFile, navSourceFile)
      .inOrder()
    assertThat(posCaptor.allValues.map { it.position.startLine }).containsExactly(5, 6, 7, 8, 10).inOrder()
    assertThat(msgCaptor.allValues[0])
      .isEqualTo("Navigation XML document <deepLink> element must contain a app:${SdkConstants.ATTR_URI} attribute.")
    assertThat(msgCaptor.allValues[1])
      .isEqualTo("Navigation XML document <deepLink> element ${SdkConstants.ATTR_DEEPLINK_MIMETYPE} cannot be blank.")
    assertThat(msgCaptor.allValues[2]).isEqualTo("Improper use of wildcards and/or placeholders in deeplink URI host")
    assertThat(msgCaptor.allValues[3]).isEqualTo("DeepLink URI cannot be blank")
    // URISyntaxException failures are prefixed with <deepLink> context in MergingReport without the Java class-name prefix.
    assertThat(msgCaptor.allValues[4]).startsWith("Navigation XML document <deepLink> element app:${SdkConstants.ATTR_URI} is invalid: ")
    assertThat(msgCaptor.allValues[4]).doesNotContain("java.net.URISyntaxException")
    assertThat(msgCaptor.allValues[4]).contains("%http://www.example.com")

    // Property accessor and convertToData used by AGP tasks throw a fresh exception on each access.
    val firstException =
      assertThrows(NavigationXmlDocument.NavigationXmlDocumentException::class.java) {
        navigationXmlDocument.deepLinks
      }
    val secondException =
      assertThrows(NavigationXmlDocument.NavigationXmlDocumentException::class.java) {
        navigationXmlDocument.deepLinks
      }
    assertThat(firstException.message)
      .isEqualTo("Navigation XML document <deepLink> element must contain a app:${SdkConstants.ATTR_URI} attribute.")
    assertNotSame(firstException, secondException)
    assertThrows(NavigationXmlDocument.NavigationXmlDocumentException::class.java) {
      navigationXmlDocument.convertToData(emptyMap(), false)
    }
  }

  @Test
  fun testAppendElementErrorsToPreservesDocumentOrderAndDeepLinkCause() {
    val navSourceFile = SourceFile(File("nav_main.xml"), "nav_main")
    val input =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <include />
      |    <deepLink app:uri="%http://www.example.com" />
      |    <include app:graph="@navigation/valid_after" />
      |</navigation>
      """
        .trimMargin()

    val navigationXmlDocument = NavigationXmlLoader.load(navSourceFile, input)
    val mergingReportBuilder: MergingReport.Builder = mock()

    assertThat(navigationXmlDocument.validNavigationXmlIds).containsExactly("valid_after")
    assertThat(navigationXmlDocument.validDeepLinks).isEmpty()

    navigationXmlDocument.appendElementErrorsTo(mergingReportBuilder)

    val posCaptor = argumentCaptor<SourceFilePosition>()
    val msgCaptor = argumentCaptor<String>()
    verify(mergingReportBuilder, times(2))
      .addMessage(
        posCaptor.capture(),
        eq(MergingReport.Record.Severity.ERROR),
        msgCaptor.capture(),
      )
    assertThat(posCaptor.allValues.map { it.position.startLine }).containsExactly(4, 5).inOrder()
    assertThat(msgCaptor.allValues[0])
      .isEqualTo(
        "Navigation XML document <include> element must contain a app:${SdkConstants.ATTR_GRAPH} " +
          "attribute, whose value begins with ${SdkConstants.NAVIGATION_PREFIX}."
      )
    assertThat(msgCaptor.allValues[1]).startsWith("Navigation XML document <deepLink> element app:${SdkConstants.ATTR_URI} is invalid: ")
    assertThat(msgCaptor.allValues[1]).doesNotContain("java.net.URISyntaxException")

    // Each `deepLinks` access constructs a fresh DeepLinkException(exceptionMessage, cause), preserving the
    // clean underlying URISyntaxException message and single-level cause without double-wrapping.
    val thrownDeepLinkException = assertThrows(DeepLinkException::class.java) { navigationXmlDocument.deepLinks }
    assertThat(thrownDeepLinkException.cause).isInstanceOf(URISyntaxException::class.java)
    assertThat(thrownDeepLinkException.message).isEqualTo(thrownDeepLinkException.cause!!.message)

    // A string-constructed DeepLinkException (such as an invalid host wildcard) preserves a null cause.
    val wildcardNavDoc =
      NavigationXmlLoader.load(
        navSourceFile,
        """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<navigation xmlns:app="http://schemas.android.com/apk/res-auto">
        |    <deepLink app:uri="http://www.*.com" />
        |</navigation>
        """
          .trimMargin(),
      )
    val thrownWildcardException = assertThrows(DeepLinkException::class.java) { wildcardNavDoc.deepLinks }
    assertThat(thrownWildcardException.message).isEqualTo("Improper use of wildcards and/or placeholders in deeplink URI host")
    assertThat(thrownWildcardException.cause).isNull()
  }

  @Test
  fun testThrowingAccessorsAreIsolatedBetweenIncludesAndDeepLinks() {
    val navSourceFile = SourceFile(File("nav_main.xml"), "nav_main")
    val invalidDeepLinkOnly =
      NavigationXmlLoader.load(
        navSourceFile,
        """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<navigation xmlns:app="http://schemas.android.com/apk/res-auto">
        |    <include app:graph="@navigation/valid_graph" />
        |    <deepLink />
        |</navigation>
        """
          .trimMargin(),
      )
    assertThat(invalidDeepLinkOnly.navigationXmlIds).containsExactly("valid_graph")
    assertThrows(NavigationXmlDocument.NavigationXmlDocumentException::class.java) { invalidDeepLinkOnly.deepLinks }

    val invalidIncludeOnly =
      NavigationXmlLoader.load(
        navSourceFile,
        """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<navigation xmlns:app="http://schemas.android.com/apk/res-auto">
        |    <include />
        |    <deepLink app:uri="https://www.example.com/valid" />
        |</navigation>
        """
          .trimMargin(),
      )
    assertThat(invalidIncludeOnly.deepLinks.map { it.path }).containsExactly("/valid")
    assertThrows(NavigationXmlDocument.NavigationXmlDocumentException::class.java) { invalidIncludeOnly.navigationXmlIds }
  }

  @Test
  fun testAppendElementErrorsToRecordsMultipleErrorsOnSingleDeepLinkElement() {
    val navSourceFile = SourceFile(File("nav_main.xml"), "nav_main")
    val input =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <deepLink app:uri="http://www.*.com" app:mimeType="" />
      |    <deepLink app:mimeType="" />
      |</navigation>
      """
        .trimMargin()

    val navigationXmlDocument = NavigationXmlLoader.load(navSourceFile, input)
    val mergingReportBuilder: MergingReport.Builder = mock()

    assertThat(navigationXmlDocument.validDeepLinks).isEmpty()
    navigationXmlDocument.appendElementErrorsTo(mergingReportBuilder)

    val posCaptor = argumentCaptor<SourceFilePosition>()
    val msgCaptor = argumentCaptor<String>()
    verify(mergingReportBuilder, times(4))
      .addMessage(
        posCaptor.capture(),
        eq(MergingReport.Record.Severity.ERROR),
        msgCaptor.capture(),
      )
    assertThat(posCaptor.allValues.map { it.position.startLine }).containsExactly(4, 4, 5, 5).inOrder()
    assertThat(msgCaptor.allValues)
      .containsExactly(
        "Navigation XML document <deepLink> element ${SdkConstants.ATTR_DEEPLINK_MIMETYPE} cannot be blank.",
        "Improper use of wildcards and/or placeholders in deeplink URI host",
        "Navigation XML document <deepLink> element ${SdkConstants.ATTR_DEEPLINK_MIMETYPE} cannot be blank.",
        "Navigation XML document <deepLink> element must contain a app:${SdkConstants.ATTR_URI} attribute.",
      )
      .inOrder()

    val thrown =
      assertThrows(NavigationXmlDocument.NavigationXmlDocumentException::class.java) {
        navigationXmlDocument.deepLinks
      }
    assertThat(thrown.message)
      .isEqualTo("Navigation XML document <deepLink> element ${SdkConstants.ATTR_DEEPLINK_MIMETYPE} cannot be blank.")
  }

  @Test
  fun testPrecomputedDataReturnsItemsAndReportsNoErrors() {
    val precomputedDeepLink = DeepLink.fromUri("https://www.example.com/precomputed", SourceFilePosition.UNKNOWN, false)
    val data = NavigationXmlDocumentData("nav_precomputed", listOf("child_nav"), listOf(precomputedDeepLink))
    val navigationXmlDocument = NavigationXmlDocument(data)
    val mergingReportBuilder: MergingReport.Builder = mock()

    assertThat(navigationXmlDocument.name).isEqualTo("nav_precomputed")
    assertThat(navigationXmlDocument.navigationXmlIds).containsExactly("child_nav")
    assertThat(navigationXmlDocument.validNavigationXmlIds).containsExactly("child_nav")
    assertThat(navigationXmlDocument.deepLinks).containsExactly(precomputedDeepLink)
    assertThat(navigationXmlDocument.validDeepLinks).containsExactly(precomputedDeepLink)

    navigationXmlDocument.appendElementErrorsTo(mergingReportBuilder)
    verify(mergingReportBuilder, never()).addMessage(any<SourceFilePosition>(), any(), any())
  }

  @Test
  fun testConvertToDataValidDocument() {
    val navSourceFile = SourceFile(File("dir/nav_main.xml"), "nav_main")
    val input =
      """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<navigation
      |    xmlns:android="http://schemas.android.com/apk/res/android"
      |    xmlns:app="http://schemas.android.com/apk/res-auto">
      |    <include app:graph="@navigation/sub_nav" />
      |    <deepLink app:uri="${'$'}{scheme}://${'$'}{host}/items/{id}" android:autoVerify="true" />
      |</navigation>
      """
        .trimMargin()

    val navigationXmlDocument = NavigationXmlLoader.load(navSourceFile, input)
    val placeholders = mapOf("scheme" to "https", "host" to "www.example.com")

    val dataWithPosition = navigationXmlDocument.convertToData(placeholders, useUnknownSourceFilePosition = false)
    assertThat(dataWithPosition.name).isEqualTo("nav_main")
    assertThat(dataWithPosition.navigationXmlIds).containsExactly("sub_nav")
    val deepLink = dataWithPosition.deepLinks.single()
    assertThat(deepLink.schemes).containsExactly("https")
    assertThat(deepLink.host).isEqualTo("www.example.com")
    assertThat(deepLink.path).isEqualTo("/items/.*")
    assertThat(deepLink.isAutoVerify).isTrue()
    assertThat(deepLink.sourceFilePosition.file.description).isEqualTo("nav_main.xml")
    assertThat(deepLink.sourceFilePosition.file.sourceFile).isNull()

    val dataUnknownPosition = navigationXmlDocument.convertToData(placeholders, useUnknownSourceFilePosition = true)
    assertThat(dataUnknownPosition.deepLinks.single().sourceFilePosition).isEqualTo(SourceFilePosition.UNKNOWN)
  }
}
