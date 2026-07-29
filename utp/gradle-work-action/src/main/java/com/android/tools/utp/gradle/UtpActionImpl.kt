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

package com.android.tools.utp.gradle

import com.android.tools.utp.gradle.api.RunUtpWorkParameters
import com.android.tools.utp.gradle.api.UtpAction
import org.gradle.api.provider.ProviderFactory

class UtpActionImpl : UtpAction {

  override fun run(parameters: RunUtpWorkParameters, provider: ProviderFactory) {
    val utpRunConfigs = parameters.utpRunConfigs.get()

    AndroidTestEngineRunner()
      .execute(
        parameters,
        utpRunConfigs.map { it.utpResultProtoOutputFile.get().asFile },
        parameters.mergedUtpResultProtoOutputFile.get().asFile,
        parameters.testResultExitCodeFile.get().asFile,
      )
  }
}
