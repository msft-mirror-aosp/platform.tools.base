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

package com.android.tools.androidtest.testengine

import com.android.tools.androidtest.testengine.instrument.TestResult
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.logging.Level
import java.util.logging.Logger

/**
 * A collector that retrieves additional test outputs from a test device to a host machine.
 *
 * This collector is a port of the UTP AndroidAdditionalTestOutputPlugin.
 */
class AndroidAdditionalTestOutputCollector(
  private val adbController: AdbController,
  private val deviceSerial: String,
  private val additionalOutputDirectoryOnHost: File?,
  private val additionalOutputDirectoryOnDevice: String?,
  private val instrumentationTargetPackageId: String,
  private val testedApplicationId: String,
  private val testPackageId: String = "",
  private val useTestStorageService: Boolean,
  private val runAsPackageName: String? = null,
  private val logger: Logger = Logger.getLogger(AndroidAdditionalTestOutputCollector::class.java.name),
) {

  companion object {
    /** AndroidX Test Storage service's output directory on device. */
    const val TEST_STORAGE_SERVICE_OUTPUT_DIR = "/sdcard/googletest/test_outputfiles"

    /** AndroidX Test Storage service's internal output directory on device. */
    const val TEST_STORAGE_SERVICE_INTERNAL_OUTPUT_DIR = "/sdcard/googletest/internal_use/"

    const val ADDITIONAL_TEST_OUTPUT_MIN_API_LEVEL = 16

    const val BENCHMARK_TEST_METRICS_KEY = "android.studio.display.benchmark"
    const val BENCHMARK_V2_TEST_METRICS_KEY = "android.studio.v2display.benchmark"
    const val BENCHMARK_PATH_TEST_METRICS_KEY = "android.studio.v2display.benchmark.outputDirPath"
    const val BENCHMARK_V3_TEST_METRICS_KEY = "android.studio.v3display.benchmark"
    const val BENCHMARK_V3_PATH_TEST_METRICS_KEY = "android.studio.v3display.benchmark.outputDirPath"

    private val benchmarkPrefixRegex = "^benchmark:( )?".toRegex(RegexOption.MULTILINE)
    val benchmarkUrlRegex = Regex(pattern = """(\[(?<title>[^]]*)])?\((?<link>(?<protocol>(file|uri|http|https)://)(?<path>[^)]*))\)""")
    private const val LINK_GROUP = "link"
    private const val BENCHMARK_TRACE_FILE_PREFIX = "file://"
    private const val BENCHMARK_V3_TRACE_FILE_PREFIX = "uri://"
  }

  /** Prepares directories on host and device before test execution. */
  fun prepare() {
    val deviceDir = getEffectiveAdditionalOutputDirectoryOnDevice()
    logger.info(
      "Preparing additional test output collector. hostDir=$additionalOutputDirectoryOnHost, deviceDir=$additionalOutputDirectoryOnDevice (effective: $deviceDir)"
    )
    createEmptyDirectoryOnHost()

    if (!deviceDir.isNullOrBlank()) {
      adbController.runAdbShellCommand(deviceSerial, listOf("rm", "-rf", deviceDir))
      adbController.runAdbShellCommand(deviceSerial, listOf("mkdir", "-p", deviceDir))
    }

    if (useTestStorageService) {
      adbController.runAdbShellCommand(deviceSerial, listOf("rm", "-rf", TEST_STORAGE_SERVICE_OUTPUT_DIR))
      adbController.runAdbShellCommand(deviceSerial, listOf("mkdir", "-p", TEST_STORAGE_SERVICE_OUTPUT_DIR))

      val apiLevel = getApiLevel()
      if (apiLevel >= 30 && isTestStorageServiceInstalled()) {
        // Grant MANAGE_EXTERNAL_STORAGE permission to androidx.test.services so that it
        // can write test artifacts in external storage.
        adbController.runAdbShellCommand(
          deviceSerial,
          listOf("appops", "set", "androidx.test.services", "MANAGE_EXTERNAL_STORAGE", "allow"),
        )
      }
    }
  }

  /**
   * Pulls a single file from the device to the host.
   *
   * If the file is located in a package-private directory (starting with "/data/"), it cannot be pulled directly by `adb pull` on
   * non-rooted devices. In this case, we use `run-as <package_name>` to copy the file to a world-readable temporary directory in
   * "/data/local/tmp/" first, and then pull it from there.
   *
   * This is a proven pattern used in other UTP plugins (like the Coverage plugin) to bypass permission restrictions.
   */
  fun pullFile(deviceFilePath: String, hostFilePath: File) {
    if (!deviceFilePath.startsWith("/data/")) {
      val pullResult = adbController.pull(deviceSerial, deviceFilePath, hostFilePath.absolutePath)
      if (pullResult.exitCode != 0) {
        logger.warning(
          "Failed to pull file from device: $deviceFilePath to $hostFilePath. exitCode=${pullResult.exitCode}, error=${pullResult.errorOutput}"
        )
      }
      return
    }

    hostFilePath.parentFile?.mkdirs()
    hostFilePath.outputStream().use { outputStream ->
      val exitCode =
        if (!runAsPackageName.isNullOrBlank() && deviceFilePath.startsWith("/data/")) {
          adbController.runAdbExecOutCommandToOutputStream(
            deviceSerial,
            listOf("run-as", runAsPackageName, "cat", deviceFilePath),
            outputStream,
          )
        } else {
          adbController.runAdbExecOutCommandToOutputStream(deviceSerial, listOf("cat", deviceFilePath), outputStream)
        }
      if (exitCode != 0) {
        logger.warning("Failed to pull file from device via cat: $deviceFilePath. exitCode=$exitCode")
        // Clean up the potentially incomplete file on host.
        hostFilePath.delete()
      }
    }
  }

  /** Pulls all files in a directory from the device to the host. */
  fun pullDirectory(deviceDirPath: String, hostDirPath: File, extension: String? = null) {
    val result =
      if (!runAsPackageName.isNullOrBlank() && deviceDirPath.startsWith("/data/")) {
        adbController.runAdbShellCommand(deviceSerial, listOf("run-as", runAsPackageName, "ls", deviceDirPath))
      } else {
        adbController.runAdbShellCommand(deviceSerial, listOf("ls", deviceDirPath))
      }
    if (result.exitCode != 0) {
      logger.warning("Failed to list directory on device: $deviceDirPath. exitCode=${result.exitCode}, error=${result.errorOutput}")
      return
    }
    val fileNames = result.output.lines().filter { it.isNotBlank() && (extension == null || it.endsWith(extension)) }
    fileNames.forEach { fileName -> pullFile("$deviceDirPath/$fileName", File(hostDirPath, fileName)) }
  }

  private fun getApiLevel(): Int {
    return adbController.runAdbShellCommand(deviceSerial, listOf("getprop", "ro.build.version.sdk")).output.trim().toIntOrNull() ?: 0
  }

  private fun createEmptyDirectoryOnHost() {
    additionalOutputDirectoryOnHost?.let { dir ->
      if (dir.exists()) {
        dir.deleteRecursively()
      }
      dir.mkdirs()
    }
  }

  private fun getCandidatePackageIds(): LinkedHashSet<String> {
    val ids = LinkedHashSet<String>()
    listOf(instrumentationTargetPackageId, testedApplicationId, testPackageId).forEach { id ->
      if (id.isNotBlank()) {
        ids.add(id)
        if (id.endsWith(".test")) {
          ids.add(id.substringBeforeLast(".test"))
        }
      }
    }
    return ids
  }

  /** Collects additional test outputs from the device after test execution. */
  fun collect() {
    logger.info("Collecting additional test outputs.")
    try {
      copyAdditionalTestOutputsFromDeviceToHost()
    } catch (e: Exception) {
      logger.log(Level.WARNING, "Failed to retrieve additional test outputs from device.", e)
    }
    try {
      if (useTestStorageService) {
        copyTestStorageServiceOutputFilesFromDeviceToHost()
      }
    } catch (e: Exception) {
      logger.log(Level.WARNING, "Failed to retrieve test storage service outputs from device.", e)
    }
  }

  internal fun getEffectiveAdditionalOutputDirectoryOnDevice(): String? {
    if (!additionalOutputDirectoryOnDevice.isNullOrBlank()) {
      return additionalOutputDirectoryOnDevice
    }

    val apiLevel = getApiLevel()
    if (apiLevel < ADDITIONAL_TEST_OUTPUT_MIN_API_LEVEL) {
      return null
    }

    if (apiLevel >= 29) {
      // sdcard/Android/media/<package_name> is the only special-cased storage dir, which
      // allows separate shell processes and instrumented tests to both have read/write access
      // without needing to apply external legacy storage flags (which were removed in API 30)
      // or --no-isolated-storage.
      return "/sdcard/Android/media/${instrumentationTargetPackageId}/additional_test_output"
    }

    val result =
      adbController.runAdbShellCommand(
        deviceSerial,
        listOf(
          "content",
          "query",
          "--uri",
          "content://media/external/file",
          "--projection",
          "_data",
          "--where",
          "\"_data LIKE '%/Android'\"",
        ),
      )
    val line = result.output.lines().firstOrNull { it.startsWith("Row:") }
    val data = line?.split("_data=")?.getOrNull(1)?.trim()
    return if (data != null) "${data}/data/${instrumentationTargetPackageId}/files/test_data" else null
  }

  private fun copyAdditionalTestOutputsFromDeviceToHost() {
    val apiLevel = getApiLevel()
    if (apiLevel < ADDITIONAL_TEST_OUTPUT_MIN_API_LEVEL) return

    val hostDir = additionalOutputDirectoryOnHost?.absolutePath ?: return
    val androidUser = getCurrentAndroidUser()

    val collectedDirs = mutableSetOf<String>()

    // 1. Try effective directory (either explicit or calculated)
    val effectiveDeviceDir = getEffectiveAdditionalOutputDirectoryOnDevice()
    if (!effectiveDeviceDir.isNullOrBlank()) {
      val normalizedDir = replaceSystemPath(effectiveDeviceDir, androidUser)
      val exists = isDirectory(normalizedDir)
      // For secondary user, even if exists is false, we try to copy using content provider.
      if (exists || androidUser != "0") {
        copyFilesFromDeviceToHost(effectiveDeviceDir, hostDir)
        collectedDirs.add(normalizedDir)
      }
    }

    // 2. Try candidate package IDs as fallback
    val idsToTry = getCandidatePackageIds()
    for (id in idsToTry) {
      val deviceDir = "/sdcard/Android/media/$id/additional_test_output"
      val normalizedDir = replaceSystemPath(deviceDir, androidUser)
      val exists = isDirectory(normalizedDir)
      if ((exists || androidUser != "0") && !collectedDirs.contains(normalizedDir)) {
        logger.info("Collecting additional test outputs from $deviceDir")
        copyFilesFromDeviceToHost(deviceDir, hostDir)
        collectedDirs.add(normalizedDir)
      }
    }

    // 3. Fallback to find command if nothing collected yet
    if (collectedDirs.isEmpty() && apiLevel >= 29) {
      val searchRoot = replaceSystemPath("/sdcard/Android/media/", androidUser)
      val findResult =
        adbController.runAdbShellCommand(deviceSerial, listOf("find", searchRoot, "-name", "additional_test_output", "-type", "d"))
      val foundPaths = findResult.output.lines().filter { it.isNotBlank() }
      for (path in foundPaths) {
        val normalizedPath = replaceSystemPath(path, androidUser)
        if (!collectedDirs.contains(normalizedPath)) {
          logger.info("Collecting additional test outputs from found path: $path")
          copyFilesFromDeviceToHost(path, hostDir)
          collectedDirs.add(normalizedPath)
        }
      }
    }
  }

  private fun copyTestStorageServiceOutputFilesFromDeviceToHost() {
    val deviceDir = TEST_STORAGE_SERVICE_OUTPUT_DIR
    val hostDir = additionalOutputDirectoryOnHost?.absolutePath ?: return
    copyFilesFromDeviceToHost(deviceDir, hostDir)
  }

  private fun copyFilesFromDeviceToHost(deviceDir: String, hostDir: String, filter: ((relativeFilePath: String) -> Boolean)? = null) {
    val apiLevel = getApiLevel()
    val currentAndroidUser = getCurrentAndroidUser()
    val normalizedDeviceDir = replaceSystemPath(deviceDir, currentAndroidUser)

    if (apiLevel >= 28 && currentAndroidUser != "0") {
      logger.info("Copying files using content provider for user $currentAndroidUser from $deviceDir.")
      copyFilesFromDeviceToHostUsingContentProvider(deviceDir, hostDir, currentAndroidUser, filter ?: { true })
      return
    }

    if (!isDirectory(normalizedDeviceDir)) {
      return
    }

    // UTP plugin implementation uses recursive ls and pull for each file.
    val result = adbController.runAdbShellCommand(deviceSerial, listOf("ls", normalizedDeviceDir))
    if (result.exitCode != 0) {
      logger.warning("Failed to list $normalizedDeviceDir: ${result.errorOutput}")
      return
    }
    result.output
      .split("\n")
      .map { it.trim() }
      .filter { it.isNotBlank() }
      .forEach {
        val deviceFilePath = "${normalizedDeviceDir}/${it}"
        val hostFilePath = File(hostDir, it).absolutePath
        if (isDirectory(deviceFilePath)) {
          File(hostFilePath).let { file ->
            if (!file.exists()) {
              file.mkdirs()
            }
          }
          copyFilesFromDeviceToHost(deviceFilePath, hostFilePath, filter)
        } else if (filter == null || filter(it)) {
          logger.info("Pulling $deviceFilePath to $hostFilePath")
          val pullResult = adbController.pull(deviceSerial, deviceFilePath, hostFilePath)
          if (pullResult.exitCode != 0) {
            logger.warning("Failed to pull $deviceFilePath to $hostFilePath: ${pullResult.errorOutput}")
          }
        }
      }
  }

  private fun copyFilesFromDeviceToHostUsingContentProvider(
    deviceDir: String,
    hostDir: String,
    androidUser: String,
    filter: (relativeFilePath: String) -> Boolean,
  ) {
    // Media store's file index might not be up-to-date. b/345801721.
    val broadcastResult =
      adbController.runAdbShellCommand(
        deviceSerial,
        listOf("am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file://$deviceDir"),
      )
    logger.info(
      "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$deviceDir " +
        "finished with exitCode=${broadcastResult.exitCode}. " +
        "stdout=${broadcastResult.output.trim()}, stderr=${broadcastResult.errorOutput.trim()}"
    )

    val normalizedDeviceDir = replaceSystemPath(deviceDir, androidUser).removeSuffix("/")
    val regex = Regex("""_id=(\d+), _data=(.*)""")
    val result =
      adbController.runAdbShellCommand(
        deviceSerial,
        listOf(
          "content",
          "query",
          "--uri",
          "content://media/external/file",
          "--user",
          androidUser,
          "--projection",
          "_id:_data",
          "--where",
          "\"mime_type IS NOT NULL AND _data LIKE '$normalizedDeviceDir%'\"",
        ),
      )

    result.output.lines().forEach {
      val matchResult = regex.find(it) ?: return@forEach
      val (id, path) = matchResult.destructured
      val relativeFilePath = path.removePrefix(normalizedDeviceDir).removePrefix("/")
      if (relativeFilePath.isNotEmpty() && filter(relativeFilePath)) {
        val hostFile = File(hostDir, relativeFilePath)
        hostFile.parentFile?.let { parentFile ->
          if (!parentFile.exists()) {
            parentFile.mkdirs()
          }
        }
        logger.info("Copying $path to ${hostFile.absolutePath} using content provider")
        hostFile.outputStream().use { fileOutputStream ->
          adbController.runAdbShellCommandToOutputStream(
            deviceSerial,
            listOf("content", "read", "--user", androidUser, "--uri", "content://media/external/file/$id"),
            fileOutputStream,
          )
        }
      }
    }
  }

  private fun replaceSystemPath(deviceDir: String, androidUser: String): String {
    if (androidUser == "0") return deviceDir
    if (deviceDir.lowercase().startsWith("/sdcard/")) {
      return "/storage/emulated/$androidUser/" + deviceDir.substring("/sdcard/".length).removePrefix("/")
    }
    if (deviceDir.lowercase().startsWith("/data/data/")) {
      return "/data/user/$androidUser/" + deviceDir.substring("/data/data/".length).removePrefix("/")
    }
    return deviceDir
  }

  /** Retrieves benchmark output from a given [testResult] and copies benchmark files from device to host. */
  fun addBenchmarkOutput(testResult: TestResult) {
    val statusBundle = testResult.statusBundle
    val benchmarkMessage =
      statusBundle[BENCHMARK_V3_TEST_METRICS_KEY]
        ?: statusBundle[BENCHMARK_V2_TEST_METRICS_KEY]
        ?: statusBundle[BENCHMARK_TEST_METRICS_KEY]
        ?: ""

    if (benchmarkMessage.isBlank()) return

    val benchmarkMessageWithoutPrefix = benchmarkPrefixRegex.replace(benchmarkMessage, "")
    val benchmarkOutputDir = statusBundle[BENCHMARK_V3_PATH_TEST_METRICS_KEY] ?: statusBundle[BENCHMARK_PATH_TEST_METRICS_KEY] ?: ""

    addBenchmarkMessage(benchmarkMessageWithoutPrefix, testResult)
    addBenchmarkFiles(benchmarkMessageWithoutPrefix, benchmarkOutputDir)
  }

  private fun addBenchmarkMessage(benchmarkMessage: String, testResult: TestResult) {
    val hostOutputDir = additionalOutputDirectoryOnHost ?: return
    val testIdentifier = testResult.testIdentifier
    val packageName = testIdentifier.testPackage
    val fullClassName = if (packageName.isNotEmpty()) "$packageName.${testIdentifier.testClass}" else testIdentifier.testClass
    val fileNameSuffix = "${fullClassName}.${testIdentifier.testMethod}"
    val benchmarkMessageOutputFile = File(hostOutputDir, "additionaltestoutput.benchmark.message_${fileNameSuffix}.txt")
    benchmarkMessageOutputFile.writeText(benchmarkMessage, StandardCharsets.UTF_8)
  }

  private fun addBenchmarkFiles(benchmarkMessage: String, benchmarkOutputDir: String) {
    if (benchmarkMessage.isBlank() || benchmarkOutputDir.isBlank()) return

    val benchmarkFileRelativePaths =
      benchmarkMessage
        .splitToSequence("\n")
        .flatMap { line -> benchmarkUrlRegex.findAll(line) }
        .mapNotNull { matchResult -> matchResult.groups[LINK_GROUP]?.value }
        .filter { matchValue ->
          matchValue.startsWith(BENCHMARK_TRACE_FILE_PREFIX) || matchValue.startsWith(BENCHMARK_V3_TRACE_FILE_PREFIX)
        }
        .map { matchValue -> matchValue.replace(BENCHMARK_TRACE_FILE_PREFIX, "").replace(BENCHMARK_V3_TRACE_FILE_PREFIX, "") }
        .toSet()

    val hostOutputDir = additionalOutputDirectoryOnHost?.absolutePath ?: return
    copyFilesFromDeviceToHost(benchmarkOutputDir, hostOutputDir, benchmarkFileRelativePaths::contains)
  }

  private fun isDirectory(deviceFilePath: String): Boolean {
    val result = adbController.runAdbShellCommand(deviceSerial, listOf("test", "-d", deviceFilePath))
    return result.exitCode == 0
  }

  private fun isTestStorageServiceInstalled(): Boolean {
    return adbController
      .runAdbShellCommand(deviceSerial, listOf("pm", "list", "packages", "androidx.test.services"))
      .output
      .contains("package:androidx.test.services")
  }

  private fun getCurrentAndroidUser(): String {
    return adbController
      .runAdbShellCommand(deviceSerial, listOf("am", "get-current-user"))
      .output
      .lineSequence()
      .map { it.trim() }
      .firstOrNull { it.isNotBlank() } ?: "0"
  }
}
