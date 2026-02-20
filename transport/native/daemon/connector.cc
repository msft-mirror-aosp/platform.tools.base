/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include <stdlib.h>
#include <unistd.h>
#include <cassert>
#include "utils/clock.h"
#include "utils/fd_utils.h"
#include "utils/socket_utils.h"

namespace {

// In the case where we are sending a connect request to agent, try to
// connect for five minutes before giving up. The time when the agent starts
// creating and listening from the socket |kAgentSocketName| can vary quite a
// bit. For example, an app can be stuck on waiting for debugger to attach.
const int kRetryMaxCount = 600;
// Interval between retry to connect to agent, in microseconds.
const int kTimeoutUs = profiler::Clock::ms_to_us(500);

}  // namespace

namespace profiler {

bool ConnectAndSendDataToPerfa(const std::string& connect_arg) {
  // Parses the app's process id from |connect_arg| to construct the target
  // agent socket we want to connect to.
  int delimiter_index = connect_arg.find(':');
  assert(delimiter_index != -1);
  std::string app_socket(profiler::kAgentSocketName);
  app_socket.append(connect_arg.substr(0, delimiter_index));

  std::string control = connect_arg.substr(delimiter_index + 1, 1);
  int daemon_socket_fd = -1;
  int retry_count = 0;
  if (control.compare(profiler::kHeartBeatRequest) == 0) {
    // Send heartbeat. No additional parsing needed.
  } else if (control.compare(profiler::kDaemonConnectRequest) == 0) {
    // Connect request. Parse the file descriptor as well.
    // connect_arg[delimiter_index] is ':'
    // connect_arg[delimiter_index + 1] is 'C'
    // connect_arg[delimiter_index + 2] is ':'
    assert(connect_arg.length() > +3);
    daemon_socket_fd = atoi(connect_arg.c_str() + delimiter_index + 3);

    // Make a number of attempts to connect to the agent. The agent may just be
    // starting up and not ready for receiving the connection yet.
    retry_count = kRetryMaxCount;

    // The connection argument format is expected to be
    // "pid:C:fd:attach_timeout_ms" where 'attach_timeout_ms' is an optional
    // parameter indicating how long the daemon should attempt to connect to the
    // agent socket. If provided and greater than 0, we dynamically calculate
    // the maximum number of retries based on the interval (kTimeoutUs). This
    // allows specific commands to fail fast (e.g. for backgrounded apps)
    // instead of spamming "Connection refused" logs for the default 5 minutes.
    int fd_delimiter_index = connect_arg.find(':', delimiter_index + 3);
    if (fd_delimiter_index != -1 &&
        connect_arg.length() > fd_delimiter_index + 1) {
      int attach_timeout_ms =
          atoi(connect_arg.c_str() + fd_delimiter_index + 1);
      if (attach_timeout_ms > 0) {
        // Convert to microseconds and divide by interval (kTimeoutUs)
        retry_count = (attach_timeout_ms * 1000) / kTimeoutUs;
        if (retry_count == 0) {
          retry_count = 1;  // Ensure at least 1 retry if timeout > 0
        }
      }
    }
  }

  int sent_count = profiler::ConnectAndSendDataToSocket(
      app_socket.c_str(), daemon_socket_fd, control.c_str(), retry_count,
      kTimeoutUs);

  if (daemon_socket_fd > 0) CloseFdAndLogAtError(daemon_socket_fd);

  // Sent |control| data is of length 1.
  return sent_count == 1;
}

}  // namespace profiler