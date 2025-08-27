/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.lint.uast

import com.android.tools.lint.LintCliClient
import com.android.tools.lint.ManualProject
import com.android.tools.lint.UastEnvironment
import com.android.tools.lint.checks.infrastructure.KlibTestFile
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.detector.api.Project
import com.android.tools.lint.useFirUast
import com.google.common.truth.Truth.assertThat
import com.intellij.mock.MockProject
import com.intellij.openapi.vfs.impl.jar.CoreJarFileSystem
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeVisitor
import com.intellij.psi.search.GlobalSearchScope
import kotlin.io.path.Path
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationList
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.psiUtil.isExtensionDeclaration
import org.junit.AfterClass
import org.junit.Assume
import org.junit.AssumptionViolatedException
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.same

class ProvideDeclarationFromKlibTest {

  companion object {
    const val TEST_DATA_PACKAGE = "com.testdata"

    @get:ClassRule @get:JvmStatic val temporaryFolder: TemporaryFolder = TemporaryFolder()

    lateinit var mockProject: MockProject

    @BeforeClass
    @JvmStatic
    fun beforeClass() {
      try {
        Assume.assumeTrue("App is running in K1 and this test will be skipped", useFirUast())
      } catch (e: AssumptionViolatedException) {
        println(e.message) // Otherwise the message will not print
        throw e
      }
      val klibFile =
        KlibTestFile(
          "/testdata.klib",
          """
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
      """,
          -0x14f6e507,
          // For reference only, not used
          kotlin(
            """
          package com.testdata
          annotation class LibAnnotation()
          fun libGlobalMethod() = Unit
          val String.globalProperty
            get() = LibClass()
          const val LIB_CONST = ""
          class LibClass() {
            val libAttr = 0
            fun libMethod(arg: Int) = Int
            fun libMethod(arg: Long) = Long
            fun <T> libGenericMethod(arg: T): Array<T>? = null
            operator fun unaryPlus() = Unit
          }
      """
              .trimIndent()
          ),
        )
      val tmp = temporaryFolder.root.canonicalFile
      klibFile.createFile(tmp)

      val config = UastEnvironment.Configuration.create(useFirUast = true)
      val lintProject =
        ManualProject(
          client = LintCliClient("TestClient"),
          dir = tmp,
          name = "test-project",
          library = true,
          android = false,
          partialResultsDir = null,
          testFiles = emptyList(),
          generatedFiles = emptyList(),
          kotlinPlatforms = "Native [general]",
        )
      lintProject.klibs[Path(tmp.path, klibFile.targetPath).toFile()] =
        Project.DependencyKind.Regular
      config.addModules(
        listOf(
          UastEnvironment.Module(
            lintProject,
            jdkHome = null,
            includeTests = false,
            includeTestFixtureSources = false,
            isUnitTest = false,
          )
        )
      )
      val env = UastEnvironment.create(config)

      mockProject = env.ideaProject
    }

    @AfterClass
    @JvmStatic
    fun afterClass() {
      UastEnvironment.disposeApplicationEnvironment()
    }
  }

  @Test
  fun getClassesFromKlib() {
    val projectScope = GlobalSearchScope.allScope(mockProject)

    val factory = KotlinStaticPsiDeclarationProviderFactory(mockProject, CoreJarFileSystem())
    val provider = factory.createPsiDeclarationProvider(projectScope)

    val classId = ClassId(FqName(TEST_DATA_PACKAGE), Name.guessByFirstCharacter("LibClass"))
    val psiClasses = provider.getClassesByClassId(classId)

    assertThat(psiClasses).hasSize(1)
    val targetClass = psiClasses.single()
    assertThat(targetClass.name).isEqualTo("LibClass")
    assertThat(targetClass.constructors).hasLength(1)
    val constructor = targetClass.constructors[0]
    assertThat(constructor.typeParameters).isEmpty()
    assertThat(constructor.parameterList.isEmpty).isTrue()
    assertThat(constructor.returnType).isNull()

    // Known issue: no getter populated, not sure if this should be a bug
    assertThat(targetClass.methods).hasLength(5)
    assertThat(targetClass.methods[0]).isSameAs(constructor)

    val libMethods = targetClass.methods.filter { it.name == "libMethod" }[0]
    val libReloadedMethods = targetClass.methods.filter { it.name == "libMethod" }[1]
    assertThat(libMethods.typeParameters).isEmpty()
    assertThat(libMethods.parameterList.parametersCount).isEqualTo(1)
    val methodParameter = libMethods.parameterList.getParameter(0)
    assertThat(methodParameter?.name).isEqualTo("arg")
    assertThat(methodParameter?.type?.canonicalText).isEqualTo("kotlin.Int")
    // This is the correct type (as in .knm), and when resolving call to this method
    // with AA the KaType for return is kotlin.Int.Companion as well.
    assertThat(libMethods.returnType?.canonicalText).isEqualTo("kotlin.Int.Companion")
    assertThat(libReloadedMethods.parameterList.getParameter(0)?.type?.canonicalText)
      .isEqualTo("kotlin.Long")
    assertThat(libReloadedMethods.returnType?.canonicalText).isEqualTo("kotlin.Long.Companion")

    val libGenericMethod = targetClass.methods.single { it.name == "libGenericMethod" }
    assertThat(libGenericMethod.typeParameters).hasLength(1)
    assertThat(libGenericMethod.typeParameters[0].name).isEqualTo("T")
    assertThat(libGenericMethod.parameterList.parametersCount).isEqualTo(1)
    assertThat(libGenericMethod.parameterList.getParameter(0)?.name).isEqualTo("arg")
    assertThat(libGenericMethod.parameterList.getParameter(0)?.type?.canonicalText).isEqualTo("T")
    assertThat(libGenericMethod.returnType?.canonicalText).isEqualTo("kotlin.Array<T>?")

    val libOperator = targetClass.methods.single { it.name == "unaryPlus" }
    assertThat(libOperator.name).isEqualTo("unaryPlus")
    assertThat(libOperator.typeParameters).isEmpty()
    assertThat(libOperator.parameterList.isEmpty).isTrue()
    // This is the correct type as in K/N Unit is not mapped to voidType.
    assertThat(libOperator.returnType?.canonicalText).isEqualTo("kotlin.Unit")

    assertThat(targetClass.fields).hasLength(1)
    val libField = targetClass.fields.single { it.name == "libAttr" }
    // Known issue: all fields have void type
    assertThat(libField.type.canonicalText).isEqualTo("void")
  }

  @OptIn(KaExperimentalApi::class) // For mocking only
  @Test
  fun getOverloadedMethod() {
    val kaModule = mock<KaModule> { on { project } doReturn mockProject }
    val emptyAnnotationList = mock<KaAnnotationList> {}
    val paramType = mock<KaType> {}
    val valueParam = mock<KaValueParameterSymbol> { on { returnType } doReturn paramType }
    val returnType = mock<KaType> {}
    val symbol =
      mock<KaNamedFunctionSymbol> {
        on { callableId } doReturn
          CallableId(
            FqName(TEST_DATA_PACKAGE),
            FqName("LibClass"),
            Name.guessByFirstCharacter("libMethod"),
          )
        on { annotations } doReturn emptyAnnotationList
        on { this.returnType } doReturn returnType
        on { valueParameters } doReturn listOf(valueParam)
        on { typeParameters } doReturn emptyList()
      }
    val session =
      mock<KaSession> {
        on { symbol.containingModule } doReturn kaModule
        // Mocking KaType.asPsiType
        // (https://github.com/JetBrains/kotlin/blob/master/analysis/analysis-api/src/org/jetbrains/kotlin/analysis/api/components/KaJavaInteroperabilityComponent.kt#L55)
        // on the two mock KaTypes
        on {
          same(paramType)
            .asPsiType(
              any(),
              anyBoolean(),
              any(),
              anyBoolean(),
              isNull(),
              anyBoolean(),
              anyBoolean(),
            )
        } doReturn MockPsiType("kotlin.Int")
        on {
          same(returnType)
            .asPsiType(
              any(),
              anyBoolean(),
              any(),
              anyBoolean(),
              isNull(),
              anyBoolean(),
              anyBoolean(),
            )
        } doReturn MockPsiType("kotlin.Int.Companion")
      }

    with(DecompiledPsiDeclarationProvider) {
      val result = session.provide(symbol)

      assertThat(result).isInstanceOf(PsiMethod::class.java)
      val methodsResult = result as PsiMethod
      assertThat(methodsResult.name).isEqualTo("libMethod")
      assertThat(methodsResult.parameterList.getParameter(0)?.type?.canonicalText)
        .isEqualTo("kotlin.Int")
      assertThat(methodsResult.returnType?.canonicalText).isEqualTo("kotlin.Int.Companion")
    }
  }

  @Test
  fun getGlobalSymbols() {
    val projectScope = GlobalSearchScope.allScope(mockProject)

    val factory = KotlinStaticPsiDeclarationProviderFactory(mockProject, CoreJarFileSystem())
    val provider = factory.createPsiDeclarationProvider(projectScope)

    val kaFunction =
      mock<KaNamedFunctionSymbol> {
        on { callableId } doReturn
          CallableId(FqName(TEST_DATA_PACKAGE), Name.guessByFirstCharacter("libGlobalMethod"))
      }

    val globalMethod = provider.getFunctions(kaFunction)
    assertThat(globalMethod).hasSize(1)
    assertThat(globalMethod.single().name).isEqualTo("libGlobalMethod")

    val kaExtensionProperty =
      mock<KaPropertySymbol> {
        on { callableId } doReturn
          CallableId(FqName(TEST_DATA_PACKAGE), Name.guessByFirstCharacter("globalProperty"))
        on { name } doReturn Name.guessByFirstCharacter("globalProperty")
      }

    val globalExtensionProperty = provider.getProperties(kaExtensionProperty)
    assertThat(globalExtensionProperty).hasSize(1)
    assertThat(globalExtensionProperty.single().name).isEqualTo("globalProperty")
    assertThat(globalExtensionProperty.single().isExtensionDeclaration()).isTrue()

    val kaConstProperty =
      mock<KaPropertySymbol> {
        on { callableId } doReturn
          CallableId(FqName(TEST_DATA_PACKAGE), Name.guessByFirstCharacter("LIB_CONST"))
        on { name } doReturn Name.guessByFirstCharacter("LIB_CONST")
      }

    val globalConstProperty = provider.getProperties(kaConstProperty)
    assertThat(globalConstProperty).hasSize(1)
    assertThat(globalConstProperty.single().name).isEqualTo("LIB_CONST")
  }
}

// This dummy class is required because equals() cannot be mocked
private class MockPsiType(val matchTarget: String) : PsiType(emptyArray()) {
  override fun equals(other: Any?): Boolean {
    val target = other as? PsiClassType ?: return false
    return target.canonicalText == matchTarget
  }

  override fun getPresentableText(): String {
    throw UnsupportedOperationException()
  }

  override fun getCanonicalText(): String {
    throw UnsupportedOperationException()
  }

  override fun isValid(): Boolean {
    throw UnsupportedOperationException()
  }

  override fun equalsToText(text: String): Boolean {
    throw UnsupportedOperationException()
  }

  override fun <A : Any?> accept(visitor: PsiTypeVisitor<A?>): A {
    throw UnsupportedOperationException()
  }

  override fun getResolveScope(): GlobalSearchScope {
    throw UnsupportedOperationException()
  }

  override fun getSuperTypes(): Array<out PsiType?> {
    throw UnsupportedOperationException()
  }
}
