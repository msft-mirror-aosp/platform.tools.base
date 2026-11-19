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
package com.google.devrel.gmscore.tools.apk.arsc;

import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Represents a RES_TABLE_FLAG_LIST chunk.
 *
 * <p>Contains a list of string pool references to flag names.
 */
public final class FlagListChunk extends Chunk {

  /** The list of string pool references to flag names. */
  private final List<Integer> flagNameIndices = new ArrayList<>();

  protected FlagListChunk(ByteBuffer buffer, @Nullable Chunk parent) {
    super(buffer, parent);
  }

  @Override
  protected void init(ByteBuffer buffer) {
    super.init(buffer);
    int count = (chunkSize - getHeaderSize()) / 4;
    for (int i = 0; i < count; i++) {
      flagNameIndices.add(buffer.getInt());
    }
  }

  @Override
  protected Type getType() {
    return Type.TABLE_FLAG_LIST;
  }

  @Override
  protected void writePayload(DataOutput output, ByteBuffer header, int options)
      throws IOException {
    for (int index : flagNameIndices) {
      output.writeInt(index);
    }
  }

  /** Returns the list of string pool references to flag names. */
  public List<Integer> getFlagNameIndices() {
    return Collections.unmodifiableList(flagNameIndices);
  }
}
