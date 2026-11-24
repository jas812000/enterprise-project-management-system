package com.jamesstevens.epms.security.rbac;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import static org.junit.jupiter.api.Assertions.*;

/** Contract tests for the current restricted JdbcTemplate configuration. */
class RbacDatasourceBoundaryTest {
    private final RbacJdbcConfig config = new RbacJdbcConfig();

    @Test
    void rejectsApplicationIdentityBeforeConnecting() {
        assertThrows(IllegalStateException.class, () ->
                config.rbacJdbcTemplate(
                        "jdbc:" + "postgresql://127.0.0.1:1/unused",
                        "epms_app",
                        "dummy"));
    }

    @Test
    void rejectsMigratorIdentityBeforeConnecting() {
        assertThrows(IllegalStateException.class, () ->
                config.rbacJdbcTemplate(
                        "jdbc:" + "postgresql://127.0.0.1:1/unused",
                        "epms_migrator",
                        "dummy"));
    }

    @Test void restrictedTemplateIsTheOnlyBean() throws Exception {
        Method method = RbacJdbcConfig.class.getDeclaredMethod(
                "rbacJdbcTemplate", String.class, String.class, String.class);
        Bean bean = method.getAnnotation(Bean.class);
        assertNotNull(bean);
        assertArrayEquals(new String[]{"rbacJdbcTemplate"}, bean.name());
        assertEquals(org.springframework.jdbc.core.JdbcTemplate.class, method.getReturnType());
        assertEquals(1, java.util.Arrays.stream(RbacJdbcConfig.class.getDeclaredMethods())
                .filter(candidate -> candidate.isAnnotationPresent(Bean.class)).count());
        ConditionalOnProperty condition = RbacJdbcConfig.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition);
        assertArrayEquals(new String[]{"epms.rbac.enabled"}, condition.name());
        assertEquals("true", condition.havingValue());
    }
}
