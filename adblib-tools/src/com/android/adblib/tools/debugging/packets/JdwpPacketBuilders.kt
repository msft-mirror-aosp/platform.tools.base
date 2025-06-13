/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.adblib.tools.debugging.packets

import com.android.adblib.EmptyAdbInputChannel
import com.android.adblib.tools.debugging.packets.JdwpPacketConstants.PACKET_HEADER_LENGTH
import com.android.adblib.tools.debugging.packets.impl.JdwpCommands

object JdwpPacketBuilders {
    object Commands {
        fun vmIdSizes(packetId: Int): JdwpPacketView {
            return JdwpPacketView.Command(
                id = packetId,
                length = PACKET_HEADER_LENGTH,
                cmdSet = JdwpCommands.CmdSet.SET_VM.value,
                cmd = JdwpCommands.VmCmd.CMD_VM_IDSIZES.value,
                payload = EmptyAdbInputChannel()
            )
        }
    }
}
