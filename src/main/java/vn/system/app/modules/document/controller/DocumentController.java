package vn.system.app.modules.document.controller;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.turkraft.springfilter.boot.Filter;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.ScopeSpec;
import vn.system.app.common.util.annotation.ApiMessage;
import vn.system.app.common.util.error.PermissionException;
import vn.system.app.modules.document.domain.Document;
import vn.system.app.modules.document.domain.DocumentAccess;
import vn.system.app.modules.document.domain.DocumentTargetCompany;
import vn.system.app.modules.document.domain.request.DocumentRequest;
import vn.system.app.modules.document.domain.response.ResDocumentDTO;
import vn.system.app.modules.document.service.DocumentService;
import vn.system.app.modules.document.util.DocumentAccessSpecs;
import vn.system.app.common.util.SecurityUtil;
import jakarta.persistence.criteria.Subquery;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import vn.system.app.modules.sharetoken.domain.ShareTokenAccessLog;
import vn.system.app.modules.sharetoken.domain.ProcedureShareToken;
import vn.system.app.modules.sharetoken.domain.request.CreateShareTokenRequest;
import vn.system.app.modules.sharetoken.domain.request.SendShareEmailRequest;
import vn.system.app.modules.sharetoken.domain.response.ResShareTokenDTO;
import vn.system.app.modules.sharetoken.service.ProcedureShareTokenService;
import vn.system.app.modules.procedure.enums.ProcedureType;
import vn.system.app.modules.documentfolder.domain.DocumentFolder;
import vn.system.app.modules.user.domain.User;
import vn.system.app.modules.department.domain.Department;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService service;
    private final ProcedureShareTokenService shareTokenService;

    // =====================================================
    // CREATE
    // =====================================================
    @PostMapping
    @ApiMessage("Tạo văn bản")
    public ResponseEntity<ResDocumentDTO> create(
            @Valid @RequestBody DocumentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.handleCreate(req));
    }

    // =====================================================
    // UPDATE
    // =====================================================
    @GetMapping("/next-code")
    @ApiMessage("Lấy mã văn bản tiếp theo")
    public ResponseEntity<java.util.Map<String, String>> getNextCode(
            @RequestParam Long companyId,
            @RequestParam Long categoryId,
            @RequestParam Integer year) {
        return ResponseEntity.ok(service.getNextDocumentCode(companyId, categoryId, year));
    }

    // =====================================================
    // UPDATE
    // =====================================================
    @PutMapping("/{id}")
    @ApiMessage("Cập nhật văn bản")
    public ResponseEntity<ResDocumentDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody DocumentRequest req) {
        return ResponseEntity.ok(service.handleUpdate(id, req));
    }

    // =====================================================
    // TOGGLE ACTIVE
    // =====================================================
    @PutMapping("/{id}/active")
    @ApiMessage("Thay đổi trạng thái kích hoạt")
    public ResponseEntity<Void> toggleActive(@PathVariable Long id) {
        service.handleToggleActive(id);
        return ResponseEntity.ok().build();
    }

    // =====================================================
    // DELETE
    // =====================================================
    @DeleteMapping("/{id}")
    @ApiMessage("Xoá văn bản")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.handleDelete(id);
        return ResponseEntity.ok().build();
    }

    // =====================================================
    // MARK AS READ
    // =====================================================
    @PutMapping("/{id}/read")
    @ApiMessage("Đánh dấu đã xem văn bản")
    public ResponseEntity<Void> markAsRead(@PathVariable Long id) {
        service.handleMarkAsRead(id);
        return ResponseEntity.ok().build();
    }

    // =====================================================
    // GET ONE
    // =====================================================
    @GetMapping("/{id}")
    @ApiMessage("Chi tiết văn bản")
    public ResponseEntity<ResDocumentDTO> getOne(@PathVariable Long id) {
        return ResponseEntity.ok(service.convertToDTO(service.fetchByIdForRead(id)));
    }

    // =====================================================
    // GET ALL (filter + paginate)
    // =====================================================
    @GetMapping
    @ApiMessage("Danh sách văn bản")
    public ResponseEntity<ResultPaginationDTO> getAll(
            @Filter Specification<Document> spec,
            Pageable pageable) {
        
        vn.system.app.common.util.UserScopeContext.UserScope scope = vn.system.app.common.util.UserScopeContext.get();

        if (scope == null) {
            throw new PermissionException("Không xác định được phạm vi người dùng, vui lòng đăng nhập lại");
        } else if (scope.isSuperAdmin() || scope.isAdminLevel()) {
            // Super Admin & Admin Sub 1 (ALL): Bypass filter, see everything
        } else if (scope.isCompanyLevel()) {
            // Admin Sub 2 (COMPANY): Filter by companyId OR cross-company access
            Specification<Document> companySpec = ScopeSpec.byCompanyScope("department.company.id");
            Specification<Document> targetCompanySpec = buildTargetCompanySpec();
            Specification<Document> accessSpec = buildAccessSpec();
            Specification<Document> scopeSpec = companySpec.or(targetCompanySpec).or(accessSpec);
            scopeSpec = scopeSpec.and(DocumentAccessSpecs.confidentialGuard());
            spec = (spec == null) ? scopeSpec.and(DocumentAccessSpecs.notExcluded()) : spec.and(scopeSpec).and(DocumentAccessSpecs.notExcluded());
        } else {
            // User bình thường (INDIVIDUAL): Filter by createdBy OR accessList OR company/department scope for non-confidential documents
            Specification<Document> individualSpec = (root, query, cb) -> {
                String currentUserLogin = SecurityUtil.getCurrentUserLogin().orElse("");
                String currentUserId = SecurityUtil.getCurrentUserId().orElse("");
                
                // 1. Created by me
                jakarta.persistence.criteria.Predicate createdByPred = cb.equal(root.get("createdBy"), currentUserLogin);
                
                // 2. In access list
                Subquery<Integer> subquery = query.subquery(Integer.class);
                Root<DocumentAccess> accessRoot = subquery.from(DocumentAccess.class);
                subquery.select(cb.literal(1));
                subquery.where(
                    cb.equal(accessRoot.get("document"), root),
                    cb.equal(accessRoot.get("userId"), currentUserId)
                );
                jakarta.persistence.criteria.Predicate accessPred = cb.exists(subquery);
                
                // Use explicit left joins to prevent filtering out documents with null department (e.g. personal drive documents)
                jakarta.persistence.criteria.Join<Document, vn.system.app.modules.department.domain.Department> deptJoin = 
                    root.join("department", jakarta.persistence.criteria.JoinType.LEFT);

                // 3. Company level document (COMPANY)
                jakarta.persistence.criteria.Predicate companyLevelPred = cb.disjunction();
                if (scope != null && scope.companyIds() != null && !scope.companyIds().isEmpty()) {
                    jakarta.persistence.criteria.Join<vn.system.app.modules.department.domain.Department, vn.system.app.modules.company.domain.Company> companyJoin = 
                        deptJoin.join("company", jakarta.persistence.criteria.JoinType.LEFT);
                    companyLevelPred = cb.and(
                        cb.equal(root.get("procedureType"), ProcedureType.COMPANY),
                        cb.or(
                            companyJoin.get("id").in(scope.companyIds()),
                            cb.exists(buildTargetCompanySubquery(root, query, cb, scope.companyIds()))
                        )
                    );
                }
                
                // 4. Department level document (DEPARTMENT)
                jakarta.persistence.criteria.Predicate deptLevelPred = cb.disjunction();
                if (scope != null && scope.departmentIds() != null && !scope.departmentIds().isEmpty()) {
                    Subquery<Integer> departmentMappingSubquery = query.subquery(Integer.class);
                    Root<Document> departmentMappingRoot = departmentMappingSubquery.from(Document.class);
                    Join<Document, Department> departmentMappingJoin = departmentMappingRoot.join("departments", JoinType.INNER);
                    departmentMappingSubquery.select(cb.literal(1));
                    departmentMappingSubquery.where(
                        cb.equal(departmentMappingRoot.get("id"), root.get("id")),
                        departmentMappingJoin.get("id").in(scope.departmentIds())
                    );

                    deptLevelPred = cb.and(
                        cb.equal(root.get("procedureType"), ProcedureType.DEPARTMENT),
                        cb.or(
                            deptJoin.get("id").in(scope.departmentIds()),
                            cb.exists(departmentMappingSubquery)
                        )
                    );
                }

                // 5 & 6. Folder owner or folder owner's manager
                jakarta.persistence.criteria.Join<Document, DocumentFolder> folderJoin = root.join("folder", jakarta.persistence.criteria.JoinType.LEFT);
                jakarta.persistence.criteria.Predicate folderOwnerPred = cb.equal(folderJoin.get("ownerId"), currentUserId);
                
                Subquery<Integer> managerSubquery = query.subquery(Integer.class);
                Root<User> userRoot = managerSubquery.from(User.class);
                managerSubquery.select(cb.literal(1));
                managerSubquery.where(
                    cb.equal(userRoot.get("id"), folderJoin.get("ownerId")),
                    cb.equal(userRoot.get("directManager").get("id"), currentUserId)
                );
                jakarta.persistence.criteria.Predicate folderManagerPred = cb.exists(managerSubquery);
                
                return cb.and(
                    DocumentAccessSpecs.notExcludedPredicate(root, query, cb, currentUserId, scope.departmentIds()),
                    cb.or(createdByPred, accessPred, companyLevelPred, deptLevelPred, folderOwnerPred, folderManagerPred)
                );
            };
            spec = (spec == null) ? individualSpec : spec.and(individualSpec);
        }
        
        return ResponseEntity.ok(service.fetchAll(spec, pageable));
    }

    private Specification<Document> buildAccessSpec() {
        return (root, query, cb) -> {
            String currentUserId = SecurityUtil.getCurrentUserId().orElse("");
            Subquery<Integer> subquery = query.subquery(Integer.class);
            Root<DocumentAccess> accessRoot = subquery.from(DocumentAccess.class);
            subquery.select(cb.literal(1));
            subquery.where(
                cb.equal(accessRoot.get("document"), root),
                cb.equal(accessRoot.get("userId"), currentUserId)
            );
            return cb.exists(subquery);
        };
    }

    private Specification<Document> buildTargetCompanySpec() {
        return (root, query, cb) -> {
            vn.system.app.common.util.UserScopeContext.UserScope scope = vn.system.app.common.util.UserScopeContext.get();
            if (scope == null || scope.companyIds() == null || scope.companyIds().isEmpty()) {
                return cb.disjunction();
            }
            return cb.exists(buildTargetCompanySubquery(root, query, cb, scope.companyIds()));
        };
    }

    private Subquery<Integer> buildTargetCompanySubquery(
            Root<Document> root,
            jakarta.persistence.criteria.CriteriaQuery<?> query,
            jakarta.persistence.criteria.CriteriaBuilder cb,
            java.util.Set<Long> companyIds) {
        Subquery<Integer> targetCompanySubquery = query.subquery(Integer.class);
        Root<DocumentTargetCompany> targetCompanyRoot = targetCompanySubquery.from(DocumentTargetCompany.class);
        targetCompanySubquery.select(cb.literal(1));
        targetCompanySubquery.where(
            cb.equal(targetCompanyRoot.get("document"), root),
            targetCompanyRoot.get("companyId").in(companyIds)
        );
        return targetCompanySubquery;
    }

    // =====================================================
    // GET BY COMPANY
    // =====================================================
    @GetMapping("/by-company/{companyId}")
    @ApiMessage("Danh sách văn bản theo công ty")
    public ResponseEntity<List<ResDocumentDTO>> getByCompany(
            @PathVariable Long companyId) {
        return ResponseEntity.ok(service.fetchByCompany(companyId));
    }

    // =====================================================
    // GET BY DEPARTMENT
    // =====================================================
    @GetMapping("/by-department/{departmentId}")
    @ApiMessage("Danh sách văn bản theo phòng ban")
    public ResponseEntity<List<ResDocumentDTO>> getByDepartment(
            @PathVariable Long departmentId) {
        return ResponseEntity.ok(service.fetchByDepartment(departmentId));
    }

    // =====================================================
    // GET BY SECTION
    // =====================================================
    @GetMapping("/by-section/{sectionId}")
    @ApiMessage("Danh sách văn bản theo bộ phận")
    public ResponseEntity<List<ResDocumentDTO>> getBySection(
            @PathVariable Long sectionId) {
        return ResponseEntity.ok(service.fetchBySection(sectionId));
    }

    // =====================================================
    // GET BY CATEGORY
    // =====================================================
    @GetMapping("/by-category/{categoryId}")
    @ApiMessage("Danh sách văn bản theo loại")
    public ResponseEntity<List<ResDocumentDTO>> getByCategory(
            @PathVariable Long categoryId) {
        return ResponseEntity.ok(service.fetchByCategory(categoryId));
    }

    // =====================================================
    // TẠO SHARE TOKEN
    // =====================================================
    @PostMapping("/{id}/share-tokens")
    @ApiMessage("Tạo link chia sẻ văn bản")
    public ResponseEntity<ResShareTokenDTO> createShareToken(
            @PathVariable Long id,
            @Valid @RequestBody CreateShareTokenRequest req) {
        service.fetchByIdForWrite(id);
        req.setProcedureType("DOCUMENT");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(shareTokenService.handleCreate(id, req));
    }

    // =====================================================
    // DANH SÁCH SHARE TOKEN
    // =====================================================
    @GetMapping("/{id}/share-tokens")
    @ApiMessage("Danh sách link chia sẻ của văn bản")
    public ResponseEntity<List<ResShareTokenDTO>> getShareTokens(
            @PathVariable Long id) {
        service.fetchByIdForWrite(id);
        return ResponseEntity.ok(shareTokenService.fetchByProcedure(id, "DOCUMENT"));
    }

    // =====================================================
    // THU HỒI SHARE TOKEN
    // =====================================================
    @PatchMapping("/share-tokens/{tokenId}/revoke")
    @ApiMessage("Thu hồi link chia sẻ văn bản")
    public ResponseEntity<Void> revokeShareToken(@PathVariable Long tokenId) {
        assertDocumentShareTokenManageAccess(tokenId);
        shareTokenService.handleRevoke(tokenId);
        return ResponseEntity.ok().build();
    }

    // =====================================================
    // GỬI EMAIL CHIA SẺ
    // =====================================================
    @PostMapping("/share-tokens/{tokenId}/send-email")
    @ApiMessage("Gửi email chia sẻ văn bản")
    public ResponseEntity<Void> sendShareEmail(
            @PathVariable Long tokenId,
            @Valid @RequestBody SendShareEmailRequest req) {
        assertDocumentShareTokenManageAccess(tokenId);
        shareTokenService.handleSendEmail(tokenId, req.getEmail());
        return ResponseEntity.ok().build();
    }

    // =====================================================
    // LỊCH SỬ TRUY CẬP SHARE TOKEN
    // =====================================================
    @GetMapping("/share-tokens/{tokenId}/access-logs")
    @ApiMessage("Lịch sử truy cập link chia sẻ văn bản")
    public ResponseEntity<List<ShareTokenAccessLog>> getAccessLogs(
            @PathVariable Long tokenId) {
        assertDocumentShareTokenManageAccess(tokenId);
        return ResponseEntity.ok(shareTokenService.fetchAccessLogs(tokenId));
    }

    private void assertDocumentShareTokenManageAccess(Long tokenId) {
        ProcedureShareToken token = shareTokenService.fetchById(tokenId);
        if (!"DOCUMENT".equals(token.getProcedureType())) {
            throw new vn.system.app.common.util.error.IdInvalidException("Link chia sẻ văn bản không tồn tại");
        }
        service.fetchByIdForWrite(token.getProcedureId());
    }

    // =====================================================
    // MANAGE SHORTCUTS
    // =====================================================
    @PostMapping("/{id}/shortcut")
    @ApiMessage("Tạo lối tắt tài liệu")
    public ResponseEntity<Void> createShortcut(
            @PathVariable Long id,
            @RequestParam Long folderId) {
        service.createShortcut(id, folderId);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/{id}/shortcut")
    @ApiMessage("Xóa lối tắt tài liệu")
    public ResponseEntity<Void> deleteShortcut(
            @PathVariable Long id,
            @RequestParam Long folderId) {
        service.deleteShortcut(id, folderId);
        return ResponseEntity.ok().build();
    }
}
