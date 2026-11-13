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

    // Cases 1 and 2 share their code, and case 3 goes to the default code.
    static void sharedCases(int value) {
        switch (value) {
            case 1:
            case 2:
                user();
                break;
            case 4:
                generated();
                break;
            default:
                break;
        }
    }

    interface Action<T> {
        T invoke();
    }

    // javac gives Constant a synthetic bridge invoke(): Object that calls
    // invoke(): String.
    static final class Constant implements Action<String> {
        @Override
        public String invoke() {
            return "constant";
        }
    }

    // javac gives Color a constructor that only passes the name and the ordinal
    // of a constant to the constructor of Enum.
    enum Color {
        RED,
        GREEN
    }

    // The handler of the synchronized block exits the monitor and throws the
    // exception again.
    static void synchronizedBlock(Object lock) {
        synchronized (lock) {
            user();
        }
    }

    // D8 exits the monitor of a synchronized method itself, with the same
    // handler.
    static synchronized void synchronizedMethod() {
        user();
    }

    // For an enum that another compilation unit declares, such as TimeUnit,
    // javac puts the table that maps its constants to the cases of the switch
    // into the synthetic class InstrumenterTestTarget$1, whose static
    // initializer fills it on the line of the switch.
    static int unitCode(java.util.concurrent.TimeUnit unit) {
        switch (unit) {
            case SECONDS:
                return 1;
            default:
                return 0;
        }
    }
}
