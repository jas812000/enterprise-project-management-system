
-- EPMS account type reference data
--
-- Seeds the foundational account classifications.
-- Account types identify the kind of account, not its security role.
-- Security roles and permissions are managed separately.

INSERT INTO account_types (
    account_type_id,
    code,
    display_name,
    description,
    is_active,
    sort_order
)
VALUES
    (
        '0199a000-0000-7000-8000-000000000001',
        'HUMAN',
        'Human User',
        'An individual authorized to use EPMS.',
        TRUE,
        10
    ),
    (
        '0199a000-0000-7000-8000-000000000002',
        'SERVICE',
        'Service Account',
        'An account used by an application or automated process.',
        TRUE,
        20
    ),
    (
        '0199a000-0000-7000-8000-000000000003',
        'SYSTEM',
        'System Account',
        'An account reserved for internal EPMS operations.',
        TRUE,
        30
    );
