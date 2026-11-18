package com.flowcanvas.repository;

import com.flowcanvas.domain.WorkflowNode;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface WorkflowNodeRepository extends JpaRepository<WorkflowNode, UUID> {

    /**
     * Nodes of a workflow, ordered by their canvas position.
     *
     * <p>The ordering matters: it makes the response deterministic and matches the visual
     * left-to-right layout of the canvas.
     */
    @Query("select n from WorkflowNode n where n.workflowId = :workflowId "
            + "order by n.positionX asc, n.positionY asc")
    List<WorkflowNode> findByWorkflowId(@Param("workflowId") UUID workflowId);

    @Modifying
    @Query("delete from WorkflowNode n where n.workflowId = :workflowId")
    void deleteByWorkflowId(@Param("workflowId") UUID workflowId);

    /** Node counts for a page of workflows in one round trip: rows of {@code [workflowId, count]}. */
    @Query("select n.workflowId, count(n) from WorkflowNode n "
            + "where n.workflowId in :workflowIds group by n.workflowId")
    List<Object[]> countByWorkflowIds(@Param("workflowIds") Collection<UUID> workflowIds);
}
