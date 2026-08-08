# ART Tooling

A reusable JVMTI agent that lets a host tool inspect a running, debuggable Android app —
finding live objects and hooking methods — with the inspection logic written in Java and run
inside the target process. Tools share one native agent instead of each bundling their own.

## How it works

A host tool ships three artifacts to the device and attaches the agent to a process:

1. The native agent (`libarttooling_agent.so`) attaches via JVMTI.
2. It loads the ART Tooling library (`libarttooling.jar`) into the **bootstrap** class loader
   and hands control to `AgentLoader`.
3. `AgentLoader` loads the tool's own dex through an **app-parented** class loader, then
   instantiates and starts the tool's `Agent`.
4. From inside the process, the tool calls `ArtTooling` to find instances and
   register method hooks.

## Why two class loaders

Android class loaders form a chain: each one delegates to its parent before loading a class
itself, and the **bootstrap** loader sits at the root of every chain. The consequence that
matters here: a class loaded by the bootstrap loader is reachable from everywhere, while a
class loaded further down is reachable only from that loader and its descendants.

- The **library** (`ArtTooling`) goes on the bootstrap loader. We hook a method by rewriting
  its bytecode to call `ArtTooling.onEntry`/`onExit`. The methods we hook can belong to any
  class — including framework classes — so `ArtTooling` has to be reachable from all of them,
  and only a class on the bootstrap loader is reachable everywhere.
- The tool's **agent dex** goes on a loader just below the app's own class loader. A tool
  normally needs the app's classes — to read its objects or call its methods — and those are
  reachable from the app's loader and its descendants, but not from the bootstrap loader.

## Capabilities

- `findInstances(Class<T>)` — every live instance of a type on the heap (JVMTI heap tagging).
- `registerEntryHook` / `registerExitHook` — observe a method's arguments on entry, and observe
  or replace its return value on exit (in-memory dex rewriting).

## Attach contract

The agent is attached with a four-field options string:

`library_dex;agent_dex;agent_class;agent_options`

- `library_dex` — path to `libarttooling.jar`.
- `agent_dex` — path to the tool's agent dex.
- `agent_class` — binary name of the tool's `Agent` implementation inside `agent_dex`.
- `agent_options` — opaque; passed to `Agent#onAttach` unchanged (may contain semicolons).

## Source layout

`native/` — the JVMTI agent, built per-ABI as `libarttooling_agent.so`:

- `agent.cc` — `Agent_OnAttach`: parses options and hands off to `AgentLoader`. The first attach
  through a loaded copy of the library also creates the JVMTI environment, puts the library dex
  on the bootstrap loader, and creates the engine. Later attaches through that copy reuse them.
  A process can hold several copies, since each newly installed file loads as a new library.
- `agent_options.{h,cc}` — parses the options string.
- `jvmti_art_tooling.{h,cc}` — heap-walking instance discovery and class instrumentation.
- `array_params_entry_hook.{h,cc}`, `art_tooling_transform.h` — slicer bytecode rewriting that
  routes method entry/exit to the Java dispatchers.
- `art_tooling_jni.{h,cc}` — JNI bindings for `ArtTooling`'s native methods.
- `art_tooling_constants.h` — shared class names, descriptors, and log tag.

`java/` — the in-process API, packaged as `libarttooling.jar`:

- `ArtTooling` — the API (`findInstances`, hook registration, `clear`, and the dispatch entry points).
- `HookRegistry` — hook storage, dispatch, and cleanup.
- `Agent` — the interface a tool implements.
- `AgentLoader` — loads the tool's dex app-parented and starts its `Agent`.

The bytecode-instrumentation and heap-walking code is adapted from app-inspection's agent
(`app_inspection_service.cc`, `array_params_entry_hook.cc`, `app_inspection_transform.h`).
