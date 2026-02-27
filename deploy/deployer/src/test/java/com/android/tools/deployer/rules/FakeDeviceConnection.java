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
import com.android.adblib.ddmlibcompatibility.testutils.UseAdbLibAndroidDebugBridgeRule;
import com.android.adblib.testingutils.FakeAdbServerProviderRule;
import com.android.adblib.testingutils.TestingAdbSessionHost;
import com.android.ddmlib.AndroidDebugBridge;
import com.android.fakeadbserver.FakeAdbServer;
import com.android.tools.deployer.devices.DeviceId;
import com.android.tools.deployer.devices.FakeDevice;
import com.android.tools.deployer.devices.FakeDeviceHandler;
import com.android.tools.deployer.devices.FakeDeviceLibrary;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * A JUnit {@link TestRule} that sets up a {@link FakeDevice} and connects it to a {@link FakeAdbServer}.
 * <p>
 * This rule handles the lifecycle of the device and the ADB bridge, ensuring that they are
 * properly initialized before each test and shut down afterward. It also optionally supports
 * enabling adblib compatibility for tests that require it.
 */
public class FakeDeviceConnection implements TestRule {

    private final DeviceId deviceId;
    private FakeDevice device;
    private boolean useAdbLib = false;
    private final FakeDeviceHandler handler = new FakeDeviceHandler();
    public final FakeAdbServerProviderRule fakeAdbRule = new FakeAdbServerProviderRule(
            provider -> {
                provider.installDeviceHandler(handler);
                return kotlin.Unit.INSTANCE;
            }
    );

    /**
     * This rule is responsible for configuring the bridge and connecting the device.
     * It is separated from {@link #fakeAdbRule} so that {@link UseAdbLibAndroidDebugBridgeRule}
     * can be inserted between them. This ensures that the bridge is redirected *before*
     * we enable fake ADB server mode and connect the device.
     */
    private final TestRule deviceConnectionRule = new TestRule() {
        @Override
        public Statement apply(Statement base, Description description) {
            return new Statement() {
                @Override
                public void evaluate() throws Throwable {
                    device = new FakeDeviceLibrary().build(deviceId);
                    FakeAdbServer adbServer = fakeAdbRule.getFakeAdb().getFakeAdbServer();
                    AndroidDebugBridge.enableFakeAdbServerMode(adbServer.getPort());
                    handler.connect(device, adbServer);

                    try {
                        base.evaluate();
                    } finally {
                        device.shutdown();
                        AndroidDebugBridge.terminate();
                        AndroidDebugBridge.disableFakeAdbServerMode();
                    }
                }
            };
        }
    };

    public FakeDeviceConnection(DeviceId deviceId) {
        this.deviceId = deviceId;
    }

    /**
     * A specialized {@link FakeDeviceConnection} that enables adblib compatibility by default.
     * Useful for tests that use {@link ApiLevel.Init} to initialize the rule.
     */
    public static class WithAdbLib extends FakeDeviceConnection {
        public WithAdbLib(DeviceId deviceId) {
            super(deviceId);
            useAdbLib();
        }
    }

    public void useAdbLib() {
        useAdbLib = true;
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

    @Override
    public Statement apply(Statement base, Description description) {
        RuleChain chain = RuleChain.outerRule(fakeAdbRule);
        if (useAdbLib) {
            chain = chain.around(new UseAdbLibAndroidDebugBridgeRule(this::getAdbSession));
        }
        return chain.around(deviceConnectionRule).apply(base, description);
    }
}
