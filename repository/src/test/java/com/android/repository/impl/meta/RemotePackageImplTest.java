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

package com.android.repository.impl.meta;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.repository.Revision;
import com.android.repository.api.RepoManager;
import com.android.repository.impl.manager.RepoManagerImpl;
import com.android.repository.testframework.FakeProgressIndicator;
import com.android.testutils.file.InMemoryFileSystems;

import junit.framework.TestCase;

import java.nio.file.Path;

public class RemotePackageImplTest extends TestCase {

    public void testGetInstallDirValid() {
        Path sdkRoot = InMemoryFileSystems.createInMemoryFileSystemAndFolder("sdk");
        RepoManager mgr = new RepoManagerImpl(sdkRoot);
        FakeProgressIndicator progress = new FakeProgressIndicator();

        FakeRemotePackageImpl p = new FakeRemotePackageImpl("foo;bar");
        Path installDir = p.getInstallDir(mgr, progress);
        assertEquals(sdkRoot.resolve("foo/bar"), installDir);
    }

    public void testGetInstallDirEscaping() {
        Path sdkRoot = InMemoryFileSystems.createInMemoryFileSystemAndFolder("sdk");
        RepoManager mgr = new RepoManagerImpl(sdkRoot);
        FakeProgressIndicator progress = new FakeProgressIndicator();

        FakeRemotePackageImpl p = new FakeRemotePackageImpl("foo/../../bar");
        try {
            p.getInstallDir(mgr, progress);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            Path expectedResult =
                    sdkRoot.toAbsolutePath()
                            .normalize()
                            .getParent()
                            .resolve("bar")
                            .toAbsolutePath()
                            .normalize();
            String expectedMessage =
                    String.format(
                            "Package path 'foo/../../bar' resolves outside the SDK root (%s vs %s)",
                            expectedResult, sdkRoot.toAbsolutePath().normalize());
            assertEquals(expectedMessage, e.getMessage());
        }
    }

    private static class FakeRemotePackageImpl extends RemotePackageImpl {
        private String mPath;

        FakeRemotePackageImpl(String path) {
            mPath = path;
        }

        @NonNull
        @Override
        public String getPath() {
            return mPath;
        }

        @Override
        public void setPath(@NonNull String path) {
            mPath = path;
        }

        @Override
        protected Archives getArchives() {
            return null;
        }

        @Override
        protected void setArchives(@Nullable Archives archives) {}

        @Override
        protected ChannelRef getChannelRef() {
            return null;
        }

        @Override
        protected void setChannelRef(@Nullable ChannelRef cr) {}

        @Override
        public TypeDetails getTypeDetails() {
            return null;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Fake Display Name";
        }

        @NonNull
        @Override
        public RevisionType getRevision() {
            return new FakeRevisionType(new Revision(1));
        }

        @Override
        public CommonFactory createFactory() {
            return null;
        }
    }

    private static class FakeRevisionType extends RevisionType {
        private final Revision mRevision;

        FakeRevisionType(Revision revision) {
            mRevision = revision;
        }

        @Override
        public int getMajor() {
            return mRevision.getMajor();
        }

        @Override
        public Integer getMinor() {
            return mRevision.getMinor();
        }

        @Override
        public Integer getMicro() {
            return mRevision.getMicro();
        }

        @Override
        public Integer getPreview() {
            return mRevision.getPreview();
        }

        @NonNull
        @Override
        public Revision toRevision() {
            return mRevision;
        }
    }
}
