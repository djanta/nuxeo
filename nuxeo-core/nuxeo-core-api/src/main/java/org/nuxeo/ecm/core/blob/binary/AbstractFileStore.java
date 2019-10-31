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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.apache.commons.lang3.mutable.MutableObject;
import org.nuxeo.ecm.core.api.NuxeoException;

/**
 * Basic helper implementations for a {@link FileStore}.
 *
 * @since 11.1
 */
public abstract class AbstractFileStore implements FileStore {

    // in these low-level APIs we deal with unprefixed xpaths, so not file:content
    protected static final String MAIN_BLOB_XPATH = "content";

    @Override
    public String writeBlob(BlobContext blobContext, boolean recordMode, String digestAlgorithm) throws IOException {
        WriteObserver writeObserver;
        Supplier<String> keyComputer;
        if (recordMode) {
            String xpath = blobContext.xpath;
            if (!MAIN_BLOB_XPATH.equals(xpath)) {
                throw new NuxeoException(
                        "Cannot store blob at xpath '" + xpath + "' in record blob provider");
            }
            writeObserver = null;
            String key = getKeyFromBlobContext(blobContext);
            keyComputer = () -> key;
        } else {
            // intermediate object through which write observer and key computer exchange data for the digest
            MutableObject<String> keyHolder = new MutableObject<>();
            writeObserver = new WriteObserverDigest(digestAlgorithm, keyHolder::setValue);
            keyComputer = keyHolder::getValue;
        }
        BlobWriteContext blobWriteContext = new BlobWriteContext(blobContext);
        writeBlob(blobWriteContext, writeObserver, keyComputer);
        return keyComputer.get();
    }

    @Override
    public void deleteBlob(BlobContext blobContext) {
        String xpath = blobContext.xpath;
        if (!MAIN_BLOB_XPATH.equals(xpath)) {
            throw new NuxeoException(
                    "Cannot delete blob at xpath '" + xpath + "' in record blob provider");
        }
        String key = getKeyFromBlobContext(blobContext);
        deleteFile(key);
    }

    /**
     * Policy to compute a key for a blob in record mode.
     */
    public String getKeyFromBlobContext(BlobContext blobContext) {
        return blobContext.docId;
    }

    /** Returns a random string suitable as a key. */
    protected String randomString() {
        return String.valueOf(randomLong());
    }

    /** Returns a random positive long. */
    protected long randomLong() {
        long value;
        do {
            value = ThreadLocalRandom.current().nextLong();
        } while (value == Long.MIN_VALUE);
        if (value < 0) {
            value = -value;
        }
        return value;
    }

}
