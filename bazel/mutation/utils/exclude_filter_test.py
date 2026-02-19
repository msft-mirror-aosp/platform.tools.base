from absl.testing import absltest
from absl.testing import parameterized
from tools.base.bazel.mutation.utils import exclude_filter

class CommentFilterTest(parameterized.TestCase):
    def setUp(self):
        self.filter = exclude_filter.CommentFilter()

    @parameterized.named_parameters(
        ('single_line_comment', ['// This is a comment'], 0, True),
        ('multi_line_comment_start', ['/* Start of comment'], 0, True),
        ('code_line', ['val x = 1;'], 0, False),
        ('comment_after_code_match_false', ['val x = 1; // comment'], 0, False),
        ('multi_line_inline_match_true', ['val x = 1; /* comment */'], 0, True),
        ('empty_line', [''], 0, False),
    )
    def test_match(self, lines, index, expected_result):
        self.assertEqual(self.filter.match(lines, index), expected_result)

    @parameterized.named_parameters(
        (
                'single_line_range',
                ['// comment'],
                0,
                (0, 0)
        ),
        (
                'multi_line_on_single_line',
                ['/* comment */'],
                0,
                (0, 0)
        ),
        (
                'multi_line_spanning_lines',
                [
                    'val x = 1;',
                    '/* Start',
                    ' * Middle',
                    ' */',
                    'val y = 2;'
                ],
                1,
                (1, 3)
        ),
        (
                'multi_line_unterminated',
                ['/* Start', 'Middle'],
                0,
                (0, 2) # Note: Implementation returns index equal to len(lines) if unterminated
        ),
    )
    def test_get_filter_range(self, lines, index, expected_range):
        self.assertEqual(self.filter.get_filter_range(lines, index), expected_range)

    def test_get_filter_range_returns_none_if_no_start_marker(self):
        # Edge case where match might be true (e.g. regex match) but logic inside get_filter_range
        # specifically checks for "/*" if it wasn't a "//" match.
        # However, the code structure implies get_filter_range handles "//" first.
        # If we pass a line that doesn't match either, it returns None.
        lines = ["val x = 1;"]
        self.assertIsNone(self.filter.get_filter_range(lines, 0))


class AbstractInterfaceFilterTest(parameterized.TestCase):
    def setUp(self):
        self.filter = exclude_filter.AbstractInterfaceFilter()

    @parameterized.named_parameters(
        ('abstract_class', ['abstract public static class Foo {'], 0, True),
        ('interface', ['interface Foo {'], 0, True),
        ('public_interface', ['public interface Foo {'], 0, True),
        ('concrete_class', ['class Foo {'], 0, False),
        ('abstract_method', ['public abstract void foo();'], 0, False),
    )
    def test_match(self, lines, index, expected_result):
        self.assertEqual(self.filter.match(lines, index), expected_result)

    @parameterized.named_parameters(
        (
                'simple_interface',
                [
                    'interface Foo {',
                    '    void bar();',
                    '}'
                ],
                0,
                (0, 2)
        ),
        (
                'interface_with_decorators',
                [
                    '@Deprecated',
                    '@Suppress("unused")',
                    'interface Foo {',
                    '}'
                ],
                2, # Index of 'interface Foo {'
                (0, 3)
        ),
        (
                'abstract_class_with_nested_braces',
                [
                    'abstract class Foo {',
                    '    void bar() {',
                    '        if (true) { }',
                    '    }',
                    '}'
                ],
                0,
                (0, 4)
        ),
        (
                'single_line_interface',
                ['interface Foo { void bar(); }'],
                0,
                (0, 0)
        ),
        (
                'unterminated_block',
                [
                    'interface Foo {',
                    '    void bar();'
                ],
                0,
                (0, 1) # Returns start_index, len(lines)-1
        ),
        (
                'decorators_stop_at_non_decorator',
                [
                    '// Some comment',
                    '@Annotation',
                    'abstract class Foo {',
                    '}'
                ],
                2,
                (1, 3) # Should include @Annotation but stop before comment
        )
    )
    def test_get_filter_range(self, lines, index, expected_range):
        self.assertEqual(self.filter.get_filter_range(lines, index), expected_range)

class AbstractMethodFilterTest(parameterized.TestCase):
    def setUp(self):
        self.filter = exclude_filter.AbstractMethodFilter()

    @parameterized.named_parameters(
        ('standard_abstract_method', ['public abstract void foo();'], 0, True),
        ('kotlin_abstract_method', ['abstract fun foo(): Int'], 0, True),
        ('indented_abstract_method', ['    abstract void foo();'], 0, True),
        ('abstract_class_definition', ['public abstract class Foo {'], 0, False),
        ('abstract_class_no_modifier', ['abstract class Foo {'], 0, False),
        ('concrete_method', ['public void foo() {}'], 0, False),
    )
    def test_match(self, lines, index, expected_result):
        self.assertEqual(self.filter.match(lines, index), expected_result)

    @parameterized.named_parameters(
        (
                'single_line_method',
                ['public abstract void foo(int a);'],
                0,
                (0, 0)
        ),
        (
                'multi_line_args',
                [
                    'public abstract void foo(',  # 0: ( level 1
                    '    String a,',             # 1
                    '    int b',                 # 2
                    ');'                         # 3: ) level 0
                ],
                0,
                (0, 3)
        ),
        (
                'nested_parentheses_annotations',
                [
                    'abstract void foo(',                  # 0
                    '    @Size(min=1, max=10) List<T> l',  # 1: ( opens and ) closes inside line
                    ');'                                   # 2
                ],
                0,
                (0, 2)
        ),
        (
                'unterminated_parentheses',
                [
                    'abstract void foo(',
                    '    int x'
                    # Missing closing paren
                ],
                0,
                (0, 1) # Returns to end of file
        ),
    )
    def test_get_filter_range(self, lines, index, expected_range):
        self.assertEqual(self.filter.get_filter_range(lines, index), expected_range)

if __name__ == '__main__':
    absltest.main()
