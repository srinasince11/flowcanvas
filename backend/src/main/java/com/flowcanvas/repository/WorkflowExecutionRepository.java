package com.flowcanvas.repository;

import com.flowcanvas.domain.WorkflowExecution;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution, UUID> {

    Page<WorkflowExecution> findByWorkflowIdOrderByStartedAtDesc(UUID workflowId, Pageable pageable);

    List<WorkflowExecution> findTop20ByWorkflowIdOrderByStartedAtDesc(UUID workflowId);

    /** Fails runs that were in flight when the server stopped; returns how many were closed. */
    @Modifying
    @Query("""
            update WorkflowExecution e
               set e.status = 'FAILED', e.error = :reason, e.finishedAt = :now
             where e.status in ('PENDING', 'RUNNING')
            """)
    int failUnfinished(@Param("reason") String reason, @Param("now") Instant now);
}
