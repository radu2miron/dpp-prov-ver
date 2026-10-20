package edu.tucn.dppprovver.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@Component // creates a JwtAuthorizationFilter instance
public class JwtAuthorizationFilter extends OncePerRequestFilter {
    @Value("${authorization.enabled}")
    private Boolean authorizationEnabled;

    // the following resources don't need to be authorized
    private static final String[] AUTH_EXCEPTIONS = {
            // login endpoint
            "POST /login",

            // swagger URLs
            "GET /swagger-ui/",
            "GET /v3/api-docs",
    };

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        //get the Authorization header from request
        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || authHeader.trim().length() < 7) { // null & empty check on Authorization header
            response.sendError(403); // return a 403-Forbidden error if the header is null
            return;
        }

        //remove 'Bearer' from the token header
        String jwtToken = authHeader.substring(7);

        try {
            JwtUtil.validateToken(jwtToken); // verify that the JWT token is valid
        } catch (JwtException e) {
            response.sendError(403); // return a 403-Forbidden error if the token is invalid
            return;
        }

        filterChain.doFilter(request, response);
    }

    // exclude the URL exceptions from the authorization filtering
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUrl = request.getRequestURI();
        String requestMethod = request.getMethod();

        if (Boolean.TRUE.equals(authorizationEnabled)) {
            return (requestUrl.equals("/") && requestMethod.equals("GET"))
                    || Arrays.stream(AUTH_EXCEPTIONS).anyMatch((requestMethod + " " + requestUrl)::startsWith);
        } else {
            return true;
        }
    }
}
