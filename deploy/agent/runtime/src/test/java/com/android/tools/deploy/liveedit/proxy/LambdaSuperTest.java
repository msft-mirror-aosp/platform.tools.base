/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tools.deploy.liveedit;


import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class LambdaSuperTest {
    private static final String TEST_CLASS = "com/android/tools/deploy/liveedit/LambdasKt";

    @BeforeClass
    public static void before() throws Exception {
        LambdaUtils.loadClassAndLambdaClasses(LambdasKt.class);
    }

    @Test
    public void testSuspend() throws Exception {
        int actual = LambdasKt.testSuspend();
        Assert.assertEquals(
                actual, LiveEditStubs.stubI(TEST_CLASS, "testSuspend", "()I", new Object[2]));
    }

    @Test
    public void testRestrictedSuspend() throws Exception {
        int actual = LambdasKt.testRestrictedSuspend();
        Assert.assertEquals(
                actual,
                LiveEditStubs.stubI(TEST_CLASS, "testRestrictedSuspend", "()I", new Object[2]));
    }

    @Test
    public void testAsyncAwait() throws Exception {
        int actual = LambdasKt.testAsyncAwait();
        Assert.assertEquals(
                actual, LiveEditStubs.stubI(TEST_CLASS, "testAsyncAwait", "()I", new Object[2]));
    }

    @Test
    public void testLaunchJoin() throws Exception {
        int actual = LambdasKt.testLaunchJoin();
        Assert.assertEquals(
                actual, LiveEditStubs.stubI(TEST_CLASS, "testLaunchJoin", "()I", new Object[2]));
    }

    @Test
    public void testFunctionReference() throws Exception {
        int expected = LambdasKt.testFunctionReference();
        Assert.assertEquals(
                expected,
                LiveEditStubs.stubI(TEST_CLASS, "testFunctionReference", "()I", new Object[2]));
    }

    @Test
    public void testAdaptedFunctionReference() throws Exception {
        int expected = LambdasKt.testAdaptedReference();
        Assert.assertEquals(
                expected,
                LiveEditStubs.stubI(TEST_CLASS, "testAdaptedReference", "()I", new Object[2]));
    }
}
