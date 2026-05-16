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
#include <errno.h>
#include <signal.h>
#include <sys/mman.h>
#endif

#include <chrono>
#include <csignal>
#include <ctime>
#include <iostream>
#include <memory>
#include <mutex>
#include <random>
#include <sstream>
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

ABSL_FLAG(int32_t, timeout, 0, "Timeout in seconds");
ABSL_FLAG(int32_t, parent_pid, 0, "Pid of the parent process to monitor");
ABSL_FLAG(bool, use_ipv6, false, "Use IPv6 for the gRPC server");
ABSL_FLAG(bool, use_token, false, "Require clients to use auth token");

#define GRPC_RETURN_IF_ERROR(expr)   \
  do {                               \
    ::grpc::Status _status = (expr); \
    if (!_status.ok()) {             \
      return _status;                \
    }                                \
  } while (0)

namespace {

using grpc::Server;
using grpc::ServerBuilder;
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

/**
 * Checks if a process with the given PID is currently running.
 *
 * @param pid The Process ID to check.
 * @return true if the process is alive, false otherwise.
 */
bool IsProcessAlive(int32_t pid) {
#if defined(_WIN32)
  // Windows Implementation
  HANDLE process = OpenProcess(SYNCHRONIZE, FALSE, pid);

  if (process == NULL) {
    // If OpenProcess fails because of permissions, the process exists but is
    // protected.
    if (GetLastError() == ERROR_ACCESS_DENIED) {
      return true;
    }
    return false;
  }

  bool is_active = WaitForSingleObject(process, 0) == WAIT_TIMEOUT;
  CloseHandle(process);
  return is_active;
#elif defined(__APPLE__) || defined(__linux__)
  // POSIX Implementation (macOS & Linux)

  // Sending signal '0' doesn't actually send a signal, but it
  // performs the same error checking as a real signal.
  if (kill(pid, 0) == 0) {
    // Process exists and we have permission to signal it
    return true;
  }

  // If kill failed, check the error code
  if (errno == EPERM) {
    // Process exists, but we are not allowed to send signals to it
    // (e.g., it belongs to root or another user).
    return true;
  }

  // Process does not exist (ESRCH) or other error
  return false;
#else
#error Host platform is not supported
#endif
}

// Generates a random 128-bit hex string
std::string GenerateToken() {
  std::random_device rd;
  std::mt19937_64 gen(rd());
  std::uniform_int_distribution<uint64_t> dis;
  std::stringstream ss;
  ss << std::hex << dis(gen) << dis(gen);
  return ss.str();
}

// Prevents timing attacks when checking the token
bool ConstantTimeCompare(const std::string& a, const std::string& b) {
  if (a.length() != b.length()) return false;
  int result = 0;
  for (size_t i = 0; i < a.length(); ++i) {
    result |= a[i] ^ b[i];
  }
  return result == 0;
}

class GapidServiceImpl final : public Gapid::Service {
 public:
  // Serializes all queries to perfetto Trace Processor. AGI does the same!
  std::mutex mu_;

  // This should be in a database somewhere. Database here would be an
  // in-memory map trace ID into the trace processor that has the
  // trace file loaded.
  std::unique_ptr<perfetto::trace_processor::TraceProcessor> tp;

  GapidServiceImpl(int timeout_seconds, int32_t parent_pid,
                   const std::string& token)
      : last_ping_time_(std::chrono::steady_clock::now()),
        running_(true),
        timeout_seconds_(timeout_seconds),
        parent_pid_(parent_pid),
        token_(token) {
    // Start a thread to monitor the ping timeout.
    if (timeout_seconds_ != 0) {
      ping_monitor_thread_ = std::thread([this]() { MonitorPingTimeout(); });
    }

    // Start a thread to monitor the parent process.
    if (parent_pid_ != 0) {
      parent_monitor_thread_ =
          std::thread([this]() { MonitorParentProcess(); });
    }
  }

  ~GapidServiceImpl() {
    running_ = false;
    if (ping_monitor_thread_.joinable()) {
      ping_monitor_thread_.join();
    }
    if (parent_monitor_thread_.joinable()) {
      parent_monitor_thread_.join();
    }
  }
  /**
   * This is a keep-alive call. If there are no pings received for N seconds,
   * it means the frontend is dead/exited, so we should exit this process.
   */
  Status Ping(ServerContext* context, const PingRequest* request,
              PingResponse* response) override {
    GRPC_RETURN_IF_ERROR(ValidateToken(context));
    // Update the last ping time whenever ping is received,
    last_ping_time_ = std::chrono::steady_clock::now();
    return Status::OK;
  }

  Status GetServerInfo(ServerContext* context,
                       const GetServerInfoRequest* request,
                       GetServerInfoResponse* response) override {
    GRPC_RETURN_IF_ERROR(ValidateToken(context));
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
    GRPC_RETURN_IF_ERROR(ValidateToken(context));
    LOG(INFO) << "RPC: GetAvailableStringTables";

    // This is for internationalization. We don't support this yet.
    // So we return zero tables.
    response->mutable_tables();  // initialize as empty. (is this needed?)
    return Status::OK;
  }

  Status LoadCapture(ServerContext* context, const LoadCaptureRequest* request,
                     LoadCaptureResponse* response) override {
    GRPC_RETURN_IF_ERROR(ValidateToken(context));
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
    GRPC_RETURN_IF_ERROR(ValidateToken(context));
    LOG(INFO) << "RPC: Get";
    auto* capture = response->mutable_value()->mutable_capture();
    capture->set_type(service::Perfetto);
    capture->set_name("capture name");
    return Status::OK;
  }

  Status PerfettoQuery(ServerContext* context,
                       const PerfettoQueryRequest* request,
                       PerfettoQueryResponse* response) override {
    GRPC_RETURN_IF_ERROR(ValidateToken(context));
    LOG(INFO) << "RPC: PerfettoQuery: " << request->query().data();
    std::lock_guard<std::mutex> guard(mu_);
    execute_query(tp.get(), request->query().data(),
                  response->mutable_result());

    return Status::OK;
  }

 private:
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

  /**
   * Monitors the parent process. If the parent process is no longer alive,
   * then the current process is terminated.
   */
  void MonitorParentProcess() {
    while (running_) {
      if (!IsProcessAlive(parent_pid_)) {
        LOG(ERROR) << "Parent process is not alive. Exiting...";
        raise(SIGTERM);  // Send termination signal
      }
      std::this_thread::sleep_for(std::chrono::seconds(1));
    }
  }

  /**
   * Verifies that the incoming request satisfies the required token.
   * Returns Status::OK if the request should be passed through.
   */
  grpc::Status ValidateToken(grpc::ServerContext* context) {
    // Insecure connection, possibly for testing.
    if (!absl::GetFlag(FLAGS_use_token)) return grpc::Status::OK;

    auto auth_metadata =
        context->client_metadata().equal_range("authorization");
    if (auth_metadata.first != auth_metadata.second) {
      // Extract the value
      std::string value(auth_metadata.first->second.data(),
                        auth_metadata.first->second.length());
      std::string expected = "Bearer " + token_;

      if (ConstantTimeCompare(value, expected)) {
        return grpc::Status::OK;
      }
    }
    return grpc::Status(grpc::StatusCode::UNAUTHENTICATED,
                        "Invalid or missing token");
  }

 private:
  std::chrono::time_point<std::chrono::steady_clock> last_ping_time_;
  std::atomic<bool> running_;
  std::thread ping_monitor_thread_;
  std::thread parent_monitor_thread_;
  const int timeout_seconds_;
  const int32_t parent_pid_;
  std::string token_;
};

void RunServer(int timeout_seconds, int32_t parent_pid, bool use_ipv6) {
  std::string token = absl::GetFlag(FLAGS_use_token) ? GenerateToken() : "";
  ServerBuilder builder;

  // Requirements:
  //  1. Let gRPC dynamically pick an available port.
  //  2. Use loopback instead of any (0.0.0.0) so that the port is open to
  //     only the local machine's private internal network.
  //  3. Support both IPv4 and IPv6. Some of our target machines will be
  //  IPv6-only.
  //  4. Use the same port for IPv4 and IPv6.
  //
  // Note that if we use "localhost:0" as the listening address, it serves 1-3
  // above, but it cannot guarantee the same port being used on both IPv4 and
  // IPv6.
  //
  // For now, we are selecting either IPv4 or IPv6, bot never both.
  //
  // TODO: Support serving on the same port of both IPv4/IPv6 stacks
  // simultaneously.
  int selected_port = 0;
  if (use_ipv6) {
    LOG(INFO) << "Using IPv6";
    builder.AddListeningPort("[::1]:0", grpc::InsecureServerCredentials(),
                             &selected_port);
  } else {
    LOG(INFO) << "Using IPv4";
    builder.AddListeningPort("127.0.0.1:0", grpc::InsecureServerCredentials(),
                             &selected_port);
  }

  GapidServiceImpl gapid_service(timeout_seconds, parent_pid, token);
  builder.RegisterService(&gapid_service);

  std::unique_ptr<Server> server(builder.BuildAndStart());

  // The frontend needs to parse this, so we don't use LOG to avoid any
  // prefixes.
  if (absl::GetFlag(FLAGS_use_token)) {
    std::cout << "Bound on port '" << selected_port << "', using token '"
              << token << "'" << std::endl;
  } else {
    std::cout << "Bound on port '" << selected_port << "'" << std::endl;
  }
  server->Wait();
}

}  // end namespace

int main(int argc, char** argv) {
  absl::InitializeLog();

  absl::ParseCommandLine(argc, argv);

  int timeout = absl::GetFlag(FLAGS_timeout);
  int32_t parent_pid = absl::GetFlag(FLAGS_parent_pid);
  bool use_ipv6 = absl::GetFlag(FLAGS_use_ipv6);

  LOG(INFO) << "Starting server with server timeout value of " << timeout
            << " seconds and parent_pid " << parent_pid;
  RunServer(timeout, parent_pid, use_ipv6);
  return 0;
}
