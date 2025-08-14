/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.tools.perflib.heap.io;

import com.android.annotations.NonNull;
import com.android.tools.perflib.captures.DataBuffer;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class InMemoryBuffer implements DataBuffer {

    private final ByteBuffer mBuffer;

    public InMemoryBuffer(int capacity) {
        mBuffer = ByteBuffer.allocateDirect(capacity);
    }

    /**
     * Create an in memory buffer from a raw array of bytes.
     */
    public InMemoryBuffer(byte[] data) {
        mBuffer = ByteBuffer.wrap(data);
    }

    public InMemoryBuffer(ByteBuffer data) {
        mBuffer = data;
        mBuffer.rewind();
    }

    /**
     * Create an in-memory buffer by memory-mapping a file. This is the most efficient way to handle
     * large files, as it doesn't require loading the entire file into the Java heap.
     */
    public InMemoryBuffer(@NonNull File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r");
                FileChannel channel = raf.getChannel()) {
            mBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
        }
    }

    @Override
    public void dispose() {}

    public ByteBuffer getDirectBuffer() {
        return mBuffer;
    }

    @Override
    public byte readByte() {
        return mBuffer.get();
    }

    @Override
    public void append(@NonNull byte[] data) {
        // Do nothing, since this is not a streaming data buffer.
    }

    @Override
    public void read(@NonNull byte[] b) {
        mBuffer.get(b);
    }

    @Override
    public void readSubSequence(byte[] b, int sourceStart, int sourceEnd) {
        mBuffer.slice().position(sourceStart).get(b);
    }

    @Override
    public char readChar() {
        return mBuffer.getChar();
    }

    @Override
    public short readShort() {
        return mBuffer.getShort();
    }

    @Override
    public int readInt() {
        return mBuffer.getInt();
    }

    @Override
    public long readLong() {
        return mBuffer.getLong();
    }

    @Override
    public float readFloat() {
        return mBuffer.getFloat();
    }

    @Override
    public double readDouble() {
        return mBuffer.getDouble();
    }

    @Override
    public void setPosition(long position) {
        mBuffer.position((int) position);
    }

    @Override
    public long position() {
        return mBuffer.position();
    }

    @Override
    public boolean hasRemaining() {
        return mBuffer.hasRemaining();
    }

    @Override
    public long remaining() {
        return mBuffer.remaining();
    }
}
