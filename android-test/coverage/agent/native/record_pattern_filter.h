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

#ifndef COVERAGE_AGENT_RECORD_PATTERN_FILTER_H_
#define COVERAGE_AGENT_RECORD_PATTERN_FILTER_H_

#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

/**
 * Filters the handler that javac gives the calls to the accessors of record
 * patterns. It catches any exception (javac catches Throwable, which D8 turns
 * into a catch of any exception) and throws a MatchException with it as the
 * cause, which D8 replaces with a RuntimeException:
 *     move-exception vC
 *     new-instance vE, Ljava/lang/RuntimeException;  (or MatchException)
 *     invoke-virtual {vC}, Ljava/lang/Throwable;->toString()Ljava/lang/String;
 *     move-result-object vM
 *     invoke-direct {vE, vM, vC},
 *         Ljava/lang/RuntimeException;-><init>(Ljava/lang/String;
 *                                              Ljava/lang/Throwable;)V
 *     throw vE
 * The try blocks of all the patterns of a method can share the handler, which
 * D8 can split with gotos. In release mode, D8 can also copy the exception to
 * other registers first. Kotlin has no record patterns, so the filter only runs
 * on other classes. A catch of Throwable written in the source that
 * throws new RuntimeException(t.toString(), t) compiles to the same handler,
 * so it is filtered too.
 */
class RecordPatternFilter : public IFilter {
 public:
  void Filter(const FilterContext& context, ir::EncodedMethod* ir_method,
              lir::CodeIr& code_ir, FilterOutput& output) override;
};

}  // namespace coverage

#endif  // COVERAGE_AGENT_RECORD_PATTERN_FILTER_H_
