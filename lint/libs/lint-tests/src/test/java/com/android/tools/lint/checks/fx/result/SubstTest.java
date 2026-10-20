/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.tools.lint.checks.fx.result;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.Lists;

import kotlinx.collections.immutable.PersistentMap;

import org.junit.Test;

import java.util.List;

public class SubstTest {

    @Test
    public void substitutionKeepsInsertionOrder() {
        List<Type.Sym.Name> keys =
                List.of(
                        new Type.Sym.Param("x", new Scope.Generated(0)),
                        new Type.Sym.Param("y", new Scope.Generated(0)),
                        new Type.Sym.Param("z", new Scope.Generated(0)));
        for (List<Type.Sym.Name> order : List.of(keys, Lists.reverse(keys))) {
            PersistentMap<Type.Sym.Name, Type> subst = EnvKt.getEmptySubst();
            for (Type.Sym.Name key : order) {
                subst = subst.put(key, Type.Companion.getNone());
            }
            assertThat(subst.keySet()).containsExactlyElementsIn(order).inOrder();
        }
    }
}
