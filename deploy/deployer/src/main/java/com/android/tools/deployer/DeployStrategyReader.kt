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
package com.android.tools.deployer

import com.android.ide.common.build.GenericBuiltArtifactsLoader.loadFromFile
import com.android.ide.common.build.GenericFilterConfiguration
import com.android.tools.deployer.model.ApkParser
import com.android.tools.deployer.model.App
import com.android.tools.deployer.model.PackageManagerApk
import com.android.tools.deployer.model.component.ApkParserException
import com.android.utils.ILogger
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

object DeployStrategyReader {
  @JvmStatic
  @Throws(ApkParserException::class)
  fun fromStrategy(path: Path, logger: ILogger): App {
    val artifacts = checkNotNull(loadFromFile(path.toFile(), logger))
    val applicationId = artifacts.applicationId
    val strategies =
      artifacts.elements.map { artifact ->
        val apkPath: Path = Paths.get(artifact.outputFile)
        val apk = ApkParser.parse(apkPath.toAbsolutePath().toString())
        val filters =
          artifact.filters
            .stream()
            .collect(Collectors.toMap(GenericFilterConfiguration::filterType, GenericFilterConfiguration::identifier))
        PackageManagerApk(apk, filters)
      }
    return App(applicationId, strategies)
  }
}
