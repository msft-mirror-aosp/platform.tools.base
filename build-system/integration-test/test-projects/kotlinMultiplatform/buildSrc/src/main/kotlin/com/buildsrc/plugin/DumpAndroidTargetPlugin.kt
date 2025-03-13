package com.buildsrc.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.plugin.ide.IdeMultiplatformImport

class DumpAndroidTargetPlugin: Plugin<Project> {

    override fun apply(target: Project) {
        target.tasks.register("dumpAndroidTarget", DumpAndroidTargetTask::class.java) { task ->
            task.description = "Debugging task that will snapshots android target"
            task.notCompatibleWithConfigurationCache(
                "DumpAndroidTargetTask is just for testing purposes"
            )
        }

        target.tasks.register("dumpSourceSetDependencies", DumpSourceSetDependenciesTask::class.java) { task ->
            task.description = "Debugging task that will snapshot IDE source set dependencies"
            task.notCompatibleWithConfigurationCache(
                "DumpSourceSetDependenciesTask is just for testing purposes"
            )
        }
    }
}
