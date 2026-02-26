#!/bin/bash
# This script updates Emulator proto files in the current directory.
#
# Find an Emulator build here:
# https://android-build.googleplex.com/builds/branches/git_emu-main-dev/grid?
set -e

if [ $# == 1 ]
then
  build=$1
else
  echo Usage: $0 build
  echo Find an Emulator build here: https://android-build.googleplex.com/builds/branches/git_emu-main-dev/grid?
  exit 1
fi

dir="$(dirname "$0")"
linux_zip="sdk-repo-linux-emulator-$build.zip"

pushd "$dir"

repo start emulator_$build
echo Fetching Emulator build $build
/google/data/ro/projects/android/fetch_artifact --bid $build --target emulator-linux_x64_gfxstream "$linux_zip"
rm -f *.proto
unzip -j "$linux_zip" emulator/lib/*.proto
rm -f "$linux_zip" rtc_service.proto
git add .

printf "Update emulator proto files from emu-main-dev build $build\n\nBug: N/A\nTest: existing\n" > commitmsg.tmp

set +e

git commit -s -t commitmsg.tmp

rm -f "commitmsg.tmp"

popd
