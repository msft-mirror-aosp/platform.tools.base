/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.fakeadbserver.services

import com.android.fakeadbserver.DeviceState
import java.util.Collections

class ServiceManager(private val deviceState: DeviceState) {

    private val packageManager = PackageManager()
    private var activityManager: Service = ActivityManager(deviceState)
    private val log = Collections.synchronizedList(mutableListOf<List<String>>())

    private val services: MutableMap<String, Service?> = mutableMapOf(
        ActivityManager.SERVICE_NAME to activityManager,
        PackageManager.SERVICE_NAME to packageManager
    )

    // Returns a list of all service request received.
    // Each entry is a list of all parameters for that request.
    fun getLogs(): List<ServiceRequest> {
        return Collections.unmodifiableList(log)
    }

    fun processCommand(args: List<String>, output: ShellCommandOutput) {
        // First argument should be the service name
        assert(args.isNotEmpty()) { "Service name not specified" }

        // We log received commands to allow tests to inspect call history
        log.add(Collections.unmodifiableList(args))

        val serviceName = args[0]
        val service = findService(serviceName)

        if (service == null) {
            output.writeStderr("Error: Service '$serviceName' is not supported")
            output.writeExitCode(5)
            return
        }

        // TODO: This should be done at a higher level, the same way adb server performs this check
        if (deviceState.deviceStatus.state != DeviceState.DeviceStatus.ONLINE.state) {
            output.writeStderr("adb: device is ${deviceState.deviceStatus.state}")
            output.writeExitCode(1)
            return
        }

        service.process(args.slice(1 until args.size), output)
    }

    /** Override the default [com.android.fakeadbserver.services.ActivityManager] */
    fun setActivityManager(newActivityManager: Service) {
        activityManager = newActivityManager
        setService(ActivityManager.SERVICE_NAME, newActivityManager)
    }

    fun setService(name: String, service: Service) {
        services[name] = service
    }

    fun services(): Map<String, Service?> {
        return services.toMap()
    }
    private fun findService(name: String): Service? = services[name]
}

typealias ServiceRequest = List<String>
