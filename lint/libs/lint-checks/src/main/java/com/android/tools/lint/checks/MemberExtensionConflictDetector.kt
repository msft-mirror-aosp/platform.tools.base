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
package com.android.tools.lint.checks

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope.Companion.JAVA_FILE_SCOPE
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.detector.api.isKotlin
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.KaApplicableCallCandidateInfo
import org.jetbrains.kotlin.analysis.api.resolution.KaCall
import org.jetbrains.kotlin.analysis.api.resolution.KaCallCandidateInfo
import org.jetbrains.kotlin.analysis.api.resolution.KaCallableMemberCall
import org.jetbrains.kotlin.analysis.api.resolution.KaCompoundArrayAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.KaCompoundVariableAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.KaPartiallyAppliedSymbol
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.name
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.getQualifiedElementSelector
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UImportStatement
import org.jetbrains.uast.USimpleNameReferenceExpression

class MemberExtensionConflictDetector : Detector(), SourceCodeScanner {
  companion object {
    private val IMPLEMENTATION =
      Implementation(MemberExtensionConflictDetector::class.java, JAVA_FILE_SCOPE)

    private const val MSG = "Conflict applicable candidates of member and extension"

    @JvmField
    val ISSUE =
      Issue.create(
        id = "MemberExtensionConflict",
        briefDescription = MSG,
        explanation =
          """
            When both member and extension declarations are applicable, the resolution takes the member. \
            This also implies that, if an extension existed first, but then a member is added later, \
            the same call-site may end up with different call resolutions depending on target environment. \
            This results in a potential runtime exception if the generated binary (library or app) targets \
            earlier environment (i.e., without the new member, but only extension). More concrete example \
            is found at: https://issuetracker.google.com/issues/350432371
          """,
        implementation = IMPLEMENTATION,
        enabledByDefault = false,
      )
  }

  override fun getApplicableUastTypes(): List<Class<out UElement>> =
    listOf(
      UImportStatement::class.java,
      UCallExpression::class.java,
      USimpleNameReferenceExpression::class.java,
    )

  private val explicitlyImportedExtensions = mutableSetOf<CallableId>()

  override fun afterCheckFile(context: Context) {
    explicitlyImportedExtensions.clear()
  }

  @OptIn(KaExperimentalApi::class)
  override fun createUastHandler(context: JavaContext): UElementHandler =
    object : UElementHandler() {
      override fun visitImportStatement(node: UImportStatement) {
        // Regard star import as implicit import
        if (node.isOnDemand) return

        val importDirective = node.sourcePsi as? KtImportDirective ?: return
        val importedReference = importDirective.importedReference ?: return
        val ktReference =
          importedReference.getQualifiedElementSelector() as? KtReferenceExpression ?: return
        analyze(ktReference) {
          if (!isK2()) return
          val symbols = ktReference.mainReference.resolveToSymbols()
          for (symbol in symbols) {
            if (symbol is KaCallableSymbol && symbol.isExtension) {
              symbol.callableId?.let { explicitlyImportedExtensions.add(it) }
            }
          }
        }
      }

      override fun visitCallExpression(node: UCallExpression) {
        // This conflict of member and extension only happens in Kotlin
        if (!isKotlin(node.lang)) return

        val sourcePsi = node.sourcePsi as? KtElement ?: return
        analyze(sourcePsi) {
          if (!isK2()) return
          checkKtElement(node, sourcePsi)
        }
      }

      override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression) {
        // This conflict of member and extension only happens in Kotlin
        if (!isKotlin(node.lang)) return

        val sourcePsi = node.sourcePsi as? KtElement ?: return
        analyze(sourcePsi) {
          if (!isK2()) return
          checkKtElement(node, sourcePsi)
        }
      }

      private fun KaSession.isK2(): Boolean {
        // Collecting multiple applicable candidates only work for K2 AA
        // Check KaSession name: KaFe10Session v.s. KaFirSession
        return this::class.simpleName == "KaFirSession"
      }

      private fun KaSession.checkKtElement(node: UElement, ktElement: KtElement) {
        val candidates =
          ktElement
            .resolveToCallCandidates()
            // Only applicable candidates
            .filterIsInstance<KaApplicableCallCandidateInfo>()
        // Early bail-out: no conflicts
        if (candidates.size <= 1) {
          return
        }
        val (extensions, members) = candidates.partition { it.hasExtensionReceiver() }
        // Another bail-out: no members
        if (members.isEmpty()) {
          return
        }
        // Member is chosen over extension.
        val filteredMember =
          members.singleOrNull { member ->
            // So, one of candidate members must be the "best" candidate.
            member.isInBestCandidates &&
              // Also, the member should belong to a certain containing class, not local.
              // We can indirectly check that by retrieving its callable id
              // (since the local will not have a callable id).
              member.callableId() != null
          }
        // Otherwise, extension (along with explicit import) is chosen. Hence, no conflict.
        if (filteredMember == null) {
          return
        }
        // Extensions from Kotlin stdlib are inevitable, so we'd like to skip them
        // *unless* users explicitly import them. Those are technically unused,
        // so this warning will bring their attention to either remove import
        // or introduce import alias if their intention was to use an extension.
        val filteredExtensions =
          extensions.filter { !it.isFromKotlinBuiltIns() || it.isExplicitlyImported() }
        // Yet another bail-out: no extensions
        if (filteredExtensions.isEmpty()) {
          return
        }
        // Just pick the first extension to report.
        reportConflict(node, filteredMember, filteredExtensions.first())
      }

      private fun KaApplicableCallCandidateInfo.partialSymbol(): KaPartiallyAppliedSymbol<*, *>? {
        return (candidate as? KaCallableMemberCall<*, *>)?.partiallyAppliedSymbol
      }

      private fun KaApplicableCallCandidateInfo.callableId(): CallableId? {
        return partialSymbol()?.signature?.callableId
      }

      private fun KaApplicableCallCandidateInfo.hasExtensionReceiver(): Boolean {
        return partialSymbol()?.extensionReceiver != null
      }

      private fun KaApplicableCallCandidateInfo.isExplicitlyImported(): Boolean {
        val callableId = callableId() ?: return false
        return callableId in explicitlyImportedExtensions
      }

      private fun KaApplicableCallCandidateInfo.isFromKotlinBuiltIns(): Boolean {
        val callableId = callableId() ?: return false
        return callableId.packageName.startsWith(StandardNames.BUILT_INS_PACKAGE_FQ_NAME)
      }

      private fun KaSession.reportConflict(
        node: UElement,
        member: KaCallCandidateInfo,
        extension: KaCallCandidateInfo,
      ) {
        val mem = member.candidate.symbol()
        val ext = extension.candidate.symbol() as? KaCallableSymbol ?: return
        val message = buildString {
          append("`${mem.name?.asString() ?: "<unnamed>"}`")
          append(" is defined both as a member in class ")
          val classSymbol = mem.containingDeclaration as? KaClassSymbol
          append("`${classSymbol?.classId?.asFqNameString() ?: "<unknown>"}`")
          append(" and an extension in package ")
          append("`${ext.callableId?.packageName?.asString() ?: "<unknown>"}`. ")
          append("The defined behavior for this is to use the member, ")
          append("but since the extension is explicitly imported into this file, ")
          append("there's a chance that this was not expected. ")
          append("(One common way this happens is for members to be added to a class ")
          append("after code was already written to use an extension).")
        }
        context.report(ISSUE, node, context.getLocation(node), message)
      }

      private fun KaCall.symbol(): KaSymbol =
        when (this) {
          is KaCompoundVariableAccessCall ->
            compoundOperation.operationPartiallyAppliedSymbol.symbol
          is KaCompoundArrayAccessCall -> compoundOperation.operationPartiallyAppliedSymbol.symbol
          is KaCallableMemberCall<*, *> -> symbol
        }
    }
}
