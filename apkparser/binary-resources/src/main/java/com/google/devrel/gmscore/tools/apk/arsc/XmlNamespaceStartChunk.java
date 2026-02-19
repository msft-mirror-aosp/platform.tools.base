package com.google.devrel.gmscore.tools.apk.arsc;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;

/** Represents the starting tag of a namespace in an XML document. */
public final class XmlNamespaceStartChunk extends XmlNamespaceChunk {

  protected XmlNamespaceStartChunk(ByteBuffer buffer, @Nullable Chunk parent) {
    super(buffer, parent);
  }

  @Override
  protected Type getType() {
    return Chunk.Type.XML_START_NAMESPACE;
  }
}
