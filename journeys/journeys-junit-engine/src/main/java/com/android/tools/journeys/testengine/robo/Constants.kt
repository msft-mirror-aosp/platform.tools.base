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

package com.android.tools.journeys.testengine.robo

import java.time.Duration

/**
 * Holds constant values used for configuring and interacting with the Robo components.
 */
object RoboConfigConstants {

    /** Package ID for the crawler application. */
    const val CRAWLER_PACKAGE_ID = "com.google.appcrawler.platform"

    /**
     * The relative class name for the ProxyService within the crawler package.
     * Intended to be appended to [CRAWLER_PACKAGE_ID].
     */
    const val PROXY_SERVICE = ".proxy.ProxyService"

    /** Argument key used to indicate ADB port forwarding is requested. */
    const val ROBO_ADB_FORWARD = "adb_forward"

    /** Argument key used to indicate picking an open port is requested. */
    const val ROBO_PICK_OPEN_PORT = "pick_open_port"

    /** The filename for the Robo test results protobuf file. */
    const val ROBO_RESULTS_FILE_NAME = "robo_results.pb"

    /** Argument key for specifying robo script config. */
    const val ROBO_SCRIPT_CONFIG_NAME = "robo.script"

    /** Argument key for specifying the target application package in Robo tests. */
    const val ROBO_V2_APP_PACKAGE_FLAG = "appPackageName"

    /** Argument key for enabling UI Automator only mode in Robo tests. */
    const val ROBO_V2_UI_AUTOMATOR_ONLY_MODE = "uiAutomatorOnlyMode"

    /** The fully qualified class name of the test runner for instrumentation. */
    const val TEST_RUNNER_CLASS = "androidx.test.runner.AndroidJUnitRunner"

    /** Constants used for auth and connecting to crawler backend. */
    const val CRAWLER_BACKEND_ENDPOINT = "dns:///appcrawler-pa.googleapis.com:443"
    const val AUTH_SCOPE = "https://www.googleapis.com/auth/userinfo.email"
    const val AUTH_PRINCIPAL = "journeys@appcrawler-external.iam.gserviceaccount.com"
    const val QUOTA_PROJECT_ID = "appcrawler-external"

    /** The timeout for the crawl to complete. */
    const val TEST_TIMEOUT_SECONDS = 3600L

    /** Description of a goal complete model detail. */
    const val GOAL_COMPLETE_DESCRIPTION = "Goal Complete"

    /** Description of a goal failed model detail. */
    const val GOAL_FAILED_DESCRIPTION = "Goal Failed"

    /** Default timeout duration for platform connection. */
    val DEFAULT_PLATFORM_TIMEOUT: Duration = Duration.ofSeconds(60)

    /** Default timeout duration for waiting for responses from platform. */
    val DEFAULT_RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(75)

    /** Default timeout duration for waiting for responses from server. */
    val DEFAULT_RESULTS_TIMEOUT: Duration = Duration.ofSeconds(100)

    /**
     * Regex pattern to detect the "port_is_bound" message from Robo proxy logs,
     * capturing the bound port number in group 1.
     */
    val ROBO_PROXY_PORT_IS_BOUND_PATTERN = "port_is_bound(?:\\s+(\\d+))?".toPattern()

    /** Model descriptions which specify goal state. */
    val GOAL_STATUS_MODEL_DESCRIPTION = setOf(GOAL_COMPLETE_DESCRIPTION, GOAL_FAILED_DESCRIPTION)
}
