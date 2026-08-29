package vn.system.app.modules.company.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import vn.system.app.modules.company.domain.Company;

@Repository
public interface CompanyRepository
        extends JpaRepository<Company, Long>,
        JpaSpecificationExecutor<Company> {

    boolean existsByCode(String code);

    boolean existsByCodeIgnoreCase(String code);

    long countByIdIn(List<Long> ids);

}
