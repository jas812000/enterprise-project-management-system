package com.jamesstevens.epms.user.repository;

import com.jamesstevens.epms.user.entity.UserCredential;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for password credential persistence.
 *
 * <p>Each user may have at most one credential record,
 * enforced by the database's unique constraint on user_id.</p>
 *
 * <p>Credential access must be restricted to trusted
 * authentication and account-management services.
 * User-profile endpoints must not expose password hashes.</p>
 */
public interface UserCredentialRepository
        extends JpaRepository<UserCredential, UUID> {

    /**
     * Finds the password credential record for a user.
     *
     * @param userId owning user identifier
     * @return credential record, if present
     */
    Optional<UserCredential> findByUserId(UUID userId);

    /** Locks the credential row for a transactional failed-attempt update. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from UserCredential c where c.userId = :userId")
    Optional<UserCredential> findByUserIdForUpdate(@Param("userId") UUID userId);

    @Query("select c from UserCredential c where c.screeningStatus = 'PENDING_VERIFICATION' "
            + "and (c.nextScreeningRetryAt is null or c.nextScreeningRetryAt <= :now) "
            + "order by c.provisionalIssuedAt asc, c.userCredentialId asc")
    List<UserCredential> findEligibleForScreening(@Param("now") OffsetDateTime now, Pageable pageable);
}
