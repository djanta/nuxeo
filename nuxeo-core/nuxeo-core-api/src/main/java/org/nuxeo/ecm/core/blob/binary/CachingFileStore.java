/*
 * (C) Copyright 2019 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Florent Guillaume
 */
package org.nuxeo.ecm.core.blob.binary;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.function.Supplier;

import org.nuxeo.common.file.FileCache;
import org.nuxeo.common.file.LRUFileCache;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.trackers.files.FileEventTracker;

/**
 * File store wrapper that caches files locally because fetching them may be expensive.
 *
 * @since 11.1
 */
public class CachingFileStore extends AbstractFileStore {

    protected final FileStore fileStore;

    protected final Path cacheDir;

    protected final FileCache fileCache;

    protected final PathStrategyFlat tmpPathStrategy;

    protected final FileStore tmpStore;

    public CachingFileStore(FileStore fileStore, Path cacheDirPath, long maxSize, long maxCount, long minAge) {
        this.fileStore = fileStore;
        cacheDir = cacheDirPath;
        fileCache = new LRUFileCache(cacheDir.toFile(), maxSize, maxCount, minAge);
        // be sure FileTracker won't steal our files !
        FileEventTracker.registerProtectedPath(cacheDir.toAbsolutePath().toString());
        tmpPathStrategy = new PathStrategyFlat(cacheDir);
        tmpStore = new LocalFileStore(tmpPathStrategy); // "FileStore" view of the LRUFileCache tmp dir
    }

    /**
     * Returns the original {@link FileStore} that this {@link CachingFileStore} wraps.
     */
    public FileStore unwrap() {
        return fileStore;
    }

    @Override
    public void writeBlob(BlobWriteContext blobWriteContext, WriteObserver writeObserver, Supplier<String> keyComputer)
            throws IOException {
        // write the blob to a temporary file
        String tmpKey = randomString();
        tmpStore.writeBlob(blobWriteContext, writeObserver, () -> tmpKey);
        // get the final key
        String key = keyComputer.get();

        // check if it's in the cache already
        File cachedFile = fileCache.getFile(key);
        if (cachedFile != null) {
            // file already in cache
            if (Framework.isTestModeSet()) {
                Framework.getProperties().setProperty("cachedBinary", key);
            }
            // delete tmp file, not needed anymore
            tmpStore.deleteFile(tmpKey);
        } else {
            // register the file in the file cache
            File tmp = tmpPathStrategy.getPathForKey(tmpKey).toFile();
            cachedFile = fileCache.putFile(key, tmp);
            // we now have a file for this blob
            blobWriteContext.setFile(cachedFile.toPath());
            // send the file to storage
            fileStore.writeBlob(blobWriteContext, null, () -> key);
        }
    }

    @Override
    public boolean copyFile(String key, FileStore sourceStore, String sourceKey, boolean move, boolean atomic)
            throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Path getLocalFile(String key) {
        File cachedFile = fileCache.getFile(key);
        return cachedFile == null ? null : cachedFile.toPath();
    }

    @Override
    public InputStream getStream(String key) throws IOException {
        File cachedFile = fileCache.getFile(key);
        if (cachedFile == null) {
            // fetch file from storage into the cache
            // go through a tmp file for atomicity
            String tmpKey = randomString();
            boolean found = tmpStore.copyFile(tmpKey, fileStore, key, false, false);
            if (!found) {
                throw new IOException(key);
            }
            File tmp = tmpPathStrategy.getPathForKey(tmpKey).toFile();
            cachedFile = fileCache.putFile(key, tmp);
        }
        return new FileInputStream(cachedFile);
    }

    @Override
    public boolean readFileTo(String key, Path dest) throws IOException {
        // callers should go through #getStream
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteFile(String key) {
        tmpStore.deleteFile(key); // TODO add API to fileCache to do this cleanly
        fileStore.deleteFile(key);
    }

}
