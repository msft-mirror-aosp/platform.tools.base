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

package android.graphics;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@SuppressWarnings("unused")
public final class Canvas {
    // This is not in AOSP. We use it for tests.
    @VisibleForTesting public List<DrawRectLog> drawRectLogs = new ArrayList<>();
    private int drawRectInvocations = 0;

    @VisibleForTesting public List<DrawTextLog> drawTextLogs = new ArrayList<>();
    private int drawTextInvocations = 0;

    private Bitmap mBitmap;

    @VisibleForTesting
    public Canvas() {}

    @VisibleForTesting
    public Canvas(Bitmap bitmap) {
        mBitmap = bitmap;
    }

    @VisibleForTesting
    public Bitmap getBitmap() {
        return mBitmap;
    }

    public void scale(float x, float y) {}

    public void drawRect(Rect rect, @NonNull Paint paint) {
        drawRectLogs.add(new DrawRectLog(drawRectInvocations, rect, paint));
        drawRectInvocations += 1;
    }

    public void drawText(@NonNull String text, float x, float y, @NonNull Paint paint) {
        drawTextLogs.add(new DrawTextLog(drawTextInvocations, text, x, y, paint));
        drawTextInvocations += 1;
    }

    public int getWidth() {
        return 0;
    }

    public int getHeight() {
        return 0;
    }

    @VisibleForTesting
    public static class DrawRectLog {
        public final Rect rect;
        public final Paint paint;
        public final int generation;

        public DrawRectLog(int generation, Rect rect, Paint paint) {
            this.generation = generation;
            this.rect = rect;
            this.paint = paint;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            DrawRectLog that = (DrawRectLog) o;
            return generation == that.generation
                    && Objects.equals(rect, that.rect)
                    && Objects.equals(paint, that.paint);
        }

        @Override
        public int hashCode() {
            return Objects.hash(rect, paint, generation);
        }
    }

    @VisibleForTesting
    public static class DrawTextLog {
        public final String text;
        public final float x;
        public final float y;
        public final Paint paint;
        public final int generation;

        public DrawTextLog(int generation, String text, float x, float y, Paint paint) {
            this.generation = generation;
            this.text = text;
            this.x = x;
            this.y = y;
            this.paint = paint;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            DrawTextLog that = (DrawTextLog) o;
            return Float.compare(x, that.x) == 0
                    && Float.compare(y, that.y) == 0
                    && generation == that.generation
                    && Objects.equals(text, that.text)
                    && Objects.equals(paint, that.paint);
        }

        @Override
        public int hashCode() {
            return Objects.hash(text, x, y, paint, generation);
        }
    }
}
