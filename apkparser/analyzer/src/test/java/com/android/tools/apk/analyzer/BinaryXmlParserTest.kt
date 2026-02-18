/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.tools.apk.analyzer;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import com.google.devrel.gmscore.tools.apk.arsc.ResourceValue;
import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;

public class BinaryXmlParserTest {
    @Test
    public void testFormatFloatValue() {
        ResourceValue value42 =
                ResourceValue.create(
                        ByteBuffer.wrap(
                                Bytes.concat(
                                        new byte[] {
                                            0x0, 0x8, 0x0, ResourceValue.Type.FLOAT.code()
                                        },
                                        Ints.toByteArray(Float.floatToIntBits(42.0f)))));

        ResourceValue value425 =
                ResourceValue.create(
                        ByteBuffer.wrap(
                                Bytes.concat(
                                        new byte[] {
                                            0x0, 0x8, 0x0, ResourceValue.Type.FLOAT.code()
                                        },
                                        Ints.toByteArray(Float.floatToIntBits(42.5f)))));

        assertEquals("42", BinaryXmlParser.formatValue(value42, null));
        assertEquals("42.5", BinaryXmlParser.formatValue(value425, null));
    }

    @Test
    public void testFormatDimensionValue() {
        ResourceValue value1 =
                ResourceValue.create(
                        ByteBuffer.wrap(
                                Bytes.concat(
                                        new byte[] {
                                            0x0, 0x8, 0x0, ResourceValue.Type.DIMENSION.code()
                                        },
                                        Ints.toByteArray((384 << 8) + 1))));

        ResourceValue value2 =
                ResourceValue.create(
                        ByteBuffer.wrap(
                                Bytes.concat(
                                        new byte[] {
                                            0x0, 0x8, 0x0, ResourceValue.Type.DIMENSION.code()
                                        },
                                        Ints.toByteArray(0x11400024))));

        assertEquals("384dp", BinaryXmlParser.formatValue(value1, null));
        assertEquals("34.5in", BinaryXmlParser.formatValue(value2, null));
    }

    @Test
    public void testFormatFractionValue() {
        ResourceValue value1 =
                ResourceValue.create(
                        ByteBuffer.wrap(
                                Bytes.concat(
                                        new byte[] {
                                            0x0, 0x8, 0x0, ResourceValue.Type.FRACTION.code()
                                        },
                                        Ints.toByteArray(0x01000030))));

        ResourceValue value2 =
                ResourceValue.create(
                        ByteBuffer.wrap(
                                Bytes.concat(
                                        new byte[] {
                                            0x0, 0x8, 0x0, ResourceValue.Type.FRACTION.code()
                                        },
                                        Ints.toByteArray(0x20000030))));

        ResourceValue value3 =
                ResourceValue.create(
                        ByteBuffer.wrap(
                                Bytes.concat(
                                        new byte[] {
                                            0x0, 0x8, 0x0, ResourceValue.Type.FRACTION.code()
                                        },
                                        Ints.toByteArray(0x40000030))));

        ResourceValue value4 =
                ResourceValue.create(
                        ByteBuffer.wrap(
                                Bytes.concat(
                                        new byte[] {
                                            0x0, 0x8, 0x0, ResourceValue.Type.FRACTION.code()
                                        },
                                        Ints.toByteArray(0x40000031))));

        assertEquals("0.78125%", BinaryXmlParser.formatValue(value1, null));
        assertEquals("25%", BinaryXmlParser.formatValue(value2, null));
        assertEquals("50%", BinaryXmlParser.formatValue(value3, null));
        assertEquals("50%p", BinaryXmlParser.formatValue(value4, null));
    }

    @Test
    public void testFormatColorValue() {
        ResourceValue value1 =
                ResourceValue.create(
                        ByteBuffer.wrap(
                                Bytes.concat(
                                        new byte[] {
                                            0x0,
                                            0x8,
                                            0x0,
                                            ResourceValue.Type.INT_COLOR_ARGB8.code()
                                        },
                                        Ints.toByteArray(0xFF556677))));

        ResourceValue value2 =
                ResourceValue.create(
                        ByteBuffer.wrap(
                                Bytes.concat(
                                        new byte[] {
                                            0x0,
                                            0x8,
                                            0x0,
                                            ResourceValue.Type.INT_COLOR_RGB8.code()
                                        },
                                        Ints.toByteArray(0xFF556677))));

        ResourceValue value3 =
                ResourceValue.create(
                        ByteBuffer.wrap(
                                Bytes.concat(
                                        new byte[] {
                                            0x0,
                                            0x8,
                                            0x0,
                                            ResourceValue.Type.INT_COLOR_ARGB4.code()
                                        },
                                        Ints.toByteArray(0x1234F567))));

        ResourceValue value4 =
                ResourceValue.create(
                        ByteBuffer.wrap(
                                Bytes.concat(
                                        new byte[] {
                                            0x0,
                                            0x8,
                                            0x0,
                                            ResourceValue.Type.INT_COLOR_RGB4.code()
                                        },
                                        Ints.toByteArray(0x1234F567))));

        assertEquals("#FF556677", BinaryXmlParser.formatValue(value1, null));
        assertEquals("#556677", BinaryXmlParser.formatValue(value2, null));
        assertEquals("#F567", BinaryXmlParser.formatValue(value3, null));
        assertEquals("#567", BinaryXmlParser.formatValue(value4, null));
    }

    @Test
    public void testFormatReferenceValue() {
        ResourceIdResolver resolver =
                i -> {
                    if (i == 0x12345678) {
                        return "@package:restype/res_name";
                    } else {
                        return "";
                    }
                };

        ResourceValue value1 =
                ResourceValue.create(
                        ByteBuffer.wrap(
                                Bytes.concat(
                                        new byte[] {
                                            0x0, 0x8, 0x0, ResourceValue.Type.REFERENCE.code()
                                        },
                                        Ints.toByteArray(0x12345678))));

        assertEquals("@ref/0x12345678", BinaryXmlParser.formatValue(value1, null));
        assertEquals(
                "@package:restype/res_name", BinaryXmlParser.formatValue(value1, null, resolver));
    }
}
