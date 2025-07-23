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
package com.android.tools.deployer;

import com.android.tools.deploy.proto.Deploy;
import com.android.tools.idea.protobuf.ByteString;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Map;

public class LiveEditBytecodeVersioningTest extends LiveEditTestBase {
    @Before
    public void setup() {
        android.loadDex(DEX_LOCATION + ":" + LIVE_EDIT_LAMBDA_DEX_LOCATION);
        android.launchActivity(ACTIVITY_CLASS);
    }

    // Ensures we properly version when existing instances change interface. If bytecode versioning
    // is disabled by setting BytecodeValidator.checkCompatibleUpdate(Class<?>, Interpretable) to
    // always return true, this test will cause the FakeAndroid activity to crash with an error
    // due to Live Edit not finding the proper invoke() method on the object instance.
    @Test
    public void incompatibleUpdateExistingInstances() throws IOException {
        android.triggerMethod(ACTIVITY_CLASS, "invokeVersionIncompatible");
        android.waitForInput("100");

        Deploy.AgentLiveEditResponse nextResponse =
                sendUpdateRequest(makeRequest(CompileClassLocation.KOTLIN_SWAPPED_LOCATION));
        Assert.assertEquals(Deploy.AgentLiveEditResponse.Status.OK, nextResponse.getStatus());
        android.triggerMethod(ACTIVITY_CLASS, "invokeVersionIncompatible");
        Assert.assertTrue(android.waitForInput("100"));
    }

    // Ensures we properly version when proxy instances change interface. If bytecode versioning
    // is disabled by setting BytecodeValidator.checkCompatibleUpdate(Interpretable, Interpretable)
    // to always return true, this test will cause the FakeAndroid activity to crash with an error
    // due to Live Edit not finding the proper invoke() method in the proxy instance's bytecode.
    @Test
    public void incompatibleUpdateProxyInstances() throws IOException {
        Deploy.AgentLiveEditResponse response =
                sendUpdateRequest(makeRequest(CompileClassLocation.KOTLIN_ORIGINAL_LOCATION));
        Assert.assertEquals(Deploy.AgentLiveEditResponse.Status.OK, response.getStatus());

        android.triggerMethod(ACTIVITY_CLASS, "invokeVersionIncompatible");
        android.waitForInput("100");

        Deploy.AgentLiveEditResponse nextResponse =
                sendUpdateRequest(makeRequest(CompileClassLocation.KOTLIN_SWAPPED_LOCATION));
        Assert.assertEquals(Deploy.AgentLiveEditResponse.Status.OK, nextResponse.getStatus());
        android.triggerMethod(ACTIVITY_CLASS, "invokeVersionIncompatible");
        Assert.assertTrue(android.waitForInput("100"));
    }

    @Test
    public void compatibleUpdateExistingInstances() throws IOException {
        android.triggerMethod(ACTIVITY_CLASS, "invokeVersionCompatible");
        android.waitForInput("100");

        Deploy.AgentLiveEditResponse nextResponse =
                sendUpdateRequest(makeRequest(CompileClassLocation.KOTLIN_SWAPPED_LOCATION));
        Assert.assertEquals(Deploy.AgentLiveEditResponse.Status.OK, nextResponse.getStatus());
        android.triggerMethod(ACTIVITY_CLASS, "invokeVersionCompatible");
        Assert.assertTrue(android.waitForInput("999"));
    }

    @Test
    public void compatibleUpdateProxyInstances() throws IOException {
        Deploy.AgentLiveEditResponse response =
                sendUpdateRequest(makeRequest(CompileClassLocation.KOTLIN_ORIGINAL_LOCATION));
        Assert.assertEquals(Deploy.AgentLiveEditResponse.Status.OK, response.getStatus());

        android.triggerMethod(ACTIVITY_CLASS, "invokeVersionCompatible");
        android.waitForInput("100");

        Deploy.AgentLiveEditResponse nextResponse =
                sendUpdateRequest(makeRequest(CompileClassLocation.KOTLIN_SWAPPED_LOCATION));
        Assert.assertEquals(Deploy.AgentLiveEditResponse.Status.OK, nextResponse.getStatus());
        android.triggerMethod(ACTIVITY_CLASS, "invokeVersionCompatible");
        Assert.assertTrue(android.waitForInput("999"));
    }

    private Deploy.LiveEditRequest makeRequest(CompileClassLocation location) throws IOException {
        Deploy.LiveEditRequest.Builder builder =
                Deploy.LiveEditRequest.newBuilder()
                        .setPackageName(PACKAGE)
                        .setInvalidateMode(Deploy.LiveEditRequest.InvalidateMode.INVALIDATE_GROUPS);

        final String targetClassName = "pkg/KotlinBytecodeVersioningKt";
        builder.addTargetClassesBuilder()
                .setClassName(targetClassName)
                .setClassData(
                        ByteString.copyFrom(getClassBytes(targetClassName + ".class", location)))
                .build();

        for (Map.Entry<String, byte[]> entry :
                getInnerClassBytes(targetClassName, location).entrySet()) {
            builder.addSupportClassesBuilder()
                    .setClassName(entry.getKey())
                    .setClassData(ByteString.copyFrom(entry.getValue()))
                    .build();
        }
        return builder.build();
    }
}
