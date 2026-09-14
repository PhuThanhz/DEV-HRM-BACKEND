package vn.system.app.modules.employeecareerpath.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import vn.system.app.modules.employeecareerpath.domain.EmployeeCareerPath;

@Repository
public interface EmployeeCareerPathRepository
    extends JpaRepository<EmployeeCareerPath, Long> {

  Optional<EmployeeCareerPath> findByUser_IdAndActiveTrue(String userId);

  boolean existsByUser_IdAndActiveTrue(String userId);

  // Lấy theo phòng ban qua template.department
  List<EmployeeCareerPath> findByTemplate_Department_IdAndActiveTrue(Long departmentId);

  // [FIX 2] JOIN FETCH tránh N+1 khi truy cập template.steps. Không fetch join
  // thêm e.histories ở đây vì Hibernate không cho fetch 2 collection (bag) khác
  // nhau trong cùng 1 query — histories dùng @BatchSize(20) trên entity thay thế.
  @Query("""
          SELECT DISTINCT e FROM EmployeeCareerPath e
          JOIN FETCH e.template t
          JOIN FETCH t.steps
          WHERE e.active = true
            AND e.progressStatus = 0
            AND e.stepStartedAt IS NOT NULL
      """)
  List<EmployeeCareerPath> findAllInProgress();

  // Lấy tất cả lộ trình của 1 user (kể cả inactive — xem lịch sử)
  List<EmployeeCareerPath> findByUser_IdOrderByCreatedAtDesc(String userId);

  @Query("""
          SELECT e.user.id FROM EmployeeCareerPath e
          WHERE e.active = true
          AND e.template.department.id = :departmentId
      """)
  List<String> findAssignedUserIdsByDepartmentId(@Param("departmentId") Long departmentId);
}