package com.google.devrel.gmscore.tools.apk.arsc;

import com.google.common.base.Preconditions;
import com.google.common.primitives.UnsignedBytes;

import javax.annotation.Nullable;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;

import static com.google.common.base.Preconditions.checkNotNull;

/** A chunk that contains a collection of resource entries for a particular resource data type. */
public class TypeSpecChunk extends Chunk {

  /** Flag indicating that a resource entry is public. */
  private static final int SPEC_PUBLIC = 0x40000000;

  /** The id of the resource type that this type spec refers to. */
  private int id;

  /** The number of type chunks of the same resource type that this type spec refers to. */
  private int typesCount;

  /** Flags for entries at a given index. */
  private int[] resources;

  protected TypeSpecChunk(ByteBuffer buffer, @Nullable Chunk parent) {
    super(buffer, parent);
    id = Byte.toUnsignedInt(buffer.get());
    buffer.position(buffer.position() + 1); // Skip 1 byte (reserved)
    typesCount = (buffer.getShort() & 0xFFFF);
    int resourceCount = buffer.getInt();
    resources = new int[resourceCount];
    for (int i = 0; i < resourceCount; ++i) {
      resources[i] = buffer.getInt();
    }
  }

  /**
   * Returns the (1-based) type id of the resources that this {@link TypeSpecChunk} has
   * configuration masks for.
   */
  public int getId() {
    return id;
  }

  /**
   * Sets the id of this chunk.
   *
   * @param newId The new id to use.
   */
  public void setId(int newId) {
    // Ids are 1-based.
    Preconditions.checkState(newId >= 1);
    id = newId;
  }

  /** Returns the number of resource entries that this chunk has configuration masks for. */
  public int getResourceCount() {
    return getResources().length;
  }

  @Override
  protected Type getType() {
    return Chunk.Type.TABLE_TYPE_SPEC;
  }

  @Override
  protected void writeHeader(ByteBuffer output) {
    super.writeHeader(output);
    output.put(UnsignedBytes.checkedCast(id));
    output.put((byte) 0); // Write 1 byte for padding / reserved.
    output.putShort((short) typesCount);
    output.putInt(resources.length);
  }

  @Override
  protected void writePayload(DataOutput output, ByteBuffer header, int options)
      throws IOException {
    final int resourceMask =
        ((options & SerializableResource.PRIVATE_RESOURCES) != 0) ? ~SPEC_PUBLIC : ~0;
    for (int resource : getResources()) {
      output.writeInt(resource & resourceMask);
    }
  }

  /** Returns the number of type chunks of the same resource type that this type spec refers to. */
  public int getTypesCount() {
    return typesCount;
  }

  /** Sets the number of type chunks of the same resource type that this type spec refers to. */
  public void setTypesCount(int typesCount) {
    this.typesCount = typesCount;
  }

  /** Resource configuration masks. */
  public int[] getResources() {
    return resources;
  }

  public void setResources(int[] resources) {
    this.resources = resources;
  }

  /** Returns the name of the type this chunk represents (e.g. string, attr, id). */
  public String getTypeName() {
    PackageChunk packageChunk = getPackageChunk();
    checkNotNull(packageChunk, "%s has no parent package.", getClass());
    StringPoolChunk typePool = packageChunk.getTypeStringPool();
    checkNotNull(typePool, "%s's parent package has no type pool.", getClass());
    return typePool.getString(getId() - 1); // - 1 here to convert to 0-based index
  }

  /** Returns the package enclosing this chunk, if any. Else, returns null. */
  @Nullable
  private PackageChunk getPackageChunk() {
    Chunk chunk = getParent();
    while (chunk != null && !(chunk instanceof PackageChunk)) {
      chunk = chunk.getParent();
    }
    return chunk != null ? (PackageChunk) chunk : null;
  }
}
