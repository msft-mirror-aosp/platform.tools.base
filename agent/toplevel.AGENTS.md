# Agent Instructions

This is the source tree root for Android Studio, an IntelliJ-based IDE.

There are multiple git roots in this project. When asked to do something related to git, you should ask which git root to use.

## Follow instructions

DO NOT DEVIATE FROM THE INSTRUCTIONS. THIS IS CRITICAL.
If the user's request is ambiguous about which files to modify or which approach to take, use the `ask_user` tool with a `multi_choice` or `single_choice` prompt to clarify before writing any code.

## Language
- This project uses both Kotlin and Java but Kotlin is preferred.
- **ALWAYS** use Kotlin for new files, even if the code is extracted from a Java class.
- Use idiomatic Kotlin as much as possible, especially for null handling. For example:
   - Use the elvis (`?:`) operator when possible instead of if/else. Especially for quick exit from a method.
     - *BAD:* `if (foo != null) return foo else return bar`
     - *GOOD:* `return foo ?: bar`
   - Use Kotlin stdlib functions like `let`, `run`, `apply`, `with`, `use`.
   - Use the safe call operator (`?.`) when possible instead of if/else.
   - Extract return or assignments from `if`, `when` and other expressions where possible.
     - *BAD:* `var x: Int; if (cond) x = 1 else x = 2`
     - *GOOD:* `val x = if (cond) 1 else 2`

## Verification of code changes

When editing files, you **MUST** attempt to correct all the errors returned by the file modification tools (e.g., `write_file`, `replace_file_content`, `multi_replace_file_content`, or `analyze_file`). Before your work is done, review all these errors and make sure they are corrected.

## Workflows

### Validating changes with tests
When making changes, you **MUST** verify that the changes are accurate
1. Search for existing tests
2. Create tests if they don't exist yet.
3. validate that all scenarios are tested.
4. Run the tests after making change. **ALWAYS** use `jps_run_test`. **DO NOT** use bazel or gradle.
5. If adding clever or non-obvious code, you **MUST** leave a clarifying comment.

### Code Review
When the user explicitly asks to review code (e.g., "Critique my current git committed change"):
1. First, use the `run_shell_command` tool to run `git diff HEAD~1` (or the relevant git command) to view the changes if they are not already provided in the context.
2. Critique the changes based on the following criteria:
   - **Bugs or mistakes** (this is the most important thing; look carefully)
   - Missing test cases or unhandled scenarios
   - Redundant or duplicated code
   - Unnecessary comments
   - Architecture issues and API contracts
   - Thread safety and data flow
   - Idiomatic Kotlin usage
3. Provide thorough comments but **DO NOT** make changes!
4. When referencing snippet in files, always include the line number.

### git commit message
When writing git commit messages, you **MUST** limit line length to the standard 72 characters.
