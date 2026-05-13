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

import com.android.tools.deployer.model.ModelException;
import com.android.tools.deployer.model.TestLogger;
import com.android.tools.deployer.model.activate.ActivationCommands;
import com.android.tools.deployer.model.activate.AmDebugAppResultChecker;
import com.android.tools.deployer.model.activate.BroadcastResultChecker;
import com.android.tools.manifest.parser.XmlNode;
import com.android.tools.manifest.parser.components.ManifestServiceInfo;

import org.junit.Assert;
import org.junit.Test;

public class WatchFaceTest {

    @Test
    public void testGetActivationCommands() throws ModelException {
        ManifestServiceInfo info =
                new ManifestServiceInfo(new XmlNode(), "com.example.myApp") {
                    @Override
                    public String getQualifiedName() {
                        return "com.example.services.WatchFace";
                    }
                };
        WatchFace watchFace = new WatchFace(info, "com.example.myApp", new TestLogger());

        // Test RUN mode
        ActivationCommands runCommands = watchFace.getActivationCommands("", AppComponent.Mode.RUN);
        Assert.assertEquals(2, runCommands.size());

        String expectedSetCommand =
                "am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation"
                        + " set-watchface --ecn component"
                        + " com.example.myApp/com.example.services.WatchFace";
        String expectedShowCommand =
                "am broadcast -a com.google.android.wearable.app.DEBUG_SYSUI --es operation"
                        + " show-watchface";

        Assert.assertEquals(expectedSetCommand, runCommands.get(0).getCommand());
        Assert.assertEquals(expectedShowCommand, runCommands.get(1).getCommand());

        Assert.assertEquals(
                "Setting Watch Face for com.example.myApp", runCommands.get(0).getStatus());
        Assert.assertEquals("Showing Watch Face", runCommands.get(1).getStatus());

        Assert.assertTrue(runCommands.get(0).getChecker() instanceof BroadcastResultChecker);
        Assert.assertTrue(runCommands.get(1).getChecker() instanceof BroadcastResultChecker);

        // Test DEBUG mode
        ActivationCommands debugCommands =
                watchFace.getActivationCommands("", AppComponent.Mode.DEBUG);
        Assert.assertEquals(3, debugCommands.size());

        Assert.assertEquals(
                "am set-debug-app -w 'com.example.myApp'", debugCommands.get(0).getCommand());
        Assert.assertEquals(
                "Setting debug app for com.example.myApp", debugCommands.get(0).getStatus());
        Assert.assertTrue(debugCommands.get(0).getChecker() instanceof AmDebugAppResultChecker);

        Assert.assertEquals(expectedSetCommand, debugCommands.get(1).getCommand());
        Assert.assertEquals(expectedShowCommand, debugCommands.get(2).getCommand());
    }
}
