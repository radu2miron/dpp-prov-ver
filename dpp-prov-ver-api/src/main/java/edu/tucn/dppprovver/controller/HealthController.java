package edu.tucn.dppprovver.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@RestController
@RequestMapping("/api")
public class HealthController {
    @GetMapping("/health")
    public Map<String, String> health() {
        // HTTP server health, NOT a connectivity check to the Fabric network.
        return Map.of("status", "UP", "fabricConnectivity", "NOT_CHECKED");
    }
}
