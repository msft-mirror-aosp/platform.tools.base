package com.google.devrel.gmscore.tools.apk.arsc;

import javax.annotation.Nullable;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A chunk whose contents are unknown. This is a placeholder until we add a proper chunk for the
 * unknown type.
 */
public final class UnknownChunk extends Chunk {

  private final Type type;

  private final byte[] header;

  private final byte[] payload;

  protected UnknownChunk(ByteBuffer buffer, @Nullable Chunk parent) {
    super(buffer, parent);

    type = Type.fromCode(buffer.getShort(offset));
    header = new byte[headerSize - Chunk.METADATA_SIZE];
    payload = new byte[chunkSize - headerSize];
    buffer.get(header);
    buffer.get(payload);
  }

  @Override
  protected void writeHeader(ByteBuffer output) {
    output.put(header);
  }

  @Override
  protected void writePayload(DataOutput output, ByteBuffer header, int options)
      throws IOException {
    output.write(payload);
  }

  @Override
  protected Type getType() {
    return type;
  }
}
