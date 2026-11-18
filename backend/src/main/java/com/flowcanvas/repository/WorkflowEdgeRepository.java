package com.flowcanvas.repository;

import com.flowcanvas.domain.WorkflowEdge;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowEdgeRepository extends JpaRepository<WorkflowEdge, UUID> {

    List<WorkflowEdge> findByWorkflowId(UUID workflowId);

    @Modifying
    @Query("delete from WorkflowEdge e where e.workflowId = :workflowId")
    void deleteByWorkflowId(@Param("workflowId") UUID workflowId);

    /** Edge counts for a page of workflows in one round trip: rows of {@code [workflowId, count]}. */
    @Query("select e.workflowId, count(e) from WorkflowEdge e "
            + "where e.workflowId in :workflowIds group by e.workflowId")
    List<Object[]> countByWorkflowIds(@Param("workflowIds") Collection<UUID> workflowIds);
}
