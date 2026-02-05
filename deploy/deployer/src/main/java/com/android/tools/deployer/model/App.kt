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
package com.android.tools.deployer.model

import com.android.ide.common.build.GenericBuiltArtifactsLoader.loadFromFile
import com.android.ide.common.build.GenericFilterConfiguration
import com.android.tools.deployer.model.component.ApkParserException
import com.android.utils.ILogger
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

class App(
  appIdInput: String?,
  private val apks: List<DeploymentStrategy<Apk>>,
  val baselineProfiles: List<BaselineProfile> = emptyList(),

  // TDOO: appId needs to be treated as non-null in Kotlin code.
  // Unfortunately we still have LOTS of test creating null appId.
  // We will need to fix those test later.
  val appId: String = appIdInput ?: "<empty>",
) {

  val isDebuggable: Boolean
    get() = apks.stream().anyMatch { it.artifact.debuggable }

  fun getBaselineProfile(api: Int): List<Path> {
    for (bp in baselineProfiles) {
      if (bp.minApi <= api && api <= bp.maxApi) {
        return bp.paths
      }
    }
    return emptyList()
  }

  @Deprecated("This method is to be removed. Caller should be retrieving the list of APKS by a strategy")
  fun getApks() = apks.map(DeploymentStrategy<Apk>::artifact)

  val allStrategies: List<DeploymentStrategy<Apk>>
    get() = apks

  /** Retrieve a list of APKs need for the package manager. */
  fun getApksForPackageManager(abi: String): List<Apk> {
    return apks.filter { it.shouldSendToPackageManager() && it.shouldTargetAbi(abi) }.map(DeploymentStrategy<Apk>::artifact)
  }

  companion object {

    @JvmStatic
    fun fromApks(appId: String?, apks: List<Apk>): App {
      return App(appId, apks.map { PackageManagerApk(it) })
    }

    @JvmStatic
    fun fromApk(appId: String?, apk: Apk): App {
      return App(appId, listOf(PackageManagerApk(apk)))
    }

    @Throws(ApkParserException::class)
    fun fromString(appId: String?, apkPath: String): App {
      return App(appId, listOf(PackageManagerApk(ApkParser.parse(apkPath))))
    }

    @JvmStatic
    @Throws(ApkParserException::class)
    fun fromPaths(appId: String?, paths: List<Path>, baselineProfiles: List<BaselineProfile>): App {
      return App(appId, convert(paths).map { PackageManagerApk(it) }, baselineProfiles)
    }

    @JvmStatic
    @Throws(ApkParserException::class)
    fun fromPaths(appId: String?, paths: List<Path>): App {
      val apks: MutableList<Path> = ArrayList()
      val baselineProfiles: MutableList<BaselineProfile> = ArrayList()
      for (path in paths) {
        if (path.toString().endsWith(".apk")) {
          apks.add(path)
          continue
        }
        if (path.toString().endsWith(".dm")) {
          if (baselineProfiles.isEmpty()) {
            baselineProfiles.add(BaselineProfile(Int.MIN_VALUE, Int.MAX_VALUE, ArrayList()))
          }
          baselineProfiles[0].paths.add(path)
          continue
        }
        throw IllegalStateException("Unknown path type (neither apk nor dm):$path")
      }
      return fromPaths(appId, apks, baselineProfiles)
    }

    @Throws(ApkParserException::class)
    fun fromPath(appId: String, path: Path): App {
      return fromPaths(appId, listOf(path))
    }

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

    @Throws(ApkParserException::class)
    private fun convert(paths: List<Path>): List<Apk> {
      val apks: MutableList<Apk> = ArrayList()
      for (path in paths) {
        apks.add(ApkParser.parse(path.toAbsolutePath().toString()))
      }
      return apks
    }
  }
}
