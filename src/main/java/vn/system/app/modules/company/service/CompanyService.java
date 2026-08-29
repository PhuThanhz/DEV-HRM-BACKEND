package vn.system.app.modules.company.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.ScopeSpec;
import vn.system.app.common.util.UserScopeContext;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.common.util.error.PermissionException;
import vn.system.app.modules.company.domain.Company;
import vn.system.app.modules.company.domain.request.ReqCreateCompanyDTO;
import vn.system.app.modules.company.domain.request.ReqUpdateCompanyDTO;
import vn.system.app.modules.company.domain.response.ResCompanyDTO;
import vn.system.app.modules.company.domain.response.ResCreateCompanyDTO;
import vn.system.app.modules.company.domain.response.ResUpdateCompanyDTO;
import vn.system.app.modules.company.repository.CompanyRepository;

@Service
public class CompanyService {

    private final CompanyRepository companyRepository;

    public CompanyService(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
    }

    private void checkCompanyScope(Long companyId) {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope != null && !scope.isAdminLevel()) {
            if (!scope.companyIds().contains(companyId)) {
                throw new PermissionException("Bạn không có quyền thao tác trên công ty này");
            }
        }
    }

    /* ================= CREATE ================= */

    @Transactional
    public Company handleCreateCompany(Company company) {
        if (isCodeExist(company.getCode())) {
            throw new IdInvalidException("Mã công ty " + company.getCode() + " đã tồn tại");
        }
        try {
            return companyRepository.save(company);
        } catch (DataIntegrityViolationException e) {
            throw new IdInvalidException("Mã công ty " + company.getCode() + " đã tồn tại");
        }
    }

    /* ================= UPDATE ================= */

    @Transactional
    public Company handleUpdateCompany(ReqUpdateCompanyDTO req) {
        Company current = fetchEntityById(req.getId());
        checkCompanyScope(current.getId());
        current.setName(req.getName());
        current.setEnglishName(req.getEnglishName());
        return companyRepository.save(current);
    }

    /* ================= INACTIVE (SOFT DELETE) ================= */

    @Transactional
    public void handleInactiveCompany(Long id) {
        Company company = fetchEntityById(id);
        checkCompanyScope(company.getId());
        company.setStatus(0);
        companyRepository.save(company);
    }

    /* ================= ACTIVE ================= */

    @Transactional
    public void handleActiveCompany(Long id) {
        Company company = fetchEntityById(id);
        checkCompanyScope(company.getId());
        company.setStatus(1);
        companyRepository.save(company);
    }

    /* ================= FETCH ENTITY ================= */

    public Company fetchEntityById(Long id) {
        return companyRepository.findById(id)
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy công ty"));
    }

    public Company fetchEntityByIdWithScopeCheck(Long id) {
        Company company = fetchEntityById(id);
        checkCompanyScope(company.getId());
        return company;
    }

    /* ================= FETCH ALL ================= */

    public ResultPaginationDTO fetchAllCompany(
            Specification<Company> spec,
            Pageable pageable) {
        // ── ADMIN_SUB_2: chỉ thấy công ty của mình ────────────
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope != null && !scope.isAdminLevel()) {
            Specification<Company> scopeSpec = ScopeSpec.byCompanyScope("id");
            spec = Specification.where(spec).and(scopeSpec);
        }
        // ── HẾT FILTER ────────────────────────────────────────
        Page<Company> page = companyRepository.findAll(spec, pageable);

        ResultPaginationDTO rs = new ResultPaginationDTO();
        ResultPaginationDTO.Meta meta = new ResultPaginationDTO.Meta();

        meta.setPage(pageable.getPageNumber() + 1);
        meta.setPageSize(pageable.getPageSize());
        meta.setPages(page.getTotalPages());
        meta.setTotal(page.getTotalElements());

        rs.setMeta(meta);

        List<ResCompanyDTO> list = page.getContent()
                .stream()
                .map(this::convertToResCompanyDTO)
                .collect(Collectors.toList());

        rs.setResult(list);
        return rs;
    }

    /* ================= CHECK ================= */

    public boolean isCodeExist(String code) {
        return companyRepository.existsByCodeIgnoreCase(code);
    }

    /* ================= CONVERT ================= */

    public Company convertCreateReqToEntity(ReqCreateCompanyDTO req) {
        Company c = new Company();
        c.setCode(req.getCode());
        c.setName(req.getName());
        c.setEnglishName(req.getEnglishName());
        return c;
    }

    public ResCreateCompanyDTO convertToResCreateCompanyDTO(Company c) {
        ResCreateCompanyDTO res = new ResCreateCompanyDTO();
        res.setId(c.getId());
        res.setCode(c.getCode());
        res.setName(c.getName());
        res.setEnglishName(c.getEnglishName());
        res.setCreatedAt(c.getCreatedAt());
        return res;
    }

    public ResUpdateCompanyDTO convertToResUpdateCompanyDTO(Company c) {
        ResUpdateCompanyDTO res = new ResUpdateCompanyDTO();
        res.setId(c.getId());
        res.setName(c.getName());
        res.setEnglishName(c.getEnglishName());
        res.setUpdatedAt(c.getUpdatedAt());
        return res;
    }

    public ResCompanyDTO convertToResCompanyDTO(Company c) {
        ResCompanyDTO res = new ResCompanyDTO();
        res.setId(c.getId());
        res.setCode(c.getCode());
        res.setName(c.getName());
        res.setEnglishName(c.getEnglishName());
        res.setStatus(c.getStatus());
        res.setCreatedAt(c.getCreatedAt());
        res.setUpdatedAt(c.getUpdatedAt());
        return res;
    }
}
