package com.opsbot.controller;

import com.opsbot.service.RunbookIngestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class IngestionController {

    private final RunbookIngestionService ingestionService;

    public IngestionController(RunbookIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /*
     * POST /api/admin/ingest?directory=/runbooks
     *
     * Triggers runbook ingestion from the given directory path.
     * In production this would be secured behind an admin role.
     * For now it's open so we can test easily.
     *
     * Returns the count of chunks stored.
     */
    @PostMapping("/ingest")
    public Mono<ResponseEntity<Map<String, Object>>> ingest(
            @RequestParam(defaultValue = "/runbooks") String directory) {

        return ingestionService.ingestDirectory(Path.of(directory))
                .map(count -> ResponseEntity.ok(Map.of(
                        "status", "complete",
                        "chunksStored", count,
                        "directory", directory
                )));
    }
}