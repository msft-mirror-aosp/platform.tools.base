"""Functions related to Gerrit for presubmit."""

import dataclasses
import hashlib
import logging
import re
from typing import Iterator, List, Sequence

from tools.base.bazel.ci import bazel
from tools.base.bazel.ci import gce


@dataclasses.dataclass(frozen=True)
class GerritInfo:
  """Represents all Gerrit-related information for the current build."""
  _build_env: bazel.BuildEnv

  changes: List[gce.GerritChange]
  changes_hash: str

  def filter_tags(self, filter_tag: str) -> Iterator[str]:
    """Yields tag values from Gerrit change tags.

    Tag values are expected to be in one of two formats:
      - <ab_target>:<value>
      - <value>

    Args:
      filter_tag: The tag to look for.

    Yields:
      The tag values from the targeted Gerrit changes.
    """
    for gerrit_change in self.changes:
      for tag, value in gerrit_change.tags:
        if tag.lower() != filter_tag.lower():
          continue
        logging.info('Found %s tag: %s', tag, value)
        # AB target names only contain word characters and hyphens.
        match = re.fullmatch(r'^([\w-]+):(.+)$', value)
        if match:
          ab_target, value = match.group(1), match.group(2)
          if ab_target != self._build_env.build_target_name:
            continue
        yield value

  def get_bazel_flags(self) -> List[str]:
    change = self.changes[0]
    flags = [
      f'--build_metadata=gerrit_change_set_hash={self.changes_hash}',
      f'--build_metadata=gerrit_owner={change.owner}',
      f'--build_metadata=gerrit_change_id={change.change_id}',
      f'--build_metadata=gerrit_change_number={change.change_number}',
      f'--build_metadata=gerrit_change_patchset={change.patchset}',
    ]
    if change.topic:
      flags.append(f'--build_metadata=gerrit_topic={change.topic}')
    return flags


def get_gerrit_info(build_env: bazel.BuildEnv) -> GerritInfo:
  """Returns Gerrit info for the current build."""
  changes = gce.get_gerrit_changes(build_env.build_number)
  changes_hash = _change_set_hash(changes)

  return GerritInfo(
      _build_env=build_env,
      changes=changes,
      changes_hash=changes_hash,
  )


def _change_set_hash(changes: Sequence[gce.GerritChange]) -> str:
  """Returns a hash of the change set."""
  changes = sorted(changes, key=lambda c: int(c.change_number))
  hasher = hashlib.new('sha256')
  for c in changes:
    hasher.update(int(c.change_number).to_bytes(length=8, byteorder='little'))
    hasher.update(int(c.patchset).to_bytes(length=4, byteorder='little'))
  return hasher.hexdigest()


def filter_tags(gerrit_info: GerritInfo, filter_tag: str) -> Iterator[str]:
  """Yields tag values from Gerrit change tags.

  Tag values are expected to be in one of two formats:
    - <ab_target>:<value>
    - <value>

  Args:
    gerrit_info: The Gerrit info.
    filter_tag: The tag to look for.

  Yields:
    The tag values from the targeted Gerrit changes.
  """
  for gerrit_change in gerrit_info.changes:
    for tag, value in gerrit_change.tags:
      if tag.lower() != filter_tag.lower():
        continue
      logging.info('Found %s tag: %s', tag, value)
      # AB target names only contain word characters and hyphens.
      match = re.fullmatch(r'^([\w-]+):(.+)$', value)
      if match:
        ab_target, value = match.group(1), match.group(2)
        if ab_target != build_env.build_target_name:
          continue
      yield value
