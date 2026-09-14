package vn.system.app.modules.task.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import vn.system.app.modules.task.domain.Task;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long>, JpaSpecificationExecutor<Task> {

    @Override
    @EntityGraph(attributePaths = {
            "department",
            "department.company",
            "jobDescriptionTask",
            "jobDescriptionTaskItem",
            "kpiGroup"
    })
    List<Task> findAll(Specification<Task> spec, Sort sort);

    @Override
    @EntityGraph(attributePaths = {
            "department",
            "department.company",
            "jobDescriptionTask",
            "jobDescriptionTaskItem",
            "kpiGroup"
    })
    Page<Task> findAll(Specification<Task> spec, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {
            "department",
            "department.company",
            "jobDescriptionTask",
            "jobDescriptionTaskItem",
            "kpiGroup"
    })
    List<Task> findAll(Specification<Task> spec);

    boolean existsByKpiGroupId(Long kpiGroupId);
}
