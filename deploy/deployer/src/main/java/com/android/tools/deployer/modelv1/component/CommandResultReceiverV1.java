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
package com.android.tools.deployer.modelv1.component;

import com.android.tools.deployer.common.DeployerMultiLineReceiver;

import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CommandResultReceiverV1 extends DeployerMultiLineReceiver {
    public static final int SUCCESS_CODE = 1;
    public static final int INVALID_ARGUMENT_CODE = 3;

    private int resultCode = -1;
    private final @NotNull Pattern resultCodePattern = Pattern.compile("result=(\\d+)");

    public int getResultCode() {
        return resultCode;
    }

    @Override
    public void processNewLines(@NotNull String[] lines) {
        for (String line : lines) {
            Matcher matcher = resultCodePattern.matcher(line);
            if (matcher.find()) {
                resultCode = Integer.parseInt(matcher.group(1));
            }
        }
    }

    @Override
    public boolean isCancelled() {
        return false;
    }
}
