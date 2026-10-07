package com.example.routermanager.api;

import com.example.routermanager.common.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class AppAuthFilter extends OncePerRequestFilter {
    private final AppJwt jwt;
    private final AppUserRepository users;
    private final ObjectMapper mapper;

    public AppAuthFilter(AppJwt jwt, AppUserRepository users, ObjectMapper mapper) {
        this.jwt = jwt;
        this.users = users;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || ("POST".equals(request.getMethod()) &&
                (path.equals("/api/auth/login") || path.equals("/api/auth/setup")))) {
            chain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader("Authorization");
        String username = authorization != null && authorization.startsWith("Bearer ")
                ? jwt.verify(authorization.substring(7)).orElse(null) : null;
        if (username == null || !users.existsByUsername(username)) {
            response.setStatus(401);
            response.setContentType("application/json");
            mapper.writeValue(response.getOutputStream(), ApiError.of(401, "Unauthorized",
                    "Authentication required", path));
            return;
        }
        request.setAttribute("appUsername", username);
        chain.doFilter(request, response);
    }
}
