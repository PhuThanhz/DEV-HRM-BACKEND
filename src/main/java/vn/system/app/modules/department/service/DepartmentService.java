package vn.system.app.modules.department.service;

import java.util.List;
import java.util.stream.Collectors;

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
import vn.system.app.modules.company.service.CompanyService;
import vn.system.app.modules.department.domain.Department;
import vn.system.app.modules.department.domain.request.CreateDepartmentRequest;
import vn.system.app.modules.department.domain.request.UpdateDepartmentRequest;
import vn.system.app.modules.department.domain.response.DepartmentResponse;
import vn.system.app.modules.department.repository.DepartmentRepository;

@Service
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final CompanyService companyService;

    public DepartmentService(
            DepartmentRepository departmentRepository,
            CompanyService companyService) {
        this.departmentRepository = departmentRepository;
        this.companyService = companyService;
    }

    public void checkDepartmentScope(Department d) {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope != null && !scope.isAdminLevel()) {
            if (scope.isCompanyLevel()) {
                if (!scope.companyIds().contains(d.getCompany().getId())) {
                    throw new PermissionException("Bạn không có quyền thao tác trên phòng ban của công ty này");
                }
            } else {
                if (!scope.departmentIds().contains(d.getId())) {
                    throw new PermissionException("Bạn không có quyền thao tác trên phòng ban này");
                }
            }
        }
    }

    /* ================= CREATE ================= */

    @Transactional
    public DepartmentResponse handleCreateDepartment(CreateDepartmentRequest req) {
        if (departmentRepository.existsByCodeAndCompany_Id(req.getCode(), req.getCompanyId())) {
            throw new IdInvalidException("Mã phòng ban đã tồn tại trong công ty này");
        }

        Company company = companyService.fetchEntityById(req.getCompanyId());

        Department d = new Department();
        d.setCode(req.getCode());
        d.setName(req.getName());
        d.setEnglishName(req.getEnglishName());
        d.setCompany(company);
        d.setStatus(1);

        d = departmentRepository.save(d);
        return convertToResponseDTO(d);
    }

    /* ================= UPDATE ================= */

    @Transactional
    public DepartmentResponse handleUpdateDepartment(Long id, UpdateDepartmentRequest req) {
        Department d = fetchEntityById(id);
        checkDepartmentScope(d);

        if (req.getName() != null)
            d.setName(req.getName());
        if (req.getEnglishName() != null)
            d.setEnglishName(req.getEnglishName());
        if (req.getStatus() != null)
            d.setStatus(req.getStatus());

        d = departmentRepository.save(d);
        return convertToResponseDTO(d);
    }

    /* ================= DELETE (SOFT) ================= */

    @Transactional
    public void handleDeleteDepartment(Long id) {
        Department d = fetchEntityById(id);
        checkDepartmentScope(d);

        if (d.getStatus() == 0) {
            throw new IdInvalidException("Phòng ban đã bị vô hiệu hóa trước đó");
        }

        d.setStatus(0);
        departmentRepository.save(d);
    }

    /* ================= ACTIVE (RE-ACTIVATE) ================= */

    @Transactional
    public DepartmentResponse handleActiveDepartment(Long id) {
        Department d = fetchEntityById(id);
        checkDepartmentScope(d);

        if (d.getStatus() == 1) {
            throw new IdInvalidException("Phòng ban này đang hoạt động");
        }

        d.setStatus(1);
        d = departmentRepository.save(d);

        return convertToResponseDTO(d);
    }

    /* ================= FETCH ENTITY ================= */

    public Department fetchEntityById(Long id) {
        return departmentRepository.findById(id)
                .orElseThrow(() -> new IdInvalidException("Phòng ban không tồn tại"));
    }

    /* ================= FETCH ONE ================= */

    public DepartmentResponse fetchDepartmentById(Long id) {
        Department d = fetchEntityById(id);
        checkDepartmentScope(d);
        return convertToResponseDTO(d);
    }

    /* ================= FETCH ALL ================= */

    public ResultPaginationDTO fetchAllDepartments(
            Specification<Department> spec,
            Pageable pageable) {

        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope != null && !scope.isAdminLevel()) {
            if (scope.isAdminLevel()) {
                // SUPER_ADMIN, ADMIN_SUB_1 → thấy toàn bộ, không filter
                spec = Specification.where(spec)
                        .and(ScopeSpec.byCompanyScope("company.id"));
            } else if (scope.isCompanyLevel()) {
                // ADMIN_SUB_2 → thấy toàn bộ phòng ban trong công ty được gán
                spec = Specification.where(spec)
                        .and(ScopeSpec.byCompanyScope("company.id"));
            } else {
                // Employee, Manager, ... → chỉ thấy phòng ban của mình
                spec = Specification.where(spec)
                        .and(ScopeSpec.byDepartmentScope("id"));
            }
        }

        Page<Department> page = departmentRepository.findAll(spec, pageable);

        ResultPaginationDTO rs = new ResultPaginationDTO();
        ResultPaginationDTO.Meta meta = new ResultPaginationDTO.Meta();

        meta.setPage(pageable.getPageNumber() + 1);
        meta.setPageSize(pageable.getPageSize());
        meta.setPages(page.getTotalPages());
        meta.setTotal(page.getTotalElements());

        rs.setMeta(meta);

        List<DepartmentResponse> list = page.getContent()
                .stream()
                .map(this::convertToResponseDTO)
                .collect(Collectors.toList());

        rs.setResult(list);

        return rs;
    }

    /* ================= FETCH BY COMPANY ================= */

    public List<DepartmentResponse> fetchDepartmentsByCompany(Long companyId) {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        return departmentRepository.findByCompanyId(companyId)
                .stream()
                .filter(d -> {
                    if (scope == null) {
                        return false;
                    }
                    if (scope.isAdminLevel()) {
                        return true;
                    }
                    if (scope.isCompanyLevel()) {
                        return scope.companyIds() != null && scope.companyIds().contains(companyId);
                    }
                    if (scope.isDepartmentLevel()) {
                        return scope.departmentIds() != null && scope.departmentIds().contains(d.getId());
                    }
                    // Dành cho nhân viên bình thường (không phải admin, không phải manager)
                    return scope.departmentIds() != null && scope.departmentIds().contains(d.getId());
                })
                .map(this::convertToResponseDTO)
                .collect(Collectors.toList());
    }

    /* ================= CONVERT ================= */

    public DepartmentResponse convertToResponseDTO(Department d) {

        DepartmentResponse res = new DepartmentResponse();

        res.setId(d.getId());
        res.setCode(d.getCode());
        res.setName(d.getName());
        res.setEnglishName(d.getEnglishName());
        res.setStatus(d.getStatus());
        res.setCreatedAt(d.getCreatedAt());
        res.setUpdatedAt(d.getUpdatedAt());
        res.setCreatedBy(d.getCreatedBy());
        res.setUpdatedBy(d.getUpdatedBy());

        DepartmentResponse.CompanyInfo ci = new DepartmentResponse.CompanyInfo();
        ci.setId(d.getCompany().getId());
        ci.setName(d.getCompany().getName());

        res.setCompany(ci);

        return res;
    }
}
