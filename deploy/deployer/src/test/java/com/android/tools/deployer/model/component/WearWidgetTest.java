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
package com.android.tools.deployer.model.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.tools.deployer.model.ModelException;
import com.android.tools.deployer.model.TestLogger;
import com.android.tools.deployer.model.activate.ActivationCommandResultChecker;
import com.android.tools.deployer.model.activate.ActivationCommands;
import com.android.tools.deployer.model.activate.ActivationContext;
import com.android.tools.deployer.model.activate.AmDebugAppResultChecker;
import com.android.tools.deployer.model.activate.BroadcastResultChecker;
import com.android.tools.manifest.parser.XmlNode;
import com.android.tools.manifest.parser.components.ManifestServiceInfo;

import org.junit.Test;

import java.lang.reflect.Constructor;

public class WearWidgetTest {

    @Test
    public void testGetActivationCommands() throws ModelException {
        ManifestServiceInfo info =
                new ManifestServiceInfo(new XmlNode(), "com.example.myApp") {
                    @Override
                    public String getQualifiedName() {
                        return "com.example.services.MyWearWidget";
                    }
                };
        WearWidget widget = new WearWidget(info, "com.example.myApp", new TestLogger());

        // Test RUN mode with index 1
        ActivationCommands runCommands = widget.getActivationCommands("", AppComponent.Mode.RUN);
        assertEquals(4, runCommands.size());

        String expectedVersionCommand =
                "am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation"
                        + " version";
        String expectedProtolayoutVersionCommand =
                "dumpsys package com.google.android.wearable.protolayout.renderer";
        String expectedSetCommand =
                "am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation"
                        + " add-tile --ecn component"
                        + " com.example.myApp/com.example.services.MyWearWidget";
        String expectedShowCommandTemplate =
                "am broadcast -a com.google.android.wearable.app.DEBUG_SYSUI --es operation"
                        + " show-tile --ei index ${wear_widget_index}";
        String expectedShowCommandResolved =
                "am broadcast -a com.google.android.wearable.app.DEBUG_SYSUI --es operation"
                        + " show-tile --ei index 1";

        assertEquals(expectedVersionCommand, runCommands.get(0).getCommand());
        assertEquals(expectedProtolayoutVersionCommand, runCommands.get(1).getCommand());
        assertEquals(expectedSetCommand, runCommands.get(2).getCommand());
        assertEquals(expectedShowCommandTemplate, runCommands.get(3).getCommand());

        ActivationContext context = new ActivationContext();
        context.put("wear_widget_index", "1");
        assertEquals(expectedShowCommandResolved, runCommands.get(3).getResolvedCommand(context));

        assertEquals("Checking Wear OS Surface API version", runCommands.get(0).getStatus());
        assertEquals("Checking Protolayout Renderer version", runCommands.get(1).getStatus());
        assertEquals("Setting Wear Widget for com.example.myApp", runCommands.get(2).getStatus());
        assertEquals("Showing Wear Widget", runCommands.get(3).getStatus());

        assertTrue(runCommands.get(0).getChecker() instanceof Tile.WearDebugSurfaceVersionChecker);
        assertEquals(
                "ProtoLayoutRendererVersionChecker",
                runCommands.get(1).getChecker().getClass().getSimpleName());
        assertTrue(
                runCommands.get(2).getChecker() instanceof WearWidget.SetWearWidgetResultChecker);
        assertTrue(runCommands.get(3).getChecker() instanceof BroadcastResultChecker);

        // Test DEBUG mode with index 2
        ActivationCommands debugCommands =
                widget.getActivationCommands("", AppComponent.Mode.DEBUG);
        assertEquals(6, debugCommands.size());

        assertEquals(expectedVersionCommand, debugCommands.get(0).getCommand());
        assertTrue(
                debugCommands.get(0).getChecker() instanceof Tile.WearDebugSurfaceVersionChecker);

        assertEquals(expectedProtolayoutVersionCommand, debugCommands.get(1).getCommand());
        assertEquals(
                "ProtoLayoutRendererVersionChecker",
                debugCommands.get(1).getChecker().getClass().getSimpleName());

        assertEquals("am set-debug-app -w 'com.example.myApp'", debugCommands.get(2).getCommand());
        assertEquals("Setting debug app for com.example.myApp", debugCommands.get(2).getStatus());
        assertTrue(debugCommands.get(2).getChecker() instanceof AmDebugAppResultChecker);

        assertEquals(
                "am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation"
                        + " set-debug-app --es package 'com.example.myApp'",
                debugCommands.get(3).getCommand());
        assertEquals(
                "Setting debug app in Debug Surface for com.example.myApp",
                debugCommands.get(3).getStatus());
        assertTrue(debugCommands.get(3).getChecker() instanceof BroadcastResultChecker);

        assertEquals(expectedSetCommand, debugCommands.get(4).getCommand());

        String expectedShowCommandDebugTemplate =
                "am broadcast -a com.google.android.wearable.app.DEBUG_SYSUI --es operation"
                        + " show-tile --ei index ${wear_widget_index}";
        String expectedShowCommandDebugResolved =
                "am broadcast -a com.google.android.wearable.app.DEBUG_SYSUI --es operation"
                        + " show-tile --ei index 2";
        assertEquals(expectedShowCommandDebugTemplate, debugCommands.get(5).getCommand());

        ActivationContext debugContext = new ActivationContext();
        debugContext.put("wear_widget_index", "2");
        assertEquals(
                expectedShowCommandDebugResolved,
                debugCommands.get(5).getResolvedCommand(debugContext));
    }

    @Test
    public void testSetWearWidgetResultChecker() {
        ActivationContext context1 = new ActivationContext();
        WearWidget.SetWearWidgetResultChecker checker1 =
                new WearWidget.SetWearWidgetResultChecker(null, msg -> {}, context1);

        // Success case
        checker1.processLines(new String[] {"Broadcast completed: result=1", "Index=[5]"});
        assertEquals(ActivationCommandResultChecker.Status.SUCCESS, checker1.check());
        assertEquals(5, checker1.getIndex());
        assertEquals("5", context1.get("wear_widget_index"));

        // Broadcast failed
        ActivationContext context2 = new ActivationContext();
        WearWidget.SetWearWidgetResultChecker checker2 =
                new WearWidget.SetWearWidgetResultChecker(null, msg -> {}, context2);
        checker2.processLines(new String[] {"Broadcast completed: result=0", "Index=[5]"});
        assertEquals(ActivationCommandResultChecker.Status.ERROR, checker2.check());

        // Index not found
        ActivationContext context3 = new ActivationContext();
        WearWidget.SetWearWidgetResultChecker checker3 =
                new WearWidget.SetWearWidgetResultChecker(null, msg -> {}, context3);
        checker3.processLines(new String[] {"Broadcast completed: result=1"});
        assertEquals(ActivationCommandResultChecker.Status.SUCCESS, checker3.check());
        assertEquals(-1, checker3.getIndex());
        // Verify index is not set in the context
        assertNull(context3.get("wear_widget_index"));
    }

    @Test
    public void testIsProtolayoutVersionAtLeast() {
        assertTrue(WearWidget.isProtolayoutVersionAtLeast("1.6.1", "1.6.1"));
        assertTrue(WearWidget.isProtolayoutVersionAtLeast("1.6.2", "1.6.1"));
        assertTrue(WearWidget.isProtolayoutVersionAtLeast("1.7.0", "1.6.1"));
        assertTrue(WearWidget.isProtolayoutVersionAtLeast("2.0.0", "1.6.1"));
        assertTrue(WearWidget.isProtolayoutVersionAtLeast("1.6.1.87.907578152", "1.6.1"));

        assertFalse(WearWidget.isProtolayoutVersionAtLeast("1.6.1-alpha01", "1.6.1"));
        assertFalse(WearWidget.isProtolayoutVersionAtLeast("1.6.0", "1.6.1"));
        assertFalse(WearWidget.isProtolayoutVersionAtLeast("1.5.9.999", "1.6.1"));
        assertFalse(WearWidget.isProtolayoutVersionAtLeast("1.0.0", "1.6.1"));
        assertFalse(WearWidget.isProtolayoutVersionAtLeast("0.9.9", "1.6.1"));
    }

    @Test
    public void testProtoLayoutRendererVersionChecker() throws Exception {
        // Instantiate the private static class ProtoLayoutRendererVersionChecker using reflection
        Class<?> checkerClass =
                Class.forName(
                        "com.android.tools.deployer.model.component.WearWidget$ProtoLayoutRendererVersionChecker");
        Constructor<?> constructor =
                checkerClass.getDeclaredConstructor(
                        com.android.utils.ILogger.class, ActivationContext.class);
        constructor.setAccessible(true);

        // Case 1: Success with versionName present and correct version
        ActivationContext context1 = new ActivationContext();
        ActivationCommandResultChecker checker1 =
                (ActivationCommandResultChecker)
                        constructor.newInstance(new TestLogger(), context1);
        checker1.processLines(
                new String[] {
                    "Activity Resolver Table:", "  versionName=1.6.1", "  splits=[base]"
                });
        assertEquals(ActivationCommandResultChecker.Status.SUCCESS, checker1.check());

        // Case 2: Success with a higher version
        ActivationContext context2 = new ActivationContext();
        ActivationCommandResultChecker checker2 =
                (ActivationCommandResultChecker)
                        constructor.newInstance(new TestLogger(), context2);
        checker2.processLines(
                new String[] {
                    "Activity Resolver Table:", "  versionName=1.7.0", "  splits=[base]"
                });
        assertEquals(ActivationCommandResultChecker.Status.SUCCESS, checker2.check());

        // Case 3: Error with too low version
        ActivationContext context3 = new ActivationContext();
        ActivationCommandResultChecker checker3 =
                (ActivationCommandResultChecker)
                        constructor.newInstance(new TestLogger(), context3);
        checker3.processLines(
                new String[] {
                    "Activity Resolver Table:", "  versionName=1.6.0", "  splits=[base]"
                });
        assertEquals(ActivationCommandResultChecker.Status.ERROR, checker3.check());

        // Case 4: Error with empty or missing versionName
        ActivationContext context4 = new ActivationContext();
        ActivationCommandResultChecker checker4 =
                (ActivationCommandResultChecker)
                        constructor.newInstance(new TestLogger(), context4);
        checker4.processLines(new String[] {"Activity Resolver Table:", "  splits=[base]"});
        assertEquals(ActivationCommandResultChecker.Status.ERROR, checker4.check());
    }
}
