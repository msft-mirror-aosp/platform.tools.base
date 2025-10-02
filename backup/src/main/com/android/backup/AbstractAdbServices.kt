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
package com.android.backup

import com.android.adblib.DeviceSelector
import com.android.backup.BackupProgressListener.Step
import com.android.backup.ErrorCode.APP_STOPPED
import com.android.backup.ErrorCode.BACKUP_FAILED
import com.android.backup.ErrorCode.BACKUP_MANAGER_IS_NOT_RUNNING
import com.android.backup.ErrorCode.BACKUP_NOT_ACTIVATED
import com.android.backup.ErrorCode.BACKUP_NOT_SUPPORTED
import com.android.backup.ErrorCode.BMGR_ERROR_BACKUP
import com.android.backup.ErrorCode.BMGR_ERROR_RESTORE
import com.android.backup.ErrorCode.CANNOT_ENABLE_BMGR
import com.android.backup.ErrorCode.DEVICE_DISCONNECTED
import com.android.backup.ErrorCode.GMSCORE_IS_TOO_OLD
import com.android.backup.ErrorCode.GMSCORE_IS_TOO_OLD_NO_PLAY_STORE
import com.android.backup.ErrorCode.GMSCORE_NOT_FOUND
import com.android.backup.ErrorCode.PLAY_STORE_NOT_INSTALLED
import com.android.backup.ErrorCode.RESTORE_FAILED
import com.android.backup.ErrorCode.TRANSPORT_INIT_FAILED
import com.android.backup.ErrorCode.TRANSPORT_NOT_SELECTED
import com.android.backup.ErrorCode.UNEXPECTED_ERROR
import com.android.commands.bmgr.outputparser.BmgrError
import com.android.commands.bmgr.outputparser.BmgrOutputParser
import com.android.tools.environment.Logger
import com.android.utils.text.dropPrefix
import kotlin.text.RegexOption.IGNORE_CASE
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

private const val SELECT_TRANSPORT_COMPONENT_SUCCESS = "Success. Selected transport: "
private val PACKAGE_VERSION_CODE_REGEX = "^ {4}versionCode=(?<version>\\d+).*$".toRegex()
private val APPLICATION_ID_REGEX = "^([a-z][a-z\\d_]*\\.)+[a-z][a-z\\d_]*$".toRegex(IGNORE_CASE)
private const val SECURE_SETTING_ENABLE_TESTING = "backup_enable_testing_flows"
private const val SECURE_SETTING_BACKUP_TYPE = "backup_testing_flows_type"

abstract class AbstractAdbServices(
  protected val serialNumber: String,
  protected val logger: Logger,
  protected val progressListener: BackupProgressListener?,
  private var totalSteps: Int,
  private var minGmsVersion: Int,
) : AdbServices {

  private var step = 0

  protected val deviceSelector = DeviceSelector.fromSerialNumber(serialNumber)

  override suspend fun reportProgress(text: String) {
    logger.info(text)
    if (step > totalSteps) {
      totalSteps = step
    }
    progressListener?.onStep(Step(++step, totalSteps, text))
  }

  override suspend fun withSetup(transport: BackupTransport, block: suspend () -> Unit) {
    verifyGmsCore()
    withBmgr { withTestMode { withTransport(transport) { block() } } }
  }

  override suspend fun initializeTransport(transport: String): Boolean {
    val out = executeCommand("bmgr init $transport", TRANSPORT_INIT_FAILED).stdout
    val last = out.lines().last()
    return when (last) {
      "Initialization result: 0" -> true
      "Initialization result: -1000" -> {
        logger.warn("Failed to initialize '$transport`: $out")
        false
      }
      else ->
        throw BackupException(TRANSPORT_INIT_FAILED, "Failed to initialize '$transport`: $out")
    }
  }

  override suspend fun backupNow(applicationId: String, type: BackupType, initOk: Boolean) {
    setBackupType(type)
    val command = "bmgr backupnow @pm@ $applicationId --non-incremental --monitor-verbose"
    val out = executeCommand(command, BACKUP_FAILED).stdout
    val errors = BmgrOutputParser.parseBmgrErrors(out)
    if (errors.isEmpty()) {
      return
    }
    if (errors.isAppStopped()) {
      throw BackupException(
        APP_STOPPED,
        "Application '$applicationId' is in a stopped state. Please launch the app and try again.",
      )
    }
    val errorCode = if (initOk) BMGR_ERROR_BACKUP else TRANSPORT_INIT_FAILED
    val message = "Failed to backup '$applicationId`:\n${errors.joinToString("\n") { it.message }}"
    throw BackupException(errorCode, message, BmgrException(command, out, errors))
  }

  override suspend fun clearAppData(applicationId: String) {
    val out = executeCommand("pm clear $applicationId").stdout.trim()
    if (out != "Success") {
      // Don't throw if clear app data fails but log a warning.
      logger.warn("Failed to clear app data for package $applicationId")
    }
  }

  override suspend fun restore(
    token: String,
    applicationId: String,
    type: BackupType,
    initOk: Boolean,
  ) {
    setBackupType(type)
    val command = "bmgr restore $token $applicationId --monitor-verbose"
    val out = executeCommand(command, RESTORE_FAILED).stdout
    val errors = BmgrOutputParser.parseBmgrErrors(out)
    if (errors.isEmpty()) {
      return
    }
    val errorCode = if (initOk) BMGR_ERROR_RESTORE else TRANSPORT_INIT_FAILED
    val message = "Failed to restore '$applicationId`:\n${errors.joinToString("\n") { it.message }}"
    throw BackupException(errorCode, message, BmgrException(command, out, errors))
  }

  override suspend fun sendUpdateGmsIntent() {
    val out = executeCommand("am start market://details?id=com.google.android.gms")

    // The 'am' command reports errors in stderr
    val stdout = out.stdout
    val stderr = out.stderr
    if (
      !stderr.contains("Error") &&
        stdout == "Starting: Intent { act=android.intent.action.VIEW dat=market://details/... }"
    ) {
      return
    }
    if (stderr.contains("Error: Activity not started, unable to resolve Intent")) {
      throw BackupException(
        PLAY_STORE_NOT_INSTALLED,
        "Failed to update GmsCore. Play Store not installed",
      )
    } else {
      throw BackupException(
        UNEXPECTED_ERROR,
        "Failed to update GmsCore. Unexpected output: '$stdout\n$stderr'",
      )
    }
  }

  override suspend fun getForegroundApplicationId(): String {
    val stdout = executeCommand("dumpsys activity activities").stdout
    val lines = stdout.lineSequence()
    val line =
      lines.find { it.contains("mFocusedApp=ActivityRecord") }
        ?: lines.find { it.contains("ResumedActivity: ActivityRecord") }
    if (line == null) {
      logger.warn("Could not detect foreground app. Unexpected output:\n$stdout")
      throw BackupException(UNEXPECTED_ERROR, "Could not detect foreground app. See log for detail")
    }
    val applicationId = line.substringBefore('/').substringAfterLast(' ')
    if (!APPLICATION_ID_REGEX.matches(applicationId)) {
      throw BackupException(UNEXPECTED_ERROR, "Unexpected application id found in: '$line'.")
    }
    return applicationId
  }

  private suspend fun withBmgr(block: suspend () -> Unit) {
    reportProgress("Checking if BMGR is enabled")
    val bmgrEnabled = isBmgrEnabled()
    if (!bmgrEnabled) {
      totalSteps += 2
      reportProgress("Enabling BMGR")
      enableBmgr(true)
    }
    try {
      block()
    } finally {
      if (!bmgrEnabled) {
        reportProgress("Disabling BMGR")
        enableBmgr(false)
      }
    }
  }

  private suspend fun verifyGmsCore() {
    reportProgress("Verifying Google services")
    val lines =
      executeCommand("dumpsys package com.google.android.gms").stdout.lineSequence().dropWhile {
        it != "Packages:"
      }
    val versionMatch = lines.firstNotNullOfOrNull { PACKAGE_VERSION_CODE_REGEX.matchEntire(it) }
    if (versionMatch == null) {
      throw BackupException(GMSCORE_NOT_FOUND, "Google Services not found on device")
    }
    val versionString = versionMatch.getGroup("version")
    val version = versionString.toIntOrNull() ?: 0
    if (version < minGmsVersion) {
      when (isPlayStoreInstalled()) {
        true ->
          throw BackupException(
            GMSCORE_IS_TOO_OLD,
            "The version of Google Play services installed on the device hasn't" +
              " been auto updated yet. To manually update, open the Google Play store on the" +
              " device and update manually. You may need to sign into an account.",
          )
        false ->
          throw BackupException(
            GMSCORE_IS_TOO_OLD_NO_PLAY_STORE,
            "The version of Google Play services installed on the device is not supported." +
              " Please use a device with an image that includes the Google Play store.",
          )
      }
    }
  }

  override suspend fun getAppInfo(
    applicationId: String,
    withPermissions: Boolean,
    user: String?,
  ): AppInfo? {
    val lines = executeCommand("dumpsys package $applicationId").stdout.lines()
    val flags =
      lines
        .find { it.trim().startsWith("pkgFlags=") }
        ?.substringAfter('[')
        ?.substringBefore(']')
        ?.trim()
        ?.split(' ') ?: return null
    val allowBackup = flags.contains("ALLOW_BACKUP")
    val debuggable = flags.contains("DEBUGGABLE")

    val grantedPermissions = buildList {
      if (withPermissions) {
        assert(user != null) { "User must be specified if withPermissions is true" }
        lines
          .dropWhile { !it.startsWith("  Package [$applicationId] ") }
          .dropWhile { !it.startsWith("    User $user: ") }
          .dropWhile { it != "      runtime permissions:" }
          .drop(1)
          .takeWhile { it.startsWith("        ") }
          .filter { it.contains("granted=true") && !it.contains("ONE_TIME") }
          .forEach { add(it.trim().substringBefore(':')) }
      }
    }
    return AppInfo(debuggable, allowBackup, grantedPermissions)
  }

  override suspend fun isPlayStoreInstalled(): Boolean {
    try {
      val output =
        executeCommand("pm resolve-activity market://details?id=com.android.vending").stdout.trim()
      return output != "No activity found"
    } catch (e: BackupException) {
      // `pm list packages` can fail if the emulator is not ready yet but might also indicate a
      // problem.
      if (e.errorCode != DEVICE_DISCONNECTED) {
        logger.warn(e.message, e)
      }
      return false
    }
  }

  override suspend fun grantPermission(applicationId: String, permission: String) {
    try {
      executeCommand("pm grant $applicationId $permission")
    } catch (e: BackupException) {
      logger.warn("Failed to restore permission $permission on $applicationId", e)
    }
  }

  override suspend fun getDebuggableApps(): List<String> {
    return buildList {
      var packageName: String? = null
      executeCommand("dumpsys package").stdout.lineSequence().forEach { line ->
        if (line.startsWith("  Package [")) {
          packageName = line.substringAfter('[').substringBefore(']')
        }
        if (packageName != null && line.startsWith("    pkgFlags=[")) {
          val flags = line.substringAfter('[').substringBefore(']').trim().split(" ")
          if (flags.contains("DEBUGGABLE")) {
            add(packageName)
          }
          packageName = null
        }
      }
    }
  }

  override suspend fun getCurrentUser() = executeCommand("am get-current-user").stdout.trim()

  override suspend fun waitForBackupManager(user: String) {
    try {
      withTimeout(5.seconds) {
        while (true) {
          val out = executeCommand("dumpsys backup users").stdout.lines()
          if (out.contains("Backup Manager is running for users: $user")) {
            return@withTimeout
          }
          delay(1.seconds)
        }
      }
    } catch (_: TimeoutCancellationException) {
      throw BackupException(
        BACKUP_MANAGER_IS_NOT_RUNNING,
        "Backup manager is not running for user $user",
      )
    }
  }

  private suspend fun withTestMode(block: suspend () -> Unit) {
    reportProgress("Enabling test mode")
    enableTestMode(true)
    try {
      block()
    } finally {
      reportProgress("Disabling test mode")
      enableTestMode(false)
    }
  }

  private suspend fun withTransport(transport: BackupTransport, block: suspend () -> Unit) {
    reportProgress("Setting backup transport")
    val oldTransport = getCurrentTransport()
    if (oldTransport == transport.className) {
      block()
      return
    }
    totalSteps++
    setTransportWithTimeout(transport)
    try {
      block()
    } finally {
      reportProgress("Restoring backup transport")
      restoreTransport(oldTransport)
    }
  }

  private suspend fun setTransportWithTimeout(transport: BackupTransport) {
    var lastException: BackupException? = null

    try {
      withTimeout(5.seconds) {
        while (true) {
          try {
            setTransport(transport)
            break
          } catch (e: BackupException) {
            lastException = e
            logger.warn("Failed to set transport, retrying...", e)
            delay(1.seconds)
          }
        }
      }
    } catch (_: TimeoutCancellationException) {
      throw lastException
        ?: BackupException(TRANSPORT_NOT_SELECTED, "Timed out when setting transport")
    }
  }

  private suspend fun isBmgrEnabled(): Boolean {
    val output = executeCommand("bmgr enabled", CANNOT_ENABLE_BMGR)
    val stdout = output.stdout.trim()
    val stderr = output.stderr.trim()
    return when {
      stdout == "Backup Manager currently enabled" -> true
      stdout == "Backup Manager currently disabled" -> false
      stderr.contains("Could not access the Backup Manager") ->
        throw BackupException(BACKUP_NOT_SUPPORTED, "Backup is not supported on this device")
      stderr.contains("Backup Manager is not activated") ->
        throw BackupException(BACKUP_NOT_ACTIVATED, "Backup is not activated on this device")
      else ->
        throw BackupException(
          CANNOT_ENABLE_BMGR,
          "Unexpected output from 'bmgr enabled':\n${output.out}",
        )
    }
  }

  override suspend fun setTransport(transport: BackupTransport) {
    val selectTransportOut =
      executeCommand("bmgr transport -c ${transport.componentName}", TRANSPORT_NOT_SELECTED)
        .stdout
        .trim()
    if (!selectTransportOut.startsWith(SELECT_TRANSPORT_COMPONENT_SUCCESS)) {
      throw BackupException(
        TRANSPORT_NOT_SELECTED,
        "Unexpected result from 'bmgr transport -c' command: $selectTransportOut",
      )
    }
    val listTransportsOut = executeCommand("bmgr list transports", TRANSPORT_NOT_SELECTED).stdout
    val transports = listTransportsOut.lines()
    val currentTransport = transports.find { it.startsWith("  *") }?.dropPrefix("  * ")
    if (currentTransport != transport.className) {
      throw BackupException(
        TRANSPORT_NOT_SELECTED,
        "Requested transport was not set: $listTransportsOut",
      )
    }
  }

  // When restoring to a the original transport, we don't want to fail the entire operation so
  // there's no error checking here.
  private suspend fun restoreTransport(transport: String?) {
    if (transport == null) {
      return
    }
    try {
      executeCommand("bmgr transport $transport", TRANSPORT_NOT_SELECTED)
    } catch (_: Throwable) {
      // Ignore errors
    }
  }

  private suspend fun getCurrentTransport(): String? {
    val listTransportsOut = executeCommand("bmgr list transports").stdout
    val transports = listTransportsOut.lines()
    return transports.find { it.startsWith("  *") }?.dropPrefix("  * ")
  }

  private suspend fun enableBmgr(enabled: Boolean) {
    executeCommand("bmgr enable $enabled", CANNOT_ENABLE_BMGR)
  }

  private suspend fun enableTestMode(enabled: Boolean) {
    executeCommand("settings put secure $SECURE_SETTING_ENABLE_TESTING ${if (enabled) 1 else 0}")
  }

  private suspend fun setBackupType(type: BackupType) {
    executeCommand("settings put secure $SECURE_SETTING_BACKUP_TYPE ${type.type}")
  }
}

/** Get a named group value. Should only throw if the regex is bad. */
private fun MatchResult.getGroup(name: String) =
  groups[name]?.value ?: throw BackupException(UNEXPECTED_ERROR, "Group $name not found")

private fun List<BmgrError>.isAppStopped() = any { it.errorCode == "PACKAGE_STOPPED" }
