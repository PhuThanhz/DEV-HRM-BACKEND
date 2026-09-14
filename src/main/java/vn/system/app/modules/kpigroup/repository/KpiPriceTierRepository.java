package vn.system.app.modules.kpigroup.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import vn.system.app.modules.kpigroup.domain.KpiPriceTier;

@Repository
public interface KpiPriceTierRepository extends JpaRepository<KpiPriceTier, Long> {

    List<KpiPriceTier> findByKpiGroupIdOrderByTierOrderAsc(Long kpiGroupId);

    void deleteByKpiGroupId(Long kpiGroupId);
}
