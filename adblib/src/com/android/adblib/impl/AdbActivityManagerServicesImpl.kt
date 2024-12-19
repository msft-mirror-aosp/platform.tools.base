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
package com.android.adblib.impl

import com.android.adblib.AdbActivityManagerException
import com.android.adblib.AdbActivityManagerServices
import com.android.adblib.AdbSession
import com.android.adblib.AmCapabilitiesResult
import com.android.adblib.DeviceSelector
import com.android.adblib.adbLogger
import com.android.adblib.shellCommand
import com.android.adblib.utils.ByteArrayShellCollector
import com.android.adblib.withTextCollector
import com.android.server.adb.protos.AmCapabilitiesProto
import java.io.IOException

class AdbActivityManagerServicesImpl(
    override val session: AdbSession
) : AdbActivityManagerServices {
    private val logger = adbLogger(session.host)

    override suspend fun forceStop(device: DeviceSelector, packageName: String) {
        validatePackageName(packageName)
        session.deviceServices
            .shellCommand(device, "am force-stop $packageName")
            .withTextCollector()
            .executeAsSingleOutput { result ->
                if (result.stderr.isNotEmpty()) {
                    val shortenedMessage = result.stderr.left(100)
                    logger.warn("`am force-stop $packageName`: stderr: $shortenedMessage")
                    throw IOException(result.stderr)
                }
            }
    }

    override suspend fun crash(device: DeviceSelector, packageName: String) {
        validatePackageName(packageName)
        session.deviceServices
            .shellCommand(device, "am crash $packageName")
            .withTextCollector()
            .executeAsSingleOutput { result ->
                if (result.stderr.isNotEmpty()) {
                    // stderr is not empty, e.g. for unsupported API level
                    val shortenedMessage = result.stderr.left(100)
                    logger.warn("`am crash $packageName`: stderr: $shortenedMessage")
                    throw IOException(result.stderr)
                }
            }
    }

    override suspend fun capabilities(device: DeviceSelector): AmCapabilitiesResult {
        // See Android platform implementation here:
        // https://cs.android.com/android/platform/superproject/main/+/1b409eb6cacc9508e6f415353ddcacdcb6bdaf26:frameworks/base/services/core/java/com/android/server/am/ActivityManagerShellCommand.java;l=480
        val result = session.deviceServices
            .shellCommand(device, "am capabilities --protobuf")
            .withCollector(ByteArrayShellCollector())
            .executeAsSingleOutput { result ->
                if (result.stderr.isNotEmpty()) {
                    val shortenedMessage = result.stderr.left(100)
                    logger.debug { "`am capabilities --protobuf`: stderr: $shortenedMessage" }
                    throw AdbActivityManagerException("capabilities", result.stderr)
                }
                result
            }

        val protoCapabilities = AmCapabilitiesProto.Capabilities.parseFrom(result.stdout)
        return AmCapabilitiesResult(
            capabilities = protoCapabilities.valuesList.map { it.name },
            vmCapabilities = protoCapabilities.vmCapabilitiesList.map { it.name },
            frameworkCapabilities = protoCapabilities.frameworkCapabilitiesList.map { it.name },
            vmInfo = if (protoCapabilities.vmInfo == AmCapabilitiesProto.VMInfo.getDefaultInstance()) {
                null
            } else {
                AmCapabilitiesResult.VmInfo(
                    name = protoCapabilities.vmInfo.name, version = protoCapabilities.vmInfo.version
                )
            },
        )
    }

    /**
     * Ensures a package name contains only valid characters.
     */
    private fun validatePackageName(packageName: String) {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            throw IllegalArgumentException("packageName `$packageName` contains illegal characters")
        }
    }

    /**
     * Returns **at most** the first [maxLength] characters of this string.
     */
    private fun String.left(maxLength: Int): String {
        return if (maxLength < 0) {
            ""
        } else if (maxLength > length) {
            substring(0, length)
        } else {
            substring(0, maxLength)
        }
    }
}

private val PACKAGE_NAME_REGEX: Regex = Regex("[a-zA-Z0-9._]+")
