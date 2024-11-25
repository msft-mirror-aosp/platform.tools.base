/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include "tools/base/deploy/installer/restart_activity.h"
#include "tools/base/deploy/installer/binary_extract.h"

namespace deploy {

void RestartActivityCommand::ParseParameters(
    const proto::InstallerRequest& request) {
  if (!request.has_restart_activity_request()) {
    return;
  }

  request_ = request.restart_activity_request();
  package_name_ = request_.application_id();
  std::vector<int> pids(request_.process_ids().begin(),
                        request_.process_ids().end());
  process_ids_ = pids;
  ready_to_run_ = true;
}

void RestartActivityCommand::Run(proto::InstallerResponse* response) {
  proto::RestartActivityResponse* restart_response =
      response->mutable_restart_activity_response();

  if (!PrepareInteraction(request_.arch())) {
    ErrEvent("Unable to prepare interaction");
    return;
  }

  FilterProcessIds(&process_ids_);

  auto listen_response = ListenForAgents();
  if (listen_response == nullptr) {
    restart_response->set_status(
        proto::RestartActivityResponse::INSTALL_SERVER_COM_ERR);
    return;
  }

  if (listen_response->status() != proto::OpenAgentSocketResponse::OK) {
    restart_response->set_status(
        proto::RestartActivityResponse::READY_FOR_AGENTS_NOT_RECEIVED);
    return;
  }

  if (!Attach(process_ids_)) {
    restart_response->set_status(
        proto::RestartActivityResponse::AGENT_ATTACH_FAILED);
    return;
  }

  // Send restart activity request to agents
  proto::SendAgentMessageRequest req;
  req.set_agent_count(process_ids_.size());
  *req.mutable_agent_request()->mutable_restart_activity_request() = request_;
  auto resp = client_->SendAgentMessage(req);
  if (!resp) {
    restart_response->set_status(
        proto::RestartActivityResponse::INSTALL_SERVER_COM_ERR);
    return;
  }

  bool success = true;
  for (const auto& agent_response : resp->agent_responses()) {
    ConvertProtoEventsToEvents(agent_response.events());
    if (agent_response.status() != proto::AgentResponse::OK) {
      success = false;
    }
  }

  if (success) {
    restart_response->set_status(proto::RestartActivityResponse::OK);
  } else {
    restart_response->set_status(proto::RestartActivityResponse::AGENT_ERROR);
  }
}

}  // namespace deploy
