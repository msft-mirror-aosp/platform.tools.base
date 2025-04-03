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
package com.android.fakeadbserver.services

import com.google.common.base.Charsets
import java.net.Socket

interface StatusWriter {
    fun writeOk()
    fun writeFail()
    fun verifyStatusWritten()
}


class DefaultStatusWriter(val socket: Socket) : StatusWriter {

    private var writeOkCalled = false
    private var writeFailCalled = false

    override fun writeOk() {
        assert(!writeOkCalled)
        writeOkCalled = true
        socket.getOutputStream().write("OKAY".toByteArray(Charsets.UTF_8))
    }

    override fun writeFail() {
        assert(!writeFailCalled)
        writeFailCalled = true
        socket.getOutputStream().write("FAIL".toByteArray(Charsets.UTF_8))
    }

    override fun verifyStatusWritten() {
        assert(writeOkCalled != writeFailCalled) {
            "OKAY or FAIL message, but not both should be written to output stream"
        }
    }
}


class NoOperationStatusWriter() : StatusWriter {

    override fun writeOk() {
    }

    override fun writeFail() {
    }

    override fun verifyStatusWritten() {
    }
}
