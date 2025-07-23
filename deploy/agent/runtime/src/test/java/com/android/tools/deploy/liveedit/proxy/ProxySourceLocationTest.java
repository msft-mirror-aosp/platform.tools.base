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

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public class ProxySourceLocationTest {
    private static final String EXPECTED_FILE_NAME = "InspectorLambdas.kt";
    private static final String EXPECTED_CLASS_NAME =
            "com.android.tools.deploy.liveedit.InspectorLambdas";
    private String testClass;

    @Before
    public void before() throws Exception {
        testClass = LambdaUtils.loadClassAndLambdaClasses(InspectorLambdas.class);
    }

    @Test
    public void testLambdaLocations() {
        InspectorLambdas tester = new InspectorLambdas();
        LiveEditStubs.stubV(
                LiveEditStubs.getClassBytecode(testClass),
                "run",
                "()V",
                new Object[] {tester, tester});

        assertLocation(tester.getLambdas().get(0), "$run$1", 22, 22);
        assertLocation(tester.getLambdas().get(1), "$run$2", 23, 23);
        assertLocation(tester.getLambdas().get(2), "$run$3", 24, 30);
    }

    private Map<String, Object> resolve(Object lambda) {
        try {
            Method method = null;
            for (Class<?> i : lambda.getClass().getInterfaces()) {
                if (i.getTypeName()
                        .equals("com.android.tools.deploy.liveedit.SourceLocationAware")) {
                    method = i.getDeclaredMethod("getSourceLocationInfo");
                    break;
                }
            }

            if (method == null) {
                return new HashMap<>();
            }

            //noinspection unchecked
            return (Map<String, Object>) method.invoke(lambda);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private void assertLocation(Object lambda, String lambdaName, int start, int end) {
        ProxySourceLocation expected =
                new ProxySourceLocation(
                        EXPECTED_CLASS_NAME + lambdaName, EXPECTED_FILE_NAME, start, end);
        Map<String, Object> location = resolve(lambda);
        Assert.assertEquals(expected.asMap(), resolve(lambda));
    }
}
