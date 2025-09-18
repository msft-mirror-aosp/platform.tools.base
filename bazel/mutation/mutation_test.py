import os
from unittest import mock

from absl.testing import absltest
from absl.testing import parameterized

from tools.base.bazel.mutation import mutation

class MutationScriptTest(parameterized.TestCase):

    @parameterized.named_parameters(
        ('testSrc_in_path', 'path/to/testSrc/some/File1.kt', True),
        ('testData_in_path', 'path/to/testData/some/File2.java', True),
        ('test_suffix_in_path', 'path/to/LinuxTest.kt', True ),
        ('test_non_test_path_1', 'path/to/production/File3.kt', False),
        ('test_non_test_path_2', 'path/to/production/File4.java', False)
    )
    def test_is_test(self, path: str, expected_return_value: bool):
        self.assertEqual(mutation.is_test(path), expected_return_value)

    @parameterized.named_parameters(
        (
                'path_is_a_subdirectory_of_ignored_path',
                "tools/base/ignored/file.kt",
                ["tools/base/ignored", "prebuilts/"],
                True
        ),
        (
                'path_is_not_in_ignored_list',
                "tools/base/not_ignored/file.kt",
                ["tools/base/ignored"],
                False
        ),
        (
                'path_is_an_exact_match_of_ignored_path',
                "prebuilts/special.jar",
                ["tools/base/ignored", "prebuilts/"],
                True
        )
    )
    def test_is_part_of_ignored_paths(self, path: str, ignore_list: list[str], expected_return_value: bool):
        """Tests if a path is correctly identified as being in an ignore list."""
        self.assertEqual(
            mutation.is_part_of_ignored_paths(path, ignore_list),
            expected_return_value
        )

    def test_add_mutation_to_content(self):
        original_content = "line 1\nline 2\nline 3"
        change = mutation.MutationChange(
            line_number=2,
            original_content="line 2",
            mutated_content="mutated line 2"
        )
        expected_content = "line 1\nmutated line 2\nline 3"
        result = mutation.add_mutation_to_content(change, original_content)
        self.assertEqual(result, expected_content)

    @parameterized.named_parameters(
        ('kotlin_return', 'file.kt', 'return 1', 'return if (true) throw RuntimeException(\"Catch me if you can!\") else 1'),
        ('java_return', 'file.java', 'return ;', 'if (true) { throw new RuntimeException(\"Catch me if you can!\");} return ;'),
        ('kotlin_val', 'file.kt', 'val x = 1','val __catch_me:Nothing = throw RuntimeException(\"Catch me if you can!\")\nval x = 1')
    )
    def test_mutate_finds_and_generates_correct_mutations(self, filename, content, expected_substring):
        """Tests that mutate finds a single mutation and its content is correct."""
        results = mutation.mutate(filename, content)
        self.assertLen(results, 1, f"Expected 1 mutation for '{filename}', but found {len(results)}")
        self.assertEqual(expected_substring, results[0].mutated_content)

    @parameterized.named_parameters(
        ('no_match_in_kt', 'file.kt', 'fun test() {}'),
        ('unsupported_file_type', 'file.txt', 'return 1'),
    )
    def test_mutate_returns_empty_for_no_match(self, filename, content):
        """Tests that mutate returns an empty list when no mutation is possible."""
        results = mutation.mutate(filename, content)
        self.assertEmpty(results)

    def test_get_all_source_files(self):
        """Tests discovery of source files in a temp directory."""
        workspace_dir = self.create_tempdir()
        allowed_path = "src"
        ignored_path_prefix = "src/ignored"

        # Create a directory structure
        os.makedirs(os.path.join(workspace_dir, ignored_path_prefix))
        os.makedirs(os.path.join(workspace_dir, "src/testSrc"))

        # Create test files
        with open(os.path.join(workspace_dir, "src/Prod1.kt"), "w") as f: f.write("")
        with open(os.path.join(workspace_dir, "src/Prod2.java"), "w") as f: f.write("")
        with open(os.path.join(workspace_dir, "src/testSrc/MyTest.kt"), "w") as f: f.write("")
        with open(os.path.join(workspace_dir, ignored_path_prefix, "IgnoredFile.kt"), "w") as f: f.write("")
        # .txt file should not be returned
        with open(os.path.join(workspace_dir, "src/data.txt"), "w") as f: f.write("")

        sources = mutation.get_all_source_files(
            workspace_directory=workspace_dir.full_path,
            allowed_paths=[allowed_path],
            ignore_paths=[ignored_path_prefix]
        )

        # We expect only the two production files
        self.assertLen(sources, 2)
        self.assertContainsSubset(["src/Prod1.kt", "src/Prod2.java"], sources)

    @mock.patch('tools.base.bazel.mutation.mutation.write_file')
    def test_write_metadata_file(self, mock_write_file):
        """Verify the content passed to write_file for metadata."""
        change = mutation.MutationChange(
            line_number=42,
            original_content="return 1;",
            mutated_content="throw new Exception();"
        )
        mutation.write_metadata_file(
            metadata_file_path="/tmp/meta.txtpb",
            source="path/to/File.java",
            mutation_change=change
        )

        # Check that write_file was called once
        mock_write_file.assert_called_once()
        # Get the content that was passed to write_file
        call_args = mock_write_file.call_args[1]
        content = call_args['content']

        self.assertIn(f"# proto-file: {mutation.MUTATION_PROTO_FILE}", content)
        self.assertIn(f"# proto-message: {mutation.MUTATION_PROTO_MESSAGE}", content)
        self.assertIn('file_path: "path/to/File.java"', content)
        self.assertIn('line_number_mutated: 42', content)
        self.assertIn('original_content: "return 1;"', content)


if __name__ == '__main__':
    absltest.main()
