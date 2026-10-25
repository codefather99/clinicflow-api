package com.clinicflow.web.security;

import com.clinicflow.domain.port.Actor;
import com.clinicflow.web.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Locale;

/** Builds the port-level Actor from the signed-in staff member. Deji's code never sees a token. */
public final class CurrentActor {
    private CurrentActor() {}

    public static StaffPrincipal principal() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !(a.getPrincipal() instanceof StaffPrincipal p))
            throw new IllegalStateException("No staff principal on a staff route");
        return p;
    }

    public static Actor staff() {
        StaffPrincipal p = principal();
        return new Actor(p.displayName() + " (" + p.role().name().toLowerCase(Locale.ROOT) + ")", p.role(),
                MDC.get(RequestIdFilter.MDC_KEY));
    }

    public static Actor patient() { return Actor.patient(MDC.get(RequestIdFilter.MDC_KEY)); }
}
