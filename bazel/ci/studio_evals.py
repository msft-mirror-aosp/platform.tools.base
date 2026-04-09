"""Implements uitools evals CI scripts."""

from typing import List
from tools.base.bazel.ci import bazel
from tools.base.bazel.ci import studio

_TARGETS = [
    '//tools/vendor/google/ml/aiplugin/android/uitools-evals/...',
    '//tools/vendor/google/ml/aiplugin/android/upgrade-evals/...',
    '//tools/vendor/google/ml/aiplugin/android/rag-evals/...',
]

_FLAGS = [
    '--nozip_undeclared_test_outputs',
    '--test_timeout=3600',
    '--nocache_test_results',
    '--config=remote-exec',
    '--test_env=SECRET_MANAGER_KEY=DEFAULT_GEMINI_KEY',
    '--bes_keywords=cinder',
    '--build_metadata=cinder_pipelines=studio-evals',
    '--jobs=5',  # avoid having too many jobs in parallel to prevent quota issues
]

def _get_upgrade_bot_evals_tests(build_env: bazel.BuildEnv) -> List[str]:
  """Queries bazel for upgrade evals tests."""
  query = [f'attr(tags, "upgrade_bot_eval", {" + ".join(_TARGETS)})']
  target_tests = build_env.bazel_query(*query).stdout.decode('utf-8').splitlines()
  return target_tests

def _get_uitools_evals_tests(build_env: bazel.BuildEnv) -> List[str]:
  """Queries bazel for uitools evals tests."""
  query = [f'attr(tags, "uitools_eval", {" + ".join(_TARGETS)})']
  target_tests = build_env.bazel_query(*query).stdout.decode('utf-8').splitlines()
  return target_tests

def _get_rag_evals_tests(build_env: bazel.BuildEnv) -> List[str]:
  """Queries bazel for rag evals tests."""
  query = [f'attr(tags, "rag_eval", {" + ".join(_TARGETS)})']
  target_tests = build_env.bazel_query(*query).stdout.decode('utf-8').splitlines()
  return target_tests

def studio_evals(build_env: bazel.BuildEnv):
  """Runs studio evals tests."""
  flags = _FLAGS + [
      f'--test_env=AB_BUILD_ID={build_env.build_number}',
      f'--test_env=AB_BUILD_BRANCH={build_env.branch}',
  ]
  target_tests = _get_uitools_evals_tests(build_env)
  target_tests.extend(_get_upgrade_bot_evals_tests(build_env))
  target_tests.extend(_get_rag_evals_tests(build_env))
  test_result = studio.run_tests(build_env, flags, target_tests)

  if not studio.is_build_successful(test_result):
    studio.copy_bazel_logs(build_env)
    raise studio.BazelTestError(exit_code=test_result.exit_code)
