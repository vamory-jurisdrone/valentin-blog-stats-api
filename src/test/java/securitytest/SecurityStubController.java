package securitytest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller factice monté sur les vraies routes pour SecurityConfigTest.
 * Hors du package com.blog.stats pour ne jamais être scanné par l'application.
 */
@RestController
public class SecurityStubController {

    @PostMapping({"/api/events/view", "/api/events/read"})
    ResponseEntity<Void> event() {
        return ResponseEntity.accepted().build();
    }

    @GetMapping({"/api/stats/top", "/api/stats/trends", "/api/stats/articles/{id}"})
    String stats() {
        return "{}";
    }

    @PutMapping("/api/articles/{id}")
    ResponseEntity<Void> sync(@PathVariable Long id) {
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/articles/{id}")
    ResponseEntity<Void> remove(@PathVariable Long id) {
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/actuator/health")
    String health() {
        return "{\"status\":\"UP\"}";
    }

    @GetMapping("/actuator/metrics")
    String metrics() {
        return "{}";
    }

    @GetMapping("/internal/secret")
    String secret() {
        return "secret";
    }
}
