
package com.jamesstevens.epms.common.mapper;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ManualMappingTest {

    // Represents incoming client data containing only editable fields.
    record SampleRequest(String name, String description) {
    }

    // Represents an immutable response returned to an API client.
    record SampleResponse(
            UUID id,
            String name,
            String description,
            UUID createdByUserId
    ) {
    }

    // Simulates a mutable persistence entity.
    // This class exists only to demonstrate mapping behavior.
    static class SampleEntity {

        private final UUID id;
        private final UUID createdByUserId;
        private String name;
        private String description;

        SampleEntity(
                UUID id,
                UUID createdByUserId,
                String name,
                String description
        ) {
            this.id = id;
            this.createdByUserId = createdByUserId;
            this.name = name;
            this.description = description;
        }
    }

    // Explicitly maps entity fields into an immutable response DTO.
    private static SampleResponse toResponse(SampleEntity entity) {
        return new SampleResponse(
                entity.id,
                entity.name,
                entity.description,
                entity.createdByUserId
        );
    }

    // Updates only fields explicitly permitted by the request contract.
    // Identity and audit fields are intentionally not modified.
    private static void applyUpdate(
            SampleRequest request,
            SampleEntity entity
    ) {
        entity.name = request.name();
        entity.description = request.description();
    }

    @Test
    void entityToResponseShouldMapAllFieldsCorrectly() {
        // Verifies that every response field matches its entity source.
        UUID id = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();

        var entity = new SampleEntity(
                id,
                creatorId,
                "Example Organization",
                "Example description"
        );

        SampleResponse response = toResponse(entity);

        assertEquals(id, response.id());
        assertEquals("Example Organization", response.name());
        assertEquals("Example description", response.description());
        assertEquals(creatorId, response.createdByUserId());
    }

    @Test
    void nullOptionalDescriptionShouldRemainNull() {
        // Verifies that an optional null description is preserved
        // rather than replaced with an arbitrary default.
        var entity = new SampleEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Example Organization",
                null
        );

        SampleResponse response = toResponse(entity);

        assertNull(response.description());
    }

    @Test
    void requestShouldNotOverwriteProtectedFields() {
        // Verifies that client-controlled request data cannot change
        // the existing entity identifier or creator audit identifier.
        UUID originalId = UUID.randomUUID();
        UUID originalCreatorId = UUID.randomUUID();

        var entity = new SampleEntity(
                originalId,
                originalCreatorId,
                "Original Organization",
                "Original description"
        );

        var request = new SampleRequest(
                "Updated Organization",
                "Updated description"
        );

        applyUpdate(request, entity);

        assertEquals(originalId, entity.id);
        assertEquals(originalCreatorId, entity.createdByUserId);
    }

    @Test
    void updateShouldModifyOnlyEditableFields() {
        // Verifies that a full update changes name and description
        // while retaining the entity's protected identity fields.
        UUID originalId = UUID.randomUUID();
        UUID originalCreatorId = UUID.randomUUID();

        var entity = new SampleEntity(
                originalId,
                originalCreatorId,
                "Original Organization",
                "Original description"
        );

        var request = new SampleRequest(
                "Updated Organization",
                "Updated description"
        );

        applyUpdate(request, entity);

        assertEquals("Updated Organization", entity.name);
        assertEquals("Updated description", entity.description);
        assertEquals(originalId, entity.id);
        assertEquals(originalCreatorId, entity.createdByUserId);
    }

    @Test
    void responseShouldRemainUnchangedAfterEntityUpdate() {
        // Verifies that a response is an independent immutable snapshot.
        // Later entity modifications must not change existing responses.
        var entity = new SampleEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Original Organization",
                "Original description"
        );

        SampleResponse response = toResponse(entity);

        applyUpdate(
                new SampleRequest(
                        "Updated Organization",
                        "Updated description"
                ),
                entity
        );

        assertEquals("Original Organization", response.name());
        assertEquals("Original description", response.description());
        assertEquals("Updated Organization", entity.name);
        assertEquals("Updated description", entity.description);
    }
}
