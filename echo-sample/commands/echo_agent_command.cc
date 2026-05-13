/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include "echo_agent_command.h"

#include "agent/agent.h"
#include "jvmti/jvmti_helper.h"

using profiler::Agent;
using profiler::proto::Command;

void EchoAgentCommand::RegisterAgentEchoCommandHandler(JavaVM* vm) {
  // Register command handlers for agent based commands.
  Agent::Instance().RegisterCommandHandler(
      Command::ECHO, [vm](const Command* command) -> void {
        JNIEnv* jni_env = profiler::GetThreadLocalJNI(vm);
        if (jni_env == nullptr) return;

        // Grab a java Class object to represent our echo class.
        jclass echo_class =
            jni_env->FindClass("com/android/tools/agent/echo/EchoService");
        // SECURITY FIX: Unhandled JNI exceptions can cause the JVM to abort,
        // leading to a Denial of Service (DoS) of the profiled application. We
        // must check for and clear any exceptions after JNI calls.
        if (jni_env->ExceptionCheck()) {
          jni_env->ExceptionClear();
          return;
        }

        // Grab our static instance method.
        jmethodID instance_method = jni_env->GetStaticMethodID(
            echo_class, "Instance",
            "()Lcom/android/tools/agent/echo/EchoService;");
        if (jni_env->ExceptionCheck()) {
          jni_env->ExceptionClear();
          return;
        }

        // Call it to grab a pointer to our echo service.
        jobject echo_service =
            jni_env->CallStaticObjectMethod(echo_class, instance_method);
        if (jni_env->ExceptionCheck()) {
          jni_env->ExceptionClear();
          return;
        }

        // Grab a handle to our echo command method.
        jmethodID echo_command_method = jni_env->GetMethodID(
            echo_class, "onEchoCommand", "(Ljava/lang/String;)V");
        if (jni_env->ExceptionCheck()) {
          jni_env->ExceptionClear();
          return;
        }

        // Call it with our command arguments.
        jstring message =
            jni_env->NewStringUTF(command->echo_data().data().c_str());
        if (jni_env->ExceptionCheck()) {
          jni_env->ExceptionClear();
          return;
        }

        jni_env->CallVoidMethod(echo_service, echo_command_method, message);
        if (jni_env->ExceptionCheck()) {
          jni_env->ExceptionClear();
        }
        jni_env->DeleteLocalRef(message);
      });
}
