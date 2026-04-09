function fail {
   echo $1
   exit 1
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

PERFETTO_FILES=$(find . -name "*.perfetto*")
if [ -n "$PERFETTO_FILES" ]; then
  fail "Report should not exist before execution"
fi
# Run the file without instrumentation
OUT=$(tools/base/tracer/trace_test)

if [ "$OUT" != "$EXPECTED" ]; then
  fail "Expected output does not match"
fi

PERFETTO_FILES=$(find . -name "*.perfetto*")
if [ -n "$PERFETTO_FILES" ]; then
  fail "Report should not exist after running with no agent"
fi

# Now run with:
OUT=$(tools/base/tracer/trace_test --jvm_flag=-javaagent:tools/base/tracer/trace_agent.jar=tools/base/tracer/agent/testSrc/com/android/tools/tracer/test.profile)

PERFETTO_FILES=$(find . -name "*.perfetto*")
if [ -z "$PERFETTO_FILES" ]; then
  fail "Report with profile should exist"
fi

PERFETTO_FILE=$(echo "$PERFETTO_FILES" | head -n 1)

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
  if ! grep -a -F "$method" "$PERFETTO_FILE" > /dev/null; then
    fail "Expected trace method '$method' not found in $PERFETTO_FILE"
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
