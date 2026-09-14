package vn.system.app.modules.permissioncontent.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.error.IdInvalidException;

import vn.system.app.modules.department.service.DepartmentService;
import vn.system.app.modules.permissioncategory.domain.PermissionCategory;
import vn.system.app.modules.permissioncategory.repository.PermissionCategoryRepository;
import vn.system.app.modules.permissioncontent.domain.PermissionContent;
import vn.system.app.modules.permissioncontent.domain.request.ReqCreatePermissionContentDTO;
import vn.system.app.modules.permissioncontent.domain.request.ReqUpdatePermissionContentDTO;
import vn.system.app.modules.permissioncontent.domain.response.ResPermissionContentDTO;
import vn.system.app.modules.permissioncontent.domain.response.ResPermissionContentDetailDTO;
import vn.system.app.modules.permissioncontent.repository.PermissionContentRepository;

@Service
public class PermissionContentService {

    private final PermissionContentRepository repository;
    private final PermissionCategoryRepository categoryRepository;
    private final DepartmentService departmentService;

    public PermissionContentService(
            PermissionContentRepository repository,
            PermissionCategoryRepository categoryRepository,
            DepartmentService departmentService) {
        this.repository = repository;
        this.categoryRepository = categoryRepository;
        this.departmentService = departmentService;
    }

    // IDOR protection — content thuộc category nào thì phải nằm trong
    // phạm vi phòng ban của category đó
    private void checkScope(PermissionCategory category) {
        departmentService.checkDepartmentScope(category.getDepartment());
    }

    /*
     * =====================================================
     * CREATE
     * =====================================================
     */
    @Transactional
    public PermissionContent handleCreatePermissionContent(
            ReqCreatePermissionContentDTO req) {

        if (repository.existsByNameAndCategory_IdAndActiveTrue(
                req.getName(), req.getCategoryId())) {
            throw new IdInvalidException("Nội dung đã tồn tại trong danh mục");
        }

        PermissionCategory category = categoryRepository.findById(req.getCategoryId())
                .orElseThrow(() -> new IdInvalidException("Danh mục không tồn tại"));
        checkScope(category);

        PermissionContent entity = new PermissionContent();
        entity.setName(req.getName());
        entity.setCategory(category);
        entity.setActive(true);

        return repository.save(entity);
    }

    /*
     * =====================================================
     * UPDATE
     * =====================================================
     */
    @Transactional
    public PermissionContent handleUpdatePermissionContent(
            Long id,
            ReqUpdatePermissionContentDTO req) {

        PermissionContent entity = repository.findByIdAndActiveTrue(id);
        if (entity == null) {
            throw new IdInvalidException("Nội dung quyền không tồn tại");
        }
        checkScope(entity.getCategory());

        boolean duplicated = repository.existsByNameAndCategory_IdAndActiveTrue(
                req.getName(), entity.getCategory().getId());

        if (duplicated && !entity.getName().equals(req.getName())) {
            throw new IdInvalidException("Tên nội dung đã tồn tại trong danh mục");
        }

        entity.setName(req.getName());
        return repository.save(entity);
    }

    /*
     * =====================================================
     * DELETE (SOFT)
     * =====================================================
     */
    @Transactional
    public void handleDeletePermissionContent(Long id) {

        PermissionContent entity = repository.findByIdAndActiveTrue(id);
        if (entity == null) {
            throw new IdInvalidException("Nội dung quyền không tồn tại");
        }
        checkScope(entity.getCategory());

        entity.setActive(false);
        repository.save(entity);
    }

    /*
     * =====================================================
     * TOGGLE ACTIVE
     * =====================================================
     */
    @Transactional
    public PermissionContent handleTogglePermissionContent(Long id) {

        PermissionContent entity = repository.findById(id)
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy nội dung quyền"));
        checkScope(entity.getCategory());

        entity.setActive(!entity.isActive());
        return repository.save(entity);
    }

    /*
     * =====================================================
     * FETCH BY ID (ACTIVE)
     * =====================================================
     */
    public PermissionContent fetchPermissionContentById(Long id) {

        PermissionContent entity = repository.findByIdAndActiveTrue(id);
        if (entity == null) {
            throw new IdInvalidException("Nội dung quyền không tồn tại hoặc đã bị vô hiệu hoá");
        }
        checkScope(entity.getCategory());
        return entity;
    }

    /*
     * =====================================================
     * FETCH ALL BY CATEGORY (BẮT BUỘC)
     * =====================================================
     */
    public ResultPaginationDTO fetchAllPermissionContent(
            Long categoryId,
            Pageable pageable) {

        if (categoryId == null) {
            throw new IdInvalidException("categoryId là bắt buộc");
        }
        PermissionCategory category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new IdInvalidException("Danh mục không tồn tại"));
        checkScope(category);

        Page<PermissionContent> page = repository.findByCategory_Id(categoryId, pageable);

        ResultPaginationDTO rs = new ResultPaginationDTO();
        ResultPaginationDTO.Meta mt = new ResultPaginationDTO.Meta();

        mt.setPage(pageable.getPageNumber() + 1);
        mt.setPageSize(pageable.getPageSize());
        mt.setPages(page.getTotalPages());
        mt.setTotal(page.getTotalElements());

        rs.setMeta(mt);

        List<ResPermissionContentDTO> data = page.getContent()
                .stream()
                .map(this::convertToResPermissionContentDTO)
                .collect(Collectors.toList());

        rs.setResult(data);
        return rs;
    }

    /*
     * =====================================================
     * CONVERT LIST DTO
     * =====================================================
     */
    public ResPermissionContentDTO convertToResPermissionContentDTO(
            PermissionContent entity) {

        ResPermissionContentDTO dto = new ResPermissionContentDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setActive(entity.isActive());
        return dto;
    }

    /*
     * =====================================================
     * CONVERT DETAIL DTO
     * =====================================================
     */
    public ResPermissionContentDetailDTO convertToResPermissionContentDetailDTO(
            PermissionContent e) {

        ResPermissionContentDetailDTO dto = new ResPermissionContentDetailDTO();
        dto.setId(e.getId());
        dto.setName(e.getName());
        dto.setActive(e.isActive());

        ResPermissionContentDetailDTO.CategoryDTO cat = new ResPermissionContentDetailDTO.CategoryDTO();
        cat.setId(e.getCategory().getId());
        cat.setCode(e.getCategory().getCode());
        cat.setName(e.getCategory().getName());

        dto.setCategory(cat);
        return dto;
    }
}
