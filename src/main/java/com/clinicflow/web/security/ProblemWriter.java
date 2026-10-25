package com.clinicflow.web.security;

import com.clinicflow.web.Json;
import com.clinicflow.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Writes the contract's Problem shape for errors raised by Spring Security, before any controller runs. */
@Component
public class ProblemWriter {

    public void unauthenticated(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex) throws IOException {
        write(res, 401, "Not signed in", "UNAUTHENTICATED", "Sign in to continue.");
    }

    public void forbidden(HttpServletRequest req, HttpServletResponse res, AccessDeniedException ex) throws IOException {
        write(res, 403, "Not allowed", "FORBIDDEN", "Your role does not allow this action.");
    }

    private void write(HttpServletResponse res, int status, String title, String code, String detail) throws IOException {
        String rid = MDC.get(RequestIdFilter.MDC_KEY);
        res.setStatus(status);
        res.setContentType("application/problem+json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"type\":\"about:blank\",\"title\":" + Json.quote(title) + ",\"status\":" + status
                + ",\"detail\":" + Json.quote(detail) + ",\"code\":" + Json.quote(code)
                + ",\"requestId\":" + Json.quote(rid) + "}");
    }
}
