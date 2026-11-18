package com.flowcanvas.repository;

import com.flowcanvas.domain.NodeExecution;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NodeExecutionRepository extends JpaRepository<NodeExecution, UUID> {

    List<NodeExecution> findByExecutionIdOrderBySequenceAsc(UUID executionId);

    /** Closes out node records that were still pending or running when the server stopped. */
    @Modifying
    @Query("""
            update NodeExecution n
               set n.status = 'SKIPPED', n.error = :reason, n.finishedAt = :now
             where n.status in ('PENDING', 'RUNNING')
            """)
    int skipUnfinished(@Param("reason") String reason, @Param("now") Instant now);
}
