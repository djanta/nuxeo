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
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import javax.transaction.Status;
import javax.transaction.Synchronization;
import javax.transaction.SystemException;
import javax.transaction.Transaction;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.core.api.ConcurrentUpdateException;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.runtime.jtajca.NuxeoContainer;
import org.nuxeo.runtime.transaction.TransactionHelper;

/**
 * Transactional File Store.
 * <p>
 * Until the transaction is committed, blobs are stored in a transient store. Upon commit, they are sent to the
 * permanent store.
 *
 * @since 11.1
 */
public class TransactionalFileStore extends AbstractFileStore implements Synchronization {

    private static final Log log = LogFactory.getLog(TransactionalFileStore.class);

    protected final FileStore transientStore;

    protected final FileStore permanentStore;

    // the files that have been created in the transaction
    // a map of key to 1. temporary key of the blob, or 2. empty string for a delete
    protected final ThreadLocal<Map<String, String>> transientKeys = new ThreadLocal<>();

    // the keys that have been modified in any active transaction
    protected final Map<String, Transaction> keysInActiveTransactions = new ConcurrentHashMap<>();

    protected static final String DELETE_MARKER = "";

    protected static boolean isDeleteMarker(String transientKey) {
        return DELETE_MARKER.equals(transientKey);
    }

    public TransactionalFileStore(FileStore transientStore, FileStore permanentStore) {
        this.transientStore = transientStore;
        this.permanentStore = permanentStore;
    }

    @Override
    public void writeBlob(BlobWriteContext blobWriteContext, WriteObserver writeObserver, Supplier<String> keyComputer)
            throws IOException {
        if (TransactionHelper.isTransactionActive()) {
            // for the transient write we use a random key
            String transientKey = randomString();
            transientStore.writeBlob(blobWriteContext, writeObserver, () -> transientKey);
            String key = keyComputer.get(); // may depend on write observer
            try {
                checkConcurrentUpdate(key);
            } catch (ConcurrentUpdateException e) {
                // delete transient store file
                transientStore.deleteFile(transientKey);
                throw e;
            }
            putTransientKey(key, transientKey);
            // store in transient store last, so that if there's a rollback we can remove it
        } else {
            permanentStore.writeBlob(blobWriteContext, writeObserver, keyComputer);
        }
    }

    @Override
    public boolean copyFile(String key, FileStore sourceStore, String sourceKey, boolean move, boolean atomic)
            throws IOException {
        // copyFile only called from commit or during caching
        throw new UnsupportedOperationException();
    }

    @Override
    public Path getLocalFile(String key) {
        if (TransactionHelper.isTransactionActive()) {
            String transientKey = getTransientKey(key);
            if (isDeleteMarker(transientKey)) {
                return null;
            } else if (transientKey != null) {
                return transientStore.getLocalFile(transientKey);
            }
            // else fall through
        }
        // check permanent store
        return permanentStore.getLocalFile(key);
    }

    @Override
    public InputStream getStream(String key) throws IOException {
        if (TransactionHelper.isTransactionActive()) {
            String transientKey = getTransientKey(key);
            if (isDeleteMarker(transientKey)) {
                return null;
            } else if (transientKey != null) {
                InputStream stream = transientStore.getStream(transientKey);
                if (stream == null) {
                    log.error("Missing file from transient file store: " + transientKey);
                }
                return stream;
            }
            // else fall through
        }
        // check permanent store
        return permanentStore.getStream(key);
    }

    @Override
    public boolean readFileTo(String key, Path file) throws IOException {
        if (TransactionHelper.isTransactionActive()) {
            String transientKey = getTransientKey(key);
            if (isDeleteMarker(transientKey)) {
                return false; // deleted in transaction
            } else if (transientKey != null) {
                boolean found = transientStore.readFileTo(transientKey, file);
                if (!found) {
                    log.error("Missing file from transient file store: " + transientKey);
                }
                return found;
            }
            // else fall through to check permanent store
        }
        // check permanent store
        return permanentStore.readFileTo(key, file);
    }

    @Override
    public void deleteFile(String key) {
        if (TransactionHelper.isTransactionActive()) {
            checkConcurrentUpdate(key);
            putTransientKey(key, DELETE_MARKER);
        } else {
            permanentStore.deleteFile(key);
        }
    }

    // called when we know a transaction is already active
    // checks for concurrent update before writing to key
    protected void checkConcurrentUpdate(String key) {
        Transaction tx = getTransaction();
        Transaction otherTx = keysInActiveTransactions.putIfAbsent(key, tx);
        if (otherTx != null) {
            if (otherTx != tx) {
                throw new ConcurrentUpdateException(key);
            }
            // there may be a previous transient file
            // it's now unneeded as we're about to overwrite it
            String otherTransientKey = getTransientKey(key);
            if (otherTransientKey != null && !isDeleteMarker(otherTransientKey)) {
                transientStore.deleteFile(otherTransientKey);
            }
        }
    }

    protected Transaction getTransaction() {
        try {
            return NuxeoContainer.getTransactionManager().getTransaction();
        } catch (NullPointerException | SystemException e) {
            throw new NuxeoException(e);
        }
    }

    // ---------- Synchronization ----------

    protected String getTransientKey(String key) {
        Map<String, String> transactionKeyMap = transientKeys.get();
        if (transactionKeyMap == null) {
            return null;
        } else {
            return transactionKeyMap.get(key);
        }
    }

    protected void putTransientKey(String key, String value) {
        Map<String, String> transactionKeyMap = transientKeys.get();
        if (transactionKeyMap == null) {
            transactionKeyMap = new HashMap<>();
            transientKeys.set(transactionKeyMap);
            TransactionHelper.registerSynchronization(this);
        }
        transactionKeyMap.put(key, value);
    }

    @Override
    public void beforeCompletion() {
        // nothing to do
    }

    @Override
    public void afterCompletion(int status) {
        Map<String, String> transactionKeyMap = transientKeys.get();
        transientKeys.remove();
        try {
            if (status == Status.STATUS_COMMITTED) {
                // move transient files to permanent store
                for (Entry<String, String> en : transactionKeyMap.entrySet()) {
                    String key = en.getKey();
                    String transientKey = en.getValue();
                    if (isDeleteMarker(transientKey)) {
                        permanentStore.deleteFile(key);
                    } else {
                        // atomically move to permanent store
                        try {
                            boolean found = permanentStore.copyFile(key, transientStore, transientKey, true, true);
                            if (!found) {
                                log.error("Missing file from transient file store: " + transientKey + ", failed to commit creation of file: " + key);
                            }
                        } catch (IOException e) {
                            log.error("Failed to commit creation of file: " + key, e);
                        }
                    }
                }
            } else if (status == Status.STATUS_ROLLEDBACK) {
                // delete transient files
                for (String transientKey : transactionKeyMap.values()) {
                    if (!isDeleteMarker(transientKey)) {
                        transientStore.deleteFile(transientKey);
                    }
                }
            } else {
                log.error("Unexpected afterCompletion status: " + status);
            }
        } finally {
            keysInActiveTransactions.keySet().removeAll(transactionKeyMap.keySet());
        }
    }

}
