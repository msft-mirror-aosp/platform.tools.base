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

#include "daemon/transport_service.h"
#include <grpc++/grpc++.h>
#include <gtest/gtest.h>
#include <sys/stat.h>
#include <chrono>
#include <fstream>
#include <memory>
#include <string>
#include "daemon/daemon.h"
#include "daemon/event_buffer.h"
#include "proto/common.grpc.pb.h"
#include "utils/daemon_config.h"
#include "utils/fake_clock.h"
#include "utils/file_cache.h"
#include "utils/fs/disk_file_system.h"

namespace profiler {

using grpc::Status;
using grpc::StatusCode;

class TransportServiceTest : public ::testing::Test {
 public:
  TransportServiceTest()
      : temp_dir_(::testing::TempDir()),
        config_(proto::DaemonConfig::default_instance()),
        buffer_(&clock_, 10, 5) {}

  void SetUp() override {
    // Create the cache directory structure needed by the FileCache.
    std::string cache_path = temp_dir_ + "/cache";
    mkdir(cache_path.c_str(), S_IRWXU | S_IRWXG | S_IROTH | S_IXOTH);
    cache_dir_ = cache_path + "/complete/";
    mkdir(cache_dir_.c_str(), S_IRWXU | S_IRWXG | S_IROTH | S_IXOTH);

    // Initialize service components.
    file_cache_ = std::make_unique<FileCache>(
        std::make_unique<DiskFileSystem>(), temp_dir_);
    daemon_ = std::make_unique<Daemon>(&clock_, &config_, file_cache_.get(),
                                       &buffer_);
    service_ = std::make_unique<TransportServiceImpl>(daemon_.get());

    // Start the gRPC server.
    grpc::ServerBuilder builder;
    int port;
    builder.AddListeningPort("0.0.0.0:0", grpc::InsecureServerCredentials(),
                             &port);
    builder.RegisterService(service_.get());
    server_ = builder.BuildAndStart();

    // Increase client receive limit above the service's 4MB chunk size to avoid
    // gRPC cancellation.
    grpc::ChannelArguments args;
    args.SetMaxReceiveMessageSize(5 * 1024 * 1024);
    std::shared_ptr<grpc::ChannelInterface> channel =
        grpc::CreateCustomChannel("0.0.0.0:" + std::to_string(port),
                                  grpc::InsecureChannelCredentials(), args);
    stub_ = proto::TransportService::NewStub(channel);
  }

  void TearDown() override { server_->Shutdown(); }

 protected:
  // Generates a file with a predictable, repeating pattern.
  void CreateFileWithGeneratedContent(const std::string& path, size_t size) {
    std::ofstream out(path, std::ios::binary);
    ASSERT_TRUE(out.is_open()) << "Failed to open file for writing: " << path;
    for (size_t i = 0; i < size; ++i) {
      out.put('a' + (i % 26));
    }
  }

  // Verifies that a string matches the predictable pattern from the generator,
  // avoiding the need to store a large expected string in memory.
  void VerifyGeneratedContent(const std::string& data, size_t expected_size) {
    ASSERT_EQ(data.size(), expected_size);
    bool result = true;
    for (size_t i = 0; i < expected_size; ++i) {
      if (data[i] != 'a' + (i % 26)) {
        result = false;
        break;
      }
    }
    ASSERT_EQ(result, true);
  }

  void CreateSmallTestFile(const std::string& path,
                           const std::string& content) {
    std::ofstream out(path);
    out << content;
  }

  const std::string temp_dir_;
  std::string cache_dir_;

  FakeClock clock_;
  DaemonConfig config_;
  EventBuffer buffer_;

  std::unique_ptr<FileCache> file_cache_;
  std::unique_ptr<Daemon> daemon_;
  std::unique_ptr<TransportServiceImpl> service_;

  std::unique_ptr<grpc::Server> server_;
  std::unique_ptr<proto::TransportService::Stub> stub_;
};

TEST_F(TransportServiceTest, TestGetBytesInChunks) {
  // Case 1: Successful streaming of a normal file.
  {
    const std::string content = "This is the content of the test file.";
    const std::string file_id = "small_test.txt";
    CreateSmallTestFile(cache_dir_ + file_id, content);

    proto::BytesRequest request;
    request.set_id(file_id);
    grpc::ClientContext context;
    auto reader = stub_->GetBytesInChunks(&context, request);

    std::string received_content;
    proto::BytesInChunksResponse response;
    while (reader->Read(&response)) {
      received_content.append(response.chunk());
    }
    Status status = reader->Finish();
    EXPECT_TRUE(status.ok());
    EXPECT_EQ(content, received_content);
  }

  // Case 2: Requesting a file that does not exist.
  {
    proto::BytesRequest request;
    request.set_id("non_existent_id.txt");
    grpc::ClientContext context;
    auto reader = stub_->GetBytesInChunks(&context, request);

    proto::BytesInChunksResponse response;
    EXPECT_FALSE(reader->Read(&response));
    Status status = reader->Finish();
    EXPECT_EQ(StatusCode::NOT_FOUND, status.error_code());
  }

  // Case 3: Requesting a file that is empty.
  {
    const std::string file_id = "empty.txt";
    CreateSmallTestFile(cache_dir_ + file_id, "");

    proto::BytesRequest request;
    request.set_id(file_id);
    grpc::ClientContext context;
    auto reader = stub_->GetBytesInChunks(&context, request);

    // Service returns NOT_FOUND for empty files; test must expect this
    // behavior.
    proto::BytesInChunksResponse response;
    EXPECT_FALSE(reader->Read(&response));
    Status status = reader->Finish();
    EXPECT_EQ(StatusCode::NOT_FOUND, status.error_code());
  }

  // Case 4: Streaming a large file.
  {
    const std::string large_file_id = "large_generated_file.txt";
    const size_t largeFileSize = 10 * 1024 * 1024;  // 10MB
    CreateFileWithGeneratedContent(cache_dir_ + large_file_id, largeFileSize);

    proto::BytesRequest request;
    request.set_id(large_file_id);
    grpc::ClientContext context;
    // Set a 30s deadline to prevent test hangs.
    context.set_deadline(std::chrono::system_clock::now() +
                         std::chrono::seconds(30));
    auto reader = stub_->GetBytesInChunks(&context, request);

    std::string received_data;
    received_data.reserve(largeFileSize);
    proto::BytesInChunksResponse response;
    while (reader->Read(&response)) {
      received_data.append(response.chunk());
    }

    Status status = reader->Finish();
    EXPECT_TRUE(status.ok())
        << "gRPC call failed with code: " << status.error_code()
        << " and message: " << status.error_message();
    VerifyGeneratedContent(received_data, largeFileSize);
  }
}

}  // namespace profiler
