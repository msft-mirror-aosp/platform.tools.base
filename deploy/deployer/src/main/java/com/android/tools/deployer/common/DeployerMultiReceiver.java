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
package com.android.tools.deployer.common;

import com.android.annotations.NonNull;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Base implementation of {@link DeployerIShellOutputReceiver}, that takes multiple instances of
 * {@link DeployerIShellOutputReceiver} and broadcasts the received data to all of them.
 *
 * <p>This class is a copy of {@link com.android.ddmlib.MultiReceiver} adapted for use within
 * deployer without a dependency on ddmlib.
 */
public class DeployerMultiReceiver implements DeployerIShellOutputReceiver {

    private final @NonNull ArrayList<DeployerIShellOutputReceiver> receivers;

    public DeployerMultiReceiver(@NonNull DeployerIShellOutputReceiver... receivers) {
        this.receivers = new ArrayList<>(Arrays.asList(receivers));
    }

    @Override
    public void addOutput(@NonNull byte[] data, int offset, int length) {
        updateReceiverList();
        for (DeployerIShellOutputReceiver receiver : receivers) {
            receiver.addOutput(data, offset, length);
        }
    }

    @Override
    public void flush() {
        updateReceiverList();
        for (DeployerIShellOutputReceiver receiver : receivers) {
            receiver.flush();
        }
        receivers.clear();
    }

    @Override
    public boolean isCancelled() {
        updateReceiverList();
        return receivers.isEmpty();
    }

    /** Removes any cancelled receiver from the current list. */
    private void updateReceiverList() {
        receivers.removeIf(DeployerIShellOutputReceiver::isCancelled);
    }
}
