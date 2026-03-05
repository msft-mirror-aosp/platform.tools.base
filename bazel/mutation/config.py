import re

PATHS = [
    "tools/adt/idea",
    "tools/base",
    "tools/vendor/google",
    "tools/vendor/google3"
]

IGNORE_PATHS = [
    re.compile(r".*build-system/.*"),
    re.compile(r"(?i).*/test/.*"),
    re.compile(r"Test\.(kt|java)$"),
    re.compile(r".*/(testSrc|testData)/.*"),
]
