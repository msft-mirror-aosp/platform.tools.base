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

    // javac gives Color values(), valueOf(String) and a constructor that only
    // passes the name and the ordinal of a constant to the constructor of Enum.
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

    // javac compiles the body of the lambda into the synthetic method
    // lambda$userAction$0.
    static Runnable userAction() {
        return () -> user();
    }

    // D8 moves the code of the default methods to the companion class
    // Command$-CC when it desugars them, with the body of the lambda of later()
    // as $private$lambda$later$0, and gives DefaultCommand synthetic methods
    // that call them.
    interface Command {
        default void invoke() {
            user();
        }

        default Runnable later() {
            return () -> invoke();
        }
    }

    static final class DefaultCommand implements Command {}

    // javac compiles a switch on a String into a switch on the hash code of
    // the String, whose cases compare the String with the strings and load the
    // index of the one that matches, followed by a second switch on that
    // index, all on the line of the switch.
    static int sizeCode(String name) {
        switch (name) {
            case "small":
                return 1;
            case "medium":
                return 2;
            default:
                return 0;
        }
    }
}
