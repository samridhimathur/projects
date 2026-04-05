package com.opsbot.service;

import com.opsbot.model.RunbookChunk;
import com.opsbot.repository.RunbookRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/*
 * Reads runbook .txt files from a directory, splits them into chunks
 * by paragraph (blank line = chunk boundary), generates embeddings
 * for each chunk, and stores them in the runbook_chunks table.
 *
 * This service is called ONCE during setup (or via an admin endpoint).
 * It is NOT called on every RCA request.
 */
@Service
public class RunbookIngestionService {

    private static final Logger log = LoggerFactory.getLogger(RunbookIngestionService.class);
    private static final int MIN_CHUNK_LENGTH = 50;  // skip chunks shorter than 50 chars

    private final EmbeddingService embeddingService;
    private final RunbookRepository runbookRepository;

    public RunbookIngestionService(EmbeddingService embeddingService,
                                   RunbookRepository runbookRepository) {
        this.embeddingService = embeddingService;
        this.runbookRepository = runbookRepository;
    }

    /*
     * Ingests all .txt files from the given directory.
     * Returns the total number of chunks stored.
     *
     * Flux.fromIterable(files)          — one file at a time
     * .flatMap(this::ingestFile, 2)     — process 2 files concurrently
     *                                    (limit concurrency to avoid rate limits)
     * .reduce(0, Integer::sum)          — count total chunks ingested
     */
    public Mono<Integer> ingestDirectory(Path directory) {
        try {
            List<Path> files = Files.list(directory)
                    .filter(p -> p.toString().endsWith(".txt"))
                    .collect(Collectors.toList());

            log.info("Found {} runbook files to ingest", files.size());

            return Flux.fromIterable(files)
                    .flatMap(file -> ingestFile(file), 2) // max 2 concurrent files
                    .reduce(0, Integer::sum)
                    .doOnSuccess(total ->
                            log.info("Ingestion complete. Total chunks stored: {}", total));

        } catch (IOException e) {
            log.error("Failed to list directory: {}", directory, e);
            return Mono.error(e);
        }
    }

    /*
     * Ingests a single .txt file.
     *
     * Split strategy: paragraph-based (blank line = boundary).
     * Why paragraphs?
     * - Each paragraph in a runbook typically covers one concept
     * - Smaller than a whole section but larger than a sentence
     * - Natural boundary that humans also use to separate ideas
     *
     * We skip chunks already in the DB (existsBySourceAndChunkIndex)
     * so re-running ingestion is safe — it won't duplicate data.
     */
    public Mono<Integer> ingestFile(Path filePath) {
        String filename = filePath.getFileName().toString();
        log.info("Ingesting: {}", filename);

        try {
            String content = Files.readString(filePath);
            List<String> chunks = splitIntoParagraphs(content);

            log.info("{} → {} chunks", filename, chunks.size());

            return Flux.range(0, chunks.size())
                    .filter(i -> chunks.get(i).length() >= MIN_CHUNK_LENGTH)
                    .filter(i -> !runbookRepository
                            .existsBySourceAndChunkIndex(filename, i)) // skip if exists
                    .flatMap(i -> embedAndSave(filename, i, chunks.get(i)), 3)
                    .reduce(0, (count, saved) -> count + 1)
                    .doOnSuccess(count ->
                            log.info("Stored {} new chunks from {}", count, filename));

        } catch (IOException e) {
            log.error("Failed to read file: {}", filePath, e);
            return Mono.just(0);
        }
    }

    /*
     * Embeds one chunk and saves it to Postgres.
     * subscribeOn(boundedElastic) — JPA save is blocking.
     */
    private Mono<RunbookChunk> embedAndSave(String source, int index, String content) {
        return embeddingService.generateEmbedding(content)
                .flatMap(embedding -> Mono.fromCallable(() -> {
                    RunbookChunk chunk = new RunbookChunk();
                    chunk.setSource(source);
                    chunk.setChunkIndex(index);
                    chunk.setContent(content);
                    chunk.setEmbedding(embedding);
                    return runbookRepository.save(chunk);
                }).subscribeOn(Schedulers.boundedElastic()))
                .doOnSuccess(c ->
                        log.debug("Saved chunk {}/{}", source, index))
                .doOnError(e ->
                        log.error("Failed to save chunk {}/{}: {}", source, index, e.getMessage()));
    }

    /*
     * Splits text into paragraphs by blank lines.
     *
     * "paragraph 1\n\nparagraph 2\n\nparagraph 3"
     *  → ["paragraph 1", "paragraph 2", "paragraph 3"]
     *
     * We also trim whitespace and filter empty strings from
     * multiple consecutive blank lines.
     */
    private List<String> splitIntoParagraphs(String content) {
        return Arrays.stream(content.split("\n\n+"))  // split on one or more blank lines
                .map(String::trim)
                .filter(chunk -> !chunk.isEmpty())
                .collect(Collectors.toList());
    }
}