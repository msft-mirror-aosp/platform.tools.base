/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.tools.render.framework

import com.android.tools.rendering.RenderService
import com.intellij.ide.plugins.PluginInitContextFactory
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.PluginSetBuilder
import com.intellij.ide.plugins.PluginsDiscoveryResult
import com.intellij.ide.plugins.UnambiguousPluginSet
import com.intellij.ide.plugins.tryBuild
import com.intellij.mock.MockApplication
import com.intellij.mock.MockProject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.AsyncExecutionService
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ReadAndWriteScope
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.ReadResult
import com.intellij.openapi.application.ReadWriteActionSupport
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.util.concurrency.AppExecutorUtil

/** [IJFramework] implementation for the standalone rendering. */
object IJFramework : Disposable {
  private val application = MockApplication(this)

  init {
    ApplicationManager.setApplication(application, this)
    PluginManagerCore.setPluginSet(
      PluginSetBuilder(
          PluginInitContextFactory.getInstance().createActualContext(),
          UnambiguousPluginSet.tryBuild(emptyList())!!,
          PluginsDiscoveryResult.build(emptyList()),
        )
        .createPluginSetWithEnabledModulesMap()
    )
    application.registerService(AsyncExecutionService::class.java, StubAsyncExecutionService(), this)
    application.registerService(
      ReadWriteActionSupport::class.java,
      object : ReadWriteActionSupport {
        override fun committedDocumentsConstraint(project: Project): ReadConstraint = ReadConstraint.withDocumentsCommitted(project)

        override fun <X, E : Throwable> computeCancellable(action: ThrowableComputable<X, E>): X = ReadAction.compute(action)

        override suspend fun <X> executeReadAction(
          constraints: List<ReadConstraint>,
          undispatched: Boolean,
          blocking: Boolean,
          action: () -> X,
        ): X {
          throw UnsupportedOperationException()
        }

        override suspend fun <X> executeReadAndWriteAction(
          constraints: Array<out ReadConstraint>,
          runWriteActionOnEdt: Boolean,
          undispatched: Boolean,
          action: ReadAndWriteScope.() -> ReadResult<X>,
        ): X {
          throw UnsupportedOperationException()
        }

        override fun smartModeConstraint(project: Project): ReadConstraint = ReadConstraint.inSmartMode(project)

        override suspend fun <T> runWriteAction(action: () -> T): T {
          throw UnsupportedOperationException()
        }
      },
      this,
    )
  }

  fun createProject(): Project {
    return MockProject(null, application)
  }

  override fun dispose() {
    RenderService.shutdownRenderExecutor()
    AppExecutorUtil.shutdownApplicationScheduledExecutorService()
  }
}
