package com.paycore.fraudservice.controller;

import com.paycore.fraudservice.domain.entity.BlacklistEntry;
import com.paycore.fraudservice.domain.enums.AddedBy;
import com.paycore.fraudservice.domain.enums.EntityType;
import com.paycore.fraudservice.service.BlacklistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/internal/v1/fraud/blacklist")
@RequiredArgsConstructor
@Tag(name = "Blacklist Management APIs", description = "Admin endpoints for blacklist lookup, addition, and revocation")
public class BlacklistController {

    private final BlacklistService blacklistService;

    @GetMapping
    @Operation(summary = "Get active blacklist entries", description = "Lists all active blacklist rules across account, device, and IP")
    public ResponseEntity<List<BlacklistEntry>> getBlacklist() {
        return ResponseEntity.ok(blacklistService.getActiveBlacklist());
    }

    @PostMapping
    @Operation(summary = "Add blacklist entry", description = "Adds a new entity to the blacklist and synchronizes immediately with Redis Set")
    public ResponseEntity<BlacklistEntry> addBlacklistEntry(@RequestBody AddBlacklistRequest request) {
        AddedBy addedBy = request.getAddedBy() != null ? request.getAddedBy() : AddedBy.ADMIN_MANUAL;
        BlacklistEntry entry = blacklistService.addEntry(
                request.getEntityType(),
                request.getEntityValue(),
                request.getReason(),
                addedBy,
                request.getExpiresAt()
        );
        return ResponseEntity.ok(entry);
    }

    @DeleteMapping("/{type}/{value}")
    @Operation(summary = "Remove blacklist entry", description = "Deactivates blacklist entry and purges from Redis Set")
    public ResponseEntity<Void> removeBlacklistEntry(
            @PathVariable("type") EntityType type,
            @PathVariable("value") String value
    ) {
        blacklistService.removeEntry(type, value);
        return ResponseEntity.noContent().build();
    }

    @Data
    public static class AddBlacklistRequest {
        private EntityType entityType;
        private String entityValue;
        private String reason;
        private AddedBy addedBy;
        private Instant expiresAt;
    }
}
