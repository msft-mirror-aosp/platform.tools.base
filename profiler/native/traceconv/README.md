# Perfetto traceconv

`traceconv` wraps `@perfetto//:traceconv`. In Android Studio Profilers, it is used
by `TraceconvBundler` to bundle native debug symbols and Proguard/R8 mapping files
into Perfetto trace captures (`.heapprofd`).


## Release Process

After merging commits into HEAD, Studio postsubmit bots build the optimized binaries.
Update the prebuilts using:
```shell
cd prebuilts/tools/common/traceconv
./fetch.py --bid <build_id>
```

> **Note**: Upgrading `traceconv` may emit newer trace schemas. Verify whether
> `trace_processor_daemon` also needs to be upgraded.


## Local e2e Testing

For local e2e testing with Android Studio, build the binary with:
```shell
bazel build --config=release //tools/base/profiler/native/traceconv
```

Copy the generated binary from `bazel-bin/external/perfetto+/traceconv` (or `traceconv.exe` on Windows) into:
```
.../prebuilts/tools/common/traceconv/[local_platform]/
```

You might need to `chmod +x` if on Linux or Mac.
Remember to `git restore` the binary when you're done.
