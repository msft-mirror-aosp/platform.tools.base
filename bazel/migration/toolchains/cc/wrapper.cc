// Copyright 2017 The Bazel Authors. All rights reserved.
// Modifications Copyright 2024 - The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
// wrapper.cc: Pass args to the wrapped clang.
//
// This is a wrapper that passes the provided args to clang, with the following
// strings substituted:
// "{BAZEL_EXECUTION_ROOT}" -> $CWD
//
// An environment variable "WRAPPER_WRAP_BINARY" must be set when running the
// wrapper, pointing to the compiler binary to run.

#include <spawn.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

#include <fstream>
#include <functional>
#include <iostream>
#include <memory>
#include <string>
#include <vector>

using namespace std;

static const char *kBinaryPathVarName = "WRAPPER_WRAP_BINARY";
static const char *kDebugFlagVarName = "__WRAPPER_LOG_ONLY";

namespace {

// Unescape and unquote an argument read from a line of a response file.
const string Unescape(const string &arg) {
  string result;
  auto length = arg.size();
  for (size_t i = 0; i < length; i++) {
    auto ch = arg[i];

    // If it's a backslash, consume it and append the character that follows.
    if (ch == '\\' && i + 1 < length) {
      i++;
      result.push_back(arg[i]);
      continue;
    }

    // If it's a quote, process everything up to the matching quote, unescaping
    // backslashed characters as needed.
    if (ch == '"' || ch == '\'') {
      auto quote = ch;
      i++;
      while (i != length && arg[i] != quote) {
        if (arg[i] == '\\' && i + 1 < length) {
          i++;
        }
        result.push_back(arg[i]);
        i++;
      }
      if (i == length) {
        break;
      }
      continue;
    }

    // It's a regular character.
    result.push_back(ch);
  }

  return result;
}

// Converts an array of string arguments to char *arguments.
// Note that the lifetime of the char* arguments in the returned array
// are controlled by the lifetime of the strings in args.
vector<const char *> ConvertToCArgs(const vector<string> &args) {
  vector<const char *> c_args;
  for (int i = 0; i < args.size(); i++) {
    c_args.push_back(args[i].c_str());
  }
  c_args.push_back(nullptr);
  return c_args;
}

// Spawns a subprocess for given arguments args. The first argument is used
// for the executable path.
int RunSubProcess(const vector<string> &args) {
  auto exec_argv = ConvertToCArgs(args);

  pid_t pid;
  int status = posix_spawn(&pid, args[0].c_str(), nullptr, nullptr,
                           const_cast<char **>(exec_argv.data()), nullptr);
  if (status != 0) {
    cerr << "Error forking process '" << args[0] << "': " << strerror(status)
         << "\n";
    return status;
  }
  int wait_status;
  do {
    wait_status = waitpid(pid, &status, 0);
  } while ((wait_status == -1) && (errno == EINTR));
  if (wait_status < 0) {
    cerr << "Error waiting on child process '" << args[0]
         << "': " << strerror(errno) << "\n";
    return wait_status;
  }
  if (WIFEXITED(status) && WEXITSTATUS(status) != 0) {
    cerr << "Error in child process '" << args[0]
         << "': " << WEXITSTATUS(status) << "\n";
    return status;
  } else if (WIFSIGNALED(status)) {
    cerr << "Error in child process '" << args[0] << "': " << WTERMSIG(status)
         << "\n";
    return status;
  }

  return EXIT_SUCCESS;
}

// Finds and replaces all instances of oldsub with newsub, in-place on str.
void FindAndReplace(const string &oldsub, const string &newsub, string *str) {
  int start = 0;
  while ((start = str->find(oldsub, start)) != string::npos) {
    str->replace(start, oldsub.length(), newsub);
    start += newsub.length();
  }
}

// Returns the DEVELOPER_DIR environment variable in the current process
// environment. Aborts if this variable is unset.
string GetMandatoryEnvVar(const string &var_name) {
  char *env_value = getenv(var_name.c_str());
  if (env_value == nullptr) {
    cerr << "Error: " << var_name << " not set.\n";
    exit(EXIT_FAILURE);
  }
  return env_value;
}

// An RAII temporary file.
class TempFile {
 public:
  // Create a new temporary file using the given path template string (the same
  // form used by `mkstemp`). The file will automatically be deleted when the
  // object goes out of scope.
  static unique_ptr<TempFile> Create(const string &path_template) {
    const char *tmpDir = getenv("TMPDIR");
    if (!tmpDir) {
      tmpDir = "/tmp";
    }
    size_t size = strlen(tmpDir) + path_template.size() + 2;
    unique_ptr<char[]> path(new char[size]);
    snprintf(path.get(), size, "%s/%s", tmpDir, path_template.c_str());

    if (mkstemp(path.get()) == -1) {
      cerr << "Failed to create temporary file '" << path.get()
           << "': " << strerror(errno) << "\n";
      return nullptr;
    }
    return unique_ptr<TempFile>(new TempFile(path.get()));
  }

  // Explicitly make TempFile non-copyable and movable.
  TempFile(const TempFile &) = delete;
  TempFile &operator=(const TempFile &) = delete;
  TempFile(TempFile &&) = default;
  TempFile &operator=(TempFile &&) = default;

  ~TempFile() { remove(path_.c_str()); }

  // Gets the path to the temporary file.
  string GetPath() const { return path_; }

 private:
  explicit TempFile(const string &path) : path_(path) {}

  string path_;
};

static unique_ptr<TempFile> WriteResponseFile(const vector<string> &args) {
  auto response_file = TempFile::Create("wrapper_params.XXXXXX");
  ofstream response_file_stream(response_file->GetPath());

  for (const auto &arg : args) {
    // When Clang writes out a response file to communicate from driver to
    // frontend, they just quote every argument to be safe; we duplicate that
    // instead of trying to be "smarter" and only quoting when necessary.
    response_file_stream << '"';
    for (auto ch : arg) {
      if (ch == '"' || ch == '\\') {
        response_file_stream << '\\';
      }
      response_file_stream << ch;
    }
    response_file_stream << "\"\n";
  }

  response_file_stream.close();
  return response_file;
}

void ProcessArgument(const string arg, const string cwd,
                     function<void(const string &)> consumer);

bool ProcessResponseFile(const string arg, const string cwd,
                         function<void(const string &)> consumer) {
  auto path = arg.substr(1);
  ifstream original_file(path);
  // Ignore non-file args such as '@loader_path/...'
  if (!original_file.good()) {
    return false;
  }

  string arg_from_file;
  while (getline(original_file, arg_from_file)) {
    // Arguments in response files might be quoted/escaped, so we need to
    // unescape them ourselves.
    string unescaped = Unescape(arg_from_file);
    // Argument can have spaces inside. We need to split to multiple args.
    char *p = strtok(unescaped.data(), " ");
    while (p != nullptr) {
      ProcessArgument(p, cwd, consumer);
      p = strtok(nullptr, " ");
    }
  }

  return true;
}

string GetCurrentDirectory() {
  // Passing null,0 causes getcwd to allocate the buffer of the correct size.
  char *buffer = getcwd(nullptr, 0);
  string cwd(buffer);
  free(buffer);
  return cwd;
}

void ProcessArgument(const string arg, const string cwd,
                     function<void(const string &)> consumer) {
  auto new_arg = arg;
  if (arg[0] == '@') {
    if (ProcessResponseFile(arg, cwd, consumer)) return;
  }

  FindAndReplace("{BAZEL_EXECUTION_ROOT}", cwd, &new_arg);
  consumer(new_arg);
}

}  // namespace

int main(int argc, char *argv[]) {
  string tool_path = GetMandatoryEnvVar(kBinaryPathVarName);
  unsetenv(kBinaryPathVarName);
  char *debug = getenv(kDebugFlagVarName);
  unsetenv(kDebugFlagVarName);

  const string cwd = GetCurrentDirectory();
  vector<string> processed_args = {};

  auto consumer = [&](const string &arg) { processed_args.push_back(arg); };
  for (int i = 1; i < argc; i++) {
    ProcessArgument(argv[i], cwd, consumer);
  }

  auto response_file = WriteResponseFile(processed_args);

  // Special mode that only prints the command. Used for testing.
  if (debug) {
    cout << tool_path << '\n';
    ifstream f(response_file->GetPath());
    if (f.is_open()) cout << f.rdbuf();
    return EXIT_SUCCESS;
  }

  vector<string> invocation_args = {tool_path, "@" + response_file->GetPath()};
  return RunSubProcess(invocation_args);
}