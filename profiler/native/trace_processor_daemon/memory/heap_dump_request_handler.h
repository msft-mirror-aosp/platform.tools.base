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

#ifndef _TRACE_PROCESSOR_DAEMON_HEAP_DUMP_REQUEST_HANDLER_H_
#define _TRACE_PROCESSOR_DAEMON_HEAP_DUMP_REQUEST_HANDLER_H_

#include "perfetto/trace_processor/trace_processor.h"
#include "proto/trace_processor_service.pb.h"

namespace profiler {
namespace perfetto {
// Handles query execution and SQL aggregation for heap dumps against Perfetto
// Trace Processor.
class HeapDumpRequestHandler {
 public:
  HeapDumpRequestHandler(::perfetto::trace_processor::TraceProcessor* processor)
      : processor_(processor) {}
  ~HeapDumpRequestHandler() {}

  // Populates aggregated class overview, shallow sizes, native sizes, and
  // retained sizes across all heaps.
  void PopulateEvents(proto::HeapDumpResult* result);

  // Populates scalar primitive fields and raw array payloads for requested
  // object instances.
  void PopulatePrimitiveFields(
      const proto::QueryParameters::GetPrimitiveFieldsParameters& request,
      proto::GetPrimitiveFieldsResult* result);

  // Queries instances for a given class or set of IDs with pagination and
  // sorting.
  //
  // Contract: Requires PopulateEvents to have been called beforehand on the
  // session to load required Perfetto dominator tree modules.
  void PopulateInstances(
      const proto::QueryParameters::HeapDumpInstancesParameters& request,
      proto::HeapDumpInstancesResult* result);

  // Resolves reference chains for requested object instances.
  void PopulateReferences(
      const proto::QueryParameters::GetReferencesParameters& request,
      proto::GetReferencesResult* result);

 private:
  ::perfetto::trace_processor::TraceProcessor* processor_;
};

// Helper function to escape single quotes and strip embedded null bytes for
// SQLite query literals.
std::string EscapeSqlString(const std::string& input);

}  // namespace perfetto
}  // namespace profiler
#endif  //  _TRACE_PROCESSOR_DAEMON_HEAP_DUMP_REQUEST_HANDLER_H_
