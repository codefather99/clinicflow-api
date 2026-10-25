package com.clinicflow.web.security;

import com.clinicflow.domain.port.ActorRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Henry's table (V100). Plain SQL; nothing here touches Deji's tables. */
@Repository
public class StaffUserRepository {
    public record Row(UUID id, String email, String passwordHash, String displayName, ActorRole role, boolean active) {}

    private final JdbcTemplate jdbc;

    public StaffUserRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Row> findByEmail(String email) {
        return jdbc.query("SELECT id, email, password_hash, display_name, role, active FROM staff_user WHERE lower(email) = lower(?)",
                        (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("password_hash"),
                                rs.getString("display_name"), ActorRole.valueOf(rs.getString("role")), rs.getBoolean("active")),
                        email)
                .stream().findFirst();
    }

    /** @return true if a row was inserted */
    public boolean insertIfMissing(String email, String passwordHash, String displayName, ActorRole role) {
        return jdbc.update("INSERT INTO staff_user (email, password_hash, display_name, role) VALUES (?, ?, ?, ?) ON CONFLICT (email) DO NOTHING",
                email, passwordHash, displayName, role.name()) == 1;
    }
}
