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

package com.android.build.gradle.integration.common.output

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Annotation
import com.android.tools.smali.dexlib2.writer.builder.DexBuilder
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.google.common.collect.ImmutableSet
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TestDataCreator {
    companion object {

        val FAKE_CLASS: ByteArray =
            byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())

        fun writeAar(zipFile: Path) {
            writeAar(zipFile, false)
        }

        fun writeAarWithLibJar(zipFile: Path) {
            writeAar(zipFile, true)
        }

        private fun writeAar(zipFile: Path, libJar: Boolean) {
            ZipOutputStream(
                Files.newOutputStream(
                    zipFile,
                    StandardOpenOption.TRUNCATE_EXISTING
                )
            ).use { zipOutputStream ->
                zipOutputStream.putNextEntry(ZipEntry("classes.jar"))
                zipOutputStream.write(fakeJar("com.example.SomeClass"))
                zipOutputStream.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zipOutputStream.putNextEntry(ZipEntry("R.txt"))
                zipOutputStream.putNextEntry(ZipEntry("res/values/values.xml"))
                zipOutputStream.write("values file content".toByteArray())
                if (libJar) {
                    zipOutputStream.putNextEntry(ZipEntry("libs/some_lib.jar"))
                    zipOutputStream.write(fakeJar("com.example.somelib.Lib"))
                }
            }
        }

        fun fakeJar(className: String): ByteArray {
            val baos = ByteArrayOutputStream()
            ZipOutputStream(baos).use { inner ->
                inner.putNextEntry(ZipEntry(className.replace('.', '/') + ".class"))
                inner.write(FAKE_CLASS)
            }
            return baos.toByteArray()
        }

        fun writeApkWithMonoDex(apk: Path) {
            writeApk(apk, false)
        }

        fun writeApkWithMultiDex(apk: Path) {
            writeApk(apk, true)
        }

        private fun writeApk(apk: Path, multi: Boolean) {
            ZipOutputStream(
                Files.newOutputStream(
                    apk,
                    StandardOpenOption.TRUNCATE_EXISTING
                )
            ).use { zipOutputStream ->
                zipOutputStream.putNextEntry(ZipEntry("classes.dex"))
                zipOutputStream.write(
                    dexFile(if (multi) "com.example.SomeMultiClass" else "com.example.SomeClass")
                )
                zipOutputStream.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zipOutputStream.putNextEntry(ZipEntry("java_resource"))
                zipOutputStream.putNextEntry(ZipEntry("res/values/values.xml"))
                if (multi) {
                    zipOutputStream.putNextEntry(ZipEntry("classes2.dex"))
                    zipOutputStream.write(dexFile("com.example.somelib.Lib"))
                    zipOutputStream.putNextEntry(ZipEntry("classes3.dex"))
                    zipOutputStream.write(dexFile("com.example.somelib2.Lib2"))
                }
                zipOutputStream.write("values file content".toByteArray())
            }
        }

        fun dexFile(className: String): ByteArray? {
            val dexBuilder = DexBuilder(Opcodes.getDefault())

            dexBuilder.internClassDef(
                "L" + className.replace('.', '/') + ";",
                0x01,
                "Ljava/lang/Object;",
                null,
                null,
                ImmutableSet.of<Annotation?>(),
                null,
                null
            )

            val dexData = MemoryDataStore()
            dexBuilder.writeTo(dexData)

            return dexData.getData()
        }
    }
}
