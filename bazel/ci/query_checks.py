"""Implements checks on the build graph using 'bazel query'."""

import dataclasses
import logging

from tools.base.bazel.ci import bazel
from tools.base.bazel.ci import errors

# A query matching targets opted out of presubmit (studio-win and studio-linux).
_QUERY_TARGETS_PRESUBMIT_OPT_OUT = r'(attr(tags, "noci:studio-linux[,\]]", //...) intersect attr(tags, "noci:studio-win[,\]]", //...))'


@dataclasses.dataclass(frozen=True,kw_only=True)
class BuildGraphException(errors.CIError):
  """Represents a build graph exception."""
  title: str
  go_link: str
  body: str
  exit_code: int = 1

  def __str__(self) -> str:
    return f"""#############################
# {self.title} - {self.go_link}
{self.body}
"""


def cquery_all(build_env: bazel.BuildEnv):
  """Run cquery and validate default target configurations."""
  build_env.bazel_cquery(
      '//tools/...',
      'union',
      '//prebuilts/...',
      '--config=ci',
  )


def no_local_genrules(build_env: bazel.BuildEnv):
  """Verify targets are not using local=True.

  Build actions, like custom genrule targets, should use sandbox execution to
  avoid modifying the source workspace files.

  Raises:
    BuildGraphException: If build targets are using local=True.
  """
  result = build_env.bazel_query('attr("local", "1", //tools/...)')
  query_targets = result.stdout.decode('utf8').splitlines(keepends=True)
  with open('tools/base/bazel/ci/data/allowlist-targets-local-strategy.txt', encoding='utf8') as f:
    golden_targets = f.readlines()
  new_targets = list(set(query_targets) - set(golden_targets))

  if new_targets:
    raise BuildGraphException(
        title='Disallow local strategy',
        go_link='go/studio-ci#no-local-genrules',
        body='ERROR: The following targets are using local=1\n'+''.join(new_targets),
    )


def require_cpu_tags(build_env: bazel.BuildEnv):
  """Require certain targets have cpu:N tags."""
  result = build_env.bazel_query(
    'attr(tags, "ci:studio-mac", //tools/...) except attr(tags, "cpu:[0-9]+", //tools/...)')
  if not result.stdout:
    return

  raise BuildGraphException(
    title='MacOS tests must have a cpu:[0-9] tag',
    go_link='go/studio-ci#macos',
    body='ERROR: The following targets are missing a cpu:N tag.\n'+result.stdout.decode('utf8')
  )


def gradle_requires_cpu4_or_more(build_env: bazel.BuildEnv):
  """Tests running on MacOS using Gradle must declare cpu:4 or higher."""
  studio_mac_tags = 'attr(tags, "ci:studio-mac", //tools/...)'
  high_cpu_tags = 'attr(tags, "cpu:([4-9]|[1-9][1-9])", //tools/...)'
  candidate_query = f'{studio_mac_tags} except {high_cpu_tags}'

  result = build_env.bazel_query(candidate_query)
  if not result.stdout:
    return

  starlark_expr = (
      'str(target.label) if [f for f in'
      ' providers(target)["DefaultInfo"].default_runfiles.files.to_list() if'
      ' "tools/external/gradle/" in f.path and f.path.endswith(".zip")] else ""'
  )
  cquery_result = build_env.bazel_cquery(
      candidate_query,
      '--output=starlark',
      f'--starlark:expr={starlark_expr}',
  )
  failing_targets = [
      f' //{line.strip().removeprefix("@@//").removeprefix("//")}'
      for line in cquery_result.stdout.decode('utf8').splitlines()
      if line.strip()
  ]
  if not failing_targets:
    return

  raise BuildGraphException(
      title='Gradle tests need cpu:4 or greater',
      go_link='go/studio-ci#macos',
      body=(
          'ERROR: The follow targets depend on //tools/base/build-system:gradle-distrib'
          ' and must have cpu:4 or greater\n'
      ) + '\n'.join(failing_targets),
  )


def check_large_machine_allowlist(build_env: bazel.BuildEnv):
  """Targets using large machines are not allowed on presubmit."""
  perfgate_release_targets = r'attr(tags, "perfgate-release[,\]]", //...)'
  query = r'attr(exec_properties, "[,{ ]label:machine-size=large[,}]", //...)'
  query += f' except {_QUERY_TARGETS_PRESUBMIT_OPT_OUT} except {perfgate_release_targets}'
  result = build_env.bazel_query(query)
  if not result.stdout:
    return
  query_targets = result.stdout.decode('utf8').splitlines()
  raise BuildGraphException(
        title='Large machines are not allowed on presubmit. Please consult with android-devtools-infra@',
        go_link='',
        body=(
            'ERROR: The following targets are using large machines.\n'
        ) + '\n'.join(query_targets)
    )


def check_docker_network_allowlist(build_env: bazel.BuildEnv):
  """Targets using docker network are not allowed on presubmit."""
  query = r'attr(exec_properties, "[,{ ]dockerNetwork=standard[,}]", //...)'
  query += f' except {_QUERY_TARGETS_PRESUBMIT_OPT_OUT}'
  result = build_env.bazel_query(query)
  if not result.stdout:
    return
  targets = result.stdout.decode('utf8').splitlines()
  with open('tools/base/bazel/ci/data/allowlist-docker-network.txt', encoding='utf8') as f:
    allowlist_targets = f.read().splitlines()

  targets = list(set(targets) - set(allowlist_targets))
  if not targets:
    return
  raise BuildGraphException(
        title='Relying on network access is discouraged. Please consult with android-devtools-infra@',
        go_link='',
        body=(
            'ERROR: The follow targets set dockerNetwork=standard.\n'
        ) + '\n'.join(targets)
    )
