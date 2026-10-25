package com.clinicflow.web.api;

import com.clinicflow.web.dto.Dto;
import com.clinicflow.web.error.BadCredentialsApiException;
import com.clinicflow.web.security.CurrentActor;
import com.clinicflow.web.security.JwtService;
import com.clinicflow.web.security.StaffPrincipal;
import com.clinicflow.web.security.StaffUserRepository;
import jakarta.validation.Valid;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final StaffUserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthController(StaffUserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    /** Unsafe, not idempotent. The same message for "no such user" and "wrong password" on purpose. */
    @PostMapping("/login")
    public Dto.LoginResponse login(@Valid @RequestBody Dto.LoginRequest req) {
        StaffUserRepository.Row u = users.findByEmail(req.email().trim())
                .filter(StaffUserRepository.Row::active)
                .filter(r -> encoder.matches(req.password(), r.passwordHash()))
                .orElseThrow(BadCredentialsApiException::new);
        StaffPrincipal p = new StaffPrincipal(u.id(), u.displayName(), u.role());
        return new Dto.LoginResponse(jwt.issue(p), "Bearer", jwt.ttlSeconds(), new Dto.StaffUser(p.id(), p.displayName(), p.role()));
    }

    @GetMapping("/me")
    public Dto.StaffUser me() {
        StaffPrincipal p = CurrentActor.principal();
        return new Dto.StaffUser(p.id(), p.displayName(), p.role());
    }
}
