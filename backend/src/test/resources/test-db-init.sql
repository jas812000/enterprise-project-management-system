-- Initialize PostgreSQL roles required by EPMS integration tests.
--
-- Testcontainers executes this script before Spring Boot starts.
-- The epms_app role is required by Flyway V3 for privilege grants.
--
-- NOLOGIN prevents direct database authentication using this role.
-- The Testcontainers database user remains responsible for migrations.

CREATE ROLE epms_app NOLOGIN;

-- V11 requires a separately provisioned, non-login security administration role.
-- Testcontainers executes this before Flyway; this is test-only provisioning.
CREATE ROLE epms_security_admin NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;

-- Dedicated RBAC function owner and caller for isolated integration tests.
-- Neither role inherits epms_security_admin maintenance-function privileges.
CREATE ROLE epms_rbac_function_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
CREATE ROLE epms_rbac_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

-- Dedicated one-time bootstrap roles for isolated PostgreSQL integration tests.
-- The owner cannot log in; the runtime has no inherited privileges.
CREATE ROLE epms_bootstrap_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
CREATE ROLE epms_bootstrap_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE epms_bootstrap_runtime PASSWORD 'bootstrap-test-only-local-container-password';

-- Session-B2B: dedicated least-privilege function owner (test containers only).
CREATE ROLE epms_auth_function_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
