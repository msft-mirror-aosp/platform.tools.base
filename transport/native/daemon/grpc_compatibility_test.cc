/*
 * Copyright (C) 2019 The Android Open Source Project
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
#include <gtest/gtest.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <climits>
#include <unordered_set>

#include <grpc++/grpc++.h>
#include "daemon/agent_service.h"
#include "daemon/daemon.h"
#include "perfd/perfd.h"
#include "utils/fake_clock.h"
#include "utils/file_cache.h"
#include "utils/fs/memory_file_system.h"
#include "utils/socket_utils.h"

using profiler::proto::EmptyResponse;
using profiler::proto::HeartBeatRequest;
using std::string;
using std::unique_ptr;

namespace profiler {

const char* const kUnixPrefix = "unix:";
const char* const kUnixAbstractPrefix = "unix-abstract:";

class GrpcCompatibilityTest : public ::testing::Test {
 public:
  GrpcCompatibilityTest()
      : file_cache_(unique_ptr<FileSystem>(new MemoryFileSystem()), "/"),
        config_(proto::DaemonConfig::default_instance()),
        buffer_(&clock_, 10, 5),
        daemon_(&clock_, &config_, &file_cache_, &buffer_),
        service_(&daemon_) {}

  // Sets up the server and returns the bound port.
  int SetUpServer(const string& target) {
    grpc::ServerBuilder builder;
    int port;
    builder.AddListeningPort(target, grpc::InsecureServerCredentials(), &port);
    builder.RegisterService(&service_);
    Perfd::Initialize(&daemon_);
    server_ = builder.BuildAndStart();
    return port;
  }

  void SetUpClient(const string& target) {
    std::shared_ptr<grpc::ChannelInterface> channel =
        grpc::CreateChannel(target, grpc::InsecureChannelCredentials());
    stub_ = proto::AgentService::NewStub(channel);
  }

  void SetUpClientWithFd(int fd) {
    // Create channel args that match those used in transport/native/agent/agent.cc
    grpc::ChannelArguments channel_args;
    channel_args.SetInt(GRPC_ARG_MAX_RECONNECT_BACKOFF_MS, 1000);
    std::shared_ptr<grpc::ChannelInterface> channel =
        grpc::CreateCustomInsecureChannelFromFd("transport-fd-grpc", fd, channel_args);
    stub_ = proto::AgentService::NewStub(channel);
  }

  void VerifyConnectionIsOk() {
    HeartBeatRequest request;
    request.set_pid(100);
    grpc::ClientContext context;
    EmptyResponse response;
    grpc::Status status = stub_->HeartBeat(&context, request, &response);
    EXPECT_TRUE(status.ok());
  }

  void TearDown() override {
    daemon_.InterruptWriteEvents();
    server_->Shutdown();
  }

  FakeClock clock_;
  FileCache file_cache_;
  DaemonConfig config_;
  EventBuffer buffer_;
  Daemon daemon_;
  AgentServiceImpl service_;

  unique_ptr<::grpc::Server> server_;
  unique_ptr<proto::AgentService::Stub> stub_;
};

// This test should pass with gRPC 1.22.0 out-of-box.
TEST_F(GrpcCompatibilityTest, GrpcWorksForIpAddress) {
  string ip{"0.0.0.0:0"};
  int port = SetUpServer(ip);
  SetUpClient(ip + std::to_string(port));
  VerifyConnectionIsOk();
}

// This test should pass with gRPC 1.22.0 out-of-box.
TEST_F(GrpcCompatibilityTest, GrpcWorksForRegularDomainSocket) {
  string regular_domain_socket{"/tmp/regular_socket"};
  SetUpServer(kUnixPrefix + regular_domain_socket);
  SetUpClient(kUnixPrefix + regular_domain_socket);
  VerifyConnectionIsOk();
}

TEST_F(GrpcCompatibilityTest, GrpcWorksForAbstractDomainSocket) {
  string abstract_domain_socket{"AbstractSocket"};
  SetUpServer(kUnixAbstractPrefix + abstract_domain_socket);
  SetUpClient(kUnixAbstractPrefix + abstract_domain_socket);
  VerifyConnectionIsOk();
}

TEST_F(GrpcCompatibilityTest, GrpcWorksForConnectedFd) {
  string regular_domain_socket{"/tmp/regular_socket"};
  SetUpServer(kUnixPrefix + regular_domain_socket);

  // Open a socket connected to the server.
  int fd;  // The client socket that's connected to daemon.
  if ((fd = socket(AF_UNIX, SOCK_STREAM, 0)) == -1) {
    perror("socket error");
    ASSERT_TRUE(false);
  }
  struct sockaddr_un addr_un;
  socklen_t addr_len;
  SetUnixSocketAddr(regular_domain_socket.c_str(), &addr_un, &addr_len);
  if (connect(fd, (struct sockaddr*)&addr_un, addr_len) == -1) {
    perror("connect error");
    ASSERT_TRUE(false);
  }

  // Pass the connected fd to the client.
  SetUpClientWithFd(fd);
  VerifyConnectionIsOk();
}

TEST_F(GrpcCompatibilityTest, GrpcWorksForAbstractSocketAndConnectedFd) {
  string abstract_domain_socket{"AbstractSocket"};

  SetUpServer(kUnixAbstractPrefix + abstract_domain_socket);

  // Open a socket connected to the server.
  int fd;  // The client socket that's connected to daemon.
  if ((fd = socket(AF_UNIX, SOCK_STREAM, 0)) == -1) {
    perror("socket error");
    ASSERT_TRUE(false);
  }
  struct sockaddr_un addr_un;
  socklen_t addr_len;
  SetUnixSocketAddr("@AbstractSocket", &addr_un, &addr_len);
  if (connect(fd, (struct sockaddr*)&addr_un, addr_len) == -1) {
    perror("connect error");
    ASSERT_TRUE(false);
  }

  // Pass the connected fd to the client.
  SetUpClientWithFd(fd);
  VerifyConnectionIsOk();
}

}  // namespace profiler
