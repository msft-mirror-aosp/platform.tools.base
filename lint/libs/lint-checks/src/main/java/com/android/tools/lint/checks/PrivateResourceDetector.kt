/*
 * Copyright (C) 2012 The Android Open Source Project
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

import com.android.SdkConstants.ATTR_NAME
import com.android.SdkConstants.ATTR_REF_PREFIX
import com.android.SdkConstants.FD_RES_VALUES
import com.android.SdkConstants.TAG_ARRAY
import com.android.SdkConstants.TAG_INTEGER_ARRAY
import com.android.SdkConstants.TAG_PLURALS
import com.android.SdkConstants.TAG_RESOURCES
import com.android.SdkConstants.TAG_STRING_ARRAY
import com.android.SdkConstants.TAG_STYLE
import com.android.SdkConstants.TOOLS_URI
import com.android.SdkConstants.VALUE_TRUE
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.resources.resourceNameToFieldName
import com.android.resources.FolderTypeRelationship
import com.android.resources.ResourceFolderType
import com.android.resources.ResourceType
import com.android.resources.ResourceUrl
import com.android.tools.lint.client.api.ResourceRepositoryScope
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Incident
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.LintMap
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.ResourceXmlDetector
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.detector.api.XmlContext
import com.android.tools.lint.detector.api.getBaseName
import com.android.tools.lint.detector.api.isXmlFile
import com.android.utils.SdkUtils
import com.android.utils.XmlUtils
import com.intellij.psi.PsiField
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UReferenceExpression
import org.w3c.dom.Attr
import org.w3c.dom.Element
import org.w3c.dom.Node

/** Check which looks for access of private resources. */
class PrivateResourceDetector
/** Constructs a new detector */
: ResourceXmlDetector(), SourceCodeScanner {
  companion object {
    /** Attribute for overriding a resource */
    private const val ATTR_OVERRIDE = "override"

    private const val KEY_URL = "url"

    private val IMPLEMENTATION =
      Implementation(
        PrivateResourceDetector::class.java,
        Scope.JAVA_AND_RESOURCE_FILES,
        Scope.JAVA_FILE_SCOPE,
        Scope.RESOURCE_FILE_SCOPE,
      )

    @JvmField
    val ISSUE: Issue =
      Issue.create(
        id = "PrivateResource",
        briefDescription = "Using private resources",
        explanation =
          """
          Private resources should not be referenced; the may not be present everywhere, \
          and even where they are they may disappear without notice.

          To fix this, copy the resource into your own project instead.
          """,
        category = Category.CORRECTNESS,
        priority = 3,
        severity = Severity.WARNING,
        implementation = IMPLEMENTATION,
      )
  }

  /** List of resource URLs overriding private resources locally */
  private var overriding: MutableList<String>? = null

  // ---- implements SourceCodeScanner ----
  override fun appliesToResourceRefs(): Boolean {
    return true
  }

  override fun visitResourceReference(
    context: JavaContext,
    node: UElement,
    type: ResourceType,
    name: String,
    isFramework: Boolean,
  ) {
    if (!isFramework && isPrivate(context, type, name)) {
      // See if it's a local package reference
      var foreignPackage = false
      val globalAnalysis = context.isGlobalAnalysis()
      if (node is UReferenceExpression) {
        val resolved = node.resolve()
        if (resolved is PsiField) {
          val pkg = context.evaluator.getPackage(resolved)
          if (pkg != null) {
            val pkgName = pkg.qualifiedName
            if (
              !(pkgName == context.project.getPackage() ||
                globalAnalysis && pkgName == context.mainProject.getPackage())
            ) {
              foreignPackage = true
            }
          }
        }
      }

      // See if this is resource we're overriding locally
      if (!foreignPackage) {
        if (globalAnalysis && referencedInMain(context, type, name)) {
          return
        }

        if (isOverriding(type, name)) {
          return
        }
      }

      val message: String = createUsageErrorMessage(context, type, name)
      val incident = Incident(ISSUE, node, context.getLocation(node), message)
      if (globalAnalysis) {
        context.report(incident)
      } else {
        context.report(incident, map().put(KEY_URL, "@$type/$name"))
      }
    }
  }

  override fun filterIncident(context: Context, incident: Incident, map: LintMap): Boolean {
    val urlString = map.getString(KEY_URL, null)
    if (urlString != null) {
      val url = ResourceUrl.parse(urlString)
      return url != null && !referencedInMain(context, url.type, url.name)
    }

    return false
  }

  private fun referencedInMain(
    context: Context,
    resourceType: ResourceType,
    name: String,
  ): Boolean {
    val client = context.client
    val mainProject = context.mainProject
    val repository = client.getResources(mainProject, ResourceRepositoryScope.LOCAL_DEPENDENCIES)
    return repository.hasResources(ResourceNamespace.TODO(), resourceType, name)
  }

  // ---- Implements XmlScanner ----

  override fun getApplicableAttributes(): List<String> {
    return ALL
  }

  /** Check resource references: accessing a private resource from an upstream library? */
  override fun visitAttribute(context: XmlContext, attribute: Attr) {
    val value = attribute.nodeValue
    val url = ResourceUrl.parse(value)
    if (isPrivate(context, url)) {
      val message: String = createUsageErrorMessage(context, url!!.type, url.name)
      context.report(ISSUE, attribute, context.getValueLocation(attribute), message)
    }
  }

  /** Check resource definitions: overriding a private resource from an upstream library? */
  override fun getApplicableElements(): List<String> {
    return listOf(
      TAG_STYLE,
      TAG_RESOURCES,
      TAG_ARRAY,
      TAG_STRING_ARRAY,
      TAG_INTEGER_ARRAY,
      TAG_PLURALS,
    )
  }

  override fun visitElement(context: XmlContext, element: Element) {
    if (TAG_RESOURCES == element.tagName) {
      for (item in XmlUtils.getSubTags(element)) {
        val nameAttribute = item.getAttributeNode(ATTR_NAME)
        if (nameAttribute != null) {
          val name = resourceNameToFieldName(nameAttribute.value)
          val resourceType = ResourceType.fromXmlTag(item)
          if (resourceType != null && isPrivate(context, resourceType, name)) {
            recordOverriding(resourceType, name)
            if (VALUE_TRUE != item.getAttributeNS(TOOLS_URI, ATTR_OVERRIDE)) {
              val message: String = createOverrideErrorMessage(context, resourceType, name)
              val location = context.getValueLocation(nameAttribute)
              context.report(ISSUE, nameAttribute, location, message)
            }
          }
        }
      }
    } else {
      assert(
        TAG_STYLE == element.tagName ||
          TAG_ARRAY == element.tagName ||
          TAG_PLURALS == element.tagName ||
          TAG_INTEGER_ARRAY == element.tagName ||
          TAG_STRING_ARRAY == element.tagName
      )
      for (item in XmlUtils.getSubTags(element)) {
        checkChildRefs(context, item)
      }
    }
  }

  private fun recordOverriding(resourceType: ResourceType, resourceName: String) {
    val overriding = overriding ?: mutableListOf<String>().also { overriding = it }
    overriding.add("${resourceType.getName()}:$resourceName")
  }

  private fun isOverriding(type: ResourceType, name: String): Boolean {
    val overriding = overriding ?: return false
    return overriding.contains("$type:$name")
  }

  private fun isPrivate(context: Context, type: ResourceType, name: String): Boolean {
    if (type == ResourceType.ID) {
      // No need to complain about "overriding" id's. There's no harm
      // in doing so. (This avoids warning about cases like for example
      // appcompat's (private) @id/title resource, which would otherwise
      // flag any attempt to create a resource named title in the user's
      // project.
      return false
    }

    val lookup = context.project.getResourceVisibility()
    return lookup.isPrivate(type, name)
  }

  private fun isPrivate(context: Context, url: ResourceUrl?): Boolean {
    return url != null && !url.isFramework && isPrivate(context, url.type, url.name)
  }

  private fun checkChildRefs(context: XmlContext, item: Element) {
    // Look for ?attr/ and @dimen/foo etc references in the item children
    val childNodes = item.childNodes
    var i = 0
    val n = childNodes.length
    while (i < n) {
      val child = childNodes.item(i)
      if (child.nodeType == Node.TEXT_NODE) {
        val text = child.nodeValue

        val index = text.indexOf(ATTR_REF_PREFIX)
        if (index != -1) {
          val name = text.substring(index + ATTR_REF_PREFIX.length).trim()
          if (isPrivate(context, ResourceType.ATTR, name)) {
            val message: String = createUsageErrorMessage(context, ResourceType.ATTR, name)
            context.report(ISSUE, item, context.getLocation(child), message)
          }
        } else {
          var j = 0
          val m = text.length
          while (j < m) {
            val c = text[j]
            if (c == '@') {
              val url = ResourceUrl.parse(text.trim())
              if (isPrivate(context, url)) {
                val message: String = createUsageErrorMessage(context, url!!.type, url.name)
                context.report(ISSUE, item, context.getLocation(child), message)
              }
              break
            } else if (!Character.isWhitespace(c)) {
              break
            }
            j++
          }
        }
      }
      i++
    }
  }

  override fun beforeCheckFile(context: Context) {
    val file = context.file
    if (!isXmlFile(file) && !SdkUtils.isBitmapFile(file)) {
      return
    }
    val parentName = file.getParentFile().getName()
    val dash = parentName.indexOf('-')
    if (dash != -1 || FD_RES_VALUES == parentName) {
      return
    }
    val folderType = ResourceFolderType.getFolderType(parentName) ?: return
    val types = FolderTypeRelationship.getRelatedResourceTypes(folderType)
    if (types.isEmpty()) {
      return
    }
    val type = types[0]
    val resourceName = resourceNameToFieldName(getBaseName(file.getName()))
    if (isPrivate(context, type, resourceName)) {
      val message: String = createOverrideErrorMessage(context, type, resourceName)
      val location = Location.create(file)
      context.report(ISSUE, location, message)
    }
  }

  private fun createOverrideErrorMessage(
    context: Context,
    type: ResourceType,
    name: String,
  ): String {
    val libraryName: String = getLibraryName(context, type, name)
    return "Overriding `@$type/$name` which is marked as private in $libraryName. If " +
      "deliberate, use tools:override=\"true\", otherwise pick a " +
      "different name."
  }

  private fun createUsageErrorMessage(context: Context, type: ResourceType, name: String): String {
    val libraryName: String = getLibraryName(context, type, name)
    return "The resource `@$type/$name` is marked as private in $libraryName"
  }

  /** Pick a suitable name to describe the library defining the private resource */
  private fun getLibraryName(context: Context, type: ResourceType, name: String): String {
    val lookup = context.project.getResourceVisibility()
    val library = lookup.getPrivateIn(type, name)
    return library ?: "the library"
  }
}
