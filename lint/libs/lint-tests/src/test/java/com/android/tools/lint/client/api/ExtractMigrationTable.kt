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
import java.util.jar.JarInputStream
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles.JVM_CONFIG_FILES
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtImportList
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtVisitorVoid

/**
 * Code to extract analysis API compatibility typealiases, intended to be used in the jar bytecode
 * migration
 */
fun main() {
  val typeMap = mutableMapOf<String, String>()
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
        extract(env, fileName, text, typeMap)
      }
      entry = jis.nextJarEntry
    }
  }

  val entries = typeMap.entries.sortedBy { it.key }
  for ((key, value) in entries) {
    println(
      "      \"${getInternalName(key).replace("$", "\\$")}\" ->\n" +
        "       \"${getInternalName(value).replace("$", "\\$")}\""
    )
  }

  parentDisposable.dispose()
}

private fun extract(
  env: KotlinCoreEnvironment,
  fileName: String,
  text: String,
  typeMap: MutableMap<String, String>,
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
    }
  )
}
