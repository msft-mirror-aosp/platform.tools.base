package com.google.devrel.gmscore.tools.apk.arsc;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;

/** Represents the ending tag of a namespace in an XML document. */
public final class XmlNamespaceEndChunk extends XmlNamespaceChunk {

  protected XmlNamespaceEndChunk(ByteBuffer buffer, @Nullable Chunk parent) {
    super(buffer, parent);
  }

  @Override
  protected Type getType() {
    return Chunk.Type.XML_END_NAMESPACE;
  }
}
