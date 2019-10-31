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

import java.util.Objects;

import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.model.Document;

/**
 * Context of blob (what document it's part of, its xpath, etc.).
 *
 * @since 11.1
 */
public class BlobContext {

    public final Blob blob;

    public final String repositoryName;

    public final String docId;

    public final String versionSeriesId;

    public final String xpath;

    // used for tests
    public BlobContext(Blob blob, String docId, String xpath) {
        this.blob = blob;
        this.repositoryName = null;
        this.docId = docId;
        this.versionSeriesId = null;
        this.xpath = xpath;
    }

    public BlobContext(Blob blob, Document doc, String xpath) {
        Objects.requireNonNull(doc, "null doc");
        this.blob = blob;
        this.repositoryName = doc.getRepositoryName();
        this.docId = doc.getUUID();
        this.versionSeriesId = doc.getVersionSeriesId();
        this.xpath = xpath;
    }

    public BlobContext(Blob blob) {
        this.blob = blob;
        this.repositoryName = null;
        this.docId = null;
        this.versionSeriesId = null;
        this.xpath = null;
    }

}
