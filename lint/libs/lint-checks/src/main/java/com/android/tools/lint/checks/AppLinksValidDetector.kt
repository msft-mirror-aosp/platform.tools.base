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
package com.android.tools.lint.checks

import com.android.SdkConstants.ANDROID_NS_NAME
import com.android.SdkConstants.ANDROID_URI
import com.android.SdkConstants.ATTR_AUTO_VERIFY
import com.android.SdkConstants.ATTR_EXPORTED
import com.android.SdkConstants.ATTR_HOST
import com.android.SdkConstants.ATTR_MIME_TYPE
import com.android.SdkConstants.ATTR_ORDER
import com.android.SdkConstants.ATTR_PATH
import com.android.SdkConstants.ATTR_PATH_ADVANCED_PATTERN
import com.android.SdkConstants.ATTR_PATH_PATTERN
import com.android.SdkConstants.ATTR_PATH_PREFIX
import com.android.SdkConstants.ATTR_PATH_SUFFIX
import com.android.SdkConstants.ATTR_PORT
import com.android.SdkConstants.ATTR_PRIORITY
import com.android.SdkConstants.ATTR_SCHEME
import com.android.SdkConstants.PREFIX_RESOURCE_REF
import com.android.SdkConstants.PREFIX_THEME_REF
import com.android.SdkConstants.TAG_ACTION
import com.android.SdkConstants.TAG_ACTIVITY
import com.android.SdkConstants.TAG_ACTIVITY_ALIAS
import com.android.SdkConstants.TAG_CATEGORY
import com.android.SdkConstants.TAG_DATA
import com.android.SdkConstants.TAG_INTENT_FILTER
import com.android.SdkConstants.TAG_URI_RELATIVE_FILTER_GROUP
import com.android.SdkConstants.TOOLS_URI
import com.android.SdkConstants.VALUE_0
import com.android.SdkConstants.VALUE_FALSE
import com.android.SdkConstants.VALUE_TRUE
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.resources.ResourceType
import com.android.resources.ResourceUrl
import com.android.sdklib.AndroidVersion.VersionCodes
import com.android.tools.lint.checks.AndroidPatternMatcher.PATTERN_ADVANCED_GLOB
import com.android.tools.lint.checks.AndroidPatternMatcher.PATTERN_LITERAL
import com.android.tools.lint.checks.AndroidPatternMatcher.PATTERN_PREFIX
import com.android.tools.lint.checks.AndroidPatternMatcher.PATTERN_SIMPLE_GLOB
import com.android.tools.lint.checks.AndroidPatternMatcher.PATTERN_SUFFIX
import com.android.tools.lint.client.api.LintClient
import com.android.tools.lint.client.api.ResourceRepositoryScope
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.LintFix
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.XmlContext
import com.android.tools.lint.detector.api.XmlScanner
import com.android.tools.lint.detector.api.isDataBindingExpression
import com.android.tools.lint.detector.api.isManifestPlaceHolderExpression
import com.android.tools.lint.detector.api.resolvePlaceHolders
import com.android.utils.XmlUtils
import com.android.utils.iterator
import com.android.utils.subtagCount
import com.android.xml.AndroidManifest.ATTRIBUTE_NAME
import com.google.common.base.Joiner
import java.lang.Character.isUpperCase
import java.net.MalformedURLException
import java.net.URL
import java.util.regex.Pattern
import org.jetbrains.annotations.VisibleForTesting
import org.w3c.dom.Attr
import org.w3c.dom.Element
import org.w3c.dom.Node

/** Checks for invalid app links URLs */
class AppLinksValidDetector : Detector(), XmlScanner {
  override fun getApplicableElements(): Collection<String> {
    return listOf(TAG_ACTIVITY, TAG_ACTIVITY_ALIAS, TAG_INTENT_FILTER)
  }

  override fun visitElement(context: XmlContext, element: Element) {
    when (val tag = element.tagName) {
      TAG_INTENT_FILTER -> checkIntentFilter(context, element)
      TAG_ACTIVITY,
      TAG_ACTIVITY_ALIAS -> checkActivity(context, element)
      else -> error("Unhandled tag $tag")
    }
  }

  /**
   * Reports incidents for intent filter [intentFilter]. Note that intent filters inside an
   * <activity> (as opposed to those inside a <service>, <receiver>, etc.) have additional
   * restrictions/requirements, which are not checked here.
   */
  private fun checkIntentFilter(context: XmlContext, intentFilter: Element) {
    val intentFilterData = getIntentFilterData(ElementWrapper(intentFilter, context))
    val namespace = intentFilter.lookupPrefix(ANDROID_URI) ?: ANDROID_NS_NAME

    // --- Check "splitting" (Recommend splitting a data tag into multiple tags with individual
    // attributes ---
    if (intentFilterData.dataTags.dataTagElements.size <= 1) return
    for (dataTag in intentFilterData.dataTags) {
      val node = (dataTag as? ElementWrapper)?.element ?: continue
      val location = context.getLocation(node)
      val indentAmount = location.start?.column ?: 4
      val subTags = mutableListOf<String>()
      dataTag.getAttributeWrapper(ATTR_SCHEME)?.rawValue?.let {
        subTags.add("""<data $namespace:scheme="$it" />""")
      }
      dataTag.getAttributeWrapper(ATTR_HOST)?.rawValue?.let { host ->
        val port = dataTag.getAttributeWrapper(ATTR_PORT)?.rawValue
        val portIfNeeded = if (port == null) "" else """ $namespace:port="$port""""
        subTags.add("""<data $namespace:host="$host"$portIfNeeded />""")
      }
      for (pathAttribute in PATH_ATTRIBUTES) {
        dataTag.getAttributeWrapper(pathAttribute)?.rawValue?.let {
          subTags.add("""<data $namespace:$pathAttribute="$it" />""")
        }
      }
      if (subTags.size > 1) {
        context.report(
          INTENT_FILTER_UNIQUE_DATA_ATTRIBUTES,
          node,
          context.getLocation(node),
          "Consider splitting $TAG_DATA tag into multiple tags with individual" +
            " attributes to avoid confusion",
          fix()
            .replace()
            .with(subTags.joinToString("\n" + indentation(indentAmount)))
            .autoFix()
            .build(),
        )
      }
    }
  }

  private fun checkActivity(context: XmlContext, element: Element) {
    val infos =
      XmlUtils.getSubTagsByName(element, TAG_INTENT_FILTER).map {
        handleIntentFilterInActivity(context, it)
      }
    // Check that if any intent filter uses ACTION_VIEW, the activity is exported
    val isExported =
      element.getAttributeNodeNS(ANDROID_URI, ATTR_EXPORTED)?.value.let {
        it.isNullOrBlank() || it == VALUE_TRUE
      }
    if (!isExported && infos.any { it.actionSet.contains(ACTION_VIEW) }) {
      // Report error if the activity supporting action view is not exported.
      reportUrlError(
        context,
        element,
        context.getLocation(element),
        "Activity supporting ACTION_VIEW is not exported",
      )
    }
    // Check that any tools:testUrl tags match at least one intent filter
    var current = XmlUtils.getFirstSubTagByName(element, TAG_VALIDATION)
    while (current != null) {
      if (TOOLS_URI == current.namespaceURI) {
        val testUrlAttr = current.getAttributeNode("testUrl")
        if (testUrlAttr == null) {
          val message = "Expected `testUrl` attribute"
          reportUrlError(context, current, context.getLocation(current), message)
        } else {
          val testUrlString = testUrlAttr.value
          try {
            val testUrl = URL(testUrlString)
            val reason = checkTestUrlMatchesAtLeastOneInfo(testUrl, infos)
            if (reason != null) {
              reportTestUrlFailure(
                context,
                testUrlAttr,
                context.getValueLocation(testUrlAttr),
                reason,
              )
            }
          } catch (e: MalformedURLException) {
            val message = "Invalid test URL: " + e.localizedMessage
            reportTestUrlFailure(
              context,
              testUrlAttr,
              context.getValueLocation(testUrlAttr),
              message,
            )
          }
        }
      } else {
        reportTestUrlFailure(
          context,
          current,
          context.getNameLocation(current),
          "Validation nodes should be in the `tools:` namespace to " +
            "ensure they are removed from the manifest at build time",
        )
      }
      current = XmlUtils.getNextTagByName(current, TAG_VALIDATION)
    }
  }

  private fun reportUrlError(
    context: XmlContext,
    node: Node,
    location: Location,
    message: String,
    quickfixData: LintFix? = null,
  ) {
    // Validation errors were reported here before
    if (context.driver.isSuppressed(context, _OLD_ISSUE_URL, node)) {
      return
    }
    context.report(VALIDATION, node, location, message, quickfixData)
  }

  private fun reportUrlWarning(
    context: XmlContext,
    node: Node,
    location: Location,
    message: String,
    quickfixData: LintFix? = null,
  ) {
    context.report(APP_LINK_WARNING, node, location, message, quickfixData)
  }

  private fun reportTestUrlFailure(
    context: XmlContext,
    node: Node,
    location: Location,
    message: String,
  ) {
    context.report(TEST_URL, node, location, message)
  }

  /**
   * Given a test URL and a list of [IntentFilterData], this method checks whether the URL matches,
   * and if so returns null, otherwise returning the reason for the mismatch.
   *
   * @param testUrl the URL to test
   * @param infos the URL information
   * @return null for a match, otherwise the failure reason
   */
  @VisibleForTesting
  fun checkTestUrlMatchesAtLeastOneInfo(testUrl: URL, infos: List<IntentFilterData>): String? {
    val reasons = mutableListOf<String>()
    for (info in infos) {
      val reason = info.match(testUrl) ?: return null // found a match
      if (!reasons.contains(reason)) {
        reasons.add(reason)
      }
    }
    return if (reasons.isNotEmpty()) {
      "Test URL " + Joiner.on(" or ").join(reasons)
    } else {
      null
    }
  }

  /**
   * Processes an intent filter element ([intentFilter]), inside an activity element, in the
   * [XmlContext] [context].
   *
   * Note that intent filters inside an <activity> (as opposed to those inside a <service>,
   * <receiver>, etc.) have additional restrictions/requirements. As a result, this function is only
   * called for intent filters inside an activity, whereas [checkIntentFilter] is called for all
   * intent filters (including those inside a <service>, <receiver>, etc.).
   *
   * @return the [IntentFilterData] for this intent filter
   */
  private fun handleIntentFilterInActivity(
    context: XmlContext,
    intentFilter: Element,
  ): IntentFilterData {
    val intentFilterData = getIntentFilterData(ElementWrapper(intentFilter, context))

    // --- Check "missing data node" (Intent filter has ACTION_VIEW & CATEGORY_BROWSABLE, but no
    // data node) ---
    val hasActionView = intentFilterData.actionSet.contains(ACTION_VIEW)
    val hasCategoryBrowsable = intentFilterData.categorySet.contains(CATEGORY_BROWSABLE)
    if (intentFilterData.dataTags.isEmpty) {
      if (hasCategoryBrowsable) {
        // If this intent has ACTION_VIEW and CATEGORY_BROWSABLE, but doesn't have a data node, it's
        // a likely mistake
        reportUrlError(
          context,
          intentFilter,
          context.getLocation(intentFilter),
          "Missing data element",
        )
      }
      return intentFilterData
    }

    // --- Check mimeType ---
    for (mimeTypeElement in intentFilterData.dataTags.mimeTypeElements) {
      val node = (mimeTypeElement as? AttrWrapper)?.attr ?: continue
      val value = mimeTypeElement.substitutedValue ?: continue
      // Ignore cases where resolving string resource or manifest placeholder failed,
      // so we're still using the raw string.
      if (isSubstituted(value)) continue
      if (value.any(Char::isUpperCase)) {
        val resolvedValueMessage =
          if (mimeTypeElement.rawValue != value) {
            " (without placeholders, value is `$value`)"
          } else {
            ""
          }
        reportUrlError(
          context,
          node,
          context.getValueLocation(node),
          "Mime-type matching is case sensitive and should only use lower-case characters$resolvedValueMessage",
        )
      }
    }

    // --- Check scheme ---
    for (schemeElement in intentFilterData.dataTags.schemeElements) {
      val node = (schemeElement as? AttrWrapper)?.attr ?: continue
      val value = schemeElement.substitutedValue ?: continue
      // Ignore cases where resolving string resource or manifest placeholder failed,
      // so we're still using the raw string.
      if (isSubstituted(value)) continue
      if (value.isBlank()) {
        reportUrlError(context, node, context.getLocation(node), "`${node.name}` cannot be empty")
        continue
      }
      if (value.endsWith(":")) {
        reportUrlError(
          context,
          node,
          context.getValueLocation(node),
          "Don't include trailing colon in the `scheme` declaration",
        )
      } else if (value.any(Char::isUpperCase)) {
        reportUrlError(
          context,
          node,
          context.getValueLocation(node),
          "Scheme matching is case sensitive and should only " + "use lower-case characters",
        )
      }
      // Scheme matching also does URI decoding. However, any <a> tag where the scheme has a % or
      // a whitespace is treated as a relative path, so unexpected behavior may occur if the
      // developer attempts to create a custom scheme with non-URI characters in it.
      // However, it's difficult for us to be sure that an intent filter is intended to be used as
      // a deeplink unless the intent filter is also an applink (i.e. has autoVerify="true"), so
      // we don't want to unnecessarily enforce that scheme attributes can't have non-URI
      // characters.
      // (If autoVerify="true", then scheme must be http(s), so URI-encoded characters are not
      // allowed anyway.)
    }

    // --- Check host ---

    // Note that while whitespace is not allowed in URIs, Android performs URI decoding.
    // Thus, `scheme://hello%20world` matches an intent filter with android:host="hello world".
    // So it is OK for hosts to have whitespace characters.
    for (hostElement in intentFilterData.dataTags.hostElements) {
      val node = (hostElement as? AttrWrapper)?.attr ?: continue
      val value = hostElement.substitutedValue ?: continue
      // Ignore cases where resolving string resource or manifest placeholder failed,
      // so we're still using the raw string.
      if (isSubstituted(value)) continue

      if (value.isBlank()) {
        reportUrlError(context, node, context.getLocation(node), "`${node.name}` cannot be empty")
        continue
      }
      if (value.lastIndexOf('*') > 0) {
        reportUrlError(
          context,
          node,
          context.getValueLocation(node),
          "The host wildcard (`*`) can only be the first character",
        )
      } else if (value.any(Char::isUpperCase)) {
        reportUrlError(
          context,
          node,
          context.getValueLocation(node),
          "Host matching is case sensitive and should only " + "use lower-case characters",
        )
      }
    }

    // --- Check port ---
    for (portElement in intentFilterData.dataTags.portElements) {
      val node = (portElement as? AttrWrapper)?.attr ?: continue
      val value = portElement.substitutedValue ?: continue
      // Ignore cases where resolving string resource or manifest placeholder failed,
      // so we're still using the raw string.
      if (isSubstituted(value)) continue
      if (value.isBlank()) {
        reportUrlError(context, node, context.getLocation(node), "`${node.name}` cannot be empty")
        continue
      }
      try {
        val port = value.toInt() // might also throw number exc
        if (port < 1 || port > 65535) {
          throw NumberFormatException()
        }
      } catch (e: NumberFormatException) {
        reportUrlError(context, node, context.getValueLocation(node), "not a valid port number")
      }
    }

    // --- Check that port is not by itself ---
    for (dataTag in intentFilterData.dataTags) {
      val port = dataTag.getAttributeWrapper(ATTR_PORT)
      if (port?.substitutedValue != null && dataTag.getAttributeWrapper(ATTR_HOST) == null) {
        val node = (port as? AttrWrapper)?.attr ?: continue
        // The port *only* takes effect if it's specified on the *same* XML
        // element as the host (this isn't true for the other attributes,
        // which can be spread out across separate <data> elements)
        reportUrlError(
          context,
          node,
          context.getValueLocation(node),
          "The port must be specified in the same `<data>` " + "element as the `host`",
        )
      }
    }

    // --- Check path attributes ---
    for (dataTag in intentFilterData.dataTags) {
      for (path in PATH_ATTRIBUTES.mapNotNull { dataTag.getAttributeWrapper(it) }) {
        // Note that while whitespace is not allowed in URIs, Android performs URI decoding.
        // Thus, `scheme://host/hello%20world` matches an intent filter with android:path="hello
        // world".
        // So it is OK for paths to have whitespace characters.
        val attribute = (path as? AttrWrapper)?.attr ?: continue
        val name = path.name
        val rawValue = path.rawValue
        val substitutedValue = path.substitutedValue
        if (rawValue.isNullOrBlank() || substitutedValue.isNullOrBlank()) {
          reportUrlError(
            context,
            attribute,
            context.getLocation(attribute),
            "`${attribute.name}` cannot be empty",
          )
          continue
        }
        // Ignore cases where resolving string resource or manifest placeholder failed,
        // so we're still using the raw string.
        if (isSubstituted(substitutedValue)) continue
        if (!substitutedValue.startsWith("/") && name in setOf(ATTR_PATH, ATTR_PATH_PREFIX)) {
          val fix = LintFix.create().replace().text(rawValue).with("/$rawValue").build()
          reportUrlError(
            context,
            attribute,
            context.getValueLocation(attribute),
            "`${attribute.name}` attribute should start with `/`, but it is `$substitutedValue`",
            fix,
          )
        }
        if (
          !(substitutedValue.startsWith("/") || substitutedValue.startsWith(".*")) &&
            name == ATTR_PATH_PATTERN
        ) {
          reportUrlError(
            context,
            attribute,
            context.getValueLocation(attribute),
            "`${attribute.name}` attribute should start with `/` or `.*`, but it is `$substitutedValue`",
          )
        }

        // --- Check for query parameters (?) and URI fragments (#) ---

        // Query parameters (?) and URI fragments (#) are not included in the intent when the user
        // clicks on the URI from the browser.
        // To be safe, we'll only perform this check if the intent filter has autoVerify.
        if (intentFilterData.autoVerify == VALUE_TRUE) {
          // Android matches query params separately.
          // Also, the query string, as specified in the Android manifest, should not be
          // URI-encoded.
          // That is, query="param=value!" matches ?param=value! and ?param=value%21.
          // query="param=value%21" matches neither ?param=value! nor ?param=value%21.
          val queryParameters =
            substitutedValue
              .split('?')
              .let { if (it.size == 2) it[1] else "" }
              .substringBefore('#')
              .splitToSequence('&')
              .filter { it.isNotBlank() }
              .toSet()
          // Android treats the fragment as a single section.
          val fragmentInUri =
            substitutedValue.split('#').let { if (it.size == 2) it[1] else "" }.substringBefore('?')

          if (queryParameters.isNotEmpty() || fragmentInUri.isNotBlank()) {
            // The query/fragment string, as specified in the Android manifest, should not be
            // URI-encoded.
            // See getQueryAndFragmentParameters below.
            val namespace = intentFilter.lookupPrefix(ANDROID_URI) ?: ANDROID_NS_NAME
            val queries = queryParameters.sorted().map { """<data $namespace:query="$it" />""" }
            val pathBeforeQueryAndFragment = substitutedValue.split('?', '#')[0]
            val dataElement = (dataTag as? ElementWrapper)?.element ?: continue
            val parent = dataElement.parentNode as? Element ?: continue
            val dataIndent = context.getLocation(dataElement).start?.column ?: DEFAULT_INDENT_AMOUNT
            val fixText =
              when (parent.tagName) {
                TAG_URI_RELATIVE_FILTER_GROUP -> {
                  val newLineAndDataIndent = "\n" + indentation(dataIndent)
                  val newLineAndIndentedFragment =
                    when (fragmentInUri) {
                      "" -> ""
                      else ->
                        """$newLineAndDataIndent<data $namespace:fragment="$fragmentInUri" />"""
                    }
                  "<data $namespace:$name=$pathBeforeQueryAndFragment />" +
                    concatenateWithIndent(queries, newLineAndDataIndent) +
                    newLineAndIndentedFragment
                }
                TAG_INTENT_FILTER -> {
                  val parentIndent = context.getLocation(parent).start?.column ?: 0
                  val startingIndent = indentation(dataIndent)
                  val innerIndentAmount = 2 * dataIndent - parentIndent
                  val newLineAndInnerIndent = "\n" + indentation(innerIndentAmount)
                  val newLineAndIndentedFragment =
                    when (fragmentInUri) {
                      "" -> ""
                      else ->
                        """$newLineAndInnerIndent<data $namespace:fragment="$fragmentInUri" />"""
                    }
                  "<uri-relative-filter-group>" +
                    """$newLineAndInnerIndent<data $namespace:$name="$pathBeforeQueryAndFragment" />""" +
                    concatenateWithIndent(queries, newLineAndInnerIndent) +
                    newLineAndIndentedFragment +
                    "\n$startingIndent</uri-relative-filter-group>"
                }
                else -> null
              }

            reportUrlError(
              context,
              dataElement,
              context.getLocation(dataElement),
              "App link matching does not support query parameters or fragments, " +
                "unless using `<uri-relative-filter-group>` (introduced in Android 15)",
              if (
                (context.project.buildSdk < VersionCodes.VANILLA_ICE_CREAM) ||
                  (context.project.targetSdk < VersionCodes.VANILLA_ICE_CREAM) ||
                  fixText == null
              ) {
                null
              } else {
                fix().replace().with(fixText).build()
              },
            )
          }
        }
        // --- Check for ? in pathPattern and pathAdvancedPattern ---
        // Neither pathPattern nor pathAdvancedPattern supports ? as a regex character:
        // https://developer.android.com/guide/topics/manifest/data-element
        if (
          substitutedValue.contains("?") &&
            name in setOf(ATTR_PATH_PATTERN, ATTR_PATH_ADVANCED_PATTERN)
        ) {
          reportUrlError(
            context,
            attribute,
            context.getValueLocation(attribute),
            "$name does not support `?` as a Regex character",
          )
        }
      }
    }

    // --- Check whether this is "almost an app link" ---
    // i.e. intent filter has everything we would expect from an app link but doesn't have
    // autoVerify
    if (
      // These are all the conditions we expect from app links
      intentFilterData.dataTags.schemes.isNotEmpty() &&
        intentFilterData.dataTags.schemes.all(::isWebScheme) &&
        intentFilterData.dataTags.hostPortPairs.isNotEmpty() &&
        hasActionView &&
        hasCategoryBrowsable &&
        intentFilterData.categorySet.contains(CATEGORY_DEFAULT) &&
        // If intent filter already has autoVerify=true, the intent filter is correct.
        intentFilterData.autoVerify != VALUE_TRUE &&
        // If developer manually specified autoVerify=false, don't generate an error.
        intentFilterData.autoVerify != VALUE_FALSE
    ) {
      // Just highlight the <intent-filter> tag, instead of the whole intent filter
      reportUrlWarning(
        context,
        intentFilter,
        context.getNameLocation(intentFilter),
        """This intent filter has the format of an Android App Link but is \
            |missing the `autoVerify` attribute; add `android:autoVerify="true"` \
            |to ensure your domain will be validated and enable App Link-related \
            |Lint warnings. If you do not want clicked URLs to bring the user to \
            |your app, remove the `android.intent.category.BROWSABLE` category, or \
            |set `android:autoVerify="false"` to make it clear this is not intended \
            |to be an Android App Link."""
          .trimMargin(),
        fix().set(ANDROID_URI, ATTR_AUTO_VERIFY, VALUE_TRUE).build(),
      )
    }

    // Validation
    // autoVerify means this is an Android App Link:
    // https://developer.android.com/training/app-links#android-app-links
    if (hasAutoVerifyButInvalidAppLink(intentFilterData)) {
      if (!hasElementsRequiredForAppLinks(intentFilterData)) {
        // If we are in Studio then add quick-fix data so that Studio adds the
        // "Launch App Links Assistant" quick-fix.
        val fix =
          if (LintClient.isStudio) {
            fix().data(KEY_SHOW_APP_LINKS_ASSISTANT, true)
          } else {
            null
          }

        // --- Check that all elements & attributes that are required for App Links are present ---
        reportUrlError(
          context,
          intentFilter,
          context.getLocation(intentFilter),
          "Missing required elements/attributes for Android App Links",
          fix,
        )
      }
      /* else {
        // If intent filter contains both web and non-web schemes
        val webSchemes = intentFilterData.dataTags.schemes.filter { isWebScheme(it) }
        val customSchemes = intentFilterData.dataTags.schemes.filterNot { isWebScheme(it) }
        if (webSchemes.isNotEmpty() && customSchemes.isNotEmpty()) {
          val parentIndent = context.getLocation(activity).start?.column ?: 0
          val intentFilterIndent =
            context.getLocation(intentFilter).start?.column ?: DEFAULT_INDENT_AMOUNT
          val indentDiff = intentFilterIndent - parentIndent
          val startIndent = indentation(intentFilterIndent)
          val intentFilterChildIndent = indentation(intentFilterIndent + indentDiff)
          val namespace = intentFilter.lookupPrefix(ANDROID_URI) ?: ANDROID_NS_NAME

          val webSchemeIntentFilterText = StringBuilder("<")
          // We know that this intentFilter has autoVerify, so copy it over
          copyTagWithAttributes(intentFilter, webSchemeIntentFilterText)
          webSchemeIntentFilterText.append(">")
          val customSchemeIntentFilterText = StringBuilder("<$TAG_INTENT_FILTER")
          for (i in 0 until intentFilter.attributes.length) {
            val item = intentFilter.attributes.item(i)
            if (item.nodeName.endsWith(ATTR_AUTO_VERIFY)) continue
            customSchemeIntentFilterText.append(" ")
            customSchemeIntentFilterText.append(item.nodeName)
            customSchemeIntentFilterText.append("=\"")
            customSchemeIntentFilterText.append(item.nodeValue)
            customSchemeIntentFilterText.append('"')
          }
          customSchemeIntentFilterText.append(">")
          for (subTag in intentFilter) {
            // If the tag is a data tag, IntentFilterData already has all the needed information.
            // Therefore, we only copy non-data tags.
            if (subTag.tagName != TAG_DATA) {
              for (sb in sequenceOf(webSchemeIntentFilterText, customSchemeIntentFilterText)) {
                sb.append("\n")
                sb.append(intentFilterChildIndent)
                recursivelyCopy(subTag, intentFilterIndent + indentDiff, indentDiff, sb)
              }
            }
          }
          for (scheme in webSchemes.sorted()) {
            webSchemeIntentFilterText.append("\n")
            webSchemeIntentFilterText.append(intentFilterChildIndent)
            webSchemeIntentFilterText.append("""<data $namespace:scheme="$scheme" />""")
          }
          for (scheme in customSchemes.sorted()) {
            customSchemeIntentFilterText.append("\n")
            customSchemeIntentFilterText.append(intentFilterChildIndent)
            customSchemeIntentFilterText.append("""<data $namespace:scheme="$scheme" />""")
          }
          val intentFilterTextAfterSchemes = StringBuilder()
          for ((host, port) in
            intentFilterData.dataTags.hostPortPairs.sortedWith(
              compareBy<Pair<String?, String?>> { it.first }.thenBy { it.second }
            )) {
            intentFilterTextAfterSchemes.append("\n")
            intentFilterTextAfterSchemes.append(intentFilterChildIndent)
            intentFilterTextAfterSchemes.append("""<data $namespace:host="$host"""")
            if (!port.isNullOrBlank()) {
              intentFilterTextAfterSchemes.append(""" $namespace:port="$port"""")
            }
            intentFilterTextAfterSchemes.append(" />")
          }
          for (path in intentFilterData.dataTags.rawPaths.sorted()) {
            intentFilterTextAfterSchemes.append("\n")
            intentFilterTextAfterSchemes.append(intentFilterChildIndent)
            intentFilterTextAfterSchemes.append(
              """<data $namespace:${path.attributeName}="${path.attributeValue}" />"""
            )
          }
          for (mimeType in intentFilterData.dataTags.rawMimeTypes.sorted()) {
            intentFilterTextAfterSchemes.append("\n")
            intentFilterTextAfterSchemes.append(intentFilterChildIndent)
            intentFilterTextAfterSchemes.append("""<data $namespace:mimeType="$mimeType" />""")
          }
          intentFilterTextAfterSchemes.append("\n")
          intentFilterTextAfterSchemes.append(startIndent)
          intentFilterTextAfterSchemes.append("</$TAG_INTENT_FILTER>")

          // Check that web schemes and custom schemes are not used together in intent filters with
          // autoVerify
          val replacementText = webSchemeIntentFilterText
          replacementText.append(intentFilterTextAfterSchemes)
          replacementText.append("\n")
          replacementText.append(startIndent)
          replacementText.append(customSchemeIntentFilterText)
          replacementText.append(intentFilterTextAfterSchemes)
          context.report(
            APP_LINK_SPLIT_TO_WEB_AND_CUSTOM,
            context.getLocation(intentFilter),
            "Split your `http(s)` and custom schemes into separate intent filters",
            fix().replace().with(replacementText.toString()).build(),
          )
        }
      } */
    }

    val showMissingSchemeCheck =
      intentFilterData.dataTags.schemes.isEmpty() &&
        (intentFilterData.dataTags.paths.isNotEmpty() ||
          intentFilterData.dataTags.hostPortPairs.isNotEmpty())

    val firstData =
      (intentFilterData.dataTags.firstOrNull() as? ElementWrapper)?.element
        ?: return intentFilterData

    // --- Check "missing scheme" ---
    // If there are hosts, paths, or ports, then there should be a scheme.
    // We insist on this because hosts, paths, and ports will be ignored if there is no explicit
    // scheme, which makes the intent filter very misleading.
    if (showMissingSchemeCheck) {
      val fix =
        if (intentFilterData.dataTags.hostPortPairs.isEmpty()) {
          // If there are no hosts, ask the user to specify the scheme.
          fix().set().todo(ANDROID_URI, ATTR_SCHEME)
        } else {
          // If there's at least one host, it's likely they want http(s), so we can prompt them with
          // http.
          fix().set().todo(ANDROID_URI, ATTR_SCHEME, "http")
        }
      reportUrlError(
        context,
        firstData,
        context.getLocation(firstData),
        "At least one `scheme` must be specified",
        fix.build(),
      )
    }

    // --- Check "missing URI" (This intent filter has a view action but no URI) ---
    // We only show this check if the "missing scheme check" isn't already showing, because they're
    // very similar.
    val showMissingUriCheck =
      hasActionView &&
        // Per documentation
        //   https://developer.android.com/guide/topics/manifest/data-element.html
        // "If the filter has a data type set (the mimeType attribute) but no scheme, the
        //  content: and file: schemes are assumed."
        intentFilterData.dataTags.schemes.isEmpty() &&
        intentFilterData.dataTags.mimeTypes.isEmpty()
    if (!showMissingSchemeCheck && showMissingUriCheck) {
      reportUrlError(
        context,
        firstData,
        context.getLocation(firstData),
        "VIEW actions require a URI",
        fix()
          .alternatives(
            fix().set().todo(ANDROID_URI, ATTR_SCHEME).build(),
            fix().set().todo(ANDROID_URI, ATTR_MIME_TYPE).build(),
          ),
      )
    }

    // --- Check "missing host" (Hosts are required when a path is used. ) ---
    // We insist on this because paths will be ignored if there is no host, which makes the intent
    // filter very misleading.
    if (
      intentFilterData.dataTags.paths.isNotEmpty() &&
        intentFilterData.dataTags.hostPortPairs.isEmpty()
    ) {
      val fix = LintFix.create().set().todo(ANDROID_URI, ATTR_HOST).build()
      reportUrlError(
        context,
        firstData,
        context.getLocation(firstData),
        "At least one `host` must be specified",
        fix,
      )
    }

    // --- Check "view + browsable" (If this intent filter has an ACTION_VIEW action, and it has a
    // http URL but doesn't have BROWSABLE, it may be a mistake, so we will report a warning.) ---
    if (
      hasActionView && intentFilterData.dataTags.schemes.any(::isWebScheme) && !hasCategoryBrowsable
    ) {
      reportUrlError(
        context,
        intentFilter,
        context.getLocation(intentFilter),
        "Activity supporting ACTION_VIEW is not set as BROWSABLE",
      )
    }
    return intentFilterData
  }

  companion object {
    internal const val ACTION_VIEW = "android.intent.action.VIEW"
    internal const val CATEGORY_BROWSABLE = "android.intent.category.BROWSABLE"
    internal const val CATEGORY_DEFAULT = "android.intent.category.DEFAULT"
    // Per documentation
    //   https://developer.android.com/guide/topics/manifest/data-element.html
    // "If the filter has a data type set (the mimeType attribute) but no scheme, the
    //  content: and file: schemes are assumed."
    internal val IMPLICIT_SCHEMES = setOf("file", "content")
    internal val PATH_ATTRIBUTES =
      listOf(
        ATTR_PATH,
        ATTR_PATH_PREFIX,
        ATTR_PATH_PATTERN,
        ATTR_PATH_ADVANCED_PATTERN,
        ATTR_PATH_SUFFIX,
      )
    private const val HTTP = "http"
    private const val HTTPS = "https"
    private const val DEFAULT_INDENT_AMOUNT = 4

    private fun indentation(indentAmount: Int) = " ".repeat(indentAmount)

    private fun replaceUrlWithValue(context: XmlContext?, str: String): String {
      if (context == null) {
        return str
      }
      val client = context.client
      val url = ResourceUrl.parse(str)
      if (url == null || url.isFramework) {
        return str
      }
      val project = context.project
      val resources = client.getResources(project, ResourceRepositoryScope.ALL_DEPENDENCIES)
      val items = resources.getResources(ResourceNamespace.TODO(), ResourceType.STRING, url.name)
      if (items.isEmpty()) {
        return str
      }
      val resourceValue = items[0].resourceValue ?: return str
      return if (resourceValue.value == null) str else resourceValue.value!!
    }

    internal fun attrToAndroidPatternMatcher(attr: String): Int {
      return when (attr) {
        ATTR_PATH -> PATTERN_LITERAL
        ATTR_PATH_PREFIX -> PATTERN_PREFIX
        ATTR_PATH_PATTERN -> PATTERN_SIMPLE_GLOB
        ATTR_PATH_ADVANCED_PATTERN -> PATTERN_ADVANCED_GLOB
        ATTR_PATH_SUFFIX -> PATTERN_SUFFIX
        else ->
          throw AssertionError(
            "Input was required to be one of the <data> path attributes, but was $attr"
          )
      }
    }

    fun androidPatternMatcherToAttr(androidPatternMatcherConstant: Int): String {
      return when (androidPatternMatcherConstant) {
        PATTERN_LITERAL -> ATTR_PATH
        PATTERN_PREFIX -> ATTR_PATH_PREFIX
        PATTERN_SIMPLE_GLOB -> ATTR_PATH_PATTERN
        PATTERN_ADVANCED_GLOB -> ATTR_PATH_ADVANCED_PATTERN
        PATTERN_SUFFIX -> ATTR_PATH_SUFFIX
        else ->
          throw AssertionError(
            "Input was required to be an AndroidPatternMatcher constant but was $androidPatternMatcherConstant"
          )
      }
    }

    fun isWebScheme(scheme: String): Boolean {
      return scheme == HTTP || scheme == HTTPS
    }

    data class Path(val attributeValue: String, val attributeName: String) : Comparable<Path> {
      fun toPatternMatcher(): AndroidPatternMatcher {
        return AndroidPatternMatcher(attributeValue, attrToAndroidPatternMatcher(attributeName))
      }

      override fun compareTo(other: Path): Int {
        if (this.attributeName < other.attributeName) return -1
        return (this.attributeValue.compareTo(other.attributeValue))
      }

      override fun toString(): String {
        // Don't update this, since that would change AppLinksValidDetector's messages.
        return when (attributeName) {
          ATTR_PATH -> "literal "
          ATTR_PATH_PREFIX -> "prefix "
          ATTR_PATH_PATTERN -> "glob "
          ATTR_PATH_SUFFIX -> "suffix "
          ATTR_PATH_ADVANCED_PATTERN -> "advanced "
          else ->
            throw AssertionError(
              "Expected attributeName to be a path attribute but was $attributeName"
            )
        } + attributeValue
      }
    }

    data class Query(val attributeValue: String, val attributeName: String) : Comparable<Query> {
      override fun compareTo(other: Query): Int {
        return compareValuesBy(this, other, { it.attributeName }, { it.attributeValue })
      }
    }

    data class Fragment(val attributeValue: String, val attributeName: String) :
      Comparable<Fragment> {
      override fun compareTo(other: Fragment): Int {
        return compareValuesBy(this, other, { it.attributeName }, { it.attributeValue })
      }
    }

    interface TagWrapper {
      val name: String
      val subTags: Iterable<TagWrapper>

      /** Get the [AttributeWrapper] containing an attribute node (name=value). */
      fun getAttributeWrapper(attrName: String): AttributeWrapper?
    }

    interface AttributeWrapper {
      /** The attribute name. */
      val name: String

      /** The attribute value without applying string substitutions. */
      val rawValue: String?

      /** The attribute value after applying string substitutions. */
      val substitutedValue: String?
    }

    class ElementWrapper(val element: Element, private val context: XmlContext) : TagWrapper {
      override val name: String = element.tagName

      // Use asSequence for performance improvement
      override val subTags = XmlUtils.getSubTags(element).map { ElementWrapper(it, context) }

      override fun getAttributeWrapper(attrName: String): AttrWrapper? =
        element.getAttributeNodeNS(ANDROID_URI, attrName)?.let { AttrWrapper(it, context) }
    }

    class AttrWrapper(val attr: Attr, private val context: XmlContext) : AttributeWrapper {
      override val name: String = attr.localName // Exclude the namespace prefix.

      override val rawValue: String? by
        lazy(LazyThreadSafetyMode.NONE) {
          val fallbackReturnValue = attr.value ?: return@lazy null
          // The below can actually be null, so the ?: return is needed.
          val location =
            try {
              context.getValueLocation(attr)
            } catch (_: StringIndexOutOfBoundsException) {
              null
            } ?: return@lazy fallbackReturnValue
          val start = location.start?.offset ?: return@lazy fallbackReturnValue
          val end = location.end?.offset ?: return@lazy fallbackReturnValue
          return@lazy context.getContents()?.substring(start, end) ?: fallbackReturnValue
        }

      override val substitutedValue: String? by
        lazy(LazyThreadSafetyMode.NONE) {
          val value = attr.value ?: return@lazy null
          if (value.startsWith(PREFIX_RESOURCE_REF) || value.startsWith(PREFIX_THEME_REF)) {
            return@lazy replaceUrlWithValue(context, value)
          }
          // Return `value` as a fallback so we still return something when resolution fails
          return@lazy resolvePlaceHolders(context.project, value) ?: value
        }
    }

    data class IntentFilterData(
      val autoVerify: String?,
      val order: Int,
      val priority: Int,
      val actions: List<AttributeWrapper>,
      val categories: List<AttributeWrapper>,
      val dataTags: DataTagInfo,
      val uriRelativeFilterGroups: List<UriRelativeFilterGroup>,
    ) {
      val actionSet by
        lazy(LazyThreadSafetyMode.NONE) { actions.mapTo(mutableSetOf()) { it.substitutedValue } }
      val categorySet by
        lazy(LazyThreadSafetyMode.NONE) { categories.mapTo(mutableSetOf()) { it.substitutedValue } }

      /**
       * Matches a URL against this info, and returns null if successful or the failure reason if
       * not a match
       *
       * @param testUrl the URL to match
       * @return null for a successful match or the failure reason
       */
      fun match(testUrl: URL): String? {
        val schemesIncludingImplicitSchemes =
          when {
            dataTags.schemes.isEmpty() && dataTags.mimeTypes.isNotEmpty() -> IMPLICIT_SCHEMES
            else -> dataTags.schemes
          }
        val schemeOk =
          schemesIncludingImplicitSchemes.any { scheme: String ->
            scheme == testUrl.protocol || isSubstituted(scheme)
          }
        if (!schemeOk) {
          return "did not match scheme ${Joiner.on(", ").join(dataTags.schemes)}"
        }
        if (dataTags.hostPortPairs.isNotEmpty()) {
          val hostOk =
            dataTags.hostPortPairs.any { (host, port) ->
              host.let { matchesHost(testUrl.host, it) || isSubstituted(it) } &&
                (port?.let { testUrl.port.toString() == port || isSubstituted(port) } != false)
            }
          if (!hostOk) {
            return "did not match${if (dataTags.hostPortPairs.size > 1) " any of" else ""} ${
              dataTags.hostPortPairs.joinToString(", ") { (host, port) -> if (port != null) "host+port $host:$port" else "host $host" }
            }"
          }
        }
        if (dataTags.paths.isNotEmpty()) {
          val testPath = testUrl.path
          val pathOk =
            dataTags.paths.any {
              isSubstituted(it.attributeValue) || it.toPatternMatcher().match(testPath)
            }
          if (!pathOk) {
            val sb = StringBuilder()
            dataTags.paths.forEach { sb.append("path ").append(it.toString()).append(", ") }
            if (sb.endsWith(", ")) {
              sb.setLength(sb.length - 2)
            }
            var message = "did not match $sb"
            if (
              dataTags.paths.any {
                !isSubstituted(it.attributeValue) && it.attributeValue.any(::isUpperCase)
              } || testPath.any(::isUpperCase)
            ) {
              message += " Note that matching is case sensitive."
            }
            return message
          }
        }
        return null // OK
      }

      /**
       * Check whether a given host matches the hostRegex. The hostRegex could be a regular host
       * name, or it could contain only one '*', such as *.example.com, where '*' matches any string
       * whose length is at least 1.
       *
       * @param actualHost The actual host we want to check.
       * @param hostPattern The criteria host, which could contain a '*'.
       * @return Whether the actualHost matches the hostRegex
       */
      private fun matchesHost(actualHost: String, hostPattern: String): Boolean {
        // Per https://developer.android.com/guide/topics/manifest/data-element.html
        // the asterisk must be the first character
        return if (!hostPattern.startsWith("*")) {
          actualHost == hostPattern
        } else
          try {
            val pattern = Regex(".*${Pattern.quote(hostPattern.substring(1))}")
            actualHost.matches(pattern)
          } catch (_: Throwable) {
            // Make sure we don't fail to compile the regex, though with the quote call
            // above this really shouldn't happen
            false
          }
      }
    }

    /** Information collected from all data tags within the parent. */
    data class DataTagInfo(val dataTagElements: List<TagWrapper>) : Iterable<TagWrapper> {
      val isEmpty = dataTagElements.isEmpty()
      val schemeElements by
        lazy(LazyThreadSafetyMode.NONE) {
          dataTagElements.mapNotNull { it.getAttributeWrapper(ATTR_SCHEME) }
        }
      val schemes by
        lazy(LazyThreadSafetyMode.NONE) {
          schemeElements.mapNotNullTo(mutableSetOf()) { it.substitutedValue }
        }
      val rawSchemes by
        lazy(LazyThreadSafetyMode.NONE) {
          schemeElements.mapNotNullTo(mutableSetOf()) { it.rawValue }
        }
      val hostElements by
        lazy(LazyThreadSafetyMode.NONE) {
          dataTagElements.mapNotNull { it.getAttributeWrapper(ATTR_HOST) }
        }
      val portElements by
        lazy(LazyThreadSafetyMode.NONE) {
          dataTagElements.mapNotNull { it.getAttributeWrapper(ATTR_PORT) }
        }
      val hostPortPairs by
        lazy(LazyThreadSafetyMode.NONE) {
          dataTagElements.mapNotNullTo(mutableSetOf()) {
            val host =
              it.getAttributeWrapper(ATTR_HOST)?.substitutedValue ?: return@mapNotNullTo null
            Pair(host, it.getAttributeWrapper(ATTR_PORT)?.substitutedValue)
          }
        }
      val rawHostPortPairs by
        lazy(LazyThreadSafetyMode.NONE) {
          dataTagElements.mapNotNull {
            val host = it.getAttributeWrapper(ATTR_HOST)?.rawValue ?: return@mapNotNull null
            Pair(host, it.getAttributeWrapper(ATTR_PORT)?.rawValue)
          }
        }
      val pathElements by
        lazy(LazyThreadSafetyMode.NONE) {
          val result = mutableSetOf<AttributeWrapper>()
          for (subTag in dataTagElements) {
            for (value in PATH_ATTRIBUTES) {
              subTag.getAttributeWrapper(value)?.let { result.add(it) }
            }
          }
          result
        }
      val paths by
        lazy(LazyThreadSafetyMode.NONE) {
          pathElements.mapNotNullTo(mutableSetOf()) { attr ->
            attr.substitutedValue?.let { Path(attributeValue = it, attributeName = attr.name) }
          }
        }
      val rawPaths by
        lazy(LazyThreadSafetyMode.NONE) {
          pathElements.mapNotNullTo(mutableSetOf()) { attr ->
            attr.rawValue?.let { Path(attributeValue = it, attributeName = attr.name) }
          }
        }
      val mimeTypeElements by
        lazy(LazyThreadSafetyMode.NONE) {
          dataTagElements.mapNotNull { it.getAttributeWrapper(ATTR_MIME_TYPE) }
        }
      val mimeTypes by
        lazy(LazyThreadSafetyMode.NONE) {
          mimeTypeElements.mapNotNullTo(mutableSetOf()) { it.substitutedValue }
        }
      val rawMimeTypes by
        lazy(LazyThreadSafetyMode.NONE) {
          mimeTypeElements.mapNotNullTo(mutableSetOf()) { it.rawValue }
        }

      override fun iterator(): Iterator<TagWrapper> = dataTagElements.iterator()
    }

    data class UriRelativeFilterGroup(
      val allow: Boolean,
      val dataTagInfo: DataTagInfo,
      val queries: Set<Query>,
      val rawQueries: Set<Query>,
      val queryElements: List<TagWrapper>,
      val fragments: Set<Fragment>,
      val rawFragments: Set<Fragment>,
      val fragmentElements: List<TagWrapper>,
    )

    fun getIntentFilterData(intentFilter: TagWrapper): IntentFilterData {
      val autoVerify = intentFilter.getAttributeWrapper(ATTR_AUTO_VERIFY)?.substitutedValue
      val order =
        intentFilter.getAttributeWrapper(ATTR_ORDER)?.substitutedValue?.ifEmpty { VALUE_0 }?.toInt()
          ?: 0
      val priority =
        intentFilter
          .getAttributeWrapper(ATTR_PRIORITY)
          ?.substitutedValue
          ?.ifEmpty { VALUE_0 }
          ?.toInt() ?: 0
      val actions = mutableListOf<AttributeWrapper>()
      val categories = mutableListOf<AttributeWrapper>()
      val dataTagElements = mutableListOf<TagWrapper>()
      for (subTag in intentFilter.subTags) {
        when (subTag.name) {
          TAG_ACTION -> subTag.getAttributeWrapper(ATTRIBUTE_NAME)?.let { actions.add(it) }
          TAG_CATEGORY -> subTag.getAttributeWrapper(ATTRIBUTE_NAME)?.let { categories.add(it) }
          TAG_DATA -> dataTagElements.add(subTag)
        }
      }
      return IntentFilterData(
        autoVerify,
        order,
        priority,
        actions,
        categories,
        DataTagInfo(dataTagElements),
        listOf(), // TODO(b/370997994)
      )
    }

    fun hasAutoVerifyButInvalidAppLink(intentFilter: TagWrapper): Boolean {
      return hasAutoVerifyButInvalidAppLink(getIntentFilterData(intentFilter))
    }

    fun hasAutoVerifyButInvalidAppLink(data: IntentFilterData): Boolean {
      return data.autoVerify == VALUE_TRUE &&
        (!hasElementsRequiredForAppLinks(data) ||
          data.dataTags.schemes.any { !isSubstituted(it) && !isWebScheme(it) })
    }

    private fun hasElementsRequiredForAppLinks(data: IntentFilterData): Boolean {
      return (data.actions.any { it.substitutedValue == ACTION_VIEW } &&
        data.categories.any { it.substitutedValue == CATEGORY_DEFAULT } &&
        data.categories.any { it.substitutedValue == CATEGORY_BROWSABLE } &&
        data.dataTags.schemes.any { isSubstituted(it) || isWebScheme(it) } &&
        data.dataTags.hostPortPairs.isNotEmpty()) ||
        // If schemes are empty and hosts are non-empty, we already show a different check; showing
        // this one would be a duplicate.
        (data.dataTags.schemes.isEmpty() && data.dataTags.hostPortPairs.isNotEmpty())
    }

    private fun concatenateWithIndent(
      inputs: List<String>,
      newLineAndIndentString: String,
    ): String {
      return when {
        inputs.isEmpty() -> ""
        else -> inputs.joinToString(newLineAndIndentString, prefix = newLineAndIndentString)
      }
    }

    private fun copyTagWithAttributes(element: Element, sb: StringBuilder = StringBuilder()) {
      sb.append(element.tagName)
      for (i in 0 until element.attributes.length) {
        val item = element.attributes.item(i)
        sb.append(" ")
        sb.append(item.nodeName)
        sb.append("=\"")
        sb.append(item.nodeValue)
        sb.append('"')
      }
    }

    private fun recursivelyCopy(
      element: Element,
      startingIndentAmount: Int,
      indentDiff: Int,
      sb: StringBuilder = StringBuilder(),
    ) {
      sb.append("<")
      copyTagWithAttributes(element, sb)
      if (element.subtagCount() == 0) {
        sb.append(" />")
        return
      }
      sb.append(">")
      for (subTag in element) {
        sb.append("\n")
        for (i in 0 until (startingIndentAmount + indentDiff)) {
          sb.append(" ")
        }
        recursivelyCopy(subTag, startingIndentAmount + indentDiff, indentDiff, sb)
      }
      sb.append("\n")
      for (i in 0 until startingIndentAmount) {
        sb.append(" ")
      }
      sb.append("</${element.tagName}>")
    }

    private val IMPLEMENTATION =
      Implementation(AppLinksValidDetector::class.java, Scope.MANIFEST_SCOPE)

    @JvmField
    val TEST_URL =
      Issue.create(
        id = "TestAppLink",
        briefDescription = "Unmatched URLs",
        explanation =
          """
                Using one or more `tools:validation testUrl="some url"/>` elements in your manifest allows \
                the link attributes in your intent filter to be checked for matches.
                """,
        category = Category.CORRECTNESS,
        priority = 5,
        severity = Severity.FATAL,
        implementation = IMPLEMENTATION,
      )

    @JvmField
    val VALIDATION =
      Issue.create(
          id = "AppLinkUrlError",
          briefDescription = "URI invalid",
          explanation =
            """
                Ensure your intent filter has the documented elements for deep links, web links, or Android \
                App Links.""",
          category = Category.CORRECTNESS,
          priority = 5,
          moreInfo = "https://developer.android.com/training/app-links",
          severity = Severity.ERROR,
          implementation = IMPLEMENTATION,
        )
        .addMoreInfo("https://g.co/AppIndexing/AndroidStudio")

    @JvmField
    val APP_LINK_WARNING =
      Issue.create(
          id = "AppLinkWarning",
          briefDescription = "App Link warning",
          explanation =
            """From Android 12, intent filters that use the HTTP and HTTPS schemes will no longer \
              bring the user to your app when the user clicks a link, unless the intent filter is \
              an Android App Link. Such intent filters must include certain elements, and at least \
              one Android App Link for each domain must have `android:autoVerify="true"` to verify \
              ownership of the domain. We recommend adding `android:autoVerify="true"` to any intent \
              filter that is intended to be an App Link, in case the other App Links are modified.""",
          category = Category.CORRECTNESS,
          priority = 5,
          moreInfo = "https://developer.android.com/training/app-links",
          severity = Severity.WARNING,
          implementation = IMPLEMENTATION,
        )
        .addMoreInfo("https://g.co/AppIndexing/AndroidStudio")

    /**
     * Only used for compatibility issue lookup (the driver suppression check takes an issue, not an
     * id)
     */
    private val _OLD_ISSUE_URL =
      Issue.create(
        id = "GoogleAppIndexingUrlError",
        briefDescription = "?",
        explanation = "?",
        category = Category.USABILITY,
        priority = 5,
        severity = Severity.ERROR,
        implementation = IMPLEMENTATION,
      )

    /** Multiple attributes in an intent filter data declaration */
    @JvmField
    @Suppress("LintImplUnexpectedDomain")
    val INTENT_FILTER_UNIQUE_DATA_ATTRIBUTES =
      Issue.create(
        id = "IntentFilterUniqueDataAttributes",
        briefDescription = "Data tags should only declare unique attributes",
        explanation =
          """
                `<intent-filter>` `<data>` tags should only declare a single unique attribute \
                (i.e. scheme OR host, but not both). This better matches the runtime behavior of \
                intent filters, as they combine all of the declared data attributes into a single \
                matcher which is allowed to handle any combination across attribute types.

                For example, the following two `<intent-filter>` declarations are the same:
                ```xml
                <intent-filter>
                    <data android:scheme="http" android:host="example.com" />
                    <data android:scheme="https" android:host="example.org" />
                </intent-filter>
                ```

                ```xml
                <intent-filter>
                    <data android:scheme="http"/>
                    <data android:scheme="https"/>
                    <data android:host="example.com" />
                    <data android:host="example.org" />
                </intent-filter>
                ```

                They both handle all of the following:
                * http://example.com
                * https://example.com
                * http://example.org
                * https://example.org

                The second one better communicates the combining behavior and is clearer to an \
                external reader that one should not rely on the scheme/host being self contained. \
                It is not obvious in the first that http://example.org is also matched, which can \
                lead to confusion (or incorrect behavior) with a more complex set of schemes/hosts.

                Note that this does not apply to host + port, as those must be declared in the same \
                `<data>` tag and are only associated with each other.
                """,
        category = Category.CORRECTNESS,
        severity = Severity.WARNING,
        moreInfo = "https://developer.android.com/guide/components/intents-filters",
        implementation = IMPLEMENTATION,
      )

    /** Intent filter with autoVerify uses both web and custom schemes */
    @JvmField
    val APP_LINK_SPLIT_TO_WEB_AND_CUSTOM =
      Issue.create(
        id = "AppLinkSplitToWebAndCustom",
        briefDescription = "Android App links should only use http(s) schemes",
        explanation =
          """
          In order for Android App Links to open in your app, Android must perform domain \
          verification. However, Android only sends domain verification requests for \
          `<intent-filter>`s that only contain http(s) schemes.

          To ensure correct behavior, please split your http(s) schemes and other schemes \
          into two different `<intent-filter>`s.
        """,
        category = Category.CORRECTNESS,
        severity = Severity.ERROR,
        moreInfo =
          "https://developer.android.com/training/app-links/verify-android-applinks#add-intent-filters",
        implementation = IMPLEMENTATION,
      )

    private const val TAG_VALIDATION = "validation"

    const val KEY_SHOW_APP_LINKS_ASSISTANT = "SHOW_APP_LINKS_ASSISTANT"

    // Path matchers use the order used in
    // https://developer.android.com/guide/topics/manifest/data-element
    // <scheme>://<host>:<port>[<path>|<pathPrefix>|<pathPattern>|<pathSuffix>|<pathAdvancedPattern>]
    private val INTENT_FILTER_DATA_SORT_REFERENCE =
      listOf(
        ATTR_SCHEME,
        ATTR_HOST,
        ATTR_PORT,
        ATTR_PATH,
        ATTR_PATH_PREFIX,
        ATTR_PATH_PATTERN,
        ATTR_PATH_SUFFIX,
        ATTR_PATH_ADVANCED_PATTERN,
        ATTR_MIME_TYPE,
      )

    private fun isSubstituted(expression: String): Boolean {
      return isDataBindingExpression(expression) || isManifestPlaceHolderExpression(expression)
    }
  }

  override fun sameMessage(issue: Issue, new: String, old: String): Boolean {
    if (issue == VALIDATION && old == "Missing URL" && new == "VIEW actions require a URI")
      return true // See commit 406811b
    return super.sameMessage(issue, new, old)
  }
}
