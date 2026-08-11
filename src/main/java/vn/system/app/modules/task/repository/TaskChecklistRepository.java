package vn.system.app.modules.task.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import vn.system.app.modules.task.domain.TaskChecklist;

@Repository
public interface TaskChecklistRepository extends JpaRepository<TaskChecklist, Long> {

    List<TaskChecklist> findByTaskIdOrderBySortOrderAscIdAsc(Long taskId);

    List<TaskChecklist> findByTaskIdIn(List<Long> taskIds);

    void deleteByTaskId(Long taskId);
}
