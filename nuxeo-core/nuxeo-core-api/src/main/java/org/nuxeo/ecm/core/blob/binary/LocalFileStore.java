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

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * File storage as files on a local filesystem. The actual storage path chosen for a given key is decided based on a
 * {@link PathStrategy}.
 *
 * @since 11.1
 */
public class LocalFileStore extends AbstractFileStore {

    private static final Log log = LogFactory.getLog(LocalFileStore.class);

    protected final PathStrategy pathStrategy;

    public LocalFileStore(PathStrategy pathStrategy) {
        this.pathStrategy = pathStrategy;
    }

    @Override
    public void writeBlob(BlobWriteContext blobWriteContext, WriteObserver writeObserver, Supplier<String> keyComputer) throws IOException {
        Path tmp = pathStrategy.createTempFile();
        try {
            transfer(blobWriteContext, tmp, writeObserver);
            String key = keyComputer.get(); // can depend on WriteObserver, for example for digests
            Path dest = pathStrategy.getPathForKey(key);
            Files.createDirectories(dest.getParent());
            Files.move(tmp, dest, ATOMIC_MOVE);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException e) {
                log.error(e, e);
            }
        }
    }

    @Override
    public boolean copyFile(String key, FileStore sourceStore, String sourceKey, boolean move, boolean atomic)
            throws IOException {
        if (sourceStore instanceof LocalFileStore) {
            return copyFile(key, ((LocalFileStore) sourceStore), sourceKey, move, atomic);
        } else {
            return copyFileGeneric(key, sourceStore, sourceKey, move, atomic);
        }
    }

    /**
     * Optimized file-to-file copy/move.
     */
    protected boolean copyFile(String key, LocalFileStore sourceStore, String sourceKey, boolean move, boolean atomic)
            throws IOException {
        Path dest = pathStrategy.getPathForKey(key);
        Files.createDirectories(dest.getParent());
        Path source = sourceStore.pathStrategy.getPathForKey(sourceKey);
        if (!Files.exists(source)) { // NOSONAR (squid:S3725)
            return false; // not found
        }
        if (move) {
            if (atomic) {
                PathStrategy.atomicMove(source, dest);
            } else {
                Files.move(source, dest, REPLACE_EXISTING);
            }
        } else {
            if (atomic) {
                Path tmp = pathStrategy.createTempFile();
                try {
                    Files.copy(source, tmp, REPLACE_EXISTING);
                    Files.move(tmp, dest, ATOMIC_MOVE);
                } finally {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (IOException e) {
                        log.error(e, e);
                    }
                }
            } else {
                Files.copy(source, dest, REPLACE_EXISTING);
            }
        }
        return true;
    }

    /**
     * Generic copy/move to a local file.
     */
    protected boolean copyFileGeneric(String key, FileStore sourceStore, String sourceKey, boolean move, boolean atomic)
            throws IOException {
        Path dest = pathStrategy.getPathForKey(key);
        Files.createDirectories(dest.getParent());
        Path tmp = null;
        try {
            Path readTo;
            if (atomic) {
                readTo = tmp = pathStrategy.createTempFile();
            } else {
                readTo = dest;
            }
            Path file = sourceStore.getLocalFile(sourceKey);
            if (file == null) {
                boolean found = sourceStore.readFileTo(sourceKey, readTo);
                if (!found) {
                    return false;
                }
            } else {
                Files.copy(file, readTo, REPLACE_EXISTING);
            }
            if (atomic) {
                Files.move(readTo, dest, ATOMIC_MOVE);
            }
            if (move) {
                sourceStore.deleteFile(sourceKey);
            }
            return true;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    log.error(e, e);
                }
            }
        }
    }

    @Override
    public Path getLocalFile(String key) {
        Path file = pathStrategy.getPathForKey(key);
        return Files.exists(file) ? file : null;
    }

    @Override
    public InputStream getStream(String key) throws IOException {
        Path file = pathStrategy.getPathForKey(key);
        return Files.newInputStream(file);
    }

    @Override
    public boolean readFileTo(String key, Path dest) throws IOException {
        Path file = pathStrategy.getPathForKey(key);
        if (Files.exists(file)) {
            Files.copy(file, dest);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void deleteFile(String key) {
        Path file = pathStrategy.getPathForKey(key);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.error(e, e);
        }
    }

    protected static final int MIN_BUF_SIZE = 8 * 1024; // 8 kB

    protected static final int MAX_BUF_SIZE = 64 * 1024; // 64 kB

    /**
     * Transfers a blob to a file, notifying an observer while doing this.
     *
     * @param blobWriteContext the blob write context, to get the blob stream
     * @param dest the destination file
     * @param writeObserver the write observer
     */
    public static void transfer(BlobWriteContext blobWriteContext, Path dest, WriteObserver writeObserver) throws IOException {
        // no need for BufferedOutputStream as we write a buffer already
        try (OutputStream out = Files.newOutputStream(dest)) {
            transfer(blobWriteContext, out, writeObserver);
        }
    }

    /**
     * Transfers a blob to an output stream, notifying an observer while doing this.
     *
     * @param blobWriteContext the blob write context, to get the blob stream
     * @param out the destination stream
     * @param writeObserver the write observer
     */
    public static void transfer(BlobWriteContext blobWriteContext, OutputStream out, WriteObserver writeObserver) throws IOException {
        try (InputStream in = blobWriteContext.getStream()) {
            int size = in.available();
            if (size == 0) {
                size = MAX_BUF_SIZE;
            } else if (size < MIN_BUF_SIZE) {
                size = MIN_BUF_SIZE;
            } else if (size > MAX_BUF_SIZE) {
                size = MAX_BUF_SIZE;
            }
            byte[] buf = new byte[size];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (writeObserver != null) {
                    writeObserver.write(buf, 0, n);
                }
                out.write(buf, 0, n);
            }
            if (writeObserver != null) {
                writeObserver.flush();
            }
            out.flush();
        }
    }

}
