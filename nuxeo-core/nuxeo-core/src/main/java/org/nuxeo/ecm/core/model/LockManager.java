/*
 * (C) Copyright 2006-2019 Nuxeo (http://nuxeo.com/) and others.
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
package org.nuxeo.ecm.core.model;

/**
 * Manager of locks for a repository.
 * <p>
 * The method {@link #closeLockManager()} must be called when done with the lock manager.
 *
 * @since 6.0
 * @deprecated since 10.2, use {@link org.nuxeo.ecm.core.api.lock.LockManager} instead
 */
@Deprecated
public interface LockManager extends org.nuxeo.ecm.core.api.lock.LockManager {

    /**
     * For backward compatibility
     */
    static boolean canLockBeRemoved(String oldOwner, String owner) {
        return org.nuxeo.ecm.core.api.lock.LockManager.canLockBeRemoved(oldOwner, owner);
    }

}
