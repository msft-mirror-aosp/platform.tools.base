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

#ifndef COVERAGE_AGENT_TRY_WITH_RESOURCES_FILTER_H_
#define COVERAGE_AGENT_TRY_WITH_RESOURCES_FILTER_H_

#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

/**
 * Filters the code that javac generates for a try-with-resources statement:
 * the close of the resource when the block ends, and the handler that closes
 * it when the block throws. javac catches Throwable, which D8 turns into a
 * catch of any exception:
 *     if-eqz vR, :next  (left out when the resource is created with new)
 *     invoke-virtual {vR}, LFoo;->close()V  (or invoke-interface)
 *   :next
 *     ...
 *   :handler
 *     move-exception vP
 *     if-eqz vR, :throw  (left out when the resource is created with new)
 *     invoke-virtual {vR}, LFoo;->close()V  (its try block goes to :suppress)
 *     goto :throw
 *   :suppress
 *     move-exception vT
 *     invoke-virtual {vP, vT},
 *         Ljava/lang/Throwable;->addSuppressed(Ljava/lang/Throwable;)V
 *   :throw
 *     throw vP
 * Below API 19, D8 calls a static backport of addSuppressed instead. Instead of
 * close(), D8 can call the method that the class of the resource had before it
 * became AutoCloseable, such as recycle() of TypedArray, or a static method
 * that it generates and that takes the resource, such as the one that closes
 * resources only known as AutoCloseable from API 19 on. D8 can split the
 * handler with gotos and move registers in it. D8 also puts the handlers after
 * all the code that follows the statement, it can copy the resource to another
 * register before the close, and it can reuse those registers once the
 * statement ends. So the close when the block ends is the last close of the
 * same resource with the same method that comes after the start of the block,
 * outside it, and before the handler and the next writes of the registers that
 * hold the resource. A return in the block keeps its own close, and nothing is
 * filtered without a close, as when the block always throws, or when the block
 * can't throw and D8 removes the handler. The close, the suppression and the
 * throws of the handler must share a line, the line of the try statement: D8
 * lays out the same code written by hand in the same order, and only the lines
 * tell them apart, so code without line numbers isn't told apart. Older javac
 * versions generate other code, which isn't filtered. Kotlin has no
 * try-with-resources statement, so the filter only runs on other classes.
 */
class TryWithResourcesFilter : public IFilter {
 public:
  void Filter(const FilterContext& context, ir::EncodedMethod* ir_method,
              lir::CodeIr& code_ir, FilterOutput& output) override;
};

}  // namespace coverage

#endif  // COVERAGE_AGENT_TRY_WITH_RESOURCES_FILTER_H_
