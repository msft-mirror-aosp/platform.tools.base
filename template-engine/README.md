# Android Template Engine

This document outlines the template engine used for the `android-cli` command line tool.

---

# The template engine architecture

The template engine is responsible for generating an arbitrary set of files and directories into a destination
directory, from a template definition (see next section) and an optional set of argument values.
Arguments can be provided by the end-user or programmatically (if the engine is used as a stand-alone library).

## The directory structure of a template definition

Each template is defined in a `template-directory` that contains both files and directories of the template, in addition to
a custom `template\template-definition.json` file.

By default, the template engine copies all files and directories from the `template-directory` to the destination directory unchanged. The behavior
can be customized by transformations expression defined in the `template-definition.json` file.

```text
<template-directory>                  <-- The directory where a template is defined
  .template/template-definition.json  <-- Engine configuration (not copied)
  [file-1]                            <-- Copied unchanged (unless transformed)
  [file-2]                            <-- Copied unchanged (unless transformed)
     (...)
  [directory-1]                       <-- Directory and contents copied
     [file-3]
     [file-4]
     (...)
  [directory-2]
  (...)
```

## The `.template/template-definition.json` file

The `template-definition.json` file describes the template metadata (such a "name", "tags", "description"), expected arguments, and transformations to apply to the template files.

Example:

```json
{
  // 1. metadata section
  "name": "Empty Activity Compose",
  "short-name": "empty-activity-compose",
  "tags": ["compose", "activity"],
  // 2. arguments block
  "arguments": [
    {
      "id": "package-name",                     // <-- "package-name" is an argument with a default value
      "default-value": "com.example.app"
    },
    {
      "id": "app-name",                         // <-- "app-name" is another argument
      "default-value": "My App"
    }
  ],
  // 3. transformations block
  "transformations": [
    {
      "string-replace": {                       // <-- replace all "com.example.example" strings in all "*.kt" files
        "selector": { "glob": "*.kt" },         // <-- with the value of the "package-name" argument
        "from": "com.example.template",
        "to": "${package-name}"
      }
    },
    {
      "rename-file": {                          // <-- change the destination directory of *.kt files
        "selector": { "glob": "app/src/main/java/com/example/template/*.kt" },
        "source-path": "com/example/template",
        "target-path": "${package-name.replace('.', '/')}"
      }
    }
  ]
}
```

#### The "arguments" block

The `arguments` block defines the inputs needed for generating the template.
- `id`: The unique identifier for the argument, usable in string interpolations (e.g., `${package-name}`).
- `default-value`: The default value for the argument. It supports string interpolations using other argument IDs (e.g., `${package-name.replace('a', 'b')}`).

#### The "transformations" block

The `transformations` block defines operations applied to the source files as they are processed.
A transformation acts on one or more files identified by a `selector`, typically using a `glob` pattern.

Current available transformations are:

- **`string-replace`**: Replaces text inside the content of matching files.
  - `selector`: Which files this transformation applies to (e.g., `{"glob": "*.kt"}`).
  - `from`: The static string to find and replace.
  - `to`: The string replacement. It supports string interpolation expressions.

- **`rename-file`**: Renames files and/or directories when generating the output.
  - `selector`: Which files this transformation applies to (e.g., `{"glob": "app/src/main/java/com/example/template/*"}`).
  - `source-path`: The substring in the source relative path to replace.
  - `target-path`: The new path. It supports string interpolation expressions.

### String Interpolation

The template engine supports a custom string interpolation syntax used to evaluate dynamic values during template processing.
These expressions can be used in most places where a string value is used (e.g. `default-value` definitions for arguments, `to` fields for `string-replace`, etc.)

String interpolations are denoted by `${expression}`. The text surrounding these expressions is treated as a constant literal.

For example, `foo ${name} bar` is the concatenation of the `foo ` literal, followed by the value of the `name` argument, followed by the ` bar` literal.

#### Features

- **Constant Literals**: You can mix literal text with interpolation expressions.
  - Example: `package ${package-name};` will evaluate to `package com.example.app;`.
- **Variable Replacement**: You can reference the value of any argument defined in the `arguments` block using its `id`.
  - Example: `${package-name}` will evaluate to the value of the `package-name` argument.
- **Method Calls**: You can chain method calls on variable references. The currently supported methods are:
  - `.replace('old', 'new')`: Replaces all occurrences of the literal string `'old'` with `'new'`. Both arguments can be string literals (enclosed in single quotes) or other variable references.
  - `.toLower()`: Converts the entire string to lowercase.

#### Examples

Assuming the following argument values are provided:
- `package-name` = `com.example.app`
- `app-name` = `My Cool App`

| Expression | Evaluated Value |
|---|---|
| `Hello ${app-name}!` | `Hello My Cool App!` |
| `${package-name}` | `com.example.app` |
| `${package-name.replace('.', '/')}` | `com/example/app` |
| `${app-name.toLower()}` | `my cool app` |
| `${app-name.replace('Cool', 'Awesome')}` | `My Awesome App` |

---

## Invocation from android-cli

Example
```
android create empty-activity-app --destination-path=~/MyApp --name="My Test App" --minSdk="30"
```
where

| Argument               | Optiona/Mandatory | Description                                                        |
|------------------------|-------------------|--------------------------------------------------------------------|
| `empty-activity-app`   | Mandatory         | The name of the template to use                                    |
| `~/MyApp`              | Mandatory         | The destination directory where to store the generated application |
| `--name="My Test App"` | Optional          | Overrides  the name of the application from the default            |
| `--minSdk="30"`        | Optional          | Overrides the minimum SDK version                                  |

