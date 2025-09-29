"""Performs mutation testing by randomly selecting and modifying a source file.

This script is designed to introduce a single, random modification ("mutation")
into the codebase to a file which is selected at random from the specified directories.
It operates by:
1.  Scanning specified directories for eligible Java or Kotlin source files,
    while respecting exclusion rules for test files and ignored paths.
2.  Randomly selecting one source file from the eligible list.
3.  Analyzing the chosen file to find all possible mutation points based on
    a set of predefined regular expression rules.
4.  Randomly choosing one of these potential mutations to apply.
5.  Overwriting the original source file with the newly mutated content.
6.  Generating a protobuf text-format metadata file that documents the exact
    change, including the file path, line number, and the original vs.
    mutated code.

The script is intended to be executed within a Bazel environment and accepts
command-line arguments to configure its behavior.

Command-line Arguments:
    --paths (List[str], optional): A list of directory paths to scan for
        source files. Defaults to paths defined in the global variable DEFAULT_PATHS.
    --ignore_paths (List[str], optional): A list of directory or file
        prefixes to exclude from the scan. Defaults to the paths defined in the
        global variable DEFAULT_IGNORE_PATHS.
    --metadata_file_path (str, required): The path where the output
        protobuf metadata file will be written. Supported file format: .txtpb
"""

import os
import re
import argparse
import random
from typing import Optional, List, Tuple
from tools.base.bazel.mutation.proto import mutation_pb2
from google.protobuf import text_format

# Default paths for picking files
DEFAULT_PATHS = ["tools/adt/idea", "tools/base", "tools/vendor/google", "tools/vendor/google3"]
DEFAULT_IGNORE_PATHS = [
    re.compile(r".*build-system/.*"),
]

# Allowed file format for mutation
ALLOWED_FILE_FORMAT = (".kt", ".java")

# Proto file header details
MUTATION_PROTO_FILE = "tools/base/bazel/mutation/proto/mutation.proto"
MUTATION_PROTO_MESSAGE = "MutationFileMetadata"

class MutationChange:
    def __init__(self, line_number: int, original_content: str, mutated_content: str):
        self.line_number = line_number
        self.original_content = original_content
        self.mutated_content = mutated_content


class MutationRegexMatcher:
    KOTLIN_REGEX_PATTERNS = [
        # Rule 1: Mutate a 'return' statement.
        (
            "return_exception",
            re.compile(r"(\s*)return "),
            lambda m: f"{m.group(1)}return if (true) throw RuntimeException(\"Catch me if you can!\") else ",
            lambda line: False
        ),

        # Rule 2: Mutate a 'val' declaration by inserting a new line before it.
        # Exclude line which have fun, class, override or ",","(",")","{","}" in it
        (
            "val_exception",
            re.compile(r"^([ \t]*)val "),
            lambda m: f"{m.group(1)}val __catch_me:Nothing = throw RuntimeException(\"Catch me if you can!\")\n{m.group(1)}val ",
            lambda line: bool(re.search(r'\b(class|fun|override)\b|[,(){}]', line))
        ),
    ]

    JAVA_REGEX_PATTERNS = [
        (
            "return_exception",
            re.compile(r"(\s*)return "),
            lambda m: f"{m.group(1)}if (true) {{ throw new RuntimeException(\"Catch me if you can!\");}} return ",
            lambda line: False
        ),
    ]

    @staticmethod
    def get_regex(source: str):
        if source.endswith(".kt"):
            return MutationRegexMatcher.KOTLIN_REGEX_PATTERNS

        if source.endswith(".java"):
            return MutationRegexMatcher.JAVA_REGEX_PATTERNS
        return None

def add_mutation_to_content(mutation: MutationChange, content: str) -> str:
    # Extract details from the mutation
    line_number = mutation.line_number

    # Replace the file content with the modified one
    content_lines = content.splitlines()

    # Line number is 1-based, array index is 0-based
    content_lines[line_number - 1] = mutation.mutated_content
    mutated_file_content = '\n'.join(content_lines)

    return mutated_file_content

def get_all_source_files(workspace_directory: str, allowed_paths: List[str], ignore_paths: List[str]) -> List[str]:
    # List to store all files
    sources = []
    # Get absolute paths to traverse
    absolute_allowed_paths = []
    for allowed_path in allowed_paths:
        absolute_allowed_paths.append(os.path.normpath(os.path.join(workspace_directory, allowed_path)))

    for curr_path in absolute_allowed_paths:
        for dir_path, sub_dirs, file_names in os.walk(curr_path):
            for file_name in file_names:
                full_file_path = str(os.path.join(dir_path, file_name))
                rel_path = str(os.path.relpath(path=full_file_path, start=workspace_directory))
                # Ensure the file has valid extension and is not a test file or a part of ignored_paths
                if (
                        full_file_path.endswith(ALLOWED_FILE_FORMAT)
                        and not is_test(full_file_path)
                        and not is_part_of_ignored_paths(path=rel_path, ignore_paths=ignore_paths)
                    ):
                    # Appending relative path to the workspace directory
                    sources.append(rel_path)

    return sources

def is_part_of_ignored_paths(path: str, ignore_paths: List[str|re.Pattern]):
    for ignore_path in ignore_paths:
        if isinstance(ignore_path, str):
            # Handle simple string prefix matching
            if path.startswith(ignore_path):
                return True
        elif isinstance(ignore_path, re.Pattern):
            # Handle compiled regular expression matching
            if ignore_path.search(path):
                return True
    return False

def inspect_tree(workspace_directory: str, allowed_paths: List[str], ignore_paths: List[str], metadata_file_path: str):
    # Get all files from the allowed paths, but not in ignore paths
    sources = get_all_source_files(workspace_directory=workspace_directory,
                                   allowed_paths=allowed_paths,
                                   ignore_paths=ignore_paths)

    # Flag to check if any mutation file is selected
    mutation_file_selected = False
    while sources and not mutation_file_selected:
        source = random.choice(sources)
        # Remove pair from list
        sources.remove(source)
        # We will try to mutate the file
        content = read(workspace_directory, source)
        possible_mutations_list = mutate(source, content)

        # Check if we have at least a single mutation possible, if not continue to next iteration
        if not possible_mutations_list:
            continue

        # Set this flag to true since we found a file where we can have a mutation
        mutation_file_selected = True

        # We will select a mutation at random from a list of mutations
        selected_mutation_change = random.choice(possible_mutations_list)
        # Replace the content with the selected_mutation_change at the appropriate line number
        mutated_file_content = add_mutation_to_content(mutation=selected_mutation_change, content=content)

        # Write the mutated file to the source path
        write(workspace_directory=workspace_directory,
              source=source,
              content=mutated_file_content)

        # Write metadata file
        write_metadata_file(metadata_file_path=metadata_file_path,
                            source=source,
                            mutation_change=selected_mutation_change)

        print("  [\033[32mSuccessfully Mutated File\033[0m] :" + workspace_directory + "/" + source)



def read(workspace_directory: str, source: str) -> str:
    source_path = os.path.join(workspace_directory, source)
    with open(source_path, 'r', encoding='utf-8') as f:
        content = f.read()
    return content

def write_metadata_file(metadata_file_path: str, source: str, mutation_change: MutationChange):

    # Create mutation proto object
    mutation_metadata_proto = mutation_pb2.MutationFileMetadata(
        file_path=source,
        line_number_mutated=mutation_change.line_number,
        original_content=mutation_change.original_content,
        mutated_content=mutation_change.mutated_content
    )

    # Writing headers to file according to this doc: go/textformat-spec#header
    mutation_metadata_content_list = [
        f"# proto-file: {MUTATION_PROTO_FILE}",
        f"# proto-message: {MUTATION_PROTO_MESSAGE}",
        text_format.MessageToString(mutation_metadata_proto)
    ]

    write_file(
        file_path=metadata_file_path,
        content="\n".join(mutation_metadata_content_list)
    )


def write_file(file_path: str, content: str):
    with open(file=file_path, mode="w", encoding='utf-8') as file:
        file.write(content)

def write(workspace_directory: str, source: str, content: str):
    source_path = os.path.join(workspace_directory, source)

    # Create the directory if it doesn't exist
    os.makedirs(os.path.dirname(source_path), exist_ok=True)

    write_file(file_path=source_path, content=content)

def mutate(source: str, content: str) -> Optional[List[MutationChange]]:
    """
        Finds all possible mutation points in a file based on a set of rules.
        This function parses the file line-by-line and checks each line against
        a series of regular expression patterns defined for the file type. For
        every match found, it records the potential mutation as a MutationChange
        object.

       Args:
           source: The filename of the source code, used to determine which
               set of regex rules to apply (e.g., for .kt or .java files).
           content: The string content of the source file to be scanned.

       Returns:
           A list of MutationChange objects, where each object represents a
           possible mutation. Returns an empty list if no mutation rules are defined
           for the given source file type or if rules exist but no mutations are found.
   """
    regex_patterns = MutationRegexMatcher.get_regex(source=source)
    # Check if any regex patterns were found for the source file extension, if not return None
    if not regex_patterns:
        return []

    # List to hold all possible mutations of the file
    possible_mutations_list = []
    # Iterate through each line with its number
    for line_number, line in enumerate(content.splitlines(), 1):
        # Try each pattern on the current line
        for version, pattern, regex_function, exclusion_function in regex_patterns:
            # Check if the line needs to be excluded from regex matching
            if exclusion_function(line):
                continue
            # Use re.subn to check for a match and perform replacement in one step.
            # It returns the new string and the number of substitutions made.
            modified_line, num_subs = pattern.subn(regex_function, line, count=1)

            if num_subs > 0:
                # A mutation was successful for this line, create an MutationChange object and add it to the list
                possible_mutations_list.append(
                    MutationChange(
                        line_number=line_number,
                        original_content=line,
                        mutated_content=modified_line
                    )
                )

    return possible_mutations_list

def is_test(source: str) -> bool:
    if "/testSrc/" in source or "/testData/" in source:
        return True
    if source.endswith("Test.kt") or source.endswith("Test.java"):
        return True
    return False

def main(args):
    build_workspace_directory = os.environ.get("BUILD_WORKSPACE_DIRECTORY")
    if not build_workspace_directory:
        print("Error: BUILD_WORKSPACE_DIRECTORY environment variable is not set or is empty.", file=os.sys.stderr)
        os.sys.exit(1) # Exit with an error code

    inspect_tree(build_workspace_directory, args.paths, args.ignore_paths, args.metadata_file_path)



if __name__ == '__main__':
    parser = argparse.ArgumentParser(description = 'Mutation Testing')
    parser.add_argument("--paths", nargs="*", default=DEFAULT_PATHS)
    parser.add_argument("--ignore_paths", nargs="*", default=DEFAULT_IGNORE_PATHS)
    parser.add_argument("--metadata_file_path", type=str, required=True)
    args = parser.parse_args()
    main(args)
