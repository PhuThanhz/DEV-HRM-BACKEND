package vn.system.app.modules.permissioncategory.service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import jakarta.transaction.Transactional;

import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.ScopeSpec;
import vn.system.app.common.util.error.IdInvalidException;

import vn.system.app.modules.department.service.DepartmentService;
import vn.system.app.modules.permissioncategory.domain.PermissionCategory;
import vn.system.app.modules.permissioncategory.domain.request.PermissionCategoryRequest;
import vn.system.app.modules.permissioncategory.domain.response.PermissionCategoryResponse;
import vn.system.app.modules.permissioncategory.repository.PermissionCategoryRepository;

@Service
public class PermissionCategoryService {

    private final PermissionCategoryRepository repository;
    private final DepartmentService departmentService;

    public PermissionCategoryService(
            PermissionCategoryRepository repository,
            DepartmentService departmentService) {

        this.repository = repository;
        this.departmentService = departmentService;
    }

    // =====================================================
    // CREATE CATEGORY
    // =====================================================
    @Transactional
    public PermissionCategory handleCreateCategory(PermissionCategoryRequest req) {

        if (repository.existsByCodeAndActiveTrue(req.getCode())) {
            throw new IdInvalidException("Danh mục phân quyền đã tồn tại");
        }

        var department = departmentService.fetchEntityById(req.getDepartmentId());
        departmentService.checkDepartmentScope(department);

        PermissionCategory entity = new PermissionCategory();
        entity.setCode(req.getCode());
        entity.setName(req.getName());
        entity.setDepartment(department);
        entity.setActive(req.getActive() != null ? req.getActive() : true);

        return repository.save(entity);
    }

    // =====================================================
    // UPDATE CATEGORY
    // =====================================================
    @Transactional
    public PermissionCategory handleUpdateCategory(long id, PermissionCategoryRequest req) {

        PermissionCategory entity = this.fetchCategoryById(id);

        if (!entity.getCode().equals(req.getCode())
                && repository.existsByCodeAndActiveTrue(req.getCode())) {
            throw new IdInvalidException("Danh mục phân quyền đã tồn tại");
        }

        entity.setCode(req.getCode());
        entity.setName(req.getName());

        if (req.getDepartmentId() != null) {
            var department = departmentService.fetchEntityById(req.getDepartmentId());
            entity.setDepartment(department);
        }

        if (req.getActive() != null) {
            entity.setActive(req.getActive());
        }

        return repository.save(entity);
    }

    // =====================================================
    // SOFT DELETE CATEGORY
    // =====================================================
    @Transactional
    public void handleDeleteCategory(long id) {
        PermissionCategory entity = this.fetchCategoryById(id);
        entity.setActive(false);
        repository.save(entity);
    }

    // =====================================================
    // FETCH CATEGORY BY ID
    // =====================================================
    public PermissionCategory fetchCategoryById(long id) {
        Optional<PermissionCategory> optional = repository.findById(id);
        if (optional.isEmpty()) {
            throw new IdInvalidException("Danh mục phân quyền với id = " + id + " không tồn tại");
        }
        PermissionCategory entity = optional.get();
        departmentService.checkDepartmentScope(entity.getDepartment());
        return entity;
    }

    // =====================================================
    // PAGINATION
    // =====================================================
    public ResultPaginationDTO fetchAllCategory(Pageable pageable) {

        Specification<PermissionCategory> spec = ScopeSpec.byCompanyOrDepartmentScope(
                "department.company.id", "department.id");
        Page<PermissionCategory> page = repository.findAll(spec, pageable);

        ResultPaginationDTO rs = new ResultPaginationDTO();
        ResultPaginationDTO.Meta meta = new ResultPaginationDTO.Meta();

        meta.setPage(pageable.getPageNumber() + 1);
        meta.setPageSize(pageable.getPageSize());
        meta.setPages(page.getTotalPages());
        meta.setTotal(page.getTotalElements());

        rs.setMeta(meta);

        List<PermissionCategoryResponse> data = page.getContent()
                .stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());

        rs.setResult(data);
        return rs;
    }

    // =====================================================
    // ⭐ LẤY CATEGORY THEO PHÒNG BAN
    // =====================================================
    public List<PermissionCategoryResponse> fetchCategoriesByDepartment(Long departmentId) {

        departmentService.checkDepartmentScope(departmentService.fetchEntityById(departmentId));

        var list = repository.findByDepartmentId(departmentId);

        return list.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    // =====================================================
    // CONVERT ENTITY → DTO
    // =====================================================
    public PermissionCategoryResponse convertToResponse(PermissionCategory entity) {

        PermissionCategoryResponse res = new PermissionCategoryResponse();

        res.setId(entity.getId());
        res.setCode(entity.getCode());
        res.setName(entity.getName());
        res.setActive(entity.getActive());

        res.setCreatedAt(entity.getCreatedAt());
        res.setUpdatedAt(entity.getUpdatedAt());
        res.setCreatedBy(entity.getCreatedBy());
        res.setUpdatedBy(entity.getUpdatedBy());

        if (entity.getDepartment() != null) {
            res.setDepartmentId(entity.getDepartment().getId());
            res.setDepartmentName(entity.getDepartment().getName());

            // ✅ map company bên trong luôn
            if (entity.getDepartment().getCompany() != null) {
                res.setCompanyId(entity.getDepartment().getCompany().getId());
                res.setCompanyName(entity.getDepartment().getCompany().getName());
            }
        }
        return res;
    }
}