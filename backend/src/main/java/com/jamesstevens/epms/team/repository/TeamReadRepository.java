package com.jamesstevens.epms.team.repository;

import com.jamesstevens.epms.team.entity.TeamReadEntity;
import com.jamesstevens.epms.team.entity.TeamLifecycleStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Internal operational reads only. Callers must enforce resource authorization. */
@Transactional(readOnly = true)
public interface TeamReadRepository extends Repository<TeamReadEntity, UUID> {
    Optional<TeamReadEntity> findByTeamIdAndOrganizationId(UUID teamId, UUID organizationId);

    Page<TeamReadEntity> findByOrganizationIdOrderByNameAscTeamIdAsc(UUID organizationId, Pageable pageable);

    Page<TeamReadEntity> findByOrganizationIdAndLifecycleStatusOrderByNameAscTeamIdAsc(
            UUID organizationId, TeamLifecycleStatus lifecycleStatus, Pageable pageable);
}
