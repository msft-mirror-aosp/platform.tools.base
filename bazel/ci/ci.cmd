set WORKSPACE_DIR=%~dp0\..\..\..\..
set PATH=%PATH%;%cd%\prebuilts\studio\jdk\jbr-next\win\bin
set BAZELISK=%WORKSPACE_DIR%\prebuilts\tools\windows-x86_64\bazel\bazelisk.exe

if defined BUILD_NUMBER (
  echo common --credential_helper=*.pkg.dev=%%workspace%%/build/bazel/tools/ci_credhelper.cmd > ci.bazelrc
)

%BAZELISK% --max_idle_secs=60 run --config=ci --config=remote-exec //tools/base/bazel/ci -- %1