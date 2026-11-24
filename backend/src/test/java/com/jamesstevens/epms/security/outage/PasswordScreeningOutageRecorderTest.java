package com.jamesstevens.epms.security.outage;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordScreeningOutageRecorderTest {
    @Test void writesIncidentAndEventWithSameIdentifier() throws Exception {
        var repository = mock(PasswordScreeningOutageRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        UUID id = UUID.randomUUID();
        when(repository.saveAndFlush(any(PasswordScreeningOutage.class))).thenAnswer(invocation -> {
            PasswordScreeningOutage outage = invocation.getArgument(0);
            Field field = PasswordScreeningOutage.class.getDeclaredField("outageId");
            field.setAccessible(true);
            field.set(outage, id);
            return outage;
        });
        var recorder = new PasswordScreeningOutageRecorder(repository, jdbc,
            Clock.fixed(Instant.parse("2026-11-20T20:00:00Z"), ZoneOffset.UTC));
        assertEquals(id, recorder.recordFailure("PROVIDER_TIMEOUT"));
        verify(jdbc).update(contains("SCREENING_FAILURE"), any(), eq(id));
    }

    @Test void missingPersistedIdentifierFailsClosed() {
        var repository = mock(PasswordScreeningOutageRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        when(repository.saveAndFlush(any(PasswordScreeningOutage.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        var recorder = new PasswordScreeningOutageRecorder(repository, jdbc);
        assertThrows(NullPointerException.class, () -> recorder.recordFailure("PROVIDER_TIMEOUT"));
        verifyNoInteractions(jdbc);
    }

    @Test void invalidCategoryCannotBeRecorded() {
        var repository = mock(PasswordScreeningOutageRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var recorder = new PasswordScreeningOutageRecorder(repository, jdbc);
        assertThrows(IllegalArgumentException.class, () -> recorder.recordFailure(" "));
        verifyNoInteractions(repository, jdbc);
    }
}
