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

#include "heap_dump_request_handler.h"

#include <gtest/gtest.h>

#include "perfetto/trace_processor/basic_types.h"
#include "perfetto/trace_processor/read_trace.h"
#include "perfetto/trace_processor/trace_processor.h"

namespace profiler {
namespace perfetto {
namespace {

using ::perfetto::trace_processor::Config;
using ::perfetto::trace_processor::ReadTrace;
using ::perfetto::trace_processor::TraceProcessor;

const std::string HPROF_PATH(
    "tools/base/profiler/native/trace_processor_daemon/testdata/"
    "bitmap-duplicates.hprof");

std::unique_ptr<TraceProcessor> LoadTrace(const std::string& trace_path) {
  Config config;
  config.ingest_ftrace_in_raw_table = false;
  auto tp = TraceProcessor::CreateInstance(config);
  auto read_status = ReadTrace(tp.get(), trace_path.c_str(), [](uint64_t) {});
  EXPECT_TRUE(read_status.ok());
  return tp;
}

// Validates that heap dump events and aggregated class overviews are populated
// correctly from an HPROF file.
TEST(HeapDumpRequestHandlerTest, TestPopulateEvents) {
  auto tp = LoadTrace(HPROF_PATH);
  HeapDumpRequestHandler handler{tp.get()};
  proto::HeapDumpResult result;
  handler.PopulateEvents(&result);

  EXPECT_GT(result.class_overview_size(), 0);

  bool found_bitmap = false;
  for (const auto& class_overview : result.class_overview()) {
    if (class_overview.class_name() == "android.graphics.Bitmap" &&
        class_overview.heap_name() == "app") {
      found_bitmap = true;
      EXPECT_EQ(class_overview.instance_count(), 4);
      EXPECT_GT(class_overview.shallow_size(), 0);
      EXPECT_GT(class_overview.retained_size(), 0);
      break;
    }
  }
  EXPECT_TRUE(found_bitmap);
}

// Validates that instance querying with sorting and pagination works correctly.
TEST(HeapDumpRequestHandlerTest, TestPopulateInstances) {
  auto tp = LoadTrace(HPROF_PATH);
  HeapDumpRequestHandler handler{tp.get()};

  proto::HeapDumpResult events_result;
  handler.PopulateEvents(&events_result);

  proto::QueryParameters::HeapDumpInstancesParameters request;
  request.add_class_names("android.graphics.Bitmap");
  request.set_heap_name("app");
  request.set_sort_attribute(proto::QueryParameters::SORT_RETAINED_SIZE);
  request.set_sort_descending(true);

  proto::HeapDumpInstancesResult result;
  handler.PopulateInstances(request, &result);

  EXPECT_EQ(result.instance_size(), 4);

  // Verify descending sort order by retained size
  for (int i = 0; i < result.instance_size() - 1; ++i) {
    EXPECT_GE(result.instance(i).retained_size(),
              result.instance(i + 1).retained_size());
  }
}

// Validates primitive scalar fields resolution for an instance.
TEST(HeapDumpRequestHandlerTest, TestPopulatePrimitiveFields) {
  auto tp = LoadTrace(HPROF_PATH);
  HeapDumpRequestHandler handler{tp.get()};

  proto::HeapDumpResult events_result;
  handler.PopulateEvents(&events_result);

  // First fetch an instance ID for android.graphics.Bitmap (largest by retained
  // size is 800x800)
  proto::QueryParameters::HeapDumpInstancesParameters instances_request;
  instances_request.add_class_names("android.graphics.Bitmap");
  instances_request.set_heap_name("app");
  instances_request.set_sort_attribute(
      proto::QueryParameters::SORT_RETAINED_NATIVE_SIZE);
  instances_request.set_sort_descending(true);

  proto::HeapDumpInstancesResult instances_result;
  handler.PopulateInstances(instances_request, &instances_result);
  ASSERT_GT(instances_result.instance_size(), 0);

  int64_t bitmap_id = instances_result.instance(0).id();

  // Query primitive fields for the bitmap instance
  proto::QueryParameters::GetPrimitiveFieldsParameters fields_request;
  fields_request.add_instance_ids(bitmap_id);

  proto::GetPrimitiveFieldsResult fields_result;
  handler.PopulatePrimitiveFields(fields_request, &fields_result);

  ASSERT_EQ(fields_result.instances_size(), 1);
  const auto& instance_fields = fields_result.instances(0);
  EXPECT_EQ(instance_fields.instance_id(), bitmap_id);
  EXPECT_GT(instance_fields.field_size(), 0);

  bool found_height = false;
  bool found_width = false;
  for (const auto& field : instance_fields.field()) {
    if (field.name().find(".mHeight") != std::string::npos) {
      found_height = true;
      EXPECT_EQ(field.value(), "800");
    } else if (field.name().find(".mWidth") != std::string::npos) {
      found_width = true;
      EXPECT_EQ(field.value(), "800");
    }
  }
  EXPECT_TRUE(found_height);
  EXPECT_TRUE(found_width);
}

// Validates reference tree chain querying (forward and reverse references).
TEST(HeapDumpRequestHandlerTest, TestPopulateReferences) {
  auto tp = LoadTrace(HPROF_PATH);
  HeapDumpRequestHandler handler{tp.get()};

  proto::HeapDumpResult events_result;
  handler.PopulateEvents(&events_result);

  // Fetch an instance ID for android.graphics.Bitmap
  proto::QueryParameters::HeapDumpInstancesParameters instances_request;
  instances_request.add_class_names("android.graphics.Bitmap");
  instances_request.set_heap_name("app");

  proto::HeapDumpInstancesResult instances_result;
  handler.PopulateInstances(instances_request, &instances_result);
  ASSERT_GT(instances_result.instance_size(), 0);

  int64_t bitmap_id = instances_result.instance(0).id();

  // Query references for the bitmap instance
  proto::QueryParameters::GetReferencesParameters refs_request;
  refs_request.add_instance_ids(bitmap_id);
  refs_request.set_fetch_forward(true);
  refs_request.set_fetch_reverse(true);

  proto::GetReferencesResult refs_result;
  handler.PopulateReferences(refs_request, &refs_result);

  EXPECT_GT(refs_result.reference_size(), 0);

  bool found_klass_ref = false;
  bool found_drawcanvas_ref = false;
  for (const auto& ref : refs_result.reference()) {
    if (ref.owner_id() == bitmap_id &&
        ref.field_name().find("shadow$_klass_") != std::string::npos) {
      found_klass_ref = true;
      EXPECT_GT(ref.owned_id(), 0);
    }
    if (ref.owned_id() == bitmap_id &&
        ref.field_name().find("testBitmap1") != std::string::npos) {
      found_drawcanvas_ref = true;
      EXPECT_GT(ref.owner_id(), 0);
    }
  }
  EXPECT_TRUE(found_klass_ref);
  EXPECT_TRUE(found_drawcanvas_ref);
}

TEST(HeapDumpRequestHandlerTest, TestEscapeSqlString) {
  // 1. Happy case: clean strings pass through with identical contents and size
  std::string clean = "android.graphics.Bitmap";
  EXPECT_EQ(EscapeSqlString(clean), "android.graphics.Bitmap");
  EXPECT_EQ(EscapeSqlString("app"), "app");

  // 2. Single quote escaping: ' -> ''
  EXPECT_EQ(EscapeSqlString("foo'bar"), "foo''bar");
  EXPECT_EQ(EscapeSqlString("'start"), "''start");
  EXPECT_EQ(EscapeSqlString("end'"), "end''");

  // 3. Null byte stripping: embedded '\0' bytes are removed
  std::string with_null("foo\0bar", 7);
  EXPECT_EQ(EscapeSqlString(with_null), "foobar");
  EXPECT_EQ(EscapeSqlString(with_null).size(), 6);
}

}  // namespace
}  // namespace perfetto
}  // namespace profiler
