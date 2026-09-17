# Android Studio ProfilerTester App

ProfilerTester is an Android app used by the dev and QA teams to test Android Studio's CPU, Memory, Network, and Energy profilers. The app is intentionally designed to easily reproduce various scenarios like high CPU usage, memory leaks, network requests, and wakelock usage.

## Setup & Build Instructions

1. **Open the project**: Open Android Studio and select `File > Open`, then navigate to the project directory (e.g. `tools/base/profiler/integration-tests/ProfilerTester`).
2. **Install CMake**: Ensure CMake is installed, as this app includes native C++ code for CPU profiling tests.
   * Go to `Settings > Appearance & Behavior > System Settings > Android SDK`.
   * Click on the **SDK Tools** tab and check **CMake**.
   * Click **OK** to install.
3. **Sync Gradle**: Ensure you have the latest Android SDK installed. Android Studio will automatically sync the Gradle project.
   * *If Gradle sync fails*: verify the `distributionUrl` in `gradle/wrapper/gradle-wrapper.properties` is valid, or update it from https://services.gradle.org/distributions.
4. **Build and Install**: Click the **Run** button (the green "play" icon) in the Android Studio toolbar to build and install the app on your emulator or physical device.

## Supported Profiling Categories & Scenarios
This app provides a comprehensive suite of scenarios to trigger and test Android Studio's Profiler tools. The available task categories include:

* **CPU**: Features tests like infinite C++ loops, thread sleep, standard math operations, ART Java method tracing tests, array sorts, and heavy UI thread workloads to monitor CPU usage.
* **Memory**: Tests raw memory allocation including chunk allocation/deallocation, String Builder stress tests, array tracking, JNI object allocation, and triggering garbage collection.
* **Memory Leaks**: Specifically designed to reproduce Android context leaks (Activity leaks, Fragment leaks, View leaks, ViewModel leaks, Service leaks) for testing LeakCanary and Memory Profiler.
* **Network**: Issues single or repeated HTTP requests using standard `HttpURLConnection` and OkHttp (v2 and v3) clients to observe Network Profiler payloads, headers, and traffic.
* **Background Tasks**: Generates various wakelocks (short, long, custom duration, intentionally leaked), system alarms (1-shot and repeating), and scheduled JobServices to test Energy/Background profilers.
* **Event & UI**: Replicates user interactions like typing words into text fields and switching between activities.
* **Bluetooth, Camera, Location, Audio, Screen Brightness, Feedback**: Several sensor/hardware tasks designed to simulate real-world app state changes for holistic power usage and system-level event tracking.

## How to use the app (Triggering Scenarios)

Once the app is running, you will see a main screen (`MainActivityFragment`) with dropdown spinners to select test categories and specific tasks.

1. **Attach Profilers**: Profile the app in Android Studio (using the **Profile** button instead of Run) and attach it to the `android.com.java.profilertester` process.
2. **Select a Category**: Use the primary dropdown to select the category (e.g. Memory Leaks, Background Tasks, CPU Tasks, Network).
3. **Select a Task**: Use the secondary dropdown to select the specific task to run (e.g. "Activity Leak", "Long Wake Lock (65s)", "C++ infinite loop").
4. **Execute**: Click the **Run** (Play) button in the app UI to trigger the task. Observe the results in the Android Studio Profilers.
5. **Navigate**: You can use the "forward" or "rewind" buttons in the app UI to quickly cycle through different tasks and categories.

## Exporting zip for the QA team

Whenever you update the app code or config, you should provide a `.zip` file so the QA team (who may not have a full Git checkout) can easily use it.

1. In Android Studio, use **File > Export to Zip File** to generate a zip of the project files.
2. Open the generated zip file and **delete** the `app/build/`, `.gradle/`, and `app/.cxx/` folders to keep the size small.
3. Copy the clean zip file to `tools/adt/idea/manual-tests/res/perf-tools/` and overwrite the existing `ProfilerTester.zip`.
4. Upload your CL with the updated source code and the zip file to Gerrit.
