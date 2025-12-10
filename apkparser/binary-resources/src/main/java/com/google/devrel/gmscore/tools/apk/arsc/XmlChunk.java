package com.google.devrel.gmscore.tools.apk.arsc;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;

/**
 * Represents an XML chunk structure.
 *
 * <p>An XML chunk can contain many nodes as well as a string pool which contains all of the strings
 * referenced by the nodes.
 */
public final class XmlChunk extends ChunkWithChunks {

  protected XmlChunk(ByteBuffer buffer, @Nullable Chunk parent) {
    super(buffer, parent);
  }

  @Override
  protected Type getType() {
    return Chunk.Type.XML;
  }

  /** Returns a string at the provided (0-based) index if the index exists in the string pool. */
  public String getString(int index) {
    for (Chunk chunk : getChunks().values()) {
      if (chunk instanceof StringPoolChunk) {
        return ((StringPoolChunk) chunk).getString(index);
      }
    }
    throw new IllegalStateException("XmlChunk did not contain a string pool.");
  }
}
