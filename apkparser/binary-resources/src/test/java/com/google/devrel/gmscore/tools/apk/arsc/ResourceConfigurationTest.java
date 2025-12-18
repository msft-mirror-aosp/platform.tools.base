/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.google.devrel.gmscore.tools.apk.arsc;

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;

public class ResourceConfigurationTest {
  @Test
  public void testConfigurationString() {
    {
      byte[] buff = new byte[] {
          0, 0, 0, 28, 0, 0, 0, 0, 'f', 'r', 'C', 'A', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
      };
      assertEquals("fr-rCA", ResourceConfiguration.create(ByteBuffer.wrap(buff)).toString());
    }
    {
      byte[] buff = new byte[] {
          0, 0, 0, 48, 0, 0, 0, 0, 's', 'r', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
          0, 0, 0, 0, 0, 0, 0, 0, 'L', 'a', 't', 'n', 0, 0, 0, 0, 0, 0, 0, 0
      };
      assertEquals("b+sr+Latn", ResourceConfiguration.create(ByteBuffer.wrap(buff)).toString());
    }
    {
      byte[] buff = new byte[] {
          0, 0, 0, 28, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 36, 0, 0
      };
      assertEquals("v36", ResourceConfiguration.create(ByteBuffer.wrap(buff)).toString());
    }
    {
      byte[] buff = new byte[] {
          0, 0, 0, 28, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 36, 0, 1
      };
      assertEquals("v36.1", ResourceConfiguration.create(ByteBuffer.wrap(buff)).toString());
    }
  }
}
