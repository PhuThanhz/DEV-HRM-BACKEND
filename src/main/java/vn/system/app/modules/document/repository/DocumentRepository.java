package vn.system.app.modules.document.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import vn.system.app.modules.document.domain.Document;

@Repository
public interface DocumentRepository extends
        JpaRepository<Document, Long>,
        JpaSpecificationExecutor<Document> {

    @Override
    @EntityGraph(attributePaths = {"category", "accountingCategory", "department", "department.company", "section", "folder"})
    Page<Document> findAll(Specification<Document> spec, Pageable pageable);

    boolean existsByDocumentCode(String documentCode);

    boolean existsByDocumentCodeAndIdNot(String documentCode, Long id);

    @Query("SELECT d FROM Document d WHERE d.department.company.id = :companyId")
    List<Document> findByCompanyId(@Param("companyId") Long companyId);

    @Query("SELECT d.documentCode FROM Document d WHERE d.documentCode LIKE %:suffix")
    List<String> findDocumentCodesBySuffix(@Param("suffix") String suffix);

    List<Document> findByDepartment_Id(Long departmentId);

    @Query("SELECT DISTINCT d FROM Document d LEFT JOIN d.departments dd " +
            "WHERE d.department.id = :deptId OR dd.id = :deptId")
    List<Document> findByDepartmentIdIncludingMapped(@Param("deptId") Long deptId);

    List<Document> findBySection_Id(Long sectionId);

    List<Document> findByCategory_Id(Long categoryId);

    boolean existsByCategory_Id(Long categoryId);

    boolean existsByAccountingCategory_Id(Long accountingCategoryId);

    List<Document> findByFolder_Id(Long folderId);

    Optional<Document> findByQrToken(String qrToken);

    Optional<Document> findFirstByFileUrlsContaining(String fileName);
}
