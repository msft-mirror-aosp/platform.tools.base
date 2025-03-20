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

package android.view;

import android.content.Context;
import android.graphics.Point;

import java.util.ArrayList;
import java.util.List;

public final class ViewGroup extends View {

    public static class LayoutParams {
        public static final int MATCH_PARENT = -1;
        public static final int WRAP_CONTENT = -2;

        public int width = 0;
        public int height = 0;

        public LayoutParams(int width, int height) {}

        LayoutParams() {}
    }

    private final List<View> mChildren = new ArrayList<>();

    public ViewGroup(Context context) {
        super(context);
    }

    public int getChildCount() {
        return mChildren.size();
    }

    public View getChildAt(int i) {
        return mChildren.get(i);
    }

    public void addView(View view) {
        mChildren.add(view);
    }

    public void addView(View child, LayoutParams params) {
        mChildren.add(child);
    }

    public void removeView(View view) {
        mChildren.remove(view);
    }

    /** Note: This is not a correct implementation of onMeasure. Used only in tests. */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = 0;
        int height = 0;
        for (int index=0; index < mChildren.size(); index++) {
            View child = mChildren.get(index);
            child.measure(widthMeasureSpec, heightMeasureSpec);
            width = Math.max(width, child.getLeft() + child.getMeasuredWidth());
            height = Math.max(height, child.getTop() + child.getMeasuredHeight());
        }
        setMeasuredDimension(width, height);
    }
}
