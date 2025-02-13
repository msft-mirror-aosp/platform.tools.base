/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tools.lint.client.api

import com.android.SdkConstants.DOT_KT
import com.android.testutils.TestUtils
import com.android.tools.lint.client.api.LintFixPerformer.Companion.skipAnnotation
import com.android.tools.lint.detector.api.ClassContext.Companion.getInternalName
import com.intellij.openapi.util.Disposer
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale.getDefault
import java.util.jar.JarInputStream
import java.util.regex.Pattern
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles.JVM_CONFIG_FILES
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtImportList
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.jetbrains.kotlin.psi.ValueArgument

/**
 * Code to extract analysis API compatibility typealiases, intended to be used in the jar bytecode
 * migration
 */
fun main() {
  val typeMap = mutableMapOf<String, String>()
  val apiMap = mutableMapOf<String, String>()
  // TODO: hard to find the right snapshot of sources. Maybe using git diff on source/api?
  val currentSources =
    File(
      TestUtils.getWorkspaceRoot().toFile(),
      "prebuilts/tools/common/lint-psi/kotlin-compiler/kotlin-compiler-sources.jar",
    )

  val parentDisposable = Disposer.newDisposable("ExtractMigrationTable")
  val env =
    KotlinCoreEnvironment.createForProduction(
      parentDisposable,
      CompilerConfiguration(),
      JVM_CONFIG_FILES,
    )

  JarInputStream(ByteArrayInputStream(currentSources.readBytes())).use { jis ->
    var entry = jis.nextJarEntry
    while (entry != null) {
      val fileName = entry.name
      if (
        fileName.endsWith(DOT_KT) &&
          !entry.isDirectory &&
          fileName.startsWith("org/jetbrains/kotlin/analysis/api/")
      ) {
        val text = String(jis.readAllBytes(), Charsets.UTF_8)
        extract(env, fileName, text, typeMap, apiMap)
      }
      entry = jis.nextJarEntry
    }
  }

  println("=".repeat(6) + " type mapping " + "=".repeat(6))
  println()
  printMap(typeMap) { typeName -> "\"" + getInternalName(typeName).replace("$", "\\$") + "\"" }
  println()
  println("=".repeat(6) + " API mapping " + "=".repeat(6))
  println()
  printMap(apiMap) { api ->
    if ("." in api) {
      val sig = api.substringAfter(" ")
      val names = api.substringBefore(" ")
      val clsName = names.substringBeforeLast(".")
      val mtdName = names.substringAfterLast(".")
      "// " +
        getInternalName(clsName).replace("$", "\\$") +
        "\n      \"" +
        mtdName +
        " " +
        getInternalName(sig) +
        "\""
    } else {
      "\"$api\""
    }
  }

  parentDisposable.dispose()
}

private fun printMap(m: Map<String, String>, formatter: (String) -> String) {
  val entries = m.entries.sortedBy { it.key }
  for ((key, value) in entries) {
    println("      ${formatter(key)} ->\n" + "       ${formatter(value)}")
  }
}

private fun extract(
  env: KotlinCoreEnvironment,
  fileName: String,
  text: String,
  typeMap: MutableMap<String, String>,
  apiMap: MutableMap<String, String>,
) {
  val factory = KtPsiFactory(env.project)
  val ktFile = factory.createFile(fileName, text)
  ktFile.acceptChildren(
    object : KtVisitorVoid() {
      var pkg = ""
      val imports = mutableMapOf<String, String>()

      override fun visitPackageDirective(directive: KtPackageDirective) {
        pkg = directive.qualifiedName
      }

      override fun visitImportList(importList: KtImportList) {
        importList.acceptChildren(this)
      }

      override fun visitImportDirective(importDirective: KtImportDirective) {
        val fqName = importDirective.importedFqName
        val name = fqName?.shortName()?.identifier
        if (name != null) {
          imports[name] = fqName.asString()
        }
      }

      override fun visitTypeAlias(typeAlias: KtTypeAlias) {
        val name = typeAlias.name?.substringBefore("<") ?: return
        // Known cases to skip (not used for backwards compatibility,
        // but for other type reuse within AA)
        when (name) {
          "KaScopeNameFilter" -> return
        }
        var alias = typeAlias.getTypeReference()?.text?.substringBefore("<") ?: return
        if (alias.startsWith("@")) {
          val end = skipAnnotation(alias, 0)
          if (end > 0) {
            alias = alias.substring(end).trim()
          }
        }
        val fqn =
          if ("." in alias) {
            alias
          } else {
            imports[alias] ?: "$pkg.$alias"
          }

        typeMap["$pkg.$name"] = fqn
      }

      var cls = ""

      override fun visitClass(klass: KtClass) {
        klass.name?.let { cls = it }
        klass.acceptChildren(this)
      }

      override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        classOrObject.name?.let { cls = it }
        classOrObject.acceptChildren(this)
      }

      override fun visitClassBody(classBody: KtClassBody) {
        classBody.acceptChildren(this)
      }

      private val REPLACED_PATTERN = Pattern.compile("""ReplaceWith\("(.+)"\)""", Pattern.DOTALL)

      override fun visitNamedFunction(function: KtNamedFunction) {
        val deprecated = getDeprecatedAnnotation(function) ?: return
        val attr = getReplaceWith(deprecated) ?: return
        val name = function.name ?: return
        val attrValue = attr.getArgumentExpression()?.text ?: return
        val matcher = REPLACED_PATTERN.matcher(attrValue)
        if (matcher.find()) {
          val replaced = matcher.group(1)
          mapApi(name + " " + computeSignature(function), replaced)
        }
      }

      override fun visitProperty(property: KtProperty) {
        val deprecated = getDeprecatedAnnotation(property) ?: return
        val attr = getReplaceWith(deprecated) ?: return
        val name = property.name ?: return
        val attrValue = attr.getArgumentExpression()?.text ?: return
        val matcher = REPLACED_PATTERN.matcher(attrValue)
        if (matcher.find()) {
          val replaced = matcher.group(1)
          mapApi(toPropertyGetterName(name) + " " + computeSignature(property), replaced)
        }
      }

      private fun mapApi(name: String, replaced: String) {
        if (" in " in replaced || " as " in replaced || " as? " in replaced) {
          // E.g., ReplaceWith("classId in annotations")
          // E.g., ReplaceWith("this.getSymbol() as? S")
          // TODO: chain (of property access, followed by contains call or type cast)
          println("MISSING: $pkg.$cls#$name -> $replaced")
        } else if ("[" in replaced && "]" in replaced) {
          // E.g. ReplaceWith("annotations[classId]")
          // TODO: chain (of property access and array access)
          println("MISSING: $pkg.$cls#$name -> $replaced")
        } else if ("." in replaced) {
          // E.g. ReplaceWith("types.commonSupertype"))
          // TODO: chain (of property accesses)
          println("MISSING: $pkg.$cls#$name -> $replaced")
        } else if ("(" in replaced && ")" in replaced) {
          // E.g., ReplaceWith("resolveToCall()") -> resolveToCall
          apiMap["$pkg.$cls.$name"] = replaced.substringBefore("(")
        } else {
          // E.g., ReplaceWith("expressionType") -> getExpressionType
          apiMap["$pkg.$cls.$name"] = toPropertyGetterName(replaced)
        }
      }

      private fun toPropertyGetterName(name: String): String {
        return if (name.startsWith("is") || name.startsWith("get")) {
          name
        } else {
          "get" +
            name.replaceFirstChar {
              if (it.isLowerCase()) it.titlecase(getDefault()) else it.toString()
            }
        }
      }

      private fun getDeprecatedAnnotation(annotated: KtAnnotated): KtAnnotationEntry? {
        // Finding @Deprecated(...)
        return annotated.annotationEntries.find { entry ->
          entry.typeReference?.text?.contains("Deprecated") == true
        }
      }

      private fun getReplaceWith(annotationEntry: KtAnnotationEntry): ValueArgument? {
        // Finding @Deprecated(..., replaceWith = ReplaceWith("..."), ...)
        return annotationEntry.valueArguments.find { arg ->
          arg.getArgumentName()?.asName?.identifier == "replaceWith" ||
            arg.getArgumentExpression()?.text?.startsWith("ReplaceWith(") == true
        }
      }

      private fun computeSignature(callable: KtCallableDeclaration): String {
        // TODO: JVM primitives, (nested) array, type parameter?
        return buildString {
          append("(")
          val rcvTxt = callable.receiverTypeReference?.getTypeText()
          val fqn =
            if (rcvTxt != null) {
              // Extension receiver (static call)
              imports[rcvTxt] ?: rcvTxt
            } else {
              // Dispatch receiver (virtual/interface call)
              "$pkg.$cls"
            }
          append("L${fqn};")
          // TODO: value parameters
          append(")")
          // TODO: return type
        }
      }
    }
  )
}
