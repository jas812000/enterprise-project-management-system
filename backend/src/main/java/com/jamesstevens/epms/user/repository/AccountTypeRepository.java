package com.jamesstevens.epms.user.repository;

import com.jamesstevens.epms.user.entity.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only application access to account-type reference data.
 *
 * <p>Account types are maintained through Flyway migrations,
 * not through ordinary application operations.</p>
 *
 * <p>The User Service uses this repository to verify that
 * a requested account classification exists and is active.</p>
 */
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface AccountTypeRepository
        extends JpaRepository<AccountType, UUID> {

    /**
     * Finds an active account type by its identifier.
     *
     * @param accountTypeId account-type identifier
     * @return matching active account type, if present
     */
    Optional<AccountType> findByAccountTypeIdAndActiveTrue(
            UUID accountTypeId
    );
}
