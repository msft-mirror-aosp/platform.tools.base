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

package com.android.tools.ui.inspector.device

import com.android.adblib.AdbSession
import com.android.adblib.DeviceSelector
import com.android.adblib.shellAsText

/** An installed package and the Android user 0 UID that owns it. */
internal data class PackageUid(val packageName: String, val uid: Int)

/**
 * Resolves identities between packages and processes through their Linux UID.
 *
 * Android assigns every installed app its own Linux user account (UIDs from 10000 up); every process the app spawns runs as that user, and
 * the kernel stamps the UID on it immutably. The UID is therefore the one identifier that both the package manager (which assigned it) and
 * the kernel (which enforces it) agree on, and the one an app cannot influence — unlike process names, which a manifest sets freely via
 * `android:process`. Identity questions are answered by joining the two authorities on the UID: the foreground app is top-activity PID ->
 * UID -> owning package; a package's processes are package -> UID -> PIDs.
 *
 * All queries are scoped to Android user 0, matching bare `run-as`, which injection relies on; other users' apps run under distinct UID
 * ranges and are out of scope. Packages sharing a UID (legacy sharedUserId, deprecated since API 29) are the one case where UID -> package
 * is not unique; callers surface that ambiguity to the user.
 */
internal class UidResolver(private val adbSession: AdbSession, private val deviceSelector: DeviceSelector) {

  /** Queries the device for the UID owning [packageName] under Android user 0; null when the package is not installed there. */
  suspend fun packageUid(packageName: String): Int? {
    // The pm positional filter matches substrings, so the exact package is selected from the parsed result below.
    val output = adbSession.deviceServices.shellAsTextOrThrow(deviceSelector, "pm list packages -U --user 0 $packageName").stdout
    return parsePackageUids(output).firstOrNull { it.packageName == packageName }?.uid
  }

  /** Queries the device for every package installed under Android user 0 and its owning UID. */
  suspend fun allPackageUids(): List<PackageUid> =
    parsePackageUids(adbSession.deviceServices.shellAsTextOrThrow(deviceSelector, "pm list packages -U --user 0").stdout)

  /** Queries the device for the UID owning the process with [pid]; null when the process no longer exists. */
  suspend fun processUid(pid: String): Int? {
    // Trimming the full output first: toybox may print a blank header line before the value.
    val stdout = adbSession.deviceServices.shellAsText(deviceSelector, "ps -o UID= -p $pid").stdout.trim()
    return stdout.takeIf { DECIMAL_REGEX.matches(it) }?.toIntOrNull()
  }

  /** Queries the device for the IDs of all processes owned by [uid], preserving `ps` row order. */
  suspend fun pidsForUid(uid: Int): List<String> =
    parsePidsForUid(adbSession.deviceServices.shellAsTextOrThrow(deviceSelector, "ps -A -o PID,UID,NAME").stdout, uid)
}

private val PACKAGE_UID_REGEX = Regex("package:(\\S+) uid:(\\d+)")
private val DECIMAL_REGEX = Regex("\\d+")

/** Parses `pm list packages -U` output (`package:<name> uid:<uid>` per line), skipping blank, diagnostic, and malformed lines. */
internal fun parsePackageUids(output: String): List<PackageUid> =
  output.lines().mapNotNull { line ->
    val match = PACKAGE_UID_REGEX.matchEntire(line.trim()) ?: return@mapNotNull null
    val uid = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
    PackageUid(packageName = match.groupValues[1], uid = uid)
  }

/** Extracts from `ps -A -o PID,UID,NAME` [output] the PIDs of the rows whose UID is [uid], skipping the header and malformed rows. */
internal fun parsePidsForUid(output: String, uid: Int): List<String> {
  val pids = linkedSetOf<String>()
  output.lines().forEach { line ->
    val columns = line.trim().split("\\s+".toRegex())
    if (columns.size < 3) return@forEach
    val pid = columns[0]
    val rowUid = columns[1].toIntOrNull()
    if (pid.all { it.isDigit() } && rowUid == uid) {
      pids += pid
    }
  }
  return pids.toList()
}
