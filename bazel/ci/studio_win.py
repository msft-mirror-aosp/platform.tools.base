"""Implements studio-win CI script."""

import pathlib
import tempfile

from tools.base.bazel.ci import bazel
from tools.base.bazel.ci import studio
from tools.base.bazel.ci.presubmit import failure_retry
from tools.base.bazel.ci.presubmit import impacted_targets
from tools.base.bazel.ci.presubmit import presubmit


def studio_win(build_env: bazel.BuildEnv):
  """Runs Windows pre/postsubmit tests."""
  # If DIST_DIR does not exist, create one.
  if not build_env.dist_dir:
    build_env.dist_dir = tempfile.mkdtemp('dist-dir')
  dist_path = pathlib.Path(build_env.dist_dir)

  targets = [
      '//prebuilts/studio/...',
      '//prebuilts/tools/...',
      '//tools/...',
      '-//tools/vendor/google3/aswb/...',
      '-//tools/vendor/google/aswb/...',
      '-//tools/adt/idea/aswb/...',
  ]
  extra_targets = [
      '//tools/base/profiler/native/trace_processor_daemon',
      '//tools/base/profiler/native/sherlock:sherlock_trace_processor',
      '//tools/adt/idea/studio:android-studio.linux.zip',
      '//tools/adt/idea/studio:android-studio.mac.zip',
      '//tools/adt/idea/studio:android-studio.mac_arm.zip',
      '//tools/adt/idea/studio:android-studio.win.zip',
      '//tools/vendor/google/skia:skiaparser.zip',
      '//tools/vendor/google/skia:skia_test_support.zip',
  ]
  test_tag_filters = '-noci:studio-win,-qa_smoke,-qa_fast,-qa_unreliable,-perfgate-release,-no_k2'

  profile_path = dist_path / f'winprof{build_env.build_number}.json.gz'
  flags = [
      # TODO(b/173153395) Switch back to dynamic after Bazel issue is resolved.
      # See https://github.com/bazelbuild/bazel/issues/22482
      '--config=remote-exec',
      f'--profile={profile_path}',

      f'--test_tag_filters={test_tag_filters}',

      '--tool_tag=studio_win.cmd',
      '--jobs=500',
  ]

  build_type = studio.BuildType.from_build_number(build_env.build_number)
  if build_type == studio.BuildType.POSTSUBMIT:
    impacted_targets.generate_and_upload_hash_file(build_env)
    targets += extra_targets

  if build_type == studio.BuildType.PRESUBMIT:
    result = presubmit.find_test_targets(
        build_env,
        targets,
        test_tag_filters,
    )
    # ci_test is included so that there is always a test target to run.
    targets = result.targets + ['//tools/base/bazel/ci:ci_test']
    flags.extend(result.flags)

  test_result = studio.run_tests(build_env, flags, targets)

  if build_type == studio.BuildType.PRESUBMIT:
    failure_retry.validate_and_upload(build_env)

  studio.copy_artifacts(
      build_env,
      [
          ('tools/vendor/google/android/android', ''),
          ('tools/vendor/google/android/android-cli.zip', ''),
          ('tools/vendor/google/skia/skiaparser.zip', ''),
          ('tools/vendor/google/skia/skia_test_support.zip', ''),
          ('tools/base/profiler/native/trace_processor_daemon/trace_processor_daemon.exe', ''),
          ('tools/base/profiler/native/sherlock/sherlock_trace_processor.exe', ''),
      ],
      missing_ok=(build_type == studio.BuildType.PRESUBMIT),
  )

  build_env.bazel_shutdown()

  if test_result.exit_code in {
      bazel.EXITCODE_SUCCESS,
      bazel.EXITCODE_TEST_FAILURES,
  }:
    return

  raise studio.BazelTestError(exit_code=test_result.exit_code)
