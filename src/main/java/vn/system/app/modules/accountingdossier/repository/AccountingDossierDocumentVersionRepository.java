package vn.system.app.modules.accountingdossier.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import vn.system.app.modules.accountingdossier.domain.AccountingDossierDocumentVersion;

@Repository
public interface AccountingDossierDocumentVersionRepository extends JpaRepository<AccountingDossierDocumentVersion, Long> {
    long countByDossierDocumentId(Long dossierDocumentId);

    java.util.Optional<AccountingDossierDocumentVersion> findFirstByFileUrl(String fileUrl);
}
