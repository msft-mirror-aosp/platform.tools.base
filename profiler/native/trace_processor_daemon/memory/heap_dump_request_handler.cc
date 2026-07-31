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

#include <fstream>
#include <iostream>
#include <string>
#include <unordered_map>
#include <vector>

#include "absl/strings/str_join.h"

namespace profiler {
namespace perfetto {

namespace {

// Forward declarations for helper functions
std::string FormatSortAndLimitSql(
    const proto::QueryParameters::HeapDumpInstancesParameters& request);
std::string FormatHeapFilterSql(
    const proto::QueryParameters::HeapDumpInstancesParameters& request,
    const std::string& id_list);
std::string FormatClassJoinSql(
    const proto::QueryParameters::HeapDumpInstancesParameters& request);

proto::GetPrimitiveFieldsResult::InstancePrimitiveFields*
FindOrAddInstanceProto(
    std::unordered_map<
        int64_t, proto::GetPrimitiveFieldsResult::InstancePrimitiveFields*>&
        instance_map,
    proto::GetPrimitiveFieldsResult* result, int64_t instance_id);

}  // namespace

void HeapDumpRequestHandler::PopulateEvents(proto::HeapDumpResult* result) {
  auto dom_it = processor_->ExecuteQuery(
      "INCLUDE PERFETTO MODULE android.memory.heap_graph.dominator_tree;");
  if (!dom_it.Status().ok()) {
    std::cerr << "Failed to load dominator tree module: "
              << dom_it.Status().message() << std::endl;
    return;
  }

  auto agg_it = processor_->ExecuteQuery(
      "INCLUDE PERFETTO MODULE "
      "android.memory.heap_graph.heap_graph_class_aggregation;");
  if (!agg_it.Status().ok()) {
    std::cerr << "Failed to load class aggregation module: "
              << agg_it.Status().message() << std::endl;
    return;
  }

  std::string class_query = R"(
    WITH s AS (
      SELECT
        o.type_id,
        o.heap_type,
        COUNT(o.id) AS instance_count,
        SUM(IFNULL(o.self_size, 0)) AS shallow_size,
        SUM(IFNULL(o.native_size, 0)) AS native_size,
        SUM(IIF(m.marked = 1, IFNULL(dt.dominated_size_bytes, 0), 0)) AS retained_size,
        SUM(IIF(m.marked = 1, IFNULL(dt.dominated_native_size_bytes, 0), 0)) AS retained_native_size
      FROM heap_graph_object o
      LEFT JOIN heap_graph_dominator_tree dt ON o.id = dt.id
      LEFT JOIN _heap_object_marked_for_dominated_stats m ON o.id = m.id
      GROUP BY o.type_id, o.heap_type
    )
    SELECT
      c.id as class_id,
      c.deobfuscated_name,
      c.name,
      IFNULL(s.instance_count, 0) AS instance_count,
      IFNULL(s.shallow_size, 0) AS shallow_size,
      IFNULL(s.native_size, 0) AS native_size,
      c.superclass_id,
      IFNULL(s.retained_size, 0) AS retained_size,
      IFNULL(s.heap_type, 'default'),
      IFNULL(s.retained_native_size, 0) AS retained_native_size
    FROM heap_graph_class c
    LEFT JOIN s ON s.type_id = c.id
  )";

  auto it = processor_->ExecuteQuery(class_query);
  if (!it.Status().ok()) {
    std::string err =
        "Class aggregation query failed: " + it.Status().message();
    std::cerr << err << std::endl;
    return;
  }
  while (it.Next()) {
    auto* class_overview = result->add_class_overview();
    class_overview->set_class_id(it.Get(0).AsLong());
    class_overview->set_class_name(
        !it.Get(1).is_null()
            ? it.Get(1).AsString()
            : (!it.Get(2).is_null() ? it.Get(2).AsString() : ""));
    class_overview->set_instance_count(it.Get(3).AsLong());
    class_overview->set_shallow_size(it.Get(4).AsLong());
    class_overview->set_native_size(it.Get(5).AsLong());
    class_overview->set_super_class_id(
        it.Get(6).is_null() ? -1 : it.Get(6).AsLong());
    class_overview->set_retained_size(it.Get(7).is_null() ? 0
                                                          : it.Get(7).AsLong());
    class_overview->set_heap_name(it.Get(8).is_null() ? "default"
                                                      : it.Get(8).AsString());
    class_overview->set_retained_native_size(
        it.Get(9).is_null() ? 0 : it.Get(9).AsLong());
  }
}

void HeapDumpRequestHandler::PopulateInstances(
    const proto::QueryParameters::HeapDumpInstancesParameters& request,
    proto::HeapDumpInstancesResult* result) {
  std::string id_list = absl::StrJoin(request.instance_ids(), ",");
  std::string where_clause = FormatHeapFilterSql(request, id_list);
  std::string join_class = FormatClassJoinSql(request);
  std::string limit_clause = FormatSortAndLimitSql(request);

  // Query Instances
  std::string instance_query = R"(
    SELECT
      o.id,
      o.type_id,
      o.self_size,
      o.native_size,
      dt.dominated_size_bytes,
      dt.dominated_native_size_bytes,
      o.reachable,
      o.root_distance,
      dt.depth,
      o.heap_type,
      d.value_string,
      d.array_element_count,
      d.field_set_id,
      o.reference_set_id IS NOT NULL
    FROM heap_graph_object o
    )" + join_class + R"(
    LEFT JOIN heap_graph_dominator_tree dt ON dt.id = o.id
    LEFT JOIN __intrinsic_heap_graph_object_data d ON d.id = o.object_data_id
    )" + where_clause + R"(
    )" + limit_clause + R"(
  )";

  auto instance_it = processor_->ExecuteQuery(instance_query);
  if (!instance_it.Status().ok()) {
    std::cerr << "PopulateInstances query failed: "
              << instance_it.Status().message() << std::endl;
    return;
  }
  while (instance_it.Next()) {
    auto* instance = result->add_instance();
    instance->set_id(instance_it.Get(0).AsLong());
    instance->set_type_id(instance_it.Get(1).AsLong());
    instance->set_self_size(instance_it.Get(2).AsLong());
    instance->set_native_size(
        instance_it.Get(3).is_null() ? 0 : instance_it.Get(3).AsLong());

    int64_t dom_size =
        instance_it.Get(4).is_null() ? 0 : instance_it.Get(4).AsLong();
    int64_t dom_native =
        instance_it.Get(5).is_null() ? 0 : instance_it.Get(5).AsLong();
    instance->set_retained_size(dom_size);
    instance->set_retained_native_size(dom_native);

    int64_t reachable =
        instance_it.Get(6).is_null() ? 0 : instance_it.Get(6).AsLong();
    int64_t depth;
    if (reachable == 0) {
      depth = 2147483647;
    } else {
      if (!instance_it.Get(7).is_null()) {
        depth = instance_it.Get(7).AsLong();
      } else if (!instance_it.Get(8).is_null()) {
        depth = instance_it.Get(8).AsLong();
      } else {
        depth = 1000000;
      }
    }
    instance->set_depth(depth);
    instance->set_heap_name(instance_it.Get(9).is_null()
                                ? "default"
                                : instance_it.Get(9).AsString());

    if (!instance_it.Get(10).is_null() &&
        instance_it.Get(10).type ==
            ::perfetto::trace_processor::SqlValue::kString) {
      instance->set_string_value(instance_it.Get(10).AsString());
    }
    if (!instance_it.Get(11).is_null()) {
      instance->set_array_length(instance_it.Get(11).AsLong());
    }
    instance->set_has_primitive_fields(!instance_it.Get(12).is_null());
    instance->set_has_references(!instance_it.Get(13).is_null() &&
                                 instance_it.Get(13).AsLong() > 0);
  }
}

void HeapDumpRequestHandler::PopulatePrimitiveFields(
    const proto::QueryParameters::GetPrimitiveFieldsParameters& request,
    proto::GetPrimitiveFieldsResult* result) {
  if (request.instance_ids_size() == 0) return;

  std::string id_list = absl::StrJoin(request.instance_ids(), ",");

  std::string ref_query = R"(
    SELECT
      o.id,
      p.field_name,
      p.field_type,
      COALESCE(p.int_value, p.long_value, p.float_value, p.double_value, p.bool_value, p.byte_value, p.char_value, p.short_value)
    FROM heap_graph_object o
    JOIN __intrinsic_heap_graph_object_data d ON d.id = o.object_data_id
    JOIN heap_graph_primitive p ON d.field_set_id = p.field_set_id
    WHERE o.id IN ()" + id_list +
                          R"()
  )";

  auto ref_it = processor_->ExecuteQuery(ref_query);

  if (!ref_it.Status().ok()) {
    std::string err =
        "GetPrimitiveFields query failed: " + ref_it.Status().message();
    std::cerr << err << std::endl;
    return;
  }

  std::unordered_map<int64_t,
                     proto::GetPrimitiveFieldsResult::InstancePrimitiveFields*>
      instance_map;

  while (ref_it.Next()) {
    int64_t instance_id = ref_it.Get(0).AsLong();
    auto* instance_proto =
        FindOrAddInstanceProto(instance_map, result, instance_id);

    auto* field = instance_proto->add_field();
    field->set_name(ref_it.Get(1).AsString());
    field->set_type_name(ref_it.Get(2).is_null() ? ""
                                                 : ref_it.Get(2).AsString());

    if (ref_it.Get(3).type == ::perfetto::trace_processor::SqlValue::kLong) {
      field->set_value(std::to_string(ref_it.Get(3).AsLong()));
    } else if (ref_it.Get(3).type ==
               ::perfetto::trace_processor::SqlValue::kDouble) {
      field->set_value(std::to_string(ref_it.Get(3).AsDouble()));
    } else {
      field->set_value("0");
    }
  }

  // Also try fetching the raw array blob if these objects are arrays
  std::string array_query = R"(
    SELECT
      o.id,
      od.array_element_type,
      __intrinsic_heap_graph_array(od.array_data_id)
    FROM heap_graph_object o
    JOIN __intrinsic_heap_graph_object_data od ON od.id = o.object_data_id
    WHERE od.array_data_id IS NOT NULL AND o.id IN ()" +
                            id_list + R"()
  )";

  auto arr_it = processor_->ExecuteQuery(array_query);
  if (!arr_it.Status().ok()) {
    std::string err =
        "GetPrimitiveFields array query failed: " + arr_it.Status().message();
    std::cerr << err << std::endl;
  }
  while (arr_it.Next()) {
    int64_t instance_id = arr_it.Get(0).AsLong();
    auto* instance_proto =
        FindOrAddInstanceProto(instance_map, result, instance_id);

    if (!arr_it.Get(1).is_null()) {
      instance_proto->set_array_type(arr_it.Get(1).AsString());
    }
    if (!arr_it.Get(2).is_null() &&
        arr_it.Get(2).type == ::perfetto::trace_processor::SqlValue::kBytes) {
      instance_proto->set_array_blob(arr_it.Get(2).AsBytes(),
                                     arr_it.Get(2).bytes_count);
    }
  }
}

void HeapDumpRequestHandler::PopulateReferences(
    const proto::QueryParameters::GetReferencesParameters& request,
    proto::GetReferencesResult* result) {
  if (request.instance_ids_size() == 0) return;

  std::string id_list = absl::StrJoin(request.instance_ids(), ",");

  std::string ref_query = "";
  if (request.fetch_forward()) {
    ref_query += R"(
      SELECT
        r.owner_id,
        r.owned_id,
        r.field_name
      FROM heap_graph_reference r
      WHERE r.owner_id IN ()" +
                 id_list + R"()
    )";
  }

  if (request.fetch_reverse()) {
    if (!ref_query.empty()) {
      ref_query += " UNION ALL ";
    }
    ref_query += R"(
      SELECT
        r.owner_id,
        r.owned_id,
        r.field_name
      FROM heap_graph_reference r
      WHERE r.owned_id IN ()" +
                 id_list + R"()
    )";
  }

  if (ref_query.empty()) return;

  auto ref_it = processor_->ExecuteQuery(ref_query);
  if (!ref_it.Status().ok()) {
    std::string err =
        "GetReferences query failed: " + ref_it.Status().message();
    std::cerr << err << std::endl;
    return;
  }
  while (ref_it.Next()) {
    auto* ref = result->add_reference();
    ref->set_owner_id(ref_it.Get(0).is_null() ? 0 : ref_it.Get(0).AsLong());
    ref->set_owned_id(ref_it.Get(1).is_null() ? 0 : ref_it.Get(1).AsLong());
    ref->set_field_name(ref_it.Get(2).is_null() ? ""
                                                : ref_it.Get(2).AsString());
  }
}

std::string EscapeSqlString(const std::string& input) {
  if (input.find('\'') == std::string::npos &&
      input.find('\0') == std::string::npos) {
    return input;
  }
  std::string escaped;
  escaped.reserve(input.size());
  for (char c : input) {
    if (c == '\'') {
      escaped.append("''");
    } else if (c != '\0') {
      escaped.push_back(c);
    }
  }
  return escaped;
}

namespace {

std::string FormatSortAndLimitSql(
    const proto::QueryParameters::HeapDumpInstancesParameters& request) {
  std::string sort_field = "IFNULL(dt.dominated_size_bytes, 0)";
  switch (request.sort_attribute()) {
    case proto::QueryParameters::SORT_SHALLOW_SIZE:
      sort_field = "o.self_size";
      break;
    case proto::QueryParameters::SORT_NATIVE_SIZE:
      sort_field = "o.native_size";
      break;
    case proto::QueryParameters::SORT_DEPTH:
      sort_field = "IFNULL(dt.depth, 2147483647)";
      break;
    case proto::QueryParameters::SORT_LABEL:
      sort_field = "d.value_string";
      break;
    case proto::QueryParameters::SORT_RETAINED_SIZE:
      sort_field = "IFNULL(dt.dominated_size_bytes, 0)";
      break;
    case proto::QueryParameters::SORT_RETAINED_NATIVE_SIZE:
      sort_field = "IFNULL(dt.dominated_native_size_bytes, 0)";
      break;
    default:
      sort_field = "IFNULL(dt.dominated_size_bytes, 0)";
      break;
  }

  std::string order_dir = request.sort_descending() ? "DESC" : "ASC";
  std::string sql = "ORDER BY " + sort_field + " " + order_dir;
  if (request.limit() > 0 || request.offset() > 0) {
    int64_t limit = request.limit() > 0 ? request.limit() : -1;
    sql += " LIMIT " + std::to_string(limit) + " OFFSET " +
           std::to_string(request.offset());
  }
  return sql;
}

std::string FormatHeapFilterSql(
    const proto::QueryParameters::HeapDumpInstancesParameters& request,
    const std::string& id_list) {
  if (request.instance_ids_size() > 0) {
    return "WHERE o.id IN (" + id_list + ")";
  }

  if (request.heap_name() != "") {
    if (request.heap_name() == "default") {
      return "WHERE o.heap_type IS NULL OR o.heap_type IN ('', 'default', "
             "'HEAP_TYPE_DEFAULT')";
    } else if (request.heap_name() == "app") {
      return "WHERE o.heap_type IN ('app', 'HEAP_TYPE_APP')";
    } else if (request.heap_name() == "image") {
      return "WHERE o.heap_type IN ('image', 'HEAP_TYPE_IMAGE', "
             "'HEAP_TYPE_BOOT_IMAGE')";
    } else if (request.heap_name() == "zygote") {
      return "WHERE o.heap_type IN ('zygote', 'HEAP_TYPE_ZYGOTE')";
    } else {
      return "WHERE o.heap_type = '" + EscapeSqlString(request.heap_name()) +
             "'";
    }
  }
  return "";
}

std::string FormatClassJoinSql(
    const proto::QueryParameters::HeapDumpInstancesParameters& request) {
  if (request.class_names_size() == 0) return "";

  std::string names_list = "";
  for (int i = 0; i < request.class_names_size(); i++) {
    names_list += "'" + EscapeSqlString(request.class_names(i)) + "'";
    if (i < request.class_names_size() - 1) names_list += ",";
  }
  return "JOIN (SELECT id FROM heap_graph_class WHERE name IN (" + names_list +
         ") OR deobfuscated_name IN (" + names_list +
         ")) c ON o.type_id = c.id";
}

proto::GetPrimitiveFieldsResult::InstancePrimitiveFields*
FindOrAddInstanceProto(
    std::unordered_map<
        int64_t, proto::GetPrimitiveFieldsResult::InstancePrimitiveFields*>&
        instance_map,
    proto::GetPrimitiveFieldsResult* result, int64_t instance_id) {
  auto it = instance_map.find(instance_id);
  if (it == instance_map.end()) {
    auto* instance_proto = result->add_instances();
    instance_proto->set_instance_id(instance_id);
    instance_map[instance_id] = instance_proto;
    return instance_proto;
  }
  return it->second;
}

}  // namespace

}  // namespace perfetto
}  // namespace profiler
