package com.jamesstevens.epms.team.repository;

import com.jamesstevens.epms.team.entity.TeamMembershipReadEntity;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Internal operational reads only. Callers must enforce resource authorization. */
@Transactional(readOnly = true)
public interface TeamMembershipReadRepository extends Repository<TeamMembershipReadEntity, UUID> {
    Page<TeamMembershipReadEntity> findByOrganizationIdAndTeamIdOrderByJoinedAtAscTeamMembershipIdAsc(
            UUID organizationId, UUID teamId, Pageable pageable);

    Page<TeamMembershipReadEntity> findByOrganizationIdAndUserIdOrderByJoinedAtAscTeamMembershipIdAsc(
            UUID organizationId, UUID userId, Pageable pageable);

    boolean existsByOrganizationIdAndTeamIdAndUserId(UUID organizationId, UUID teamId, UUID userId);
}
