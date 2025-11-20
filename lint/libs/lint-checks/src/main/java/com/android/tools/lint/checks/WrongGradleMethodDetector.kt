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

import com.android.tools.lint.client.api.LintClient
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.ConstantEvaluator
import com.android.tools.lint.detector.api.DefaultPosition
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.GradleContext
import com.android.tools.lint.detector.api.GradleScanner
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.LintFix
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.LocationType
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.name
import org.jetbrains.kotlin.analysis.api.symbols.receiverType
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.symbol
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.psiUtil.getCallNameExpression
import org.jetbrains.uast.UCallExpression

class WrongGradleMethodDetector : Detector(), GradleScanner {
  override fun checkMethodCall(
    context: GradleContext,
    statement: String,
    parent: String?,
    parentParent: String?,
    namedArguments: Map<String, String>,
    unnamedArguments: List<String>,
    cookie: Any,
  ) {
    if (cookie is UCallExpression) {
      val call = cookie.sourcePsi
      if (call is KtCallElement && call.valueArguments.lastOrNull() is KtLambdaArgument) {
        if (LintClient.isStudio) {
          // KTS build script environment should be properly set up
          checkIde(context, statement, call)
        } else {
          // Missing gradle dependencies and other setup, so do simpler (and more
          // limited) pattern matching for a couple of specific known problems;
          // firebase app distribution without an import, and dependencies declarations
          // inside build types and product flavors
          checkCli(context, statement, parent, parentParent, call, cookie)
        }
      }
    } else {
      // In Groovy files we don't attempt to do any type matching, we just
      // look for the same patterns that we do above (CLI only) for KTS
      checkCli(context, statement, parent, parentParent, null, cookie)
    }
  }

  @Suppress("KotlincFE10")
  @OptIn(KaExperimentalApi::class)
  private fun checkIde(context: GradleContext, statement: String, ktCall: KtCallElement) {
    analyze(ktCall) {
      val symbol = ktCall.resolveToCall()?.singleFunctionCallOrNull()?.symbol ?: return
      val parentType = getReceiverType(symbol) as? KaClassType ?: return
      val thisType = getTypeReceiverType(ktCall) ?: return
      if (!thisType.isSubtypeOf(parentType)) {
        // Unwrap flexible types to their lower bound to get the underlying class name.
        val thisTypeClassifier = thisType.lowerBoundIfFlexible() as? KaClassType ?: return
        val thisTypeString = thisTypeClassifier.classId.asSingleFqName().asString()

        if (statement == FIREBASE_APP_DISTRIBUTION_NAME) {
          reportFirebaseAppDistributionMistake(context, ktCall)
          return
        }

        if (!thisTypeString.startsWith("com.android.build.api.dsl.")) {
          // Limit to our own APIs; see b/405442664 for difficulties in general.
          return
        }

        val imports = findImports(symbol) ?: emptyList()
        val fix =
          if (imports.isNotEmpty()) {
            createFix(context, imports.first())
          } else {
            null
          }

        val inProductFlavor = thisTypeString == "com.android.build.api.dsl.ApplicationProductFlavor"
        val inBuildType = thisTypeString == "com.android.build.api.dsl.ApplicationBuildType"
        val simpleParentType = parentType.classId.relativeClassName.asString()

        val message =
          getErrorMessage(
            simpleParentType,
            thisTypeString,
            if (statement == DEPENDENCIES_BLOCK_NAME) ktCall.parentLambda()?.getContainerName()
            else null,
            imports.firstOrNull(),
            inBuildType,
            inProductFlavor,
          )

        context.report(
          ISSUE,
          ktCall,
          context.getLocation(ktCall.getCallNameExpression()),
          message,
          fix,
        )
      }
    }
  }

  /** Creates the error message. */
  private fun getErrorMessage(
    parentType: String,
    thisType: String,
    dependenciesParent: String?,
    import: String?,
    inBuildType: Boolean,
    inProductFlavor: Boolean,
  ): String {
    return buildString {
      append("Suspicious receiver type; ")
      append("this does not apply to ")
      if (inBuildType) {
        append("build types")
      } else if (inProductFlavor) {
        append("product flavors")
      } else {
        append("the current receiver of type `")
        append(thisType.substringAfterLast("."))
        append("`")
      }
      append(". ")
      append("This will apply to a receiver of type `$parentType`, ")
      append("found in one of the enclosing lambdas. ")
      append("Make sure it's declared in the right place in the file")
      if (import != null) {
        append(", or you may have to import `$import`.")
      } else {
        append(".")
      }
      if (dependenciesParent != null && (inBuildType || inProductFlavor)) {
        val container = if (inProductFlavor) "product flavor" else "build type"
        append(" If you wanted a $container specific dependency, ")
        append("use `${dependenciesParent}Implementation` ")
        append("rather than `implementation` in the top level `dependencies` block.")
      }
    }
  }

  private fun checkCli(
    context: GradleContext,
    statement: String,
    parent: String?,
    parentParent: String?,
    ktsCall: KtCallElement?,
    cookie: Any,
  ) {
    // From outside the IDE we don't have a correct classpath and KTS setup,
    // so resolving into the Gradle APIs doesn't work -- which means we cannot
    // accurately compare inheritance types. Therefore, instead this just
    // hardcodes some *known* cases that are wrong which we can check for
    // without resolve results.
    if (statement == FIREBASE_APP_DISTRIBUTION_NAME) {
      if (
        ktsCall != null &&
          context.getContents()?.contains(FIREBASE_APP_DISTRIBUTION_PKG_PREFIX) == false
      ) {
        // The firebase issue only applies to KTS, not Groovy
        reportFirebaseAppDistributionMistake(context, ktsCall)
      }
    } else if (statement == DEPENDENCIES_BLOCK_NAME) {
      val inBuildType = parentParent == "buildTypes"
      val inProductFlavor = parentParent == "productFlavors"
      if (inBuildType || inProductFlavor) {
        val message =
          getErrorMessage(
            "Project",
            "org.gradle.api.Project",
            ktsCall?.parentLambda()?.getContainerName() ?: parent,
            null,
            inBuildType,
            inProductFlavor,
          )

        if (ktsCall != null) {
          context.report(
            ISSUE,
            ktsCall,
            context.getLocation(ktsCall.getCallNameExpression()),
            message,
          )
        } else {
          context.report(ISSUE, cookie, context.getLocation(cookie, LocationType.NAME), message)
        }
      }
    }
  }

  private fun reportFirebaseAppDistributionMistake(
    context: GradleContext,
    call: KtCallElement,
    location: Location = context.getLocation(call.getCallNameExpression()),
  ) {
    reportFirebaseAppDistributionMistake(context, call as Any, location)
  }

  private fun reportFirebaseAppDistributionMistake(
    context: GradleContext,
    cookie: Any,
    location: Location,
  ) {
    context.report(
      ISSUE,
      cookie,
      location,
      FIREBASE_APP_DISTRIBUTION_MESSAGE,
      fix = createFix(context, FIREBASE_APP_DISTRIBUTION_FQN),
    )
  }

  private fun getReceiverType(symbol: KaFunctionSymbol): KaType? {
    return symbol.receiverParameter?.returnType
  }

  private fun KtLambdaExpression.getContainerName(): String? {
    val call = parentOfType<KtCallExpression>() ?: return null
    val callName = call.calleeExpression?.text ?: return null
    when (callName) {
      "getByName",
      "create",
      "maybeCreate",
      "named",
      "register" -> {
        val arg = call.valueArguments.firstOrNull()?.getArgumentExpression() ?: return null
        return ConstantEvaluator.evaluateString(null, arg, false)
      }
    }
    return callName
  }

  private fun KtCallElement.parentLambda(): KtLambdaExpression? {
    return parentOfType<KtLambdaExpression>()
  }

  private fun KaSession.getTypeReceiverType(call: KtCallElement): KaType? {
    val parentLambda = call.parentLambda() ?: return null
    val type = parentLambda.expressionType as? KaFunctionType
    return type?.receiverType
  }

  private fun KaSession.findImports(symbol: KaFunctionSymbol): List<String>? {
    val methodName = symbol.name ?: return null
    val typeSymbol =
      (symbol.valueParameters.firstOrNull()?.returnType as? KaClassType)
        ?.typeArguments
        ?.firstOrNull()
        ?.type
        ?.symbol
    if (typeSymbol is KaClassSymbol) {
      // TODO: Isn't there a better way to get the package name??
      val fqn = typeSymbol.classId?.asSingleFqName()?.asString() ?: return null
      val pkgName = fqn.substringBeforeLast('.')
      if (pkgName == "org.gradle.kotlin.dsl") {
        return null
      }
      return findTopLevelCallables(FqName(pkgName), methodName)
        .filter { it is KaFunctionSymbol }
        .mapNotNull { it.callableId?.asSingleFqName()?.asString() }
        .toSet()
        .toList()
        .sorted()
    }
    return null
  }

  private fun createFix(context: GradleContext, import: String): LintFix {
    return fix()
      .name("Import $import")
      .replace()
      .beginning()
      .with("import $import\n")
      .range(Location.create(context.file, DefaultPosition(-1, -1, 0), DefaultPosition(-1, -1, 0)))
      .build()
  }

  companion object {
    private const val FIREBASE_APP_DISTRIBUTION_NAME = "firebaseAppDistribution"
    private const val FIREBASE_APP_DISTRIBUTION_PKG_PREFIX =
      "com.google.firebase.appdistribution.gradle."
    private const val FIREBASE_APP_DISTRIBUTION_FQN =
      "$FIREBASE_APP_DISTRIBUTION_PKG_PREFIX$FIREBASE_APP_DISTRIBUTION_NAME"
    private const val FIREBASE_APP_DISTRIBUTION_MESSAGE =
      "This does not resolve to the right method; you need to explicitly " +
        "add `import $FIREBASE_APP_DISTRIBUTION_FQN` to this file!"
    private const val DEPENDENCIES_BLOCK_NAME = "dependencies"

    @JvmField
    val ISSUE =
      Issue.create(
        id = "WrongGradleMethod",
        briefDescription = "Wrong Gradle method invoked",
        explanation =
          """
          This lint check looks for suspicious Gradle DSL calls.

          One common example is attempting to create product flavor or build type specific dependencies by \
          placing the `dependencies` block inside a product flavor or build type.

          Another one is KTS specific. \
          When migrating from Groovy to KTS, be extra careful with some calls into plugin DSL methods; in some \
          cases, you might have to insert an explicit `import` statement, even if the code compiles without it; \
          instead you are silently calling a generated method.

          For example, with the Firebase App Distribution plugin, you *cannot* just convert this `build.gradle` snippet:
          ```groovy
          buildTypes {
              release {
                  firebaseAppDistribution {
                      artifactType="APK"
                      releaseNotesFile="/path/to/releasenotes.txt"
                      testers="ali@example.com, bri@example.com, cal@example.com"
                  }
              }
          }
          ```
          to this KTS:
          ```kotlin
          buildTypes {
              getByName("release") {
                  firebaseAppDistribution {
                      artifactType = "APK"
                      releaseNotesFile = "/path/to/releasenotes.txt"
                      testers = "ali@example.com, bri@example.com, cal@example.com"
                  }
              }
          }
          ```
          You have to *also* add this import at the top of the file:
          ```kotlin
          import com.google.firebase.appdistribution.gradle.firebaseAppDistribution
          ```

          If you get this error on other DSL constructs inside build types or product flavors, \
          check the plugin documentation.
          """,
        category = Category.CORRECTNESS,
        priority = 2,
        severity = Severity.ERROR,
        implementation = Implementation(WrongGradleMethodDetector::class.java, Scope.GRADLE_SCOPE),
      )
  }
}
