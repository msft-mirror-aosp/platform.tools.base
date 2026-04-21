# WARNING: this produces a very large trace file, use wisely.
TraceOutputDirectory: /tmp/
Trace-Agent: false

# It's recommended to start tracing when the Application enters its main entry point
Start: com.intellij.idea.Main::main
Flush: com.intellij.openapi.application.impl.ApplicationImpl::exit

# Tracing *everything* via wildcards requires limiting the scope to avoid infinite recursion
# and out-of-memory errors from instrumenting standard Java libraries (java.lang.*, etc.) so
# this mostly focuses on Android Studio packages.
Trace: com.android.*
Trace: com.google.*
