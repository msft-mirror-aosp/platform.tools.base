from abc import ABC, abstractmethod
import re

class ExcludeFilter(ABC):
    @abstractmethod
    def match(self, lines: list[str], index: int):
        return

    @abstractmethod
    def get_filter_range(self, lines: list[str], index: int) -> (int, int):
        pass

class CommentFilter(ExcludeFilter):
    def __init__(self):
        self.in_multi_line_comment = False

    def match(self, lines: list[str], index: int) -> bool:
        if re.match(r"\s*//.*", lines[index]) or "/*" in lines[index]:
            return True
        return False

    def get_filter_range(self, lines: list[str], index: int) -> (int, int):
        # Single line comment
        if re.match(r"\s*//.*", lines[index]):
            return index, index

        # Edge Case
        if "/*" not in lines[index]:
            return None
        # Process Multiline-comment
        start_index = index
        while index < len(lines) and "*/" not in lines[index]:
            index += 1

        return start_index, index

class AbstractInterfaceFilter(ExcludeFilter):
    """Filters out abstract class and interface declarations."""
    def match(self, lines: list[str], index: int) -> bool:
        line = lines[index].strip()
        return bool(re.search(r'\babstract\b.*\bclass\b', line) or re.search(r'\binterface\b', line))

    def __get_decorator_start(self, lines, index) -> int:
        # Start at the line above definition
        index -= 1
        while index>=0:
            if re.match(r"\s*@.*", lines[index]):
                index -= 1
            else:
                return index+1
        return 0

    def get_filter_range(self, lines: list[str], index: int) -> (int, int):
        # Ignore all the decorators before the class definition
        start_index = self.__get_decorator_start(lines, index)

        # Use stack to trace start and end of class definition
        brace_level = 0
        found_opening_brace = False
        while index < len(lines):
            line = lines[index]
            for char in line:
                if char == '{':
                    brace_level += 1
                    found_opening_brace = True
                elif char == '}':
                    brace_level -= 1

            if found_opening_brace and brace_level == 0:
                # Found the closing brace for the abstract class/interface
                return start_index, index
            index += 1

        return start_index, len(lines)-1 # Reached end of file without closing brace

class AbstractMethodFilter(ExcludeFilter):

    def match(self, lines: list[str], index: int):
        # Match abstract methods which are not classes
        return bool(re.match(r'^(?!.*\bclass\b).*\babstract\b.*$', lines[index]))

    def get_filter_range(self, lines: list[str], index: int) -> (int, int):
        start_index = index
        # Use stack to trace start and end of class definition
        brace_level = 0
        found_opening_brace = False
        while index < len(lines):
            line = lines[index]
            for char in line:
                if char == '(':
                    brace_level += 1
                    found_opening_brace = True
                elif char == ')':
                    brace_level -= 1

            if found_opening_brace and brace_level == 0:
                # Found the closing brace for the abstract class/interface
                return start_index, index
            index += 1

        return start_index, len(lines)-1 # Reached end of file without closing brace
