package vn.system.app.modules.jd.jobdescription.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import vn.system.app.modules.jd.jobdescription.domain.JobDescription;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.domain.Specification;

@Repository
public interface JobDescriptionRepository
        extends JpaRepository<JobDescription, Long>,
        JpaSpecificationExecutor<JobDescription> {

    @Override
    @EntityGraph(attributePaths = {
        "company",
        "department",
        "companyJobTitle",
        "departmentJobTitle",
        "departmentJobTitle.jobTitle",
        "sectionJobTitle",
        "requirement"
    })
    Page<JobDescription> findAll(Specification<JobDescription> spec, Pageable pageable);
    
    boolean existsByCode(String code);

    List<JobDescription> findByCreatedBy(String createdBy);

    List<JobDescription> findByStatus(String status);

    List<JobDescription> findByStatusAndCompany_IdIn(String status, List<Long> companyIds);

    List<JobDescription> findByDepartmentId(Long departmentId);

    List<JobDescription> findByCreatedByAndStatus(String createdBy, String status);

    Page<JobDescription> findByCreatedBy(String createdBy, Pageable pageable);

    Page<JobDescription> findByStatus(String status, Pageable pageable);

    @Query("SELECT jd FROM JobDescription jd " +
            "WHERE jd.status = 'REJECTED' " +
            "AND EXISTS (" +
            "  SELECT 1 FROM JdFlowLog l " +
            "  WHERE l.jobDescription = jd " +
            "  AND l.fromUser.id = :userId " +
            "  AND l.action = 'REJECT'" +
            ")")
    Page<JobDescription> findRejectedByUser(
            @Param("userId") String userId, Pageable pageable);

    @Query("""
        SELECT DISTINCT jd FROM JobDescription jd
        LEFT JOIN jd.companyJobTitle cjt
        LEFT JOIN jd.departmentJobTitle djt
        LEFT JOIN jd.sectionJobTitle sjt
        WHERE jd.status = 'PUBLISHED'
        AND (
            (cjt IS NOT NULL AND cjt.id IN :companyJobTitleIds) OR
            (djt IS NOT NULL AND djt.id IN :departmentJobTitleIds) OR
            (sjt IS NOT NULL AND sjt.id IN :sectionJobTitleIds)
        )
    """)
    List<JobDescription> findPublishedJdsByJobTitles(
            @Param("companyJobTitleIds") List<Long> companyJobTitleIds,
            @Param("departmentJobTitleIds") List<Long> departmentJobTitleIds,
            @Param("sectionJobTitleIds") List<Long> sectionJobTitleIds);
}