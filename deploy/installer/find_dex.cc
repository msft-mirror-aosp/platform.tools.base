/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "tools/base/deploy/installer/find_dex.h"

#include <string>
#include <vector>

#include "tools/base/deploy/installer/apk_archive.h"
#include "tools/base/deploy/installer/command_cmd.h"
#include "tools/base/deploy/installer/dex_view.h"

namespace deploy {

void FindDexCommand::ParseParameters(const proto::InstallerRequest& request) {
  if (!request.has_find_dex_request()) {
    return;
  }

  request_ = request.find_dex_request();
  ready_to_run_ = true;
}

void FindDexCommand::Run(proto::InstallerResponse* response) {
  Phase p1("Command Dex");

  auto* find_dex_response = response->mutable_find_dex_response();
  std::string package_name = request_.package_name();
  uint64_t size_limit = request_.dex_file_size_limit();

  std::vector<std::string> apks_path;
  std::string error_message;
  CmdCommand cmd(workspace_);
  if (!cmd.GetApks(package_name, &apks_path, &error_message)) {
    ErrEvent("Could not find apks for this package: " + package_name);
    ErrEvent("Error: " + error_message);
    find_dex_response->set_status(proto::FindDexResponse::ERROR);
    return;
  }

  for (std::string& apk_path : apks_path) {
    Phase p2("processing APK: " + apk_path);

    ApkArchive archive(apk_path);
    // In the current implementation we don't care about compressed dex files,
    // since decompessing them will cause higher response latency.
    for (const auto& entry : GetUncompressedDexEntries(archive)) {
      deploy::DexView view(entry.payload);
      if (view.ContainsClassDeclaration(request_.class_signature())) {
        // Sending large DEX files over the wire can be very slow
        if (entry.PayloadSize() > size_limit) {
          find_dex_response->set_status(proto::FindDexResponse::FILE_TOO_LARGE);
          return;
        }
        std::string data(reinterpret_cast<const char*>(entry.payload),
                         entry.PayloadSize());
        find_dex_response->set_dex_file(std::move(data));
        find_dex_response->set_status(proto::FindDexResponse::OK);
        return;
      }
    }
  }
  find_dex_response->set_status(proto::FindDexResponse::NOT_FOUND);
}

}  // namespace deploy
