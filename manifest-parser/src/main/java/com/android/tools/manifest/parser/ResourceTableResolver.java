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
package com.android.tools.manifest.parser;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A {@link ResourceResolver} backed by an APK's compiled resource table ({@code resources.arsc}).
 *
 * <p>Manifests only ever need a handful of values, so rather than decoding the whole table this
 * walks the chunk headers straight to the one entry asked for. The layout is the platform's, from
 * {@code frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h}.
 *
 * <p>Values are taken from the default configuration. That is what a manifest attribute resolves to
 * on most devices, and the only answer available without a device: when the default configuration
 * has no entry, the first configuration that does is used instead.
 */
public final class ResourceTableResolver implements ResourceResolver {

    private static final int RES_TABLE_TYPE = 0x0002;
    private static final int RES_TABLE_PACKAGE_TYPE = 0x0200;
    private static final int RES_TABLE_TYPE_TYPE = 0x0201;

    private static final int CHUNK_HEADER_SIZE = 8;

    /** {@code ResTable_type} flags: entries are (index, offset) pairs, or offsets are 16-bit. */
    private static final int FLAG_SPARSE = 0x01;

    private static final int FLAG_OFFSET16 = 0x02;
    private static final int NO_ENTRY_16 = 0xffff;
    private static final int NO_ENTRY_32 = 0xffffffff;

    /** {@code ResTable_entry} flags: a bag rather than a value, or value packed into the entry. */
    private static final int ENTRY_FLAG_COMPLEX = 0x0001;

    private static final int ENTRY_FLAG_COMPACT = 0x0008;

    /** {@code Res_value} data types. */
    private static final int TYPE_REFERENCE = 0x01;

    private static final int TYPE_DYNAMIC_REFERENCE = 0x07;
    private static final int TYPE_INT_BOOLEAN = 0x12;

    /**
     * Bounds how many resource-to-resource references are followed (e.g. a bool aliasing another).
     */
    private static final int MAX_REFERENCE_DEPTH = 8;

    /** Returned by {@link #findValue} when there is no plain value for the resource. */
    private static final long NO_VALUE = -1;

    @NonNull private final ByteBuffer table;

    private ResourceTableResolver(@NonNull ByteBuffer table) {
        this.table = table;
    }

    /** Reads a {@code resources.arsc} stream, returning null if it is not a resource table. */
    @Nullable
    public static ResourceTableResolver fromStream(@NonNull InputStream resourcesArsc)
            throws IOException {
        return fromStream(resourcesArsc, -1);
    }

    /**
     * Reads a {@code resources.arsc} stream of {@code size} bytes (e.g. {@link
     * java.util.zip.ZipEntry#getSize()}), returning null if it is not a resource table. Knowing the
     * size lets the table be read into a single array instead of a growing buffer, which on a large
     * app means allocating its 12 MB once rather than ~45 MB of intermediate copies. A negative
     * size means unknown.
     */
    @Nullable
    public static ResourceTableResolver fromStream(@NonNull InputStream resourcesArsc, long size)
            throws IOException {
        if (size < 0 || size > Integer.MAX_VALUE - 8) {
            return fromBytes(readAll(resourcesArsc));
        }
        byte[] bytes = new byte[(int) size];
        int offset = 0;
        while (offset < bytes.length) {
            int read = resourcesArsc.read(bytes, offset, bytes.length - offset);
            if (read == -1) {
                break;
            }
            offset += read;
        }
        if (offset != bytes.length || resourcesArsc.read() != -1) {
            // The size was wrong; a partial or overlong table cannot be trusted.
            return null;
        }
        return fromBytes(bytes);
    }

    @NonNull
    private static byte[] readAll(@NonNull InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            bytes.write(buffer, 0, read);
        }
        return bytes.toByteArray();
    }

    /**
     * Wraps the contents of a {@code resources.arsc}, returning null if it is not a resource table.
     */
    @Nullable
    public static ResourceTableResolver fromBytes(@NonNull byte[] resourcesArsc) {
        ByteBuffer buffer = ByteBuffer.wrap(resourcesArsc).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.limit() < CHUNK_HEADER_SIZE || buffer.getShort(0) != RES_TABLE_TYPE) {
            return null;
        }
        return new ResourceTableResolver(buffer);
    }

    @Nullable
    @Override
    public Boolean resolveBoolean(int resourceId) {
        try {
            int id = resourceId;
            for (int depth = 0; depth < MAX_REFERENCE_DEPTH; depth++) {
                long value = findValue(id);
                if (value == NO_VALUE) {
                    return null;
                }
                int type = (int) (value >>> 32);
                int data = (int) value;
                if (type == TYPE_INT_BOOLEAN) {
                    return data != 0;
                }
                if (type != TYPE_REFERENCE && type != TYPE_DYNAMIC_REFERENCE) {
                    return null;
                }
                id = data;
            }
            return null;
        } catch (IndexOutOfBoundsException e) {
            // A truncated or malformed table: nothing can be resolved from it.
            return null;
        }
    }

    /**
     * Returns the value of {@code resourceId} as {@code (dataType << 32) | data}, or {@link
     * #NO_VALUE} if it has no plain value.
     */
    private long findValue(int resourceId) {
        int packageId = resourceId >>> 24;
        int typeId = (resourceId >>> 16) & 0xff;
        int entryId = resourceId & 0xffff;

        long fallback = NO_VALUE;
        int tableEnd = chunkEnd(0, table.limit());
        // The global string pool is skipped like any other child: only its header is read.
        for (int pkg = headerEnd(0);
                pkg + CHUNK_HEADER_SIZE <= tableEnd;
                pkg = chunkEnd(pkg, tableEnd)) {
            if (table.getShort(pkg) != RES_TABLE_PACKAGE_TYPE
                    || table.getInt(pkg + 8) != packageId) {
                continue;
            }
            int packageEnd = chunkEnd(pkg, tableEnd);
            for (int type = headerEnd(pkg);
                    type + CHUNK_HEADER_SIZE <= packageEnd;
                    type = chunkEnd(type, packageEnd)) {
                if (table.getShort(type) != RES_TABLE_TYPE_TYPE
                        || (table.get(type + 8) & 0xff) != typeId) {
                    continue;
                }
                long value = entryValue(type, entryId);
                if (value == NO_VALUE) {
                    continue;
                }
                // ResTable_type: id(1) flags(1) reserved(2) entryCount(4) entriesStart(4) config
                if (isDefaultConfig(type + CHUNK_HEADER_SIZE + 12)) {
                    return value;
                }
                if (fallback == NO_VALUE) {
                    fallback = value;
                }
            }
        }
        return fallback;
    }

    /** Looks up entry {@code entryId} in the {@code ResTable_type} chunk at {@code chunk}. */
    private long entryValue(int chunk, int entryId) {
        int flags = table.get(chunk + 9) & 0xff;
        int entryCount = table.getInt(chunk + 12);
        int entriesStart = table.getInt(chunk + 16);
        int offsets = headerEnd(chunk);

        int offset = -1;
        if ((flags & FLAG_SPARSE) != 0) {
            // Sorted (u16 index, u16 offset / 4) pairs.
            int low = 0;
            int high = entryCount - 1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                int index = table.getShort(offsets + mid * 4) & 0xffff;
                if (index == entryId) {
                    offset = (table.getShort(offsets + mid * 4 + 2) & 0xffff) * 4;
                    break;
                } else if (index < entryId) {
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
        } else if (entryId < entryCount) {
            if ((flags & FLAG_OFFSET16) != 0) {
                int value = table.getShort(offsets + entryId * 2) & 0xffff;
                if (value != NO_ENTRY_16) {
                    offset = value * 4;
                }
            } else {
                int value = table.getInt(offsets + entryId * 4);
                if (value != NO_ENTRY_32) {
                    offset = value;
                }
            }
        }
        if (offset < 0) {
            return NO_VALUE;
        }

        int entry = chunk + entriesStart + offset;
        int entryFlags = table.getShort(entry + 2) & 0xffff;
        if ((entryFlags & ENTRY_FLAG_COMPACT) != 0) {
            // u16 key, u16 flags (data type in the high byte), u32 data.
            return ((long) (entryFlags >>> 8) << 32) | (table.getInt(entry + 4) & 0xffffffffL);
        }
        if ((entryFlags & ENTRY_FLAG_COMPLEX) != 0) {
            return NO_VALUE;
        }
        // Res_value follows the entry header: size(2) res0(1) dataType(1) data(4).
        int value = entry + (table.getShort(entry) & 0xffff);
        return ((long) (table.get(value + 3) & 0xff) << 32)
                | (table.getInt(value + 4) & 0xffffffffL);
    }

    /** A configuration is the default one when every field after its size is zero. */
    private boolean isDefaultConfig(int config) {
        int size = table.getInt(config);
        for (int i = 4; i < size; i++) {
            if (table.get(config + i) != 0) {
                return false;
            }
        }
        return true;
    }

    private int headerEnd(int chunk) {
        return chunk + (table.getShort(chunk + 2) & 0xffff);
    }

    /**
     * Returns where the chunk at {@code chunk} ends, clamped to its parent's {@code limit}. A size
     * too small to be a chunk ends the walk rather than looping on the same offset forever.
     */
    private int chunkEnd(int chunk, int limit) {
        int size = table.getInt(chunk + 4);
        if (size < CHUNK_HEADER_SIZE || size > limit - chunk) {
            return limit;
        }
        return chunk + size;
    }
}
