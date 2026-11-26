package com.jamesstevens.epms.team.repository;

import com.jamesstevens.epms.team.entity.TeamLeadershipReadEntity;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Internal operational reads only. Callers must enforce resource authorization. */
@Transactional(readOnly = true)
public interface TeamLeadershipReadRepository extends Repository<TeamLeadershipReadEntity, UUID> {
    Page<TeamLeadershipReadEntity> findByOrganizationIdAndTeamIdOrderByAssignedAtAscTeamLeadershipAssignmentIdAsc(
            UUID organizationId, UUID teamId, Pageable pageable);

    Page<TeamLeadershipReadEntity> findByOrganizationIdAndUserIdOrderByAssignedAtAscTeamLeadershipAssignmentIdAsc(
            UUID organizationId, UUID userId, Pageable pageable);

    boolean existsByOrganizationIdAndTeamIdAndUserId(UUID organizationId, UUID teamId, UUID userId);
}
