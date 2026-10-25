package com.clinicflow.web.security;

import com.clinicflow.domain.port.ActorRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** DEMONSTRATION ONLY. Creates the two demo staff accounts listed in the README if they do not exist. */
@Component
public class DemoStaffSeeder implements ApplicationRunner {
    private final StaffUserRepository repo;
    private final PasswordEncoder encoder;
    private final String receptionPassword;
    private final String adminPassword;

    public DemoStaffSeeder(StaffUserRepository repo, PasswordEncoder encoder,
                           @Value("${clinicflow.demo.reception-password}") String receptionPassword,
                           @Value("${clinicflow.demo.admin-password}") String adminPassword) {
        this.repo = repo;
        this.encoder = encoder;
        this.receptionPassword = receptionPassword;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        repo.insertIfMissing("reception@clinicflow.demo", encoder.encode(receptionPassword), "Grace Okoro", ActorRole.RECEPTIONIST);
        repo.insertIfMissing("admin@clinicflow.demo", encoder.encode(adminPassword), "Admin Demo", ActorRole.ADMIN);
    }
}
