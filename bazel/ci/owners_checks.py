import argparse
import dataclasses
import functools
import os
import sys
from typing import List

from tools.base.bazel.ci import bazel
from tools.base.bazel.ci import errors


_EXEMPT_LIST = "tools/base/bazel/ci/owners_exempt.lst"


@dataclasses.dataclass(frozen=True,kw_only=True)
class UnownedFilesError(errors.CIError):
  unowned_files: set[str]

  def __str__(self) -> str:
    unowned_files = '\n'.join(self.unowned_files)
    return f"""#############################
# ERROR: Found files without an OWNERS and component ID
{unowned_files}

Total unowned files: {len(self.unowned_files)}
See go/studio-code-ownership for more info
"""


def _is_single_directory_owned(directory_path: str) -> bool:
  """Checks if the specified directory contains an 'OWNERS' file with a '# Bug component:' line. """
  owner_file_path = os.path.join(directory_path, 'OWNERS')

  if os.path.exists(owner_file_path) and os.path.isfile(owner_file_path):
    with open(owner_file_path, 'r', encoding='utf-8') as f:
      for line in f:
        if line.strip().startswith('# Bug component:'):
          return True
  return False


@functools.cache
def _is_directory_owned(directory_path: str) -> bool:
  """Checks if a directory is itself or are its parents owned. """

  if _is_single_directory_owned(directory_path):
    return True

  parent_dir = os.path.dirname(directory_path)
  if parent_dir != directory_path:
    return _is_directory_owned(parent_dir)

  return False


def require_component_id(build_env: bazel.BuildEnv):
  """ Checks all files in the workspace are owned (except exempted ones)."""
  unowned_files = unowned_unexempt_files(build_env.workspace_dir)
  if unowned_files:
    raise UnownedFilesError(unowned_files=unowned_files)


def _find_unowned_files(workspace_dir: str) -> (set[str], int):
  """Returns the set of files not owned in a directory tree."""

  unowned_files = set()
  sources = [
      'tools/base',
      'tools/adt/idea',
      'tools/vendor/google',
      'tools/vendor/google3',
  ]

  total = 0
  for source_root in sources:
    directory_path = os.path.join(workspace_dir, source_root)
    for root, _, files in os.walk(directory_path):
      for file in files:
        if file.endswith(('.java', '.kt')):
          total += 1
          if not _is_directory_owned(root):
            file_path = os.path.join(root, file)
            workspace_path = os.path.relpath(file_path, workspace_dir)
            unowned_files.add(workspace_path)

  return unowned_files, total


def _write_exempt_files(workspace_dir: str, files: set[str]):
  file_path = os.path.join(workspace_dir, _EXEMPT_LIST)
  with open(file_path, 'w', encoding='utf-8') as file:
    for path in sorted(files):
      file.write(f"{path}\n")


def _read_exempt_files(workspace_dir: str) -> set[str]:
  file_path = os.path.join(workspace_dir, _EXEMPT_LIST)
  exempt_files = set()
  with open(file_path, 'r', encoding='utf-8') as file:
    for line in file:
      exempt_files.add(line.strip())
  return exempt_files


def unowned_unexempt_files(workspace_dir: str) -> List[str]:
  """Returns all unowned files (minus exempt ones)."""
  unowned, total = _find_unowned_files(workspace_dir)
  exempt = _read_exempt_files(workspace_dir)
  new_files = unowned - exempt
  print(f'Checked {total} files. Of which {len(new_files)} are unowned, and {len(unowned) - len(new_files)} are exempt.')
  return new_files


def _update_owned_files(workspace_dir):
  unowned, _ = _find_unowned_files(workspace_dir)
  _write_exempt_files(workspace_dir, unowned)


def main():
  parser = argparse.ArgumentParser()
  parser.add_argument('action', help='The action to perform', choices=["check", "update"], nargs="?", default="update")
  args = parser.parse_args()
  workspace_dir = os.environ.get('BUILD_WORKSPACE_DIRECTORY')
  if args.action == "check":
    unowned_files = unowned_unexempt_files(workspace_dir)
    if unowned_files:
      raise UnownedFilesError(unowned_files=unowned_files)
  elif args.action == "update":
    _update_owned_files(workspace_dir)


if __name__ == '__main__':
  sys.exit(main())
