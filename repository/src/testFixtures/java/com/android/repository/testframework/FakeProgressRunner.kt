/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.repository.testframework

import com.android.repository.api.ProgressRunner
import com.android.repository.api.ProgressRunner.ProgressRunnable
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** A basic [ProgressRunner] that uses a [FakeProgressIndicator]. */
class FakeProgressRunner(val coroutineScope: CoroutineScope = CoroutineScope(EmptyCoroutineContext)) : ProgressRunner {
  var progressIndicator: FakeProgressIndicator = FakeProgressIndicator()

  override fun runAsyncWithProgress(r: ProgressRunnable) {
    coroutineScope.launch { r.run(progressIndicator) }
  }

  override fun runSyncWithProgress(r: ProgressRunnable) {
    runBlocking { r.run(progressIndicator) }
  }
}
