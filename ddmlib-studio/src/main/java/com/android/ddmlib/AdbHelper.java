/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.ddmlib;

import com.android.annotations.concurrency.Slow;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Helper class to handle requests and connections to adb.
 *
 * <p>{@link AndroidDebugBridge} is the public API to connection to adb, while {@link AdbHelper}
 * does the low level stuff.
 *
 * <p>This currently uses spin-wait non-blocking I/O. A Selector would be more efficient, but seems
 * like overkill for what we're doing here.
 */
public final class AdbHelper {

    static final int WAIT_TIME = 5; // spin-wait sleep, in ms

    public static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    /**
     * do not instantiate
     */
    private AdbHelper() {}


    /**
     * Identify which adb service the command should target.
     */
    public enum AdbService {
        /**
         * the shell service
         */
        SHELL,

        /** The exec service. */
        EXEC,

        /** The abb service. */
        ABB_EXEC,
    }

    /**
     * Write until all data in "data" is written, the optional length is reached,
     * the timeout expires, or the connection fails. Returns "true" if all
     * data was written.
     * @param chan the opened socket to write to.
     * @param data the buffer to send.
     * @param length the length to write or -1 to send the whole buffer.
     * @param timeout The timeout value. A timeout of zero means "wait forever".
     * @throws TimeoutException in case of timeout on the connection.
     * @throws IOException in case of I/O error on the connection.
     */
    @Slow
    public static void write(SocketChannel chan, byte[] data, int length, int timeout) throws TimeoutException, IOException {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length != -1 ? length : data.length);
        int numWaits = 0;

        while (buf.position() != buf.limit()) {
            int count;

            count = chan.write(buf);
            if (count < 0) {
                Log.d("ddms", "write: channel EOF");
                throw new IOException("channel EOF");
            } else if (count == 0) {
                // TODO: need more accurate timeout?
                if (timeout != 0 && numWaits * WAIT_TIME > timeout) {
                    Log.d("ddms", "write: timeout");
                    throw new TimeoutException();
                }
                try {
                    // non-blocking spin
                    Thread.sleep(WAIT_TIME);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    // Throw a timeout exception in place of interrupted exception to avoid API changes.
                    throw new TimeoutException("Write interrupted with immediate timeout via interruption.");
                }
                numWaits++;
            } else {
                numWaits = 0;
            }
        }
    }

    public static void setAbbExecAllowed(boolean allowed) {
        SplitApkInstallerBase.setAbbExecAllowed(allowed);
    }
}
