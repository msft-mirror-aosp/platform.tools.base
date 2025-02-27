/*
 * Copyright (C) 2025 The Android Open Source Project
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

import java.util.Objects;

public class Paint {
    private int color = 0;
    private Style style = Style.FILL;
    private float strokeWidth = 0f;

    public int getColor() {
        return color;
    }

    public void setColor(int color) {
        this.color = color;
    }

    public Style getStyle() {
        return style;
    }

    public void setStyle(Style style) {
        this.style = style;
    }

    public float getStrokeWidth() {
        return strokeWidth;
    }

    public void setStrokeWidth(float width) {
        this.strokeWidth = width;
    }

    public static enum Style {
        FILL,
        STROKE,
        FILL_AND_STROKE;

        private Style() {}
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Paint paint = (Paint) o;
        return color == paint.color
                && Float.compare(strokeWidth, paint.strokeWidth) == 0
                && style == paint.style;
    }

    @Override
    public int hashCode() {
        return Objects.hash(color, style, strokeWidth);
    }
}
