#!/bin/bash

# Guard against direct execution to prevent accidental file deletion
if [[ -z "${TEST_WORKSPACE}" ]]; then
  echo "Error: This script is intended to be run as a bazel test." >&2
  exit 1
fi

trap 'rm -f ./*.perfetto*' EXIT

function fail {
   echo $1
   exit 1
}

function findPerfetto {
  find . -name "*.perfetto*"
}

EXPECTED=$(cat <<- EOF
>main
><init>
>simple
>twoReturns
<twoReturns.1
>twoReturns
<twoReturns.2
<itCatches
>itCatches
>nestedCatches
>throw1
>catch1
>finally1
>catch2
>finally2
<nestedCatches
>itThrows
>callsAThrow
>itThrows
><init>1
<<init>1
<main
EOF
)

PERFETTO_FILES=$(findPerfetto)
if [ -n "$PERFETTO_FILES" ]; then
  fail "Report should not exist before execution"
fi
# Run the file without instrumentation
OUT=$(tools/base/tracer/trace_test)

if [ "$OUT" != "$EXPECTED" ]; then
  fail "Expected output does not match"
fi

PERFETTO_FILES=$(findPerfetto)
if [ -n "$PERFETTO_FILES" ]; then
  fail "Report should not exist after running with no agent"
fi

# Now run with:
OUT=$(tools/base/tracer/trace_test --jvm_flag=-javaagent:tools/base/tracer/trace_agent.jar=tools/base/tracer/agent/testSrc/com/android/tools/tracer/test.profile)

PERFETTO_FILES=$(findPerfetto)
if [ -z "$PERFETTO_FILES" ]; then
  fail "Report with profile should exist"
fi

EXPECTED_METHODS=(
  "void MainTest.main(String[])"
  "void MainTest.simple()"
  "int MainTest.twoReturns(boolean)"
  "void MainTest.itCatches()"
  "void MainTest.isAnnotated()"
  "void MainTest.nestedCatches()"
  "void MainTest.itThrows()"
  "void MainTest.callsAThrow()"
  "void Other.<init>()"
  "void PkgClass.<init>()"
  "manual trace"
  "custom events"
  "custom"
)

for method in "${EXPECTED_METHODS[@]}"; do
  if ! grep -a -F -q "$method" $PERFETTO_FILES; then
    fail "Expected trace method '$method' not found in any of: $PERFETTO_FILES"
  fi
done

if [ "$OUT" != "$EXPECTED" ]; then
  fail "Expected output with agent does not match"
fi

# Now run with no profile
OUT=$(tools/base/tracer/trace_test --jvm_flag=-javaagent:tools/base/tracer/trace_agent.jar)

if [ "$OUT" != "$EXPECTED" ]; then
  fail "Expected output with agent and no profile does not match"
fi
