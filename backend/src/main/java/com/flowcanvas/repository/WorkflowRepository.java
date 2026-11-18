package com.flowcanvas.repository;

import com.flowcanvas.domain.Workflow;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowRepository extends JpaRepository<Workflow, UUID> {

    Page<Workflow> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    /**
     * Case-insensitive name/description search, paged in the database.
     *
     * <p>{@code pattern} must already be a LIKE pattern with {@code %}, {@code _} and
     * {@code \} escaped (see {@code WorkflowService#likePattern}), so a user searching for
     * "100%" matches that literal text rather than everything.
     */
    @Query(value = """
            select w from Workflow w
            where lower(w.name) like :pattern escape '\\'
               or lower(coalesce(w.description, '')) like :pattern escape '\\'
            order by w.updatedAt desc
            """,
            countQuery = """
            select count(w) from Workflow w
            where lower(w.name) like :pattern escape '\\'
               or lower(coalesce(w.description, '')) like :pattern escape '\\'
            """)
    Page<Workflow> search(@Param("pattern") String pattern, Pageable pageable);
}
