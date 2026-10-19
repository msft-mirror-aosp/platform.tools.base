/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.ninepatch;

import junit.framework.TestCase;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class NinePatchTest extends TestCase {

    private NinePatch mPatch;

    @Override
    protected void setUp() throws Exception {
        InputStream stream = this.getClass().getResourceAsStream("button.9.png");

        mPatch = NinePatch.load(stream, true /* is9Patch*/, false /* convert */);
    }

    public void test9PatchLoad() throws Exception {
        assertNotNull(mPatch);
    }

    public void test9PatchMinSize() {
        int[] padding = new int[4];
        mPatch.getPadding(padding);
        assertEquals(13, padding[0]);
        assertEquals(3, padding[1]);
        assertEquals(13, padding[2]);
        assertEquals(4, padding[3]);
        assertEquals(36, mPatch.getWidth());
        assertEquals(25, mPatch.getHeight());
    }

    public void testMissingPaddingFallbackToStretchTicks() {
        // Content is 10x8 (12x10 with 1px 9-patch border).
        BufferedImage image = new BufferedImage(12, 10, BufferedImage.TYPE_INT_ARGB);
        // Top stretchable ticks at content x = [2, 7) -> left = 2, right = 10 - 7 = 3
        for (int x = 1 + 2; x < 1 + 7; x++) {
            image.setRGB(x, 0, 0xFF000000);
        }
        // Left stretchable ticks at content y = [3, 6) -> top = 3, bottom = 8 - 6 = 2
        for (int y = 1 + 3; y < 1 + 6; y++) {
            image.setRGB(0, y, 0xFF000000);
        }
        // Bottom and right padding lines are left completely transparent (omitted).

        NinePatch patch = NinePatch.load(image, true /* is9Patch */, false /* convert */);
        assertNotNull(patch);

        int[] padding = new int[4];
        patch.getPadding(padding);
        assertEquals(2, padding[0]); // left
        assertEquals(3, padding[1]); // top
        assertEquals(3, padding[2]); // right
        assertEquals(2, padding[3]); // bottom

        // Verify serialized chunk (paddingLeft, paddingRight, paddingTop, paddingBottom at offset
        // 12)
        ByteBuffer chunkBuf =
                ByteBuffer.wrap(patch.getChunk().getSerializedChunk())
                        .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(2, chunkBuf.getInt(12)); // paddingLeft
        assertEquals(3, chunkBuf.getInt(16)); // paddingRight
        assertEquals(3, chunkBuf.getInt(20)); // paddingTop
        assertEquals(2, chunkBuf.getInt(24)); // paddingBottom
    }

    public void testFullBlackPaddingTicksProduceZeroPadding() {
        // Content is 10x8 (12x10 with 1px 9-patch border).
        BufferedImage image = new BufferedImage(12, 10, BufferedImage.TYPE_INT_ARGB);
        // Top stretchable ticks at content x = [2, 7)
        for (int x = 1 + 2; x < 1 + 7; x++) {
            image.setRGB(x, 0, 0xFF000000);
        }
        // Left stretchable ticks at content y = [3, 6)
        for (int y = 1 + 3; y < 1 + 6; y++) {
            image.setRGB(0, y, 0xFF000000);
        }
        // Explicit full-width bottom and full-height right black padding lines -> 0 padding
        for (int x = 1; x <= 10; x++) {
            image.setRGB(x, 9, 0xFF000000);
        }
        for (int y = 1; y <= 8; y++) {
            image.setRGB(11, y, 0xFF000000);
        }

        NinePatch patch = NinePatch.load(image, true /* is9Patch */, false /* convert */);
        assertNotNull(patch);

        int[] padding = new int[4];
        patch.getPadding(padding);
        assertEquals(0, padding[0]);
        assertEquals(0, padding[1]);
        assertEquals(0, padding[2]);
        assertEquals(0, padding[3]);
    }

    public void testMultipleStretchTicksTouchingEdgesPaddingFallback() {
        // Content is 10x8 (12x10 with 1px 9-patch border).
        BufferedImage image = new BufferedImage(12, 10, BufferedImage.TYPE_INT_ARGB);
        // Top has two stretchable segments: [0, 3) and [6, 10) -> fixed is only [3, 6) in the
        // middle.
        // Since stretchable starts at 0 and ends at 10, fallback horizontal padding must be (0, 0).
        for (int x = 1; x < 1 + 3; x++) {
            image.setRGB(x, 0, 0xFF000000);
        }
        for (int x = 1 + 6; x < 1 + 10; x++) {
            image.setRGB(x, 0, 0xFF000000);
        }
        // Left has two stretchable segments: [0, 2) and [4, 7) -> fixed is [2, 4) and [7, 8).
        // Since stretchable starts at 0 and ends at 7, fallback vertical padding must be (0, 1).
        for (int y = 1; y < 1 + 2; y++) {
            image.setRGB(0, y, 0xFF000000);
        }
        for (int y = 1 + 4; y < 1 + 7; y++) {
            image.setRGB(0, y, 0xFF000000);
        }

        NinePatch patch = NinePatch.load(image, true /* is9Patch */, false /* convert */);
        assertNotNull(patch);

        int[] padding = new int[4];
        patch.getPadding(padding);
        assertEquals(0, padding[0]); // left
        assertEquals(0, padding[1]); // top
        assertEquals(0, padding[2]); // right
        assertEquals(1, padding[3]); // bottom

        // Single stretchable segment touching the right/bottom edge only:
        // Top stretchable [2, 10) -> fixed is [0, 2) -> horizontal padding (2, 0).
        // Left stretchable [3, 8) -> fixed is [0, 3) -> vertical padding (3, 0).
        BufferedImage rightBottomTouching = new BufferedImage(12, 10, BufferedImage.TYPE_INT_ARGB);
        for (int x = 1 + 2; x < 1 + 10; x++) {
            rightBottomTouching.setRGB(x, 0, 0xFF000000);
        }
        for (int y = 1 + 3; y < 1 + 8; y++) {
            rightBottomTouching.setRGB(0, y, 0xFF000000);
        }
        NinePatch rightBottomPatch =
                NinePatch.load(rightBottomTouching, true /* is9Patch */, false /* convert */);
        assertNotNull(rightBottomPatch);
        rightBottomPatch.getPadding(padding);
        assertEquals(2, padding[0]); // left
        assertEquals(3, padding[1]); // top
        assertEquals(0, padding[2]); // right
        assertEquals(0, padding[3]); // bottom
    }
}
