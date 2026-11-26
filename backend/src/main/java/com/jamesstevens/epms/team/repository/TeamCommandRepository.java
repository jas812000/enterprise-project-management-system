package com.jamesstevens.epms.team.repository;

import com.jamesstevens.epms.team.dto.TeamNameConflict;
import com.jamesstevens.epms.team.dto.TeamProfile;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

/** Fixed commands for trusted services. Actor identity must be resolved by the service.
 * Each database command is atomic on the restricted datasource; do not assume a JPA
 * transaction on the primary datasource also controls this connection.
 */
@Repository
@ConditionalOnProperty(name = "epms.rbac.enabled", havingValue = "true")
public class TeamCommandRepository {
    private final JdbcOperations jdbc;

    public TeamCommandRepository(@Qualifier("rbacJdbcTemplate") JdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    public UUID joinMember(UUID actor, UUID team, UUID user, String reason) {
        return jdbc.queryForObject(
                "SELECT public.epms_team_command_join_member(?, ?, ?, ?)",
                UUID.class, actor, team, user, reason);
    }

    public void leaveMember(UUID actor, UUID team, UUID membership, String endType, String reasonCode, String comment) {
        jdbc.queryForObject(
                "SELECT public.epms_team_command_leave_member(?, ?, ?, ?, ?, ?)",
                Object.class, actor, team, membership, endType, reasonCode, comment);
    }

    public UUID assignPermanent(UUID actor, UUID team, UUID membership, String reason) {
        return jdbc.queryForObject(
                "SELECT public.epms_team_command_assign_permanent(?, ?, ?, ?)",
                UUID.class, actor, team, membership, reason);
    }

    public void endPlanned(UUID actor, UUID team, UUID assignment, UUID replacement, String reason) {
        jdbc.queryForObject(
                "SELECT public.epms_team_command_end_planned(?, ?, ?, ?, ?)",
                Object.class, actor, team, assignment, replacement, reason);
    }

    public UUID designateAlternate(UUID actor, UUID team, UUID membership, short priority, String reason) {
        return jdbc.queryForObject(
                "SELECT public.epms_team_command_designate_alternate(?, ?, ?, ?::smallint, ?)",
                UUID.class, actor, team, membership, priority, reason);
    }

    public void revokeAlternate(UUID actor, UUID team, UUID alternate, String reason) {
        jdbc.queryForObject(
                "SELECT public.epms_team_command_revoke_alternate(?, ?, ?, ?)",
                Object.class, actor, team, alternate, reason);
    }

    public UUID transition(UUID actor, UUID team, String status, String reason) {
        return jdbc.queryForObject(
                "SELECT public.epms_team_command_transition(?, ?, ?, ?)",
                UUID.class, actor, team, status, reason);
    }

    public UUID disableAccount(UUID actor, UUID target, String type, String reason) {
        return jdbc.queryForObject(
                "SELECT public.epms_team_command_disable_account(?, ?, ?, ?)",
                UUID.class, actor, target, type, reason);
    }

    public boolean hasPermission(UUID user, UUID team, String permission) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT public.epms_team_command_has_permission(?, ?, ?)",
                Boolean.class, user, team, permission));
    }

    public UUID emergencyLeadership(UUID actor, UUID team, UUID assignment, String type, String reason) {
        return jdbc.queryForObject(
                "SELECT public.epms_team_command_emergency_leadership(?, ?, ?, ?, ?)",
                UUID.class, actor, team, assignment, type, reason);
    }
    public UUID create(UUID actor, String name, String description, boolean override, String reason) {
        return jdbc.queryForObject(
                "SELECT public.epms_team_command_create(?, ?, ?, ?, ?)",
                UUID.class, actor, name, description, override, reason);
    }

    public Long updateProfile(UUID actor, UUID team, long version, String name,
                              String description, boolean override, String reason) {
        return jdbc.queryForObject(
                "SELECT public.epms_team_command_update_profile(?, ?, ?, ?, ?, ?, ?)",
                Long.class, actor, team, version, name, description, override, reason);
    }

    public List<TeamProfile> profiles(UUID actor, UUID team, int limit, int offset) {
        return jdbc.query("SELECT * FROM public.epms_team_command_profiles(?, ?, ?, ?)",
                (row, index) -> new TeamProfile(row.getObject("team_id", UUID.class),
                        row.getString("name"), row.getString("description"),
                        row.getString("lifecycle_status"), row.getLong("version")),
                actor, team, limit, offset);
    }

    public List<TeamNameConflict> profileConflicts(UUID actor, UUID team, String name) {
        return jdbc.query("SELECT * FROM public.epms_team_command_profile_conflicts(?, ?, ?)",
                (row, index) -> new TeamNameConflict(row.getObject("team_id", UUID.class),
                        row.getString("name")), actor, team, name);
    }
    public String reviewConflicts(UUID actor, UUID team, String name) {
        return jdbc.queryForObject(
                "SELECT public.epms_team_command_review_conflicts(?, ?, ?)",
                String.class, actor, team, name);
    }

    public UUID createReviewed(UUID actor, String name, String description, String reason, String review) {
        return jdbc.queryForObject(
                "SELECT public.epms_team_command_create_reviewed(?, ?, ?, true, ?, ?)",
                UUID.class, actor, name, description, reason, review);
    }

    public Long updateProfileReviewed(UUID actor, UUID team, long version, String name,
                                      String description, String reason, String review) {
        return jdbc.queryForObject(
                "SELECT public.epms_team_command_update_profile_reviewed(?, ?, ?, ?, ?, true, ?, ?)",
                Long.class, actor, team, version, name, description, reason, review);
    }
}
