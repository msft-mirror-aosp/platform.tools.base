/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.tools.preview.multipreview

import java.io.File
import java.util.zip.ZipFile
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

/**
 * Resolves if a given annotation class has @Preview annotation in its ancestors.
 *
 * Note that this class is *not* thread-safe.
 */
class MultipreviewAnnotationResolver(
  private val previewAnnotationClassDescriptor: String,
  private val previewAnnotationContainerClassDescriptor: String,
  private val classResolver: (String) -> ClassReader?,
) {

  private val resolvedAnnotationClasses: MutableMap<String, Set<BaseAnnotationRepresentation>> = mutableMapOf()
  private val currentlyResolvingAnnotationClasses: MutableSet<String> = mutableSetOf()

  /**
   * Finds all preview annotations from ancestors of a given [annotationClassDescriptor].
   *
   * If there are duplicated preview annotations with identical annotation parameters, they are unified into one entry.
   *
   * [onFindFinished] callback is invoked with a set of all found preview annotation parameters.
   *
   * When it returns non-null value, you must return it from your [ClassVisitor.visitMethod] for preview annotations annotated to your
   * method to be processed properly.
   */
  fun findAllPreviewAnnotations(
    annotationClassDescriptor: String,
    onFindFinished: (Set<BaseAnnotationRepresentation>) -> Unit,
  ): AnnotationVisitor? {
    return when (annotationClassDescriptor) {
      previewAnnotationClassDescriptor -> {
        object : AnnotationVisitor(Opcodes.ASM9) {
          val parameters = mutableMapOf<String, Any>()

          override fun visit(name: String, value: Any) {
            parameters[name] = value
          }

          override fun visitEnd() {
            onFindFinished(setOf(BaseAnnotationRepresentation(parameters)))
          }
        }
      }
      previewAnnotationContainerClassDescriptor -> {
        object : AnnotationVisitor(Opcodes.ASM9) {
          val previewAnnotations = mutableSetOf<BaseAnnotationRepresentation>()

          override fun visitArray(name: String): AnnotationVisitor {
            return object : AnnotationVisitor(Opcodes.ASM9) {
              override fun visitAnnotation(name: String?, descriptor: String): AnnotationVisitor? {
                return findAllPreviewAnnotations(descriptor, previewAnnotations::addAll)
              }
            }
          }

          override fun visitEnd() {
            onFindFinished(previewAnnotations)
          }
        }
      }
      else -> {
        onFindFinished(findAllPreviewAnnotationsFromUserDefinedAnnotationClass(annotationClassDescriptor))
        null
      }
    }
  }

  private fun findAllPreviewAnnotationsFromUserDefinedAnnotationClass(
    annotationClassDescriptor: String
  ): Set<BaseAnnotationRepresentation> {
    if (currentlyResolvingAnnotationClasses.contains(annotationClassDescriptor)) {
      // This annotation class has a cyclic dependency.
      return setOf()
    }

    // Note that computeIfAbsent() throws ConcurrentModificationException due to recursion.
    return resolvedAnnotationClasses[annotationClassDescriptor]
      ?: run {
        val resolvedValue = resolve(annotationClassDescriptor)
        resolvedAnnotationClasses[annotationClassDescriptor] = resolvedValue
        resolvedValue
      }
  }

  private fun resolve(annotationClassDescriptor: String): Set<BaseAnnotationRepresentation> {
    if (!annotationClassDescriptor.startsWith("L") || !annotationClassDescriptor.endsWith(";")) {
      return setOf()
    }

    try {
      currentlyResolvingAnnotationClasses += annotationClassDescriptor

      classResolver(annotationClassDescriptor)?.let {
        return resolveAnnotationClass(it)
      }

      return setOf()
    } finally {
      currentlyResolvingAnnotationClasses -= annotationClassDescriptor
    }
  }

  private fun resolveAnnotationClass(annotationClass: ClassReader): Set<BaseAnnotationRepresentation> {
    val previewAnnotations = mutableSetOf<BaseAnnotationRepresentation>()

    annotationClass.accept(
      object : ClassVisitor(Opcodes.ASM9) {
        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
          findAllPreviewAnnotations(descriptor, previewAnnotations::addAll)
      },
      ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
    )

    return previewAnnotations
  }
}
