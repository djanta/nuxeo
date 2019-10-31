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

/**
 * Called writing bytes.
 *
 * @since 11.1
 */
public interface WriteObserver {

    /**
     * Called when a chunk of a write is done
     *
     * @param buf the buffer
     * @param offset the offset to start from in the buffer
     * @param len the number of bytes to use, starting at {@code offset}
     */
    void write(byte[] buf, int offset, int len);

    /**
     * Called when the whole write is done.
     */
    void flush();

}
