package com.google.devrel.gmscore.tools.apk.arsc;

import javax.annotation.Nullable;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;

/** Represents the end of an XML node. */
public final class XmlEndElementChunk extends XmlNodeChunk {

  /** A string reference to the namespace URI, or -1 if not present. */
  private final int namespace;

  /** A string reference to the attribute name. */
  private final int name;

  protected XmlEndElementChunk(ByteBuffer buffer, @Nullable Chunk parent) {
    super(buffer, parent);
    namespace = buffer.getInt();
    name = buffer.getInt();
  }

  /** Returns the namespace URI, or the empty string if no namespace is present. */
  public String getNamespace() {
    return getString(namespace);
  }

  /** Returns the attribute name. */
  public String getName() {
    return getString(name);
  }

  @Override
  protected Type getType() {
    return Chunk.Type.XML_END_ELEMENT;
  }

  @Override
  protected void writePayload(DataOutput output, ByteBuffer header, int options)
      throws IOException {
    super.writePayload(output, header, options);
    output.writeInt(namespace);
    output.writeInt(name);
  }

  /**
   * Returns a brief description of this XML node. The representation of this information is
   * subject to change, but below is a typical example:
   *
   * <pre>
   * "XmlEndElementChunk{line=1234, comment=My awesome comment., namespace=foo, name=bar}"
   * </pre>
   */
  @Override
  public String toString() {
    return String.format(
        Locale.US,
        "XmlEndElementChunk{line=%d, comment=%s, namespace=%s, name=%s}",
        getLineNumber(),
        getComment(),
        getNamespace(),
        getName());
  }
}
