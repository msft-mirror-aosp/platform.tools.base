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
 * Instrumented by instrumenter_test.cc, whose test filter handles each method as its name says.
 *
 * <p>The test refers to lines of this file.
 */
class InstrumenterTestTarget {

    static void generated() {}

    static void user() {}

    static void ignoredCall() {
        generated();
        user();
    }

    static void ignoredBlock(boolean condition) {
        if (condition) {
            generated();
        }
        user();
    }

    static void ignoredBranch(boolean condition) {
        user();
        if (condition) {
            user();
        }
    }

    static void replacedBranches(boolean condition) {
        if (condition) {
            user();
        } else {
            user();
        }
        user();
    }

    static void allIgnored() {
        generated();
    }
}
