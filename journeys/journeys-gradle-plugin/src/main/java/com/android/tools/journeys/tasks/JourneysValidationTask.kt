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

package com.android.tools.journeys.tasks

import com.android.builder.testing.api.DeviceConnector
import com.android.builder.testing.api.DeviceProvider
import com.android.utils.FileUtils
import com.android.utils.ILogger
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Runs journeys tests of a variant.
 */
@DisableCachingByDefault
abstract class JourneysValidationTask : Test() {

    /** Input directory containing journeys xml files. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val journeysInputDir: DirectoryProperty

    /** Output directory for test results. */
    @get:OutputDirectory
    abstract val resultsDir: DirectoryProperty

    /** Application ID of the app under test. */
    @get:Input
    abstract val applicationId: Property<String>

    /** Comma-separated list of journeys to run.
     * If no filter is specified, all journeys will be run.
     */
    @get:Optional
    @get:Input
    abstract val journeysFilter: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apkDirectories: ListProperty<Directory>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val journeysCrawlerConfig: ConfigurableFileCollection

    @get:Internal
    abstract val adbExecutable: RegularFileProperty

    @get:Optional
    @get:Input
    abstract val accessTokenFilePath: Property<String>

    @TaskAction
    override fun executeTests() {
        FileUtils.cleanOutputDir(resultsDir.get().asFile)

        // TODO(saxenaankita): Remove the workaround to fetch apks once test suites is available.
        val allApks = apkDirectories.get().flatMap {
            it.asFile.walkTopDown().filter { file -> file.name.endsWith(".apk") }.asIterable()
        }
        if (allApks.isEmpty()) {
            throw GradleException("No apk found to run journeys.")
        }
        val appApk = if (allApks.count() == 1) {
            allApks[0]
        } else {
            allApks.firstOrNull { it.name.contains("universal") }
                ?: throw GradleException("Could not find Universal APK for journeys.")
        }
        setTestEngineParam("Proxy.appApkPath", appApk.absolutePath)

        val logger = getLogger()
        // Reflection to access gradle-core classes without explicit dependency.
        val classLoader = this.javaClass.classLoader
        val loggerWrapperClass = classLoader.loadClass(LOGGER_WRAPPER)
        val loggerWrapperConstructor = loggerWrapperClass.getDeclaredConstructor(Logger::class.java)
        val iLogger = loggerWrapperConstructor.newInstance(logger)

        // In this approach, the limitations observed are:
        // 1) Even if some of the devices are available, the task won't use
        // them until all required devices are available.
        // 2) Unused devices get blocked too (unused means the ones which can
        // be skipped based on user specified device filter)
        val connectedDeviceProviderClass = classLoader.loadClass(CONNECTED_DEVICE_PROVIDER)
        val connectedDeviceProviderConstructor =
            connectedDeviceProviderClass.getDeclaredConstructor(
                File::class.java, Int::class.java, ILogger::class.java, String::class.java
            )
        val getDevicesMethod = connectedDeviceProviderClass.getDeclaredMethod("getDevices")
        val deviceProvider = connectedDeviceProviderConstructor.newInstance(
            adbExecutable.get().asFile,
            0,
            iLogger,
            System.getenv("ANDROID_SERIAL")
        )

        (deviceProvider as? DeviceProvider)?.use {
            val deviceConnectors = getDevicesMethod.invoke(deviceProvider) as? List<DeviceConnector>
            // Use the first device if multiple devices are connected.
            val connectedDeviceId = deviceConnectors?.firstOrNull()?.serialNumber

            if (connectedDeviceId != null) {
                setTestEngineParam("journeysInputDir", journeysInputDir.get().asFile.absolutePath)
                setTestEngineParam("resultsDir", resultsDir.get().asFile.absolutePath)
                setTestEngineParam("testDeviceId", connectedDeviceId)
                journeysFilter.orNull?.let {
                    setTestEngineParam(
                        "journeysFilter", it.split(",").map { it.trim() }.joinToString(",")
                    )
                }
                setTestEngineParam("Proxy.applicationId", applicationId.get())
                setTestEngineParam("Proxy.adbPath", adbExecutable.get().asFile.absolutePath)
                setTestEngineParam("Proxy.crawlerApkPath", journeysCrawlerConfig.singleFile.absolutePath)
                setTestEngineParam("Proxy.accessTokenPath", accessTokenFilePath.get())
                super.executeTests()
            } else {
                logger.quiet("No devices connected, exiting")
            }
        }
    }

    /**
     * Sets a test engine parameter as a JVM argument.
     *
     * @param key The parameter key.
     * @param value The parameter value.
     */
    private fun setTestEngineParam(key: String, value: String) {
        jvmArgs("-DJourneysTestEngineInput.$key=$value")
    }
}

private const val LOGGER_WRAPPER = "com.android.build.gradle.internal.LoggerWrapper"
private const val CONNECTED_DEVICE_PROVIDER =
    "com.android.build.gradle.internal.testing.ConnectedDeviceProvider"
