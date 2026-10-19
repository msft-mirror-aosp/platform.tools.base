package com.android.build.gradle.internal

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.impl.ArtifactsImpl
import com.android.build.api.artifact.impl.InternalScopedArtifacts
import com.android.build.api.variant.Packaging
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.gradle.internal.component.ComponentCreationConfig
import com.android.build.gradle.internal.tasks.ProcessJavaResTask
import com.android.build.gradle.internal.tasks.creationconfig.ProcessJavaResCreationConfig
import com.android.build.gradle.internal.tasks.factory.GlobalTaskCreationConfig
import com.android.build.gradle.internal.tasks.factory.TaskCreationAction
import com.android.build.gradle.internal.tasks.factory.TaskFactory
import com.android.build.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.android.builder.core.ComponentTypeImpl
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.Project
import org.gradle.api.tasks.TaskContainer
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.lenient
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness

class TaskManagerTest {

  @get:Rule var mockitoJUnitRule: MockitoRule = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS)

  private val project = ProjectBuilder.builder().build()

  private val globalTaskCreationConfig: GlobalTaskCreationConfig = mock()

  class TestTaskManager(project: Project, globalConfig: GlobalTaskCreationConfig) : TaskManager(project, globalConfig) {

    override val javaResMergingScopes: Set<InternalScopedArtifacts.InternalScope>
      get() = setOf()

    fun testCreateProcessJavaResTask(creationConfig: ComponentCreationConfig, packaging: Packaging) {
      createProcessJavaResTask(creationConfig, packaging)
    }
  }

  @Test
  fun testEmptyAllScope() {
    val taskManager = TestTaskManager(project, globalTaskCreationConfig)
    val artifacts = ArtifactsImpl(project, "test")
    taskManager.initializeAllScope(artifacts)
    assertThat(artifacts.forScope(ScopedArtifacts.Scope.ALL).getFinalArtifacts(ScopedArtifact.CLASSES).files).isEmpty()
  }

  @Test
  fun testAllScopeFromProject() {
    val taskManager = TestTaskManager(project, globalTaskCreationConfig)
    val artifacts = ArtifactsImpl(project, "test")
    artifacts.forScope(ScopedArtifacts.Scope.PROJECT).setInitialContent(ScopedArtifact.CLASSES, project.files("/project/classes"))
    artifacts
      .forScope(InternalScopedArtifacts.InternalScope.SUB_PROJECTS)
      .setInitialContent(ScopedArtifact.CLASSES, project.files("/sub-project/classes"))
    artifacts
      .forScope(InternalScopedArtifacts.InternalScope.EXTERNAL_LIBS)
      .setInitialContent(ScopedArtifact.CLASSES, project.files("/external-libs/classes"))
    artifacts.forScope(ScopedArtifacts.Scope.PROJECT).setInitialContent(ScopedArtifact.JAVA_RES, project.files("/project/res"))

    artifacts
      .forScope(InternalScopedArtifacts.InternalScope.SUB_PROJECTS)
      .setInitialContent(ScopedArtifact.JAVA_RES, project.files("/sub-project/res"))
    artifacts
      .forScope(InternalScopedArtifacts.InternalScope.EXTERNAL_LIBS)
      .setInitialContent(ScopedArtifact.JAVA_RES, project.files("/external-libs/res"))

    taskManager.initializeAllScope(artifacts)

    assertThat(artifacts.forScope(ScopedArtifacts.Scope.ALL).getFinalArtifacts(ScopedArtifact.CLASSES).files.map(File::getAbsolutePath))
      .containsExactly(toProjectFile("/project/classes"), toProjectFile("/sub-project/classes"), toProjectFile("/external-libs/classes"))

    assertThat(artifacts.forScope(ScopedArtifacts.Scope.ALL).getFinalArtifacts(ScopedArtifact.JAVA_RES).files.map(File::getAbsolutePath))
      .containsExactly(toProjectFile("/project/res"), toProjectFile("/sub-project/res"), toProjectFile("/external-libs/res"))
  }

  @Test
  fun testCreateProcessJavaResTaskPackagingLoopRegression() {
    val mockProject = mock<Project>()
    val mockTaskContainer = mock<TaskContainer>()
    whenever(mockProject.tasks).thenReturn(mockTaskContainer)

    val mockGlobalConfig = mock<GlobalTaskCreationConfig>()
    val taskManager = TestTaskManager(mockProject, mockGlobalConfig)

    // Inject a mocked TaskFactory via reflection to isolate the test from Gradle task creation pipelines.
    val taskFactoryField = TaskManager::class.java.getDeclaredField("taskFactory")
    taskFactoryField.isAccessible = true
    val mockTaskFactory = mock<TaskFactory>()
    taskFactoryField.set(taskManager, mockTaskFactory)

    val actionCaptor = argumentCaptor<TaskCreationAction<ProcessJavaResTask>>()
    whenever(mockTaskFactory.register(actionCaptor.capture())).thenReturn(mock())

    val componentCreationConfig =
      mock<ComponentCreationConfig>(
        defaultAnswer = RETURNS_DEEP_STUBS,
        lenient = true,
      )

    val realArtifacts = ArtifactsImpl(project, "debug")
    lenient().`when`(componentCreationConfig.artifacts).thenReturn(realArtifacts)

    lenient().`when`(componentCreationConfig.name).thenReturn("debug")
    lenient().`when`(componentCreationConfig.componentType).thenReturn(ComponentTypeImpl.BASE_APK)

    val mockPackaging = mock<Packaging>()

    taskManager.testCreateProcessJavaResTask(componentCreationConfig, mockPackaging)

    assertThat(actionCaptor.allValues).hasSize(1)
    val creationAction = actionCaptor.firstValue as ProcessJavaResTask.CreationAction

    val configField = VariantTaskCreationAction::class.java.getDeclaredField("creationConfig")
    configField.isAccessible = true
    val taskConfig = configField.get(creationAction) as ProcessJavaResCreationConfig

    assertThat(taskConfig.packaging).isSameInstanceAs(mockPackaging)
  }

  private fun toProjectFile(path: String) = project.layout.projectDirectory.file(path).asFile.absolutePath
}
