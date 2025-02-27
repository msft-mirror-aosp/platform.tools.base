/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.build.gradle.integration.packaging

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.GradleTestProject.Companion.builder
import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.output.AarSubject
import com.android.build.gradle.integration.common.output.AbstractZipSubject
import com.android.build.gradle.integration.common.truth.AbstractAndroidSubject
import com.android.build.gradle.integration.common.truth.TruthHelper
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.utils.FileUtils
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.function.Consumer
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * test for packaging of asset files.
 */
class NativeSoPackagingFromJarTest {
    @get:Rule
    val project: GradleTestProject = builder()
        .fromTestProject("projectWithModules")
        .create()

    private lateinit var appProject: GradleTestProject
    private lateinit var libProject: GradleTestProject


    @Before
    fun setUp() {
        appProject = project.getSubproject("app")

        // rewrite settings.gradle to remove un-needed modules
        project.setIncludedProjects("app", "library")

        // setup dependencies.
        TestFileUtils.appendToFile(
            appProject.buildFile,
            "\ndependencies {\n" + "  api files(\"libs/foo.jar\")\n" + "}\n"
        )

        libProject = project.getSubproject("library")

        TestFileUtils.appendToFile(
            libProject.buildFile,
            "\ndependencies {\n" + "  api files(\"libs/bar.jar\")\n" + "}\n"
        )

        val appDir = appProject.projectDir
        createJarWithNativeLib(File(appDir, "libs"), "foo.jar", false)

        val libDir = libProject.projectDir
        createJarWithNativeLib(File(libDir, "libs"), "bar.jar", true)
    }

    @Test
    fun testAppPackaging() {
        project.executor().run("app:assembleDebug")
        checkApk(appProject, "libhello.so", "hello")
    }

    @Test
    fun testLibraryPackaging() {
        project.executor().run("library:assembleDebug")
        checkAar(libProject, "libhello.so", "hello")

        // also check that the bar.jar is also present as a local jar with a the class
        // but not the so file.
        // first extract bar.jar from the apk.
        libProject.assertAar(AarSelector.DEBUG) {
            contains("libs/bar.jar")
            secondaryJars().classes().containsExactly(COM_FOO_FOO)
            secondaryJars().resources().isEmpty()
        }
    }

    companion object {

        private const val LIB_X86_LIBHELLO_SO = "lib/x86/libhello.so"
        private const val COM_FOO_FOO = "com/foo/Foo"
        private val COM_FOO_FOO_CLASS: String = COM_FOO_FOO + ".class"
    }

    private fun createJarWithNativeLib(
        folder: File, fileName: String, includeClass: Boolean
    ) {
        FileUtils.mkdirs(folder)
        val jarFile = File(folder, fileName)

        FileOutputStream(jarFile).use { fos ->
            JarOutputStream(
                BufferedOutputStream(fos)
            ).use { jarOutputStream ->
                jarOutputStream.putNextEntry(JarEntry(LIB_X86_LIBHELLO_SO))
                jarOutputStream.write("hello".toByteArray())
                jarOutputStream.closeEntry()
                if (includeClass) {
                    jarOutputStream.putNextEntry(JarEntry(COM_FOO_FOO_CLASS))
                    jarOutputStream.write(dummyClassByteCode)
                    jarOutputStream.closeEntry()
                }
            }
        }
    }

    /**
     * check an apk has (or not) the given asset file name.
     *
     * If the content is non-null the file is expected to be there with the same content. If the
     * content is null the file is not expected to be there.
     *
     * @param project the project
     * @param filename the filename
     * @param content the content
     */
    private fun checkApk(
        project: GradleTestProject, filename: String, content: String?
    ) {
        val apk = project.getApk("debug")
        check(TruthHelper.assertThatApk(apk), "lib", filename, content)
        PackagingTests.checkZipAlign(apk.getFile().toFile())
    }

    /**
     * check an aat has (or not) the given asset file name.
     *
     *
     * If the content is non-null the file is expected to be there with the same content. If the
     * content is null the file is not expected to be there.
     *
     * @param project the project
     * @param filename the filename
     * @param content the content
     */
    private fun checkAar(
        project: GradleTestProject,
        filename: String,
        content: String?
    ) {
        project.assertAar(AarSelector.DEBUG) {
            check(this, "jni", filename, content)
        }
    }

    private fun check(
        subject: AbstractAndroidSubject<*, *>,
        folderName: String,
        filename: String,
        content: String?
    ) {
        if (content != null) {
            subject.containsFileWithContent("$folderName/x86/$filename", content)
        } else {
            subject.doesNotContain("$folderName/x86/$filename")
        }
    }

    private fun check(
        subject: AbstractZipSubject<*, *>,
        folderName: String,
        filename: String,
        content: String?
    ) {
        if (content != null) {
            subject.textFile(folderName + "/x86/" + filename).isEqualTo(content)
        } else {
            subject.doesNotContain(folderName + "/x86/" + filename)
        }
    }

    private val dummyClassByteCode: ByteArray?
        /**
         * Creates a class and returns the byte[] with the class
         * @return
         */
        get() {
            val cw = ClassWriter(0)
            var fv: FieldVisitor?
            var mv: MethodVisitor?
            var av0: AnnotationVisitor?

            cw.visit(
                Opcodes.V1_6,
                Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER,
                "com/foo/Foo",
                null,
                "java/lang/Object",
                null
            )

            mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
            mv.visitCode()
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(1, 1)
            mv.visitEnd()

            mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "aaa", "()V", null, null)
            mv.visitCode()
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "test/Aaa", "bbb", "()V",
                false
            )
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(1, 1)
            mv.visitEnd()

            mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "bbb", "()V", null, null)
            mv.visitCode()
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(0, 1)
            mv.visitEnd()

            mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "ccc", "()V", null, null)
            mv.visitCode()
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(0, 1)
            mv.visitEnd()

            cw.visitEnd()

            return cw.toByteArray()
        }
}
