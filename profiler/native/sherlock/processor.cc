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

#include "processor.h"

// Makes it shorter to refer to the perfetto.pb.h contents.
namespace p = perfetto;

// Makes it shorter to refer to Trace Processor.
namespace ptp = perfetto::trace_processor;

std::unique_ptr<ptp::TraceProcessor> new_processor() {
  ptp::Config config;
  // config.drop_ftrace_data_before = ...
  std::unique_ptr<ptp::TraceProcessor> ptr(
      ptp::TraceProcessor::CreateInstance(config));
  return ptr;
}

bool parse_data(ptp::TraceProcessor* tp, const void* data, size_t size) {
  std::unique_ptr<uint8_t[]> buf(new uint8_t[size]);
  fprintf(stderr, "memcpy start\n");
  memcpy(buf.get(), data, size);
  fprintf(stderr, "memcpy end\n");

  if (!tp->Parse(std::move(buf), size).ok()) {
    return false;
  }

  tp->NotifyEndOfFile();
  return true;
}

void execute_query(ptp::TraceProcessor* tp, const char* query,
                   p::QueryResult* result) {
  auto it = tp->ExecuteQuery(query);

  // Add columns and column descriptors to proto.
  for (uint32_t col = 0; col < it.ColumnCount(); col++) {
    auto* descriptor = result->add_column_descriptors();
    descriptor->set_name(it.GetColumnName(col));
    descriptor->set_type(p::QueryResult::ColumnDesc::UNKNOWN);
    result->add_columns();
  }

  // Add values and is_nulls to proto, for each row and column.
  uint32_t rows = 0;
  for (; it.Next(); rows++) {
    for (uint32_t col = 0; col < it.ColumnCount(); col++) {
      auto* column = result->mutable_columns(static_cast<int>(col));
      auto* desc = result->mutable_column_descriptors(static_cast<int>(col));
      auto value = it.Get(col);

      switch (desc->type() << 8 | value.type) {
        // Nulls.
        case p::QueryResult::ColumnDesc::UNKNOWN << 8 | ptp::SqlValue::kNull:
          // Don't yet know the column type. Add a null value for each.
          column->add_long_values(0);
          column->add_double_values(0);
          column->add_string_values("");
          column->add_is_nulls(true);
          break;
        case p::QueryResult::ColumnDesc::LONG << 8 | ptp::SqlValue::kNull:
          column->add_long_values(0);
          column->add_is_nulls(true);
          break;
        case p::QueryResult::ColumnDesc::DOUBLE << 8 | ptp::SqlValue::kNull:
          column->add_double_values(0);
          column->add_is_nulls(true);
          break;
        case p::QueryResult::ColumnDesc::STRING << 8 | ptp::SqlValue::kNull:
          column->add_string_values("");
          column->add_is_nulls(true);
          break;

          // Values matching the type.
        case p::QueryResult::ColumnDesc::UNKNOWN << 8 | ptp::SqlValue::kString:
          desc->set_type(p::QueryResult::ColumnDesc::STRING);
          column->clear_long_values();
          column->clear_double_values();
          // fall-through.
        case p::QueryResult::ColumnDesc::STRING << 8 | ptp::SqlValue::kString:
          column->add_string_values(value.string_value);
          column->add_is_nulls(false);
          break;
        case p::QueryResult::ColumnDesc::UNKNOWN << 8 | ptp::SqlValue::kLong:
          desc->set_type(p::QueryResult::ColumnDesc::LONG);
          column->clear_string_values();
          column->clear_double_values();
          // fall-through.
        case p::QueryResult::ColumnDesc::LONG << 8 | ptp::SqlValue::kLong:
          column->add_long_values(value.long_value);
          column->add_is_nulls(false);
          break;
        case p::QueryResult::ColumnDesc::UNKNOWN << 8 | ptp::SqlValue::kDouble:
          desc->set_type(p::QueryResult::ColumnDesc::DOUBLE);
          column->clear_string_values();
          column->clear_long_values();
          // fall-through.
        case p::QueryResult::ColumnDesc::DOUBLE << 8 | ptp::SqlValue::kDouble:
          column->add_double_values(value.double_value);
          column->add_is_nulls(false);
          break;

          // Values needing conversion.
        case p::QueryResult::ColumnDesc::LONG << 8 | ptp::SqlValue::kDouble:
          // TODO: should we "upgrade" the column to double?
          column->add_long_values(static_cast<int64_t>(value.double_value));
          column->add_is_nulls(false);
          break;
        case p::QueryResult::ColumnDesc::DOUBLE << 8 | ptp::SqlValue::kLong:
          column->add_double_values(static_cast<double>(value.long_value));
          column->add_is_nulls(false);
          break;
        default:
          // ignore mismatched numeric/string values.
          break;
      }
    }
  }

  result->set_num_records(rows);

  auto status = it.Status();
  if (!status.ok()) {
    fprintf(stderr, "Query has error: %s\n", query);
    result->set_error(status.message());
  }
}
