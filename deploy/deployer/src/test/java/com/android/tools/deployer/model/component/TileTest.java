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
import com.android.tools.deployer.model.activate.ActivationCommandResultChecker;
import com.android.tools.deployer.model.activate.ActivationCommands;
import com.android.tools.deployer.model.activate.ActivationContext;
import com.android.tools.deployer.model.activate.AmDebugAppResultChecker;
import com.android.tools.deployer.model.activate.BroadcastResultChecker;
import com.android.tools.manifest.parser.XmlNode;
import com.android.tools.manifest.parser.components.ManifestServiceInfo;

import org.junit.Assert;
import org.junit.Test;

public class TileTest {

    @Test
    public void testGetActivationCommands() throws ModelException {
        ManifestServiceInfo info =
                new ManifestServiceInfo(new XmlNode(), "com.example.myApp") {
                    @Override
                    public String getQualifiedName() {
                        return "com.example.services.Tile";
                    }
                };
        Tile tile = new Tile(info, "com.example.myApp", new TestLogger());

        // Test RUN mode with index 1
        ActivationCommands runCommands = tile.getActivationCommands("1", AppComponent.Mode.RUN);
        Assert.assertEquals(3, runCommands.size());

        String expectedVersionCommand =
                "am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation"
                        + " version";
        String expectedSetCommand =
                "am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation"
                        + " 'add-tile' --ecn component com.example.myApp/com.example.services.Tile";
        String expectedShowCommandTemplate =
                "am broadcast -a com.google.android.wearable.app.DEBUG_SYSUI --es operation"
                        + " show-tile --ei index ${tile_index}";
        String expectedShowCommandResolved =
                "am broadcast -a com.google.android.wearable.app.DEBUG_SYSUI --es operation"
                        + " show-tile --ei index 1";

        Assert.assertEquals(expectedVersionCommand, runCommands.get(0).getCommand());
        Assert.assertEquals(expectedSetCommand, runCommands.get(1).getCommand());
        Assert.assertEquals(expectedShowCommandTemplate, runCommands.get(2).getCommand());

        ActivationContext context = new ActivationContext();
        context.put("tile_index", "1");
        Assert.assertEquals(
                expectedShowCommandResolved, runCommands.get(2).getResolvedCommand(context));

        Assert.assertEquals("Checking Wear OS Surface API version", runCommands.get(0).getStatus());
        Assert.assertEquals("Setting Tile for com.example.myApp", runCommands.get(1).getStatus());
        Assert.assertEquals("Showing Tile", runCommands.get(2).getStatus());

        Assert.assertTrue(
                runCommands.get(0).getChecker() instanceof Tile.WearDebugSurfaceVersionChecker);
        Assert.assertTrue(runCommands.get(1).getChecker() instanceof BroadcastResultChecker);
        Assert.assertTrue(runCommands.get(2).getChecker() instanceof BroadcastResultChecker);

        // Test DEBUG mode with index 2
        ActivationCommands debugCommands = tile.getActivationCommands("2", AppComponent.Mode.DEBUG);
        Assert.assertEquals(5, debugCommands.size());

        Assert.assertEquals(expectedVersionCommand, debugCommands.get(0).getCommand());
        Assert.assertTrue(
                debugCommands.get(0).getChecker() instanceof Tile.WearDebugSurfaceVersionChecker);

        Assert.assertEquals(
                "am set-debug-app -w 'com.example.myApp'", debugCommands.get(1).getCommand());
        Assert.assertEquals(
                "Setting debug app for com.example.myApp", debugCommands.get(1).getStatus());
        Assert.assertTrue(debugCommands.get(1).getChecker() instanceof AmDebugAppResultChecker);

        Assert.assertEquals(
                "am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation"
                        + " set-debug-app --es package 'com.example.myApp'",
                debugCommands.get(2).getCommand());
        Assert.assertEquals(
                "Setting debug app in Debug Surface for com.example.myApp",
                debugCommands.get(2).getStatus());
        Assert.assertTrue(debugCommands.get(2).getChecker() instanceof BroadcastResultChecker);

        Assert.assertEquals(expectedSetCommand, debugCommands.get(3).getCommand());

        String expectedShowCommandDebugTemplate =
                "am broadcast -a com.google.android.wearable.app.DEBUG_SYSUI --es operation"
                        + " show-tile --ei index ${tile_index}";
        String expectedShowCommandDebugResolved =
                "am broadcast -a com.google.android.wearable.app.DEBUG_SYSUI --es operation"
                        + " show-tile --ei index 2";
        Assert.assertEquals(expectedShowCommandDebugTemplate, debugCommands.get(4).getCommand());

        ActivationContext debugContext = new ActivationContext();
        debugContext.put("tile_index", "2");
        Assert.assertEquals(
                expectedShowCommandDebugResolved,
                debugCommands.get(4).getResolvedCommand(debugContext));
    }

    @Test
    public void testWearDebugSurfaceVersionChecker() {
        Tile.WearDebugSurfaceVersionChecker checker =
                new Tile.WearDebugSurfaceVersionChecker(new TestLogger(), new ActivationContext());

        // Success case
        checker.processLines(new String[] {"Broadcast completed: result=1, data=\"3\""});
        Assert.assertEquals(ActivationCommandResultChecker.Status.SUCCESS, checker.check());

        // Version too low
        checker =
                new Tile.WearDebugSurfaceVersionChecker(new TestLogger(), new ActivationContext());
        checker.processLines(new String[] {"Broadcast completed: result=1, data=\"1\""});
        Assert.assertEquals(ActivationCommandResultChecker.Status.ERROR, checker.check());

        // Broadcast failed
        checker =
                new Tile.WearDebugSurfaceVersionChecker(new TestLogger(), new ActivationContext());
        checker.processLines(new String[] {"Broadcast completed: result=0, data=\"3\""});
        Assert.assertEquals(ActivationCommandResultChecker.Status.ERROR, checker.check());
    }

    @Test
    public void testSetWatchTileResultChecker() {
        ActivationContext context = new ActivationContext();
        Tile.SetWatchTileResultChecker checker =
                new Tile.SetWatchTileResultChecker(null, msg -> {}, context);

        // Success case
        checker.processLines(new String[] {"Broadcast completed: result=1", "Index=[5]"});
        Assert.assertEquals(ActivationCommandResultChecker.Status.SUCCESS, checker.check());
        Assert.assertEquals(5, checker.getIndex());
        Assert.assertEquals("5", context.get("tile_index"));

        // Broadcast failed
        context = new ActivationContext();
        checker = new Tile.SetWatchTileResultChecker(null, msg -> {}, context);
        checker.processLines(new String[] {"Broadcast completed: result=0", "Index=[5]"});
        Assert.assertEquals(ActivationCommandResultChecker.Status.ERROR, checker.check());

        // Index not found
        context = new ActivationContext();
        checker = new Tile.SetWatchTileResultChecker(null, msg -> {}, context);
        checker.processLines(new String[] {"Broadcast completed: result=1"});
        Assert.assertEquals(ActivationCommandResultChecker.Status.SUCCESS, checker.check());
        Assert.assertEquals(-1, checker.getIndex());
    }
}
