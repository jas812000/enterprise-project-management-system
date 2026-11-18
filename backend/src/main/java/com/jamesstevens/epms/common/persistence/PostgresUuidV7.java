
package com.jamesstevens.epms.common.persistence;

import org.hibernate.annotations.IdGeneratorType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configures an entity identifier to use PostgreSQL-generated UUIDv7 values.
 *
 * <p>This annotation connects Hibernate's identifier-generation
 * infrastructure to {@link PostgresUuidV7Generator}.
 *
 * <p>When an entity annotated with {@code @PostgresUuidV7}
 * is persisted, Hibernate invokes the generator before executing
 * the INSERT statement.
 *
 * <p>The generator obtains the identifier using PostgreSQL's
 * native {@code uuidv7()} function.
 *
 * <p>This annotation is intended for UUID identifier fields
 * in EPMS persistence entities.
 *
 * <p>Example usage:
 * <pre>{@code
 * @Id
 * @PostgresUuidV7
 * @Column(
 *         name = "organization_id",
 *         nullable = false,
 *         updatable = false
 * )
 * private UUID organizationId;
 * }</pre>
 *
 * @see PostgresUuidV7Generator
 */

@IdGeneratorType(PostgresUuidV7Generator.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PostgresUuidV7 {
}
