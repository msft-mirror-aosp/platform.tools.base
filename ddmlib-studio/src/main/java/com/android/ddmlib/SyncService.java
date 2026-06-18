/*
 * Copyright (C) 2007 The Android Open Source Project
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
package com.android.ddmlib;

import java.util.Date;

/** Class supporting Sync Services. */
public class SyncService {

    public static class FileStat {

        private final int myMode;

        private final int mySize;

        private final Date myLastModified;

        public FileStat(int mode, int size, long lastModifiedSecs) {
            myMode = mode;
            mySize = size;
            myLastModified = new Date(lastModifiedSecs * 1000);
        }

        public int getMode() {
            return myMode;
        }

        public int getSize() {
            return mySize;
        }

        public Date getLastModified() {
            return myLastModified;
        }
    }
}
