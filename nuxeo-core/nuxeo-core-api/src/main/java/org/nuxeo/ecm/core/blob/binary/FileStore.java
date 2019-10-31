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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Interface for basic access to storage of a Blob (read/write/delete).
 * <p>
 * A blob is identified by a key, and holds a stream of bytes. It may have a few associated metadata (filename, content
 * type).
 *
 * @since 11.1
 */
public interface FileStore {

    /**
     * Writes a blob.
     *
     * @param blobContext the blob context
     * @param recordMode whether record mode should be used
     * @param digestAlgorithm the digest algorithm (used when not in record mode)
     * @return the blob key
     */
    String writeBlob(BlobContext blobContext, boolean recordMode, String digestAlgorithm) throws IOException;

    /**
     * Writes a blob.
     *
     * @param blobWriteContext the context of the blob write, including the blob
     * @param writeObserver a callback used during writes
     * @param keyComputer the supplier for the key that the blob is associated with
     */
    void writeBlob(BlobWriteContext blobWriteContext, WriteObserver writeObserver, Supplier<String> keyComputer) throws IOException;

    /**
     * Writes a file based on a key, as a copy/move from a source in another file store.
     * <p>
     * If the copy/move is requested to be atomic, then the destination file is created atomically. In case of atomic
     * move, in some stores the destination will be created atomically but the source will only be deleted afterwards.
     *
     * @param key the key
     * @param sourceStore the source store
     * @param sourceKey the source key
     * @param move {@code true} for a move (the source should be deleted afterwards), {@code false} for a regular copy
     * @param atomic {@code true} if the copy/move must be atomic, or {@code false} if not
     * @return {@code true} if the file was found in the source store, {@code false} if it was not found
     */
    boolean copyFile(String key, FileStore sourceStore, String sourceKey, boolean move, boolean atomic)
            throws IOException;

    /**
     * Gets an already-existing file containing the blob for the given key, if present.
     *
     * @param key the blob key
     * @return the file containing the blob, or {@code null} if it is not available locally
     */
    Path getLocalFile(String key);

    /**
     * Gets the stream of the blob for the given key.
     *
     * @param key the blob key
     * @return the blob stream, or {@code null} if the blob cannot be found
     */
    InputStream getStream(String key) throws IOException;

    /**
     * Fetches the file for a blob based on its key.
     *
     * @param key the blob key
     * @param dest the file to use to store the fetched data
     * @return {@code true} if the file was fetched, {@code false} if the file was not found
     * TODO put in sub-interface dealing with file-based stores
     */
    boolean readFileTo(String key, Path dest) throws IOException;

    /**
     * Deletes a blob.
     *
     * @param blobContext the blob context
     */
    void deleteBlob(BlobContext blobContext);

    /**
     * Deletes a file based on a key. No error occurs if the file does not exist.
     * <p>
     * This method does not throw {@link IOException}, but may log an error message.
     *
     * @param key the file key
     */
    void deleteFile(String key);

}
