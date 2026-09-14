package vn.system.app.modules.accountingdossier.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import vn.system.app.modules.accountingdossier.domain.AccountingDossierDocument;
import vn.system.app.modules.accountingdossier.domain.enums.AccountingDossierStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

@Repository
public interface AccountingDossierDocumentRepository extends JpaRepository<AccountingDossierDocument, Long>,
        JpaSpecificationExecutor<AccountingDossierDocument> {

    @Override
    @EntityGraph(attributePaths = {"dossier", "dossier.company", "dossier.department", "accountingCategory", "document"})
    Page<AccountingDossierDocument> findAll(Specification<AccountingDossierDocument> spec, Pageable pageable);
    
    List<AccountingDossierDocument> findByDossierId(Long dossierId);

    List<AccountingDossierDocument> findByDossierIdAndActiveTrue(Long dossierId);

    List<AccountingDossierDocument> findByDossierIdAndActiveTrueOrderByCreatedAtDescIdDesc(Long dossierId);
    
    long countByDossierId(Long dossierId);

    long countByDossierIdAndActiveTrue(Long dossierId);

    void deleteByDossierId(Long dossierId);

    List<AccountingDossierDocument> findByIdInAndDossierIdAndActiveTrue(List<Long> ids, Long dossierId);

    java.util.Optional<AccountingDossierDocument> findFirstByFileUrl(String fileUrl);

    @Query("""
        SELECT d FROM AccountingDossierDocument d
        WHERE d.active = true
          AND LOWER(TRIM(d.invoiceNumber)) = LOWER(TRIM(:invoiceNumber))
          AND LOWER(TRIM(COALESCE(d.partnerName, ''))) = LOWER(TRIM(:partnerName))
          AND d.dossier.id <> :currentDossierId
          AND d.dossier.status IN :statuses
    """)
    List<AccountingDossierDocument> findDuplicateInvoices(
        @Param("invoiceNumber") String invoiceNumber,
        @Param("partnerName") String partnerName,
        @Param("currentDossierId") Long currentDossierId,
        @Param("statuses") Collection<AccountingDossierStatus> statuses
    );
}
