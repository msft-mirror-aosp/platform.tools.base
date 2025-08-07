/*
 * Copyright (C) 2025 The Android Open Source Project
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

import com.google.common.io.ByteStreams;

import kotlin.jvm.internal.Lambda;

import org.junit.Assert;
import org.junit.Test;

import java.io.InputStream;

public class BytecodeValidatorTest {

    private static class NonLambda {}

    private static class AnotherNonLambda {
        public void publicMethod() {}
    }

    private static class LambdaA extends Lambda {
        public LambdaA() {
            super(0);
        }

        public void publicMethod() {}

        private void privateMethod() {}
    }

    private static class LambdaB extends Lambda {
        public LambdaB() {
            super(0);
        }

        public void publicMethod() {}

        public void anotherPublicMethod() {}

        private void privateMethod() {}
    }

    private Interpretable toInterpretable(Class<?> clazz) throws Exception {
        String resourceName = clazz.getName().replace('.', '/') + ".class";
        try (InputStream stream = clazz.getClassLoader().getResourceAsStream(resourceName)) {
            if (stream == null) {
                throw new RuntimeException("Couldn't load class: " + clazz.getName());
            }
            byte[] bytes = ByteStreams.toByteArray(stream);
            return new Interpretable(bytes);
        }
    }

    @Test
    public void testNonLambdaClasses() throws Exception {
        // Ignore incompatible interfaces with non-lambda classes
        Interpretable current = toInterpretable(AnotherNonLambda.class);
        Interpretable next = toInterpretable(NonLambda.class);
        Assert.assertTrue(BytecodeValidator.checkCompatibleUpdate(current, next));
    }

    @Test
    public void testCompatibleChange() throws Exception {
        // Adding a new public method does not break compatibility
        Interpretable current = toInterpretable(LambdaA.class);
        Interpretable next = toInterpretable(LambdaB.class);
        Assert.assertTrue(BytecodeValidator.checkCompatibleUpdate(current, next));
    }

    @Test
    public void testIncompatibleChange() throws Exception {
        // Removing public method breaks compatibility
        Interpretable current = toInterpretable(LambdaB.class);
        Interpretable next = toInterpretable(LambdaA.class);
        Assert.assertFalse(BytecodeValidator.checkCompatibleUpdate(current, next));
    }

    @Test
    public void testCompatibleChangeWithClass() throws Exception {
        // Adding a new public method does not break compatibility
        Interpretable next = toInterpretable(LambdaB.class);
        Assert.assertTrue(BytecodeValidator.checkCompatibleUpdate(LambdaA.class, next));
    }

    @Test
    public void testIncompatibleChangeWithClass() throws Exception {
        // Removing public method breaks compatibility
        Interpretable next = toInterpretable(LambdaA.class);
        Assert.assertFalse(BytecodeValidator.checkCompatibleUpdate(LambdaB.class, next));
    }
}
