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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public class ResourceTableResolverTest {

    private static final int PACKAGE_ID = 0x7f;
    private static final int BOOL_TYPE = 0x05;
    private static final int STRING_TYPE = 0x0a;

    private static final int TYPE_REFERENCE = 0x01;
    private static final int TYPE_STRING = 0x03;
    private static final int TYPE_INT_BOOLEAN = 0x12;

    private enum Layout {
        DENSE_32,
        DENSE_16,
        SPARSE
    }

    @Test
    public void resolvesBooleans() {
        ResourceTableResolver resolver =
                table(
                        typeChunk(
                                BOOL_TYPE,
                                true,
                                Layout.DENSE_32,
                                entries(0, bool(true), 1, bool(false))));
        assertEquals(Boolean.TRUE, resolver.resolveBoolean(id(BOOL_TYPE, 0)));
        assertEquals(Boolean.FALSE, resolver.resolveBoolean(id(BOOL_TYPE, 1)));
    }

    @Test
    public void resolvesWith16BitOffsets() {
        ResourceTableResolver resolver =
                table(
                        typeChunk(
                                BOOL_TYPE,
                                true,
                                Layout.DENSE_16,
                                entries(0, bool(false), 3, bool(true))));
        assertEquals(Boolean.FALSE, resolver.resolveBoolean(id(BOOL_TYPE, 0)));
        assertEquals(Boolean.TRUE, resolver.resolveBoolean(id(BOOL_TYPE, 3)));
        assertNull(resolver.resolveBoolean(id(BOOL_TYPE, 1)));
    }

    @Test
    public void resolvesSparseEntries() {
        ResourceTableResolver resolver =
                table(
                        typeChunk(
                                BOOL_TYPE,
                                true,
                                Layout.SPARSE,
                                entries(2, bool(true), 40, bool(false), 0x21, bool(true))));
        assertEquals(Boolean.TRUE, resolver.resolveBoolean(id(BOOL_TYPE, 2)));
        assertEquals(Boolean.FALSE, resolver.resolveBoolean(id(BOOL_TYPE, 40)));
        assertEquals(Boolean.TRUE, resolver.resolveBoolean(id(BOOL_TYPE, 0x21)));
        assertNull(resolver.resolveBoolean(id(BOOL_TYPE, 3)));
    }

    @Test
    public void resolvesCompactEntries() {
        ResourceTableResolver resolver =
                table(
                        typeChunk(
                                BOOL_TYPE,
                                true,
                                Layout.DENSE_32,
                                entries(
                                        0,
                                        compact(TYPE_INT_BOOLEAN, -1),
                                        1,
                                        compact(TYPE_INT_BOOLEAN, 0))));
        assertEquals(Boolean.TRUE, resolver.resolveBoolean(id(BOOL_TYPE, 0)));
        assertEquals(Boolean.FALSE, resolver.resolveBoolean(id(BOOL_TYPE, 1)));
    }

    @Test
    public void prefersTheDefaultConfiguration() {
        ResourceTableResolver resolver =
                table(
                        typeChunk(BOOL_TYPE, false, Layout.DENSE_32, entries(0, bool(false))),
                        typeChunk(BOOL_TYPE, true, Layout.DENSE_32, entries(0, bool(true))));
        assertEquals(Boolean.TRUE, resolver.resolveBoolean(id(BOOL_TYPE, 0)));
    }

    @Test
    public void fallsBackToAnotherConfigurationWithoutADefault() {
        ResourceTableResolver resolver =
                table(typeChunk(BOOL_TYPE, false, Layout.DENSE_32, entries(0, bool(false))));
        assertEquals(Boolean.FALSE, resolver.resolveBoolean(id(BOOL_TYPE, 0)));
    }

    @Test
    public void followsReferences() {
        ResourceTableResolver resolver =
                table(
                        typeChunk(
                                BOOL_TYPE,
                                true,
                                Layout.DENSE_32,
                                entries(
                                        0, value(TYPE_REFERENCE, id(BOOL_TYPE, 1)),
                                        1, value(TYPE_REFERENCE, id(BOOL_TYPE, 2)),
                                        2, bool(false))));
        assertEquals(Boolean.FALSE, resolver.resolveBoolean(id(BOOL_TYPE, 0)));
    }

    @Test
    public void stopsFollowingAReferenceCycle() {
        ResourceTableResolver resolver =
                table(
                        typeChunk(
                                BOOL_TYPE,
                                true,
                                Layout.DENSE_32,
                                entries(
                                        0, value(TYPE_REFERENCE, id(BOOL_TYPE, 1)),
                                        1, value(TYPE_REFERENCE, id(BOOL_TYPE, 0)))));
        assertNull(resolver.resolveBoolean(id(BOOL_TYPE, 0)));
    }

    @Test
    public void returnsNullForNonBooleansAndMissingResources() {
        ResourceTableResolver resolver =
                table(
                        typeChunk(
                                BOOL_TYPE,
                                true,
                                Layout.DENSE_32,
                                entries(0, bool(true), 1, complex())),
                        typeChunk(
                                STRING_TYPE,
                                true,
                                Layout.DENSE_32,
                                entries(0, value(TYPE_STRING, 0))));
        assertNull(resolver.resolveBoolean(id(STRING_TYPE, 0)));
        assertNull(resolver.resolveBoolean(id(BOOL_TYPE, 1)));
        assertNull(resolver.resolveBoolean(id(BOOL_TYPE, 7)));
        assertNull(resolver.resolveBoolean(id(0x03, 0)));
        assertNull(resolver.resolveBoolean(0x01050000));
    }

    @Test
    public void readsStreamsOfKnownAndUnknownSize() throws Exception {
        byte[] bytes =
                tableBytes(typeChunk(BOOL_TYPE, true, Layout.DENSE_32, entries(0, bool(true))));
        assertEquals(
                Boolean.TRUE,
                ResourceTableResolver.fromStream(new ByteArrayInputStream(bytes), bytes.length)
                        .resolveBoolean(id(BOOL_TYPE, 0)));
        assertEquals(
                Boolean.TRUE,
                ResourceTableResolver.fromStream(new ByteArrayInputStream(bytes))
                        .resolveBoolean(id(BOOL_TYPE, 0)));
        // A size that does not match the stream means the table cannot be trusted.
        assertNull(
                ResourceTableResolver.fromStream(
                        new ByteArrayInputStream(bytes), bytes.length + 1));
        assertNull(
                ResourceTableResolver.fromStream(
                        new ByteArrayInputStream(bytes), bytes.length - 1));
    }

    @Test
    public void rejectsSomethingThatIsNotAResourceTable() {
        assertNull(ResourceTableResolver.fromBytes(new byte[0]));
        assertNull(
                ResourceTableResolver.fromBytes(
                        new byte[] {0x03, 0x00, 0x08, 0x00, 0x08, 0, 0, 0}));
    }

    @Test
    public void survivesTruncatedAndMalformedTables() {
        byte[] good =
                tableBytes(typeChunk(BOOL_TYPE, true, Layout.DENSE_32, entries(0, bool(true))));
        ResourceTableResolver truncated =
                ResourceTableResolver.fromBytes(Arrays.copyOf(good, good.length - 20));
        assertNull(truncated.resolveBoolean(id(BOOL_TYPE, 0)));

        // A zero-sized chunk must end the walk instead of looping on the same offset.
        byte[] zeroSized = good.clone();
        ByteBuffer.wrap(zeroSized).order(ByteOrder.LITTLE_ENDIAN).putInt(12 + 4, 0);
        assertNull(ResourceTableResolver.fromBytes(zeroSized).resolveBoolean(id(BOOL_TYPE, 0)));
    }

    // ---------------------------------------------------------------------------------------------
    // A minimal resources.arsc writer, laid out as in ResourceTypes.h.
    // ---------------------------------------------------------------------------------------------

    private static int id(int type, int entry) {
        return PACKAGE_ID << 24 | type << 16 | entry;
    }

    private static Map<Integer, byte[]> entries(Object... indexAndEntry) {
        Map<Integer, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < indexAndEntry.length; i += 2) {
            entries.put((Integer) indexAndEntry[i], (byte[]) indexAndEntry[i + 1]);
        }
        return entries;
    }

    private static byte[] bool(boolean value) {
        return value(TYPE_INT_BOOLEAN, value ? -1 : 0);
    }

    /** ResTable_entry (size, flags, key) followed by a Res_value (size, res0, dataType, data). */
    private static byte[] value(int dataType, int data) {
        return le(16).putShort((short) 8)
                .putShort((short) 0)
                .putInt(0)
                .putShort((short) 8)
                .put((byte) 0)
                .put((byte) dataType)
                .putInt(data)
                .array();
    }

    /** A compact entry: key, flags with the data type in the high byte, data. */
    private static byte[] compact(int dataType, int data) {
        return le(8).putShort((short) 0)
                .putShort((short) (0x0008 | dataType << 8))
                .putInt(data)
                .array();
    }

    /** A bag (ResTable_map_entry) with no values. */
    private static byte[] complex() {
        return le(16).putShort((short) 16)
                .putShort((short) 0x0001)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .array();
    }

    private static byte[] typeChunk(
            int typeId, boolean defaultConfig, Layout layout, Map<Integer, byte[]> entries) {
        TreeMap<Integer, byte[]> sorted = new TreeMap<>(entries);
        int entryCount = layout == Layout.SPARSE ? sorted.size() : sorted.lastKey() + 1;
        int configSize = 64;
        int headerSize = 8 + 12 + configSize;
        int offsetWidth = layout == Layout.DENSE_32 ? 4 : layout == Layout.DENSE_16 ? 2 : 4;
        int offsetsSize = (entryCount * offsetWidth + 3) & ~3;

        ByteArrayOutputStream data = new ByteArrayOutputStream();
        ByteBuffer offsets = le(offsetsSize);
        if (layout == Layout.SPARSE) {
            for (Map.Entry<Integer, byte[]> e : sorted.entrySet()) {
                offsets.putShort((short) (int) e.getKey()).putShort((short) (data.size() / 4));
                write(data, e.getValue());
            }
        } else {
            for (int i = 0; i < entryCount; i++) {
                byte[] entry = sorted.get(i);
                if (layout == Layout.DENSE_16) {
                    offsets.putShort((short) (entry == null ? 0xffff : data.size() / 4));
                } else {
                    offsets.putInt(entry == null ? -1 : data.size());
                }
                if (entry != null) {
                    write(data, entry);
                }
            }
        }
        int flags = layout == Layout.SPARSE ? 0x01 : layout == Layout.DENSE_16 ? 0x02 : 0;
        int entriesStart = headerSize + offsetsSize;
        ByteBuffer chunk = le(entriesStart + data.size());
        chunk.putShort((short) 0x0201).putShort((short) headerSize).putInt(chunk.capacity());
        chunk.put((byte) typeId)
                .put((byte) flags)
                .putShort((short) 0)
                .putInt(entryCount)
                .putInt(entriesStart);
        chunk.putInt(configSize);
        byte[] config = new byte[configSize - 4];
        if (!defaultConfig) {
            config[24] = (byte) 0xf0; // a non-zero density
        }
        chunk.put(config).put(offsets.array()).put(data.toByteArray());
        return chunk.array();
    }

    private static ResourceTableResolver table(byte[]... typeChunks) {
        return ResourceTableResolver.fromBytes(tableBytes(typeChunks));
    }

    private static byte[] tableBytes(byte[]... typeChunks) {
        // An empty global string pool, which the resolver must skip without decoding.
        byte[] stringPool = le(28).putShort((short) 0x0001).putShort((short) 28).putInt(28).array();

        ByteArrayOutputStream children = new ByteArrayOutputStream();
        for (byte[] typeChunk : typeChunks) {
            write(children, typeChunk);
        }
        int packageHeaderSize = 8 + 4 + 256 + 5 * 4;
        ByteBuffer pkg = le(packageHeaderSize + children.size());
        pkg.putShort((short) 0x0200)
                .putShort((short) packageHeaderSize)
                .putInt(pkg.capacity())
                .putInt(PACKAGE_ID);
        pkg.position(packageHeaderSize);
        pkg.put(children.toByteArray());

        ByteBuffer table = le(12 + stringPool.length + pkg.capacity());
        table.putShort((short) 0x0002).putShort((short) 12).putInt(table.capacity()).putInt(1);
        table.put(stringPool).put(pkg.array());
        return table.array();
    }

    private static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void write(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }
}
