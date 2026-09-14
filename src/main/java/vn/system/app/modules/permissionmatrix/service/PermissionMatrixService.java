package vn.system.app.modules.permissionmatrix.service;

import java.util.*;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.common.util.error.IdInvalidException;

import vn.system.app.modules.department.service.DepartmentService;
import vn.system.app.modules.departmentjobtitle.domain.DepartmentJobTitle;
import vn.system.app.modules.departmentjobtitle.service.DepartmentJobTitleService;

import vn.system.app.modules.permissionassignment.domain.PermissionAssignment;
import vn.system.app.modules.permissionassignment.repository.PermissionAssignmentRepository;

import vn.system.app.modules.permissioncategory.domain.PermissionCategory;
import vn.system.app.modules.permissioncategory.repository.PermissionCategoryRepository;

import vn.system.app.modules.permissioncategoryscope.domain.PermissionCategoryScope;
import vn.system.app.modules.permissioncategoryscope.repository.PermissionCategoryScopeRepository;

import vn.system.app.modules.permissioncontent.domain.PermissionContent;
import vn.system.app.modules.permissioncontent.repository.PermissionContentRepository;

import vn.system.app.modules.processaction.domain.ProcessAction;

import vn.system.app.modules.permissionmatrix.domain.response.FullPermissionMatrixDTO;

@Service
public class PermissionMatrixService {

    private final PermissionContentRepository contentRepo;
    private final PermissionCategoryRepository categoryRepo;
    private final PermissionCategoryScopeRepository scopeRepo;
    private final DepartmentJobTitleService djtService;
    private final PermissionAssignmentRepository assignmentRepo;
    private final DepartmentService departmentService;

    public PermissionMatrixService(
            PermissionContentRepository contentRepo,
            PermissionCategoryRepository categoryRepo,
            PermissionCategoryScopeRepository scopeRepo,
            DepartmentJobTitleService djtService,
            PermissionAssignmentRepository assignmentRepo,
            DepartmentService departmentService) {

        this.contentRepo = contentRepo;
        this.categoryRepo = categoryRepo;
        this.scopeRepo = scopeRepo;
        this.djtService = djtService;
        this.assignmentRepo = assignmentRepo;
        this.departmentService = departmentService;
    }

    // =============================================================
    // BUILD FULL MATRIX THEO DANH MỤC
    // =============================================================
    @Transactional(readOnly = true)
    public FullPermissionMatrixDTO buildCategoryMatrix(Long categoryId) {

        // 1️⃣ Load category
        PermissionCategory category = categoryRepo.findById(categoryId)
                .orElseThrow(() -> new IdInvalidException("Danh mục không tồn tại"));

        departmentService.checkDepartmentScope(category.getDepartment());
        Long departmentId = category.getDepartment().getId();

        // 2️⃣ Load toàn bộ nội dung theo category
        List<PermissionContent> contents = contentRepo.findByCategory_IdAndActiveTrue(categoryId);

        // 3️⃣ Lấy Scope (nếu có)
        List<PermissionCategoryScope> scopes = scopeRepo.findByCategory_Id(categoryId);

        List<Long> djtIdsFromScope = scopes.stream()
                .map(PermissionCategoryScope::getDepartmentJobTitleId)
                .toList();

        // 4️⃣ Nếu không có scope → tự load toàn bộ chức danh phòng ban
        List<DepartmentJobTitle> djts;
        if (djtIdsFromScope.isEmpty()) {
            djts = djtService.fetchByDepartment(departmentId); // fallback
        } else {
            djts = djtService.fetchEntitiesByIds(djtIdsFromScope);
        }

        // 5️⃣ Load ALL assignment của category (tối ưu hiệu năng)
        Set<Long> contentIds = contents.stream()
                .map(PermissionContent::getId)
                .collect(Collectors.toSet());

        List<PermissionAssignment> assignments = contentIds.isEmpty()
                ? List.of()
                : assignmentRepo.findByPermissionContent_IdIn(contentIds);

        Map<String, ProcessAction> assignmentMap = new HashMap<>();
        for (PermissionAssignment pa : assignments) {
            String key = pa.getPermissionContent().getId()
                    + "-" + pa.getDepartmentJobTitle().getId();
            assignmentMap.put(key, pa.getProcessAction());
        }

        // 6️⃣ Build DTO trả về
        FullPermissionMatrixDTO res = new FullPermissionMatrixDTO();

        res.setCategoryId(category.getId());
        res.setCategoryName(category.getName());
        res.setDepartmentName(category.getDepartment().getName());

        // Columns = DJT
        res.setColumns(
                djts.stream()
                        .map(djt -> new FullPermissionMatrixDTO.Column(
                                djt.getId(),
                                djt.getJobTitle().getNameVi()))
                        .toList());

        // Rows
        List<FullPermissionMatrixDTO.Row> rows = new ArrayList<>();

        for (PermissionContent c : contents) {

            FullPermissionMatrixDTO.Row row = new FullPermissionMatrixDTO.Row();
            row.setContentId(c.getId());
            row.setContentName(c.getName());

            List<FullPermissionMatrixDTO.Cell> cells = new ArrayList<>();

            for (DepartmentJobTitle djt : djts) {

                String key = c.getId() + "-" + djt.getId();
                ProcessAction action = assignmentMap.get(key);

                FullPermissionMatrixDTO.Cell cell = new FullPermissionMatrixDTO.Cell();
                cell.setDepartmentJobTitleId(djt.getId());
                cell.setProcessActionCode(action != null ? action.getCode() : null);

                cells.add(cell);
            }

            row.setCells(cells);
            rows.add(row);
        }

        res.setRows(rows);

        return res;
    }
}
