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

import java.nio.ByteBuffer;
import javax.annotation.Nullable;

/**
 * Represents a RES_TABLE_FLAGGED chunk.
 *
 * <p>Contains a flag reference and a boolean, followed by sub-chunks.
 */
public final class FlaggedChunk extends ChunkWithChunks {

  /** The index of the flag name in the value string pool. */
  private final int flagNameIndex;

  /** Whether the flag value should be negated when interpreted in the runtime. */
  private final boolean flagNegated;

  protected FlaggedChunk(ByteBuffer buffer, @Nullable Chunk parent) {
    super(buffer, parent);
    flagNameIndex = buffer.getInt();
    flagNegated = (buffer.get() & 0xFF) != 0;
    // Padding 3 bytes
    buffer.get();
    buffer.get();
    buffer.get();
  }

  @Override
  protected Type getType() {
    return Type.TABLE_FLAGGED;
  }

  @Override
  protected void writeHeader(ByteBuffer output) {
    super.writeHeader(output);
    output.putInt(flagNameIndex);
    output.put((byte) (flagNegated ? 1 : 0));
    output.put((byte) 0);
    output.put((byte) 0);
    output.put((byte) 0);
  }

  /** Returns the index into the flag name string pool. */
  public int getFlagNameIndex() {
    return flagNameIndex;
  }

  /** Returns whether the flag should be negated. */
  public boolean isFlagNegated() {
    return flagNegated;
  }
}
