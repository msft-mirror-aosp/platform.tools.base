/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.sdklib;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/** Unit tests for {@link AndroidVersion}. */
public class AndroidVersionTest {

    @Test
    public void unspecifiedExtension() {
        // Extension levels for an API level are greater than versions where the extension level is
        // not given.
        AndroidVersion v = new AndroidVersion(30, null);
        assertFalse(v.isPreview());
        assertNull(v.getCodename());
        assertEquals("30", v.getApiStringWithoutExtension());
        assertEquals("30", v.getApiStringWithExtension());

        assertTrue(v.isAtLeast(0));
        assertTrue(v.isAtLeast(14));
        assertFalse(v.isAtLeast(31));
    }

    @Test
    public void withBaseExtensionLevel() {
        assertThat(new AndroidVersion(30).withBaseExtensionLevel())
                .isEqualTo(new AndroidVersion(30, null, null, true));
        assertThat(new AndroidVersion(33).withBaseExtensionLevel())
                .isEqualTo(new AndroidVersion(33, null, 3, true));
        assertThat(new AndroidVersion(33).withBaseExtensionLevel().getExtensionLevel())
                .isEqualTo(3);
        assertThat(new AndroidVersion(36, 1).withBaseExtensionLevel())
                .isEqualTo(new AndroidVersion(36, 1, null, null, true));
    }

    @Test
    public void withExtensionLevel() {
        assertThat(new AndroidVersion(30).withExtensionLevel(5))
                .isEqualTo(new AndroidVersion(30, null, 5, false));
        assertThat(new AndroidVersion(33).withExtensionLevel(3))
                .isEqualTo(new AndroidVersion(33, null, 3, true));
        assertThat(new AndroidVersion(33).withExtensionLevel(4))
                .isEqualTo(new AndroidVersion(33, null, 4, false));
        assertThat(new AndroidVersion(36, 1).withExtensionLevel(99))
                .isEqualTo(new AndroidVersion(36, 1, null, 99, false));
    }

    @Test
    public void nonBaseExtension() {
        AndroidVersion v = new AndroidVersion(30, null, 4, false);
        assertFalse(v.isPreview());
        assertNull(v.getCodename());
        assertEquals("30", v.getApiStringWithoutExtension());
        assertEquals("30-ext4", v.getApiStringWithExtension());

        assertTrue(v.isAtLeast(0));
        assertTrue(v.isAtLeast(14));
        assertFalse(v.isAtLeast(31));

        assertThat(v).isGreaterThan(new AndroidVersion(29, "codename"));
        assertThat(v).isLessThan(new AndroidVersion(30, "codename"));
        assertEquals("API 30, extension level 4", v.toString());

        assertNotEquals(
                new AndroidVersion(30, null, 6, false).hashCode(),
                new AndroidVersion(30, null, 4, false).hashCode());
    }

    @Test
    public void extensionLevelOrdering() {
        // API 33 with default extension level
        AndroidVersion api33base = new AndroidVersion(33, null);
        // API 33 with explicit base extension level
        AndroidVersion api33baseExt3 = new AndroidVersion(33, null, 3, true);
        // Invalid version specification: this should not occur in practice
        AndroidVersion api33ext1 = new AndroidVersion(33, null, 1, false);
        // API 33 with non-base extension level
        AndroidVersion api33ext4 = new AndroidVersion(33, null, 4, false);
        // Previews for what will likely become API 34 (indicated by API 33 + codename)
        AndroidVersion api34Preview = new AndroidVersion(33, "U");
        AndroidVersion api34PreviewWithExtension = new AndroidVersion(33, "U", 4, false);

        assertThat(api33base).isEquivalentAccordingToCompareTo(api33baseExt3);
        assertThat(api33base).isLessThan(api33ext1);
        // This is unintuitive, but necessary given that api33base == api33baseExt3.
        // If we instead consider extension levels in the case when isBaseExtension differs between
        // the operands, we have apiBase < api33ext1 < api33baseExt3, yet apiBase == api33baseExt3.
        // A non-transitive comparator could cause sorting to fail. It is more important to properly
        // handle the case of an unspecified extension level than the case of an extension level
        // that is explicitly given and incorrect.
        assertThat(api33baseExt3).isLessThan(api33ext1);
        assertThat(api33baseExt3).isLessThan(api33ext4);
        assertThat(api33ext1).isLessThan(api33ext4);
        assertThat(api33ext4).isLessThan(api34Preview);
        assertThat(api33ext4).isLessThan(api34PreviewWithExtension);
        assertThat(api34Preview).isLessThan(api34PreviewWithExtension);
    }

    @Test
    public void minorLevelOrdering() {
        AndroidVersion api36 = new AndroidVersion(36);
        AndroidVersion api36Baklava = new AndroidVersion(36, "Baklava");
        AndroidVersion api36_1 = new AndroidVersion(36, 1);
        AndroidVersion api36Baklava1 = new AndroidVersion(36, 1, "Baklava", null, true);
        AndroidVersion api36_2 = new AndroidVersion(36, 2);
        AndroidVersion api37 = new AndroidVersion(37);

        assertThat(api36).isLessThan(api36Baklava);
        assertThat(api36Baklava).isLessThan(api36_1);
        assertThat(api36_1).isLessThan(api36Baklava1);
        assertThat(api36Baklava1).isLessThan(api36_2);
        assertThat(api36_2).isLessThan(api37);
    }

    /**
     * For AndroidVersions that have isBaseExtension set, we don't use the extension level for
     * equivalence or comparison.
     */
    @Test
    public void baseExtensionEquivalence() {
        AndroidVersion base = new AndroidVersion(10);
        AndroidVersion baseWithExtensionInfo = new AndroidVersion(10, null, 3, true);
        assertEquals(base, baseWithExtensionInfo);
        assertEquals(base.hashCode(), baseWithExtensionInfo.hashCode());

        assertThat(base.compareTo(baseWithExtensionInfo)).isEqualTo(0);
    }

    @Test
    public void testAndroidVersion() {
        AndroidVersion v = new AndroidVersion(1, "  CODENAME   ");
        assertEquals(1, v.getApiLevel());
        assertEquals("CODENAME", v.getApiStringWithExtension());
        assertEquals("CODENAME", v.getApiStringWithoutExtension());
        assertTrue(v.isPreview());
        assertEquals("CODENAME", v.getCodename());
        assertEquals("API 1, CODENAME preview", v.toString());

        v = new AndroidVersion(15, "REL");
        assertEquals(15, v.getApiLevel());
        assertEquals("15", v.getApiString());
        assertFalse(v.isPreview());
        assertNull(v.getCodename());
        assertEquals(new AndroidVersion(15), v);
        assertEquals("API 15", v.toString());

        v = new AndroidVersion(15, null);
        assertEquals(15, v.getApiLevel());
        assertEquals("15", v.getApiStringWithExtension());
        assertEquals("15", v.getApiStringWithoutExtension());
        assertFalse(v.isPreview());
        assertNull(v.getCodename());
        assertEquals(new AndroidVersion(15), v);
        assertEquals("API 15", v.toString());

        // An empty codename is like a null codename
        v = new AndroidVersion(15, "   ");
        assertFalse(v.isPreview());
        assertNull(v.getCodename());
        assertEquals("15", v.getApiStringWithExtension());

        v = new AndroidVersion(15, "");
        assertFalse(v.isPreview());
        assertNull(v.getCodename());
        assertEquals("15", v.getApiStringWithExtension());

        assertTrue(v.isAtLeast(0));
        assertTrue(v.isAtLeast(14));
        assertTrue(v.isAtLeast(15));
        assertFalse(v.isAtLeast(16));
        assertFalse(v.isAtLeast(Integer.MAX_VALUE));
    }

    @Test
    public void getApiStringWithExtension() {
        assertEquals("33", new AndroidVersion(33, 0).getApiStringWithExtension());
        assertEquals("36.0", new AndroidVersion(36, 0, null, 17, true).getApiStringWithExtension());
        assertEquals(
                "Baklava",
                new AndroidVersion(35, 0, "Baklava", 17, true).getApiStringWithExtension());
        assertEquals(
                "36.0-ext41",
                new AndroidVersion(36, 0, null, 41, false).getApiStringWithExtension());
        assertEquals(
                "Baklava.1",
                new AndroidVersion(36, 0, "Baklava.1", 41, false).getApiStringWithExtension());
        assertEquals("Tiramisu", new AndroidVersion(32, "Tiramisu").getApiStringWithExtension());
        assertEquals(
                "VanillaIceCream",
                new AndroidVersion(34, 0, "VanillaIceCream", 12, false)
                        .getApiStringWithExtension());
    }

    @Test
    public void getApiStringWithoutExtension() {
        assertEquals("33", new AndroidVersion(33, 0).getApiStringWithoutExtension());
        assertEquals(
                "36.0", new AndroidVersion(36, 0, null, 17, true).getApiStringWithoutExtension());
        assertEquals(
                "Baklava",
                new AndroidVersion(35, 0, "Baklava", 17, true).getApiStringWithoutExtension());
        assertEquals(
                "36.0", new AndroidVersion(36, 0, null, 41, false).getApiStringWithoutExtension());
        assertEquals(
                "Baklava.1",
                new AndroidVersion(36, 0, "Baklava.1", 41, false).getApiStringWithoutExtension());
        assertEquals("Tiramisu", new AndroidVersion(32, "Tiramisu").getApiStringWithoutExtension());
        assertEquals(
                "VanillaIceCream",
                new AndroidVersion(34, 0, "VanillaIceCream", 12, false)
                        .getApiStringWithoutExtension());
    }

    @Test
    public void fromString_apiLevel() {
        // A valid integer is considered an API level
        AndroidVersion v = AndroidVersion.fromString("15");
        assertEquals(15, v.getApiLevel());
        assertEquals("15", v.getApiStringWithExtension());
        assertFalse(v.isPreview());
        assertNull(v.getCodename());
        assertEquals(new AndroidVersion(15), v);
        assertEquals("API 15", v.toString());
    }

    @Test
    public void fromString_apiMinorLevel() {
        AndroidVersion v = AndroidVersion.fromString("36.1");
        assertEquals(36, v.getApiLevel());
        assertEquals(1, v.getApiMinorLevel());
        assertEquals("36.1", v.getApiStringWithExtension());
        assertFalse(v.isPreview());
        assertNull(v.getCodename());
        assertEquals(new AndroidVersion(36, 1), v);
        assertEquals("API 36.1", v.toString());
    }

    @Test
    public void fromString_codename() {
        // A valid name is considered a codename
        AndroidVersion v = AndroidVersion.fromString("CODE_NAME");
        assertEquals("CODE_NAME", v.getApiStringWithExtension());
        assertTrue(v.isPreview());
        assertTrue(v.isBaseExtension());
        assertEquals("CODE_NAME", v.getCodename());
        assertEquals(v, AndroidVersion.fromString("CODE_NAME"));
        assertEquals(0, v.getApiLevel());
        assertEquals("API 0, CODE_NAME preview", v.toString());

        // invalid code name should fail
        for (String s : new String[] {"REL", "code.name", "10val", ""}) {
            try {
                //noinspection ResultOfObjectAllocationIgnored
                AndroidVersion.fromString(s);
                fail("Invalid code name '" + s + "': Expected to fail. Actual: did not fail.");
            } catch (IllegalArgumentException e) {
                assertEquals("Invalid Android API or codename " + s, e.getMessage());
            }
        }
    }

    @Test
    public void platformHashString_base() {
        assertEquals("android-35", new AndroidVersion(35, 0).getPlatformHashString());
        assertEquals("android-36", new AndroidVersion(36, 0).getPlatformHashString());
        assertEquals("android-36.1", new AndroidVersion(36, 1).getPlatformHashString());
        assertEquals("android-37.0", new AndroidVersion(37, 0).getPlatformHashString());
    }

    @Test
    public void platformHashString_previews() {
        assertEquals(
                "android-Baklava",
                new AndroidVersion(35, 0, "Baklava", null, true).getPlatformHashString());
        assertEquals(
                "android-Baklava.1",
                new AndroidVersion(36, 0, "Baklava.1", null, true).getPlatformHashString());
        assertEquals(
                "android-Baklava.2",
                new AndroidVersion(36, 1, "Baklava.2", null, true).getPlatformHashString());
        assertEquals(
                "android-DEV",
                new AndroidVersion(37, 0, "DEV", null, true).getPlatformHashString());
    }

    @Test
    public void platformHashString_extensions() {
        assertEquals(
                "android-35-ext99",
                new AndroidVersion(35, 0).withExtensionLevel(99).getPlatformHashString());
        assertEquals(
                "android-36-ext99",
                new AndroidVersion(36, 0).withExtensionLevel(99).getPlatformHashString());
        assertEquals(
                "android-36.1-ext99",
                new AndroidVersion(36, 1).withExtensionLevel(99).getPlatformHashString());
        assertEquals(
                "android-37.0-ext99",
                new AndroidVersion(37, 0).withExtensionLevel(99).getPlatformHashString());
    }

    @Test
    public void platformHashString_previewExtensions() {
        assertEquals(
                "android-DEV", new AndroidVersion(35, 0, "DEV", 99, false).getPlatformHashString());
        assertEquals(
                "android-DEV", new AndroidVersion(36, 0, "DEV", 99, false).getPlatformHashString());
        assertEquals(
                "android-DEV", new AndroidVersion(36, 1, "DEV", 99, false).getPlatformHashString());
        assertEquals(
                "android-DEV", new AndroidVersion(37, 0, "DEV", 99, false).getPlatformHashString());
    }

    @Test
    public void testGetFeatureLevel() {
        assertEquals(1, AndroidVersion.DEFAULT.getFeatureLevel());

        assertEquals(5, new AndroidVersion(5, null).getApiLevel());
        assertEquals(5, new AndroidVersion(5, null).getFeatureLevel());

        assertEquals(5, new AndroidVersion(5, "codename").getApiLevel());
        assertEquals(6, new AndroidVersion(5, "codename").getFeatureLevel());
    }
}
