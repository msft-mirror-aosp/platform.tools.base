# UI Inspector CLI - Agent Guide
* Ref: `tools/vendor/google/android/` (`run`/`interact` for ADB, `cli` for I/O).
* Style: Use imports instead of full class names.

## Real Device Test
1. `adb devices` -> `<serial>`
2. `adb shell pm list packages -3` -> `<pkg>`
   Check: `adb shell run-as <pkg> id`
3. `tools/base/bazel/bazel run //tools/base/ui-inspector/host:cli -- dump-ui --serial <serial> --package <pkg>`
