/*
 * Copyright (C) 2026 The Android Open Source Project
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

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.AppUIExecutor
import com.intellij.openapi.application.AsyncExecutionService
import com.intellij.openapi.application.ExpirableExecutor
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.NonBlockingReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import org.jetbrains.concurrency.CancellablePromise

class StubAsyncExecutionService : AsyncExecutionService() {
  override fun createExecutor(executor: Executor): ExpirableExecutor = throw UnsupportedOperationException()

  override fun createUIExecutor(modalityState: ModalityState): AppUIExecutor = throw UnsupportedOperationException()

  override fun createWriteThreadExecutor(modalityState: ModalityState): AppUIExecutor = throw UnsupportedOperationException()

  override fun <T> buildNonBlockingReadAction(computation: Callable<out T>): NonBlockingReadAction<T> {
    return object : NonBlockingReadAction<T> {
      override fun inSmartMode(project: Project) = this

      override fun withDocumentsCommitted(project: Project) = this

      override fun expireWhen(expireCondition: BooleanSupplier) = this

      override fun wrapProgress(progressIndicator: ProgressIndicator) = this

      override fun expireWith(parentDisposable: Disposable) = this

      override fun finishOnUiThread(modality: ModalityState, uiThreadAction: Consumer<in T>) = this

      override fun coalesceBy(vararg equality: Any) = this

      override fun submit(backgroundThreadExecutor: Executor): CancellablePromise<T> = throw UnsupportedOperationException()

      override fun executeSynchronously(): T = computation.call()
    }
  }
}
