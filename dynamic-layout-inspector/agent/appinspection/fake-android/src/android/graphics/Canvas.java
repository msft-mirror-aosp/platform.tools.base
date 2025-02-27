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
    @VisibleForTesting public List<DrawLog> drawLogs = new ArrayList<>();
    private int generation = 0;

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
        drawLogs.add(new DrawLog(generation, rect, paint));
        generation += 1;
    }

    @VisibleForTesting
    public static class DrawLog {
        public final Rect rect;
        public final Paint paint;
        public final int generation;

        public DrawLog(int generation, Rect rect, Paint paint) {
            this.generation = generation;
            this.rect = rect;
            this.paint = paint;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            DrawLog that = (DrawLog) o;
            return generation == that.generation
                    && Objects.equals(rect, that.rect)
                    && Objects.equals(paint, that.paint);
        }

        @Override
        public int hashCode() {
            return Objects.hash(rect, paint, generation);
        }
    }
}
