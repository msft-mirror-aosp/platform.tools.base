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
package com.android.tools.deployer.rules;

import com.android.adblib.AdbSession;
import com.android.adblib.AdbSessionKt;
import com.android.adblib.ConnectedDevice;
import com.android.adblib.ConnectedDevicesTracker;
import com.android.adblib.ConnectedDevicesTrackerKt;
import com.android.adblib.testingutils.CoroutineTestUtils;
import com.android.adblib.testingutils.FakeAdbServerProviderRule;
import com.android.adblib.testingutils.TestingAdbSessionHost;
import com.android.fakeadbserver.FakeAdbServer;
import com.android.tools.deployer.devices.DeviceId;
import com.android.tools.deployer.devices.FakeDevice;
import com.android.tools.deployer.devices.FakeDeviceHandler;
import com.android.tools.deployer.devices.FakeDeviceLibrary;

import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.time.Duration;

/**
 * A JUnit {@link TestRule} that sets up a {@link FakeDevice} and connects it to a {@link
 * FakeAdbServer}.
 *
 * <p>This rule handles the lifecycle of the device and the ADB server, ensuring that they are
 * properly initialized before each test and shut down afterward.
 */
public class FakeDeviceConnection implements TestRule {

    private final DeviceId deviceId;
    private FakeDevice device;
    private final FakeDeviceHandler handler = new FakeDeviceHandler();
    public final FakeAdbServerProviderRule fakeAdbRule = new FakeAdbServerProviderRule(
            provider -> {
                provider.installDeviceHandler(handler);
                return kotlin.Unit.INSTANCE;
            }
    );

    private final TestRule deviceConnectionRule = new TestRule() {
        @Override
        public Statement apply(Statement base, Description description) {
            return new Statement() {
                @Override
                public void evaluate() throws Throwable {
                    device = new FakeDeviceLibrary().build(deviceId);
                    FakeAdbServer adbServer = fakeAdbRule.getFakeAdb().getFakeAdbServer();
                    handler.connect(device, adbServer);

                    try {
                        base.evaluate();
                    } finally {
                        device.shutdown();
                    }
                }
            };
        }
    };

    public FakeDeviceConnection(DeviceId deviceId) {
        this.deviceId = deviceId;
    }

    public DeviceId getDeviceId() { return deviceId; }

    public FakeDevice getDevice() {
        return device;
    }

    public FakeAdbServer getServer() {
        return fakeAdbRule.getFakeAdb().getFakeAdbServer();
    }

    public TestingAdbSessionHost getHost() {
        return fakeAdbRule.getHost();
    }

    public AdbSession getAdbSession() {
        return fakeAdbRule.getAdbSession();
    }

    /**
     * Waits until the adblib session tracks the {@link #getDevice() device} and returns the
     * corresponding {@link ConnectedDevice}.
     */
    public ConnectedDevice waitForConnectedDevice() {
        ConnectedDevicesTracker tracker = AdbSessionKt.getConnectedDevicesTracker(getAdbSession());
        return CoroutineTestUtils.INSTANCE.runBlockingWithTimeout(
                Duration.ofSeconds(30),
                (scope, continuation) ->
                        ConnectedDevicesTrackerKt.waitForDevice(
                                tracker, device.getSerial(), continuation));
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return RuleChain.outerRule(fakeAdbRule)
                .around(deviceConnectionRule)
                .apply(base, description);
    }
}
