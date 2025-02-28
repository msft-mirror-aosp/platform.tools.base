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

package android.view;

import androidx.annotation.VisibleForTesting;

public class MotionEvent {

    public static final int ACTION_DOWN = 0;
    public static final int BUTTON_SECONDARY = 2;

    @VisibleForTesting
    final float x;
    @VisibleForTesting
    final float y;
    @VisibleForTesting
    final int action;
    @VisibleForTesting
    final int buttonState;


    @VisibleForTesting
    public MotionEvent() {
        this.x = -1f;
        this.y = -1f;
        this.action = -1;
        this.buttonState = -1;
    }

    @VisibleForTesting
    public MotionEvent(float x, float y, int action, int buttonState) {
        this.x = x;
        this.y = y;
        this.action = action;
        this.buttonState = buttonState;
    }

    public MotionEvent(float x, float y) {
        this.x = x;
        this.y = y;
        this.action = -1;
        this.buttonState = -1;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public final int getButtonState() {
        return buttonState;
    }

    public final int getAction() {
        return action;
    }
}
