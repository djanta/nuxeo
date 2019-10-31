/*
 * (C) Copyright 2006-2018 Nuxeo (http://nuxeo.com/) and others.
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
 *     Florent Guillaume, jcarsique
 */

package org.nuxeo.ecm.core.blob.binary;

import static org.nuxeo.ecm.core.blob.BlobProviderDescriptor.PREVENT_USER_UPDATE;
import static org.nuxeo.ecm.core.blob.BlobProviderDescriptor.RECORD;
import static org.nuxeo.ecm.core.blob.BlobProviderDescriptor.TRANSIENT;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.impl.blob.FileBlob;
import org.nuxeo.ecm.core.blob.BlobInfo;
import org.nuxeo.ecm.core.blob.BlobProvider;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.ecm.core.blob.SimpleManagedBlob;

/**
 * A simple filesystem-based binary manager. It stores the binaries according to their digest (hash), which means that
 * no transactional behavior needs to be implemented.
 * <p>
 * A garbage collection is needed to purge unused binaries.
 * <p>
 * The format of the <em>binaries</em> directory is:
 * <ul>
 * <li><em>data/</em> hierarchy with the actual binaries in subdirectories,</li>
 * <li><em>tmp/</em> temporary storage during creation,</li>
 * <li><em>config.xml</em> a file containing the configuration used.</li>
 * </ul>
 *
 * @author Florent Guillaume
 */
public class DefaultBinaryManager extends LocalBinaryManager implements BlobProvider {

    private static final Log log = LogFactory.getLog(DefaultBinaryManager.class);

    protected FileStore fileStore;

    @Override
    public void initialize(String blobProviderId, Map<String, String> properties) throws IOException {
        super.initialize(blobProviderId, properties);
        PathStrategy permanentPathStrategy = new PathStrategySubDirs(storageDir.toPath(), descriptor.depth);
        FileStore permanentStore = new LocalFileStore(permanentPathStrategy);
        if (isRecordMode()) {
            PathStrategy transientPathStrategy = new PathStrategyFlat(tmpDir.toPath());
            FileStore transientStore = new LocalFileStore(transientPathStrategy);
            fileStore = new TransactionalFileStore(transientStore, permanentStore);
        } else {
            fileStore = permanentStore;
        }
    }

    @Override
    public Binary getBinary(Blob blob) throws IOException {
        if (!(blob instanceof FileBlob) || !((FileBlob) blob).isTemporary()) {
            return super.getBinary(blob); // just open the stream
        }
        String digest = storeAndDigest((FileBlob) blob);
        File file = getFileForDigest(digest, false);
        /*
         * Now we can build the Binary.
         */
        return new Binary(file, digest, blobProviderId);
    }

    /**
     * Stores and digests a temporary FileBlob.
     */
    protected String storeAndDigest(FileBlob blob) throws IOException {
        String digest;
        if (StringUtils.isEmpty(blob.getDigest())) {
            try (InputStream in = blob.getStream()) {
                digest = storeAndDigest(in, NullOutputStream.NULL_OUTPUT_STREAM);
            }
        } else {
            digest = blob.getDigest();
        }
        File digestFile = getFileForDigest(digest, true);
        if (digestFile.exists()) {
            // The file with the proper digest is already there so don't do anything. This is to avoid
            // "Stale NFS File Handle" problems which would occur if we tried to overwrite it anyway.
            // Note that this doesn't try to protect from the case where two identical files are uploaded
            // at the same time.
            // Update date for the GC.
            digestFile.setLastModified(blob.getFile().lastModified());
        } else {
            blob.moveTo(digestFile);
        }
        return digest;
    }

    @Override
    public BinaryManager getBinaryManager() {
        return this;
    }

    @Override
    public Map<String, String> getProperties() {
        return properties;
    }

    @Override
    public boolean isTransient() {
        return Boolean.parseBoolean(properties.get(TRANSIENT));
    }

    @Override
    public boolean supportsUserUpdate() {
        return !Boolean.parseBoolean(properties.get(PREVENT_USER_UPDATE));
    }

    @Override
    public boolean isRecordMode() {
        return Boolean.parseBoolean(properties.get(RECORD));
    }

    @Override
    public String writeBlob(BlobContext blobContext) throws IOException {
        if (fileStore == null) {
            return writeBlobLegacy(blobContext);
        }
        return fileStore.writeBlob(blobContext, isRecordMode(), getDigestAlgorithm());
    }

    // this code used to be called through BinaryBlobProvider
    protected String writeBlobLegacy(BlobContext blobContext) throws IOException {
        return getBinary(blobContext.blob).getDigest();
    }

    @Override
    public String writeBlob(Blob blob) throws IOException {
        if (isRecordMode()) {
            throw new UnsupportedOperationException("Cannot write blob directly without context in record mode");
        }
        return writeBlob(new BlobContext(blob));
    }

    protected String stripBlobKeyPrefix(String key) {
        // strip prefix
        int colon = key.indexOf(':');
        if (colon >= 0 && key.substring(0, colon).equals(blobProviderId)) {
            key = key.substring(colon + 1);
        }
        return key;
    }

    @Override
    public InputStream getStream(ManagedBlob blob) throws IOException {
        String key = stripBlobKeyPrefix(blob.getKey());
        InputStream stream = fileStore.getStream(key);
        if (stream == null) {
            throw new IOException("Missing blob: " + key);
        }
        return stream;
    }

    @Override
    public Blob readBlob(BlobInfo blobInfo) throws IOException {
        if (fileStore == null) {
            return readBlobLegacy(blobInfo);
        }
        return new SimpleManagedBlob(blobProviderId, blobInfo); // calls back to #getStream
    }

    // this code used to be called through BinaryBlobProvider
    protected Blob readBlobLegacy(BlobInfo blobInfo) throws IOException {
        String key = stripBlobKeyPrefix(blobInfo.key);
        Binary binary = getBinary(key);
        if (binary == null) {
            throw new IOException("Unknown binary: " + key);
        }
        long length;
        if (blobInfo.length == null) {
            log.debug("Missing blob length for: " + blobInfo.key);
            // to avoid crashing, get the length from the binary's file (may be costly)
            File file = binary.getFile();
            length = file == null ? -1 : file.length();
        } else {
            length = blobInfo.length.longValue();
        }
        return new BinaryBlob(binary, blobInfo.key, blobInfo.filename, blobInfo.mimeType, blobInfo.encoding,
                blobInfo.digest, length);
    }

    @Override
    public void deleteBlob(BlobContext blobContext) {
        if (fileStore == null) {
            throw new UnsupportedOperationException("delete not supported");
        }
        fileStore.deleteBlob(blobContext);
    }

}
