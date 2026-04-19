package com.opsbot.model;

import java.time.OffsetDateTime;

public interface RunbookViewProjection {

    Long getId();
    String getSource();          // filename e.g. "high-memory-runbook.txt"
    Integer getChunkIndex();     // position within the file (0, 1, 2...)
    String getContent();         // raw text of this chunk
    OffsetDateTime getCreatedAt();
}
