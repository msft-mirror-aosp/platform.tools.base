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

#include <stddef.h>
#include <stdint.h>

#include <memory>

#include "perfetto/trace_processor/trace_processor.h"
#include "proto/perfetto.pb.h"

typedef struct {
  size_t size;
  uint8_t* data;
} result;

std::unique_ptr<perfetto::trace_processor::TraceProcessor> new_processor();

bool parse_data(perfetto::trace_processor::TraceProcessor* tp, const void* data,
                size_t size);

void execute_query(perfetto::trace_processor::TraceProcessor* tp,
                   const char* query, perfetto::QueryResult* result);
