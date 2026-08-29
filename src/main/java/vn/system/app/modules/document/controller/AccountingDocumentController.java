package vn.system.app.modules.document.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.turkraft.springfilter.boot.Filter;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import vn.system.app.common.util.annotation.ApiMessage;
import vn.system.app.common.util.ScopeSpec;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.common.util.error.PermissionException;
import vn.system.app.modules.document.domain.Document;
import vn.system.app.modules.document.domain.request.AccountingDocumentRequest;
import vn.system.app.modules.document.domain.request.DocumentRequest;
import vn.system.app.modules.document.domain.response.ResDocumentDTO;
import vn.system.app.modules.document.service.DocumentService;
import vn.system.app.modules.document.util.DocumentAccessSpecs;
import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.modules.documentcategory.repository.DocumentCategoryRepository;
import vn.system.app.modules.documentcategory.domain.DocumentCategory;
import vn.system.app.modules.document.domain.DocumentAudit;
import vn.system.app.modules.document.repository.DocumentAuditRepository;

import java.util.UUID;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/accounting-documents")
@RequiredArgsConstructor
public class AccountingDocumentController {

    private static final String ACCOUNTING_CATEGORY_CODE = "ACCOUNTING_DOC";

    private final DocumentService service;
    private final DocumentCategoryRepository categoryRepository;
    private final DocumentAuditRepository auditRepository;

    // =====================================================
    // CREATE
    // =====================================================
    @PostMapping
    @ApiMessage("Tạo mới chứng từ kế toán")
    public ResponseEntity<ResDocumentDTO> create(@Valid @RequestBody AccountingDocumentRequest accountingReq) {
        DocumentRequest req = accountingReq.toDocumentRequest(
                getAccountingCategory().getId(),
                generateDocumentCode());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.handleCreate(req));
    }

    // =====================================================
    // UPDATE
    // =====================================================
    @PutMapping("/{id}")
    @ApiMessage("Cập nhật chứng từ kế toán")
    public ResponseEntity<ResDocumentDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody AccountingDocumentRequest accountingReq) {
        assertAccountingDocument(id, true);
        DocumentRequest req = accountingReq.toDocumentRequest(
                getAccountingCategory().getId(),
                generateDocumentCode());
        return ResponseEntity.ok(service.handleUpdate(id, req));
    }

    // =====================================================
    // DELETE
    // =====================================================
    @DeleteMapping("/{id}")
    @ApiMessage("Xoá chứng từ kế toán")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        assertAccountingDocument(id, true);
        service.handleDelete(id);
        return ResponseEntity.ok().build();
    }

    // =====================================================
    // LOCK / UNLOCK
    // =====================================================
    @PutMapping("/{id}/lock")
    @ApiMessage("Khoá/Mở khoá chứng từ kế toán")
    public ResponseEntity<Void> lock(@PathVariable Long id, @RequestParam boolean lockStatus) {
        assertAccountingDocument(id, false); // Kế toán/Admin có quyền xem thì được phép gọi hàm này (hàm trong service sẽ check quyền admin thêm)
        service.handleLockDocument(id, lockStatus);
        return ResponseEntity.ok().build();
    }

    // =====================================================
    // GET ONE
    // =====================================================
    @GetMapping("/{id}")
    @ApiMessage("Chi tiết chứng từ kế toán")
    public ResponseEntity<ResDocumentDTO> getOne(@PathVariable Long id) {
        Document document = assertAccountingDocument(id, false);
        return ResponseEntity.ok(service.convertToDTO(document));
    }

    // =====================================================
    // GET ALL (filter + paginate)
    // =====================================================
    @GetMapping
    @ApiMessage("Danh sách chứng từ kế toán")
    public ResponseEntity<ResultPaginationDTO> getAll(
            @Filter Specification<Document> spec,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String validity,
            @RequestParam(required = false) Boolean isLocked,
            Pageable pageable) {

        // 1. Chỉ lấy các document thuộc kho chứng từ kế toán.
        Specification<Document> accSpec = (root, query, cb) -> cb.equal(root.get("category").get("categoryCode"), ACCOUNTING_CATEGORY_CODE);
        accSpec = applyAccountingLookupSpec(accSpec, keyword, validity, isLocked);

        
        // 2. Phân quyền Cross-Company (Level 2)
        vn.system.app.common.util.UserScopeContext.UserScope scope = vn.system.app.common.util.UserScopeContext.get();
        if (scope == null) {
            throw new PermissionException("Không xác định được phạm vi người dùng, vui lòng đăng nhập lại");
        }
        if (scope.isSuperAdmin() || scope.isAdminLevel()) {
            // Xem toàn bộ chứng từ kế toán của tập đoàn
        } else {
            // Xem chứng từ của công ty hiện tại
            Specification<Document> companySpec = ScopeSpec.byCompanyScope("department.company.id");
            accSpec = accSpec.and(companySpec).and(DocumentAccessSpecs.confidentialGuard());
        }
        accSpec = accSpec.and(DocumentAccessSpecs.notExcluded());

        spec = spec == null ? accSpec : spec.and(accSpec);
        return ResponseEntity.ok(service.fetchAll(spec, pageable));
    }

    // =====================================================
    // EXPORT
    // =====================================================
    @GetMapping("/export")
    @ApiMessage("Lấy dữ liệu xuất Excel danh sách chứng từ kế toán")
    public ResponseEntity<java.util.List<ResDocumentDTO>> exportExcel(
            @Filter Specification<Document> spec,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String validity,
            @RequestParam(required = false) Boolean isLocked) {
        Specification<Document> accSpec = (root, query, cb) -> cb.equal(root.get("category").get("categoryCode"), ACCOUNTING_CATEGORY_CODE);
        accSpec = applyAccountingLookupSpec(accSpec, keyword, validity, isLocked);
        vn.system.app.common.util.UserScopeContext.UserScope scope = vn.system.app.common.util.UserScopeContext.get();
        if (scope == null) {
            throw new PermissionException("Không xác định được phạm vi người dùng, vui lòng đăng nhập lại");
        }
        if (scope.isSuperAdmin() || scope.isAdminLevel()) {
        } else {
            Specification<Document> companySpec = ScopeSpec.byCompanyScope("department.company.id");
            accSpec = accSpec.and(companySpec).and(DocumentAccessSpecs.confidentialGuard());
        }
        accSpec = accSpec.and(DocumentAccessSpecs.notExcluded());
        spec = spec == null ? accSpec : spec.and(accSpec);

        java.util.List<Document> list = service.fetchAllList(spec);
        java.util.List<ResDocumentDTO> dtoList = service.convertToDtoListBatched(list);
        return ResponseEntity.ok(dtoList);
    }

    // =====================================================
    // GET AUDITS
    // =====================================================
    @GetMapping("/{id}/audits")
    @ApiMessage("Lịch sử thao tác chứng từ")
    public ResponseEntity<java.util.List<DocumentAudit>> getAudits(@PathVariable Long id) {
        assertAccountingDocument(id, false);
        return ResponseEntity.ok(auditRepository.findByDocumentIdOrderByCreatedAtDesc(id));
    }

    private DocumentCategory getAccountingCategory() {
        return categoryRepository.findByCategoryCode(ACCOUNTING_CATEGORY_CODE)
                .orElseThrow(() -> new IdInvalidException("Danh mục hệ thống cho chứng từ kế toán chưa được khởi tạo"));
    }

    private Document assertAccountingDocument(Long id, boolean write) {
        Document document = service.fetchById(id);
        if (document.getCategory() == null
                || !ACCOUNTING_CATEGORY_CODE.equals(document.getCategory().getCategoryCode())) {
            throw new IdInvalidException("Chứng từ kế toán không tồn tại");
        }
        if (write) {
            service.validateAccountingWriteAccess(document);
        } else {
            service.validateAccountingReadAccess(document);
        }
        return document;
    }

    private Specification<Document> applyAccountingLookupSpec(
            Specification<Document> baseSpec,
            String keyword,
            String validity,
            Boolean isLocked) {
        Specification<Document> result = baseSpec;

        if (isLocked != null) {
            result = result.and((root, query, cb) -> {
                if (isLocked) {
                    return cb.isTrue(root.get("isLocked"));
                } else {
                    return cb.or(cb.isFalse(root.get("isLocked")), cb.isNull(root.get("isLocked")));
                }
            });
        }

        if (keyword != null && !keyword.isBlank()) {
            String normalizedKeyword = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
            Specification<Document> keywordSpec = (root, query, cb) -> {
                var department = root.join("department", jakarta.persistence.criteria.JoinType.LEFT);
                var accountingCategory = root.join("accountingCategory", jakarta.persistence.criteria.JoinType.LEFT);
                return cb.or(
                        cb.like(cb.lower(root.get("documentCode")), normalizedKeyword),
                        cb.like(cb.lower(root.get("documentName")), normalizedKeyword),
                        cb.like(cb.lower(department.get("name")), normalizedKeyword),
                        cb.like(cb.lower(accountingCategory.get("categoryName")), normalizedKeyword),
                        cb.like(cb.lower(accountingCategory.get("categoryCode")), normalizedKeyword),
                        cb.like(cb.lower(accountingCategory.get("symbol")), normalizedKeyword));
            };
            result = result.and(keywordSpec);
        }

        if (validity != null && !validity.isBlank()) {
            String normalizedValidity = validity.trim().toUpperCase(Locale.ROOT);
            if ("EFFECTIVE".equals(normalizedValidity)) {
                result = result.and((root, query, cb) -> cb.isTrue(root.get("active")));
            } else if ("CANCELLED".equals(normalizedValidity)) {
                result = result.and((root, query, cb) -> cb.isFalse(root.get("active")));
            }
        }

        return result;
    }

    private String generateDocumentCode() {
        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyMM"));
        String randomPart = UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        return "CT-" + datePart + "-" + randomPart;
    }
}
