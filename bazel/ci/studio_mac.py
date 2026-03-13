"""Implements studio-mac CI scripts."""

import pathlib
from typing import List

from tools.base.bazel.ci import bazel
from tools.base.bazel.ci import studio
from tools.base.bazel.ci.presubmit import impacted_targets

# TODO: Why is this unused?
_ARTIFACTS = [
    ('tools/adt/idea/studio/android-studio.linux.zip', 'artifacts'),
    ('tools/adt/idea/studio/android-studio.win.zip', 'artifacts'),
    ('tools/adt/idea/studio/android-studio.mac.zip', 'artifacts'),
]


def studio_mac(build_env: bazel.BuildEnv) -> None:
  """Runs studio-mac target."""
  # List attributes, like tags, are converted into into strings in the format:
  # [value1, value2]. So a tag ends with a comma (,) or closing bracket (]).
  query = [r'attr(tags, "ci:studio-mac[,\]]", //tools/...)']
  test_targets = (
      build_env.bazel_query(*query).stdout.decode('utf-8').splitlines()
  )
  targets = test_targets + [
      '//tools/vendor/google/android:android',
      '//tools/vendor/google/android:android-cli.zip',
      '//tools/vendor/google/skia:skiaparser',
      '//tools/vendor/google/skia:skia_test_support',
      '//tools/base/profiler/native/trace_processor_daemon',
      '//tools/base/profiler/native/sherlock:sherlock_trace_processor',
  ]
  flags = build_flags(
      build_env,
      'ci:studio-mac',
  )
  result = studio.run_bazel_test(build_env, flags, targets)
  if studio.is_build_successful(result):
    if build_env.dist_dir:
      studio.copy_artifacts(
          build_env,
          [
              (
                  'tools/base/profiler/native/trace_processor_daemon/trace_processor_daemon',
                  '',
              ),
              (
                  'tools/base/profiler/native/sherlock/sherlock_trace_processor',
                  '',
              ),
              ('tools/vendor/google/skia/skiaparser.zip', ''),
              ('tools/vendor/google/skia/skia_test_support.zip', ''),
              ('tools/vendor/google/android/android', ''),
              ('tools/vendor/google/android/android-cli.zip', ''),
          ],
      )
      studio.collect_logs(build_env, result.bes_path)
    if result.exit_code != bazel.EXITCODE_NO_TESTS_FOUND:
      return
  else:
    studio.copy_bazel_logs(build_env)

  raise studio.BazelTestError(exit_code=result.exit_code)


def studio_mac_arm(build_env: bazel.BuildEnv) -> None:
  """Runs studio-mac-arm target."""
  query = ['attr(tags, ci:studio-mac-arm, //tools/...)']
  test_targets = (
      build_env.bazel_query(*query).stdout.decode('utf-8').splitlines()
  )
  targets = test_targets + [
      '//tools/vendor/google/android:android',
      '//tools/vendor/google/android:android-cli.zip',
      '//tools/vendor/google/skia:skiaparser',
      '//tools/vendor/google/skia:skia_test_support',
      '//tools/base/profiler/native/trace_processor_daemon',
      '//tools/base/profiler/native/sherlock:sherlock_trace_processor',
      '//tools/adt/idea/android/native/diagnostics/heap:libjni_object_tagger',
  ]
  flags = build_flags(build_env) + [
      '--discard_analysis_cache',
      '--nokeep_state_after_build',
  ]
  result = studio.run_bazel_test(build_env, flags, targets)
  if studio.is_build_successful(result):
    if build_env.dist_dir:
      studio.copy_artifacts(
          build_env,
          [
              (
                  'tools/base/profiler/native/trace_processor_daemon/trace_processor_daemon',
                  '',
              ),
              (
                  'tools/base/profiler/native/sherlock/sherlock_trace_processor',
                  '',
              ),
              (
                  'tools/adt/idea/android/native/diagnostics/heap/libjni_object_tagger.dylib',
                  '',
              ),
              ('tools/vendor/google/skia/skiaparser.zip', ''),
              ('tools/vendor/google/skia/skia_test_support.zip', ''),
              ('tools/vendor/google/android/android', ''),
              ('tools/vendor/google/android/android-cli.zip', ''),
          ],
      )
    if result.exit_code != bazel.EXITCODE_NO_TESTS_FOUND:
      return
  else:
    studio.copy_bazel_logs(build_env)

  raise studio.BazelTestError(exit_code=result.exit_code)


def build_flags(
    build_env: bazel.BuildEnv,
    test_tag_filters: str = '',
) -> List[str]:
  """Returns the flags to use for testing."""
  dist_path = pathlib.Path(build_env.dist_dir)
  profile_path = dist_path / f'profile-{build_env.build_number}.json.gz'

  return [
      f'--profile={profile_path}',
      f'--test_tag_filters={test_tag_filters}',
      '--tool_tag=studio_mac.sh',
  ]
