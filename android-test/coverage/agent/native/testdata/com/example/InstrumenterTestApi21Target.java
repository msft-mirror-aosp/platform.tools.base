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

package com.example;

/**
 * Instrumented by instrumenter_test.cc after d8 compiled it for API 21, as apps build it.
 *
 * <p>The test refers to lines of this file.
 */
class InstrumenterTestApi21Target {

    // From API 19 on, D8 closes a resource that is only known as AutoCloseable
    // through a static method of a class that it generates.
    static int firstChar(String text) throws Exception {
        try (AutoCloseable resource = open(text)) {
            return resource.hashCode();
        }
    }

    static AutoCloseable open(String text) {
        return text.isEmpty() ? null : new java.io.StringReader(text);
    }

    // With more values than low registers, D8 copies the resource to another
    // register before its close, to make the long from the read.
    static long sum(int a) throws java.io.IOException {
        int i0 = a;
        int i1 = a + 1;
        int i2 = a + 2;
        int i3 = a + 3;
        int i4 = a + 4;
        int i5 = a + 5;
        int i6 = a + 6;
        int i7 = a + 7;
        int i8 = a + 8;
        int i9 = a + 9;
        int i10 = a + 10;
        int i11 = a + 11;
        int i12 = a + 12;
        int i13 = a + 13;
        long read;
        try (java.io.Reader reader = reader(a)) {
            read = reader.read();
        }
        return read + i0 + i1 + i2 + i3 + i4 + i5 + i6 + i7 + i8 + i9 + i10 + i11 + i12 + i13;
    }

    static java.io.Reader reader(int a) {
        return a == 0 ? null : new java.io.StringReader(String.valueOf(a));
    }
}
