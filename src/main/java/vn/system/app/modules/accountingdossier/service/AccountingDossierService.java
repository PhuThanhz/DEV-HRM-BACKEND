package vn.system.app.modules.accountingdossier.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Year;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.ScopeSpec;
import vn.system.app.common.util.UserScopeContext;
import vn.system.app.common.util.SecurityUtil;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.common.util.error.PermissionException;
import vn.system.app.common.util.error.DuplicateInvoiceWarningException;
import vn.system.app.common.config.AppProperties;
import vn.system.app.modules.accountingdossier.domain.AccountingDossier;
import vn.system.app.modules.accountingdossier.domain.AccountingDossierAuditLog;
import vn.system.app.modules.accountingdossier.domain.AccountingDossierCategory;
import vn.system.app.modules.accountingdossier.domain.AccountingDossierCategoryDocument;
import vn.system.app.modules.accountingdossier.domain.AccountingDossierCategoryDocumentId;
import vn.system.app.modules.accountingdossier.domain.AccountingDossierDocument;
import vn.system.app.modules.accountingdossier.domain.AccountingDossierDocumentVersion;
import vn.system.app.modules.accountingdossier.domain.AccountingDossierApprovalStep;
import vn.system.app.modules.accountingdossier.domain.AccountingDossierSequence;
import vn.system.app.modules.accountingdossier.domain.AccountingDossierOutbox;
import vn.system.app.modules.accountingdossier.repository.AccountingDossierOutboxRepository;
import vn.system.app.modules.accountingdossier.domain.enums.AccountingDossierCategoryMode;
import vn.system.app.modules.accountingdossier.domain.enums.AccountingDossierStatus;
import vn.system.app.modules.accountingdossier.domain.enums.AccountingDossierStorageStatus;
import vn.system.app.modules.accountingdossier.domain.enums.ApproverType;
import vn.system.app.modules.accountingdossier.domain.enums.ApprovalStepStatus;
import vn.system.app.modules.accountingdossier.domain.enums.WorkflowInstanceStatus;
import vn.system.app.modules.accountingdossier.domain.request.AccountingDossierActionRequest;
import vn.system.app.modules.accountingdossier.domain.request.AccountingDossierSubmitRequest;
import vn.system.app.modules.accountingdossier.domain.request.AccountingDossierDocumentCheckRequest;
import vn.system.app.modules.accountingdossier.domain.request.AccountingDossierCategoryRequest;
import vn.system.app.modules.accountingdossier.domain.request.AccountingDossierDocumentRequest;
import vn.system.app.modules.accountingdossier.domain.request.AccountingDossierRequest;
import vn.system.app.modules.accountingdossier.domain.response.ResAccountingDossierAuditLogDTO;
import vn.system.app.modules.accountingdossier.domain.response.ResAccountingDossierBulkActionDTO;
import vn.system.app.modules.accountingdossier.domain.response.ResAccountingDossierCategoryDTO;
import vn.system.app.modules.accountingdossier.domain.response.ResAccountingDossierDTO;
import vn.system.app.modules.accountingdossier.domain.response.ResAccountingDossierDocumentDTO;
import vn.system.app.modules.accountingdossier.domain.response.ResAccountingDossierApprovalStepDTO;
import vn.system.app.modules.accountingdossier.domain.response.ResAccountingDossierReportRowDTO;
import vn.system.app.modules.accountingdossier.domain.response.ResAccountingDossierStorageSummaryDTO;
import vn.system.app.modules.accountingdossier.domain.response.ResAccountingDossierDashboardMetricsDTO;
import vn.system.app.modules.accountingdossier.repository.AccountingDossierAuditLogRepository;
import vn.system.app.modules.accountingdossier.repository.AccountingDossierCategoryRepository;
import vn.system.app.modules.accountingdossier.repository.AccountingDossierDocumentRepository;
import vn.system.app.modules.accountingdossier.repository.AccountingDossierDocumentVersionRepository;
import vn.system.app.modules.accountingdossier.repository.AccountingDossierApprovalStepRepository;
import vn.system.app.modules.accountingdossier.repository.AccountingDossierRepository;
import vn.system.app.modules.accountingdossier.repository.AccountingDossierListProjection;
import vn.system.app.modules.accountingdossier.repository.AccountingDossierSequenceRepository;
import vn.system.app.modules.company.domain.Company;

import vn.system.app.modules.company.repository.CompanyRepository;
import vn.system.app.modules.department.domain.Department;
import vn.system.app.modules.department.repository.DepartmentRepository;
import vn.system.app.modules.document.domain.AccountingDocumentCategory;
import vn.system.app.modules.document.domain.Document;
import vn.system.app.modules.document.repository.AccountingDocumentCategoryRepository;
import vn.system.app.modules.document.repository.DocumentRepository;
import vn.system.app.modules.section.domain.Section;
import vn.system.app.modules.section.repository.SectionRepository;

@Service
public class AccountingDossierService {
    private final ThreadLocal<vn.system.app.modules.user.domain.User> bulkActionUser = new ThreadLocal<>();
    private static final long DEFAULT_APPROVAL_SLA_HOURS = 24;
    private static final Set<String> SUPPORTED_DOCUMENT_TYPES = Set.of(
            "PDF", "EXCEL", "WORD", "PPT", "CSV", "XML", "VIDEO_LINK", "NAS_PATH", "PNG", "JPG", "OTHER");
    private static final Set<String> SUPPORTED_DOCUMENT_CHECK_STATUSES = Set.of(
            "VALID", "NEED_SUPPLEMENT", "INVALID", "NOT_REQUIRED");
    private static final Set<String> REVIEWABLE_DOSSIER_STATUSES = Set.of(
            AccountingDossierStatus.SUBMITTED.name(),
            AccountingDossierStatus.IN_REVIEW.name());

    private final AccountingDossierRepository repository;
    private final CompanyRepository companyRepository;
    private final DepartmentRepository departmentRepository;
    private final SectionRepository sectionRepository;
    private final AccountingDossierSequenceRepository sequenceRepository;
    private final AccountingDossierAuditLogRepository auditLogRepository;
    private final AccountingDossierDocumentRepository documentItemRepository;
    private final AccountingDossierCategoryRepository dossierCategoryRepository;
    private final AccountingDocumentCategoryRepository accountingCategoryRepository;
    private final DocumentRepository documentRepository;
    private final AccountingDossierDocumentVersionRepository documentVersionRepository;
    private final vn.system.app.modules.user.repository.UserRepository userRepository;
    private final AccountingDossierApprovalStepRepository approvalStepRepository;
    private final AppProperties appProperties;
    private final ApproverResolutionService approverResolutionService;
    private final ApprovalStepGenerationService approvalStepGenerationService;
    private final DossierAuditService dossierAuditService;
    private final AccountingDossierOutboxRepository outboxRepository;
    private final AccountingApprovalDelegationService delegationService;
    private final AccountingDossierNotificationService notificationService;

    public AccountingDossierService(
            AccountingDossierRepository repository,
            CompanyRepository companyRepository,
            DepartmentRepository departmentRepository,
            SectionRepository sectionRepository,
            AccountingDossierSequenceRepository sequenceRepository,
            AccountingDossierAuditLogRepository auditLogRepository,
            AccountingDossierDocumentRepository documentItemRepository,
            AccountingDossierCategoryRepository dossierCategoryRepository,
            AccountingDocumentCategoryRepository accountingCategoryRepository,
            DocumentRepository documentRepository,
            AccountingDossierDocumentVersionRepository documentVersionRepository,
            vn.system.app.modules.user.repository.UserRepository userRepository,
            AccountingDossierApprovalStepRepository approvalStepRepository,
            AppProperties appProperties,
            ApproverResolutionService approverResolutionService,
            ApprovalStepGenerationService approvalStepGenerationService,
            DossierAuditService dossierAuditService,
            AccountingDossierOutboxRepository outboxRepository,
            AccountingApprovalDelegationService delegationService,
            AccountingDossierNotificationService notificationService) {
        this.repository = repository;
        this.companyRepository = companyRepository;
        this.departmentRepository = departmentRepository;
        this.sectionRepository = sectionRepository;
        this.sequenceRepository = sequenceRepository;
        this.auditLogRepository = auditLogRepository;
        this.documentItemRepository = documentItemRepository;
        this.dossierCategoryRepository = dossierCategoryRepository;
        this.accountingCategoryRepository = accountingCategoryRepository;
        this.documentRepository = documentRepository;
        this.documentVersionRepository = documentVersionRepository;
        this.userRepository = userRepository;
        this.approvalStepRepository = approvalStepRepository;
        this.appProperties = appProperties;
        this.approverResolutionService = approverResolutionService;
        this.approvalStepGenerationService = approvalStepGenerationService;
        this.dossierAuditService = dossierAuditService;
        this.outboxRepository = outboxRepository;
        this.delegationService = delegationService;
        this.notificationService = notificationService;
    }

    private void writeOutbox(Long dossierId, String eventType, String payloadJson, String idempotencyKey) {
        try {
            AccountingDossierOutbox outbox = new AccountingDossierOutbox();
            outbox.setDossierId(dossierId);
            outbox.setEventType(eventType);
            outbox.setIdempotencyKey(idempotencyKey);
            outbox.setPayload(payloadJson);
            outbox.setStatus("PENDING");
            outboxRepository.saveAndFlush(outbox);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new IdInvalidException("Yêu cầu đã được xử lý trước đó (IDEMPOTENT_REJECTED)");
        }
    }

    @Transactional
    public ResAccountingDossierDTO create(AccountingDossierRequest req) {
        Company company = resolveCompany(req.getCompanyId());
        Department department = resolveDepartment(req.getDepartmentId(), company.getId());
        Section section = resolveSection(req.getSectionId(), department.getId());

        validateCompanyScope(company.getId());

        AccountingDossier entity = new AccountingDossier();
        applyRequest(entity, req, company, department, section);
        entity.setStatus(AccountingDossierStatus.DRAFT);

        AccountingDossier saved = repository.save(entity);
        createTemplateDocumentRows(saved);
        dossierAuditService.writeLog(saved, "CREATE_DOSSIER", saved.getDossierCategory() == null
                ? saved.isSyncCategoryRequested()
                        ? "Tạo bộ chứng từ nháp phi cấu trúc, có đề xuất lưu thành mẫu"
                        : "Tạo bộ chứng từ nháp phi cấu trúc"
                : "Tạo bộ chứng từ nháp theo mẫu: " + saved.getDossierCategory().getCategoryName());
        return convertToDTO(saved);
    }

    @Transactional
    public ResAccountingDossierDTO update(Long id, AccountingDossierRequest req) {
        AccountingDossier current = fetchById(id);
        validateEditable(current);
        validateDossierMutator(current);

        Company company = resolveCompany(req.getCompanyId());
        Department department = resolveDepartment(req.getDepartmentId(), company.getId());
        Section section = resolveSection(req.getSectionId(), department.getId());

        validateCompanyScope(company.getId());
        Long oldCategoryId = current.getDossierCategory() == null ? null : current.getDossierCategory().getId();
        applyRequest(current, req, company, department, section);

        AccountingDossier saved = repository.save(current);
        Long newCategoryId = saved.getDossierCategory() == null ? null : saved.getDossierCategory().getId();
        dossierAuditService.writeLog(saved, "UPDATE_DOSSIER", !java.util.Objects.equals(oldCategoryId, newCategoryId)
                ? "Cập nhật bộ chứng từ, đổi mẫu sử dụng"
                : "Cập nhật bộ chứng từ");
        return convertToDTO(saved);
    }

    @Transactional
    public void delete(Long id) {
        AccountingDossier current = fetchById(id);
        validateEditable(current);
        validateDossierMutator(current);
        validateCompanyScope(current.getCompany().getId());
        current.setActive(false);
        current.setDeletedAt(Instant.now());
        current.setDeletedBy(vn.system.app.common.util.SecurityUtil.getCurrentUserLogin().orElse(""));
        repository.save(current);
        List<AccountingDossierDocument> items = documentItemRepository.findByDossierIdAndActiveTrue(id);
        items.forEach(item -> {
            item.setActive(false);
            item.setDeletedAt(Instant.now());
            item.setDeletedBy(current.getDeletedBy());
        });
        documentItemRepository.saveAll(items);
        dossierAuditService.writeLog(current, "SOFT_DELETE_DOSSIER", "Xóa mềm bộ chứng từ", "DOSSIER", current.getId(),
                current.getStatus().name(), current.getStatus().name(), null, "active=false");
    }

    @Transactional
    public ResAccountingDossierDTO submit(Long id) {
        return submit(id, null);
    }

    @Transactional
    public ResAccountingDossierDTO submit(Long id, AccountingDossierSubmitRequest req) {
        AccountingDossier dossier = fetchById(id);
        if (dossier.getStatus() != AccountingDossierStatus.DRAFT
                && dossier.getStatus() != AccountingDossierStatus.RETURNED
                && dossier.getStatus() != AccountingDossierStatus.REJECTED) {
            throw new PermissionException(
                    "Chỉ có thể chuyển xử lý bộ chứng từ đang ở trạng thái Nháp, Bị hoàn trả hoặc Bị từ chối");
        }
        validateDossierMutator(dossier);

        long docsCount = documentItemRepository.countByDossierIdAndActiveTrue(id);
        if (docsCount == 0) {
            throw new IdInvalidException("Không thể chuyển xử lý bộ chứng từ rỗng (cần ít nhất 1 chứng từ con)");
        }
        validateRequiredDocumentsBeforeSubmit(dossier);

        if (dossier.getDossierCode() == null || dossier.getDossierCode().trim().isEmpty()) {
            dossier.setDossierCode(generateDossierCode(dossier.getCompany()));
        }

        if (dossier.getQrToken() == null || dossier.getQrToken().isEmpty()) {
            dossier.setQrToken(UUID.randomUUID().toString());
            String qrUrl = appProperties.getBaseUrl() + "/admin/accounting-dossiers/qr/" + dossier.getQrToken();
            dossier.setQrCode(vn.system.app.common.util.QrCodeUtil.generateBase64(qrUrl));
        }

        AccountingDossierStatus oldStatus = dossier.getStatus();
        dossier.setStatus(AccountingDossierStatus.SUBMITTED);
        dossier.setSubmittedAt(Instant.now());
        approvalStepGenerationService.generateApprovalSteps(dossier, req);

        AccountingDossier saved = saveDossierWithOptimisticLockCheck(dossier);
        dossierAuditService.writeLog(saved, "SUBMIT_DOSSIER", "Chuyển bộ chứng từ và cấp mã hệ thống", "DOSSIER", saved.getId(),
                oldStatus.name(), saved.getStatus().name(), null, saved.getDossierCode());
        approvalStepRepository.findByDossierIdAndStatusAndActiveTrue(saved.getId(), ApprovalStepStatus.CURRENT)
                .ifPresent(currentStep -> notificationService.notifyApprovalStep(saved, currentStep, "ACCOUNTING_DOSSIER_SUBMITTED",
                        String.format("Bộ chứng từ %s đã được gửi phê duyệt. Vui lòng xử lý bước %s.",
                                notificationService.dossierLabel(saved), currentStep.getStepName())));
        writeOutbox(saved.getId(), "SUBMIT", "Dossier submitted: " + saved.getDossierCode(), "DOSSIER_" + saved.getId() + "_SUBMIT_RETURN_" + saved.getReturnCount());
        return convertToDTO(saved);
    }

    @Transactional
    public ResAccountingDossierDTO reopen(Long id) {
        AccountingDossier dossier = fetchById(id);
        if (dossier.getStatus() != AccountingDossierStatus.REJECTED) {
            throw new PermissionException("Chỉ có thể mở lại bộ chứng từ đang ở trạng thái Bị từ chối (REJECTED)");
        }
        validateDossierMutator(dossier);

        AccountingDossierStatus oldStatus = dossier.getStatus();
        dossier.setStatus(AccountingDossierStatus.DRAFT);
        
        AccountingDossier saved = saveDossierWithOptimisticLockCheck(dossier);
        dossierAuditService.writeLog(saved, "REOPEN_DOSSIER", "Mở lại bộ chứng từ bị từ chối thành nháp", "DOSSIER", saved.getId(),
                oldStatus.name(), saved.getStatus().name(), null, null);
        
        return convertToDTO(saved);
    }

    @Transactional
    public ResAccountingDossierDTO requestReturn(Long id, AccountingDossierActionRequest req) {
        AccountingDossier dossier = fetchById(id);
        if (dossier.getStatus() != AccountingDossierStatus.SUBMITTED
                && dossier.getStatus() != AccountingDossierStatus.IN_REVIEW) {
            throw new PermissionException("Chỉ bộ chứng từ đã chuyển xử lý mới được yêu cầu hoàn chứng từ");
        }

        vn.system.app.modules.user.domain.User currentUser = userRepository
                .findByEmail(SecurityUtil.getCurrentUserLogin().orElse(""));
        if (currentUser == null) {
            throw new IdInvalidException("Không xác định được người yêu cầu hoàn chứng từ");
        }
        if (!Objects.equals(dossier.getCreatorId(), currentUser.getId())) {
            throw new PermissionException("Chỉ người lập hồ sơ mới được yêu cầu hoàn chứng từ");
        }

        AccountingDossierStatus oldStatus = dossier.getStatus();
        dossier.setStatus(AccountingDossierStatus.RETURN_REQUESTED);

        AccountingDossier saved = saveDossierWithOptimisticLockCheck(dossier);
        dossierAuditService.writeLog(saved, "REQUEST_RETURN_DOSSIER",
                normalizeNote(req == null ? null : req.getNote(), "Người lập yêu cầu hoàn chứng từ"));
        approvalStepRepository.findByDossierIdAndStatusAndActiveTrue(saved.getId(), ApprovalStepStatus.CURRENT)
                .ifPresent(currentStep -> notificationService.notifyApprovalStep(saved, currentStep, "ACCOUNTING_RETURN_REQUESTED",
                        String.format("Người lập hồ sơ yêu cầu hoàn trả bộ chứng từ %s. Vui lòng xem xét yêu cầu.",
                                notificationService.dossierLabel(saved))));
        return convertToDTO(saved);
    }

    @Transactional
    public ResAccountingDossierDTO approve(Long id, AccountingDossierActionRequest req) {
        AccountingDossier dossier = fetchById(id);
        if (dossier.getStatus() != AccountingDossierStatus.SUBMITTED
                && dossier.getStatus() != AccountingDossierStatus.IN_REVIEW) {
            throw new PermissionException("Hồ sơ không ở trạng thái phê duyệt");
        }

        List<AccountingDossierDocument> docs = documentItemRepository.findByDossierIdAndActiveTrue(id);
        if (docs != null) {
            boolean hasInvalidDoc = docs.stream().anyMatch(doc ->
                    "INVALID".equalsIgnoreCase(doc.getCheckStatus()) ||
                    "NEED_SUPPLEMENT".equalsIgnoreCase(doc.getCheckStatus())
            );
            if (hasInvalidDoc) {
                throw new IdInvalidException("Bộ chứng từ có chứng từ không hợp lệ hoặc cần bổ sung");
            }
        }

        String email = SecurityUtil.getCurrentUserLogin().orElse("");
        vn.system.app.modules.user.domain.User currentUser = resolveActionUser(email);
        if (currentUser == null) {
            throw new IdInvalidException("Không xác định được người duyệt");
        }

        List<AccountingDossierApprovalStep> steps = approvalStepRepository
                .findByDossierIdAndActiveTrueOrderByStepOrderAsc(id);
        AccountingDossierApprovalStep currentStep = steps.stream()
                .filter(step -> step.getStatus() == ApprovalStepStatus.CURRENT)
                .findFirst()
                .orElseThrow(() -> new IdInvalidException("Không có bước phê duyệt hiện tại nào đang chờ"));

        validateApprover(currentStep, currentUser);

        currentStep.setStatus(ApprovalStepStatus.APPROVED);
        currentStep.setActedAt(Instant.now());
        currentStep.setActionNote(req == null ? null : req.getNote());
        
        try {
            approvalStepRepository.saveAndFlush(currentStep);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            throw new IdInvalidException("Hồ sơ đang được xử lý bởi người khác (DOSSIER_CONCURRENTLY_MODIFIED)");
        }

        // Find next step
        AccountingDossierApprovalStep nextStep = null;
        for (AccountingDossierApprovalStep step : steps) {
            if (step.getStepOrder() > currentStep.getStepOrder() && step.getStatus() != ApprovalStepStatus.SKIPPED) {
                nextStep = step;
                break;
            }
        }

        AccountingDossierStatus oldStatus = dossier.getStatus();
        if (nextStep != null) {
            applyNextApproverSelection(nextStep, req);
            nextStep.setStatus(ApprovalStepStatus.CURRENT);
            nextStep.setCreatedAt(Instant.now());
            nextStep.setDueAt(calculateApprovalDueAt(nextStep));
            approvalStepRepository.save(nextStep);
            dossier.setStatus(AccountingDossierStatus.IN_REVIEW);
            saveDossierWithOptimisticLockCheck(dossier);
            dossierAuditService.writeLog(dossier, "APPROVE_DOSSIER_STEP", "Phê duyệt bước: " + currentStep.getStepName(),
                    "APPROVAL_STEP", currentStep.getId(), oldStatus.name(), dossier.getStatus().name(),
                    null, currentStep.getStepName());
            notificationService.notifyApprovalStep(dossier, nextStep, "ACCOUNTING_APPROVAL_NEEDED",
                    String.format("Bộ chứng từ %s đang chờ bạn xử lý bước %s.",
                            notificationService.dossierLabel(dossier), nextStep.getStepName()));
            writeOutbox(dossier.getId(), "APPROVE", "Dossier approved by step: " + currentStep.getStepName(), "DOSSIER_" + dossier.getId() + "_APPROVE_STEP_" + currentStep.getId());
        } else {
            // Final approval!
            // Check that all required documents are VALID (no PENDING, INVALID, NEED_SUPPLEMENT, NOT_REQUIRED)
            // and all optional documents are not INVALID or NEED_SUPPLEMENT.
            if (docs != null && !docs.isEmpty()) {
                Set<Long> requiredCategoryIds = new java.util.HashSet<>();
                if (dossier.getCategoryMode() == AccountingDossierCategoryMode.TEMPLATE && dossier.getDossierCategory() != null) {
                    dossier.getDossierCategory().getCategoryDocuments().stream()
                            .filter(AccountingDossierCategoryDocument::isRequired)
                            .filter(cd -> cd.getDocumentCategory() != null)
                            .forEach(cd -> requiredCategoryIds.add(cd.getDocumentCategory().getId()));
                }
                
                for (AccountingDossierDocument doc : docs) {
                    boolean isRequired = false;
                    if (dossier.getCategoryMode() == AccountingDossierCategoryMode.TEMPLATE) {
                        if (doc.getAccountingCategory() != null && requiredCategoryIds.contains(doc.getAccountingCategory().getId())) {
                            isRequired = true;
                        }
                    }
                    if (isRequired) {
                        if (!"VALID".equalsIgnoreCase(doc.getCheckStatus())) {
                            throw new IdInvalidException("Chứng từ bắt buộc '" + doc.getDocumentName() + "' phải ở trạng thái Hợp lệ (VALID) ở bước duyệt cuối cùng");
                        }
                    } else {
                        if ("INVALID".equalsIgnoreCase(doc.getCheckStatus()) || "NEED_SUPPLEMENT".equalsIgnoreCase(doc.getCheckStatus())) {
                            throw new IdInvalidException("Chứng từ tùy chọn '" + doc.getDocumentName() + "' không hợp lệ hoặc cần bổ sung");
                        }
                    }
                }
            }

            dossier.setStatus(AccountingDossierStatus.APPROVED);
            dossier.setApprovedAt(Instant.now());
            saveDossierWithOptimisticLockCheck(dossier);
            approvalStepGenerationService.completeActiveInstance(id, WorkflowInstanceStatus.COMPLETED);
            dossierAuditService.writeLog(dossier, "APPROVE_DOSSIER_FINAL", "Phê duyệt cuối cùng bộ chứng từ",
                    "DOSSIER", dossier.getId(), oldStatus.name(), dossier.getStatus().name(),
                    null, dossier.getDossierCode());
            notificationService.notifyCreator(dossier, "ACCOUNTING_APPROVED",
                    String.format("Bộ chứng từ %s đã được phê duyệt hoàn tất.",
                            notificationService.dossierLabel(dossier)));
            writeOutbox(dossier.getId(), "APPROVE_FINAL", "Dossier fully approved: " + dossier.getDossierCode(), "DOSSIER_" + dossier.getId() + "_APPROVE_FINAL_STEP_" + currentStep.getId());
            // Phase 3: Auto sync unstructured dossier to category template if requested
            if (dossier.isSyncCategoryRequested()
                    && dossier
                            .getCategoryMode() == vn.system.app.modules.accountingdossier.domain.enums.AccountingDossierCategoryMode.UNSTRUCTURED) {
                syncToCategoryTemplate(dossier);
            }
        }

        return convertToDTO(dossier);
    }

    private void applyNextApproverSelection(AccountingDossierApprovalStep nextStep, AccountingDossierActionRequest req) {
        if (req == null || req.getNextApproverUserId() == null || req.getNextApproverUserId().isBlank()) {
            return;
        }
        if (nextStep.getApproverUserId() != null && !nextStep.getApproverUserId().isBlank()) {
            return;
        }

        String selectedUserId = req.getNextApproverUserId().trim();
        vn.system.app.modules.user.domain.User selectedUser = userRepository.findById(selectedUserId)
                .orElseThrow(() -> new IdInvalidException("Người nhận kế tiếp không tồn tại"));

        if (nextStep.getEligibleApproverIds() != null && !nextStep.getEligibleApproverIds().isBlank()) {
            boolean eligible = java.util.Arrays.stream(nextStep.getEligibleApproverIds().split(","))
                    .map(String::trim)
                    .anyMatch(selectedUserId::equals);
            if (!eligible) {
                throw new PermissionException("Người được chọn không thuộc danh sách có quyền xử lý bước kế tiếp");
            }
        } else if (!matchesApproverType(selectedUser, nextStep.getApproverType())) {
            throw new PermissionException("Người được chọn không phù hợp vai trò xử lý bước kế tiếp");
        }

        nextStep.setApproverUserId(selectedUserId);
        nextStep.setEligibleApproverIds(null);
    }

    private boolean matchesApproverType(vn.system.app.modules.user.domain.User user, ApproverType approverType) {
        if (user == null || approverType == null) {
            return false;
        }
        String roleName = user.getRole() == null || user.getRole().getName() == null
                ? ""
                : user.getRole().getName().toUpperCase();
        if (approverType == ApproverType.ACCOUNTANT) {
            return (roleName.contains("ACCOUNTANT") || roleName.contains("KETOAN") || roleName.contains("KẾ TOÁN"))
                    && !roleName.contains("CHIEF")
                    && !roleName.contains("TRUONG")
                    && !roleName.contains("TRƯỞNG");
        }
        if (approverType == ApproverType.CHIEF_ACCOUNTANT) {
            return roleName.contains("CHIEF")
                    || roleName.contains("KETOANTRUONG")
                    || roleName.contains("KẾ TOÁN TRƯỞNG");
        }
        if (approverType == ApproverType.DIRECTOR) {
            return user.getRole() != null && user.getRole().getPermissions() != null
                    && user.getRole().getPermissions().stream()
                            .anyMatch(p -> "Phê duyệt bộ chứng từ kế toán - Giám đốc".equals(p.getName()));
        }
        if (approverType == ApproverType.CUSTOM) {
            return true;
        }
        return false;
    }

    @Transactional
    public ResAccountingDossierDTO claim(Long id) {
        AccountingDossier dossier = fetchById(id);
        if (dossier.getStatus() != AccountingDossierStatus.SUBMITTED
                && dossier.getStatus() != AccountingDossierStatus.IN_REVIEW) {
            throw new PermissionException("Hồ sơ không ở trạng thái có thể nhận xử lý");
        }

        String email = SecurityUtil.getCurrentUserLogin().orElse("");
        vn.system.app.modules.user.domain.User currentUser = resolveActionUser(email);
        if (currentUser == null) {
            throw new IdInvalidException("Không xác định được người nhận xử lý");
        }

        AccountingDossierApprovalStep currentStep = approvalStepRepository
                .findByDossierIdAndStatusAndActiveTrue(id, ApprovalStepStatus.CURRENT)
                .orElseThrow(() -> new IdInvalidException("Không có bước phê duyệt hiện tại nào đang chờ"));

        if (currentStep.getApproverUserId() != null) {
            throw new IdInvalidException("Bước này đã được giao cho một người xử lý cụ thể");
        }
        if (currentStep.getApproverType() == ApproverType.DEPARTMENT_MANAGER) {
            throw new PermissionException("Bước Trưởng bộ phận phải được gán đích danh, không nhận từ hàng đợi");
        }

        validateApprover(currentStep, currentUser);
        currentStep.setApproverUserId(currentUser.getId());
        try {
            approvalStepRepository.saveAndFlush(currentStep);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            throw new IdInvalidException("Bước phê duyệt đang được nhận bởi người khác (DOSSIER_CONCURRENTLY_MODIFIED)");
        }

        dossierAuditService.writeLog(dossier, "CLAIM_APPROVAL_STEP",
                "Nhận xử lý bước: " + currentStep.getStepName(),
                "APPROVAL_STEP", currentStep.getId(), null, currentUser.getId(), null, null);
        java.util.Set<String> claimRecipients = new java.util.LinkedHashSet<>();
        claimRecipients.add(dossier.getCreatorId());
        claimRecipients.addAll(notificationService.resolveStepRecipientIds(currentStep));
        claimRecipients.remove(currentUser.getId());
        notificationService.notifyUsers(claimRecipients, "ACCOUNTING_DOSSIER_CLAIMED",
                String.format("%s đã nhận xử lý bước %s của bộ chứng từ %s.",
                        currentUser.getName(), currentStep.getStepName(), notificationService.dossierLabel(dossier)),
                dossier.getId());
        writeOutbox(dossier.getId(), "CLAIM", "Approval step claimed: " + currentStep.getStepName(),
                "DOSSIER_" + dossier.getId() + "_CLAIM_STEP_" + currentStep.getId());
        return convertToDTO(dossier);
    }

    @Transactional
    public ResAccountingDossierDTO reject(Long id, AccountingDossierActionRequest req) {
        if (req == null || req.getNote() == null || req.getNote().trim().isEmpty()) {
            throw new IdInvalidException("Lý do từ chối không được để trống");
        }

        AccountingDossier dossier = fetchById(id);
        if (dossier.getStatus() != AccountingDossierStatus.SUBMITTED
                && dossier.getStatus() != AccountingDossierStatus.IN_REVIEW) {
            throw new PermissionException("Hồ sơ không ở trạng thái phê duyệt");
        }

        String email = SecurityUtil.getCurrentUserLogin().orElse("");
        vn.system.app.modules.user.domain.User currentUser = resolveActionUser(email);
        if (currentUser == null) {
            throw new IdInvalidException("Không xác định được người thao tác");
        }

        List<AccountingDossierApprovalStep> steps = approvalStepRepository
                .findByDossierIdAndActiveTrueOrderByStepOrderAsc(id);
        AccountingDossierApprovalStep currentStep = steps.stream()
                .filter(step -> step.getStatus() == ApprovalStepStatus.CURRENT)
                .findFirst()
                .orElseThrow(() -> new IdInvalidException("Không có bước phê duyệt hiện tại nào đang chờ"));

        validateApprover(currentStep, currentUser);

        currentStep.setStatus(ApprovalStepStatus.REJECTED);
        currentStep.setActedAt(Instant.now());
        currentStep.setActionNote(req.getNote());
        
        try {
            approvalStepRepository.saveAndFlush(currentStep);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            throw new IdInvalidException("Hồ sơ đang được xử lý bởi người khác (DOSSIER_CONCURRENTLY_MODIFIED)");
        }

        AccountingDossierStatus oldStatus = dossier.getStatus();
        dossier.setStatus(AccountingDossierStatus.REJECTED);
        saveDossierWithOptimisticLockCheck(dossier);
        approvalStepGenerationService.completeActiveInstance(id, WorkflowInstanceStatus.REJECTED);

        dossierAuditService.writeLog(dossier, "REJECT_DOSSIER", "Từ chối phê duyệt bộ chứng từ: " + req.getNote(),
                "DOSSIER", dossier.getId(), oldStatus.name(), dossier.getStatus().name(),
                null, req.getNote());
        notificationService.notifyCreator(dossier, "ACCOUNTING_REJECTED",
                String.format("Bộ chứng từ %s đã bị từ chối. Lý do: %s",
                        notificationService.dossierLabel(dossier), req.getNote()));
        writeOutbox(dossier.getId(), "REJECT", "Dossier rejected with reason: " + req.getNote(), "DOSSIER_" + dossier.getId() + "_REJECT_STEP_" + currentStep.getId());

        return convertToDTO(dossier);
    }

    @Transactional
    public ResAccountingDossierDTO terminate(Long id, AccountingDossierActionRequest req) {
        if (req == null || req.getNote() == null || req.getNote().trim().isEmpty()) {
            throw new IdInvalidException("Lý do chấm dứt không được để trống");
        }

        AccountingDossier dossier = fetchById(id);
        if (dossier.getStatus() == AccountingDossierStatus.APPROVED
                || dossier.getStatus() == AccountingDossierStatus.TERMINATED
                || dossier.getStatus() == AccountingDossierStatus.ARCHIVED) {
            throw new PermissionException("Không thể chấm dứt bộ chứng từ đã duyệt, đã lưu trữ hoặc đã chấm dứt");
        }

        vn.system.app.modules.user.domain.User currentUser = userRepository.findByEmail(SecurityUtil.getCurrentUserLogin().orElse(""));
        if (currentUser == null || currentUser.getRole() == null) {
            throw new PermissionException("Không xác định được quyền chấm dứt bộ chứng từ");
        }
        String roleName = currentUser.getRole().getName().toUpperCase();
        if (!"SUPER_ADMIN".equals(roleName) && !"CHIEF_ACCOUNTANT".equals(roleName) && !"KETOANTRUONG".equals(roleName)) {
            throw new PermissionException("Chỉ Kế toán trưởng hoặc Super Admin mới được quyền chấm dứt");
        }

        AccountingDossierStatus oldStatus = dossier.getStatus();
        dossier.setStatus(AccountingDossierStatus.TERMINATED);
        dossier.setTerminatedAt(Instant.now());
        saveDossierWithOptimisticLockCheck(dossier);
        approvalStepGenerationService.completeActiveInstance(id, WorkflowInstanceStatus.CANCELLED);

        // Terminate current step if exists
        List<AccountingDossierApprovalStep> steps = approvalStepRepository
                .findByDossierIdAndActiveTrueOrderByStepOrderAsc(id);
        steps.stream()
                .filter(step -> step.getStatus() == ApprovalStepStatus.CURRENT)
                .findFirst()
                .ifPresent(step -> {
                    step.setStatus(ApprovalStepStatus.REJECTED);
                    step.setActedAt(Instant.now());
                    step.setActionNote("[CHẤM DỨT] " + req.getNote());
                    try {
                        approvalStepRepository.saveAndFlush(step);
                    } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                        throw new IdInvalidException("Hồ sơ đang được xử lý bởi người khác (DOSSIER_CONCURRENTLY_MODIFIED)");
                    }
                });

        dossierAuditService.writeLog(dossier, "TERMINATE_DOSSIER", "Chấm dứt bộ chứng từ: " + req.getNote(),
                "DOSSIER", dossier.getId(), oldStatus.name(), dossier.getStatus().name(),
                null, req.getNote());
        notificationService.notifyCreator(dossier, "ACCOUNTING_TERMINATED",
                String.format("Bộ chứng từ %s đã bị chấm dứt. Lý do: %s",
                        notificationService.dossierLabel(dossier), req.getNote()));
        writeOutbox(dossier.getId(), "TERMINATE", "Dossier terminated with reason: " + req.getNote(), "DOSSIER_" + dossier.getId() + "_TERMINATE");

        return convertToDTO(dossier);
    }

    @Transactional
    public ResAccountingDossierDTO handleReturnResponse(Long id, String action, AccountingDossierActionRequest req) {
        AccountingDossier dossier = fetchById(id);
        if (dossier.getStatus() != AccountingDossierStatus.RETURN_REQUESTED) {
            throw new PermissionException("Hồ sơ không có yêu cầu hoàn trả nào đang chờ");
        }

        String email = SecurityUtil.getCurrentUserLogin().orElse("");
        vn.system.app.modules.user.domain.User currentUser = userRepository.findByEmail(email);
        if (currentUser == null) {
            throw new IdInvalidException("Không xác định được người thao tác");
        }

        List<AccountingDossierApprovalStep> steps = approvalStepRepository
                .findByDossierIdAndActiveTrueOrderByStepOrderAsc(id);
        AccountingDossierApprovalStep currentStep = steps.stream()
                .filter(step -> step.getStatus() == ApprovalStepStatus.CURRENT)
                .findFirst()
                .orElseThrow(() -> new IdInvalidException("Không có bước phê duyệt hiện tại nào đang chờ"));

        validateApprover(currentStep, currentUser);

        AccountingDossierStatus oldStatus = dossier.getStatus();

        if ("ACCEPT".equalsIgnoreCase(action)) {
            if (currentStep.getApproverType() == ApproverType.DIRECTOR) {
                if (req == null || req.getNote() == null || req.getNote().trim().isEmpty()) {
                    throw new IdInvalidException("Vui lòng nhập lý do hoàn trả tại bước Giám đốc");
                }
            }
            dossier.setStatus(AccountingDossierStatus.RETURNED);
            dossier.setReturnCount(dossier.getReturnCount() + 1);
            saveDossierWithOptimisticLockCheck(dossier);

            currentStep.setStatus(ApprovalStepStatus.RETURNED);
            currentStep.setActedAt(Instant.now());
            currentStep
                    .setActionNote(normalizeNote(req == null ? null : req.getNote(), "Chấp nhận hoàn trả để sửa đổi"));
            
            try {
                approvalStepRepository.saveAndFlush(currentStep);
            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                throw new IdInvalidException("Hồ sơ đang được xử lý bởi người khác (DOSSIER_CONCURRENTLY_MODIFIED)");
            }

            dossierAuditService.writeLog(dossier, "ACCEPT_RETURN_DOSSIER", "Chấp nhận hoàn trả bộ chứng từ: " + currentStep.getActionNote(),
                    "DOSSIER", dossier.getId(), oldStatus.name(), dossier.getStatus().name(),
                    String.valueOf(dossier.getReturnCount() - 1), String.valueOf(dossier.getReturnCount()));
            notificationService.notifyCreator(dossier, "ACCOUNTING_RETURN_ACCEPTED",
                    String.format("Yêu cầu hoàn trả bộ chứng từ %s đã được chấp nhận. Vui lòng cập nhật và gửi phê duyệt lại.",
                            notificationService.dossierLabel(dossier)));
            writeOutbox(dossier.getId(), "RETURN", "Dossier returned with reason: " + currentStep.getActionNote(), "DOSSIER_" + dossier.getId() + "_RETURN_STEP_" + currentStep.getId());
        } else if ("REJECT".equalsIgnoreCase(action)) {
            dossier.setStatus(AccountingDossierStatus.IN_REVIEW);
            saveDossierWithOptimisticLockCheck(dossier);

            dossierAuditService.writeLog(dossier, "REJECT_RETURN_DOSSIER",
                    "Từ chối hoàn trả bộ chứng từ: " + (req == null ? "" : req.getNote()),
                    "DOSSIER", dossier.getId(), oldStatus.name(), dossier.getStatus().name(),
                    null, req == null ? null : req.getNote());
            notificationService.notifyCreator(dossier, "ACCOUNTING_RETURN_REJECTED",
                    String.format("Yêu cầu hoàn trả bộ chứng từ %s đã bị từ chối. Hồ sơ tiếp tục luồng phê duyệt.",
                            notificationService.dossierLabel(dossier)));
            writeOutbox(dossier.getId(), "REJECT_RETURN", "Rejected return with reason: " + (req == null ? "" : req.getNote()), "DOSSIER_" + dossier.getId() + "_REJECT_RETURN_" + currentStep.getId());
        } else {
            throw new IdInvalidException("Hành động không hợp lệ: ACCEPT hoặc REJECT");
        }

        return convertToDTO(dossier);
    }

    @Transactional(readOnly = true)
    public List<ResAccountingDossierApprovalStepDTO> getApprovalSteps(Long dossierId) {
        fetchById(dossierId);
        List<AccountingDossierApprovalStep> steps = approvalStepRepository
                .findByDossierIdAndActiveTrueOrderByStepOrderAsc(dossierId);
        Set<String> approverIds = steps.stream().map(AccountingDossierApprovalStep::getApproverUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, String> approverNames = approverIds.isEmpty() ? Map.of() : userRepository.findAllById(approverIds).stream()
                .collect(Collectors.toMap(vn.system.app.modules.user.domain.User::getId, vn.system.app.modules.user.domain.User::getName, (a, b) -> a));
        return steps.stream().map(step -> convertToStepDTO(step, approverNames)).collect(Collectors.toList());
    }

    private ResAccountingDossierApprovalStepDTO convertToStepDTO(AccountingDossierApprovalStep entity, Map<String, String> approverNames) {
        ResAccountingDossierApprovalStepDTO dto = new ResAccountingDossierApprovalStepDTO();
        dto.setId(entity.getId());
        dto.setDossierId(entity.getDossier().getId());
        dto.setStepOrder(entity.getStepOrder());
        dto.setStepName(entity.getStepName());
        dto.setApproverType(entity.getApproverType() == null ? null : entity.getApproverType().name());
        dto.setApproverUserId(entity.getApproverUserId());
        dto.setEligibleApproverIds(entity.getEligibleApproverIds());
        dto.setStatus(entity.getStatus() == null ? null : entity.getStatus().name());
        dto.setActionNote(entity.getActionNote());
        dto.setActedAt(entity.getActedAt());
        dto.setCreatedAt(entity.getCreatedAt());

        dto.setApproverName(approverNames.get(entity.getApproverUserId()));
        return dto;
    }

    private void validateApprover(AccountingDossierApprovalStep step, vn.system.app.modules.user.domain.User user) {
        String dossierId = step.getDossier() != null ? step.getDossier().getCreatorId() : null;
        if (dossierId != null && user.getId().equals(dossierId)) {
            throw new PermissionException("Bạn không thể tự phê duyệt bước này trong hồ sơ do chính mình lập (SELF_APPROVAL_BLOCKED)");
        }

        if (user.getRole() != null && user.getRole().getName().equals("SUPER_ADMIN")) {
            if (step.getApproverType() == ApproverType.DIRECTOR) {
                throw new PermissionException("SUPER_ADMIN không được duyệt thay bước Giám đốc. Vui lòng sử dụng chức năng Emergency Reassign.");
            }
            return;
        }

        if (step.getApproverUserId() != null) {
            boolean directAssignee = step.getApproverUserId().equals(user.getId());
            boolean delegatedAssignee = step.isAllowDelegation() && delegationService.canActAsDelegate(step.getApproverUserId(), user.getId(), step.getDossier());
            if (!directAssignee && !delegatedAssignee) {
                throw new PermissionException("Bạn không có quyền xử lý bước phê duyệt này");
            }
        } else {
            if (step.getEligibleApproverIds() != null && !step.getEligibleApproverIds().isBlank()) {
                boolean eligible = java.util.Arrays.stream(step.getEligibleApproverIds().split(","))
                        .anyMatch(user.getId()::equals);
                if (!eligible) {
                    throw new PermissionException("Bạn không thuộc hàng đợi người duyệt phù hợp của bước này");
                }
                return;
            }
            String roleName = user.getRole() == null ? "" : user.getRole().getName().toUpperCase();
            if (step.getApproverType() == ApproverType.ACCOUNTANT) {
                if (!roleName.contains("ACCOUNTANT") && !roleName.contains("KETOAN") && !roleName.contains("KẾ TOÁN")) {
                    throw new PermissionException("Chỉ kế toán mới có quyền xử lý bước này");
                }
                validateStepCompanyMembership(step);
            } else if (step.getApproverType() == ApproverType.CHIEF_ACCOUNTANT) {
                if (!roleName.contains("CHIEF_ACCOUNTANT") && !roleName.contains("KETOANTRUONG")
                        && !roleName.contains("KẾ TOÁN TRƯỞNG")) {
                    throw new PermissionException("Chỉ kế toán trưởng mới có quyền xử lý bước này");
                }
                validateStepCompanyMembership(step);
            } else if (step.getApproverType() == ApproverType.DEPARTMENT_MANAGER) {
                throw new PermissionException("Bạn không có quyền xử lý bước phê duyệt này");
            } else if (step.getApproverType() == ApproverType.DIRECTOR) {
                boolean hasDirectorPermission = user.getRole() != null && user.getRole().getPermissions().stream()
                        .anyMatch(p -> "Phê duyệt bộ chứng từ kế toán - Giám đốc".equals(p.getName()));
                if (!hasDirectorPermission) {
                    throw new PermissionException("Chỉ Giám đốc mới có quyền xử lý bước này");
                }
                validateStepCompanyMembership(step);
            } else {
                throw new PermissionException("Bạn không có quyền xử lý bước phê duyệt này (CUSTOM_STEP_NO_APPROVERS)");
            }
        }
    }

    /** Chặn user thuộc công ty khác duyệt hộ khi bước chưa được gán đích danh (fallback theo role name). */
    private void validateStepCompanyMembership(AccountingDossierApprovalStep step) {
        if (step.getDossier() == null || step.getDossier().getCompany() == null) {
            return;
        }
        validateCompanyScope(step.getDossier().getCompany().getId());
    }

    private Instant calculateApprovalDueAt(AccountingDossierApprovalStep step) {
        int slaMinutes = step.getSlaMinutes() != null && step.getSlaMinutes() > 0
                ? step.getSlaMinutes()
                : (int) (DEFAULT_APPROVAL_SLA_HOURS * 60);
        return Instant.now().plusSeconds(slaMinutes * 60L);
    }

    private void validateDocumentReviewer(AccountingDossier dossier) {
        String email = SecurityUtil.getCurrentUserLogin().orElse("");
        vn.system.app.modules.user.domain.User currentUser = userRepository.findByEmail(email);
        if (currentUser == null) {
            throw new IdInvalidException("Không xác định được người kiểm tra chứng từ");
        }
        if (currentUser.getRole() != null && "SUPER_ADMIN".equals(currentUser.getRole().getName())) {
            return;
        }

        AccountingDossierApprovalStep currentStep = approvalStepRepository
                .findByDossierIdAndActiveTrueOrderByStepOrderAsc(dossier.getId())
                .stream()
                .filter(step -> step.getStatus() == ApprovalStepStatus.CURRENT)
                .findFirst()
                .orElseThrow(() -> new IdInvalidException("Không có bước phê duyệt hiện tại nào đang chờ"));

        boolean eligibleReviewer = currentStep.getEligibleApproverIds() != null
                && java.util.Arrays.stream(currentStep.getEligibleApproverIds().split(","))
                        .anyMatch(currentUser.getId()::equals)
                && currentStep.getStepName() != null
                && currentStep.getStepName().toLowerCase(java.util.Locale.ROOT).contains("kế toán");
        if (currentStep.getApproverType() != ApproverType.ACCOUNTANT && !eligibleReviewer) {
            throw new PermissionException("Chỉ bước kế toán kiểm tra mới được kiểm tra chứng từ con");
        }
        validateApprover(currentStep, currentUser);
    }

    private void validateDocumentMutator(AccountingDossier dossier) {
        validateDossierMutator(dossier);
    }

    /** Chỉ Kế toán trưởng hoặc Super Admin mới được đưa hồ sơ vào lưu trữ (đồng bộ với quyền chấm dứt hồ sơ). */
    private void validateArchiver() {
        vn.system.app.modules.user.domain.User currentUser = userRepository
                .findByEmail(SecurityUtil.getCurrentUserLogin().orElse(""));
        if (currentUser == null || currentUser.getRole() == null) {
            throw new PermissionException("Không xác định được quyền lưu trữ bộ chứng từ");
        }
        String roleName = currentUser.getRole().getName().toUpperCase();
        if (!"SUPER_ADMIN".equals(roleName) && !"CHIEF_ACCOUNTANT".equals(roleName) && !"KETOANTRUONG".equals(roleName)) {
            throw new PermissionException("Chỉ Kế toán trưởng hoặc Super Admin mới được quyền lưu trữ");
        }
    }

    /** Draft and returned dossiers may only be changed by their creator or an administrator. */
    private void validateDossierMutator(AccountingDossier dossier) {
        String email = SecurityUtil.getCurrentUserLogin().orElse("");
        vn.system.app.modules.user.domain.User currentUser = userRepository.findByEmail(email);
        if (currentUser == null) {
            throw new IdInvalidException("Không xác định được người thao tác bộ chứng từ");
        }
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if ((scope != null && (scope.isSuperAdmin() || scope.isAdminLevel()))
                || (currentUser.getRole() != null && "SUPER_ADMIN".equals(currentUser.getRole().getName()))) {
            return;
        }
        if (dossier.getCreatorId() == null) {
            throw new PermissionException("Hồ sơ cũ không có thông tin người lập, chỉ Quản trị viên mới được phép thao tác");
        }
        if (dossier.getCreatorId().equals(currentUser.getId())) {
            return;
        }
        throw new PermissionException("Chỉ người lập hồ sơ mới được thay đổi hoặc chuyển xử lý bộ này");
    }

    private void assertCanReadDossier(AccountingDossier dossier, vn.system.app.modules.user.domain.User currentUser) {
        // Scope check first — superAdmin/adminLevel bypasses all further checks
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope != null && (scope.isSuperAdmin() || scope.isAdminLevel())) {
            return;
        }

        if (currentUser == null) {
            throw new PermissionException("Chưa đăng nhập");
        }
        if (currentUser.getRole() != null && "SUPER_ADMIN".equalsIgnoreCase(currentUser.getRole().getName())) {
            return;
        }

        // 1. Creator always has read access
        if (currentUser.getId().equals(dossier.getCreatorId())) {
            return;
        }

        // 2. Anyone involved in the approval flow (approver, eligible, or delegate) has read access
        List<AccountingDossierApprovalStep> steps = approvalStepRepository.findByDossierIdAndActiveTrue(dossier.getId());
        for (AccountingDossierApprovalStep step : steps) {
            if (currentUser.getId().equals(step.getApproverUserId())) {
                return;
            }
            if (step.getEligibleApproverIds() != null && !step.getEligibleApproverIds().isBlank()) {
                boolean inEligible = java.util.Arrays.stream(step.getEligibleApproverIds().split(","))
                        .anyMatch(currentUser.getId()::equals);
                if (inEligible) {
                    return;
                }
            }
            if (step.getApproverUserId() != null && step.isAllowDelegation() 
                    && delegationService.canActAsDelegate(step.getApproverUserId(), currentUser.getId(), dossier)) {
                return;
            }
        }

        // 3. Role-based scope restrictions
        if (currentUser.getRole() != null) {
            String roleName = currentUser.getRole().getName();
            if ("EMPLOYEE".equalsIgnoreCase(roleName)) {
                throw new PermissionException("Bạn không có quyền xem chi tiết bộ chứng từ này");
            } else if ("DEPARTMENT_MANAGER".equalsIgnoreCase(roleName) || "ADMIN_SUB_3".equalsIgnoreCase(roleName)) {
                if (scope == null) {
                    throw new PermissionException("Không xác định được phạm vi người dùng, vui lòng đăng nhập lại");
                }
                if (scope.departmentIds() != null && scope.departmentIds().contains(dossier.getDepartment().getId())) {
                    return;
                }
                throw new PermissionException("Bộ chứng từ không thuộc phạm vi bộ phận được phép truy cập của bạn");
            } else {
                // For all other roles (DIRECTOR, CHIEF_ACCOUNTANT, etc.) fall back to company-scope check
                validateCompanyScope(dossier.getCompany().getId());
            }
        }
        validateCompanyScope(dossier.getCompany().getId());
    }

    private Long resolveAndValidateReportCompanyId(Long companyId) {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope == null || scope.isSuperAdmin() || scope.isAdminLevel()) {
            return companyId;
        }
        if (companyId != null) {
            validateCompanyScope(companyId);
            return companyId;
        }
        if (scope.companyIds() != null && !scope.companyIds().isEmpty()) {
            return scope.companyIds().iterator().next();
        }
        throw new PermissionException("Không xác định được phạm vi công ty để truy vấn báo cáo");
    }

    public AccountingDossier fetchById(Long id) {
        AccountingDossier dossier = repository.findById(id)
                .orElseThrow(() -> new IdInvalidException("Bộ chứng từ kế toán không tồn tại"));
        if (!dossier.isActive()) {
            throw new IdInvalidException("Bộ chứng từ kế toán đã bị xóa");
        }
        
        String email = SecurityUtil.getCurrentUserLogin().orElse(null);
        if (email != null) {
            vn.system.app.modules.user.domain.User currentUser = resolveActionUser(email);
            assertCanReadDossier(dossier, currentUser);
        } else {
            throw new PermissionException("Chưa đăng nhập");
        }
        
        return dossier;
    }

    private AccountingDossier saveDossierWithOptimisticLockCheck(AccountingDossier dossier) {
        try {
            return repository.saveAndFlush(dossier);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            throw new IdInvalidException("Hồ sơ đang được xử lý bởi người khác (DOSSIER_CONCURRENTLY_MODIFIED)");
        }
    }

    @Transactional(readOnly = true)
    public ResAccountingDossierDTO getOne(Long id) {
        return convertToDTO(fetchById(id));
    }

    public ResAccountingDossierDTO getByQrToken(String token) {
        AccountingDossier dossier = repository.findByQrToken(token)
                .orElseThrow(() -> new IdInvalidException("Mã QR không hợp lệ hoặc không tồn tại"));
        if (!dossier.isActive()) {
            throw new IdInvalidException("Bộ chứng từ kế toán đã bị xóa");
        }
        validateCompanyScope(dossier.getCompany().getId());
        return convertToDTO(dossier);
    }

    public List<ResAccountingDossierAuditLogDTO> fetchLogs(Long id) {
        fetchById(id);
        return auditLogRepository.findByDossierIdOrderByCreatedAtDesc(id).stream()
                .map(this::convertToLogDTO)
                .collect(Collectors.toList());
    }

    public ResultPaginationDTO fetchAll(Specification<AccountingDossier> spec, Pageable pageable) {
        return fetchAll(spec, pageable, null);
    }

    @Transactional(readOnly = true)
    public ResultPaginationDTO fetchAll(
            Specification<AccountingDossier> spec,
            Pageable pageable,
            String approverUserId) {
        return fetchAll(spec, pageable, approverUserId, null, null, null, null, null, null, null, null);
    }

    public ResultPaginationDTO fetchAll(
            Specification<AccountingDossier> spec,
            Pageable pageable,
            String approverUserId,
            String storageStatus,
            Integer retentionYear,
            Integer retentionMonth,
            Integer retentionDay,
            String creatorId,
            Long companyId,
            Long departmentId,
            Long dossierCategoryId) {
        if (approverUserId != null && !approverUserId.trim().isEmpty()) {
            AccountingDossierStorageStatus parsedStorageStatus = null;
            if (storageStatus != null && !storageStatus.trim().isEmpty()) {
                try {
                    parsedStorageStatus = AccountingDossierStorageStatus.valueOf(storageStatus.trim());
                } catch (IllegalArgumentException e) {
                    throw new IdInvalidException("Trạng thái lưu trữ không hợp lệ");
                }
            }
            Pageable effectivePageable = PageRequest.of(
                    pageable == null ? 0 : pageable.getPageNumber(),
                    pageable == null ? 10 : pageable.getPageSize());
            Page<AccountingDossier> processedPage = approvalStepRepository.findProcessedDossiersByApprover(
                    approverUserId.trim(),
                    parsedStorageStatus,
                    companyId,
                    departmentId,
                    dossierCategoryId,
                    effectivePageable);

            ResultPaginationDTO rs = new ResultPaginationDTO();
            ResultPaginationDTO.Meta meta = new ResultPaginationDTO.Meta();
            meta.setPage(effectivePageable.getPageNumber() + 1);
            meta.setPageSize(effectivePageable.getPageSize());
            meta.setPages(processedPage.getTotalPages());
            meta.setTotal(processedPage.getTotalElements());
            rs.setMeta(meta);
            rs.setResult(processedPage.getContent().stream().map(this::convertToDTO).collect(Collectors.toList()));
            return rs;
        }

        Specification<AccountingDossier> scopeSpec;
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope != null && !scope.isSuperAdmin() && !scope.isAdminLevel()) {
            String email = SecurityUtil.getCurrentUserLogin().orElse(null);
            if (email != null) {
                vn.system.app.modules.user.domain.User currentUser = userRepository.findByEmail(email);
                if (currentUser != null && currentUser.getRole() != null) {
                    String roleName = currentUser.getRole().getName();
                    if ("EMPLOYEE".equalsIgnoreCase(roleName)) {
                        scopeSpec = (root, query, cb) -> cb.equal(root.get("creatorId"), currentUser.getId());
                    } else if ("DEPARTMENT_MANAGER".equalsIgnoreCase(roleName)
                            || "ADMIN_SUB_3".equalsIgnoreCase(roleName)) {
                        scopeSpec = (root, query, cb) -> root.get("department").get("id").in(scope.departmentIds());
                    } else {
                        scopeSpec = (root, query, cb) -> root.get("company").get("id").in(scope.companyIds());
                    }
                } else {
                    scopeSpec = ScopeSpec.byCompanyScope("company.id");
                }
            } else {
                scopeSpec = ScopeSpec.byCompanyScope("company.id");
            }
        } else {
            scopeSpec = Specification.where(null);
        }

        Specification<AccountingDossier> activeSpec = (root, query, cb) -> cb.isTrue(root.get("active"));
        Specification<AccountingDossier> baseSpec = scopeSpec.and(activeSpec);
        Specification<AccountingDossier> approverSpec = buildApproverUserSpec(approverUserId);
        Specification<AccountingDossier> storageSpec = buildStorageLookupSpec(
                storageStatus,
                retentionYear,
                retentionMonth,
                retentionDay,
                creatorId,
                companyId,
                departmentId,
                dossierCategoryId);
        Specification<AccountingDossier> finalSpec = spec == null ? baseSpec : spec.and(baseSpec);
        if (approverSpec != null) {
            finalSpec = finalSpec.and(approverSpec);
        }
        if (storageSpec != null) {
            finalSpec = finalSpec.and(storageSpec);
        }
        Pageable effectivePageable = withDefaultDossierSort(pageable);
        Page<AccountingDossierListProjection> page = repository.findBy(finalSpec, q -> q.as(AccountingDossierListProjection.class).page(effectivePageable));

        ResultPaginationDTO rs = new ResultPaginationDTO();
        ResultPaginationDTO.Meta meta = new ResultPaginationDTO.Meta();
        meta.setPage(effectivePageable.getPageNumber() + 1);
        meta.setPageSize(effectivePageable.getPageSize());
        meta.setPages(page.getTotalPages());
        meta.setTotal(page.getTotalElements());
        rs.setMeta(meta);
        rs.setResult(page.getContent().stream().map(this::convertToListDTO).collect(Collectors.toList()));
        return rs;
    }

    private Pageable withDefaultDossierSort(Pageable pageable) {
        if (pageable == null) {
            return PageRequest.of(0, 10, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        }
        if (pageable.getSort().isSorted()) {
            return pageable;
        }
        return PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    private Specification<AccountingDossier> buildStorageLookupSpec(
            String storageStatus,
            Integer retentionYear,
            Integer retentionMonth,
            Integer retentionDay,
            String creatorId,
            Long companyId,
            Long departmentId,
            Long dossierCategoryId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (storageStatus != null && !storageStatus.trim().isEmpty()) {
                try {
                    predicates.add(cb.equal(root.get("storageStatus"),
                            AccountingDossierStorageStatus.valueOf(storageStatus.trim().toUpperCase())));
                } catch (IllegalArgumentException e) {
                    throw new IdInvalidException("Trạng thái lưu trữ không hợp lệ: " + storageStatus);
                }
            }
            if (companyId != null) {
                predicates.add(cb.equal(root.get("company").get("id"), companyId));
            }
            if (departmentId != null) {
                predicates.add(cb.equal(root.get("department").get("id"), departmentId));
            }
            if (dossierCategoryId != null) {
                predicates.add(cb.equal(root.get("dossierCategory").get("id"), dossierCategoryId));
            }
            if (retentionYear != null) {
                Instant[] range = buildRetentionRange(retentionYear, retentionMonth, retentionDay);
                predicates.add(cb.greaterThanOrEqualTo(root.get("retentionUntil"), range[0]));
                predicates.add(cb.lessThan(root.get("retentionUntil"), range[1]));
            }
            if (creatorId != null && !creatorId.trim().isEmpty()) {
                predicates.add(cb.equal(root.get("creatorId"), creatorId.trim()));
            }
            if (predicates.isEmpty()) {
                return cb.conjunction();
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private Instant[] buildRetentionRange(Integer year, Integer month, Integer day) {
        if (year < 1970 || year > 9999) {
            throw new IdInvalidException("Năm lưu trữ không hợp lệ: " + year);
        }
        int safeMonth = month == null ? 1 : month;
        int safeDay = day == null ? 1 : day;
        try {
            LocalDate startDate = LocalDate.of(year, safeMonth, safeDay);
            LocalDate endDate = day != null ? startDate.plusDays(1)
                    : month != null ? startDate.plusMonths(1)
                            : startDate.plusYears(1);
            ZoneId zone = ZoneId.systemDefault();
            return new Instant[] {
                    startDate.atStartOfDay(zone).toInstant(),
                    endDate.atStartOfDay(zone).toInstant()
            };
        } catch (RuntimeException e) {
            throw new IdInvalidException("Ngày/tháng/năm lưu trữ không hợp lệ");
        }
    }

    @Transactional(readOnly = true)
    public ResultPaginationDTO fetchPendingMyApproval(Pageable pageable) {
        String email = SecurityUtil.getCurrentUserLogin().orElse("");
        vn.system.app.modules.user.domain.User currentUser = userRepository.findByEmail(email);
        if (currentUser == null) {
            ResultPaginationDTO rs = new ResultPaginationDTO();
            ResultPaginationDTO.Meta meta = new ResultPaginationDTO.Meta();
            meta.setPage(1);
            meta.setPageSize(pageable.getPageSize());
            meta.setPages(0);
            meta.setTotal(0);
            rs.setMeta(meta);
            rs.setResult(java.util.Collections.emptyList());
            return rs;
        }

        List<ApproverType> approverTypes = new ArrayList<>();
        String roleName = currentUser.getRole() == null ? "" : currentUser.getRole().getName().toUpperCase();
        if (roleName.contains("ACCOUNTANT") || roleName.contains("KETOAN") || roleName.contains("KẾ TOÁN")) {
            approverTypes.add(ApproverType.ACCOUNTANT);
        }
        if (roleName.contains("CHIEF_ACCOUNTANT") || roleName.contains("KETOANTRUONG")
                || roleName.contains("KẾ TOÁN TRƯỞNG")) {
            approverTypes.add(ApproverType.CHIEF_ACCOUNTANT);
        }
        Pageable effectivePageable = PageRequest.of(
                pageable == null ? 0 : pageable.getPageNumber(),
                pageable == null ? 10 : pageable.getPageSize());
        Page<AccountingDossier> page = approverTypes.isEmpty()
                ? approvalStepRepository.findCurrentDossiersForDirectApprover(currentUser.getId(), effectivePageable)
                : approvalStepRepository.findCurrentDossiersForApprover(currentUser.getId(), approverTypes, effectivePageable);

        ResultPaginationDTO rs = new ResultPaginationDTO();
        ResultPaginationDTO.Meta meta = new ResultPaginationDTO.Meta();
        meta.setPage(effectivePageable.getPageNumber() + 1);
        meta.setPageSize(effectivePageable.getPageSize());
        meta.setPages(page.getTotalPages());
        meta.setTotal(page.getTotalElements());
        rs.setMeta(meta);
        rs.setResult(page.getContent().stream().map(this::convertToDTO).collect(Collectors.toList()));
        return rs;
    }

    public ResultPaginationDTO fetchAllDossierDocuments(
            Specification<AccountingDossierDocument> spec,
            String keyword,
            String dossierCode,
            String fileStatus,
            Pageable pageable) {
        Specification<AccountingDossierDocument> scopeSpec = ScopeSpec.byCompanyScope("dossier.company.id");
        Specification<AccountingDossierDocument> activeSpec = (root, query, cb) -> cb.isTrue(root.get("active"));
        Specification<AccountingDossierDocument> lookupSpec = buildDossierDocumentLookupSpec(keyword, dossierCode, fileStatus);
        Specification<AccountingDossierDocument> finalSpec = spec == null ? scopeSpec.and(activeSpec)
                : spec.and(scopeSpec).and(activeSpec);
        if (lookupSpec != null) {
            finalSpec = finalSpec.and(lookupSpec);
        }

        Pageable effectivePageable = pageable == null
                ? PageRequest.of(0, 10, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
                : pageable.getSort().isSorted()
                ? pageable
                : PageRequest.of(
                        pageable.getPageNumber(),
                        pageable.getPageSize(),
                        Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<AccountingDossierDocument> page = documentItemRepository.findAll(finalSpec, effectivePageable);
        ResultPaginationDTO rs = new ResultPaginationDTO();
        ResultPaginationDTO.Meta meta = new ResultPaginationDTO.Meta();
        meta.setPage(effectivePageable.getPageNumber() + 1);
        meta.setPageSize(effectivePageable.getPageSize());
        meta.setPages(page.getTotalPages());
        meta.setTotal(page.getTotalElements());
        rs.setMeta(meta);
        rs.setResult(page.getContent().stream().map(this::convertToDocumentDTO).collect(Collectors.toList()));
        return rs;
    }

    private Specification<AccountingDossierDocument> buildDossierDocumentLookupSpec(String keyword, String dossierCode, String fileStatus) {
        Specification<AccountingDossierDocument> result = null;

        if (keyword != null && !keyword.isBlank()) {
            String normalizedKeyword = "%" + keyword.trim().toLowerCase() + "%";
            Specification<AccountingDossierDocument> keywordSpec = (root, query, cb) -> {
                var dossier = root.join("dossier", jakarta.persistence.criteria.JoinType.LEFT);
                var department = dossier.join("department", jakarta.persistence.criteria.JoinType.LEFT);
                var company = dossier.join("company", jakarta.persistence.criteria.JoinType.LEFT);
                var accountingCategory = root.join("accountingCategory", jakarta.persistence.criteria.JoinType.LEFT);
                var document = root.join("document", jakarta.persistence.criteria.JoinType.LEFT);
                return cb.or(
                        cb.like(cb.lower(root.get("documentName")), normalizedKeyword),
                        cb.like(cb.lower(root.get("documentType")), normalizedKeyword),
                        cb.like(cb.lower(root.get("createdBy")), normalizedKeyword),
                        cb.like(cb.lower(dossier.get("dossierCode")), normalizedKeyword),
                        cb.like(cb.lower(dossier.get("content")), normalizedKeyword),
                        cb.like(cb.lower(root.get("invoiceNumber")), normalizedKeyword),
                        cb.like(cb.lower(root.get("invoiceContent")), normalizedKeyword),
                        cb.like(cb.lower(root.get("partnerName")), normalizedKeyword),
                        cb.like(cb.lower(department.get("name")), normalizedKeyword),
                        cb.like(cb.lower(company.get("name")), normalizedKeyword),
                        cb.like(cb.lower(document.get("documentCode")), normalizedKeyword),
                        cb.like(cb.lower(accountingCategory.get("categoryName")), normalizedKeyword),
                        cb.like(cb.lower(accountingCategory.get("categoryCode")), normalizedKeyword),
                        cb.like(cb.lower(accountingCategory.get("symbol")), normalizedKeyword));
            };
            result = keywordSpec;
        }

        if (dossierCode != null && !dossierCode.isBlank()) {
            String normalizedDossierCode = "%" + dossierCode.trim().toLowerCase() + "%";
            Specification<AccountingDossierDocument> dossierCodeSpec = (root, query, cb) -> {
                var dossier = root.join("dossier", jakarta.persistence.criteria.JoinType.LEFT);
                return cb.like(cb.lower(dossier.get("dossierCode")), normalizedDossierCode);
            };
            result = result == null ? dossierCodeSpec : result.and(dossierCodeSpec);
        }

        if (fileStatus != null && !fileStatus.isBlank()) {
            String normalizedStatus = fileStatus.trim().toUpperCase();
            Specification<AccountingDossierDocument> fileSpec = (root, query, cb) -> {
                Predicate hasFile = cb.or(
                        cb.and(cb.isNotNull(root.get("fileUrl")), cb.notEqual(root.get("fileUrl"), "")),
                        cb.and(cb.isNotNull(root.get("externalLink")), cb.notEqual(root.get("externalLink"), "")),
                        cb.isNotNull(root.get("document")));
                if ("HAS_FILE".equals(normalizedStatus)) {
                    return hasFile;
                }
                if ("MISSING_FILE".equals(normalizedStatus)) {
                    return cb.not(hasFile);
                }
                return cb.conjunction();
            };
            result = result == null ? fileSpec : result.and(fileSpec);
        }

        return result;
    }

    @Transactional
    public int refreshExpiredRetentionStatuses() {
        Instant now = Instant.now();
        List<AccountingDossier> expiredDossiers = repository.findByActiveTrueAndStorageStatusAndRetentionUntilBefore(
                AccountingDossierStorageStatus.IN_RETENTION,
                now);
        for (AccountingDossier dossier : expiredDossiers) {
            dossier.setStorageStatus(AccountingDossierStorageStatus.EXPIRED);
            dossierAuditService.writeLog(dossier, "RETENTION_EXPIRED",
                    "Tự động chuyển trạng thái hết thời hạn lưu trữ",
                    "STORAGE", dossier.getId(),
                    AccountingDossierStorageStatus.IN_RETENTION.name(),
                    AccountingDossierStorageStatus.EXPIRED.name(),
                    null,
                    dossier.getRetentionUntil() == null ? null : dossier.getRetentionUntil().toString());
        }
        repository.saveAll(expiredDossiers);
        return expiredDossiers.size();
    }

    @Transactional
    public int refreshExpiredRetentionStatusesByUser() {
        String email = SecurityUtil.getCurrentUserLogin().orElse("");
        vn.system.app.modules.user.domain.User actor = userRepository.findByEmail(email);
        if (actor == null) {
            throw new IdInvalidException("Không xác định được người thao tác");
        }
        boolean hasPermission = actor.getRole() != null && (
            "SUPER_ADMIN".equals(actor.getRole().getName()) || 
            (actor.getRole().getPermissions() != null && actor.getRole().getPermissions().stream().anyMatch(p -> "Cập nhật trạng thái hết thời hạn lưu trữ".equals(p.getName())))
        );
        if (!hasPermission) {
            throw new PermissionException("Bạn không có quyền thực hiện chức năng cập nhật trạng thái hết thời hạn lưu trữ");
        }
        return refreshExpiredRetentionStatuses();
    }

    @Transactional
    public ResAccountingDossierDTO archive(Long id, AccountingDossierActionRequest req) {
        AccountingDossier dossier = fetchById(id);
        if (dossier.getStatus() != AccountingDossierStatus.APPROVED
                && dossier.getStatus() != AccountingDossierStatus.ARCHIVED) {
            throw new PermissionException("Chỉ bộ chứng từ đã duyệt mới được đưa vào lưu trữ");
        }
        validateArchiver();
        AccountingDossierStatus oldStatus = dossier.getStatus();
        AccountingDossierStorageStatus oldStorageStatus = dossier.getStorageStatus();
        dossier.setStatus(AccountingDossierStatus.ARCHIVED);
        dossier.setStorageStatus(AccountingDossierStorageStatus.ARCHIVED);
        AccountingDossier saved = saveDossierWithOptimisticLockCheck(dossier);
        dossierAuditService.writeLog(saved, "ARCHIVE_DOSSIER",
                normalizeNote(req == null ? null : req.getNote(), "Đưa bộ chứng từ vào lưu trữ"),
                "STORAGE", saved.getId(),
                oldStorageStatus == null ? null : oldStorageStatus.name(),
                AccountingDossierStorageStatus.ARCHIVED.name(),
                oldStatus.name(),
                saved.getStatus().name());
        notificationService.notifyCreator(saved, "ACCOUNTING_ARCHIVED",
                String.format("Bộ chứng từ %s đã được đưa vào lưu trữ.",
                        notificationService.dossierLabel(saved)));
        return convertToDTO(saved);
    }

    @Transactional(readOnly = true)
    public ResAccountingDossierStorageSummaryDTO getStorageSummary(Long companyId) {
        companyId = resolveAndValidateReportCompanyId(companyId);
        ResAccountingDossierStorageSummaryDTO dto = new ResAccountingDossierStorageSummaryDTO();
        Instant now = Instant.now();
        Object[] counts = repository.getStorageSummaryCounts(companyId, now, LocalDate.now().plusDays(30).atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant());
        if (counts == null) {
            // Keeps legacy mocked repositories/tests compatible; production repository returns the aggregate row.
            dto.setTotal(companyId == null ? repository.countByActiveTrue() : repository.countByActiveTrueAndCompanyId(companyId));
            dto.setInRetention(companyId == null ? repository.countByActiveTrueAndStorageStatus(AccountingDossierStorageStatus.IN_RETENTION) : repository.countByActiveTrueAndStorageStatusAndCompanyId(AccountingDossierStorageStatus.IN_RETENTION, companyId));
            dto.setExpired(companyId == null ? repository.countByActiveTrueAndStorageStatus(AccountingDossierStorageStatus.EXPIRED) : repository.countByActiveTrueAndStorageStatusAndCompanyId(AccountingDossierStorageStatus.EXPIRED, companyId));
            dto.setArchived(companyId == null ? repository.countByActiveTrueAndStorageStatus(AccountingDossierStorageStatus.ARCHIVED) : repository.countByActiveTrueAndStorageStatusAndCompanyId(AccountingDossierStorageStatus.ARCHIVED, companyId));
            dto.setExpiringSoon(companyId == null ? repository.countByActiveTrueAndRetentionUntilBetween(now, LocalDate.now().plusDays(30).atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant()) : repository.countByActiveTrueAndRetentionUntilBetweenAndCompanyId(now, LocalDate.now().plusDays(30).atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant(), companyId));
            dto.setByStatus(toCountMap(repository.countActiveGroupByStatus(companyId))); dto.setByStorageStatus(toCountMap(repository.countActiveGroupByStorageStatus(companyId)));
            dto.setPendingApproval(dto.getByStatus().getOrDefault(AccountingDossierStatus.SUBMITTED.name(), 0L) + dto.getByStatus().getOrDefault(AccountingDossierStatus.IN_REVIEW.name(), 0L) + dto.getByStatus().getOrDefault(AccountingDossierStatus.RETURN_REQUESTED.name(), 0L));
            return dto;
        }
        // Hibernate / MySQL dialect may wrap the single aggregate row as Object[]{ Object[] row }
        // Unbox one level if the first element is itself an array.
        if (counts[0] instanceof Object[] nested) {
            counts = nested;
        }
        dto.setTotal(numberAt(counts, 0)); dto.setInRetention(numberAt(counts, 1)); dto.setExpired(numberAt(counts, 2)); dto.setArchived(numberAt(counts, 3)); dto.setExpiringSoon(numberAt(counts, 4));
        Map<String, Long> byStatus = new java.util.LinkedHashMap<>(); Map<String, Long> byStorage = new java.util.LinkedHashMap<>();
        for (Object[] row : repository.getStorageSummaryGroups(companyId)) {
            ("STATUS".equals(row[0]) ? byStatus : byStorage).put(String.valueOf(row[1]), ((Number) row[2]).longValue());
        }
        dto.setByStatus(byStatus); dto.setByStorageStatus(byStorage);
        dto.setPendingApproval(dto.getByStatus().getOrDefault(AccountingDossierStatus.SUBMITTED.name(), 0L)
                + dto.getByStatus().getOrDefault(AccountingDossierStatus.IN_REVIEW.name(), 0L)
                + dto.getByStatus().getOrDefault(AccountingDossierStatus.RETURN_REQUESTED.name(), 0L));
        return dto;
    }

    @Transactional(readOnly = true)
    public ResAccountingDossierDashboardMetricsDTO getDashboardMetrics(Long companyId) {
        ResAccountingDossierDashboardMetricsDTO dto = new ResAccountingDossierDashboardMetricsDTO();
        dto.setSummary(this.getStorageSummary(companyId));
        dto.setPendingByRole(this.pendingByRole(companyId));
        dto.setByStatus(this.reportByStatus(companyId));
        dto.setByDepartment(this.reportByDepartment(companyId));
        dto.setCategories(this.fetchActiveCategories());
        return dto;
    }

    private long numberAt(Object[] values, int index) { return values[index] == null ? 0L : ((Number) values[index]).longValue(); }

    @Transactional(readOnly = true)
    public List<ResAccountingDossierReportRowDTO> reportByStatus(Long companyId) {
        companyId = resolveAndValidateReportCompanyId(companyId);
        return repository.countActiveGroupByStatus(companyId).stream()
                .map(row -> new ResAccountingDossierReportRowDTO(
                        String.valueOf(row[0]),
                        String.valueOf(row[0]),
                        (Long) row[1]))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ResAccountingDossierReportRowDTO> reportByDepartment(Long companyId) {
        companyId = resolveAndValidateReportCompanyId(companyId);
        return repository.countActiveGroupByDepartment(companyId).stream()
                .map(row -> new ResAccountingDossierReportRowDTO(
                        String.valueOf(row[0]),
                        row[1] == null ? "Chưa phân phòng ban" : String.valueOf(row[1]),
                        (Long) row[2]))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ResAccountingDossierReportRowDTO> reportByCategory(Long companyId) {
        companyId = resolveAndValidateReportCompanyId(companyId);
        return repository.countActiveGroupByCategory(companyId).stream()
                .map(row -> {
                    String key = row[0] == null ? "UNSTRUCTURED" : String.valueOf(row[0]);
                    String label = row[1] != null ? String.valueOf(row[1])
                            : row[2] != null ? String.valueOf(row[2])
                                    : "Phi cấu trúc";
                    return new ResAccountingDossierReportRowDTO(key, label, (Long) row[3]);
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ResAccountingDossierReportRowDTO> pendingByRole(Long companyId) {
        companyId = resolveAndValidateReportCompanyId(companyId);
        return approvalStepRepository.countCurrentStepsGroupByApproverType(companyId).stream()
                .map(row -> new ResAccountingDossierReportRowDTO(
                        String.valueOf(row[0]),
                        String.valueOf(row[0]),
                        (Long) row[1]))
                .collect(Collectors.toList());
    }

    private Map<String, Long> toCountMap(List<Object[]> rows) {
        return rows.stream()
                .collect(Collectors.toMap(
                        row -> String.valueOf(row[0]),
                        row -> (Long) row[1],
                        Long::sum,
                        java.util.LinkedHashMap::new));
    }

    private Specification<AccountingDossier> buildApproverUserSpec(String approverUserId) {
        if (approverUserId == null || approverUserId.trim().isEmpty()) {
            return null;
        }

        return (root, query, cb) -> {
            Subquery<Long> subquery = query.subquery(Long.class);
            Root<AccountingDossierApprovalStep> stepRoot = subquery.from(AccountingDossierApprovalStep.class);
            subquery.select(stepRoot.get("dossier").get("id"));
            subquery.where(
                    cb.equal(stepRoot.get("dossier").get("id"), root.get("id")),
                    cb.isTrue(stepRoot.get("active")),
                    stepRoot.get("status").in(
                            ApprovalStepStatus.APPROVED,
                            ApprovalStepStatus.REJECTED,
                            ApprovalStepStatus.RETURNED,
                            ApprovalStepStatus.CANCELLED),
                    cb.equal(stepRoot.get("approverUserId"), approverUserId.trim()));
            return cb.exists(subquery);
        };
    }

    private Specification<AccountingDossier> buildPendingMyApprovalSpec(String userId, List<ApproverType> approverTypes) {
        if (userId == null || userId.trim().isEmpty()) {
            return (root, query, cb) -> cb.disjunction();
        }

        List<ApproverType> safeApproverTypes = approverTypes == null ? java.util.Collections.emptyList() : approverTypes;
        return (root, query, cb) -> {
            Subquery<Long> subquery = query.subquery(Long.class);
            Root<AccountingDossierApprovalStep> stepRoot = subquery.from(AccountingDossierApprovalStep.class);
            Predicate directApprover = cb.equal(stepRoot.get("approverUserId"), userId.trim());
            Predicate roleApprover = safeApproverTypes.isEmpty()
                    ? cb.disjunction()
                    : cb.and(
                            cb.isNull(stepRoot.get("approverUserId")),
                            stepRoot.get("approverType").in(safeApproverTypes));

            subquery.select(stepRoot.get("dossier").get("id"));
            subquery.where(
                    cb.equal(stepRoot.get("dossier").get("id"), root.get("id")),
                    cb.isTrue(stepRoot.get("active")),
                    cb.equal(stepRoot.get("status"), ApprovalStepStatus.CURRENT),
                    cb.or(directApprover, roleApprover));
            return cb.exists(subquery);
        };
    }

    private void applyRequest(
            AccountingDossier entity,
            AccountingDossierRequest req,
            Company company,
            Department department,
            Section section) {
        AccountingDossierCategoryMode mode = req.getCategoryMode() == null
                ? AccountingDossierCategoryMode.TEMPLATE
                : req.getCategoryMode();
        AccountingDossierCategory dossierCategory = resolveDossierCategory(req, company.getId(), mode);

        entity.setContent(req.getContent().trim());
        entity.setCategoryMode(mode);
        entity.setDossierCategory(dossierCategory);
        entity.setDossierCategoryVersion(dossierCategory == null ? null : dossierCategory.getVersion());
        entity.setCustomCategoryName(mode == AccountingDossierCategoryMode.UNSTRUCTURED
                ? req.getCustomCategoryName().trim()
                : null);
        entity.setSyncCategoryRequested(mode == AccountingDossierCategoryMode.UNSTRUCTURED
                && Boolean.TRUE.equals(req.getSyncCategoryRequested()));
        entity.setCompany(company);
        entity.setDepartment(department);
        entity.setSection(section);
        if (entity.getRetentionYears() == null) {
            entity.setRetentionYears(10);
        }
        if (entity.getRetentionUntil() == null) {
            entity.setRetentionUntil(ZonedDateTime.now(ZoneId.systemDefault())
                    .plusYears(entity.getRetentionYears())
                    .toInstant());
        }
    }

    private AccountingDossierCategory resolveDossierCategory(
            AccountingDossierRequest req,
            Long companyId,
            AccountingDossierCategoryMode mode) {
        if (mode == AccountingDossierCategoryMode.UNSTRUCTURED) {
            if (req.getCustomCategoryName() == null || req.getCustomCategoryName().trim().isEmpty()) {
                throw new IdInvalidException("Vui lòng nhập tên danh mục phi cấu trúc");
            }
            return null;
        }
        if (req.getDossierCategoryId() == null) {
            throw new IdInvalidException("Vui lòng chọn mẫu bộ chứng từ");
        }

        AccountingDossierCategory category = dossierCategoryRepository.findById(req.getDossierCategoryId())
                .orElseThrow(() -> new IdInvalidException("Mẫu bộ chứng từ không tồn tại"));
        if (!category.isActive()) {
            throw new IdInvalidException("Mẫu bộ chứng từ đã ngưng sử dụng");
        }
        if ("COMPANY".equalsIgnoreCase(category.getScope())
                && category.getCompanyId() != null
                && !category.getCompanyId().equals(companyId)) {
            throw new PermissionException("Mẫu bộ chứng từ không áp dụng cho công ty đã chọn");
        }
        return category;
    }

    private void createTemplateDocumentRows(AccountingDossier dossier) {
        if (dossier.getCategoryMode() != AccountingDossierCategoryMode.TEMPLATE
                || dossier.getDossierCategory() == null
                || dossier.getDossierCategory().getDocumentCategories() == null) {
            return;
        }
        List<AccountingDossierDocument> items = new ArrayList<>();
        for (AccountingDocumentCategory category : dossier.getDossierCategory().getDocumentCategories()) {
            AccountingDossierDocument item = new AccountingDossierDocument();
            item.setDossier(dossier);
            item.setAccountingCategory(category);
            item.setDocumentName(category.getCategoryName());
            item.setDocumentType("OTHER");
            items.add(item);
        }
        if (!items.isEmpty()) documentItemRepository.saveAll(items);
    }

    private Company resolveCompany(Long companyId) {
        return companyRepository.findById(companyId)
                .orElseThrow(() -> new IdInvalidException("Công ty không tồn tại"));
    }

    private Department resolveDepartment(Long departmentId, Long companyId) {
        Department department = departmentRepository.findById(departmentId)
                .orElseThrow(() -> new IdInvalidException("Phòng ban không tồn tại"));
        if (department.getCompany() == null || department.getCompany().getId() != companyId) {
            throw new IdInvalidException("Phòng ban không thuộc công ty đã chọn");
        }
        return department;
    }

    private Section resolveSection(Long sectionId, Long departmentId) {
        if (sectionId == null) {
            return null;
        }
        Section section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new IdInvalidException("Bộ phận không tồn tại"));
        if (section.getDepartment() == null || section.getDepartment().getId() != departmentId) {
            throw new IdInvalidException("Bộ phận không thuộc phòng ban đã chọn");
        }
        return section;
    }

    private void validateEditable(AccountingDossier dossier) {
        if (dossier.getStatus() != AccountingDossierStatus.DRAFT
                && dossier.getStatus() != AccountingDossierStatus.RETURNED
                && dossier.getStatus() != AccountingDossierStatus.REJECTED) {
            throw new PermissionException("Chỉ bộ chứng từ ở trạng thái Nháp, Bị hoàn trả hoặc Bị từ chối mới được chỉnh sửa hoặc xóa");
        }
    }

    private void validateCompanyScope(Long companyId) {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope == null) {
            throw new PermissionException("Không xác định được phạm vi người dùng, vui lòng đăng nhập lại");
        }
        if (scope.isSuperAdmin() || scope.isAdminLevel()) {
            return;
        }
        if (scope.companyIds() == null || !scope.companyIds().contains(companyId)) {
            throw new PermissionException("Bạn không có quyền thao tác dữ liệu cho công ty này");
        }
    }

    public ResAccountingDossierDTO convertToDTO(AccountingDossier entity) {
        ResAccountingDossierDTO dto = new ResAccountingDossierDTO();
        dto.setId(entity.getId());
        dto.setDossierCode(entity.getDossierCode());
        dto.setContent(entity.getContent());
        dto.setCategoryMode(entity.getCategoryMode());
        dto.setCustomCategoryName(entity.getCustomCategoryName());
        dto.setDossierCategoryVersion(entity.getDossierCategoryVersion());
        dto.setSyncCategoryRequested(entity.isSyncCategoryRequested());
        dto.setCreatorId(entity.getCreatorId());
        dto.setStatus(entity.getStatus());
        dto.setStorageStatus(entity.getStorageStatus());
        dto.setRetentionYears(entity.getRetentionYears());
        dto.setRetentionUntil(entity.getRetentionUntil());
        dto.setSubmittedAt(entity.getSubmittedAt());
        dto.setApprovedAt(entity.getApprovedAt());
        dto.setTerminatedAt(entity.getTerminatedAt());
        dto.setReturnCount(entity.getReturnCount());
        dto.setActive(entity.isActive());

        dto.setQrToken(entity.getQrToken());
        dto.setQrCode(entity.getQrCode());

        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());

        if (entity.getCompany() != null) {
            dto.setCompany(
                    toRef(entity.getCompany().getId(), entity.getCompany().getCode(), entity.getCompany().getName()));
        }
        if (entity.getDepartment() != null) {
            dto.setDepartment(toRef(entity.getDepartment().getId(), entity.getDepartment().getCode(),
                    entity.getDepartment().getName()));
        }
        if (entity.getSection() != null) {
            dto.setSection(
                    toRef(entity.getSection().getId(), entity.getSection().getCode(), entity.getSection().getName()));
        }
        if (entity.getDossierCategory() != null) {
            dto.setDossierCategory(toRef(entity.getDossierCategory().getId(),
                    entity.getDossierCategory().getCategoryCode(),
                    entity.getDossierCategory().getCategoryName()));
        }

        return dto;
    }

    private ResAccountingDossierDTO convertToListDTO(AccountingDossierListProjection entity) {
        ResAccountingDossierDTO dto = new ResAccountingDossierDTO();
        dto.setId(entity.getId()); dto.setDossierCode(entity.getDossierCode()); dto.setContent(entity.getContent());
        dto.setCategoryMode(entity.getCategoryMode()); dto.setCustomCategoryName(entity.getCustomCategoryName());
        dto.setDossierCategoryVersion(entity.getDossierCategoryVersion()); dto.setSyncCategoryRequested(entity.isSyncCategoryRequested());
        dto.setCreatorId(entity.getCreatorId()); dto.setStatus(entity.getStatus()); dto.setStorageStatus(entity.getStorageStatus());
        dto.setRetentionYears(entity.getRetentionYears()); dto.setRetentionUntil(entity.getRetentionUntil()); dto.setSubmittedAt(entity.getSubmittedAt());
        dto.setApprovedAt(entity.getApprovedAt()); dto.setTerminatedAt(entity.getTerminatedAt()); dto.setReturnCount(entity.getReturnCount()); dto.setActive(entity.isActive());
        dto.setQrToken(entity.getQrToken()); dto.setCreatedAt(entity.getCreatedAt()); dto.setUpdatedAt(entity.getUpdatedAt()); dto.setCreatedBy(entity.getCreatedBy()); dto.setUpdatedBy(entity.getUpdatedBy());
        if (entity.getCompany() != null) dto.setCompany(toRef(entity.getCompany().getId(), entity.getCompany().getCode(), entity.getCompany().getName()));
        if (entity.getDepartment() != null) dto.setDepartment(toRef(entity.getDepartment().getId(), entity.getDepartment().getCode(), entity.getDepartment().getName()));
        if (entity.getSection() != null) dto.setSection(toRef(entity.getSection().getId(), entity.getSection().getCode(), entity.getSection().getName()));
        if (entity.getDossierCategory() != null) dto.setDossierCategory(toRef(entity.getDossierCategory().getId(), entity.getDossierCategory().getCategoryCode(), entity.getDossierCategory().getCategoryName()));
        return dto;
    }

    private ResAccountingDossierDTO.Ref toRef(Long id, String code, String name) {
        ResAccountingDossierDTO.Ref ref = new ResAccountingDossierDTO.Ref();
        ref.setId(id);
        ref.setCode(code);
        ref.setName(name);
        return ref;
    }

    // ==================== DOSSIER TEMPLATE MASTER DATA ====================

    public ResultPaginationDTO fetchCategories(Specification<AccountingDossierCategory> spec, Pageable pageable) {
        Specification<AccountingDossierCategory> effectiveSpec = templateManagementScopeSpec();
        if (spec != null) {
            effectiveSpec = effectiveSpec.and(spec);
        }
        Page<AccountingDossierCategory> page = dossierCategoryRepository.findAll(effectiveSpec, pageable);
        ResultPaginationDTO rs = new ResultPaginationDTO();
        ResultPaginationDTO.Meta meta = new ResultPaginationDTO.Meta();
        meta.setPage(pageable.getPageNumber() + 1);
        meta.setPageSize(pageable.getPageSize());
        meta.setPages(page.getTotalPages());
        meta.setTotal(page.getTotalElements());
        rs.setMeta(meta);
        rs.setResult(page.getContent().stream().map(this::convertToCategoryDTO).collect(Collectors.toList()));
        return rs;
    }

    public List<ResAccountingDossierCategoryDTO> fetchActiveCategories() {
        Specification<AccountingDossierCategory> activeSpec = (root, query, builder) -> builder.isTrue(root.get("active"));
        return dossierCategoryRepository.findAll(activeSpec.and(templateSelectionScopeSpec())).stream()
                .sorted(Comparator.comparing(AccountingDossierCategory::getCategoryName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(this::convertToCategoryDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public ResAccountingDossierCategoryDTO createCategory(AccountingDossierCategoryRequest req) {
        validateCategoryWriteScope(req);
        AccountingDossierCategory entity = new AccountingDossierCategory();
        applyCategoryRequest(entity, req, false);
        return convertToCategoryDTO(dossierCategoryRepository.save(entity));
    }

    @Transactional
    public ResAccountingDossierCategoryDTO updateCategory(Long id, AccountingDossierCategoryRequest req) {
        AccountingDossierCategory entity = dossierCategoryRepository.findById(id)
                .orElseThrow(() -> new IdInvalidException("Mẫu bộ chứng từ không tồn tại"));
        validateCategoryManagementAccess(entity);
        validateCategoryWriteScope(req);
        applyCategoryRequest(entity, req, true);
        return convertToCategoryDTO(dossierCategoryRepository.save(entity));
    }

    @Transactional
    public ResAccountingDossierCategoryDTO toggleCategoryActive(Long id, boolean active) {
        AccountingDossierCategory entity = dossierCategoryRepository.findById(id)
                .orElseThrow(() -> new IdInvalidException("Mẫu bộ chứng từ không tồn tại"));
        validateCategoryManagementAccess(entity);
        entity.setActive(active);
        return convertToCategoryDTO(dossierCategoryRepository.save(entity));
    }

    @Transactional
    public void deleteCategory(Long id) {
        AccountingDossierCategory entity = dossierCategoryRepository.findById(id)
                .orElseThrow(() -> new IdInvalidException("Mẫu bộ chứng từ không tồn tại"));
        validateCategoryManagementAccess(entity);
        if (repository.existsByDossierCategory_Id(id)) {
            throw new IdInvalidException("Không thể xóa mẫu đã được sử dụng để tạo bộ chứng từ. Hãy ngưng dùng mẫu này thay vì xóa.");
        }
        dossierCategoryRepository.delete(entity);
    }

    private void applyCategoryRequest(AccountingDossierCategory entity, AccountingDossierCategoryRequest req,
            boolean updating) {
        String scope = normalizeCategoryScope(req.getScope());
        entity.setCategoryName(req.getCategoryName().trim());
        entity.setDescription(req.getDescription() == null ? null : req.getDescription().trim());
        entity.setCompanyId("COMPANY".equals(scope) ? req.getCompanyId() : null);
        entity.setScope(scope);
        entity.setSource(entity.getSource() == null ? "MANUAL" : entity.getSource());
        entity.setActive(req.isActive());
        entity.setVersion(entity.getVersion() == null ? 1 : updating ? entity.getVersion() + 1 : entity.getVersion());

        String code = req.getCategoryCode() == null ? null : req.getCategoryCode().trim();
        if (code == null || code.isEmpty()) {
            code = "BCTM-" + System.currentTimeMillis();
        }
        if (!code.equals(entity.getCategoryCode()) && dossierCategoryRepository.findByCategoryCode(code).isPresent()) {
            throw new IdInvalidException("Mã mẫu bộ chứng từ đã tồn tại");
        }
        entity.setCategoryCode(code);

        entity.getCategoryDocuments().clear();
        entity.getCategoryDocuments().addAll(resolveCategoryDocumentItems(entity, req));
    }

    /**
     * Configuration is intentionally stricter than the generic company scope: only a
     * Super Admin can manage shared (GLOBAL) templates. Other roles can manage a
     * COMPANY template only when the company is in their assigned scope.
     */
    private Specification<AccountingDossierCategory> templateManagementScopeSpec() {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope == null || scope.isSuperAdmin()) {
            return Specification.where(null);
        }
        Set<Long> companyIds = scope.companyIds();
        if (companyIds == null || companyIds.isEmpty()) {
            return (root, query, builder) -> builder.disjunction();
        }
        return (root, query, builder) -> builder.and(
                builder.equal(root.get("scope"), "COMPANY"),
                root.get("companyId").in(companyIds));
    }

    /** Shared templates remain selectable while creating a dossier; company templates
     * are additionally limited to the caller's assigned companies. */
    private Specification<AccountingDossierCategory> templateSelectionScopeSpec() {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope == null || scope.isSuperAdmin()) {
            return Specification.where(null);
        }
        Set<Long> companyIds = scope.companyIds();
        if (companyIds == null || companyIds.isEmpty()) {
            return (root, query, builder) -> builder.equal(root.get("scope"), "GLOBAL");
        }
        return (root, query, builder) -> builder.or(
                builder.equal(root.get("scope"), "GLOBAL"),
                builder.and(
                        builder.equal(root.get("scope"), "COMPANY"),
                        root.get("companyId").in(companyIds)));
    }

    private void validateCategoryManagementAccess(AccountingDossierCategory entity) {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope == null || scope.isSuperAdmin()) {
            return;
        }
        if (!"COMPANY".equals(normalizeCategoryScope(entity.getScope()))
                || entity.getCompanyId() == null
                || scope.companyIds() == null
                || !scope.companyIds().contains(entity.getCompanyId())) {
            throw new PermissionException("Bạn chỉ có thể thao tác mẫu theo công ty trong phạm vi được phân quyền");
        }
    }

    private void validateCategoryWriteScope(AccountingDossierCategoryRequest req) {
        String scope = normalizeCategoryScope(req.getScope());
        UserScopeContext.UserScope userScope = UserScopeContext.get();
        if ("GLOBAL".equals(scope)) {
            if (userScope != null && !userScope.isSuperAdmin()) {
                throw new PermissionException("Chỉ Super Admin được phép thiết lập mẫu áp dụng toàn hệ thống");
            }
            return;
        }
        if (req.getCompanyId() == null) {
            throw new IdInvalidException("Vui lòng chọn công ty áp dụng cho mẫu");
        }
        if (userScope != null && !userScope.isSuperAdmin()
                && (userScope.companyIds() == null || !userScope.companyIds().contains(req.getCompanyId()))) {
            throw new PermissionException("Bạn không có quyền thiết lập mẫu cho công ty này");
        }
    }

    private String normalizeCategoryScope(String scope) {
        String normalized = scope == null || scope.trim().isEmpty() ? "GLOBAL" : scope.trim().toUpperCase();
        if (!"GLOBAL".equals(normalized) && !"COMPANY".equals(normalized)) {
            throw new IdInvalidException("Phạm vi mẫu bộ chứng từ không hợp lệ");
        }
        return normalized;
    }

    private List<AccountingDossierCategoryDocument> resolveCategoryDocumentItems(
            AccountingDossierCategory entity,
            AccountingDossierCategoryRequest req) {
        List<AccountingDossierCategoryDocument> items = new ArrayList<>();
        if (req.getDocumentCategoryItems() != null && !req.getDocumentCategoryItems().isEmpty()) {
            for (AccountingDossierCategoryRequest.DocumentCategoryItemRequest itemReq : req
                    .getDocumentCategoryItems()) {
                if (itemReq.getDocumentCategoryId() == null) {
                    continue;
                }
                AccountingDocumentCategory docCategory = accountingCategoryRepository
                        .findById(itemReq.getDocumentCategoryId())
                        .orElseThrow(() -> new IdInvalidException(
                                "Loại chứng từ không tồn tại: " + itemReq.getDocumentCategoryId()));
                items.add(buildCategoryDocument(entity, docCategory, !Boolean.FALSE.equals(itemReq.getRequired()),
                        itemReq.getSortOrder()));
            }
            return items;
        }

        if (req.getDocumentCategoryIds() == null) {
            return items;
        }
        int index = 0;
        for (Long docCategoryId : req.getDocumentCategoryIds()) {
            AccountingDocumentCategory docCategory = accountingCategoryRepository.findById(docCategoryId)
                    .orElseThrow(() -> new IdInvalidException("Loại chứng từ không tồn tại: " + docCategoryId));
            items.add(buildCategoryDocument(entity, docCategory, true, index++));
        }
        return items;
    }

    private AccountingDossierCategoryDocument buildCategoryDocument(
            AccountingDossierCategory entity,
            AccountingDocumentCategory docCategory,
            boolean required,
            Integer sortOrder) {
        AccountingDossierCategoryDocument item = new AccountingDossierCategoryDocument();
        item.setId(new AccountingDossierCategoryDocumentId(entity.getId(), docCategory.getId()));
        item.setDossierCategory(entity);
        item.setDocumentCategory(docCategory);
        item.setRequired(required);
        item.setSortOrder(sortOrder);
        return item;
    }

    private ResAccountingDossierCategoryDTO convertToCategoryDTO(AccountingDossierCategory entity) {
        ResAccountingDossierCategoryDTO dto = new ResAccountingDossierCategoryDTO();
        dto.setId(entity.getId());
        dto.setCategoryCode(entity.getCategoryCode());
        dto.setCategoryName(entity.getCategoryName());
        dto.setDescription(entity.getDescription());
        dto.setCompanyId(entity.getCompanyId());
        dto.setScope(entity.getScope());
        dto.setSource(entity.getSource());
        dto.setVersion(entity.getVersion());
        dto.setActive(entity.isActive());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());
        if (entity.getCategoryDocuments() != null) {
            dto.setDocumentCategories(entity.getCategoryDocuments().stream().map(item -> {
                ResAccountingDossierCategoryDTO.DocumentCategoryRef ref = new ResAccountingDossierCategoryDTO.DocumentCategoryRef();
                AccountingDocumentCategory docCategory = item.getDocumentCategory();
                ref.setId(docCategory.getId());
                ref.setCategoryCode(docCategory.getCategoryCode());
                ref.setCategoryName(docCategory.getCategoryName());
                ref.setSymbol(docCategory.getSymbol());
                ref.setRequired(item.isRequired());
                ref.setSortOrder(item.getSortOrder());
                return ref;
            }).collect(Collectors.toList()));
        }
        return dto;
    }

    // ==================== DOCUMENT ITEMS ====================

    public List<ResAccountingDossierDocumentDTO> fetchAllDocuments(Long dossierId) {
        fetchById(dossierId);
        return documentItemRepository.findByDossierIdAndActiveTrueOrderByCreatedAtDescIdDesc(dossierId).stream()
                .map(this::convertToDocumentDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public ResAccountingDossierDocumentDTO addDocument(Long dossierId, AccountingDossierDocumentRequest req) {
        AccountingDossier dossier = fetchById(dossierId);
        validateEditable(dossier);
        validateDocumentMutator(dossier);

        AccountingDossierDocument item = new AccountingDossierDocument();
        item.setDossier(dossier);
        applyDocumentRequest(item, req);

        AccountingDossierDocument saved = documentItemRepository.save(item);
        if (saved.getDocument() != null || saved.getFileUrl() != null || saved.getExternalLink() != null) {
            createDocumentVersion(saved, "Tạo chứng từ con");
        }

        dossierAuditService.writeLog(dossier, "ADD_DOCUMENT_ITEM", "Thêm chứng từ con: " + saved.getDocumentName(),
                "DOSSIER_DOCUMENT", saved.getId(), null, saved.getCheckStatus(), null,
                buildDocumentFileSnapshot(saved));

        return convertToDocumentDTO(saved);
    }

    @Transactional
    public ResAccountingDossierDocumentDTO updateDocument(Long dossierId, Long docId,
            AccountingDossierDocumentRequest req) {
        AccountingDossier dossier = fetchById(dossierId);
        validateEditable(dossier);
        validateDocumentMutator(dossier);

        AccountingDossierDocument item = documentItemRepository.findById(docId)
                .orElseThrow(() -> new IdInvalidException("Chứng từ con không tồn tại"));
        if (!item.getDossier().getId().equals(dossierId)) {
            throw new IdInvalidException("Chứng từ con không thuộc bộ chứng từ này");
        }
        if (!item.isActive()) {
            throw new IdInvalidException("Chứng từ con đã bị xóa");
        }

        String beforeValue = buildDocumentFileSnapshot(item);
        applyDocumentRequest(item, req);
        maybeCreateDocumentVersion(item, beforeValue, "Cập nhật chứng từ con");
        item.setCheckStatus("PENDING");
        item.setCheckNote(null);
        AccountingDossierDocument saved = documentItemRepository.save(item);
        dossierAuditService.writeLog(dossier, "UPDATE_DOCUMENT_ITEM", "Sửa chứng từ con: " + saved.getDocumentName(),
                "DOSSIER_DOCUMENT", saved.getId(), null, "PENDING", beforeValue, buildDocumentFileSnapshot(saved));
        return convertToDocumentDTO(saved);
    }

    @Transactional
    public void deleteDocument(Long dossierId, Long docId) {
        AccountingDossier dossier = fetchById(dossierId);
        validateEditable(dossier);
        validateDocumentMutator(dossier);

        AccountingDossierDocument item = documentItemRepository.findById(docId)
                .orElseThrow(() -> new IdInvalidException("Chứng từ con không tồn tại"));
        if (!item.getDossier().getId().equals(dossierId)) {
            throw new IdInvalidException("Chứng từ con không thuộc bộ chứng từ này");
        }
        if (!item.isActive()) {
            throw new IdInvalidException("Chứng từ con đã bị xóa");
        }

        String docName = item.getDocumentName();
        item.setActive(false);
        item.setDeletedAt(Instant.now());
        item.setDeletedBy(vn.system.app.common.util.SecurityUtil.getCurrentUserLogin().orElse(""));
        documentItemRepository.save(item);
        dossierAuditService.writeLog(dossier, "SOFT_DELETE_DOCUMENT_ITEM", "Xóa mềm chứng từ con: " + docName,
                "DOSSIER_DOCUMENT", item.getId(), item.getCheckStatus(), item.getCheckStatus(), null, "active=false");
    }

    @Transactional
    public ResAccountingDossierDocumentDTO reviewDocument(Long dossierId, Long docId,
            AccountingDossierDocumentCheckRequest req) {
        AccountingDossier dossier = fetchById(dossierId);
        if (!REVIEWABLE_DOSSIER_STATUSES.contains(dossier.getStatus().name())) {
            throw new PermissionException("Chỉ bộ chứng từ đã chuyển xử lý mới được kiểm tra chứng từ con");
        }
        validateDocumentReviewer(dossier);

        AccountingDossierDocument item = documentItemRepository.findById(docId)
                .orElseThrow(() -> new IdInvalidException("Chứng từ con không tồn tại"));
        if (!item.getDossier().getId().equals(dossierId)) {
            throw new IdInvalidException("Chứng từ con không thuộc bộ chứng từ này");
        }
        if (!item.isActive()) {
            throw new IdInvalidException("Chứng từ con đã bị xóa");
        }

        String checkStatus = normalizeCheckStatus(req.getCheckStatus());
        validateDocumentCheckStatus(dossier, checkStatus, req.getNote());

        String fromStatus = item.getCheckStatus();
        item.setCheckStatus(checkStatus);
        item.setCheckNote(normalizeNote(req.getNote(), null));

        AccountingDossierDocument saved = documentItemRepository.save(item);
        dossierAuditService.writeLog(dossier, "CHECK_DOCUMENT_ITEM_" + checkStatus,
                buildDocumentCheckNote(saved.getDocumentName(), checkStatus, saved.getCheckNote()),
                "DOSSIER_DOCUMENT", saved.getId(), fromStatus, checkStatus, null, saved.getCheckNote());
        return convertToDocumentDTO(saved);
    }

    private void applyDocumentRequest(AccountingDossierDocument item, AccountingDossierDocumentRequest req) {
        AccountingDocumentCategory category = accountingCategoryRepository.findById(req.getAccountingCategoryId())
                .orElseThrow(() -> new IdInvalidException("Danh mục loại chứng từ không tồn tại"));
        item.setAccountingCategory(category);
        item.setDocumentName(req.getDocumentName().trim());
        item.setDocumentType(req.getDocumentType() == null || req.getDocumentType().trim().isEmpty()
                ? "OTHER"
                : req.getDocumentType().trim().toUpperCase());
        if (!SUPPORTED_DOCUMENT_TYPES.contains(item.getDocumentType())) {
            throw new IdInvalidException("Định dạng chứng từ không được hỗ trợ");
        }

        if (req.getDocumentId() != null) {
            Document doc = documentRepository.findById(req.getDocumentId())
                    .orElseThrow(() -> new IdInvalidException("File/Văn bản đính kèm không tồn tại"));
            item.setDocument(doc);
        } else {
            item.setDocument(null);
        }
        item.setFileUrl(normalizeNote(req.getFileUrl(), null));
        item.setExternalLink(normalizeNote(req.getExternalLink(), null));
        item.setInvoiceDate(req.getInvoiceDate());
        item.setInvoiceNumber(normalizeNote(req.getInvoiceNumber(), null));
        item.setInvoiceContent(normalizeNote(req.getInvoiceContent(), null));
        item.setPartnerName(normalizeNote(req.getPartnerName(), null));
        item.setPartnerType(normalizePartnerType(req.getPartnerType()));
        item.setAmount(req.getAmount());
        item.setCurrency(normalizeCurrency(req.getCurrency()));

        String invNum = req.getInvoiceNumber() != null ? req.getInvoiceNumber().trim() : null;
        String partName = req.getPartnerName() != null ? req.getPartnerName().trim() : null;

        if (invNum != null && !invNum.isEmpty() && partName != null && !partName.isEmpty()) {
            List<AccountingDossierStatus> activeStatuses = List.of(
                AccountingDossierStatus.SUBMITTED,
                AccountingDossierStatus.IN_REVIEW,
                AccountingDossierStatus.RETURN_REQUESTED,
                AccountingDossierStatus.RETURNED,
                AccountingDossierStatus.APPROVED,
                AccountingDossierStatus.ARCHIVED
            );
            Long currentDossierId = item.getDossier() != null ? item.getDossier().getId() : -1L;
            List<AccountingDossierDocument> dups = documentItemRepository.findDuplicateInvoices(
                invNum, partName, currentDossierId, activeStatuses);

            if (dups != null && !dups.isEmpty()) {
                String codes = dups.stream()
                    .map(d -> d.getDossier().getDossierCode() != null ? d.getDossier().getDossierCode() : "BCT-" + d.getDossier().getId())
                    .distinct()
                    .collect(Collectors.joining(", "));
                throw new DuplicateInvoiceWarningException(
                    "Số hóa đơn '" + invNum + "' của '" + partName + "' đã tồn tại ở bộ chứng từ: " + codes + ". Không được phép lưu trùng lặp hóa đơn.");
            }
        }
    }

    private void validateDocumentCheckStatus(AccountingDossier dossier, String checkStatus, String note) {
        if (!SUPPORTED_DOCUMENT_CHECK_STATUSES.contains(checkStatus)) {
            throw new IdInvalidException("Trạng thái kiểm tra không hợp lệ");
        }
        if ("NOT_REQUIRED".equals(checkStatus) && dossier.getCategoryMode() == AccountingDossierCategoryMode.TEMPLATE) {
            throw new PermissionException("Chứng từ theo mẫu không được đánh dấu Không yêu cầu");
        }
        if (("NEED_SUPPLEMENT".equals(checkStatus) || "INVALID".equals(checkStatus))
                && (note == null || note.trim().isEmpty())) {
            throw new IdInvalidException("Vui lòng nhập ghi chú khi yêu cầu bổ sung hoặc đánh dấu không hợp lệ");
        }
    }

    private String normalizeCheckStatus(String checkStatus) {
        if (checkStatus == null) {
            throw new IdInvalidException("Trạng thái kiểm tra không được để trống");
        }
        return checkStatus.trim().toUpperCase();
    }

    private String buildDocumentCheckNote(String documentName, String checkStatus, String checkNote) {
        String statusLabel = switch (checkStatus) {
            case "VALID" -> "Xác nhận hợp lệ";
            case "NEED_SUPPLEMENT" -> "Yêu cầu bổ sung";
            case "INVALID" -> "Đánh dấu không hợp lệ";
            case "NOT_REQUIRED" -> "Đánh dấu không yêu cầu";
            default -> checkStatus;
        };
        return documentName + " - " + statusLabel + (checkNote == null || checkNote.isBlank() ? "" : ": " + checkNote);
    }

    private void validateRequiredDocumentsBeforeSubmit(AccountingDossier dossier) {
        if (dossier.getCategoryMode() != AccountingDossierCategoryMode.TEMPLATE
                || dossier.getDossierCategory() == null) {
            return;
        }
        List<AccountingDossierDocument> activeDocuments = documentItemRepository
                .findByDossierIdAndActiveTrue(dossier.getId());
        Set<Long> existingCategoryIds = activeDocuments.stream()
                .map(item -> item.getAccountingCategory() == null ? null : item.getAccountingCategory().getId())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<String> missingRequiredNames = dossier.getDossierCategory().getCategoryDocuments().stream()
                .filter(AccountingDossierCategoryDocument::isRequired)
                .filter(item -> item.getDocumentCategory() != null)
                .filter(item -> !existingCategoryIds.contains(item.getDocumentCategory().getId()))
                .map(item -> item.getDocumentCategory().getCategoryName())
                .collect(Collectors.toList());
        if (!missingRequiredNames.isEmpty()) {
            throw new IdInvalidException("Thiếu chứng từ bắt buộc: " + String.join(", ", missingRequiredNames));
        }

        Set<Long> categoriesWithAttachment = activeDocuments.stream()
                .filter(this::hasDocumentAttachment)
                .map(item -> item.getAccountingCategory() == null ? null : item.getAccountingCategory().getId())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<String> missingAttachmentNames = dossier.getDossierCategory().getCategoryDocuments().stream()
                .filter(AccountingDossierCategoryDocument::isRequired)
                .filter(item -> item.getDocumentCategory() != null)
                .filter(item -> !categoriesWithAttachment.contains(item.getDocumentCategory().getId()))
                .map(item -> item.getDocumentCategory().getCategoryName())
                .collect(Collectors.toList());
        if (!missingAttachmentNames.isEmpty()) {
            throw new IdInvalidException(
                    "Chứng từ bắt buộc chưa có file/link đính kèm: " + String.join(", ", missingAttachmentNames));
        }
    }

    private boolean hasDocumentAttachment(AccountingDossierDocument item) {
        return item.getDocument() != null
                || (item.getFileUrl() != null && !item.getFileUrl().isBlank())
                || (item.getExternalLink() != null && !item.getExternalLink().isBlank());
    }

    private void maybeCreateDocumentVersion(AccountingDossierDocument item, String beforeValue, String changeNote) {
        String afterValue = buildDocumentFileSnapshot(item);
        if (!Objects.equals(beforeValue, afterValue)) {
            createDocumentVersion(item, changeNote);
        }
    }

    private void createDocumentVersion(AccountingDossierDocument item, String changeNote) {
        AccountingDossierDocumentVersion version = new AccountingDossierDocumentVersion();
        version.setDossierDocument(item);
        version.setVersionNo((int) documentVersionRepository.countByDossierDocumentId(item.getId()) + 1);
        version.setDocumentId(item.getDocument() == null ? null : item.getDocument().getId());
        version.setFileUrl(item.getFileUrl());
        version.setExternalLink(item.getExternalLink());
        version.setChangeNote(changeNote);
        documentVersionRepository.save(version);
    }

    private String buildDocumentFileSnapshot(AccountingDossierDocument item) {
        Long documentId = item.getDocument() == null ? null : item.getDocument().getId();
        return "documentId=" + documentId
                + ";fileUrl=" + nullToDash(item.getFileUrl())
                + ";externalLink=" + nullToDash(item.getExternalLink());
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String normalizePartnerType(String value) {
        String normalized = normalizeNote(value, null);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toUpperCase();
        if (!Set.of("SUPPLIER", "CUSTOMER", "OTHER").contains(normalized)) {
            throw new IdInvalidException("Loại đối tác không hợp lệ");
        }
        return normalized;
    }

    private String normalizeCurrency(String value) {
        String normalized = normalizeNote(value, null);
        return normalized == null ? null : normalized.toUpperCase();
    }

    private ResAccountingDossierDocumentDTO convertToDocumentDTO(AccountingDossierDocument entity) {
        ResAccountingDossierDocumentDTO dto = new ResAccountingDossierDocumentDTO();
        dto.setId(entity.getId());
        dto.setDossierId(entity.getDossier().getId());
        dto.setDossierCode(entity.getDossier().getDossierCode());
        dto.setDossierContent(entity.getDossier().getContent());
        dto.setDossierStatus(entity.getDossier().getStatus() == null ? null : entity.getDossier().getStatus().name());
        dto.setDossierStorageStatus(
                entity.getDossier().getStorageStatus() == null ? null : entity.getDossier().getStorageStatus().name());
        dto.setDocumentName(entity.getDocumentName());
        dto.setDocumentType(entity.getDocumentType());
        dto.setCheckStatus(entity.getCheckStatus());
        dto.setCheckNote(entity.getCheckNote());
        dto.setFileUrl(entity.getFileUrl());
        dto.setExternalLink(entity.getExternalLink());
        dto.setInvoiceDate(entity.getInvoiceDate());
        dto.setInvoiceNumber(entity.getInvoiceNumber());
        dto.setInvoiceContent(entity.getInvoiceContent());
        dto.setPartnerName(entity.getPartnerName());
        dto.setPartnerType(entity.getPartnerType());
        dto.setAmount(entity.getAmount());
        dto.setCurrency(entity.getCurrency());
        dto.setActive(entity.isActive());
        dto.setDeletedAt(entity.getDeletedAt());
        dto.setDeletedBy(entity.getDeletedBy());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());

        if (entity.getAccountingCategory() != null) {
            ResAccountingDossierDocumentDTO.CategoryRef ref = new ResAccountingDossierDocumentDTO.CategoryRef();
            ref.setId(entity.getAccountingCategory().getId());
            ref.setCategoryCode(entity.getAccountingCategory().getCategoryCode());
            ref.setCategoryName(entity.getAccountingCategory().getCategoryName());
            dto.setAccountingCategory(ref);
        }
        if (entity.getDocument() != null) {
            ResAccountingDossierDocumentDTO.DocumentRef ref = new ResAccountingDossierDocumentDTO.DocumentRef();
            ref.setId(entity.getDocument().getId());
            ref.setDocumentCode(entity.getDocument().getDocumentCode());
            ref.setDocumentName(entity.getDocument().getDocumentName());
            dto.setDocument(ref);
        }
        if (entity.getDossier().getCompany() != null) {
            dto.setCompany(toDossierDocumentRef(
                    entity.getDossier().getCompany().getId(),
                    entity.getDossier().getCompany().getCode(),
                    entity.getDossier().getCompany().getName()));
        }
        if (entity.getDossier().getDepartment() != null) {
            dto.setDepartment(toDossierDocumentRef(
                    entity.getDossier().getDepartment().getId(),
                    entity.getDossier().getDepartment().getCode(),
                    entity.getDossier().getDepartment().getName()));
        }
        if (entity.getDossier().getSection() != null) {
            dto.setSection(toDossierDocumentRef(
                    entity.getDossier().getSection().getId(),
                    entity.getDossier().getSection().getCode(),
                    entity.getDossier().getSection().getName()));
        }
        return dto;
    }

    private ResAccountingDossierDocumentDTO.Ref toDossierDocumentRef(Long id, String code, String name) {
        ResAccountingDossierDocumentDTO.Ref ref = new ResAccountingDossierDocumentDTO.Ref();
        ref.setId(id);
        ref.setCode(code);
        ref.setName(name);
        return ref;
    }

    private String generateDossierCode(Company company) {
        int currentYear = Year.now().getValue();
        AccountingDossierSequence seq = sequenceRepository.findByCompanyIdAndYearWithLock(company.getId(), currentYear)
                .orElseGet(() -> {
                    AccountingDossierSequence newSeq = new AccountingDossierSequence();
                    newSeq.setCompany(company);
                    newSeq.setYear(currentYear);
                    newSeq.setCurrentNumber(0);
                    return newSeq;
                });
        seq.setCurrentNumber(seq.getCurrentNumber() + 1);
        sequenceRepository.save(seq);
        return String.format("BCT-%d-%06d", currentYear, seq.getCurrentNumber());
    }

    private String normalizeNote(String note, String fallback) {
        if (note == null || note.trim().isEmpty()) {
            return fallback;
        }
        return note.trim();
    }

    private ResAccountingDossierAuditLogDTO convertToLogDTO(AccountingDossierAuditLog entity) {
        ResAccountingDossierAuditLogDTO dto = new ResAccountingDossierAuditLogDTO();
        dto.setId(entity.getId());
        dto.setDossierId(entity.getDossier().getId());
        dto.setActionType(entity.getActionType());
        dto.setNote(entity.getNote());
        dto.setActorUserId(entity.getActorUserId());
        dto.setIpAddress(entity.getIpAddress());
        dto.setUserAgent(entity.getUserAgent());
        dto.setTargetType(entity.getTargetType());
        dto.setTargetId(entity.getTargetId());
        dto.setFromStatus(entity.getFromStatus());
        dto.setToStatus(entity.getToStatus());
        dto.setBeforeValue(entity.getBeforeValue());
        dto.setAfterValue(entity.getAfterValue());
        dto.setBulkActionId(entity.getBulkActionId());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        return dto;
    }



    // ==================== PHASE 3: SYNC TEMPLATE ====================

    @Transactional
    public ResAccountingDossierDTO rejectTemplateSync(Long id, AccountingDossierActionRequest req) {
        AccountingDossier dossier = fetchById(id);
        validateChiefAccountantForTemplateSync();
        if (dossier.getCategoryMode() != AccountingDossierCategoryMode.UNSTRUCTURED) {
            throw new IdInvalidException("Chỉ bộ chứng từ phi cấu trúc mới có đề xuất đồng bộ mẫu");
        }
        if (!dossier.isSyncCategoryRequested()) {
            throw new IdInvalidException("Bộ chứng từ không có đề xuất đồng bộ mẫu đang chờ");
        }

        dossier.setSyncCategoryRequested(false);
        AccountingDossier saved = repository.save(dossier);
        dossierAuditService.writeLog(saved, "REJECT_SYNC_TO_TEMPLATE",
                normalizeNote(req == null ? null : req.getNote(), "Từ chối đồng bộ bộ chứng từ phi cấu trúc thành mẫu"),
                "DOSSIER", saved.getId(), null, null, "syncCategoryRequested=true", "syncCategoryRequested=false");
        notificationService.notifyCreator(saved, "ACCOUNTING_TEMPLATE_SYNC_REJECTED",
                String.format("Đề xuất đồng bộ mẫu từ bộ chứng từ %s đã bị từ chối.",
                        notificationService.dossierLabel(saved)));
        return convertToDTO(saved);
    }

    @Transactional
    private void syncToCategoryTemplate(AccountingDossier dossier) {
        try {
            AccountingDossierCategory category = new AccountingDossierCategory();
            String syncCode = "CAT_SYNC_" + dossier.getCompany().getId() + "_"
                    + System.currentTimeMillis();
            category.setCategoryCode(syncCode);
            String catName = (dossier.getCustomCategoryName() != null && !dossier.getCustomCategoryName().isBlank())
                    ? dossier.getCustomCategoryName()
                    : dossier.getContent();
            category.setCategoryName(catName);
            category.setDescription("Đồng bộ tự động từ bộ chứng từ " + dossier.getDossierCode());
            category.setCompanyId(dossier.getCompany().getId());
            category.setScope("COMPANY");
            category.setSource("SYNCED_FROM_UNSTRUCTURED");
            AccountingDossierCategory savedCategory = dossierCategoryRepository.save(category);

            List<AccountingDossierDocument> docs = documentItemRepository.findByDossierIdAndActiveTrue(dossier.getId());
            List<AccountingDossierCategoryDocument> catDocs = new ArrayList<>();
            for (int i = 0; i < docs.size(); i++) {
                AccountingDossierDocument doc = docs.get(i);
                if (doc.getAccountingCategory() != null) {
                    AccountingDossierCategoryDocument catDoc = new AccountingDossierCategoryDocument();
                    catDoc.setId(new AccountingDossierCategoryDocumentId(savedCategory.getId(),
                            doc.getAccountingCategory().getId()));
                    catDoc.setDossierCategory(savedCategory);
                    catDoc.setDocumentCategory(doc.getAccountingCategory());
                    catDoc.setRequired(true);
                    catDoc.setSortOrder(i + 1);
                    catDocs.add(catDoc);
                }
            }
            if (!catDocs.isEmpty()) {
                savedCategory.getCategoryDocuments().addAll(catDocs);
                dossierCategoryRepository.save(savedCategory);
            }
            dossierAuditService.writeLog(dossier, "SYNC_TO_TEMPLATE", "Đồng bộ phi cấu trúc thành mẫu danh mục: " + syncCode,
                    "DOSSIER_CATEGORY", savedCategory.getId(), null, null, null, syncCode);
        } catch (Exception e) {
            // Log but don't fail the approval
        }
    }

    private void validateChiefAccountantForTemplateSync() {
        String email = SecurityUtil.getCurrentUserLogin().orElse("");
        vn.system.app.modules.user.domain.User currentUser = userRepository.findByEmail(email);
        if (currentUser == null) {
            throw new IdInvalidException("Không xác định được người thao tác");
        }
        String roleName = currentUser.getRole() == null ? "" : currentUser.getRole().getName().toUpperCase();
        if (roleName.equals("SUPER_ADMIN")
                || roleName.contains("CHIEF_ACCOUNTANT")
                || roleName.contains("KETOANTRUONG")
                || roleName.contains("KẾ TOÁN TRƯỞNG")) {
            return;
        }
        throw new PermissionException("Chỉ kế toán trưởng mới có quyền từ chối đồng bộ mẫu");
    }

    // ==================== PHASE 3: BULK ACTIONS ====================

    @Transactional
    public ResAccountingDossierBulkActionDTO bulkApprove(List<Long> ids, AccountingDossierActionRequest req) {
        return bulkDossierAction(ids, req, "APPROVE");
    }

    @Transactional
    public ResAccountingDossierBulkActionDTO bulkReject(List<Long> ids, AccountingDossierActionRequest req) {
        return bulkDossierAction(ids, req, "REJECT");
    }

    private ResAccountingDossierBulkActionDTO bulkDossierAction(
            List<Long> ids,
            AccountingDossierActionRequest req,
            String action) {
        String bulkActionId = newBulkActionId(action);
        ResAccountingDossierBulkActionDTO response = initBulkResponse(bulkActionId, ids == null ? 0 : ids.size());
        AccountingDossier firstLogDossier = null;

        if (ids == null || ids.isEmpty()) {
            return response;
        }
        vn.system.app.modules.user.domain.User resolvedUser = userRepository.findByEmail(SecurityUtil.getCurrentUserLogin().orElse(""));
        if (resolvedUser == null) throw new IdInvalidException("Không xác định được người thao tác");
        bulkActionUser.set(resolvedUser);
        try {
        for (Long dossierId : ids) {
            ResAccountingDossierBulkActionDTO.Item item = new ResAccountingDossierBulkActionDTO.Item();
            item.setId(dossierId);
            try {
                ResAccountingDossierDTO dto = "REJECT".equals(action) ? reject(dossierId, req)
                        : approve(dossierId, req);
                item.setSuccess(true);
                item.setStatus(dto.getStatus().name());
                response.setSuccessCount(response.getSuccessCount() + 1);

                AccountingDossier dossier = repository.getReferenceById(dossierId);
                firstLogDossier = firstLogDossier == null ? dossier : firstLogDossier;
                dossierAuditService.writeLog(dossier, "BULK_" + action + "_DOSSIER_ITEM",
                        "Thao tác hàng loạt " + action + " thành công",
                        "DOSSIER", dossierId, null, dto.getStatus().name(), null, null, bulkActionId);
            } catch (Exception e) {
                item.setSuccess(false);
                item.setError(e.getMessage());
                response.setFailureCount(response.getFailureCount() + 1);
                firstLogDossier = writeBulkFailureLogIfPossible(
                        firstLogDossier,
                        dossierId,
                        "BULK_" + action + "_DOSSIER_ITEM_FAILED",
                        e.getMessage(),
                        bulkActionId);
            }
            response.getItems().add(item);
        }
        } finally {
            bulkActionUser.remove();
        }

        writeBulkSummaryLogIfPossible(firstLogDossier, "BULK_" + action + "_DOSSIER",
                "Tổng kết thao tác hàng loạt " + action,
                response);
        return response;
    }

    private vn.system.app.modules.user.domain.User resolveActionUser(String email) {
        vn.system.app.modules.user.domain.User cached = bulkActionUser.get();
        return cached != null ? cached : userRepository.findByEmail(email);
    }

    // ==================== PHASE 3: BULK CHECK DOCUMENTS ====================

    @Transactional
    public ResAccountingDossierBulkActionDTO bulkCheckDocuments(Long dossierId, List<Long> documentIds,
            String checkStatus, String note) {
        AccountingDossier dossier = fetchById(dossierId);
        if (!REVIEWABLE_DOSSIER_STATUSES.contains(dossier.getStatus().name())) {
            throw new PermissionException("Bộ chứng từ không ở trạng thái được phép kiểm tra chứng từ con");
        }
        String normalizedCheckStatus = normalizeCheckStatus(checkStatus);
        validateDocumentCheckStatus(dossier, normalizedCheckStatus, note);
        validateDocumentReviewer(dossier);

        String bulkActionId = newBulkActionId("CHECK_DOCUMENTS");
        ResAccountingDossierBulkActionDTO response = initBulkResponse(
                bulkActionId,
                documentIds == null ? 0 : documentIds.size());
        if (documentIds == null || documentIds.isEmpty()) {
            writeBulkSummaryLogIfPossible(dossier, "BULK_CHECK_DOCUMENTS",
                    "Tổng kết kiểm tra hàng loạt chứng từ con", response);
            return response;
        }

        List<AccountingDossierDocument> docs = documentItemRepository.findByIdInAndDossierIdAndActiveTrue(documentIds,
                dossierId);
        Set<Long> foundIds = new HashSet<>();
        for (AccountingDossierDocument doc : docs) {
            foundIds.add(doc.getId());
            String fromStatus = doc.getCheckStatus();
            doc.setCheckStatus(normalizedCheckStatus);
            doc.setCheckNote(normalizeNote(note, null));
            response.setSuccessCount(response.getSuccessCount() + 1);
            ResAccountingDossierBulkActionDTO.Item item = new ResAccountingDossierBulkActionDTO.Item();
            item.setId(doc.getId());
            item.setSuccess(true);
            item.setStatus(normalizedCheckStatus);
            response.getItems().add(item);
            dossierAuditService.writeLog(dossier, "BULK_CHECK_DOCUMENT_ITEM", "Kiểm tra hàng loạt: " + normalizedCheckStatus,
                    "DOSSIER_DOCUMENT", doc.getId(), fromStatus, normalizedCheckStatus, null, doc.getDocumentName(),
                    bulkActionId);
        }

        for (Long documentId : documentIds) {
            if (!foundIds.contains(documentId)) {
                response.setFailureCount(response.getFailureCount() + 1);
                ResAccountingDossierBulkActionDTO.Item item = new ResAccountingDossierBulkActionDTO.Item();
                item.setId(documentId);
                item.setSuccess(false);
                item.setError("Chứng từ con không tồn tại hoặc không thuộc bộ chứng từ này");
                response.getItems().add(item);
                dossierAuditService.writeLog(dossier, "BULK_CHECK_DOCUMENT_ITEM_FAILED",
                        item.getError(),
                        "DOSSIER_DOCUMENT", documentId, null, null, null, null, bulkActionId);
            }
        }

        writeBulkSummaryLogIfPossible(dossier, "BULK_CHECK_DOCUMENTS",
                "Tổng kết kiểm tra hàng loạt chứng từ con", response);
        if (response.getSuccessCount() > 0
                && ("NEED_SUPPLEMENT".equals(normalizedCheckStatus) || "INVALID".equals(normalizedCheckStatus))) {
            notificationService.notifyCreator(dossier, "ACCOUNTING_DOCUMENTS_NEED_ATTENTION",
                    String.format("Bộ chứng từ %s có %d chứng từ %s. Vui lòng kiểm tra và bổ sung nếu cần.",
                            notificationService.dossierLabel(dossier),
                            response.getSuccessCount(),
                            notificationService.documentCheckStatusLabel(normalizedCheckStatus)));
        }
        return response;
    }

    private ResAccountingDossierBulkActionDTO initBulkResponse(String bulkActionId, int total) {
        ResAccountingDossierBulkActionDTO response = new ResAccountingDossierBulkActionDTO();
        response.setBulkActionId(bulkActionId);
        response.setTotal(total);
        return response;
    }

    private String newBulkActionId(String action) {
        return "BULK_" + action + "_" + UUID.randomUUID();
    }

    private AccountingDossier writeBulkFailureLogIfPossible(
            AccountingDossier firstLogDossier,
            Long dossierId,
            String actionType,
            String error,
            String bulkActionId) {
        AccountingDossier dossier = repository.findById(dossierId).orElse(null);
        if (dossier == null) {
            return firstLogDossier;
        }
        dossierAuditService.writeLog(dossier, actionType, error, "DOSSIER", dossierId, null, null, null, error, bulkActionId);
        return firstLogDossier == null ? dossier : firstLogDossier;
    }

    private void writeBulkSummaryLogIfPossible(
            AccountingDossier dossier,
            String actionType,
            String note,
            ResAccountingDossierBulkActionDTO response) {
        if (dossier == null) {
            return;
        }
        String summary = "total=" + response.getTotal()
                + ", success=" + response.getSuccessCount()
                + ", failure=" + response.getFailureCount();
        dossierAuditService.writeLog(dossier, actionType, note, "BULK_ACTION", null, null, null,
                response.getBulkActionId(), summary, response.getBulkActionId());
    }

    @Transactional
    public ResAccountingDossierDTO reassignDirector(Long id, String newApproverUserId, String reason) {
        // Check if current user has reassign permission or is SUPER_ADMIN
        String email = SecurityUtil.getCurrentUserLogin().orElse("");
        vn.system.app.modules.user.domain.User actor = userRepository.findByEmail(email);
        if (actor == null) {
            throw new IdInvalidException("Không xác định được người thao tác");
        }
        boolean hasReassignPermission = actor.getRole() != null && (
            "SUPER_ADMIN".equals(actor.getRole().getName()) || 
            (actor.getRole().getPermissions() != null && actor.getRole().getPermissions().stream().anyMatch(p -> "Emergency Reassign Giám đốc".equals(p.getName()) || "ACCOUNTING_APPROVAL_REASSIGN".equals(p.getName())))
        );
        if (!hasReassignPermission) {
            throw new PermissionException("Bạn không có quyền thực hiện chức năng Emergency Reassign");
        }

        AccountingDossier dossier = fetchById(id);
        if (dossier.getStatus() != AccountingDossierStatus.SUBMITTED && dossier.getStatus() != AccountingDossierStatus.IN_REVIEW) {
            throw new IdInvalidException("Chỉ có thể đổi người duyệt khi hồ sơ đang trong quá trình duyệt");
        }
        List<AccountingDossierApprovalStep> steps = approvalStepRepository
                .findByDossierIdAndActiveTrueOrderByStepOrderAsc(id);
        
        // Find current step
        AccountingDossierApprovalStep currentStep = steps.stream()
                .filter(step -> step.getStatus() == ApprovalStepStatus.CURRENT)
                .findFirst()
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy bước hiện tại đang chờ duyệt"));
        
        // Lock down to ONLY DIRECTOR step
        if (currentStep.getApproverType() != ApproverType.DIRECTOR) {
            throw new IdInvalidException("Chỉ được phép thực hiện Emergency Reassign cho bước Giám đốc (DIRECTOR)");
        }

        if (reason == null || reason.trim().isEmpty()) {
            throw new IdInvalidException("Lý do reassign không được để trống");
        }

        vn.system.app.modules.user.domain.User newDirector = userRepository.findById(newApproverUserId)
                .orElseThrow(() -> new IdInvalidException("Người nhận mới không tồn tại"));
                
        if (!newDirector.isActive()) {
            throw new IdInvalidException("Người nhận mới phải ở trạng thái hoạt động (active)");
        }

        // Check if user has Director permission and belongs to company
        List<vn.system.app.modules.user.domain.User> validDirectors = approverResolutionService.resolveAllDirectorUserIds(dossier.getCompany().getId());
        boolean isValidDirectorForCompany = validDirectors.stream()
                .anyMatch(d -> d.getId().equals(newApproverUserId));
        if (!isValidDirectorForCompany) {
            throw new IdInvalidException("Người nhận mới phải là Giám đốc hoạt động hợp lệ thuộc cùng công ty của hồ sơ");
        }

        String oldAssigneeId = currentStep.getApproverUserId();
        String oldAssigneeName = "N/A";
        if (oldAssigneeId != null) {
            oldAssigneeName = userRepository.findById(oldAssigneeId).map(vn.system.app.modules.user.domain.User::getName).orElse("N/A");
        }

        currentStep.setApproverUserId(newApproverUserId);
        try {
            approvalStepRepository.saveAndFlush(currentStep);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            throw new IdInvalidException("Bước duyệt vừa được cập nhật bởi người khác (DOSSIER_CONCURRENTLY_MODIFIED)");
        }

        // Write audit log
        String logMsg = String.format("[Emergency Reassign] Thay đổi người duyệt bước Giám đốc từ [%s] sang [%s]. Người thực hiện: [%s]. Lý do: %s", 
                oldAssigneeName, newDirector.getName(), actor.getName(), reason);
        dossierAuditService.writeLog(dossier, "REASSIGN_DIRECTOR", logMsg, "APPROVAL_STEP", currentStep.getId(), 
                oldAssigneeId, newApproverUserId, null, reason);

        notificationService.notifyUsers(List.of(newApproverUserId), "ACCOUNTING_APPROVER_REASSIGNED",
                String.format("Bạn được phân công phê duyệt bước Giám đốc cho bộ chứng từ %s. Lý do: %s",
                        notificationService.dossierLabel(dossier), reason),
                dossier.getId());
        if (oldAssigneeId != null && !oldAssigneeId.equals(newApproverUserId)) {
            notificationService.notifyUsers(List.of(oldAssigneeId), "ACCOUNTING_APPROVER_REASSIGNED",
                    String.format("Bạn không còn là người duyệt bước Giám đốc của bộ chứng từ %s.",
                            notificationService.dossierLabel(dossier)),
                    dossier.getId());
        }
        notificationService.notifyCreator(dossier, "ACCOUNTING_APPROVER_REASSIGNED",
                String.format("Bước Giám đốc của bộ chứng từ %s đã được điều chuyển sang %s.",
                        notificationService.dossierLabel(dossier), newDirector.getName()));

        writeOutbox(dossier.getId(), "REASSIGN", "Director reassigned to: " + newDirector.getName() + " reason: " + reason, "DOSSIER_" + dossier.getId() + "_REASSIGN_DIRECTOR_STEP_" + currentStep.getId());

        return convertToDTO(dossier);
    }
}
