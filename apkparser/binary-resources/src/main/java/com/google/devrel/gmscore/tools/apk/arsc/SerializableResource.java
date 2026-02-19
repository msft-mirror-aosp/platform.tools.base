package com.google.devrel.gmscore.tools.apk.arsc;

import java.io.IOException;

/**
 * A resource, typically a {@link Chunk}, that can be converted to an array of bytes.
 */
public interface SerializableResource {

  /** Indicates that no serialization options should be applied in {@link #toByteArray(int)}. */
  public static final int NONE = 0;
  /** Enables some safe size optimizations, such as string pool string deduping. */
  public static final int SHRINK = 1 << 0;
  /** Strips public flags from {@link TypeSpecChunk}s and resource entries. */
  public static final int PRIVATE_RESOURCES = 1 << 1;

  /**
   * Converts this resource into an array of bytes representation.
   * @return An array of bytes representing this resource.
   * @throws IOException
   */
  byte[] toByteArray() throws IOException;

  /**
   * Converts this resource into an array of bytes representation.
   * @param options The serialization options to be applied to the result.
   * @return An array of bytes representing this resource.
   * @throws IOException
   */
  byte[] toByteArray(int options) throws IOException;
}
