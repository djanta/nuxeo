/*
 * (C) Copyright 2018 Nuxeo (http://nuxeo.com/) and others.
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
 *     Funsho David
 *     Nuno Cunha <ncunha@nuxeo.com>
 */

package org.nuxeo.ecm.platform.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.nuxeo.ecm.core.storage.BaseDocument.RELATED_TEXT;
import static org.nuxeo.ecm.core.storage.BaseDocument.RELATED_TEXT_ID;
import static org.nuxeo.ecm.core.storage.BaseDocument.RELATED_TEXT_RESOURCES;
import static org.nuxeo.ecm.platform.comment.impl.AbstractCommentManager.ANNOTATION_RELATED_TEXT_ID;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.CloseableCoreSession;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.ACL;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.api.security.impl.ACLImpl;
import org.nuxeo.ecm.core.api.security.impl.ACPImpl;
import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.ecm.platform.comment.api.Annotation;
import org.nuxeo.ecm.platform.comment.api.AnnotationImpl;
import org.nuxeo.ecm.platform.comment.api.AnnotationService;
import org.nuxeo.ecm.platform.comment.api.ExternalEntity;
import org.nuxeo.ecm.platform.comment.api.exceptions.CommentNotFoundException;
import org.nuxeo.ecm.platform.comment.api.exceptions.CommentSecurityException;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

/**
 * @since 10.1
 */
@RunWith(FeaturesRunner.class)
@Features(CoreFeature.class)
@Deploy("org.nuxeo.ecm.platform.query.api")
@Deploy("org.nuxeo.ecm.platform.comment")
public class TestAnnotationService {

    @Inject
    protected CoreFeature coreFeature;

    @Inject
    protected AnnotationService annotationService;

    @Inject
    protected TransactionalFeature transactionalFeature;

    protected CloseableCoreSession session;

    @Before
    public void setup() {
        session = coreFeature.openCoreSession();
        DocumentModel domain = session.createDocumentModel("/", "testDomain", "Domain");
        session.createDocument(domain);
        session.close();

        // Open a session as a regular user
        session = coreFeature.openCoreSession("jdoe");

        // Give permissions to him
        ACLImpl acl = new ACLImpl();
        acl.addAll(List.of(new ACE("jdoe", SecurityConstants.READ_WRITE), //
                new ACE("jdoe", SecurityConstants.WRITE_SECURITY)));
        ACPImpl acp = new ACPImpl();
        acp.addACL(acl);
        coreFeature.getCoreSession().setACP(new PathRef("/"), acp, true);
    }

    @After
    public void tearDown() {
        session.close();
    }

    @Test
    public void testCreateAnnotation() {
        DocumentModel docToAnnotate = createDocumentModel("testDoc");
        transactionalFeature.nextTransaction();

        Annotation annotation = createSampleAnnotation(docToAnnotate.getId(), "I am an annotation");
        Annotation createdAnnotation = createAnnotation(annotation);

        assertEquals(docToAnnotate.getId(), createdAnnotation.getParentId());
        assertEquals(annotation.getAuthor(), createdAnnotation.getAuthor());
        assertEquals(annotation.getText(), createdAnnotation.getText());
        assertEquals(annotation.getXpath(), createdAnnotation.getXpath());
        assertEquals(((ExternalEntity) annotation).getEntityId(), ((ExternalEntity) createdAnnotation).getEntityId());
        assertEquals(((ExternalEntity) annotation).getOrigin(), ((ExternalEntity) createdAnnotation).getOrigin());

        assertTrue(createdAnnotation.getAncestorIds().contains(docToAnnotate.getId()));
        assertNotNull(createdAnnotation.getCreationDate());
        assertNotNull(createdAnnotation.getModificationDate());

        try (CloseableCoreSession bobSession = CoreInstance.openCoreSession(docToAnnotate.getRepositoryName(), "bob")) {
            annotationService.createAnnotation(bobSession, annotation);
            fail("bob should not be able to create annotation");
        } catch (CommentSecurityException e) {
            assertEquals("The user bob can not create annotations on document " + docToAnnotate.getId(),
                    e.getMessage());
        }
    }

    @Test
    public void testGetAnnotation() {
        DocumentModel docToAnnotate = createDocumentModel("testDoc");

        String entityId = "foo";
        String docIdToAnnotate = docToAnnotate.getId();
        String xpathToAnnotate = "files:files/0/file";

        String annotationId;
        try (CloseableCoreSession adminSession = CoreInstance.openCoreSessionSystem(coreFeature.getRepositoryName())) {
            Annotation annotation = new AnnotationImpl();
            annotation.setParentId(docIdToAnnotate);
            annotation.setXpath(xpathToAnnotate);
            ((ExternalEntity) annotation).setEntityId(entityId);
            annotationId = annotationService.createAnnotation(adminSession, annotation).getId();

            // Fake the existence of annotation for bobSession
            ACPImpl acp = new ACPImpl();
            ACL acl = acp.getOrCreateACL();
            acl.add(new ACE("bob", SecurityConstants.READ, true));
            adminSession.setACP(new IdRef(annotationId), acp, false);
            adminSession.save();
        }

        Annotation annotation = annotationService.getAnnotation(session, annotationId);
        assertEquals(entityId, ((ExternalEntity) annotation).getEntityId());

        try (CloseableCoreSession bobSession = CoreInstance.openCoreSession(docToAnnotate.getRepositoryName(), "bob")) {
            annotationService.getAnnotation(bobSession, annotationId);
            fail("bob should not be able to get annotation");
        } catch (CommentSecurityException e) {
            assertEquals("The user bob does not have access to the comments of document " + annotationId,
                    e.getMessage());
        }
    }

    @Test
    public void testUpdateAnnotation() {
        DocumentModel docToAnnotate = createDocumentModel("testDoc");

        String xpathToAnnotate = "files:files/0/file";

        Annotation annotation = new AnnotationImpl();
        annotation.setParentId(docToAnnotate.getId());
        annotation.setXpath(xpathToAnnotate);
        annotation.setAuthor(session.getPrincipal().getName());
        annotation = annotationService.createAnnotation(session, annotation);
        transactionalFeature.nextTransaction();

        // Fake the existence of annotation for bobSession
        ACPImpl acp = new ACPImpl();
        ACL acl = acp.getOrCreateACL();
        acl.add(new ACE("bob", SecurityConstants.READ, true));
        session.setACP(new IdRef(annotation.getId()), acp, false);
        transactionalFeature.nextTransaction();

        assertNull(((ExternalEntity) annotation).getEntity());

        ((ExternalEntity) annotation).setEntityId("entityId");
        ((ExternalEntity) annotation).setEntity("Entity");
        annotationService.updateAnnotation(session, annotation.getId(), annotation);

        assertEquals("Entity",
                ((ExternalEntity) annotationService.getAnnotation(session, annotation.getId())).getEntity());

        try (CloseableCoreSession bobSession = CoreInstance.openCoreSession(docToAnnotate.getRepositoryName(), "bob")) {
            annotationService.updateAnnotation(bobSession, annotation.getId(), annotation);
            fail("bob should not be able to edit annotation");
        } catch (CommentSecurityException e) {
            assertEquals("The user bob cannot edit comments of document " + docToAnnotate.getId(), e.getMessage());
        }
    }

    @Test
    public void testDeleteAnnotation() {
        DocumentModel docToAnnotate = createDocumentModel("testDoc");

        String xpathToAnnotate = "files:files/0/file";

        Annotation annotation = new AnnotationImpl();
        annotation.setParentId(docToAnnotate.getId());
        annotation.setXpath(xpathToAnnotate);
        annotation.setAuthor(session.getPrincipal().getName());
        annotation = annotationService.createAnnotation(session, annotation);
        transactionalFeature.nextTransaction();

        assertTrue(session.exists(new IdRef(annotation.getId())));

        try {
            annotationService.deleteAnnotation(session, "toto");
            fail("Deleting an unknown annotation should have failed");
        } catch (CommentNotFoundException e) {
            // ok
            assertEquals(404, e.getStatusCode());
            assertNotNull(e.getMessage());
        }

        // Fake the existence of annotation for bobSession
        ACPImpl acp = new ACPImpl();
        ACL acl = acp.getOrCreateACL();
        acl.add(new ACE("bob", SecurityConstants.READ, true));
        session.setACP(new IdRef(annotation.getId()), acp, false);
        transactionalFeature.nextTransaction();

        try (CloseableCoreSession bobSession = CoreInstance.openCoreSession(docToAnnotate.getRepositoryName(), "bob")) {
            annotationService.deleteAnnotation(bobSession, annotation.getId());
            fail("bob should not be able to delete annotation");
        } catch (CommentSecurityException e) {
            assertEquals("The user bob cannot delete comment of the document " + docToAnnotate.getId(), e.getMessage());
        }

        annotationService.deleteAnnotation(session, annotation.getId());
        assertFalse(session.exists(new IdRef(annotation.getId())));
    }

    @Test
    public void testGetAnnotationsForDocument() {
        DocumentModel docToAnnotate = createDocumentModel("testDoc");

        String xpathToAnnotate = "files:files/0/file";

        List<Annotation> annotations = annotationService.getAnnotations(session, docToAnnotate.getId(),
                xpathToAnnotate);
        assertTrue(annotations.isEmpty());

        DocumentModel docToAnnotate1 = createDocumentModel("testDoc1");
        // Fake the existence of document for bobSession
        ACPImpl acp = new ACPImpl();
        ACL acl = acp.getOrCreateACL();
        acl.add(new ACE("bob", SecurityConstants.BROWSE, true));
        session.setACP(docToAnnotate1.getRef(), acp, false);
        transactionalFeature.nextTransaction();

        int nbAnnotations1 = 99;
        Annotation annotation1 = new AnnotationImpl();
        annotation1.setParentId(docToAnnotate1.getId());
        annotation1.setXpath(xpathToAnnotate);
        for (int i = 0; i < nbAnnotations1; i++) {
            annotationService.createAnnotation(session, annotation1);
        }
        transactionalFeature.nextTransaction();

        DocumentModel docToAnnotate2 = createDocumentModel("testDoc2");
        int nbAnnotations2 = 74;
        Annotation annotation2 = new AnnotationImpl();
        annotation2.setParentId(docToAnnotate2.getId());
        annotation2.setXpath(xpathToAnnotate);
        for (int i = 0; i < nbAnnotations2; i++) {
            annotationService.createAnnotation(session, annotation2);
        }
        transactionalFeature.nextTransaction();
        assertEquals(nbAnnotations1,
                annotationService.getAnnotations(session, docToAnnotate1.getId(), xpathToAnnotate).size());
        assertEquals(nbAnnotations2,
                annotationService.getAnnotations(session, docToAnnotate2.getId(), xpathToAnnotate).size());

        try (CloseableCoreSession bobSession = CoreInstance.openCoreSession(docToAnnotate.getRepositoryName(), "bob")) {
            annotationService.getAnnotations(bobSession, docToAnnotate1.getId(), xpathToAnnotate);
            fail("bob should not be able to get annotations");
        } catch (CommentSecurityException e) {
            assertEquals("The user bob does not have access to the annotations of document " + docToAnnotate1.getId(),
                    e.getMessage());
        }
    }

    @Test
    public void testGetExternalAnnotation() {
        DocumentModel docToAnnotate = createDocumentModel("testDoc");

        String entityId = "foo";
        String docIdToAnnotate = docToAnnotate.getId();
        String xpathToAnnotate = "files:files/0/file";

        Annotation annotation = new AnnotationImpl();
        ((ExternalEntity) annotation).setEntityId(entityId);
        annotation.setParentId(docIdToAnnotate);
        annotation.setXpath(xpathToAnnotate);
        annotationService.createAnnotation(session, annotation);
        transactionalFeature.nextTransaction();

        annotation = annotationService.getExternalAnnotation(session, entityId);
        assertEquals(entityId, ((ExternalEntity) annotation).getEntityId());

        // Fake the existence of annotation for bobSession
        ACPImpl acp = new ACPImpl();
        ACL acl = acp.getOrCreateACL();
        acl.add(new ACE("bob", SecurityConstants.READ, true));
        session.setACP(new IdRef(annotation.getId()), acp, false);
        transactionalFeature.nextTransaction();

        try (CloseableCoreSession bobSession = CoreInstance.openCoreSession(docToAnnotate.getRepositoryName(), "bob")) {
            annotation = annotationService.getExternalAnnotation(bobSession, entityId);
            fail("bob should not be able to get annotation");
        } catch (CommentSecurityException e) {
            assertEquals("The user bob does not have access to the comments of document " + annotation.getId(),
                    e.getMessage());
        }
    }

    @Test
    public void testUpdateExternalAnnotation() {
        DocumentModel docToAnnotate = createDocumentModel("testDoc");

        String xpathToAnnotate = "files:files/0/file";
        String entityId = "foo";
        String entity = "<entity></entity>";

        Annotation annotation = new AnnotationImpl();
        ((ExternalEntity) annotation).setEntityId(entityId);
        annotation.setParentId(docToAnnotate.getId());
        annotation.setXpath(xpathToAnnotate);
        annotation.setAuthor(session.getPrincipal().getName());
        annotationService.createAnnotation(session, annotation);
        transactionalFeature.nextTransaction();

        assertNull(((ExternalEntity) annotation).getEntity());

        ((ExternalEntity) annotation).setEntity(entity);
        try {
            annotationService.updateExternalAnnotation(session, "fakeId", annotation);
            fail("The external annotation should not exist");
        } catch (CommentNotFoundException e) {
            // ok
            assertEquals(404, e.getStatusCode());
            assertNotNull(e.getMessage());
        }

        annotationService.updateExternalAnnotation(session, entityId, annotation);
        annotation = annotationService.getExternalAnnotation(session, entityId);
        assertEquals(entityId, ((ExternalEntity) annotation).getEntityId());

        // Fake the existence of annotation for bobSession
        ACPImpl acp = new ACPImpl();
        ACL acl = acp.getOrCreateACL();
        acl.add(new ACE("bob", SecurityConstants.READ, true));
        session.setACP(new IdRef(annotation.getId()), acp, false);
        transactionalFeature.nextTransaction();

        try (CloseableCoreSession bobSession = CoreInstance.openCoreSession(docToAnnotate.getRepositoryName(), "bob")) {
            annotationService.updateAnnotation(bobSession, annotation.getId(), annotation);
            fail("bob should not be able to edit annotation");
        } catch (CommentSecurityException e) {
            assertEquals("The user bob cannot edit comments of document " + docToAnnotate.getId(), e.getMessage());
        }
    }

    @Test
    public void testDeleteExternalAnnotation() {
        DocumentModel docToAnnotate = createDocumentModel("testDoc");

        String xpathToAnnotate = "files:files/0/file";
        String entityId = "foo";

        Annotation annotation = new AnnotationImpl();
        ((ExternalEntity) annotation).setEntityId(entityId);
        annotation.setParentId(docToAnnotate.getId());
        annotation.setXpath(xpathToAnnotate);
        annotation.setAuthor(session.getPrincipal().getName());
        annotation = annotationService.createAnnotation(session, annotation);
        transactionalFeature.nextTransaction();

        assertTrue(session.exists(new IdRef(annotation.getId())));

        try {
            annotationService.deleteExternalAnnotation(session, "toto");
            fail("Deleting an unknown annotation should have failed");
        } catch (CommentNotFoundException e) {
            // ok
            assertEquals(404, e.getStatusCode());
            assertNotNull(e.getMessage());
        }

        // Fake the existence of annotation for bobSession
        ACPImpl acp = new ACPImpl();
        ACL acl = acp.getOrCreateACL();
        acl.add(new ACE("bob", SecurityConstants.READ, true));
        session.setACP(new IdRef(annotation.getId()), acp, false);
        transactionalFeature.nextTransaction();

        try (CloseableCoreSession bobSession = CoreInstance.openCoreSession(docToAnnotate.getRepositoryName(), "bob")) {
            annotationService.deleteAnnotation(bobSession, annotation.getId());
            fail("bob should not be able to delete annotation");
        } catch (CommentSecurityException e) {
            assertEquals("The user bob cannot delete comment of the document " + docToAnnotate.getId(), e.getMessage());
        }

        annotationService.deleteExternalAnnotation(session, entityId);
        assertFalse(session.exists(new IdRef(annotation.getId())));
    }

    @Test
    public void shouldFindAnnotatedFileByFullTextSearch() {
        DocumentModel firstDocToAnnotation = createDocumentModel("anotherFile1");
        DocumentModel secondDocToAnnotation = createDocumentModel("anotherFile2");
        Map<DocumentRef, List<Annotation>> mapAnnotationsByDocRef = createAnnotationsAndRepliesForFullTextSearch(
                firstDocToAnnotation, secondDocToAnnotation);

        // One annotation and 3 replies
        checkRelatedTextResource(firstDocToAnnotation.getRef(),
                mapAnnotationsByDocRef.get(firstDocToAnnotation.getRef()));

        // One annotation and no replies
        checkRelatedTextResource(secondDocToAnnotation.getRef(),
                mapAnnotationsByDocRef.get(secondDocToAnnotation.getRef()));

        // We make a fulltext query to find the 2 annotated files
        makeAndVerifyFullTextSearch("first annotation", List.of(firstDocToAnnotation, secondDocToAnnotation));

        // We make a fulltext query to find the second annotated file
        makeAndVerifyFullTextSearch("secondFile", List.of(secondDocToAnnotation));

        // We make a fulltext query to find the first annotated file by any reply
        makeAndVerifyFullTextSearch("reply", List.of(firstDocToAnnotation));

        // Now we edit and change the annotation text of the second reply
        makeAndVerifyFullTextSearch("UpdatedReply", List.of());

        // Get the second reply and update his text
        Annotation secondReply = mapAnnotationsByDocRef.get(firstDocToAnnotation.getRef()).get(2);
        secondReply.setText("I am an UpdatedReply");
        annotationService.updateAnnotation(session, secondReply.getId(), secondReply);
        transactionalFeature.nextTransaction();

        // Now we should find the document with this updated reply text
        makeAndVerifyFullTextSearch("UpdatedReply", List.of(firstDocToAnnotation));

        // Now let's remove this second reply
        annotationService.deleteAnnotation(session, secondReply.getId());
        transactionalFeature.nextTransaction();
        makeAndVerifyFullTextSearch("UpdatedReply", List.of());

        List<Annotation> annotations = mapAnnotationsByDocRef.get(firstDocToAnnotation.getRef())
                                                             .stream()
                                                             .filter(c -> !c.getId().equals(secondReply.getId()))
                                                             .collect(Collectors.toList());
        checkRelatedTextResource(firstDocToAnnotation.getRef(), annotations);
    }

    protected Map<DocumentRef, List<Annotation>> createAnnotationsAndRepliesForFullTextSearch(
            DocumentModel firstDocToAnnotate, DocumentModel secondDocToAnnotate) {

        // Create 2 annotations on the two files
        Annotation annotationOfFile1 = createAnnotation(
                createSampleAnnotation(firstDocToAnnotate.getId(), "I am the first annotation of firstFile"));

        Annotation annotationOfFile2 = createAnnotation(
                createSampleAnnotation(secondDocToAnnotate.getId(), "I am the first annotation of secondFile"));

        // Create first reply on first annotation of first file
        Annotation firstReply = createAnnotation(
                createSampleAnnotation(annotationOfFile1.getId(), "I am the first reply of first annotation"));

        // Create second reply
        Annotation secondReply = createAnnotation(
                createSampleAnnotation(firstReply.getId(), "I am the second reply of first annotation"));

        // Create third reply
        Annotation thirdReply = createAnnotation(
                createSampleAnnotation(secondReply.getId(), "I am the third reply of first annotation"));

        return Map.of( //
                new IdRef(firstDocToAnnotate.getId()), List.of(annotationOfFile1, firstReply, secondReply, thirdReply), //
                new IdRef(secondDocToAnnotate.getId()), List.of(annotationOfFile2) //
        );
    }

    protected void makeAndVerifyFullTextSearch(String ecmFullText, List<DocumentModel> expectedDocs) {
        String query = String.format(
                "SELECT * FROM Document WHERE ecm:fulltext = '%s' AND ecm:mixinType != 'HiddenInNavigation'",
                ecmFullText);

        DocumentModelList documents = session.query(query);
        assertEquals(
                expectedDocs.stream().sorted(Comparator.comparing(DocumentModel::getId)).collect(Collectors.toList()), //
                documents.stream().sorted(Comparator.comparing(DocumentModel::getId)).collect(Collectors.toList()));
    }

    @SuppressWarnings("unchecked")
    protected void checkRelatedTextResource(DocumentRef documentRef, List<Annotation> annotations) {
        DocumentModel document = session.getDocument(documentRef);

        List<Map<String, String>> resources = (List<Map<String, String>>) document.getPropertyValue(
                RELATED_TEXT_RESOURCES);

        List<String> relatedTextIds = annotations.stream()
                                                 .map(a -> String.format(ANNOTATION_RELATED_TEXT_ID, a.getId()))
                                                 .sorted()
                                                 .collect(Collectors.toList());

        List<String> relatedTextValues = annotations.stream()
                                                    .map(Annotation::getText)
                                                    .sorted()
                                                    .collect(Collectors.toList());

        assertEquals(relatedTextIds,
                resources.stream().map(m -> m.get(RELATED_TEXT_ID)).sorted().collect(Collectors.toList()));
        assertEquals(relatedTextValues,
                resources.stream().map(m -> m.get(RELATED_TEXT)).sorted().collect(Collectors.toList()));
    }

    protected Annotation createAnnotation(Annotation annotation) {
        Annotation createdAnnotation = annotationService.createAnnotation(session, annotation);
        transactionalFeature.nextTransaction();
        return createdAnnotation;
    }

    protected Annotation createSampleAnnotation(String annotateDocId, String text) {
        String entityId = "foo";
        String xpathToAnnotate = "files:files/0/file";
        String comment = text;
        String origin = "Test";
        String entity = "<entity><annotation>bar</annotation></entity>";

        Annotation annotation = new AnnotationImpl();
        annotation.setAuthor("jdoe");
        annotation.setText(comment);
        annotation.setParentId(annotateDocId);
        annotation.setXpath(xpathToAnnotate);
        annotation.setCreationDate(Instant.now());
        annotation.setModificationDate(Instant.now());
        ((ExternalEntity) annotation).setEntityId(entityId);
        ((ExternalEntity) annotation).setOrigin(origin);
        ((ExternalEntity) annotation).setEntity(entity);

        return annotation;
    }

    protected DocumentModel createDocumentModel(String fileName) {
        DocumentModel docToAnnotate = session.createDocumentModel("/testDomain", fileName, "File");
        docToAnnotate = session.createDocument(docToAnnotate);
        transactionalFeature.nextTransaction();
        return docToAnnotate;
    }

}
