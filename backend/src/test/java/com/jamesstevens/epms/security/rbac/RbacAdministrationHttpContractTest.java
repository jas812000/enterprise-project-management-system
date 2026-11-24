package com.jamesstevens.epms.security.rbac;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Controller HTTP contract tests. These do not assert Spring Security filter-chain behavior. */
class RbacAdministrationHttpContractTest {
    private final RbacAdministrationService service = mock(RbacAdministrationService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new RbacAdministrationController(service))
            .setControllerAdvice(new RbacAdministrationExceptionHandler()).build();

    @Test void assignmentResponseDoesNotExposeActorId() throws Exception {
        UUID target = UUID.randomUUID();
        UUID result = UUID.randomUUID();
        when(service.assign(any(), eq(target), eq("PROJECT_MANAGER"), eq("approved"))).thenReturn(result);
        mvc.perform(post("/api/security/role-assignments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUserId\":\"" + target + "\",\"roleCode\":\"PROJECT_MANAGER\",\"reason\":\"approved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignmentId").value(result.toString()))
                .andExpect(jsonPath("$.actorId").doesNotExist());
        verify(service).assign(any(), eq(target), eq("PROJECT_MANAGER"), eq("approved"));
    }

    @Test void revocationRoutesAssignmentIdToService() throws Exception {
        UUID assignment = UUID.randomUUID();
        UUID result = UUID.randomUUID();
        when(service.revoke(any(), eq(assignment), eq("revoked"))).thenReturn(result);
        mvc.perform(post("/api/security/role-assignments/{id}/revoke", assignment)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"revoked\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignmentId").value(result.toString()));
        verify(service).revoke(any(), eq(assignment), eq("revoked"));
    }

    @Test void deniedOperationHasSanitizedBody() throws Exception {
        when(service.assign(any(), any(), any(), any()))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("sensitive internal detail"));
        mvc.perform(post("/api/security/role-assignments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUserId\":\"" + UUID.randomUUID() + "\",\"roleCode\":\"AUDITOR\",\"reason\":\"test\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("RBAC administration is not permitted."));
    }
}
