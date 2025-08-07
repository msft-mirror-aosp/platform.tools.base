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

import kotlin.coroutines.Continuation;
import kotlin.coroutines.jvm.internal.RestrictedSuspendLambda;
import kotlin.coroutines.jvm.internal.SuspendLambda;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import kotlin.jvm.internal.AdaptedFunctionReference;
import kotlin.jvm.internal.FunctionReference;
import kotlin.jvm.internal.FunctionReferenceImpl;

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
                actual,
                LiveEditStubs.stubI(
                        LiveEditStubs.getClassBytecode(TEST_CLASS),
                        "testSuspend",
                        "()I",
                        new Object[2]));
    }

    @Test
    public void testRestrictedSuspend() throws Exception {
        int actual = LambdasKt.testRestrictedSuspend();
        Assert.assertEquals(
                actual,
                LiveEditStubs.stubI(
                        LiveEditStubs.getClassBytecode(TEST_CLASS),
                        "testRestrictedSuspend",
                        "()I",
                        new Object[2]));
    }

    @Test
    public void escapedSuspendLambda() throws Exception {
        Function1<Continuation<? super Integer>, Object> expected = LambdasKt.returnSuspendLambda();
        //noinspection unchecked
        Function1<Continuation<? super Integer>, Object> actual =
                (Function1<Continuation<? super Integer>, Object>)
                        LiveEditStubs.stubL(
                                LiveEditStubs.getClassBytecode(TEST_CLASS),
                                "returnSuspendLambda",
                                "()Lkotlin/jvm/functions/Function1;",
                                new Object[2]);
        Assert.assertTrue(expected instanceof SuspendLambda);
        Assert.assertTrue(actual instanceof SuspendLambda);
        Assert.assertEquals(expected.invoke(null), actual.invoke(null));
    }

    @Test
    public void escapedRestrictedSuspendLambda() throws Exception {
        Function2<Restricts, Continuation<? super Integer>, Object> expected =
                LambdasKt.returnRestrictedSuspendLambda();
        //noinspection unchecked
        Function2<Restricts, Continuation<? super Integer>, Object> actual =
                (Function2<Restricts, Continuation<? super Integer>, Object>)
                        LiveEditStubs.stubL(
                                LiveEditStubs.getClassBytecode(TEST_CLASS),
                                "returnRestrictedSuspendLambda",
                                "()Lkotlin/jvm/functions/Function2;",
                                new Object[2]);
        Assert.assertTrue(expected instanceof RestrictedSuspendLambda);
        Assert.assertTrue(actual instanceof RestrictedSuspendLambda);
        Restricts receiver = new Restricts();
        Assert.assertEquals(expected.invoke(receiver, null), actual.invoke(receiver, null));
    }

    @Test
    public void testAsyncAwait() throws Exception {
        int actual = LambdasKt.testAsyncAwait();
        Assert.assertEquals(
                actual,
                LiveEditStubs.stubI(
                        LiveEditStubs.getClassBytecode(TEST_CLASS),
                        "testAsyncAwait",
                        "()I",
                        new Object[2]));
    }

    @Test
    public void testLaunchJoin() throws Exception {
        int actual = LambdasKt.testLaunchJoin();
        Assert.assertEquals(
                actual,
                LiveEditStubs.stubI(
                        LiveEditStubs.getClassBytecode(TEST_CLASS),
                        "testLaunchJoin",
                        "()I",
                        new Object[2]));
    }

    @Test
    public void testFunctionReference() throws Exception {
        int expected = LambdasKt.testFunctionReference();
        Assert.assertEquals(
                expected,
                LiveEditStubs.stubI(
                        LiveEditStubs.getClassBytecode(TEST_CLASS),
                        "testFunctionReference",
                        "()I",
                        new Object[2]));
    }

    @Test
    public void escapedFunctionReference() {
        Function0<Integer> expected = LambdasKt.returnFunctionReference();
        //noinspection unchecked
        Function0<Integer> actual =
                (Function0<Integer>)
                        LiveEditStubs.stubL(
                                LiveEditStubs.getClassBytecode(TEST_CLASS),
                                "returnFunctionReference",
                                "()Lkotlin/jvm/functions/Function0;",
                                new Object[2]);
        Assert.assertEquals(expected.invoke(), actual.invoke());
        Assert.assertTrue(expected instanceof FunctionReference);
        Assert.assertTrue(expected instanceof FunctionReferenceImpl);
        Assert.assertTrue(actual instanceof FunctionReference);
        Assert.assertTrue(actual instanceof FunctionReferenceImpl);
    }

    @Test
    public void testAdaptedFunctionReference() throws Exception {
        int expected = LambdasKt.testAdaptedReference();
        Assert.assertEquals(
                expected,
                LiveEditStubs.stubI(
                        LiveEditStubs.getClassBytecode(TEST_CLASS),
                        "testAdaptedReference",
                        "()I",
                        new Object[2]));
    }

    @Test
    public void escapedAdaptedReference() {
        Function0<Integer> expected = LambdasKt.returnAdaptedReference();
        @SuppressWarnings("unchecked")
        Function0<Integer> actual =
                (Function0<Integer>)
                        LiveEditStubs.stubL(
                                LiveEditStubs.getClassBytecode(TEST_CLASS),
                                "returnAdaptedReference",
                                "()Lkotlin/jvm/functions/Function0;",
                                new Object[2]);
        Assert.assertEquals(expected.invoke(), actual.invoke());
        Assert.assertTrue(expected instanceof AdaptedFunctionReference);
        Assert.assertTrue(actual instanceof AdaptedFunctionReference);
    }

    @Test
    public void testCoroutineThreading() {
        String[] expected = LambdasKt.testProperThreading();
        // Validate that our assumptions about coroutines are correct:
        // - Control should start and resume on the same (main) thread
        // - withContext() should move control to different thread
        Assert.assertEquals(expected[0], expected[2]);
        Assert.assertNotEquals(expected[1], expected[0]);

        String[] actual =
                (String[])
                        LiveEditStubs.stubL(
                                LiveEditStubs.getClassBytecode(TEST_CLASS),
                                "testProperThreading",
                                "()[Ljava/lang/String;",
                                new Object[2]);

        // Now validate that the Live-Edited version behaves similarly
        Assert.assertEquals(actual[0], actual[2]);
        Assert.assertNotEquals(actual[1], actual[0]);
    }
}
