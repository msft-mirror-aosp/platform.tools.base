# Perfetto trace_processor_shell

`trace_processor_shell` wraps `@perfetto//:trace_processor_shell`, Perfetto's
stock trace processor, built with the patches listed for `perfetto` in
`tools/base/bazel/toplevel.MODULE.bazel`.


## Release Process

After merging commits into HEAD, Studio postsubmit bots build the optimized binaries
and publish them as `trace_processor_shell` (`trace_processor_shell.exe` on Windows).


## Local e2e Testing

Build the binary with:
```shell
bazel build --config=release //tools/base/profiler/native/trace_processor_shell
```

The binary is at `bazel-bin/external/perfetto+/trace_processor_shell` (or
`trace_processor_shell.exe` on Windows).
