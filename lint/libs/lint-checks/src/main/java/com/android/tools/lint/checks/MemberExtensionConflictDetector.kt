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
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.types.KaTypeNullability
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
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
    listOf(UCallExpression::class.java, USimpleNameReferenceExpression::class.java)

  @OptIn(KaExperimentalApi::class)
  override fun createUastHandler(context: JavaContext): UElementHandler =
    object : UElementHandler() {
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
        // So, one of candidate members must be the "best" candidate.
        val filteredMember = members.singleOrNull { it.isInBestCandidates }
        // Otherwise, extension (along with explicit import) is chosen. Hence, no conflict.
        if (filteredMember == null) {
          return
        }
        val filteredExtensions =
          extensions.filterNot { ext ->
            // E.g., kotlin.Any?.toString(), kotlin.text.StringBuilder.append(kotlin.Any?)
            ext.isFromKotlinBuiltIns() &&
              (ext.hasNullableExtensionReceiver() || ext.hasNullableValueParameters())
          }
        // Yet another bail-out: no extensions
        if (filteredExtensions.isEmpty()) {
          return
        }
        reportConflict(this, node, listOf(filteredMember), filteredExtensions)
      }

      private fun KaApplicableCallCandidateInfo.partialSymbol(): KaPartiallyAppliedSymbol<*, *>? {
        return (candidate as? KaCallableMemberCall<*, *>)?.partiallyAppliedSymbol
      }

      private fun KaApplicableCallCandidateInfo.hasExtensionReceiver(): Boolean {
        return partialSymbol()?.extensionReceiver != null
      }

      private fun KaApplicableCallCandidateInfo.hasNullableExtensionReceiver(): Boolean {
        return partialSymbol()?.signature?.receiverType?.nullability == KaTypeNullability.NULLABLE
      }

      private fun KaApplicableCallCandidateInfo.hasNullableValueParameters(): Boolean {
        val valueParameters =
          (partialSymbol()?.symbol as? KaFunctionSymbol)?.valueParameters ?: return false
        return valueParameters.all { it.returnType.nullability == KaTypeNullability.NULLABLE }
      }

      private fun KaApplicableCallCandidateInfo.isFromKotlinBuiltIns(): Boolean {
        return partialSymbol()
          ?.signature
          ?.callableId
          ?.packageName
          ?.startsWith(StandardNames.BUILT_INS_PACKAGE_FQ_NAME) == true
      }

      private fun reportConflict(
        session: KaSession,
        node: UElement,
        members: List<KaCallCandidateInfo>,
        extensions: List<KaCallCandidateInfo>,
      ) {
        val message = buildString {
          append(MSG)
          append(": members ")
          members.joinTo(this, prefix = "{", postfix = "}") { info ->
            info.candidate.symbols().joinToString { symbol ->
              with(session) {
                (symbol as? KaDeclarationSymbol)?.render() ?: symbol.psi?.toString() ?: ""
              }
            }
          }
          append(", extensions ")
          extensions.joinTo(this, prefix = "{", postfix = "}") { info ->
            info.candidate.symbols().joinToString { symbol ->
              with(session) {
                (symbol as? KaDeclarationSymbol)?.render() ?: symbol.psi?.toString() ?: ""
              }
            }
          }
        }
        context.report(ISSUE, node, context.getLocation(node), message)
      }

      private fun KaCall.symbols(): List<KaSymbol> =
        when (this) {
          is KaCompoundVariableAccessCall ->
            listOfNotNull(
              variablePartiallyAppliedSymbol.symbol,
              compoundOperation.operationPartiallyAppliedSymbol.symbol,
            )
          is KaCompoundArrayAccessCall ->
            listOfNotNull(
              getPartiallyAppliedSymbol.symbol,
              setPartiallyAppliedSymbol.symbol,
              compoundOperation.operationPartiallyAppliedSymbol.symbol,
            )
          is KaCallableMemberCall<*, *> -> listOf(symbol)
        }
    }
}
