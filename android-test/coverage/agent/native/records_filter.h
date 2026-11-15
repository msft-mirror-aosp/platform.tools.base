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

#ifndef COVERAGE_AGENT_RECORDS_FILTER_H_
#define COVERAGE_AGENT_RECORDS_FILTER_H_

#include "tools/base/android-test/coverage/agent/native/filter_interface.h"

namespace coverage {

/**
 * Filters the methods that javac generates for records: toString(),
 * hashCode(), equals(Object) and the accessors, which return their field. D8
 * rewrites the first three: toString() passes the values of the fields from
 * $record$getFieldsAsObjects() to a method that D8 generates, hashCode()
 * passes the fields to another one, or returns 0 in a record without
 * components, and equals(Object) calls $record$equals(Object). D8 also
 * replaces the superclass Record with RecordTag when the minimum API level
 * lacks Record.
 *
 * An accessor written in the source that only returns its field, and a final
 * hashCode() written in a record without components that only returns 0, are
 * the same code as the generated ones, so they are filtered too.
 */
class RecordsFilter : public IFilter {
 public:
  void Filter(const FilterContext& context, ir::EncodedMethod* ir_method,
              lir::CodeIr& code_ir, FilterOutput& output) override;
};

}  // namespace coverage

#endif  // COVERAGE_AGENT_RECORDS_FILTER_H_
