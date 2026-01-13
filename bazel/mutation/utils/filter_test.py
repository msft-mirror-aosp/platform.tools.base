from absl.testing import absltest
from absl.testing import parameterized
from tools.base.bazel.mutation.utils import filter as mutation_filter

class MockFilter:
    """A mock filter to simulate matching and range extraction."""
    def __init__(self, matches):
        # matches: dict mapping line_index -> (start_index, end_index)
        self.matches = matches
        # To verify which indices were actually visited
        self.checked_indices = []

    def match(self, lines, index):
        self.checked_indices.append(index)
        return index in self.matches

    def get_filter_range(self, lines, index):
        return self.matches[index]


class GetValidIndexesTest(parameterized.TestCase):

    @parameterized.named_parameters(
        ('no_exclusions', 0, 5, [], [0, 1, 2, 3, 4]),
        ('single_exclusion', 0, 5, [(2, 3)], [0, 1, 4]),
        ('start_exclusion', 0, 5, [(0, 1)], [2, 3, 4]),
        ('end_exclusion', 0, 5, [(3, 4)], [0, 1, 2]),
        ('unsorted_exclusions', 0, 6, [(4, 5), (1, 2)], [0, 3]),
        # (1, 3) excludes 1, 2, 3. (2, 4) excludes 2, 3, 4. Union is 1, 2, 3, 4.
        ('overlapping_exclusions', 0, 6, [(1, 3), (2, 4)], [0, 5]),
        ('adjacent_exclusions', 0, 5, [(1, 1), (2, 2)], [0, 3, 4]),
        ('exclusion_outside_range', 0, 3, [(5, 6)], [0, 1, 2]),
        ('full_range_excluded', 0, 3, [(0, 5)], []),
    )
    def test_get_valid_indexes(self, start, stop, exclude_list, expected):
        # The function returns a generator, so we convert to list
        result = list(mutation_filter.get_valid_indexes(start, stop, exclude_list))
        self.assertEqual(result, expected)


class ApplyExcludeFiltersTest(absltest.TestCase):

    def test_apply_single_filter(self):
        lines = ["a", "b", "c", "d", "e"] # Indices 0-4
        # Filter matches index 1 and excludes range (1, 2) -> indices 1 and 2
        f1 = MockFilter({1: (1, 2)})

        result = mutation_filter.apply_exclude_filters(lines, [f1])

        # Should return the range excluded by the filter
        self.assertEqual(result, [(1, 2)])
        # Since there were no prior exclusions, the filter should have checked 0, 1, 2, 3, 4
        # Note: It continues checking even after a match because one filter might match multiple times
        self.assertIn(0, f1.checked_indices)
        self.assertIn(1, f1.checked_indices)
        self.assertIn(3, f1.checked_indices)

    def test_apply_multiple_filters_sequential(self):
        lines = ["0", "1", "2", "3", "4"]
        # Filter 1 excludes index 0
        f1 = MockFilter({0: (0, 0)})
        # Filter 2 excludes index 2
        f2 = MockFilter({2: (2, 2)})

        result = mutation_filter.apply_exclude_filters(lines, [f1, f2])

        # Result should be sorted list of all exclusions
        self.assertEqual(result, [(0, 0), (2, 2)])

        # F1 sees all lines
        self.assertIn(0, f1.checked_indices)

        # F2 should NOT see index 0 because F1 excluded it
        self.assertNotIn(0, f2.checked_indices)
        self.assertIn(1, f2.checked_indices)
        self.assertIn(2, f2.checked_indices)

    def test_apply_multiple_filters_shadowing(self):
        lines = ["0", "1", "2", "3", "4"]
        # Filter 1 excludes range (1, 3) -> indices 1, 2, 3
        f1 = MockFilter({1: (1, 3)})

        # Filter 2 is configured to match index 2, but index 2 is inside F1's exclusion
        f2 = MockFilter({2: (2, 2)})

        result = mutation_filter.apply_exclude_filters(lines, [f1, f2])

        self.assertEqual(result, [(1, 3)])

        # F2 should never be asked about index 2 because it was skipped by get_valid_indexes
        self.assertNotIn(2, f2.checked_indices)
        # F2 should still check 0 and 4
        self.assertIn(0, f2.checked_indices)
        self.assertIn(4, f2.checked_indices)

    def test_no_filters(self):
        lines = ["a", "b"]
        result = mutation_filter.apply_exclude_filters(lines, [])
        self.assertEqual(result, [])

if __name__ == '__main__':
    absltest.main()
