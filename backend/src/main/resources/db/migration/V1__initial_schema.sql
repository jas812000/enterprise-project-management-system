
-- EPMS foundational schema
-- PostgreSQL 18+ required for native uuidv7().
--
-- Creates foundational account type, organization, department, and user tables.
-- Each EPMS installation supports one organization with multiple departments,
-- users, teams, projects, and tasks.
--
-- Organization ownership is enforced through foreign keys and composite
-- foreign keys where applicable. Additional business rules are validated
-- transactionally by the application services.
--
-- Reference-catalog seed data is managed separately.

-- Account types
CREATE TABLE account_types (
                               account_type_id UUID PRIMARY KEY DEFAULT uuidv7(),
                               code VARCHAR(50) NOT NULL,
                               display_name VARCHAR(100) NOT NULL,
                               description TEXT,
                               is_active BOOLEAN NOT NULL DEFAULT TRUE,
                               sort_order INTEGER NOT NULL,
                               CONSTRAINT ck_account_types_code_nonblank
                                   CHECK (btrim(code) <> ''),
                               CONSTRAINT ck_account_types_display_name_nonblank
                                   CHECK (btrim(display_name) <> '')
);

CREATE UNIQUE INDEX ux_account_types_code_ci
    ON account_types (lower(code));


-- Organizations
CREATE TABLE organizations (
                               organization_id UUID PRIMARY KEY DEFAULT uuidv7(),
                               created_by_user_id UUID,
                               updated_by_user_id UUID,
                               name VARCHAR(200) NOT NULL,
                               description TEXT,
                               is_active BOOLEAN NOT NULL DEFAULT TRUE,
                               created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                               updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                               version BIGINT NOT NULL DEFAULT 0,
                               CONSTRAINT ck_organizations_name_nonblank
                                   CHECK (btrim(name) <> '')
);

-- An EPMS installation may contain only one organization.
-- The organization is created during initial application setup.
CREATE UNIQUE INDEX ux_organizations_single_instance
    ON organizations ((1));


-- Departments
CREATE TABLE departments (
                             department_id UUID PRIMARY KEY DEFAULT uuidv7(),
                             organization_id UUID NOT NULL
                                 REFERENCES organizations(organization_id),
                             created_by_user_id UUID,
                             updated_by_user_id UUID,
                             archived_by_user_id UUID,
                             name VARCHAR(200) NOT NULL,
                             description TEXT,
                             is_active BOOLEAN NOT NULL DEFAULT TRUE,
                             is_archived BOOLEAN NOT NULL DEFAULT FALSE,
                             archived_at TIMESTAMPTZ,
                             archive_reason TEXT,
                             created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                             updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                             version BIGINT NOT NULL DEFAULT 0,
                             CONSTRAINT ck_departments_name_nonblank
                                 CHECK (btrim(name) <> ''),
                             CONSTRAINT uq_departments_org_department
                                 UNIQUE (organization_id, department_id)
);

CREATE UNIQUE INDEX ux_departments_org_name_ci
    ON departments (organization_id, lower(name));


-- Users
CREATE TABLE users (
                       user_id UUID PRIMARY KEY DEFAULT uuidv7(),
                       organization_id UUID NOT NULL
                           REFERENCES organizations(organization_id),
                       department_id UUID,
                       supervisor_user_id UUID,
                       account_type_id UUID NOT NULL
                           REFERENCES account_types(account_type_id),
                       owner_user_id UUID,
                       backup_owner_user_id UUID,
                       created_by_user_id UUID,
                       updated_by_user_id UUID,
                       archived_by_user_id UUID,
                       reviewed_by_user_id UUID,
                       employee_number VARCHAR(50),
                       first_name VARCHAR(100) NOT NULL,
                       last_name VARCHAR(100) NOT NULL,
                       email VARCHAR(320) NOT NULL,
                       pending_email VARCHAR(320),
                       is_active BOOLEAN NOT NULL DEFAULT TRUE,
                       is_archived BOOLEAN NOT NULL DEFAULT FALSE,
                       archived_at TIMESTAMPTZ,
                       archive_reason TEXT,
                       last_used_at TIMESTAMPTZ,
                       last_reviewed_at TIMESTAMPTZ,
                       next_review_due_at TIMESTAMPTZ,
                       created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                       updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                       version BIGINT NOT NULL DEFAULT 0,
                       CONSTRAINT ck_users_first_name_nonblank
                           CHECK (btrim(first_name) <> ''),
                       CONSTRAINT ck_users_last_name_nonblank
                           CHECK (btrim(last_name) <> ''),
                       CONSTRAINT ck_users_email_nonblank
                           CHECK (btrim(email) <> ''),
                       CONSTRAINT uq_users_org_user
                           UNIQUE (organization_id, user_id),
                       CONSTRAINT fk_users_department_same_org
                           FOREIGN KEY (organization_id, department_id)
                               REFERENCES departments (organization_id, department_id),
                       CONSTRAINT fk_users_supervisor_same_org
                           FOREIGN KEY (organization_id, supervisor_user_id)
                               REFERENCES users (organization_id, user_id),
                       CONSTRAINT fk_users_owner_same_org
                           FOREIGN KEY (organization_id, owner_user_id)
                               REFERENCES users (organization_id, user_id),
                       CONSTRAINT fk_users_backup_owner_same_org
                           FOREIGN KEY (organization_id, backup_owner_user_id)
                               REFERENCES users (organization_id, user_id),
                       CONSTRAINT fk_users_created_by_same_org
                           FOREIGN KEY (organization_id, created_by_user_id)
                               REFERENCES users (organization_id, user_id),
                       CONSTRAINT fk_users_updated_by_same_org
                           FOREIGN KEY (organization_id, updated_by_user_id)
                               REFERENCES users (organization_id, user_id),
                       CONSTRAINT fk_users_archived_by_same_org
                           FOREIGN KEY (organization_id, archived_by_user_id)
                               REFERENCES users (organization_id, user_id),
                       CONSTRAINT fk_users_reviewed_by_same_org
                           FOREIGN KEY (organization_id, reviewed_by_user_id)
                               REFERENCES users (organization_id, user_id)
);

CREATE UNIQUE INDEX ux_users_org_email_ci
    ON users (organization_id, lower(email));


-- Deferred foreign keys
-- These relationships reference users, which must exist before the
-- constraints can be declared.

ALTER TABLE organizations
    ADD CONSTRAINT fk_organizations_created_by_same_org
        FOREIGN KEY (organization_id, created_by_user_id)
            REFERENCES users (organization_id, user_id),
    ADD CONSTRAINT fk_organizations_updated_by_same_org
        FOREIGN KEY (organization_id, updated_by_user_id)
        REFERENCES users (organization_id, user_id);

ALTER TABLE departments
    ADD CONSTRAINT fk_departments_created_by_same_org
        FOREIGN KEY (organization_id, created_by_user_id)
            REFERENCES users (organization_id, user_id),
    ADD CONSTRAINT fk_departments_updated_by_same_org
        FOREIGN KEY (organization_id, updated_by_user_id)
        REFERENCES users (organization_id, user_id),
    ADD CONSTRAINT fk_departments_archived_by_same_org
        FOREIGN KEY (organization_id, archived_by_user_id)
        REFERENCES users (organization_id, user_id);
