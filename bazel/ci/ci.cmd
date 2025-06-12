set WORKSPACE=%~dp0\..\..\..\..
set PATH=%PATH%;%cd%\prebuilts\studio\jdk\jbr-next\win\bin
set BAZELISK=%WORKSPACE%\prebuilts\tools\windows-x86_64\bazel\bazelisk.exe
%BAZELISK% --max_idle_secs=60 run --config=ci --config=remote-exec //tools/base/bazel/ci -- %1