package vn.system.app.modules.kpigroup.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import vn.system.app.modules.kpigroup.domain.KpiGroup;

@Repository
public interface KpiGroupRepository extends
        JpaRepository<KpiGroup, Long>,
        JpaSpecificationExecutor<KpiGroup> {

    boolean existsByNameAndCompanyId(String name, Long companyId);

    boolean existsByNameAndCompanyIdAndIdNot(String name, Long companyId, Long id);

    List<KpiGroup> findByCompanyIdAndActiveTrue(Long companyId);
}
