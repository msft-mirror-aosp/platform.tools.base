from typing import Tuple

def get_valid_indexes(start: int, stop: int, exclude_list: list[Tuple[int, int]]):
    # Sort intervals
    exclude_list = sorted(exclude_list)
    # Pointer to interval in exclude_list
    exclude_list_index = 0
    curr = start
    while curr < stop:
        while exclude_list_index < len(exclude_list) and curr > exclude_list[exclude_list_index][1]:
            exclude_list_index += 1

        if exclude_list_index >= len(exclude_list):
            yield curr
            curr += 1
            continue

        if exclude_list[exclude_list_index][0] <= curr <= exclude_list[exclude_list_index][1]:
            curr += 1
            continue

        yield curr
        curr += 1

def apply_exclude_filters(lines: list[str], exclude_filters) -> list[Tuple[int, int]]:
    all_excluded_indexes = []
    for exclude_filter in exclude_filters:
        total_lines = len(lines)
        excluded_indexes_from_filter = []

        for index in get_valid_indexes(0, total_lines, all_excluded_indexes):
            if exclude_filter.match(lines, index):
                excluded_range = exclude_filter.get_filter_range(lines, index)
                excluded_indexes_from_filter.append(excluded_range)

        all_excluded_indexes.extend(excluded_indexes_from_filter)
        all_excluded_indexes.sort()

    return all_excluded_indexes
