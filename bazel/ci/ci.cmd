set WORKSPACE_DIR=%~dp0\..\..\..\..
set PATH=%PATH%;%cd%\prebuilts\studio\jdk\jbr-next\win\bin
set BAZEL=%WORKSPACE_DIR%\prebuilts\bazel\windows-x86_64\bazel.exe

rem Set ANDROID_HOME here instead of bazelrc because androidx-vital sets
rem a different value in their builds. See:
rem https://googleplex-android.googlesource.com/platform/tools/base/+/0a8bc01ca5b48eb884c22d4a811af9c15e784578
set ANDROID_HOME=$WORKSPACE_ROOT\prebuilts\studio\sdk\windows

if defined BUILD_NUMBER (
  echo common --credential_helper=*.pkg.dev=%%workspace%%/build/bazel/tools/ci_credhelper.cmd > ci.bazelrc
  echo common --credential_helper=*.googleapis.com=%%workspace%%/build/bazel/tools/ci_credhelper.cmd >> ci.bazelrc
)

%BAZEL% --max_idle_secs=60 run --config=ci --config=remote-exec //tools/base/bazel/ci -- %1
