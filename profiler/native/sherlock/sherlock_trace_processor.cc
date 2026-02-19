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

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#else
#include <sys/mman.h>
#endif

#include <chrono>
#include <csignal>
#include <ctime>
#include <iostream>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#include "absl/flags/flag.h"
#include "absl/flags/parse.h"
#include "absl/log/initialize.h"
#include "absl/log/log.h"
#include "grpcpp/grpcpp.h"
#include "perfetto/trace_processor/trace_processor.h"
#include "processor.h"
#include "proto/service.grpc.pb.h"

ABSL_FLAG(int32_t, timeout, 10, "Timeout in seconds");

using grpc::ServerBuilder;

namespace {

using grpc::Server;
using grpc::ServerContext;
using grpc::Status;

using service::Gapid;
using service::GetAvailableStringTablesRequest;
using service::GetAvailableStringTablesResponse;
using service::GetRequest;
using service::GetResponse;
using service::GetServerInfoRequest;
using service::GetServerInfoResponse;
using service::ID;
using service::LoadCaptureRequest;
using service::LoadCaptureResponse;
using service::path_Capture;
using service::PerfettoQueryRequest;
using service::PerfettoQueryResponse;
using service::PingRequest;
using service::PingResponse;

class GapidServiceImpl final : public Gapid::Service {
 public:
  // Serializes all queries to perfetto Trace Processor. AGI does the same!
  std::mutex mu_;

  // This should be in a database somewhere. Database here would be an
  // in-memory map trace ID into the trace processor that has the
  // trace file loaded.
  std::unique_ptr<perfetto::trace_processor::TraceProcessor> tp;

  GapidServiceImpl(int timeout_seconds)
      : last_ping_time_(std::chrono::steady_clock::now()),
        running_(true),
        timeout_seconds_(timeout_seconds) {
    // Start a thread to monitor the ping timeout.
    monitor_thread_ = std::thread([this]() { MonitorPingTimeout(); });
  }

  ~GapidServiceImpl() {
    running_ = false;
    if (monitor_thread_.joinable()) {
      monitor_thread_.join();
    }
  }

  /**
   * This is a keep-alive call. If there are no pings received for N seconds,
   * it means the frontend is dead/exited, so we should exit this process.
   */
  Status Ping(ServerContext* context, const PingRequest* request,
              PingResponse* response) override {
    // Update the last ping time whenever ping is received,
    last_ping_time_ = std::chrono::steady_clock::now();
    return Status::OK;
  }

  Status GetServerInfo(ServerContext* context,
                       const GetServerInfoRequest* request,
                       GetServerInfoResponse* response) override {
    LOG(INFO) << "RPC: GetServerInfo";
    auto* server_info = response->mutable_info();
    server_info->set_name("server_name");
    server_info->set_version_major(1);
    server_info->set_version_minor(2);
    server_info->set_version_point(3);
    server_info->mutable_server_local_device()->mutable_id()->set_data(
        "123456");
    return Status::OK;
  }

  Status GetAvailableStringTables(
      ServerContext* context, const GetAvailableStringTablesRequest* request,
      GetAvailableStringTablesResponse* response) override {
    LOG(INFO) << "RPC: GetAvailableStringTables";
    // This is for internationalization. We don't support this yet.
    // So we return zero tables.
    response->mutable_tables();  // initialize as empty. (is this needed?)
    return Status::OK;
  }

  Status LoadCapture(ServerContext* context, const LoadCaptureRequest* request,
                     LoadCaptureResponse* response) override {
    LOG(INFO) << "RPC: LoadCapture";

    // This should be a check into the database. I.e., if the trace already
    // exists in the database, then we should return an error.
    if (tp != nullptr) {
      LOG(ERROR) << "LoadCapture: tp is not null";
      return Status(grpc::StatusCode::INTERNAL,
                    "A perfetto trace file is already loaded");
    }

    LOG(INFO) << "LoadCapture: creating new trace processor instance";
    tp = new_processor();

    // Memory map the file.
    LOG(INFO) << "LoadCapture: start mmap trace file";
#ifdef _WIN32
    HANDLE file =
        CreateFileA(request->path().c_str(), GENERIC_READ, FILE_SHARE_READ,
                    NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) {
      tp.reset();
      LOG(ERROR) << "LoadCapture: failed to open file";
      return Status(grpc::StatusCode::INVALID_ARGUMENT, "failed to open file");
    }

    HANDLE mapping = CreateFileMappingA(file, NULL, PAGE_READONLY, 0, 0, NULL);
    if (mapping == NULL) {
      CloseHandle(file);
      tp.reset();
      LOG(ERROR) << "LoadCapture: failed to create file mapping";
      return Status(grpc::StatusCode::INVALID_ARGUMENT,
                    "failed to create file mapping");
    }

    size_t size = GetFileSize(file, NULL);
    const char* data =
        (const char*)MapViewOfFile(mapping, FILE_MAP_READ, 0, 0, size);
    if (data == NULL) {
      CloseHandle(mapping);
      CloseHandle(file);
      tp.reset();
      LOG(ERROR) << "LoadCapture: failed to map view of file";
      return Status(grpc::StatusCode::INVALID_ARGUMENT,
                    "failed to map view of file");
    }
#else
    FILE* file = fopen(request->path().c_str(), "r");
    fseek(file, 0L, SEEK_END);
    size_t size = ftell(file);
    fseek(file, 0L, SEEK_SET);

    const char* data =
        (const char*)mmap(NULL, size, PROT_READ, MAP_SHARED, fileno(file), 0);
    if (data == MAP_FAILED) {
      tp.reset();
      LOG(ERROR) << "LoadCapture: failed to mmap file";
      return Status(grpc::StatusCode::INVALID_ARGUMENT, "failed to mmap file");
    }
#endif
    LOG(INFO) << "LoadCapture: end of mmap/MapViewOfFile";

    if (!parse_data(tp.get(), data, size)) {
      tp.reset();
#ifdef _WIN32
      UnmapViewOfFile(data);
      CloseHandle(mapping);
      CloseHandle(file);
#endif
      LOG(ERROR) << "LoadCapture: trace processor failed to parse trace file";
      return Status(grpc::StatusCode::INVALID_ARGUMENT,
                    "failed to parse trace file");
    }

    // TODO: Save TP to some database, indexed by ID.
    // See ResolvePerfettoFromPath and ResolvePerfettoFromID in AGI code.
    response->mutable_capture()->mutable_id()->set_data("this is a trace id");
    LOG(INFO) << "LoadCapture RPC returns OK";
    return Status::OK;
  }

  Status Get(ServerContext* context, const GetRequest* request,
             GetResponse* response) override {
    LOG(INFO) << "RPC: Get";
    auto* capture = response->mutable_value()->mutable_capture();
    capture->set_type(service::Perfetto);
    capture->set_name("capture name");
    return Status::OK;
  }

  Status PerfettoQuery(ServerContext* context,
                       const PerfettoQueryRequest* request,
                       PerfettoQueryResponse* response) override {
    std::lock_guard<std::mutex> guard(mu_);

    LOG(INFO) << "RPC: PerfettoQuery: " << request->query().data();
    execute_query(tp.get(), request->query().data(),
                  response->mutable_result());

    return Status::OK;
  }

 private:
  std::chrono::time_point<std::chrono::steady_clock> last_ping_time_;
  std::atomic<bool> running_;
  std::thread monitor_thread_;
  int timeout_seconds_;

  /**
   * Monitors the incoming ping requests from the client letting this process
   * know the frontend is still alive. If the duration from the last ping is
   * more than the [timeout_seconds_], this process is termianted.
   */
  void MonitorPingTimeout() {
    while (running_) {
      std::this_thread::sleep_for(std::chrono::seconds(1));
      auto now = std::chrono::steady_clock::now();
      auto duration = std::chrono::duration_cast<std::chrono::seconds>(
          now - last_ping_time_);
      if (duration.count() > timeout_seconds_) {
        LOG(ERROR) << "No ping received in " << timeout_seconds_
                   << " seconds. Exiting...";
        raise(SIGTERM);  // Send termination signal
      }
    }
  }
};

}  // end namespace

void RunServer(int timeout_seconds) {
  ServerBuilder builder;

  // Let gRPC dynamically pick an available port.
  int selected_port = 0;
  builder.AddListeningPort("0.0.0.0:0", grpc::InsecureServerCredentials(),
                           &selected_port);

  GapidServiceImpl gapid_service(timeout_seconds);
  builder.RegisterService(&gapid_service);

  std::unique_ptr<Server> server(builder.BuildAndStart());

  // The frontend needs to parse this, so we don't use LOG to avoid any
  // prefixes.
  std::cout << "Bound on port '" << selected_port << "'" << std::endl;
  server->Wait();
}

int main(int argc, char** argv) {
  absl::InitializeLog();

  absl::ParseCommandLine(argc, argv);

  int timeout = absl::GetFlag(FLAGS_timeout);

  LOG(INFO) << "Starting server with server timeout value of " << timeout
            << " seconds";
  RunServer(timeout);
  return 0;
}
