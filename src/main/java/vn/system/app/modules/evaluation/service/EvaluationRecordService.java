package vn.system.app.modules.evaluation.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.common.util.UserScopeContext;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.evaluation.domain.*;
import vn.system.app.modules.evaluation.domain.enums.*;
import vn.system.app.modules.evaluation.domain.request.ExtendRecordDeadlineRequest;
import vn.system.app.modules.evaluation.domain.request.TrainingPlanRequest;
import vn.system.app.modules.evaluation.domain.request.ReassignEvaluatorRequest;
import vn.system.app.modules.evaluation.domain.response.ResEvaluationTaskCountsDTO;
import vn.system.app.modules.evaluation.domain.response.ResDashboardManagerDTO;
import vn.system.app.modules.evaluation.domain.response.ResDashboardApproverDTO;
import java.util.Map;
import vn.system.app.modules.evaluation.repository.*;
import vn.system.app.modules.notification.service.NotificationService;
import vn.system.app.modules.user.domain.User;
import vn.system.app.modules.user.repository.UserRepository;
import vn.system.app.modules.userposition.repository.UserPositionRepository;

/**
 * Service xử lý luồng đánh giá HQCV (Giai đoạn 1 → 4).
 *
 * Bao gồm:
 * - Nhân viên tự chấm điểm + nộp (Giai đoạn 1)
 * - Quản lý trực tiếp chấm điểm + gửi phê duyệt (Giai đoạn 2)
 * - Quản lý gián tiếp phê duyệt / trả lại (Giai đoạn 3)
 * - Tính điểm, xếp loại
 */
@Service
@lombok.extern.slf4j.Slf4j
public class EvaluationRecordService {

    private final EvaluationRecordRepository recordRepo;
    private final EvaluationScoreRepository scoreRepo;
    private final EvaluationCommentRepository commentRepo;
    private final EvaluationTrainingPlanRepository trainingPlanRepo;
    private final EvaluationHistoryRepository historyRepo;
    private final EvaluationScoreAuditRepository scoreAuditRepo;
    private final TemplateCriteriaRepository criteriaRepo;
    private final UserRepository userRepo;
    private final UserPositionRepository userPositionRepo;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final EvaluationTemplateValidator templateValidator;
    private final EvaluationPeriodService periodService;
    private final EvaluationMapper mapper;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private EvaluationRecordService self;

    public EvaluationRecordService(
            EvaluationRecordRepository recordRepo,
            EvaluationScoreRepository scoreRepo,
            EvaluationCommentRepository commentRepo,
            EvaluationTrainingPlanRepository trainingPlanRepo,
            EvaluationHistoryRepository historyRepo,
            EvaluationScoreAuditRepository scoreAuditRepo,
            TemplateCriteriaRepository criteriaRepo,
            UserRepository userRepo,
            UserPositionRepository userPositionRepo,
            org.springframework.context.ApplicationEventPublisher eventPublisher,
            EvaluationTemplateValidator templateValidator,
            EvaluationPeriodService periodService,
            EvaluationMapper mapper) {
        this.recordRepo = recordRepo;
        this.scoreRepo = scoreRepo;
        this.commentRepo = commentRepo;
        this.trainingPlanRepo = trainingPlanRepo;
        this.historyRepo = historyRepo;
        this.scoreAuditRepo = scoreAuditRepo;
        this.criteriaRepo = criteriaRepo;
        this.userRepo = userRepo;
        this.userPositionRepo = userPositionRepo;
        this.eventPublisher = eventPublisher;
        this.templateValidator = templateValidator;
        this.periodService = periodService;
        this.mapper = mapper;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FETCH RECORDS
    // ═══════════════════════════════════════════════════════════════════════════

    public EvaluationRecord fetchRecordById(Long id) {
        return recordRepo.findById(id)
                .orElseThrow(() -> new IdInvalidException("Bản đánh giá không tồn tại"));
    }

    @Transactional(readOnly = true)
    public EvaluationRecord fetchRecordByIdWithFullTemplateForUser(Long id, User requester) {
        EvaluationRecord record = fetchRecordByIdWithFullTemplate(id);
        assertCanViewRecord(record, requester);
        return record;
    }

    /**
     * Fetch record kèm đầy đủ template sections/criteria — dùng cho API chi tiết
     */
    @Transactional(readOnly = true)
    public EvaluationRecord fetchRecordByIdWithFullTemplate(Long id) {
        EvaluationRecord record = recordRepo.findById(id)
                .orElseThrow(() -> new IdInvalidException("Bản đánh giá không tồn tại"));
        // Force load subCriteria và levels trong transaction để tránh
        // LazyInitializationException
        if (record.getTemplate() != null && record.getTemplate().getSections() != null) {
            record.getTemplate().getSections().forEach(section -> {
                if (section.getCriteria() != null) {
                    section.getCriteria().forEach(criteria -> {
                        if (criteria.getSubCriteria() != null) {
                            criteria.getSubCriteria().forEach(sub -> {
                                if (sub.getLevels() != null)
                                    sub.getLevels().size();
                            });
                        }
                        if (criteria.getLevels() != null)
                            criteria.getLevels().size();
                    });
                }
            });
        }
        return record;
    }

    /** Danh sách bản đánh giá của nhân viên (lịch sử) */
    public List<EvaluationRecord> fetchRecordsByEmployee(String employeeId) {
        return recordRepo.findByEmployeeIdOrderByCreatedAtDesc(employeeId);
    }

    /** Danh sách toàn bộ bản đánh giá dành cho màn hình quản trị. */
    public List<EvaluationRecord> fetchAllRecordsForAdministration() {
        if (!canViewAllEvaluationRecords()) {
            throw new IdInvalidException("Bạn không có quyền xem toàn bộ bản đánh giá");
        }
        return recordRepo.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Danh sách form cho quản lý trực tiếp trong kỳ (bao gồm cả record được uỷ
     * quyền)
     */
    public List<EvaluationRecord> fetchRecordsForDirectManager(Long periodId, String managerId) {
        if (canSeeAllEvaluationRecords()) {
            return recordRepo.findByPeriodIdOrderByCreatedAtDesc(periodId);
        }
        return recordRepo.findByPeriodIdAndDirectManagerId(periodId, managerId);
    }

    /** Danh sách form cho quản lý gián tiếp trong kỳ */
    public List<EvaluationRecord> fetchRecordsForIndirectManager(Long periodId, String managerId) {
        if (canSeeAllEvaluationRecords()) {
            return recordRepo.findByPeriodIdOrderByCreatedAtDesc(periodId);
        }
        return recordRepo.findByPeriodIdAndIndirectManagerId(periodId, managerId);
    }

    public List<EvaluationScore> fetchScores(Long recordId) {
        return scoreRepo.findByEvaluationRecordId(recordId);
    }

    public List<EvaluationComment> fetchComments(Long recordId) {
        return commentRepo.findByEvaluationRecordId(recordId);
    }

    public List<EvaluationTrainingPlan> fetchTrainingPlans(Long recordId) {
        return trainingPlanRepo.findByEvaluationRecordId(recordId);
    }

    /**
     * Danh sách form chờ quản lý trực tiếp chấm (bao gồm cả record được uỷ quyền)
     */
    public List<EvaluationRecord> fetchPendingForDirectManager(String managerId) {
        List<RecordStatus> pendingStatuses = List.of(
                RecordStatus.PENDING_MANAGER_REVIEW,
                RecordStatus.MANAGER_REVIEWING,
                RecordStatus.REVISION_NEEDED);
        return recordRepo.findByDirectManagerIdAndStatusIn(managerId, pendingStatuses);
    }

    public List<EvaluationRecord> fetchHistoryForDirectManager(String managerId) {
        return recordRepo.findManagerInvolvedRecordsForUser(managerId);
    }

    // Lịch sử (tất cả các bản đánh giá) của quản lý gián tiếp
    public List<EvaluationRecord> fetchHistoryForIndirectManager(String approverId) {
        return recordRepo.findApproverInvolvedRecordsForUser(approverId);
    }

    /** Danh sách form chờ quản lý gián tiếp phê duyệt */
    public List<EvaluationRecord> fetchPendingForIndirectManager(String managerId) {
        return recordRepo.findByIndirectManagerIdAndStatusIn(managerId,
                List.of(RecordStatus.PENDING_APPROVAL));
    }

    @Transactional(readOnly = true)
    public ResEvaluationTaskCountsDTO fetchTaskCounts(String userId) {
        long myPending = recordRepo.countByEmployeeIdAndStatusIn(userId,
                List.of(RecordStatus.NOT_STARTED, RecordStatus.EMPLOYEE_DRAFTING, RecordStatus.REVISION_NEEDED));
        long pendingManager = recordRepo.countByDirectManagerIdAndStatusIn(userId,
                List.of(RecordStatus.PENDING_MANAGER_REVIEW, RecordStatus.MANAGER_REVIEWING,
                        RecordStatus.REVISION_NEEDED));
        long pendingApproval = recordRepo.countByIndirectManagerIdAndStatusIn(userId,
                List.of(RecordStatus.PENDING_APPROVAL));
        return new ResEvaluationTaskCountsDTO(myPending, pendingManager, pendingApproval);
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<EvaluationRecord> fetchCompletedSummary(
            Long periodId,
            Long departmentId,
            Long companyId,
            Long sectionId,
            String searchText,
            String filterGrade,
            User requester,
            org.springframework.data.domain.Pageable pageable) {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope == null) {
            return org.springframework.data.domain.Page.empty(pageable);
        }

        boolean isAllVisible = scope.isSuperAdmin() || scope.isAdminLevel();

        List<String> targetEmployeeIds = null;
        if (sectionId != null) {
            targetEmployeeIds = userPositionRepo.findUserIdsBySectionId(sectionId);
        } else if (departmentId != null) {
            targetEmployeeIds = userPositionRepo.findUserIdsByDepartmentId(departmentId);
        } else if (companyId != null) {
            targetEmployeeIds = userPositionRepo.findUserIdsByCompanyId(companyId);
        }

        // Lọc chéo bảo mật dựa trên UserScope
        if (!isAllVisible) {
            List<String> allowedEmployeeIds = new java.util.ArrayList<>();
            if (scope.isCompanyLevel() && scope.companyIds() != null && !scope.companyIds().isEmpty()) {
                for (Long compId : scope.companyIds()) {
                    allowedEmployeeIds.addAll(userPositionRepo.findUserIdsByCompanyId(compId));
                }
            } else if (scope.isDepartmentLevel() && scope.departmentIds() != null && !scope.departmentIds().isEmpty()) {
                for (Long deptId : scope.departmentIds()) {
                    allowedEmployeeIds.addAll(userPositionRepo.findUserIdsByDepartmentId(deptId));
                }
            } else {
                allowedEmployeeIds.add(scope.userId());
            }

            if (targetEmployeeIds == null) {
                targetEmployeeIds = allowedEmployeeIds;
            } else {
                targetEmployeeIds = targetEmployeeIds.stream()
                        .filter(allowedEmployeeIds::contains)
                        .collect(java.util.stream.Collectors.toList());
            }
        }

        if (targetEmployeeIds != null && targetEmployeeIds.isEmpty()) {
            return org.springframework.data.domain.Page.empty(pageable);
        }

        boolean hasEmployeeIds = (targetEmployeeIds != null);
        java.util.Collection<String> queryEmployeeIds = hasEmployeeIds ? targetEmployeeIds
                : java.util.Collections.emptyList();

        String cleanSearchText = (searchText == null || searchText.trim().isEmpty()) ? null : searchText.trim();
        String cleanGrade = (filterGrade == null || filterGrade.trim().isEmpty()) ? null : filterGrade.trim();

        return recordRepo.findCompletedSummary(
                periodId,
                hasEmployeeIds,
                queryEmployeeIds,
                cleanGrade,
                cleanSearchText,
                pageable);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GIAI ĐOẠN 1: NHÂN VIÊN TỰ ĐÁNH GIÁ
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Nhân viên lưu/cập nhật điểm cho một tiêu chí.
     * Có thể gọi nhiều lần (SAVE DRAFT).
     */
    @Transactional
    public EvaluationScore saveEmployeeScore(Long recordId, Long criteriaId, Double score, User employee) {
        EvaluationRecord record = fetchRecordById(recordId);
        assertEmployeePhaseOpen(record);

        // --- IDOR CHECK ---
        if (!record.getEmployee().getId().equals(employee.getId())) {
            throw new IdInvalidException("Bạn không có quyền đánh giá bản đánh giá này");
        }

        if (record.getStatus() != RecordStatus.NOT_STARTED
                && record.getStatus() != RecordStatus.EMPLOYEE_DRAFTING
                && record.getStatus() != RecordStatus.REVISION_NEEDED) {
            throw new IdInvalidException("Bạn không thể đánh giá ở trạng thái hiện tại");
        }

        // Chuyển trạng thái sang EMPLOYEE_DRAFTING nếu đang NOT_STARTED
        if (record.getStatus() == RecordStatus.NOT_STARTED) {
            RecordStatus oldStatus = record.getStatus();
            record.setStatus(RecordStatus.EMPLOYEE_DRAFTING);
            recordRepo.save(record);
            saveHistory(record, oldStatus, RecordStatus.EMPLOYEE_DRAFTING, employee, null);
        }

        validateScore(score);

        // Kiểm tra tiêu chí có sub không → nếu có thì không cho nhập tay
        TemplateCriteria criteria = criteriaRepo.findById(criteriaId)
                .orElseThrow(() -> new IdInvalidException("Tiêu chí không tồn tại"));
        assertCriteriaBelongsToRecordTemplate(record, criteria);

        List<TemplateCriteria> subs = criteriaRepo.findByParentCriteriaId(criteriaId);
        if (!subs.isEmpty()) {
            throw new IdInvalidException("Tiêu chí cha có sub-tiêu chí, điểm được tính tự động");
        }

        return saveOrUpdateScore(record, criteria, ScoredBy.EMPLOYEE, score, true);
    }

    /**
     * Nhân viên nộp tự đánh giá.
     * Validate tất cả tiêu chí đã có điểm.
     * Chuyển trạng thái: EMPLOYEE_DRAFTING → PENDING_MANAGER_REVIEW.
     */
    @Transactional
    public EvaluationRecord submitEmployeeEvaluation(Long recordId, User employee) {
        EvaluationRecord record = fetchRecordById(recordId);
        assertEmployeePhaseOpen(record);

        // --- IDOR CHECK ---
        if (!record.getEmployee().getId().equals(employee.getId())) {
            throw new IdInvalidException("Bạn không có quyền nộp bản đánh giá này");
        }

        if (record.getStatus() != RecordStatus.NOT_STARTED && record.getStatus() != RecordStatus.EMPLOYEE_DRAFTING) {
            throw new IdInvalidException("Bạn không thể nộp đánh giá ở trạng thái hiện tại");
        }

        // Chặn cả dữ liệu ACTIVE cũ bị thiếu section/criteria/weight/level.
        templateValidator.validateReadyForUse(record.getTemplate());

        // Validate tất cả tiêu chí lá đã có điểm
        validateAllLeafCriteriaScored(record, ScoredBy.EMPLOYEE);

        // Tính điểm tiêu chí cha từ sub
        recalculateParentScores(record, ScoredBy.EMPLOYEE);

        // Tính tổng điểm nhân viên
        double totalScore = calculateTotalScore(record, ScoredBy.EMPLOYEE);
        record.setEmployeeTotalScore(totalScore);

        // Chuyển trạng thái
        RecordStatus oldStatus = record.getStatus();
        record.setStatus(RecordStatus.PENDING_MANAGER_REVIEW);
        record.setEmployeeSubmittedAt(Instant.now());
        recordRepo.save(record);

        // Ghi lịch sử
        saveHistory(record, oldStatus, RecordStatus.PENDING_MANAGER_REVIEW, record.getEmployee(), null);

        // Thông báo quản lý trực tiếp
        sendNotification(record.getDirectManager(), "MANAGER_REVIEW_NEEDED",
                String.format("Nhân viên %s đã nộp tự đánh giá. Vui lòng chấm điểm.",
                        record.getEmployee().getName()),
                managerDrawerLink(record.getId()));

        return record;
    }

    /** Nhân viên lưu nhận xét tự đánh giá (không bắt buộc) */
    @Transactional
    public EvaluationComment saveEmployeeSelfReview(Long recordId, String content, User employee) {
        EvaluationRecord record = fetchRecordById(recordId);
        assertEmployeePhaseOpen(record);

        // --- IDOR CHECK ---
        if (!record.getEmployee().getId().equals(employee.getId())) {
            throw new IdInvalidException("Bạn không có quyền nhận xét bản đánh giá này");
        }

        if (record.getStatus() != RecordStatus.NOT_STARTED && record.getStatus() != RecordStatus.EMPLOYEE_DRAFTING) {
            throw new IdInvalidException("Bạn không thể nhận xét ở trạng thái hiện tại");
        }

        // Chuyển trạng thái sang DRAFTING nếu đang NOT_STARTED
        if (record.getStatus() == RecordStatus.NOT_STARTED) {
            RecordStatus oldStatus = record.getStatus();
            record.setStatus(RecordStatus.EMPLOYEE_DRAFTING);
            recordRepo.save(record);
            saveHistory(record, oldStatus, RecordStatus.EMPLOYEE_DRAFTING, record.getEmployee(), null);
        }

        // Tìm comment cũ hoặc tạo mới
        List<EvaluationComment> existing = commentRepo.findByEvaluationRecordIdAndCommentType(
                recordId, CommentType.SELF_REVIEW);

        EvaluationComment comment;
        if (!existing.isEmpty()) {
            comment = existing.get(0);
            comment.setContent(content);
        } else {
            comment = new EvaluationComment();
            comment.setEvaluationRecord(record);
            comment.setCommentType(CommentType.SELF_REVIEW);
            comment.setContent(content);
            comment.setWrittenBy(employee);
        }

        return commentRepo.save(comment);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GIAI ĐOẠN 2: QUẢN LÝ TRỰC TIẾP CHẤM ĐIỂM
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Quản lý trực tiếp lưu điểm cho một tiêu chí.
     * Lần đầu lưu sẽ chuyển status sang MANAGER_REVIEWING.
     */
    @Transactional
    public EvaluationScore saveManagerScore(Long recordId, Long criteriaId, Double score, User manager) {
        EvaluationRecord record = fetchRecordById(recordId);
        assertManagerPhaseOpen(record);

        // --- IDOR CHECK ---
        if (!canActAsDirectManager(record, manager)) {
            throw new IdInvalidException("Bạn không có quyền đánh giá bản đánh giá này");
        }

        if (record.getStatus() != RecordStatus.PENDING_MANAGER_REVIEW
                && record.getStatus() != RecordStatus.MANAGER_REVIEWING
                && record.getStatus() != RecordStatus.REVISION_NEEDED) {
            throw new IdInvalidException("Quản lý đánh giá không thể đánh giá ở trạng thái hiện tại");
        }

        validateScore(score);

        TemplateCriteria criteria = criteriaRepo.findById(criteriaId)
                .orElseThrow(() -> new IdInvalidException("Tiêu chí không tồn tại"));
        assertCriteriaBelongsToRecordTemplate(record, criteria);

        List<TemplateCriteria> subs = criteriaRepo.findByParentCriteriaId(criteriaId);
        if (!subs.isEmpty()) {
            throw new IdInvalidException("Tiêu chí cha có sub-tiêu chí, điểm được tính tự động");
        }

        // Chuyển trạng thái sang MANAGER_REVIEWING nếu đang PENDING hoặc
        // REVISION_NEEDED
        if (record.getStatus() == RecordStatus.PENDING_MANAGER_REVIEW
                || record.getStatus() == RecordStatus.REVISION_NEEDED) {
            RecordStatus oldStatus = record.getStatus();
            record.setStatus(RecordStatus.MANAGER_REVIEWING);
            recordRepo.save(record);
            saveHistory(record, oldStatus, RecordStatus.MANAGER_REVIEWING, manager, null);
        }

        return saveOrUpdateScore(record, criteria, ScoredBy.MANAGER, score, true);
    }

    /**
     * Quản lý trực tiếp gửi phê duyệt.
     * Validate đầy đủ điểm.
     * Chuyển: MANAGER_REVIEWING → PENDING_APPROVAL.
     */
    @Transactional
    public EvaluationRecord submitManagerReview(Long recordId, User manager) {
        EvaluationRecord record = fetchRecordById(recordId);
        assertManagerPhaseOpen(record);

        // --- IDOR CHECK ---
        if (!canActAsDirectManager(record, manager)) {
            throw new IdInvalidException("Bạn không có quyền gửi phê duyệt bản đánh giá này");
        }

        if (record.getStatus() != RecordStatus.PENDING_MANAGER_REVIEW
                && record.getStatus() != RecordStatus.MANAGER_REVIEWING
                && record.getStatus() != RecordStatus.REVISION_NEEDED) {
            throw new IdInvalidException("Bạn không thể gửi phê duyệt ở trạng thái hiện tại");
        }

        templateValidator.validateReadyForUse(record.getTemplate());
        validateAllLeafCriteriaScored(record, ScoredBy.MANAGER);
        recalculateParentScores(record, ScoredBy.MANAGER);

        double totalScore = calculateTotalScore(record, ScoredBy.MANAGER);
        record.setManagerTotalScore(totalScore);
        record.setFinalGrade(calculateGrade(totalScore));

        RecordStatus oldStatus = record.getStatus();
        record.setStatus(RecordStatus.PENDING_APPROVAL);
        record.setManagerSubmittedAt(Instant.now());
        recordRepo.save(record);

        saveHistory(record, oldStatus, RecordStatus.PENDING_APPROVAL, manager, null);

        // Thông báo quản lý gián tiếp
        sendNotification(record.getIndirectManager(), "APPROVAL_NEEDED",
                String.format("Đánh giá nhân viên %s đã được chấm điểm. Vui lòng phê duyệt.",
                        record.getEmployee().getName()),
                approverDrawerLink(record.getId()));

        return record;
    }

    /** Quản lý trực tiếp lưu nhận xét */
    @Transactional
    public EvaluationComment saveManagerFeedback(Long recordId, String content, User manager) {
        EvaluationRecord record = fetchRecordById(recordId);
        assertManagerPhaseOpen(record);

        // --- IDOR CHECK ---
        if (!canActAsDirectManager(record, manager)) {
            throw new IdInvalidException("Bạn không có quyền nhận xét bản đánh giá này");
        }

        if (record.getStatus() != RecordStatus.PENDING_MANAGER_REVIEW
                && record.getStatus() != RecordStatus.MANAGER_REVIEWING
                && record.getStatus() != RecordStatus.REVISION_NEEDED) {
            throw new IdInvalidException("Quản lý không thể nhận xét ở trạng thái hiện tại");
        }

        // Chuyển trạng thái sang MANAGER_REVIEWING nếu đang PENDING hoặc
        // REVISION_NEEDED
        if (record.getStatus() == RecordStatus.PENDING_MANAGER_REVIEW
                || record.getStatus() == RecordStatus.REVISION_NEEDED) {
            RecordStatus oldStatus = record.getStatus();
            record.setStatus(RecordStatus.MANAGER_REVIEWING);
            recordRepo.save(record);
            saveHistory(record, oldStatus, RecordStatus.MANAGER_REVIEWING, manager, null);
        }

        List<EvaluationComment> existing = commentRepo.findByEvaluationRecordIdAndCommentType(
                recordId, CommentType.MANAGER_FEEDBACK);

        EvaluationComment comment;
        if (!existing.isEmpty()) {
            comment = existing.get(0);
            comment.setContent(content);
        } else {
            comment = new EvaluationComment();
            comment.setEvaluationRecord(record);
            comment.setCommentType(CommentType.MANAGER_FEEDBACK);
            comment.setContent(content);
            comment.setWrittenBy(manager);
        }

        return commentRepo.save(comment);
    }

    /** Quản lý trực tiếp lưu kế hoạch đào tạo */
    @Transactional
    public EvaluationTrainingPlan saveTrainingPlan(Long recordId, TrainingPlanRequest request, User manager) {
        EvaluationRecord record = fetchRecordById(recordId);
        assertManagerPhaseOpen(record);

        // --- IDOR CHECK ---
        if (!canActAsDirectManager(record, manager)) {
            throw new IdInvalidException("Bạn không có quyền thêm kế hoạch đào tạo cho bản đánh giá này");
        }

        if (record.getStatus() != RecordStatus.PENDING_MANAGER_REVIEW
                && record.getStatus() != RecordStatus.MANAGER_REVIEWING
                && record.getStatus() != RecordStatus.REVISION_NEEDED) {
            throw new IdInvalidException("Quản lý không thể thêm kế hoạch đào tạo ở trạng thái hiện tại");
        }

        // Chuyển trạng thái sang MANAGER_REVIEWING nếu đang PENDING hoặc
        // REVISION_NEEDED
        if (record.getStatus() == RecordStatus.PENDING_MANAGER_REVIEW
                || record.getStatus() == RecordStatus.REVISION_NEEDED) {
            RecordStatus oldStatus = record.getStatus();
            record.setStatus(RecordStatus.MANAGER_REVIEWING);
            recordRepo.save(record);
            saveHistory(record, oldStatus, RecordStatus.MANAGER_REVIEWING, manager, null);
        }

        EvaluationTrainingPlan plan = new EvaluationTrainingPlan();
        plan.setTrainingGroup(request.getTrainingGroup());
        plan.setContent(request.getContent());
        plan.setRequirements(request.getRequirements());
        plan.setSolution(request.getSolution());
        plan.setCompletionTimeline(request.getCompletionTimeline());
        plan.setEvaluationRecord(record);
        return trainingPlanRepo.save(plan);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GIAI ĐOẠN 3: QUẢN LÝ GIÁN TIẾP PHÊ DUYỆT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Người phê duyệt lưu điểm cho một tiêu chí.
     * Copy toàn bộ điểm của quản lý sang nếu đây là lần đầu sửa.
     */
    @Transactional
    public EvaluationScore saveApproverScore(Long recordId, Long criteriaId, Double score, User approver) {
        EvaluationRecord record = fetchRecordById(recordId);
        assertApprover(record, approver);
        assertApprovalPhaseOpen(record);

        if (record.getStatus() != RecordStatus.PENDING_APPROVAL) {
            throw new IdInvalidException(
                    "Người phê duyệt chỉ có thể đánh giá khi bản đánh giá ở trạng thái chờ phê duyệt");
        }

        validateScore(score);

        TemplateCriteria criteria = criteriaRepo.findById(criteriaId)
                .orElseThrow(() -> new IdInvalidException("Tiêu chí không tồn tại"));
        assertCriteriaBelongsToRecordTemplate(record, criteria);

        List<TemplateCriteria> subs = criteriaRepo.findByParentCriteriaId(criteriaId);
        if (!subs.isEmpty()) {
            throw new IdInvalidException("Tiêu chí cha có sub-tiêu chí, điểm được tính tự động");
        }

        // Copy điểm MANAGER sang APPROVER nếu chưa có điểm APPROVER nào
        List<EvaluationScore> existingApproverScores = scoreRepo.findByEvaluationRecordIdAndScoredBy(recordId,
                ScoredBy.APPROVER);
        if (existingApproverScores.isEmpty()) {
            List<EvaluationScore> managerScores = scoreRepo.findByEvaluationRecordIdAndScoredBy(recordId,
                    ScoredBy.MANAGER);
            List<EvaluationScore> copiedScores = new java.util.ArrayList<>(managerScores.size());
            for (EvaluationScore ms : managerScores) {
                copiedScores.add(saveOrUpdateScore(record, ms.getCriteria(), ScoredBy.APPROVER, ms.getScore(),
                        null, null, false, false));
            }
            scoreRepo.saveAll(copiedScores);
        }

        return saveOrUpdateScore(record, criteria, ScoredBy.APPROVER, score, true);
    }

    /** Phê duyệt: PENDING_APPROVAL → COMPLETED */
    @Transactional
    public EvaluationRecord approveRecord(Long recordId, String overrideReason, User approver) {
        EvaluationRecord record = fetchRecordById(recordId);
        assertApprover(record, approver);
        assertApprovalPhaseOpen(record);

        if (record.getStatus() != RecordStatus.PENDING_APPROVAL) {
            throw new IdInvalidException("Bản đánh giá không ở trạng thái chờ phê duyệt");
        }

        templateValidator.validateReadyForUse(record.getTemplate());

        // T4: Bắt buộc lý do khi người phê duyệt đè điểm quản lý
        List<EvaluationScore> approverScores = scoreRepo.findByEvaluationRecordIdAndScoredBy(record.getId(),
                ScoredBy.APPROVER);
        List<EvaluationScore> managerScores = scoreRepo.findByEvaluationRecordIdAndScoredBy(record.getId(),
                ScoredBy.MANAGER);

        boolean hasOverride = false;
        for (EvaluationScore appScore : approverScores) {
            EvaluationScore mgrScore = managerScores.stream()
                    .filter(s -> s.getCriteria().getId().equals(appScore.getCriteria().getId()))
                    .findFirst()
                    .orElse(null);

            if (mgrScore != null && appScore.getScore() != null && !appScore.getScore().equals(mgrScore.getScore())) {
                hasOverride = true;
                break;
            }
        }

        if (hasOverride) {
            if (overrideReason == null || overrideReason.trim().isEmpty()) {
                throw new IdInvalidException("Vui lòng nhập lý do điều chỉnh điểm số so với quản lý");
            }

            // Lưu lý do đè điểm vào comments
            EvaluationComment comment = new EvaluationComment();
            comment.setEvaluationRecord(record);
            comment.setCommentType(CommentType.APPROVER_OVERRIDE_NOTE);
            comment.setContent(overrideReason.trim());
            comment.setWrittenBy(approver);
            commentRepo.save(comment);
        }

        RecordStatus oldStatus = record.getStatus();
        record.setStatus(RecordStatus.COMPLETED);
        record.setApprovedAt(Instant.now());
        // completedAt sẽ được set khi nhân viên xác nhận đã xem

        // Tính toán điểm của người phê duyệt (nếu có)
        if (!approverScores.isEmpty()) {
            recalculateParentScores(record, ScoredBy.APPROVER);
            double approverTotal = calculateTotalScore(record, ScoredBy.APPROVER);
            record.setApproverTotalScore(approverTotal);
            record.setFinalGrade(calculateGrade(approverTotal));
        } else {
            record.setFinalGrade(calculateGrade(record.getManagerTotalScore()));
        }

        recordRepo.save(record);

        String historyNote = hasOverride ? "Phê duyệt hoàn tất (Có điều chỉnh điểm)" : null;
        saveHistory(record, oldStatus, RecordStatus.COMPLETED, approver, historyNote);

        // Thông báo nhân viên
        sendNotification(record.getEmployee(), "RESULT_AVAILABLE",
                "Kết quả đánh giá HQCV đã có. Nhấn để xem chi tiết.",
                "/admin/evaluation/my-records/" + record.getId());

        // Thông báo quản lý trực tiếp
        sendNotification(record.getDirectManager(), "RESULT_AVAILABLE",
                String.format("Đánh giá nhân viên %s đã được phê duyệt.",
                        record.getEmployee().getName()),
                managerDrawerLink(record.getId()));

        return record;
    }

    @Transactional
    public EvaluationRecord approveRecord(Long recordId, User approver) {
        return approveRecord(recordId, null, approver);
    }

    /** Phê duyệt hàng loạt */
    public vn.system.app.modules.evaluation.domain.response.BatchApproveResponse batchApproveRecords(
            List<Long> recordIds, User approver) {
        List<Long> successIds = new java.util.ArrayList<>();
        List<vn.system.app.modules.evaluation.domain.response.BatchApproveResponse.FailedApproval> failedRecords = new java.util.ArrayList<>();
        for (Long id : recordIds) {
            try {
                EvaluationRecord record = self.approveRecord(id, approver);
                successIds.add(record.getId());
            } catch (Exception e) {
                log.error("Lỗi khi phê duyệt hàng loạt bản đánh giá ID {}: {}", id, e.getMessage());
                failedRecords.add(
                        new vn.system.app.modules.evaluation.domain.response.BatchApproveResponse.FailedApproval(id,
                                e.getMessage()));
            }
        }
        return new vn.system.app.modules.evaluation.domain.response.BatchApproveResponse(successIds, failedRecords);
    }

    /**
     * Nhân viên xác nhận đã xem kết quả đánh giá.
     * Chỉ áp dụng khi record đã COMPLETED.
     * Ghi nhận completedAt (thời điểm nhân viên xác nhận).
     */
    @Transactional
    public EvaluationRecord confirmEmployeeAcknowledge(Long recordId, User employee) {
        EvaluationRecord record = fetchRecordById(recordId);

        // Kiểm tra đúng nhân viên trước mọi thao tác để tránh rò rỉ dữ liệu phiếu người
        // khác
        if (!record.getEmployee().getId().equals(employee.getId())) {
            throw new IdInvalidException("Bạn không có quyền xác nhận bản đánh giá này");
        }

        if (record.getStatus() != RecordStatus.COMPLETED) {
            throw new IdInvalidException("Bản đánh giá chưa được phê duyệt hoàn tất");
        }

        if (record.getCompletedAt() != null) {
            return record;
        }

        // Set completedAt là thời điểm nhân viên xác nhận đã xem
        record.setCompletedAt(Instant.now());
        recordRepo.save(record);

        // Thông báo quản lý trực tiếp biết nhân viên đã xem
        sendNotification(record.getDirectManager(), "RESULT_AVAILABLE",
                String.format("Nhân viên %s đã xác nhận xem kết quả đánh giá.",
                        record.getEmployee().getName()),
                managerDrawerLink(record.getId()));

        return record;
    }

    /**
     * T12 — Hệ thống tự xác nhận đã xem (auto-acknowledge).
     * Gọi từ scheduler sau N ngày mà nhân viên không bấm xác nhận.
     * Không cần kiểm tra ownership; ghi lịch sử với note "Hệ thống tự xác nhận".
     */
    @Transactional
    public EvaluationRecord systemAutoAcknowledge(Long recordId) {
        EvaluationRecord record = recordRepo.findById(recordId)
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy bản đánh giá ID: " + recordId));

        if (record.getStatus() != RecordStatus.COMPLETED) {
            return record; // Bỏ qua nếu không ở COMPLETED
        }
        if (record.getCompletedAt() != null) {
            return record; // Đã được xác nhận rồi
        }

        record.setCompletedAt(Instant.now());
        recordRepo.save(record);

        // Ghi lịch sử để audit
        EvaluationHistory history = new EvaluationHistory();
        history.setEvaluationRecord(record);
        history.setFromStatus(RecordStatus.COMPLETED);
        history.setToStatus(RecordStatus.COMPLETED);
        history.setPerformedBy(null); // Hệ thống tự động
        history.setNote("Hệ thống tự động xác nhận đã xem sau 7 ngày không có phản hồi");
        historyRepo.save(history);

        return record;
    }

    /**
     * Trả lại: PENDING_APPROVAL → REVISION_NEEDED.
     * Bắt buộc phải có lý do.
     */
    @Transactional
    public EvaluationRecord rejectRecord(Long recordId, String reason, User rejector) {
        EvaluationRecord record = fetchRecordById(recordId);
        assertApprover(record, rejector);
        assertApprovalPhaseOpen(record);

        if (record.getStatus() != RecordStatus.PENDING_APPROVAL) {
            throw new IdInvalidException("Bản đánh giá không ở trạng thái chờ phê duyệt");
        }

        if (reason == null || reason.isBlank()) {
            throw new IdInvalidException("Vui lòng nhập lý do trả lại");
        }

        RecordStatus oldStatus = record.getStatus();
        record.setStatus(RecordStatus.REVISION_NEEDED);

        // 3.4: Tự động nới managerDeadline = max(now + 2 ngày, deadline hiện tại)
        Instant now = Instant.now();
        Instant currentMgrDeadline = record.getManagerDeadlineOverride() != null
                ? record.getManagerDeadlineOverride()
                : record.getPeriod().getManagerDeadline();
        Instant targetDeadline = now.plus(2, ChronoUnit.DAYS);
        if (currentMgrDeadline == null || targetDeadline.isAfter(currentMgrDeadline)) {
            record.setManagerDeadlineOverride(targetDeadline);
        }

        // Tự động nới employeeDeadline
        Instant currentEmpDeadline = record.getEmployeeDeadlineOverride() != null
                ? record.getEmployeeDeadlineOverride()
                : record.getPeriod().getEmployeeDeadline();
        if (currentEmpDeadline == null || targetDeadline.isAfter(currentEmpDeadline)) {
            record.setEmployeeDeadlineOverride(targetDeadline);
        }

        recordRepo.save(record);

        // Xóa điểm của APPROVER để tránh rác khi chấm lại
        scoreRepo.deleteByEvaluationRecordIdAndScoredBy(recordId, ScoredBy.APPROVER);

        String auditReason = reason + " (Tự động nới hạn chót thêm 2 ngày cho Quản lý và Nhân viên)";
        saveHistory(record, oldStatus, RecordStatus.REVISION_NEEDED, rejector, auditReason);

        // Lưu lý do trả lại vào comments
        EvaluationComment comment = new EvaluationComment();
        comment.setEvaluationRecord(record);
        comment.setCommentType(CommentType.REJECTION_REASON);
        comment.setContent(reason);
        comment.setWrittenBy(rejector);
        commentRepo.save(comment);

        // Thông báo quản lý trực tiếp
        sendNotification(record.getDirectManager(), "REVISION_NEEDED",
                String.format("Đánh giá nhân viên %s đã bị trả lại. Lý do: %s",
                        record.getEmployee().getName(), reason),
                managerDrawerLink(record.getId()));

        return record;
    }

    /** Đếm số lần bị trả lại */
    public long countRejections(Long recordId, User requester) {
        EvaluationRecord record = fetchRecordById(recordId);
        assertCanViewRecord(record, requester);
        return historyRepo.countByEvaluationRecordIdAndToStatus(recordId, RecordStatus.REVISION_NEEDED);
    }

    /** Admin gia hạn deadline riêng cho một hoặc nhiều bản đánh giá. */
    @Transactional
    public List<EvaluationRecord> extendRecordDeadlines(
            List<Long> recordIds,
            ExtendRecordDeadlineRequest.Phase phase,
            Instant deadline,
            List<ExtendRecordDeadlineRequest.RecordDeadlineOverride> recordDeadlines,
            List<ExtendRecordDeadlineRequest.PhaseDeadlineOverride> phaseDeadlines,
            String reason,
            boolean cascade,
            User performer) {

        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope == null) {
            throw new IdInvalidException("Bạn không có quyền thực hiện hành động này");
        }
        if (recordIds == null || recordIds.isEmpty()) {
            throw new IdInvalidException("Danh sách bản đánh giá không được để trống");
        }
        if (phase == null) {
            throw new IdInvalidException("Giai đoạn gia hạn không hợp lệ");
        }
        if (deadline == null || !deadline.isAfter(Instant.now())) {
            throw new IdInvalidException("Hạn mới phải lớn hơn thời điểm hiện tại");
        }

        List<EvaluationRecord> records = recordRepo.findAllById(recordIds);
        if (records.size() != new HashSet<>(recordIds).size()) {
            throw new IdInvalidException("Một hoặc nhiều bản đánh giá không tồn tại");
        }

        Map<Long, Instant> customDeadlineMap = new java.util.HashMap<>();
        if (recordDeadlines != null && !recordDeadlines.isEmpty()) {
            for (ExtendRecordDeadlineRequest.RecordDeadlineOverride item : recordDeadlines) {
                if (item == null || item.getRecordId() == null || item.getDeadline() == null) {
                    throw new IdInvalidException("Danh sách hạn chót tuỳ chỉnh không hợp lệ");
                }
                customDeadlineMap.put(item.getRecordId(), item.getDeadline());
            }
            if (customDeadlineMap.size() != recordIds.size()) {
                throw new IdInvalidException("Vui lòng nhập hạn chót riêng cho từng bản đánh giá đã chọn");
            }
        }

        Map<ExtendRecordDeadlineRequest.Phase, Instant> customPhaseDeadlineMap = new java.util.EnumMap<>(
                ExtendRecordDeadlineRequest.Phase.class);
        if (phaseDeadlines != null && !phaseDeadlines.isEmpty()) {
            for (ExtendRecordDeadlineRequest.PhaseDeadlineOverride item : phaseDeadlines) {
                if (item == null || item.getPhase() == null || item.getDeadline() == null) {
                    throw new IdInvalidException("Danh sách hạn chót theo từng mốc không hợp lệ");
                }
                customPhaseDeadlineMap.put(item.getPhase(), item.getDeadline());
            }
        }

        for (EvaluationRecord record : records) {
            assertAdminAction(record);
            if (record.getPeriod() == null || record.getPeriod().getStatus() != PeriodStatus.ACTIVE) {
                throw new IdInvalidException("Chỉ có thể gia hạn bản đánh giá thuộc kỳ đang hoạt động");
            }
            if (record.getStatus() == RecordStatus.COMPLETED) {
                throw new IdInvalidException("Không thể gia hạn bản đánh giá đã hoàn tất");
            }

            Instant oldEmployeeDeadline = getEffectiveEmployeeDeadline(record);
            Instant oldManagerDeadline = getEffectiveManagerDeadline(record);
            Instant oldApprovalDeadline = getEffectiveApprovalDeadline(record);
            Instant currentPhaseDeadline = switch (phase) {
                case EMPLOYEE -> oldEmployeeDeadline;
                case MANAGER -> oldManagerDeadline;
                case APPROVAL -> oldApprovalDeadline;
            };

            if (currentPhaseDeadline == null) {
                throw new IdInvalidException("Bước xử lý hiện tại chưa được thiết lập hạn chót");
            }

            Instant targetDeadline = customDeadlineMap.getOrDefault(record.getId(), deadline);
            if (targetDeadline == null || !targetDeadline.isAfter(Instant.now())) {
                throw new IdInvalidException("Hạn mới phải lớn hơn thời điểm hiện tại");
            }
            if (!targetDeadline.isAfter(currentPhaseDeadline)) {
                throw new IdInvalidException("Hạn mới phải lớn hơn hạn hiện tại của từng bản đánh giá");
            }

            switch (phase) {
                case EMPLOYEE -> extendEmployeeDeadline(record, targetDeadline, cascade);
                case MANAGER -> extendManagerDeadline(record, targetDeadline, cascade);
                case APPROVAL -> extendApprovalDeadline(record, targetDeadline);
            }

            if (!customPhaseDeadlineMap.isEmpty()) {
                applyCustomPhaseDeadlines(record, phase, customPhaseDeadlineMap);
            }

            Instant newEmployeeDeadline = getEffectiveEmployeeDeadline(record);
            Instant newManagerDeadline = getEffectiveManagerDeadline(record);
            Instant newApprovalDeadline = getEffectiveApprovalDeadline(record);

            String note = String.format("Gia hạn giai đoạn %s tới %s%s",
                    getDeadlinePhaseLabel(phase),
                    formatInstant(targetDeadline),
                    reason != null && !reason.isBlank() ? ". Lý do: " + reason.trim() : "");
            saveHistory(record, record.getStatus(), record.getStatus(), performer, note);

            if (!Objects.equals(oldEmployeeDeadline, newEmployeeDeadline)) {
                notifyDeadlineExtended(record, record.getEmployee(), "tự đánh giá", newEmployeeDeadline,
                        "/admin/evaluation/my-records/" + record.getId(), reason);
            }
            if (!Objects.equals(oldManagerDeadline, newManagerDeadline)) {
                notifyDeadlineExtended(record, record.getDirectManager(), "quản lý trực tiếp chấm", newManagerDeadline,
                        managerDrawerLink(record.getId()), reason);
            }
            if (!Objects.equals(oldApprovalDeadline, newApprovalDeadline)) {
                notifyDeadlineExtended(record, record.getIndirectManager(), "quản lý gián tiếp duyệt",
                        newApprovalDeadline,
                        approverDrawerLink(record.getId()), reason);
            }
        }

        return recordRepo.saveAll(records);
    }

    /** Admin điều chuyển người chấm/duyệt bản đánh giá. */
    @Transactional
    public List<EvaluationRecord> reassignEvaluators(
            List<Long> recordIds,
            ReassignEvaluatorRequest.EvaluatorRole role,
            String newEvaluatorUserId,
            String reason,
            User performer) {

        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope == null) {
            throw new IdInvalidException("Bạn không có quyền thực hiện hành động này");
        }
        if (recordIds == null || recordIds.isEmpty()) {
            throw new IdInvalidException("Danh sách bản đánh giá không được để trống");
        }
        if (role == null) {
            throw new IdInvalidException("Vai trò người đánh giá không hợp lệ");
        }
        if (newEvaluatorUserId == null || newEvaluatorUserId.isBlank()) {
            throw new IdInvalidException("Người đánh giá mới không hợp lệ");
        }

        User newEvaluator = userRepo.findById(newEvaluatorUserId)
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy người chấm mới trong hệ thống"));

        List<EvaluationRecord> records = recordRepo.findAllById(recordIds);
        if (records.size() != new HashSet<>(recordIds).size()) {
            throw new IdInvalidException("Một hoặc nhiều bản đánh giá không tồn tại");
        }

        for (EvaluationRecord record : records) {
            assertAdminAction(record);
            if (record.getPeriod() == null || record.getPeriod().getStatus() != PeriodStatus.ACTIVE) {
                throw new IdInvalidException(
                        "Chỉ có thể điều chuyển người đánh giá cho bản đánh giá thuộc kỳ đang hoạt động");
            }
            if (record.getStatus() == RecordStatus.COMPLETED || record.getStatus() == RecordStatus.CANCELLED) {
                throw new IdInvalidException(
                        "Không thể điều chuyển người đánh giá cho bản đánh giá đã hoàn tất hoặc đã hủy");
            }

            String oldEvaluatorName = "";
            if (role == ReassignEvaluatorRequest.EvaluatorRole.DIRECT_MANAGER) {
                oldEvaluatorName = record.getDirectManager() != null ? record.getDirectManager().getName()
                        : "Chưa phân công";
                record.setDirectManager(newEvaluator);
            } else {
                oldEvaluatorName = record.getIndirectManager() != null ? record.getIndirectManager().getName()
                        : "Chưa phân công";
                record.setIndirectManager(newEvaluator);
            }

            String note = String.format("Điều chuyển %s từ [%s] sang [%s]%s",
                    role == ReassignEvaluatorRequest.EvaluatorRole.DIRECT_MANAGER ? "Quản lý trực tiếp"
                            : "Người phê duyệt",
                    oldEvaluatorName,
                    newEvaluator.getName(),
                    reason != null && !reason.isBlank() ? ". Lý do: " + reason.trim() : "");

            saveHistory(record, record.getStatus(), record.getStatus(), performer, note);

            // Gửi thông báo đến người chấm mới
            if (role == ReassignEvaluatorRequest.EvaluatorRole.DIRECT_MANAGER) {
                sendNotification(newEvaluator, "MANAGER_ASSIGNED",
                        String.format("Bạn được phân công chấm điểm đánh giá cho nhân viên %s",
                                record.getEmployee().getName()),
                        managerDrawerLink(record.getId()));
            } else {
                sendNotification(newEvaluator, "APPROVER_ASSIGNED",
                        String.format("Bạn được phân công phê duyệt đánh giá cho nhân viên %s",
                                record.getEmployee().getName()),
                        approverDrawerLink(record.getId()));
            }
        }

        return recordRepo.saveAll(records);
    }

    /** Lịch sử thay đổi trạng thái */
    public List<EvaluationHistory> fetchHistory(Long recordId, User requester) {
        EvaluationRecord record = fetchRecordById(recordId);
        assertCanViewRecord(record, requester);
        return historyRepo.findByEvaluationRecordIdOrderByPerformedAtDesc(recordId);
    }

    /** Lịch sử thay đổi điểm số */
    public List<EvaluationScoreAudit> fetchScoreAudits(Long recordId, User requester) {
        EvaluationRecord record = fetchRecordById(recordId);
        assertCanViewRecord(record, requester);
        return scoreAuditRepo.findByEvaluationRecordIdOrderByChangedAtDesc(recordId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BÁO CÁO / THỐNG KÊ
    // ═══════════════════════════════════════════════════════════════════════════

    /** Đếm theo trạng thái trong kỳ */
    public List<Object[]> getStatusDistribution(Long periodId) {
        periodService.fetchPeriodById(periodId);
        return recordRepo.countByPeriodGroupByStatus(periodId);
    }

    /** Đếm theo xếp loại A/B/C/D/E trong kỳ */
    public List<Object[]> getGradeDistribution(Long periodId) {
        periodService.fetchPeriodById(periodId);
        return recordRepo.countByPeriodGroupByFinalGrade(periodId);
    }

    /**
     * Dashboard cho quản lý trực tiếp: tổng hợp phiếu của các nhân viên mình phụ
     * trách.
     * Lọc theo chính người dùng (directManager) nên an toàn về phạm vi.
     */
    @Transactional(readOnly = true)
    public ResDashboardManagerDTO getManagerDashboard(Long periodId, User manager) {
        List<EvaluationRecord> records = periodId != null
                ? recordRepo.findByPeriodIdAndDirectManagerId(periodId, manager.getId())
                : recordRepo.findByDirectManagerIdOrderByCreatedAtDesc(manager.getId());

        int notStarted = 0, pendingReview = 0, reviewed = 0, approved = 0, revisionNeeded = 0;
        for (EvaluationRecord r : records) {
            switch (r.getStatus()) {
                case NOT_STARTED, EMPLOYEE_DRAFTING -> notStarted++;
                case PENDING_MANAGER_REVIEW, MANAGER_REVIEWING -> pendingReview++;
                case PENDING_APPROVAL -> reviewed++;
                case COMPLETED -> approved++;
                case REVISION_NEEDED -> revisionNeeded++;
                default -> {
                }
            }
        }

        ResDashboardManagerDTO dto = new ResDashboardManagerDTO();
        dto.setTotalEmployees(records.size());
        dto.setNotStartedCount(notStarted);
        dto.setPendingReviewCount(pendingReview);
        dto.setReviewedCount(reviewed);
        dto.setApprovedCount(approved);
        dto.setRevisionNeededCount(revisionNeeded);
        dto.setRecords(mapper.toResEvaluationRecordSummaryDTOs(records));
        return dto;
    }

    /**
     * Dashboard cho người phê duyệt (quản lý gián tiếp): tổng hợp phiếu mình phụ
     * trách,
     * kèm phân bổ xếp loại của các phiếu đã hoàn tất.
     */
    @Transactional(readOnly = true)
    public ResDashboardApproverDTO getApproverDashboard(Long periodId, User approver) {
        List<EvaluationRecord> records = periodId != null
                ? recordRepo.findByPeriodIdAndIndirectManagerId(periodId, approver.getId())
                : recordRepo.findByIndirectManagerIdOrderByCreatedAtDesc(approver.getId());

        int completed = 0, pendingApproval = 0, revisionNeeded = 0;
        Map<String, Integer> gradeDistribution = new java.util.LinkedHashMap<>();
        for (EvaluationRecord r : records) {
            switch (r.getStatus()) {
                case COMPLETED -> {
                    completed++;
                    String grade = r.getFinalGrade() != null ? r.getFinalGrade() : "Chưa xếp loại";
                    gradeDistribution.merge(grade, 1, Integer::sum);
                }
                case PENDING_APPROVAL -> pendingApproval++;
                case REVISION_NEEDED -> revisionNeeded++;
                default -> {
                }
            }
        }

        ResDashboardApproverDTO dto = new ResDashboardApproverDTO();
        dto.setTotalEmployees(records.size());
        dto.setCompletedCount(completed);
        dto.setPendingApprovalCount(pendingApproval);
        dto.setRevisionNeededCount(revisionNeeded);
        dto.setGradeDistribution(gradeDistribution);
        dto.setRecords(mapper.toResEvaluationRecordSummaryDTOs(records));
        return dto;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TÍNH ĐIỂM — CORE LOGIC
    // ═══════════════════════════════════════════════════════════════════════════

    private EvaluationScore saveOrUpdateScore(EvaluationRecord record, TemplateCriteria criteria,
            ScoredBy scoredBy, Double score) {
        return saveOrUpdateScore(record, criteria, scoredBy, score, null, null, false);
    }

    private EvaluationScore saveOrUpdateScore(EvaluationRecord record, TemplateCriteria criteria,
            ScoredBy scoredBy, Double score, boolean isManual) {
        return saveOrUpdateScore(record, criteria, scoredBy, score, null, null, isManual);
    }

    private EvaluationScore saveOrUpdateScore(EvaluationRecord record, TemplateCriteria criteria,
            ScoredBy scoredBy, Double score, List<TemplateCriteria> allCriteria) {
        return saveOrUpdateScore(record, criteria, scoredBy, score, allCriteria, null, false);
    }

    private EvaluationScore saveOrUpdateScore(EvaluationRecord record, TemplateCriteria criteria,
            ScoredBy scoredBy, Double score, List<TemplateCriteria> allCriteria, List<EvaluationScore> existingScores) {
        return saveOrUpdateScore(record, criteria, scoredBy, score, allCriteria, existingScores, false);
    }

    private EvaluationScore saveOrUpdateScore(EvaluationRecord record, TemplateCriteria criteria,
            ScoredBy scoredBy, Double score, List<TemplateCriteria> allCriteria, List<EvaluationScore> existingScores,
            boolean isManual) {
        return saveOrUpdateScore(record, criteria, scoredBy, score, allCriteria, existingScores, isManual, true);
    }

    private EvaluationScore saveOrUpdateScore(EvaluationRecord record, TemplateCriteria criteria,
            ScoredBy scoredBy, Double score, List<TemplateCriteria> allCriteria, List<EvaluationScore> existingScores,
            boolean isManual, boolean persist) {

        EvaluationScore evalScore = null;
        if (existingScores != null) {
            evalScore = existingScores.stream()
                    .filter(s -> s.getCriteria().getId().equals(criteria.getId()))
                    .findFirst()
                    .orElse(null);
            if (evalScore == null) {
                evalScore = new EvaluationScore();
            }
        } else {
            evalScore = scoreRepo
                    .findByEvaluationRecordIdAndCriteriaIdAndScoredBy(record.getId(), criteria.getId(), scoredBy)
                    .orElse(new EvaluationScore());
        }

        Double oldScore = evalScore.getScore();

        evalScore.setEvaluationRecord(record);
        evalScore.setCriteria(criteria);
        evalScore.setScoredBy(scoredBy);
        evalScore.setScore(score);

        // 3.5: Ghi nhận audit log nếu đổi điểm cũ (khác null và khác điểm mới) và là
        // thao tác thủ công (isManual)
        if (isManual && oldScore != null && !oldScore.equals(score)) {
            String email = vn.system.app.common.util.SecurityUtil.getCurrentUserLogin().orElse(null);
            User performer = email != null ? userRepo.findByEmail(email) : null;

            EvaluationScoreAudit audit = new EvaluationScoreAudit();
            audit.setEvaluationRecord(record);
            audit.setCriteriaId(criteria.getId());
            audit.setScoredBy(scoredBy);
            audit.setOldScore(oldScore);
            audit.setNewScore(score);
            audit.setChangedBy(performer);
            scoreAuditRepo.save(audit);
        }

        // Tính weighted score
        // KHÔNG nhân với sectionWeight vì criteriaWeight đã là trọng số tuyệt đối (theo
        // frontend)
        double criteriaWeight = criteria.getWeight();

        // Nếu là sub-tiêu chí → lấy weight của tiêu chí cha
        if (criteria.getParentCriteria() != null) {
            criteriaWeight = criteria.getParentCriteria().getWeight();
            // Sub-tiêu chí chia đều weight cha (trung bình)
            List<TemplateCriteria> siblings;
            if (allCriteria != null) {
                Long parentId = criteria.getParentCriteria().getId();
                siblings = allCriteria.stream()
                        .filter(c -> c.getParentCriteria() != null && c.getParentCriteria().getId().equals(parentId))
                        .collect(java.util.stream.Collectors.toList());
            } else {
                siblings = criteriaRepo.findByParentCriteriaId(
                        criteria.getParentCriteria().getId());
            }
            // weighted score cho sub = score × parentWeight / numSubs
            evalScore.setWeightedScore(score * criteriaWeight / siblings.size());
        } else {
            evalScore.setWeightedScore(score * criteriaWeight);
        }

        return persist ? scoreRepo.save(evalScore) : evalScore;
    }

    private void recalculateParentScores(EvaluationRecord record, ScoredBy scoredBy) {
        List<Long> sectionIds = record.getTemplate().getSections().stream()
                .map(TemplateSection::getId)
                .collect(java.util.stream.Collectors.toList());
        List<TemplateCriteria> allCriteria = criteriaRepo.findBySectionIdIn(sectionIds);

        // Map parent ID -> list of sub-criteria
        Map<Long, List<TemplateCriteria>> parentToSubs = allCriteria.stream()
                .filter(c -> c.getParentCriteria() != null)
                .collect(java.util.stream.Collectors.groupingBy(c -> c.getParentCriteria().getId()));

        List<EvaluationScore> scores = scoreRepo.findByEvaluationRecordIdAndScoredBy(record.getId(), scoredBy);
        Map<Long, Double> criteriaIdToScore = scores.stream()
                .collect(java.util.stream.Collectors.toMap(
                        s -> s.getCriteria().getId(),
                        EvaluationScore::getScore,
                        (s1, s2) -> s1));

        parentToSubs.forEach((parentId, subs) -> {
            TemplateCriteria parent = allCriteria.stream()
                    .filter(c -> c.getId().equals(parentId))
                    .findFirst()
                    .orElse(null);
            if (parent != null) {
                double avgScore = subs.stream()
                        .map(sub -> criteriaIdToScore.get(sub.getId()))
                        .filter(Objects::nonNull)
                        .mapToDouble(Double::doubleValue)
                        .average()
                        .orElse(0.0);
                saveOrUpdateScore(record, parent, scoredBy, avgScore, allCriteria, scores);
            }
        });
    }

    private double calculateTotalScore(EvaluationRecord record, ScoredBy scoredBy) {
        List<EvaluationScore> scores = scoreRepo.findByEvaluationRecordIdAndScoredBy(record.getId(), scoredBy);
        if (scores.isEmpty()) {
            return 0.0;
        }

        List<Long> sectionIds = record.getTemplate().getSections().stream()
                .map(TemplateSection::getId)
                .collect(java.util.stream.Collectors.toList());
        List<TemplateCriteria> allCriteria = criteriaRepo.findBySectionIdIn(sectionIds);

        Set<Long> parentIds = allCriteria.stream()
                .map(TemplateCriteria::getParentCriteria)
                .filter(Objects::nonNull)
                .map(TemplateCriteria::getId)
                .collect(java.util.stream.Collectors.toSet());

        return scores.stream()
                .filter(s -> !parentIds.contains(s.getCriteria().getId()))
                .mapToDouble(EvaluationScore::getWeightedScore)
                .sum();
    }

    /**
     * Xếp loại theo tổng điểm:
     * < 3.0 → E
     * 3.0–3.5 → D
     * 3.5–4.0 → C
     * 4.0–4.5 → B
     * > 4.5 → A
     */
    private String calculateGrade(double totalScore) {
        if (totalScore > 4.5)
            return "A";
        if (totalScore >= 4.0)
            return "B";
        if (totalScore >= 3.5)
            return "C";
        if (totalScore >= 3.0)
            return "D";
        return "E";
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VALIDATION HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private void validateScore(Double score) {
        if (score == null || !Double.isFinite(score) || score < 1 || score > 5
                || score.doubleValue() != Math.rint(score)) {
            throw new IdInvalidException("Điểm phải là một trong các mức nguyên từ 1 đến 5");
        }
    }

    private void assertCanViewRecord(EvaluationRecord record, User requester) {
        if (!canViewRecord(record, requester)) {
            throw new IdInvalidException("Bạn không có quyền xem bản đánh giá này");
        }
    }

    /**
     * Gác các hành động quản trị (điều chuyển người đánh giá, gia hạn):
     * chỉ admin toàn hệ thống hoặc admin cấp công ty được phép, không dùng quyền
     * XEM.
     */
    private void assertAdminAction(EvaluationRecord record) {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope == null) {
            throw new IdInvalidException("Bạn không có quyền thực hiện hành động này");
        }
        if (scope.isSuperAdmin() || scope.isAdminLevel()) {
            return;
        }
        String employeeId = record.getEmployee() != null ? record.getEmployee().getId() : null;
        if (scope.isCompanyLevel() && employeeId != null
                && scope.companyIds() != null && !scope.companyIds().isEmpty()) {
            for (Long companyId : scope.companyIds()) {
                if (userPositionRepo.findUserIdsByCompanyId(companyId).contains(employeeId)) {
                    return;
                }
            }
        }
        throw new IdInvalidException("Bạn không có quyền thực hiện hành động này");
    }

    private boolean canViewRecord(EvaluationRecord record, User requester) {
        if (record == null || requester == null) {
            return false;
        }

        String requesterId = requester.getId();
        if (record.getEmployee() != null && requesterId.equals(record.getEmployee().getId())) {
            return true;
        }
        if (record.getDirectManager() != null && requesterId.equals(record.getDirectManager().getId())) {
            return true;
        }
        if (record.getIndirectManager() != null && requesterId.equals(record.getIndirectManager().getId())) {
            return true;
        }

        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope == null) {
            return false;
        }
        if (scope.isSuperAdmin() || scope.isAdminLevel()) {
            return true;
        }

        String employeeId = record.getEmployee() != null ? record.getEmployee().getId() : null;
        if (employeeId == null) {
            return false;
        }

        if (scope.companyIds() != null && !scope.companyIds().isEmpty()) {
            for (Long companyId : scope.companyIds()) {
                if (userPositionRepo.findUserIdsByCompanyId(companyId).contains(employeeId)) {
                    return true;
                }
            }
        }

        if (!scope.isCompanyLevel() && scope.departmentIds() != null && !scope.departmentIds().isEmpty()) {
            for (Long departmentId : scope.departmentIds()) {
                if (userPositionRepo.findUserIdsByDepartmentId(departmentId).contains(employeeId)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean canSeeAllEvaluationRecords() {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        return scope != null && (scope.isSuperAdmin() || scope.isAdminLevel());
    }

    private boolean canViewAllEvaluationRecords() {
        if (canSeeAllEvaluationRecords()) {
            return true;
        }

        String email = vn.system.app.common.util.SecurityUtil.getCurrentUserLogin().orElse(null);
        if (email == null || email.isBlank()) {
            return false;
        }

        User user = userRepo.findWithAuthByEmail(email);
        return user != null
                && user.getRole() != null
                && user.getRole().getPermissions() != null
                && user.getRole().getPermissions().stream()
                        .filter(Objects::nonNull)
                        .anyMatch(permission -> "GET".equalsIgnoreCase(permission.getMethod())
                                && "EVALUATION_PERIOD".equals(permission.getModule())
                                && "/api/v1/evaluation/records".equals(permission.getApiPath()));
    }

    private boolean canActAsDirectManager(EvaluationRecord record, User actor) {
        if (record == null || actor == null) {
            return false;
        }
        if (canSeeAllEvaluationRecords()) {
            return true;
        }
        return record.getDirectManager() != null && actor.getId().equals(record.getDirectManager().getId());
    }

    private void assertApprover(EvaluationRecord record, User approver) {
        if (approver != null && canSeeAllEvaluationRecords()) {
            return;
        }
        if (record.getIndirectManager() == null || approver == null
                || !record.getIndirectManager().getId().equals(approver.getId())) {
            throw new IdInvalidException("Bạn không có quyền phê duyệt bản đánh giá này");
        }
    }

    private void assertCriteriaBelongsToRecordTemplate(EvaluationRecord record, TemplateCriteria criteria) {
        Long recordTemplateId = record.getTemplate() != null ? record.getTemplate().getId() : null;
        Long criteriaTemplateId = criteria.getSection() != null
                && criteria.getSection().getTemplate() != null
                        ? criteria.getSection().getTemplate().getId()
                        : null;

        if (recordTemplateId == null || !Objects.equals(recordTemplateId, criteriaTemplateId)) {
            throw new IdInvalidException("Tiêu chí không thuộc mẫu đánh giá của bản đánh giá này");
        }
    }

    private void assertEmployeePhaseOpen(EvaluationRecord record) {
        if (record.getStatus() == RecordStatus.CANCELLED) {
            throw new IdInvalidException("Bản đánh giá đã bị hủy bỏ");
        }
        EvaluationPeriod period = record.getPeriod();
        Instant now = Instant.now();

        if (period == null || period.getStatus() != PeriodStatus.ACTIVE) {
            throw new IdInvalidException("Kỳ đánh giá hiện không mở");
        }
        if (period.getEmployeeStartDate() != null && now.isBefore(period.getEmployeeStartDate())) {
            throw new IdInvalidException("Chưa đến thời gian nhân viên tự đánh giá");
        }
        Instant employeeDeadline = record.getEmployeeDeadlineOverride() != null
                ? record.getEmployeeDeadlineOverride()
                : period.getEmployeeDeadline();
        if (employeeDeadline != null && now.isAfter(employeeDeadline)) {
            throw new IdInvalidException("Đã quá hạn nhân viên tự đánh giá");
        }
    }

    private void assertManagerPhaseOpen(EvaluationRecord record) {
        if (record.getStatus() == RecordStatus.CANCELLED) {
            throw new IdInvalidException("Bản đánh giá đã bị hủy bỏ");
        }
        EvaluationPeriod period = record.getPeriod();
        Instant now = Instant.now();

        if (period == null || period.getStatus() != PeriodStatus.ACTIVE) {
            throw new IdInvalidException("Kỳ đánh giá hiện không mở");
        }
        Instant managerDeadline = record.getManagerDeadlineOverride() != null
                ? record.getManagerDeadlineOverride()
                : period.getManagerDeadline();
        if (managerDeadline != null && now.isAfter(managerDeadline)) {
            throw new IdInvalidException("Đã quá hạn quản lý chấm điểm");
        }
    }

    private void assertApprovalPhaseOpen(EvaluationRecord record) {
        if (record.getStatus() == RecordStatus.CANCELLED) {
            throw new IdInvalidException("Bản đánh giá đã bị hủy bỏ");
        }
        EvaluationPeriod period = record.getPeriod();
        Instant now = Instant.now();

        if (period == null || period.getStatus() != PeriodStatus.ACTIVE) {
            throw new IdInvalidException("Kỳ đánh giá hiện không mở");
        }
        Instant approvalDeadline = record.getApprovalDeadlineOverride() != null
                ? record.getApprovalDeadlineOverride()
                : period.getApprovalDeadline();
        if (approvalDeadline != null && now.isAfter(approvalDeadline)) {
            throw new IdInvalidException("Đã quá hạn phê duyệt đánh giá");
        }
    }

    private Instant getEffectiveEmployeeDeadline(EvaluationRecord record) {
        return record.getEmployeeDeadlineOverride() != null
                ? record.getEmployeeDeadlineOverride()
                : record.getPeriod().getEmployeeDeadline();
    }

    private Instant getEffectiveManagerDeadline(EvaluationRecord record) {
        return record.getManagerDeadlineOverride() != null
                ? record.getManagerDeadlineOverride()
                : record.getPeriod().getManagerDeadline();
    }

    private Instant getEffectiveApprovalDeadline(EvaluationRecord record) {
        return record.getApprovalDeadlineOverride() != null
                ? record.getApprovalDeadlineOverride()
                : record.getPeriod().getApprovalDeadline();
    }

    private String formatInstant(Instant instant) {
        if (instant == null)
            return "";
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        return formatter.format(instant);
    }

    private String getDeadlinePhaseLabel(ExtendRecordDeadlineRequest.Phase phase) {
        if (phase == null)
            return "không xác định";
        return switch (phase) {
            case EMPLOYEE -> "Nhân viên tự đánh giá";
            case MANAGER -> "Quản lý trực tiếp chấm";
            case APPROVAL -> "Quản lý gián tiếp duyệt";
        };
    }

    private void notifyDeadlineExtended(EvaluationRecord record, User recipient, String phaseLabel,
            Instant newDeadline, String actionLink, String reason) {
        if (recipient == null || newDeadline == null)
            return;
        String employeeName = record.getEmployee() != null ? record.getEmployee().getName() : "nhân viên";
        String reasonText = reason != null && !reason.isBlank()
                ? String.format(" Lý do: %s", reason.trim())
                : "";
        sendNotification(recipient, "DEADLINE_EXTENDED",
                String.format("Hạn xử lý bước %s cho bản đánh giá của %s đã được gia hạn đến %s.%s",
                        phaseLabel, employeeName, formatInstant(newDeadline), reasonText),
                actionLink);
    }

    private void extendEmployeeDeadline(EvaluationRecord record, Instant deadline, boolean cascade) {
        if (record.getEmployeeSubmittedAt() != null
                || (record.getStatus() != RecordStatus.NOT_STARTED
                        && record.getStatus() != RecordStatus.EMPLOYEE_DRAFTING)) {
            throw new IdInvalidException(
                    "Chỉ có thể gia hạn nhân viên khi bản đánh giá còn ở bước nhân viên tự đánh giá");
        }

        if (cascade) {
            Instant oldEmployeeDeadline = getEffectiveEmployeeDeadline(record);
            if (oldEmployeeDeadline != null && deadline.isAfter(oldEmployeeDeadline)) {
                java.time.Duration diff = java.time.Duration.between(oldEmployeeDeadline, deadline);

                // Tự động tịnh tiến hạn của Quản lý
                Instant oldManagerDeadline = getEffectiveManagerDeadline(record);
                if (oldManagerDeadline != null) {
                    record.setManagerDeadlineOverride(oldManagerDeadline.plus(diff));
                }

                // Tự động tịnh tiến hạn của Người duyệt
                Instant oldApprovalDeadline = getEffectiveApprovalDeadline(record);
                if (oldApprovalDeadline != null) {
                    record.setApprovalDeadlineOverride(oldApprovalDeadline.plus(diff));
                }
            }
        }

        record.setEmployeeDeadlineOverride(deadline);

        // Kiểm tra tính hợp lệ về mặt logic sau khi tịnh tiến
        Instant finalEmployee = getEffectiveEmployeeDeadline(record);
        Instant finalManager = getEffectiveManagerDeadline(record);
        if (finalEmployee != null && finalManager != null && finalEmployee.isAfter(finalManager)) {
            throw new IdInvalidException(String.format(
                    "Lỗi logic: Hạn mới của Nhân viên (%s) không được trễ hơn hạn của Quản lý (%s). Vui lòng tích chọn 'Tự động tịnh tiến các hạn chót tiếp theo'.",
                    formatInstant(finalEmployee), formatInstant(finalManager)));
        }
    }

    private void extendManagerDeadline(EvaluationRecord record, Instant deadline, boolean cascade) {
        if (record.getManagerSubmittedAt() != null
                || (record.getStatus() != RecordStatus.PENDING_MANAGER_REVIEW
                        && record.getStatus() != RecordStatus.MANAGER_REVIEWING
                        && record.getStatus() != RecordStatus.REVISION_NEEDED)) {
            throw new IdInvalidException("Chỉ có thể gia hạn quản lý khi bản đánh giá đang ở bước quản lý chấm/sửa");
        }

        if (cascade) {
            Instant oldManagerDeadline = getEffectiveManagerDeadline(record);
            if (oldManagerDeadline != null && deadline.isAfter(oldManagerDeadline)) {
                java.time.Duration diff = java.time.Duration.between(oldManagerDeadline, deadline);

                // Tự động tịnh tiến hạn của Người duyệt
                Instant oldApprovalDeadline = getEffectiveApprovalDeadline(record);
                if (oldApprovalDeadline != null) {
                    record.setApprovalDeadlineOverride(oldApprovalDeadline.plus(diff));
                }
            }
        }

        record.setManagerDeadlineOverride(deadline);

        // Kiểm tra tính hợp lệ về mặt logic sau khi tịnh tiến
        Instant finalEmployee = getEffectiveEmployeeDeadline(record);
        Instant finalManager = getEffectiveManagerDeadline(record);
        Instant finalApproval = getEffectiveApprovalDeadline(record);

        if (finalEmployee != null && finalManager != null && finalManager.isBefore(finalEmployee)) {
            throw new IdInvalidException(String.format(
                    "Lỗi logic: Hạn mới của Quản lý (%s) không được sớm hơn hạn của Nhân viên (%s).",
                    formatInstant(finalManager), formatInstant(finalEmployee)));
        }

        if (finalManager != null && finalApproval != null && finalManager.isAfter(finalApproval)) {
            throw new IdInvalidException(String.format(
                    "Lỗi logic: Hạn mới của Quản lý (%s) không được trễ hơn hạn của Người duyệt (%s). Vui lòng tích chọn 'Tự động tịnh tiến các hạn chót tiếp theo'.",
                    formatInstant(finalManager), formatInstant(finalApproval)));
        }
    }

    private void extendApprovalDeadline(EvaluationRecord record, Instant deadline) {
        if (record.getApprovedAt() != null || record.getStatus() != RecordStatus.PENDING_APPROVAL) {
            throw new IdInvalidException("Chỉ có thể gia hạn phê duyệt khi bản đánh giá đang chờ phê duyệt");
        }

        record.setApprovalDeadlineOverride(deadline);

        // Kiểm tra tính hợp lệ về mặt logic sau khi tịnh tiến
        Instant finalManager = getEffectiveManagerDeadline(record);
        Instant finalApproval = getEffectiveApprovalDeadline(record);

        if (finalManager != null && finalApproval != null && finalApproval.isBefore(finalManager)) {
            throw new IdInvalidException(String.format(
                    "Lỗi logic: Hạn mới của Người duyệt (%s) không được sớm hơn hạn của Quản lý (%s).",
                    formatInstant(finalApproval), formatInstant(finalManager)));
        }
    }

    private void applyCustomPhaseDeadlines(
            EvaluationRecord record,
            ExtendRecordDeadlineRequest.Phase currentPhase,
            Map<ExtendRecordDeadlineRequest.Phase, Instant> phaseDeadlines) {
        for (Map.Entry<ExtendRecordDeadlineRequest.Phase, Instant> entry : phaseDeadlines.entrySet()) {
            if (entry.getKey() == currentPhase) {
                continue;
            }
            Instant targetDeadline = entry.getValue();
            if (targetDeadline == null || !targetDeadline.isAfter(Instant.now())) {
                throw new IdInvalidException("Hạn mới theo từng mốc phải lớn hơn thời điểm hiện tại");
            }
            Instant currentDeadline = switch (entry.getKey()) {
                case EMPLOYEE -> getEffectiveEmployeeDeadline(record);
                case MANAGER -> getEffectiveManagerDeadline(record);
                case APPROVAL -> getEffectiveApprovalDeadline(record);
            };
            if (currentDeadline != null && !targetDeadline.isAfter(currentDeadline)) {
                throw new IdInvalidException("Hạn mới theo từng mốc phải lớn hơn hạn hiện tại của mốc đó");
            }
            switch (entry.getKey()) {
                case EMPLOYEE -> {
                    if (currentPhase != ExtendRecordDeadlineRequest.Phase.EMPLOYEE) {
                        throw new IdInvalidException(
                                "Không thể chỉnh hạn nhân viên khi hồ sơ đã qua bước nhân viên tự đánh giá");
                    }
                    record.setEmployeeDeadlineOverride(targetDeadline);
                }
                case MANAGER -> record.setManagerDeadlineOverride(targetDeadline);
                case APPROVAL -> record.setApprovalDeadlineOverride(targetDeadline);
            }
        }

        Instant finalEmployee = getEffectiveEmployeeDeadline(record);
        Instant finalManager = getEffectiveManagerDeadline(record);
        Instant finalApproval = getEffectiveApprovalDeadline(record);

        if (finalEmployee != null && finalManager != null && finalManager.isBefore(finalEmployee)) {
            throw new IdInvalidException(String.format(
                    "Lỗi logic: Hạn của Quản lý (%s) không được sớm hơn hạn của Nhân viên (%s).",
                    formatInstant(finalManager), formatInstant(finalEmployee)));
        }
        if (finalManager != null && finalApproval != null && finalApproval.isBefore(finalManager)) {
            throw new IdInvalidException(String.format(
                    "Lỗi logic: Hạn của Người duyệt (%s) không được sớm hơn hạn của Quản lý (%s).",
                    formatInstant(finalApproval), formatInstant(finalManager)));
        }
    }

    /**
     * Validate tất cả tiêu chí lá (không có sub) đã được chấm điểm.
     */
    private void validateAllLeafCriteriaScored(EvaluationRecord record, ScoredBy scoredBy) {
        EvaluationTemplate template = record.getTemplate();
        List<Long> sectionIds = template.getSections().stream()
                .map(TemplateSection::getId)
                .collect(java.util.stream.Collectors.toList());
        List<TemplateCriteria> allCriteria = criteriaRepo.findBySectionIdIn(sectionIds);

        Set<Long> parentIds = allCriteria.stream()
                .map(TemplateCriteria::getParentCriteria)
                .filter(Objects::nonNull)
                .map(TemplateCriteria::getId)
                .collect(java.util.stream.Collectors.toSet());

        List<TemplateCriteria> leafCriteria = allCriteria.stream()
                .filter(c -> !parentIds.contains(c.getId()))
                .collect(java.util.stream.Collectors.toList());

        List<EvaluationScore> scores = scoreRepo.findByEvaluationRecordIdAndScoredBy(record.getId(), scoredBy);
        Set<Long> scoredCriteriaIds = scores.stream()
                .map(s -> s.getCriteria().getId())
                .collect(java.util.stream.Collectors.toSet());

        for (TemplateCriteria criteria : leafCriteria) {
            if (!scoredCriteriaIds.contains(criteria.getId())) {
                throw new IdInvalidException(
                        String.format("Chưa chấm điểm tiêu chí: %s", criteria.getName()));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER: GHI LỊCH SỬ & GỬI THÔNG BÁO
    // ═══════════════════════════════════════════════════════════════════════════

    private void saveHistory(EvaluationRecord record, RecordStatus from, RecordStatus to,
            User performer, String note) {
        EvaluationHistory history = new EvaluationHistory();
        history.setEvaluationRecord(record);
        history.setFromStatus(from);
        history.setToStatus(to);
        history.setPerformedBy(performer);
        history.setNote(note);
        historyRepo.save(history);
    }

    private void sendNotification(User recipient, String type, String content, String actionLink) {
        if (recipient == null)
            return;
        eventPublisher.publishEvent(new vn.system.app.modules.notification.event.AppNotificationEvent(
                List.of(recipient.getId()), "EVALUATION", type, content, actionLink));
    }

    private String managerDrawerLink(Long recordId) {
        return "/admin/evaluation/process?tab=PENDING_EVAL&recordId=" + recordId + "&detailRole=MANAGER";
    }

    private String approverDrawerLink(Long recordId) {
        return "/admin/evaluation/process?tab=PENDING_EVAL&recordId=" + recordId + "&detailRole=APPROVER";
    }
}
