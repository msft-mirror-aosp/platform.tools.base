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

package com.android.tools.arttooling;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Exercises ArtTooling behavior that holds before the native engine is attached. Post-attach
 * behavior needs a real device and is covered by the live smoke tests, not here.
 */
@RunWith(RobolectricTestRunner.class)
public final class ArtToolingTest {

    @Test
    public void findInstancesBeforeAttachThrows() {
        assertThrows(IllegalStateException.class, () -> ArtTooling.findInstances(String.class));
    }

    @Test
    public void registerBeforeAttachThrows() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        ArtTooling.registerEntryHook(
                                String.class, "length()I", "owner", (t, args) -> {}));
        assertThrows(
                IllegalStateException.class,
                () ->
                        ArtTooling.registerExitHook(
                                String.class,
                                "trim()Ljava/lang/String;",
                                "owner",
                                (String v) -> v));
    }

    @Test
    public void exitDispatchWithNoHooksReturnsValueUnchanged() {
        assertThat(ArtTooling.onExit("Ljava/lang/String;->size()I", 7)).isEqualTo(7);
    }

    @Test
    public void findInstancesRejectsNullType() {
        assertThrows(NullPointerException.class, () -> ArtTooling.findInstances(null));
    }

    @Test
    public void findInstancesRejectsPrimitiveType() {
        assertThrows(IllegalArgumentException.class, () -> ArtTooling.findInstances(int.class));
    }

    @Test
    public void findInstancesRejectsObjectType() {
        assertThrows(IllegalArgumentException.class, () -> ArtTooling.findInstances(Object.class));
    }

    @Test
    public void registerEntryHookRejectsNullArguments() {
        assertThrows(
                NullPointerException.class,
                () -> ArtTooling.registerEntryHook(null, "length()I", "owner", (t, args) -> {}));
        assertThrows(
                NullPointerException.class,
                () -> ArtTooling.registerEntryHook(String.class, null, "owner", (t, args) -> {}));
        assertThrows(
                NullPointerException.class,
                () ->
                        ArtTooling.registerEntryHook(
                                String.class, "length()I", null, (t, args) -> {}));
        assertThrows(
                NullPointerException.class,
                () -> ArtTooling.registerEntryHook(String.class, "length()I", "owner", null));
    }

    @Test
    public void clearRejectsNullOwner() {
        assertThrows(NullPointerException.class, () -> ArtTooling.clear(null));
    }
}
