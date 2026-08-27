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
    "tools/adt/idea/ij-debugger-tests",
    "tools/vendor/google/ml/evals",
    "tools/vendor/google/ml/aiplugin/ij-platform/src/main/kotlin/com/google/tools/intellij/aiplugin/feedback",
    "tools/base/jdwp-packet",
    "tools/base/jdwp-tracer",
    "tools/base/multipreview-asm/src/perfTest",
    "tools/base/dynamic-layout-inspector/agent/appinspection/fake-android",
    "tools/adt/idea/designer/testFramework",
    "tools/adt/idea/wear-dwf/gen",
]
