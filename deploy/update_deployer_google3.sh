#!/bin/bash

if gcertstatus ; then
  stubby call blade:argon-api PipelineService.LaunchArtifactsPipeline --proto2 "
    artifact_spec <
      android_spec <
         build_id: \"$1\"
         build_target: \"studio-linux\"
         build_branch: \"git_studio-main\"
         artifact: \"artifacts/deployer.jar\"
     >
    >
    mpm <
      package_name: \"wireless/android/test_tools/argon/releases/studio_deployer\"
      version: \"live\"
      package_binary: \"studio_deployer_release_deploy.jar\"
    >
    reviewers: [\"$2\", \"$USER\"]"
  exit
fi

echo "Run prodaccess before updating deployer"
