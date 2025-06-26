/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tools.apk.analyzer.dex;

import com.android.tools.apk.analyzer.internal.SigUtils;
import com.android.tools.apk.analyzer.internal.rewriters.FieldReferenceWithNameRewriter;
import com.android.tools.apk.analyzer.internal.rewriters.MethodReferenceWithNameRewriter;
import com.android.tools.proguard.ProguardMap;
import com.android.tools.smali.baksmali.Adaptors.ClassDefinition;
import com.android.tools.smali.baksmali.Adaptors.MethodDefinition;
import com.android.tools.smali.baksmali.BaksmaliOptions;
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;
import com.android.tools.smali.dexlib2.rewriter.TypeRewriter;
import com.android.tools.smali.dexlib2.util.ReferenceUtil;
import com.android.tools.smali.util.IndentingWriter;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Optional;
import java.util.stream.StreamSupport;

public class DexDisassembler {
    @NotNull private final DexFile dexFile;
    @Nullable private final ProguardMap proguardMap;

    public DexDisassembler(@NotNull DexBackedDexFile dexFile, @Nullable ProguardMap proguardMap) {
        this.dexFile = proguardMap == null ? dexFile : rewriteDexFile(dexFile, proguardMap);
        this.proguardMap = proguardMap;
    }

    @NotNull
    public String disassembleMethod(@NotNull String fqcn, @NotNull String methodDescriptor)
            throws IOException {
        fqcn = PackageTreeCreator.decodeClassName(SigUtils.typeToSignature(fqcn), proguardMap);
        Optional<? extends ClassDef> classDef = getClassDef(fqcn);
        if (!classDef.isPresent()) {
            throw new IllegalStateException("Unable to locate class definition for " + fqcn);
        }

        Optional<? extends Method> method =
                StreamSupport.stream(classDef.get().getMethods().spliterator(), false)
                        .filter(m -> methodDescriptor.equals(ReferenceUtil.getMethodDescriptor(m)))
                        .findFirst();

        if (!method.isPresent()) {
            throw new IllegalStateException(
                    "Unable to locate method definition in class for method " + methodDescriptor);
        }

        return getMethodDexCode(classDef.get(), method.get());
    }

    @NotNull
    public String disassembleMethod(@NotNull String fqcn, @NotNull MethodReference methodRef)
            throws IOException {
        fqcn = PackageTreeCreator.decodeClassName(SigUtils.typeToSignature(fqcn), proguardMap);
        Optional<? extends ClassDef> classDef = getClassDef(fqcn);
        if (!classDef.isPresent()) {
            throw new IllegalStateException("Unable to locate class definition for " + fqcn);
        }
        MethodReference finalMethodRef =
                proguardMap != null
                        ? getRewriter(proguardMap).getMethodReferenceRewriter().rewrite(methodRef)
                        : methodRef;

        Optional<? extends Method> method =
                StreamSupport.stream(classDef.get().getMethods().spliterator(), false)
                        .filter(finalMethodRef::equals)
                        .findFirst();

        if (!method.isPresent()) {
            throw new IllegalStateException(
                    "Unable to locate method definition in class for method " + methodRef);
        }

        return getMethodDexCode(classDef.get(), method.get());
    }

    @NotNull
    private static String getMethodDexCode(ClassDef classDef, Method method) throws IOException {
        BaksmaliOptions options = new BaksmaliOptions();
        ClassDefinition classDefinition = new ClassDefinition(options, classDef);

        StringWriter writer = new StringWriter(1024);

        try (BaksmaliWriter iw = new BaksmaliWriter(new IndentingWriter(writer))) {
            MethodImplementation methodImpl = method.getImplementation();
            if (methodImpl == null) {
                MethodDefinition.writeEmptyMethodTo(iw, method, classDefinition);
            } else {
                MethodDefinition methodDefinition =
                        new MethodDefinition(classDefinition, method, methodImpl);
                methodDefinition.writeTo(iw);
            }
        }

        return writer.toString().replace("\r", "");
    }

    @NotNull
    public String disassembleClass(@NotNull String fqcn) throws IOException {
        fqcn = PackageTreeCreator.decodeClassName(SigUtils.typeToSignature(fqcn), proguardMap);
        Optional<? extends ClassDef> classDef = getClassDef(fqcn);
        if (!classDef.isPresent()) {
            throw new IllegalStateException("Unable to locate class definition for " + fqcn);
        }

        BaksmaliOptions options = new BaksmaliOptions();
        ClassDefinition classDefinition = new ClassDefinition(options, classDef.get());

        StringWriter writer = new StringWriter(1024);
        try (BaksmaliWriter iw = new BaksmaliWriter(new IndentingWriter(writer))) {
            classDefinition.writeTo(iw);
        }
        return writer.toString().replace("\r", "");
    }

    private static DexFile rewriteDexFile(@NotNull DexFile dexFile, @NotNull ProguardMap map) {
        DexRewriter rewriter = getRewriter(map);
        return rewriter.getDexFileRewriter().rewrite(dexFile);
    }

    @NotNull
    private static DexRewriter getRewriter(@NotNull ProguardMap map) {
        return new DexRewriter(
                new RewriterModule() {
                    @NotNull
                    @Override
                    public Rewriter<String> getTypeRewriter(@NotNull Rewriters rewriters) {
                        return new TypeRewriter() {
                            @NotNull
                            @Override
                            public String rewrite(@NotNull String typeName) {
                                return SigUtils.typeToSignature(
                                        PackageTreeCreator.decodeClassName(typeName, map));
                            }
                        };
                    }

                    @NotNull
                    @Override
                    public Rewriter<FieldReference> getFieldReferenceRewriter(
                            @NotNull Rewriters rewriters) {
                        return new FieldReferenceWithNameRewriter(rewriters) {
                            @Override
                            public String rewriteName(FieldReference fieldReference) {
                                return PackageTreeCreator.decodeFieldName(fieldReference, map);
                            }
                        };
                    }

                    @NotNull
                    @Override
                    public Rewriter<MethodReference> getMethodReferenceRewriter(
                            @NotNull Rewriters rewriters) {
                        return new MethodReferenceWithNameRewriter(rewriters) {
                            @Override
                            public String rewriteName(MethodReference methodReference) {
                                return PackageTreeCreator.decodeMethodName(methodReference, map);
                            }
                        };
                    }
                });
    }

    @NotNull
    private Optional<? extends ClassDef> getClassDef(@NotNull String fqcn) {
        return dexFile.getClasses()
                .stream()
                .filter(c -> fqcn.equals(SigUtils.signatureToName(c.getType())))
                .findFirst();
    }
}
