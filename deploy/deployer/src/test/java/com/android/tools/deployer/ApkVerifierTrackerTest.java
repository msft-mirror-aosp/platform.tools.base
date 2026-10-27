/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tools.deployer;

import static com.android.tools.deployer.common.ApkVerifierTracker.SKIP_VERIFICATION_OPTION;
import static com.android.tools.deployer.common.ApkVerifierTracker.getSkipVerificationInstallationFlag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.android.adblib.AdbSessionKt;
import com.android.adblib.ConnectedDevice;
import com.android.adblib.ConnectedDevicesTracker;
import com.android.adblib.ConnectedDevicesTrackerKt;
import com.android.adblib.testingutils.FakeAdbServerProviderRule;
import com.android.fakeadbserver.DeviceState;
import com.android.sdklib.AndroidApiLevel;
import com.android.tools.deployer.common.ApkVerifierTracker;
import com.android.tools.deployer.common.DeviceHolder;
import com.android.tools.deployer.devices.DeviceId;

import kotlin.coroutines.EmptyCoroutineContext;

import kotlinx.coroutines.BuildersKt;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

public class ApkVerifierTrackerTest {
    private static final String FIRST_PACKAGE = "package 0";
    private static final String SECOND_PACKAGE = "package 1";

    @Rule public final FakeAdbServerProviderRule fakeAdbRule = new FakeAdbServerProviderRule();

    private DeviceHolder api29Device;
    private DeviceHolder api30Device;
    private DeviceHolder api31Device;

    @Before
    public void before() throws InterruptedException {
        api29Device = connectDevice(DeviceId.API_29);
        api30Device = connectDevice(DeviceId.API_30);
        api31Device = connectDevice(DeviceId.API_31);
    }

    @After
    public void after() {
        ApkVerifierTracker.clear();
    }

    @Test
    public void verifyOnFirstInstall() {
        assertNull(getSkipVerificationInstallationFlag(api29Device, FIRST_PACKAGE));
        assertNull(getSkipVerificationInstallationFlag(api29Device, SECOND_PACKAGE));
        assertNull(getSkipVerificationInstallationFlag(api30Device, FIRST_PACKAGE));
        assertNull(getSkipVerificationInstallationFlag(api30Device, SECOND_PACKAGE));
    }

    @Test
    public void skipVerifyOnSecondInstall() {
        assertNull(getSkipVerificationInstallationFlag(api29Device, FIRST_PACKAGE, 0));
        assertNull(getSkipVerificationInstallationFlag(api30Device, FIRST_PACKAGE, 0));

        // Ensure that a fast followup install does not incur verification for API 30+ devices
        assertNull(getSkipVerificationInstallationFlag(api29Device, FIRST_PACKAGE, 1));
        assertEquals(
                SKIP_VERIFICATION_OPTION,
                getSkipVerificationInstallationFlag(api30Device, FIRST_PACKAGE, 1));

        // Ensure skipping doesn't bleed over to a different package.
        assertNull(getSkipVerificationInstallationFlag(api29Device, SECOND_PACKAGE, 2));
        assertNull(getSkipVerificationInstallationFlag(api30Device, SECOND_PACKAGE, 2));

        // Ensure skipping doesn't bleed over to a different device, which still skips
        // verification on its own fast followup install.
        assertNull(getSkipVerificationInstallationFlag(api31Device, FIRST_PACKAGE, 2));
        assertEquals(
                SKIP_VERIFICATION_OPTION,
                getSkipVerificationInstallationFlag(api31Device, FIRST_PACKAGE, 3));
    }

    @Test
    public void reverifiesAfterAnHourThenNoVerification() {
        assertNull(getSkipVerificationInstallationFlag(api29Device, FIRST_PACKAGE, 0));
        assertNull(getSkipVerificationInstallationFlag(api30Device, FIRST_PACKAGE, 0));

        long hour = TimeUnit.HOURS.toMillis(1);

        // Ensure that an install before an hour skips verification for API 30+ devices
        assertNull(getSkipVerificationInstallationFlag(api29Device, FIRST_PACKAGE, hour - 1));
        assertEquals(
                SKIP_VERIFICATION_OPTION,
                getSkipVerificationInstallationFlag(api30Device, FIRST_PACKAGE, hour - 1));

        // Ensure that an install on or after an hour gets verified, and that timestamps don't
        // get updated erroneously.
        assertNull(getSkipVerificationInstallationFlag(api29Device, FIRST_PACKAGE, hour));
        assertNull(getSkipVerificationInstallationFlag(api30Device, FIRST_PACKAGE, hour));

        // Ensure that a fast subsequent install skips verification for API 30+ devices
        assertNull(getSkipVerificationInstallationFlag(api29Device, FIRST_PACKAGE, hour + 1));
        assertEquals(
                SKIP_VERIFICATION_OPTION,
                getSkipVerificationInstallationFlag(api30Device, FIRST_PACKAGE, hour + 1));
    }

    private DeviceHolder connectDevice(DeviceId deviceId) throws InterruptedException {
        String serialNumber = "serial-" + deviceId.api();
        fakeAdbRule
                .getFakeAdb()
                .connectDevice(
                        serialNumber,
                        "Google",
                        "Pixel",
                        deviceId.version(),
                        new AndroidApiLevel(deviceId.api()),
                        DeviceState.HostConnectionType.USB,
                        /* maxSpeedMbps= */ 0,
                        /* negotiatedSpeedMbps= */ 0);
        ConnectedDevicesTracker tracker =
                AdbSessionKt.getConnectedDevicesTracker(fakeAdbRule.getAdbSession());
        ConnectedDevice connectedDevice =
                BuildersKt.runBlocking(
                        EmptyCoroutineContext.INSTANCE,
                        (scope, continuation) ->
                                ConnectedDevicesTrackerKt.waitForDevice(
                                        tracker, serialNumber, continuation));
        return new DeviceHolder(connectedDevice);
    }
}
