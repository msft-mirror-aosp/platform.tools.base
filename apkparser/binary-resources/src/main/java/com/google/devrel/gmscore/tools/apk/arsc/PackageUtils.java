package com.google.devrel.gmscore.tools.apk.arsc;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import static java.nio.charset.StandardCharsets.UTF_16LE;

/** Provides utility methods for package names. */
public final class PackageUtils {

  public static final int PACKAGE_NAME_SIZE = 256;

  private PackageUtils() {}  // Prevent instantiation

  /**
   * Reads the package name from the buffer and repositions the buffer to point directly after
   * the package name.
   * @param buffer The buffer containing the package name.
   * @param offset The offset in the buffer to read from.
   * @return The package name.
   */
  public static String readPackageName(ByteBuffer buffer, int offset) {
    byte[] data = buffer.array();
    int length = 0;
    // Look for the null terminator for the string instead of using the entire buffer.
    // It's UTF-16 so check 2 bytes at a time to see if its double 0.
    for (int i = offset; i < data.length && i < PACKAGE_NAME_SIZE + offset; i += 2) {
      if (data[i] == 0 && data[i + 1] == 0) {
        length = i - offset;
        break;
      }
    }
    Charset utf16 = UTF_16LE;
    String str = new String(data, offset, length, utf16);
    buffer.position(offset + PACKAGE_NAME_SIZE);
    return str;
  }

  /**
   * Writes the provided package name to the buffer in UTF-16.
   * @param buffer The buffer that will be written to.
   * @param packageName The package name that will be written to the buffer.
   */
  public static void writePackageName(ByteBuffer buffer, String packageName) {
    byte[] nameBytes = packageName.getBytes(UTF_16LE);
    buffer.put(nameBytes, 0, Math.min(nameBytes.length, PACKAGE_NAME_SIZE));
    if (nameBytes.length < PACKAGE_NAME_SIZE) {
      // pad out the remaining space with an empty array.
      buffer.put(new byte[PACKAGE_NAME_SIZE - nameBytes.length]);
    }
  }
}
