/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.tools.deployer.model.component;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.ShellCommandUnresponsiveException;
import com.android.ddmlib.TimeoutException;
import com.android.testutils.TestUtils;
import com.android.tools.deployer.common.DeployerIShellOutputReceiver;
import com.android.tools.deployer.common.DeployerNullOutputReceiver;
import com.android.tools.deployer.common.DeviceHolder;
import com.android.tools.deployer.model.ModelException;
import com.android.tools.deployer.model.TestLogger;
import com.android.tools.deployer.model.activate.ActivationCommand;
import com.android.tools.deployer.model.activate.ActivationCommandResultChecker;
import com.android.tools.deployer.model.activate.ActivationCommands;
import com.android.tools.deployer.model.activate.AmStartResultChecker;
import com.android.tools.deployer.modelv1.component.ActivityV1;
import com.android.tools.manifest.parser.ManifestInfo;
import com.android.tools.manifest.parser.XmlNode;
import com.android.tools.manifest.parser.components.ManifestActivityInfo;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.TimeUnit;

public class ActivityTest {

    @Test
    public void testGetActivationCommands() throws ModelException {
        ManifestActivityInfo info =
                new ManifestActivityInfo(new XmlNode(), "com.example.myApp") {
                    @Override
                    public String getQualifiedName() {
                        return "com.example.myApp.MainActivity";
                    }
                };
        Activity activity = new Activity(info, "com.example.myApp", new TestLogger());

        // Test RUN mode
        ActivationCommands runCommands = activity.getActivationCommands("", AppComponent.Mode.RUN);
        Assert.assertEquals(1, runCommands.size());
        ActivationCommand runCommand = runCommands.get(0);
        Assert.assertEquals(
                "am start -n com.example.myApp/com.example.myApp.MainActivity -a"
                        + " android.intent.action.MAIN -c android.intent.category.LAUNCHER",
                runCommand.getCommand());
        Assert.assertEquals("Launching Activity for com.example.myApp", runCommand.getStatus());
        Assert.assertTrue(runCommand.getChecker() instanceof AmStartResultChecker);
        Assert.assertEquals(
                ActivationCommandResultChecker.Status.SUCCESS, runCommand.getChecker().check());

        // Test DEBUG mode
        ActivationCommands debugCommands =
                activity.getActivationCommands("", AppComponent.Mode.DEBUG);
        Assert.assertEquals(1, debugCommands.size());
        ActivationCommand debugCommand = debugCommands.get(0);
        Assert.assertEquals(
                "am start -n com.example.myApp/com.example.myApp.MainActivity -a"
                        + " android.intent.action.MAIN -c android.intent.category.LAUNCHER -D",
                debugCommand.getCommand());
        Assert.assertEquals("Launching Activity for com.example.myApp", debugCommand.getStatus());
        Assert.assertTrue(debugCommand.getChecker() instanceof AmStartResultChecker);
        Assert.assertEquals(
                ActivationCommandResultChecker.Status.SUCCESS, debugCommand.getChecker().check());
    }

    @Test
    public void testFlags()
            throws ModelException,
                    ShellCommandUnresponsiveException,
                    AdbCommandRejectedException,
                    IOException,
                    TimeoutException {
        DeviceHolder deviceHolder = Mockito.mock(DeviceHolder.class);
        Mockito.when(deviceHolder.getSerialNumber()).thenReturn("1234");
        ManifestActivityInfo info =
                new ManifestActivityInfo(new XmlNode(), "com.example.myApp") {
                    @Override
                    public String getQualifiedName() {
                        return "com.example.myApp.MainActivity";
                    }
                };
        ActivityV1 activity = new ActivityV1(info, "com.example.myApp", new TestLogger());
        activity.activate(
                " --user 123",
                AppComponent.Mode.DEBUG,
                new DeployerNullOutputReceiver(),
                deviceHolder);

        String expectedCommand =
                "am start -n com.example.myApp/com.example.myApp.MainActivity -a"
                    + " android.intent.action.MAIN -c android.intent.category.LAUNCHER -D --user"
                    + " 123";

        Mockito.verify(deviceHolder, Mockito.times(1))
                .executeShellCommand(
                        eq(expectedCommand),
                        any(DeployerIShellOutputReceiver.class),
                        eq(15L),
                        eq(TimeUnit.SECONDS));
    }

    @Test
    public void useCategoryFromManifest() throws Exception {
        DeviceHolder deviceHolder = Mockito.mock(DeviceHolder.class);
        Mockito.when(deviceHolder.getSerialNumber()).thenReturn("1234");
        URL url =
                TestUtils.resolveWorkspacePath(
                                "tools/base/deploy/deployer/src/test/resource/manifestWithCategory/AndroidManifest.bxml")
                        .toUri()
                        .toURL();
        Assert.assertNotNull(url);
        try (InputStream input = url.openStream()) {
            ManifestInfo manifestInfo = ManifestInfo.parseBinaryFromStream(input);
            ActivityV1 activity =
                    new ActivityV1(
                            manifestInfo.activities().get(0),
                            "com.example.myApp",
                            new TestLogger());
            activity.activate(
                    "", AppComponent.Mode.RUN, new DeployerNullOutputReceiver(), deviceHolder);

            String expectedCommand =
                    "am start -n com.example.myApp/com.example.tv_app.MainActivity -a"
                            + " android.intent.action.MAIN -c"
                            + " android.intent.category.LEANBACK_LAUNCHER";

            Mockito.verify(deviceHolder, Mockito.times(1))
                    .executeShellCommand(
                            eq(expectedCommand),
                            any(DeployerIShellOutputReceiver.class),
                            eq(15L),
                            eq(TimeUnit.SECONDS));
        }
    }
}
