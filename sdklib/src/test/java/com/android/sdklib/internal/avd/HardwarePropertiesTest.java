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
package com.android.sdklib.internal.avd;

import static com.android.sdklib.internal.avd.ConfigKey.CLUSTER_HEIGHT;
import static com.android.sdklib.internal.avd.ConfigKey.CLUSTER_WIDTH;
import static com.android.sdklib.internal.avd.ConfigKey.DISPLAY_SETTINGS_FILE;
import static com.android.sdklib.internal.avd.ConfigKey.DISTANT_DISPLAY_HEIGHT;
import static com.android.sdklib.internal.avd.ConfigKey.DISTANT_DISPLAY_WIDTH;
import static com.android.sdklib.internal.avd.ConfigKey.RESIZABLE_CONFIG;
import static com.android.sdklib.internal.avd.ConfigKey.ROLL;

import static com.google.common.truth.Truth.assertThat;

import static java.util.stream.Collectors.toList;

import com.android.resources.Navigation;
import com.android.sdklib.TempSdkManager;
import com.android.sdklib.devices.Device;
import com.android.sdklib.devices.DeviceManager;
import com.android.sdklib.devices.PowerType;
import com.android.sdklib.repository.AndroidSdkHandler;
import com.android.testutils.NoErrorsOrWarningsLogger;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.List;
import java.util.Map;

public class HardwarePropertiesTest {
    static final String WSVGA_HASH             = "MD5:be7b258bf9edce03131d307b10b00856";
    static final String WSVGA_TRACKBALL_HASH   = "MD5:b175af6e1b3d92b267ed4459bcbb5de7";
    static final String NEXUS_ONE_HASH         = "MD5:7d6cfc4f88c91801ebb7340d8a7f7e6e";
    static final String NEXUS_ONE_PLUGGED_HASH = "MD5:730700c2dec77d0dc439bad52f4b6a1c";

    @Rule public final TempSdkManager sdkManager =
            new TempSdkManager("sdk_" + getClass().getSimpleName());

    private DeviceManager dm;

    @Before
    public void setUp() {
        dm = createDeviceManager();
    }

    private DeviceManager createDeviceManager() {
        NoErrorsOrWarningsLogger log = new NoErrorsOrWarningsLogger();
        AndroidSdkHandler sdkHandler = sdkManager.getSdkHandler();
        return DeviceManager.createInstance(sdkHandler, log);
    }

    @Test
    public final void testGetHardwareProperties() {
        final Device pixelDevice = dm.getDevice("pixel", "Google");

        Map<String, String> devProperties = HardwareProperties.getHardwareProperties(pixelDevice);
        assertThat(devProperties.get("hw.lcd.density")).isEqualTo("420");
        assertThat(devProperties.get("hw.lcd.width")).isEqualTo("1080");
        assertThat(devProperties.get("hw.ramSize")).isEqualTo("4096"); // In MB, without units
        assertThat(devProperties.get("hw.lcd.transparent")).isNull();
    }

    @Test
    public final void testGetGlassesHardwareProperties() {
        final Device glassesDevice = dm.getDevice("ai_glasses_device", "Google");

        Map<String, String> properties = HardwareProperties.getHardwareProperties(glassesDevice);
        assertThat(properties.get("hw.camera.back.orientation")).isEqualTo("0");
        assertThat(properties.get("hw.touchpad0")).isEqualTo("yes");
        assertThat(properties.get("hw.touchpad0.width")).isEqualTo("1542");
        assertThat(properties.get("hw.touchpad0.height")).isEqualTo("297");
        assertThat(properties.get("environment.width")).isEqualTo("1200");
        assertThat(properties.get("environment.height")).isEqualTo("900");
        assertThat(properties.get("hw.screen")).isEqualTo("no-touch");
        assertThat(properties.get("hw.lcd.transparent")).isEqualTo("yes");
        assertThat(properties.get("hw.ledIndicators")).isEqualTo("yes");
    }

    @Test
    public final void testGetXrGlassesHardwareProperties() {
        final Device glassesDevice = dm.getDevice("xr_glasses_device", "Google");

        Map<String, String> properties = HardwareProperties.getHardwareProperties(glassesDevice);
        assertThat(properties.get("environment.width")).isNull();
        assertThat(properties.get("environment.height")).isNull();
        assertThat(properties.get("hw.screen")).isEqualTo("no-touch");
        assertThat(properties.get("hw.lcd.transparent")).isNull();
        assertThat(properties.get("hw.dimmingLevels")).isNotEmpty();
    }

    @Test
    public void testGetFreeformHardwareProperties() {
        Device device = dm.getDevice("13.5in Freeform", "Generic");
        String settingsFile =
                HardwareProperties.getHardwareProperties(device).get(DISPLAY_SETTINGS_FILE);
        assertThat(settingsFile).isEqualTo("freeform");
    }

    @Test
    public void testGetRollableHardwareProperties() {
        Device device = dm.getDevice("7.4in Rollable", "Generic");
        assertThat(HardwareProperties.getHardwareProperties(device).get(ROLL)).isEqualTo("yes");
    }

    @Test
    public void testResizableHardwareProperties() {
        Device device = dm.getDevice("resizable", "Generic");
        assertThat(HardwareProperties.getHardwareProperties(device).get(RESIZABLE_CONFIG)).isNotEmpty();
    }

    @Test
    public void testAutomotiveDeviceProperties() {
        List<Device> automotiveDevices =
                dm.getDevices().stream().filter(Device::isAutomotive).collect(toList());
        assertThat(automotiveDevices).isNotEmpty();
        for (Device device : automotiveDevices) {
            Map<String, String> properties = HardwareProperties.getHardwareProperties(device);
            assertThat(properties).containsKey(CLUSTER_HEIGHT);
            assertThat(properties).containsKey(CLUSTER_WIDTH);
        }
    }

    @Test
    public void testAutomotiveDeviceSensors() {
        Device device = dm.getDevice("automotive_1080p_landscape", "Google");
        Map<String, String> properties = HardwareProperties.getHardwareProperties(device);

        assertThat(properties.get(HardwareProperties.HW_ACCELEROMETER)).isEqualTo("yes");
        assertThat(properties.get(HardwareProperties.HW_GYROSCOPE)).isEqualTo("yes");
        assertThat(properties.get(HardwareProperties.HW_MAGNETIC_FIELD_SENSOR)).isEqualTo("no");
        assertThat(properties.get(HardwareProperties.HW_LIGHT_SENSOR)).isEqualTo("no");
        assertThat(properties.get(HardwareProperties.HW_PRESSURE_SENSOR)).isEqualTo("no");
        assertThat(properties.get(HardwareProperties.HW_PROXIMITY_SENSOR)).isEqualTo("no");
    }

    @Test
    public void testAutomotiveDistantDeviceProperties() {
        List<Device> automotiveDistantDisplayDevices =
                dm.getDevices().stream()
                        .filter(Device::isAutomotiveDistantDisplay)
                        .collect(toList());
        assertThat(automotiveDistantDisplayDevices).isNotEmpty();
        for (Device device : automotiveDistantDisplayDevices) {
            Map<String, String> properties = HardwareProperties.getHardwareProperties(device);
            assertThat(properties).containsKey(DISTANT_DISPLAY_HEIGHT);
            assertThat(properties).containsKey(DISTANT_DISPLAY_WIDTH);
        }
    }

    @Test
    public final void testHasHardwarePropHashChanged_Generic() {
        final Device d1 = dm.getDevice("7in WSVGA (Tablet)", "Generic");

        assertThat(HardwareProperties.hasHardwarePropHashChanged(d1, "invalid"))
                .isEqualTo(WSVGA_HASH);

        assertThat(HardwareProperties.hasHardwarePropHashChanged(
                d1, WSVGA_HASH))
                .isNull();

        // change the device hardware props, this should change the hash
        d1.getDefaultHardware().setNav(Navigation.TRACKBALL);

        assertThat(HardwareProperties.hasHardwarePropHashChanged(
                d1, WSVGA_HASH))
                .isEqualTo(WSVGA_TRACKBALL_HASH);

        // change the property back, should revert its hash to the previous one
        d1.getDefaultHardware().setNav(Navigation.NONAV);

        assertThat(HardwareProperties.hasHardwarePropHashChanged(
                d1, WSVGA_HASH))
                .isNull();
    }

    @Test
    public final void testHasHardwarePropHashChanged_Oem() {
        final Device d2 = dm.getDevice("Nexus One", "Google");

        assertThat(HardwareProperties.hasHardwarePropHashChanged(d2, "invalid"))
                .isEqualTo(NEXUS_ONE_HASH);

        assertThat(HardwareProperties.hasHardwarePropHashChanged(
                d2, NEXUS_ONE_HASH))
                .isNull();

        // change the device hardware props, this should change the hash
        d2.getDefaultHardware().setChargeType(PowerType.PLUGGEDIN);

        assertThat(HardwareProperties.hasHardwarePropHashChanged(
                d2, NEXUS_ONE_HASH))
                .isEqualTo(NEXUS_ONE_PLUGGED_HASH);

        // change the property back, should revert its hash to the previous one
        d2.getDefaultHardware().setChargeType(PowerType.BATTERY);

        assertThat(HardwareProperties.hasHardwarePropHashChanged(
                d2, NEXUS_ONE_HASH))
                .isNull();
    }

    @Test
    public final void testHardwarePropHashChanged_glasses() {
        Device d = dm.getDevice("xr_glasses_device", "Google");
        String initialHash = HardwareProperties.hasHardwarePropHashChanged(d, "invalid");

        Device d2 = new Device.Builder(d).build();
        assertThat(HardwareProperties.hasHardwarePropHashChanged(d2, initialHash)).isNull();

        Device.Builder d3Builder = new Device.Builder(d);
        d3Builder.setId("new_xr_glasses");
        Device d3 = d3Builder.build();

        assertThat(HardwareProperties.hasHardwarePropHashChanged(d3, initialHash)).isNotNull();
    }

}
