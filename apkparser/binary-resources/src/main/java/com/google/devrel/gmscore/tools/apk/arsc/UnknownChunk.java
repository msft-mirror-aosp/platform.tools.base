package com.google.devrel.gmscore.tools.apk.arsc;

import com.google.common.base.Preconditions;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import javax.annotation.Nullable;

/**
 * A chunk whose contents are unknown. This is a placeholder until we add a proper chunk for the
 * unknown type.
 */
public final class UnknownChunk extends Chunk {

  private final Type type;

  private final byte[] header;

  private final byte[] payload;

  protected UnknownChunk(ByteBuffer buffer, @Nullable Chunk parent) {
    this(buffer, parent, Type.fromCode(buffer.getShort(buffer.position() - 2)));
  }

  protected UnknownChunk(ByteBuffer buffer, @Nullable Chunk parent, Type type) {
    super(buffer, parent);

    this.type = Preconditions.checkNotNull(type);
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
