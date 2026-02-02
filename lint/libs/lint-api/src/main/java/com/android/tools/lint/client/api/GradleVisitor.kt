/*
 * Copyright (C) 2018 The Android Open Source Project
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

import com.android.SdkConstants.DOT_GRADLE
import com.android.SdkConstants.DOT_GRADLE_KTS
import com.android.SdkConstants.DOT_KTS
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.GradleContext
import com.android.tools.lint.detector.api.GradleScanner
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Location
import java.io.File

/**
 * Visitor which can traverse a Gradle file and invoke the various methods on a [GradleScanner].
 *
 * This is only intended to be implemented by lint.
 */
open class GradleVisitor {

  /**
   * The [JavaContext] used by the visitor, if applicable. For example, if the build script is Kotlin Script then the [JavaContext] will
   * probably be available and can be used to check for suppression on a UElement from the build script.
   */
  internal open val javaContext: JavaContext?
    get() = null

  /**
   * Manually visiting the build script. Returns true if it has fully handled the file, otherwise returns true and some of the individual
   * DSL checks below are run.
   */
  open fun visitBuildScript(context: GradleContext, detectors: List<GradleScanner>) {
    // Empty implementation. This class is overridden in modules which have
    // access to Groovy (e.g. the Groovy parser itself from Gradle and the
    // test infrastructure, the Gradle PSI model in the IDE, etc.
  }

  @Deprecated(message = "unused", replaceWith = ReplaceWith(expression = "cookie")) open fun getPropertyKeyCookie(cookie: Any): Any = cookie

  @Deprecated(message = "unused", replaceWith = ReplaceWith(expression = "cookie"))
  open fun getPropertyPairCookie(cookie: Any): Any = cookie

  open fun getStartOffset(context: GradleContext, cookie: Any): Int = -1

  open fun findElementByRange(context: GradleContext, cookie: Any, startOffset: Int, endOffset: Int): Any? = null

  open fun createLocation(context: GradleContext, cookie: Any): Location = error("Not supported")

  /**
   * During processing of this script we may discover references to other build scripts that are included; the lint infrastructure will call
   * this method after processing this script to also process these other files (unless you're in isolated mode, e.g. directly editing the
   * file in the editor)
   */
  open fun getIncludedScripts(): List<File> = includedScripts ?: emptyList()

  private var includedScripts: MutableList<File>? = null

  /** Invoked when we come across an `apply from $relative` build script reference. */
  protected fun addIncludedScript(context: Context, relative: String?) {
    relative ?: return
    if (relative.endsWith(DOT_GRADLE) || relative.endsWith(DOT_GRADLE_KTS)) {
      val parentFile = context.file.parentFile
      if (parentFile != null) {
        var includedFile = File(parentFile, relative)
        if (relative.startsWith("..") && !context.project.isGradleRootHolder) {
          // We'll encounter root files from all the including projects, but we
          // only want to report issues here once; pick a designated project
          // to report them from. This project is not it.
          return
        }
        // TODO(b/463283604): Better not to have this exemption
        val isK2 = System.getProperty("lint.use.fir.uast", "true").toBoolean()
        if (relative.endsWith(DOT_KTS) && isK2 && !includedFile.path.startsWith(context.project.dir.path)) {
          // We currently can't access kts files outside the project root from the
          // CLI setup when using K2
          return
        }
        if (!includedFile.isFile) {
          includedFile = File(relative)
          if (!includedFile.isFile) {
            return
          }
        }
        val scripts = includedScripts ?: mutableListOf<File>().also { includedScripts = it }
        scripts.add(includedFile)
      }
    }
  }
}
