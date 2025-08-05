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
#include <stdlib.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <cstdint>
#include <fstream>
#include <sstream>
#include "daemon/daemon.h"
#include "daemon/event_writer.h"
#include "utils/android_studio_version.h"
#include "utils/file_reader.h"
#include "utils/log.h"
#include "utils/process_manager.h"
#include "utils/trace.h"

using grpc::ServerContext;
using grpc::ServerWriter;
using grpc::Status;
using grpc::StatusCode;
using std::string;

namespace profiler {

namespace {

int64_t ReadChunkInFile(std::ifstream& file, int64_t offset, int64_t chunk_size,
                        char* buffer) {
  if (buffer == nullptr) {
    Log::E(Log::Tag::TRANSPORT, "ReadChunkInFile called with a null buffer.");
    return -1;
  }

  if (chunk_size < 0) {
    Log::E(Log::Tag::TRANSPORT,
           "ReadChunkInFile cannot read a negative number of bytes: %lld",
           chunk_size);
    return -1;
  }

  if (chunk_size == 0) {
    return 0;
  }

  // Before seeking, we must clear any error flags (like eofbit or failbit)
  // from the previous read. Otherwise, a previously successful read that hit
  // EOF will leave the stream in a failed state, causing this seek to fail.
  file.clear();
  file.seekg(offset, file.beg);
  if (file.fail()) {
    Log::E(Log::Tag::TRANSPORT, "Error seeking to offset: %lld", offset);
    return -1;
  }

  file.read(buffer, chunk_size);
  // Read may fail due to I/O error; eofbit is not a failure.
  if (file.fail() && !file.eof()) {
    Log::E(Log::Tag::TRANSPORT, "Error reading chunk from offset %lld", offset);
    return -1;
  }

  // Return the actual number of bytes read
  return static_cast<int64_t>(file.gcount());
}

}  // namespace

/**
 * Helper class to wrap the EventWriter interface. This class is passed to the
 * EventBuffer and forwards any events to the attached ServerWriter.
 */
class ServerEventWriter final : public EventWriter {
 public:
  ServerEventWriter(ServerWriter<proto::Event>& writer) : writer_(writer) {}
  bool Write(const proto::Event& event) override {
    return writer_.Write(event);
  }

 private:
  ServerWriter<proto::Event>& writer_;
};

Status TransportServiceImpl::GetCurrentTime(ServerContext* context,
                                            const proto::TimeRequest* request,
                                            proto::TimeResponse* response) {
  Trace trace("PRO:GetTimes");

  response->set_timestamp_ns(daemon_->clock()->GetCurrentTime());
  // TODO: Move this to utils.
  timeval time;
  gettimeofday(&time, nullptr);
  // Not specifying LL may cause overflow depending on the underlying type of
  // time.tv_sec.
  int64_t t = time.tv_sec * 1000000LL + time.tv_usec;
  response->set_epoch_timestamp_us(t);
  return Status::OK;
}

Status TransportServiceImpl::GetVersion(ServerContext* context,
                                        const proto::VersionRequest* request,
                                        proto::VersionResponse* response) {
  response->set_version(profiler::kAndroidStudioVersion);
  return Status::OK;
}

Status TransportServiceImpl::GetFile(ServerContext* context,
                                     const proto::BytesRequest* request,
                                     proto::FileResponse* response) {
  // `GetFile` is not implemented by transport daemon. This API is
  // designed to be used for same-machine transportation only.
  return Status(grpc::StatusCode::UNIMPLEMENTED,
                "Not implemented by transport daemon");
}

Status TransportServiceImpl::ValidateAndGetCanonicalPath(
    const std::string& id, std::string* canonical_path) {
  auto* file_cache = daemon_->file_cache();
  std::string file_path = file_cache->GetFile(id)->path();

  if (file_path.empty()) {
    Log::E(Log::Tag::TRANSPORT,
           "Error: Invalid ID or could not determine file path for ID: %s",
           id.c_str());
    return Status(grpc::StatusCode::NOT_FOUND, "Invalid ID or file not found");
  }

  char* resolved_path = realpath(file_path.c_str(), nullptr);
  if (resolved_path == nullptr) {
    Log::E(Log::Tag::TRANSPORT, "File not found or error resolving path: %s",
           file_path.c_str());
    return Status(grpc::StatusCode::NOT_FOUND, "File not found");
  }

  // Use stat to check if it's a regular file.
  struct stat stat_buf;
  if (stat(resolved_path, &stat_buf) != 0) {
    Log::E(Log::Tag::TRANSPORT, "Could not get file stats for: %s",
           resolved_path);
    free(resolved_path);
    return Status(grpc::StatusCode::NOT_FOUND, "File not found");
  }

  if (!S_ISREG(stat_buf.st_mode)) {
    Log::E(Log::Tag::TRANSPORT, "Path is not a regular file: %s",
           resolved_path);
    free(resolved_path);
    return Status(grpc::StatusCode::UNAVAILABLE, "Path is not a regular file");
  }

  *canonical_path = resolved_path;
  free(resolved_path);

  return Status::OK;
}

Status TransportServiceImpl::StreamFile(
    const std::string& file_path, const std::string& id,
    grpc::ServerWriter<proto::BytesInChunksResponse>* writer) {
  const int64_t chunk_size = (int64_t)4 * 1024 * 1024;
  std::unique_ptr<char[]> buffer(new char[chunk_size]);
  int64_t offset = 0ll;
  int64_t chunk_count = 0ll;

  std::ifstream file_stream(file_path, std::ios::binary);
  if (!file_stream.is_open()) {
    Log::E(Log::Tag::TRANSPORT, "Cannot open file: %s", file_path.c_str());
    return Status(grpc::StatusCode::NOT_FOUND,
                  "File not found or access denied");
  }

  while (true) {
    int64_t num_read =
        ReadChunkInFile(file_stream, offset, chunk_size, buffer.get());

    if (num_read < 0ll) {
      Log::E(Log::Tag::TRANSPORT, "Error reading file chunk for: %s",
             file_path.c_str());
      return Status(grpc::StatusCode::INTERNAL, "A file read error occurred.");
    }

    if (num_read == 0ll) {
      break;
    }

    proto::BytesInChunksResponse response;
    response.set_chunk(buffer.get(), num_read);
    if (!writer->Write(response)) {
      Log::E(Log::Tag::TRANSPORT, "Error: Failed to write to stream for ID: %s",
             id.c_str());
      return Status::CANCELLED;
    }

    offset += num_read;
    chunk_count++;
  }

  if (offset == 0ll) {
    Log::E(Log::Tag::TRANSPORT, "Error: File is empty: %s", file_path.c_str());
    return Status(grpc::StatusCode::NOT_FOUND, "File is empty");
  }

  Log::I(Log::Tag::TRANSPORT, "Streaming file %s completed, in %lld chunks",
         file_path.c_str(), chunk_count);
  return Status::OK;
}

Status TransportServiceImpl::GetBytesInChunks(
    ServerContext* context, const proto::BytesRequest* request,
    grpc::ServerWriter<proto::BytesInChunksResponse>* writer) {
  const std::string& id = request->id();
  std::string file_path;
  Status status = ValidateAndGetCanonicalPath(id, &file_path);
  if (!status.ok()) {
    return status;
  }

  try {
    struct stat stat_buf;
    if (stat(file_path.c_str(), &stat_buf) != 0) {
      Log::E(Log::Tag::TRANSPORT, "Could not get file stats for %s",
             file_path.c_str());
      return Status(grpc::StatusCode::INTERNAL, "Could not get file stats.");
    }

    Log::I(Log::Tag::TRANSPORT, "Streaming file %s started, size %lld bytes",
           file_path.c_str(), static_cast<long long>(stat_buf.st_size));

    return StreamFile(file_path, id, writer);
  } catch (const std::exception& ex) {
    Log::E(Log::Tag::TRANSPORT, "Error streaming file %s: %s",
           file_path.c_str(), ex.what());
    return Status(grpc::StatusCode::INTERNAL,
                  "An unexpected error occurred while streaming the file.");
  }
}

Status TransportServiceImpl::GetAgentStatus(
    ServerContext* context, const proto::AgentStatusRequest* request,
    proto::AgentData* response) {
  response->set_status(
      daemon_->GetAgentStatus(request->pid(), request->package_name()));
  return Status::OK;
}

Status TransportServiceImpl::Execute(ServerContext* context,
                                     const proto::ExecuteRequest* request,
                                     proto::ExecuteResponse* response) {
  return daemon_->Execute(request->command());
}

Status TransportServiceImpl::GetEvents(ServerContext* context,
                                       const proto::GetEventsRequest* request,
                                       ServerWriter<proto::Event>* response) {
  ServerEventWriter writer(*response);
  daemon_->WriteEventsTo(&writer);
  // Only return when a connection between the client and server is terminated.
  return Status::OK;
}

Status TransportServiceImpl::GetEventGroups(
    ServerContext* context, const proto::GetEventGroupsRequest* request,
    proto::GetEventGroupsResponse* response) {
  for (auto& group : daemon_->GetEventGroups(request)) {
    proto::EventGroup* event_group = response->add_groups();
    event_group->CopyFrom(group);
  }
  return Status::OK;
}

}  // namespace profiler
