package vn.system.app.modules.evaluation.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.common.util.error.PermissionException;
import vn.system.app.modules.evaluation.domain.*;
import vn.system.app.modules.evaluation.domain.enums.*;
import vn.system.app.modules.evaluation.repository.*;
import vn.system.app.modules.notification.service.NotificationService;
import vn.system.app.modules.user.domain.User;
import vn.system.app.modules.user.repository.UserRepository;
import vn.system.app.modules.company.domain.Company;
import vn.system.app.modules.company.repository.CompanyRepository;
import vn.system.app.modules.userposition.domain.UserPosition;
import vn.system.app.modules.userposition.repository.UserPositionRepository;
import vn.system.app.common.util.UserScopeContext;
import vn.system.app.modules.evaluation.domain.response.ResPeriodProgressDTO;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service quản lý Kỳ đánh giá (Period) và nhân viên tham gia kỳ.
 * Giai đoạn 0: Admin chuẩn bị kỳ + kích hoạt.
 */
@Service
public class EvaluationPeriodService {

    private final EvaluationPeriodRepository periodRepo;
    private final PeriodTemplateRepository periodTemplateRepo;
    private final PeriodEmployeeRepository periodEmployeeRepo;
    private final EvaluationTemplateRepository templateRepo;
    private final EvaluationRecordRepository recordRepo;
    private final EvaluationHistoryRepository historyRepo;
    private final NotificationService notificationService;
    private final UserRepository userRepo;
    private final CompanyRepository companyRepo;
    private final UserPositionRepository userPositionRepo;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final EvaluationTemplateValidator templateValidator;

    public EvaluationPeriodService(
            EvaluationPeriodRepository periodRepo,
            PeriodTemplateRepository periodTemplateRepo,
            PeriodEmployeeRepository periodEmployeeRepo,
            EvaluationTemplateRepository templateRepo,
            EvaluationRecordRepository recordRepo,
            EvaluationHistoryRepository historyRepo,
            NotificationService notificationService,
            UserRepository userRepo,
            CompanyRepository companyRepo,
            UserPositionRepository userPositionRepo,
            org.springframework.context.ApplicationEventPublisher eventPublisher,
            EvaluationTemplateValidator templateValidator) {
        this.periodRepo = periodRepo;
        this.periodTemplateRepo = periodTemplateRepo;
        this.periodEmployeeRepo = periodEmployeeRepo;
        this.templateRepo = templateRepo;
        this.recordRepo = recordRepo;
        this.historyRepo = historyRepo;
        this.notificationService = notificationService;
        this.userRepo = userRepo;
        this.companyRepo = companyRepo;
        this.userPositionRepo = userPositionRepo;
        this.eventPublisher = eventPublisher;
        this.templateValidator = templateValidator;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PERIOD CRUD
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional
    public EvaluationPeriod createPeriod(EvaluationPeriod period) {
        normalizePeriodText(period);
        validatePeriodDates(period, true);
        period.setStatus(PeriodStatus.DRAFT);
        
        // Gắn Company bắt buộc
        if (period.getCompany() == null || period.getCompany().getId() == 0) {
            throw new IdInvalidException("Vui lòng chọn Công ty áp dụng cho kỳ đánh giá");
        }
        Company comp = companyRepo.findById(period.getCompany().getId())
                .orElseThrow(() -> new IdInvalidException("Công ty không tồn tại"));
        period.setCompany(comp);
        assertPeriodInCurrentScope(period);
        
        return periodRepo.save(period);
    }

    @Transactional
    public EvaluationPeriod updatePeriod(Long id, EvaluationPeriod updates) {
        EvaluationPeriod existing = fetchPeriodById(id);
        checkPeriodEditable(existing);
        normalizePeriodText(updates);

        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
        boolean employeeStartDateChanged = updates.getEmployeeStartDate() != null
                && !updates.getEmployeeStartDate().equals(existing.getEmployeeStartDate());
        if (updates.getEmployeeStartDate() != null) existing.setEmployeeStartDate(updates.getEmployeeStartDate());
        if (updates.getEmployeeDeadline() != null) existing.setEmployeeDeadline(updates.getEmployeeDeadline());
        if (updates.getManagerDeadline() != null) existing.setManagerDeadline(updates.getManagerDeadline());
        if (updates.getApprovalDeadline() != null) existing.setApprovalDeadline(updates.getApprovalDeadline());

        if (updates.getCompany() != null && updates.getCompany().getId() > 0) {
            Company comp = companyRepo.findById(updates.getCompany().getId())
                    .orElseThrow(() -> new IdInvalidException("Công ty không tồn tại"));
            existing.setCompany(comp);
        }

        assertPeriodInCurrentScope(existing);

        validatePeriodDates(existing, employeeStartDateChanged);
        return periodRepo.save(existing);
    }

    public EvaluationPeriod fetchPeriodById(Long id) {
        EvaluationPeriod period = periodRepo.findById(id)
                .orElseThrow(() -> new IdInvalidException("Kỳ đánh giá không tồn tại"));
        assertPeriodInCurrentScope(period);
        return period;
    }

    public ResultPaginationDTO fetchAllPeriods(Specification<EvaluationPeriod> spec, Pageable pageable) {
        Page<EvaluationPeriod> page = periodRepo.findAll(spec, pageable);

        ResultPaginationDTO rs = new ResultPaginationDTO();
        ResultPaginationDTO.Meta meta = new ResultPaginationDTO.Meta();
        meta.setPage(pageable.getPageNumber() + 1);
        meta.setPageSize(pageable.getPageSize());
        meta.setPages(page.getTotalPages());
        meta.setTotal(page.getTotalElements());
        rs.setMeta(meta);
        rs.setResult(page.getContent());
        return rs;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PERIOD TEMPLATE (liên kết template cho kỳ)
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional
    public PeriodTemplate addTemplateToPeriod(Long periodId, Long templateId) {
        EvaluationPeriod period = fetchPeriodById(periodId);
        checkPeriodEditable(period);

        EvaluationTemplate template = templateRepo.findById(templateId)
                .orElseThrow(() -> new IdInvalidException("Template không tồn tại"));

        if (template.getStatus() != TemplateStatus.ACTIVE) {
            throw new IdInvalidException("Chỉ có thể dùng template đã ACTIVE");
        }
        templateValidator.validateReadyForUse(template);

        if (template.getCompany() == null
                || !Objects.equals(template.getCompany().getId(), period.getCompany().getId())) {
            throw new IdInvalidException("Template không thuộc công ty của kỳ đánh giá này");
        }

        if (periodTemplateRepo.existsByPeriodIdAndTemplateId(periodId, templateId)) {
            throw new IdInvalidException("Template đã được gắn vào kỳ đánh giá này");
        }

        PeriodTemplate pt = new PeriodTemplate();
        pt.setPeriod(period);
        pt.setTemplate(template);
        pt.setApplyToRole(template.getType());

        return periodTemplateRepo.save(pt);
    }

    public List<PeriodTemplate> fetchTemplatesByPeriod(Long periodId) {
        fetchPeriodById(periodId);
        return periodTemplateRepo.findByPeriodId(periodId);
    }

    /**
     * Gỡ liên kết mẫu khỏi một kỳ nháp. Mẫu gốc vẫn được giữ nguyên để có thể
     * sử dụng ở các kỳ khác. Quản trị viên phải gỡ toàn bộ nhân sự đang áp dụng
     * mẫu trước để tránh tạo cấu hình kỳ không nhất quán.
     */
    @Transactional
    public void removeTemplateFromPeriod(Long periodId, Long templateId) {
        EvaluationPeriod period = fetchPeriodById(periodId);
        checkPeriodEditable(period);

        PeriodTemplate periodTemplate = periodTemplateRepo
                .findByPeriodIdAndTemplateId(periodId, templateId)
                .orElseThrow(() -> new IdInvalidException("Mẫu không được gắn trong kỳ đánh giá này"));

        if (periodEmployeeRepo.existsByPeriodIdAndTemplateIdAndStatus(
                periodId, templateId, PeriodEmployeeStatus.ACTIVE)) {
            throw new IdInvalidException(
                    "Mẫu đang có nhân sự tham gia. Vui lòng gỡ toàn bộ nhân sự khỏi mẫu trước khi gỡ mẫu");
        }

        periodTemplateRepo.delete(periodTemplate);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PERIOD EMPLOYEES (add nhân viên vào kỳ)
    // ═══════════════════════════════════════════════════════════════════════════

    @Transactional
    public PeriodEmployee addEmployeeToPeriod(Long periodId, String employeeId,
            String directManagerId, Long templateId) {

        EvaluationPeriod period = fetchPeriodById(periodId);
        checkPeriodEditable(period);

        User employee = userRepo.findById(employeeId)
                .orElseThrow(() -> new IdInvalidException("Nhân viên không tồn tại"));
        if (!employee.isActive()) {
            throw new IdInvalidException("Không thể thêm nhân viên đã bị vô hiệu hóa");
        }
        if (!userBelongsToCompany(employeeId, period.getCompany().getId())) {
            throw new IdInvalidException("Nhân viên không thuộc công ty của kỳ đánh giá này");
        }
        if (employee.getDirectManager() == null) {
            throw new IdInvalidException("Nhân viên " + employee.getName() + " chưa được cấu hình Quản lý trực tiếp trong Hồ sơ nhân sự.");
        }
        
        User directManager = userRepo.findById(directManagerId)
                .orElseThrow(() -> new IdInvalidException("Quản lý trực tiếp không tồn tại"));
        if (!directManager.isActive()) {
            throw new IdInvalidException("Không thể chọn quản lý trực tiếp đã bị vô hiệu hóa");
        }
        if (Objects.equals(employeeId, directManagerId)) {
            throw new IdInvalidException("Nhân viên không thể đồng thời là quản lý trực tiếp của chính mình");
        }
        if (!userBelongsToCompany(directManagerId, period.getCompany().getId())) {
            throw new IdInvalidException("Quản lý trực tiếp không thuộc công ty của kỳ đánh giá này");
        }
                
        // Tự động xác định quản lý gián tiếp là cấp trên của quản lý trực tiếp
        User indirectManager = directManager.getDirectManager();
        if (indirectManager == null) {
            throw new IdInvalidException("Quản lý trực tiếp chưa được thiết lập cấp trên, không thể xác định quản lý gián tiếp.");
        }
        if (!indirectManager.isActive()) {
            throw new IdInvalidException("Quản lý gián tiếp đã bị vô hiệu hóa, vui lòng cập nhật lại tuyến quản lý");
        }
        if (Objects.equals(indirectManager.getId(), employeeId) || Objects.equals(indirectManager.getId(), directManagerId)) {
            throw new IdInvalidException("Tuyến quản lý không hợp lệ: người đánh giá và người phê duyệt phải là các tài khoản khác nhau");
        }
        if (!userBelongsToCompany(indirectManager.getId(), period.getCompany().getId())) {
            throw new IdInvalidException("Quản lý gián tiếp không thuộc công ty của kỳ đánh giá này");
        }
        EvaluationTemplate template = templateRepo.findById(templateId)
                .orElseThrow(() -> new IdInvalidException("Template không tồn tại"));

        if (template.getStatus() != TemplateStatus.ACTIVE) {
            throw new IdInvalidException("Chỉ có thể dùng template đã ACTIVE");
        }
        templateValidator.validateReadyForUse(template);

        if (template.getCompany() == null
                || !Objects.equals(template.getCompany().getId(), period.getCompany().getId())) {
            throw new IdInvalidException("Template không thuộc công ty của kỳ đánh giá này");
        }

        if (!periodTemplateRepo.existsByPeriodIdAndTemplateId(periodId, templateId)) {
            throw new IdInvalidException("Template chưa được gắn vào kỳ đánh giá này");
        }

        // Validate Template Target Job Titles
        if (template.getTargetJobTitles() != null && !template.getTargetJobTitles().isEmpty()) {
            boolean hasMatchingJobTitle = false;
            List<UserPosition> positions = userPositionRepo.findActiveFullByUserId(employeeId);
            for (UserPosition p : positions) {
                Long empJobTitleId = null;
                if ("COMPANY".equals(p.getSource()) && p.getCompanyJobTitle() != null) {
                    empJobTitleId = p.getCompanyJobTitle().getJobTitle().getId();
                } else if ("DEPARTMENT".equals(p.getSource()) && p.getDepartmentJobTitle() != null) {
                    empJobTitleId = p.getDepartmentJobTitle().getJobTitle().getId();
                } else if ("SECTION".equals(p.getSource()) && p.getSectionJobTitle() != null) {
                    empJobTitleId = p.getSectionJobTitle().getJobTitle().getId();
                }
                
                if (empJobTitleId != null) {
                    final Long finalJobTitleId = empJobTitleId;
                    if (template.getTargetJobTitles().stream().anyMatch(jt -> jt.getId().equals(finalJobTitleId))) {
                        hasMatchingJobTitle = true;
                        break;
                    }
                }
            }
            if (!hasMatchingJobTitle) {
                throw new IdInvalidException("Chức danh của nhân viên không nằm trong danh sách chức danh áp dụng của Mẫu đánh giá này");
            }
        }

        Optional<PeriodEmployee> existingPeriodEmployee = periodEmployeeRepo.findByPeriodIdAndEmployeeId(periodId, employeeId);
        if (existingPeriodEmployee.isPresent() && existingPeriodEmployee.get().getStatus() == PeriodEmployeeStatus.ACTIVE) {
            throw new IdInvalidException("Nhân viên đã tồn tại trong kỳ đánh giá này");
        }

        PeriodEmployee pe = existingPeriodEmployee.orElseGet(PeriodEmployee::new);
        pe.setPeriod(period);
        pe.setEmployee(employee);
        pe.setDirectManager(directManager);
        pe.setIndirectManager(indirectManager);
        pe.setTemplate(template);
        pe.setStatus(PeriodEmployeeStatus.ACTIVE);
        return periodEmployeeRepo.save(pe);
    }

    /** Hủy bản đánh giá khi nhân viên nghỉ việc — KHÔNG xóa dữ liệu */
    @Transactional
    public PeriodEmployee cancelEmployee(Long periodEmployeeId) {
        PeriodEmployee pe = periodEmployeeRepo.findById(periodEmployeeId)
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy nhân viên trong kỳ"));

        if (pe.getPeriod() == null) {
            throw new IdInvalidException("Bản ghi nhân sự không có kỳ đánh giá hợp lệ");
        }
        assertPeriodInCurrentScope(pe.getPeriod());
        if (pe.getPeriod().getStatus() == PeriodStatus.CLOSED) {
            throw new IdInvalidException("Kỳ đánh giá đã đóng, không thể hủy gán nhân sự");
        }
        if (pe.getStatus() == PeriodEmployeeStatus.CANCELLED) {
            throw new IdInvalidException("Nhân sự đã được hủy gán khỏi kỳ đánh giá");
        }
        recordRepo.findByPeriodIdAndEmployeeId(pe.getPeriod().getId(), pe.getEmployee().getId())
                .ifPresent(record -> {
                    if (record.getStatus() == RecordStatus.COMPLETED) {
                        throw new IdInvalidException("Bản đánh giá đã hoàn tất, không thể hủy gán nhân sự");
                    }
                });

        pe.setStatus(PeriodEmployeeStatus.CANCELLED);
        PeriodEmployee saved = periodEmployeeRepo.save(pe);

        // 3.1: Hủy bản đánh giá EvaluationRecord tương ứng nếu chưa hoàn thành (COMPLETED)
        if (pe.getPeriod() != null && pe.getEmployee() != null) {
            recordRepo.findByPeriodIdAndEmployeeId(pe.getPeriod().getId(), pe.getEmployee().getId())
                    .ifPresent(record -> {
                        if (record.getStatus() != RecordStatus.COMPLETED && record.getStatus() != RecordStatus.CANCELLED) {
                            RecordStatus oldStatus = record.getStatus();
                            record.setStatus(RecordStatus.CANCELLED);
                            recordRepo.save(record);

                            // Ghi nhận lịch sử thay đổi trạng thái
                            String email = vn.system.app.common.util.SecurityUtil.getCurrentUserLogin().orElse(null);
                            User actor = email != null ? userRepo.findByEmail(email) : null;

                            EvaluationHistory history = new EvaluationHistory();
                            history.setEvaluationRecord(record);
                            history.setFromStatus(oldStatus);
                            history.setToStatus(RecordStatus.CANCELLED);
                            history.setPerformedBy(actor);
                            history.setNote("Nhân viên nghỉ việc / bị loại khỏi kỳ đánh giá");
                            historyRepo.save(history);
                        }
                    });
        }
        return saved;
    }

    public List<PeriodEmployee> fetchEmployeesByPeriod(Long periodId) {
        fetchPeriodById(periodId);
        return periodEmployeeRepo.findByPeriodId(periodId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // KÍCH HOẠT KỲ ĐÁNH GIÁ
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Admin kích hoạt kỳ: DRAFT → ACTIVE.
     * Sinh evaluation_record cho từng nhân viên ACTIVE trong kỳ.
     * Gửi thông báo đến tất cả nhân viên.
     */
    @Transactional
    public EvaluationPeriod activatePeriod(Long periodId) {
        EvaluationPeriod period = fetchPeriodById(periodId);

        if (period.getStatus() != PeriodStatus.DRAFT) {
            throw new IdInvalidException("Chỉ có thể kích hoạt kỳ ở trạng thái DRAFT");
        }

        // Validate kỳ có nhân viên
        List<PeriodEmployee> employees = periodEmployeeRepo.findByPeriodIdAndStatus(
                periodId, PeriodEmployeeStatus.ACTIVE);
        if (employees.isEmpty()) {
            throw new IdInvalidException("Kỳ đánh giá chưa có nhân viên nào");
        }

        // Validate kỳ có template
        List<PeriodTemplate> templates = periodTemplateRepo.findByPeriodId(periodId);
        if (templates.isEmpty()) {
            throw new IdInvalidException("Kỳ đánh giá chưa gắn template nào");
        }
        Set<Long> validatedTemplateIds = new HashSet<>();
        for (PeriodTemplate periodTemplate : templates) {
            EvaluationTemplate template = periodTemplate.getTemplate();
            templateValidator.validateReadyForUse(template);
            validatedTemplateIds.add(template.getId());
        }

        // Validate deadlines
        if (period.getEmployeeStartDate() == null || period.getEmployeeDeadline() == null
                || period.getManagerDeadline() == null || period.getApprovalDeadline() == null) {
            throw new IdInvalidException("Vui lòng thiết lập đầy đủ các mốc deadline trước khi kích hoạt");
        }

        Instant now = Instant.now();
        if (!now.isBefore(period.getEmployeeDeadline())) {
            throw new IdInvalidException(
                    "Không thể kích hoạt kỳ vì hạn nhân viên nộp đã qua. Vui lòng cập nhật lại các mốc thời gian.");
        }
        boolean employeePhaseStarted = !now.isBefore(period.getEmployeeStartDate());

        // Sinh evaluation_record cho từng nhân viên
        List<EvaluationRecord> newRecords = new ArrayList<>(employees.size());
        for (PeriodEmployee pe : employees) {
            if (pe.getTemplate() == null || !validatedTemplateIds.contains(pe.getTemplate().getId())) {
                throw new IdInvalidException(String.format(
                        "Mẫu đánh giá của nhân viên %s không còn được gắn với kỳ đánh giá",
                        pe.getEmployee().getName()));
            }
            EvaluationRecord record = new EvaluationRecord();
            record.setPeriod(period);
            record.setEmployee(pe.getEmployee());
            record.setDirectManager(pe.getDirectManager());
            record.setIndirectManager(pe.getIndirectManager());
            record.setTemplate(pe.getTemplate());
            record.setStatus(employeePhaseStarted ? RecordStatus.EMPLOYEE_DRAFTING : RecordStatus.NOT_STARTED);
            newRecords.add(record);
        }
        recordRepo.saveAll(newRecords);

        String formattedStartDate = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(period.getEmployeeStartDate());
        for (PeriodEmployee pe : employees) {
            if (employeePhaseStarted) {
                sendNotification(pe.getEmployee(), "PERIOD_OPENED", String.format("Kỳ đánh giá \"%s\" đã mở. Vui lòng hoàn thành tự đánh giá trước hạn chót.", period.getName()), "/admin/evaluation/my-records");
            } else {
                sendNotification(pe.getEmployee(), "PERIOD_UPCOMING", String.format("Kỳ đánh giá \"%s\" sắp diễn ra. Cổng tự đánh giá sẽ mở vào lúc %s.", period.getName(), formattedStartDate), "/admin/evaluation/my-records");
            }
        }

        period.setStatus(PeriodStatus.ACTIVE);
        return periodRepo.save(period);
    }

    /** Đóng kỳ đánh giá: ACTIVE → CLOSED */
    @Transactional
    public EvaluationPeriod closePeriod(Long periodId) {
        EvaluationPeriod period = fetchPeriodById(periodId);

        if (period.getStatus() != PeriodStatus.ACTIVE) {
            throw new IdInvalidException("Chỉ có thể đóng kỳ ở trạng thái ACTIVE");
        }

        // 3.3: Chặn đóng kỳ nếu vẫn còn bản đánh giá chưa kết thúc (COMPLETED / CANCELLED)
        long countUnfinished = recordRepo.countByPeriodIdAndStatusNotIn(periodId, List.of(RecordStatus.COMPLETED, RecordStatus.CANCELLED));
        if (countUnfinished > 0) {
            throw new IdInvalidException(String.format("Còn %d bản đánh giá chưa hoàn tất, vui lòng xử lý trước khi đóng kỳ", countUnfinished));
        }

        period.setStatus(PeriodStatus.CLOSED);
        return periodRepo.save(period);
    }

    /** Danh sách bản đánh giá chưa xong trong kỳ */
    public List<EvaluationRecord> getUnfinishedRecords(Long periodId) {
        return recordRepo.findByPeriodIdAndStatusNotIn(periodId, List.of(RecordStatus.COMPLETED, RecordStatus.CANCELLED));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // VALIDATION
    // ═══════════════════════════════════════════════════════════════════════════

    public ResPeriodProgressDTO getPeriodProgress(Long periodId) {
        EvaluationPeriod period = fetchPeriodById(periodId);

        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope == null) {
            throw new IdInvalidException("Không tìm thấy thông tin phiên làm việc");
        }

        List<EvaluationRecord> allRecords = recordRepo.findByPeriodIdOrderByCreatedAtDesc(periodId);
        List<String> empIds = allRecords.stream().map(r -> r.getEmployee().getId()).distinct().toList();
        List<UserPosition> positions = userPositionRepo.findActiveFullByUserIds(empIds);

        Map<String, Set<Long>> empCompanies = new java.util.HashMap<>();
        Map<String, Set<Long>> empDepartments = new java.util.HashMap<>();
        Map<String, UserPosition> empPrimaryPosition = new java.util.HashMap<>();

        for (UserPosition up : positions) {
            String uId = up.getUser().getId();
            empCompanies.computeIfAbsent(uId, k -> new java.util.HashSet<>());
            empDepartments.computeIfAbsent(uId, k -> new java.util.HashSet<>());

            Long cId = null;
            if (up.getCompanyJobTitle() != null && up.getCompanyJobTitle().getCompany() != null) {
                cId = up.getCompanyJobTitle().getCompany().getId();
            } else if (up.getDepartmentJobTitle() != null && up.getDepartmentJobTitle().getDepartment() != null
                    && up.getDepartmentJobTitle().getDepartment().getCompany() != null) {
                cId = up.getDepartmentJobTitle().getDepartment().getCompany().getId();
            } else if (up.getSectionJobTitle() != null && up.getSectionJobTitle().getSection() != null
                    && up.getSectionJobTitle().getSection().getDepartment() != null
                    && up.getSectionJobTitle().getSection().getDepartment().getCompany() != null) {
                cId = up.getSectionJobTitle().getSection().getDepartment().getCompany().getId();
            }
            if (cId != null) {
                empCompanies.get(uId).add(cId);
            }

            Long dId = null;
            if (up.getDepartmentJobTitle() != null && up.getDepartmentJobTitle().getDepartment() != null) {
                dId = up.getDepartmentJobTitle().getDepartment().getId();
                empPrimaryPosition.putIfAbsent(uId, up);
            } else if (up.getSectionJobTitle() != null && up.getSectionJobTitle().getSection() != null
                    && up.getSectionJobTitle().getSection().getDepartment() != null) {
                dId = up.getSectionJobTitle().getSection().getDepartment().getId();
                empPrimaryPosition.putIfAbsent(uId, up);
            }
            if (dId != null) {
                empDepartments.get(uId).add(dId);
            }
        }

        List<EvaluationRecord> filteredRecords = allRecords.stream()
                .filter(r -> {
                    if (scope.isSuperAdmin() || scope.isAdminLevel()) {
                        return true;
                    }
                    String empId = r.getEmployee().getId();
                    if (scope.isCompanyLevel()) {
                        Set<Long> cIds = empCompanies.getOrDefault(empId, Set.of());
                        return cIds.stream().anyMatch(cid -> scope.companyIds().contains(cid));
                    }
                    if (scope.isDepartmentLevel()) {
                        Set<Long> dIds = empDepartments.getOrDefault(empId, Set.of());
                        return dIds.stream().anyMatch(did -> scope.departmentIds().contains(did));
                    }
                    return false;
                })
                .toList();

        Instant now = Instant.now();
        int overdueCount = 0;

        for (EvaluationRecord r : filteredRecords) {
            RecordStatus st = r.getStatus();
            if (st == RecordStatus.COMPLETED || st == RecordStatus.CANCELLED || st == RecordStatus.NOT_STARTED) {
                continue;
            }
            Instant deadline = null;
            if (st == RecordStatus.EMPLOYEE_DRAFTING) {
                deadline = r.getEmployeeDeadlineOverride() != null ? r.getEmployeeDeadlineOverride() : period.getEmployeeDeadline();
            } else if (st == RecordStatus.PENDING_MANAGER_REVIEW || st == RecordStatus.MANAGER_REVIEWING || st == RecordStatus.REVISION_NEEDED) {
                deadline = r.getManagerDeadlineOverride() != null ? r.getManagerDeadlineOverride() : period.getManagerDeadline();
            } else if (st == RecordStatus.PENDING_APPROVAL) {
                deadline = r.getApprovalDeadlineOverride() != null ? r.getApprovalDeadlineOverride() : period.getApprovalDeadline();
            }
            if (deadline != null && now.isAfter(deadline)) {
                overdueCount++;
            }
        }

        Map<Long, ResPeriodProgressDTO.DepartmentProgress> deptProgressMap = new java.util.HashMap<>();

        for (EvaluationRecord r : filteredRecords) {
            String empId = r.getEmployee().getId();
            UserPosition up = empPrimaryPosition.get(empId);

            Long deptId = 0L;
            String deptName = "Chưa phân phòng ban";

            if (up != null && up.getDepartmentJobTitle() != null && up.getDepartmentJobTitle().getDepartment() != null) {
                deptId = up.getDepartmentJobTitle().getDepartment().getId();
                deptName = up.getDepartmentJobTitle().getDepartment().getName();
            } else if (up != null && up.getSectionJobTitle() != null && up.getSectionJobTitle().getSection() != null
                    && up.getSectionJobTitle().getSection().getDepartment() != null) {
                deptId = up.getSectionJobTitle().getSection().getDepartment().getId();
                deptName = up.getSectionJobTitle().getSection().getDepartment().getName();
            }

            final String finalDeptName = deptName;
            ResPeriodProgressDTO.DepartmentProgress dp = deptProgressMap.computeIfAbsent(deptId, id -> {
                ResPeriodProgressDTO.DepartmentProgress newDp = new ResPeriodProgressDTO.DepartmentProgress();
                newDp.setDepartmentId(id);
                newDp.setDepartmentName(finalDeptName);
                return newDp;
            });

            dp.setTotalRecords(dp.getTotalRecords() + 1);

            RecordStatus st = r.getStatus();
            if (st == RecordStatus.EMPLOYEE_DRAFTING) {
                dp.setDraftingCount(dp.getDraftingCount() + 1);
            } else if (st == RecordStatus.PENDING_MANAGER_REVIEW || st == RecordStatus.MANAGER_REVIEWING || st == RecordStatus.REVISION_NEEDED) {
                dp.setPendingManagerCount(dp.getPendingManagerCount() + 1);
            } else if (st == RecordStatus.PENDING_APPROVAL) {
                dp.setPendingApprovalCount(dp.getPendingApprovalCount() + 1);
            } else if (st == RecordStatus.COMPLETED) {
                dp.setCompletedCount(dp.getCompletedCount() + 1);
            } else if (st == RecordStatus.CANCELLED) {
                dp.setCancelledCount(dp.getCancelledCount() + 1);
            }

            if (st != RecordStatus.COMPLETED && st != RecordStatus.CANCELLED && st != RecordStatus.NOT_STARTED) {
                Instant deadline = null;
                if (st == RecordStatus.EMPLOYEE_DRAFTING) {
                    deadline = r.getEmployeeDeadlineOverride() != null ? r.getEmployeeDeadlineOverride() : period.getEmployeeDeadline();
                } else if (st == RecordStatus.PENDING_MANAGER_REVIEW || st == RecordStatus.MANAGER_REVIEWING || st == RecordStatus.REVISION_NEEDED) {
                    deadline = r.getManagerDeadlineOverride() != null ? r.getManagerDeadlineOverride() : period.getManagerDeadline();
                } else if (st == RecordStatus.PENDING_APPROVAL) {
                    deadline = r.getApprovalDeadlineOverride() != null ? r.getApprovalDeadlineOverride() : period.getApprovalDeadline();
                }
                if (deadline != null && now.isAfter(deadline)) {
                    dp.setOverdueCount(dp.getOverdueCount() + 1);
                }
            }
        }

        List<ResPeriodProgressDTO.OverdueRecord> overdueList = new java.util.ArrayList<>();
        for (EvaluationRecord r : filteredRecords) {
            RecordStatus st = r.getStatus();
            if (st == RecordStatus.COMPLETED || st == RecordStatus.CANCELLED || st == RecordStatus.NOT_STARTED) {
                continue;
            }
            Instant deadline = null;
            if (st == RecordStatus.EMPLOYEE_DRAFTING) {
                deadline = r.getEmployeeDeadlineOverride() != null ? r.getEmployeeDeadlineOverride() : period.getEmployeeDeadline();
            } else if (st == RecordStatus.PENDING_MANAGER_REVIEW || st == RecordStatus.MANAGER_REVIEWING || st == RecordStatus.REVISION_NEEDED) {
                deadline = r.getManagerDeadlineOverride() != null ? r.getManagerDeadlineOverride() : period.getManagerDeadline();
            } else if (st == RecordStatus.PENDING_APPROVAL) {
                deadline = r.getApprovalDeadlineOverride() != null ? r.getApprovalDeadlineOverride() : period.getApprovalDeadline();
            }
            if (deadline != null && now.isAfter(deadline)) {
                ResPeriodProgressDTO.OverdueRecord or = new ResPeriodProgressDTO.OverdueRecord();
                or.setRecordId(r.getId());
                or.setEmployeeName(r.getEmployee() != null ? r.getEmployee().getName() : "Không tên");
                or.setEmployeeEmail(r.getEmployee() != null ? r.getEmployee().getEmail() : "");
                or.setStatus(st.name());

                String label = switch (st) {
                    case EMPLOYEE_DRAFTING -> "Nhân viên tự đánh giá";
                    case PENDING_MANAGER_REVIEW, MANAGER_REVIEWING -> "Quản lý trực tiếp đánh giá";
                    case REVISION_NEEDED -> "Nhân viên chỉnh sửa";
                    case PENDING_APPROVAL -> "Người phê duyệt đánh giá";
                    default -> st.toString();
                };
                or.setStatusLabel(label);

                long days = java.time.Duration.between(deadline, now).toDays();
                or.setOverdueDays(Math.max(1, days));
                or.setDeadline(deadline);

                overdueList.add(or);
            }
        }

        ResPeriodProgressDTO dto = new ResPeriodProgressDTO();
        ResPeriodProgressDTO.KpiProgress kpi = new ResPeriodProgressDTO.KpiProgress();
        int total = filteredRecords.size();
        kpi.setTotalRecords(total);

        int notStarted = 0;
        int drafting = 0;
        int pendingManager = 0;
        int pendingApproval = 0;
        int completed = 0;
        int cancelled = 0;
        for (EvaluationRecord r : filteredRecords) {
            switch (r.getStatus()) {
                case NOT_STARTED -> notStarted++;
                case EMPLOYEE_DRAFTING -> drafting++;
                case PENDING_MANAGER_REVIEW, MANAGER_REVIEWING, REVISION_NEEDED -> pendingManager++;
                case PENDING_APPROVAL -> pendingApproval++;
                case COMPLETED -> completed++;
                case CANCELLED -> cancelled++;
            }
        }

        kpi.setNotStartedCount(notStarted);
        kpi.setDraftingCount(drafting);
        kpi.setPendingManagerCount(pendingManager);
        kpi.setPendingApprovalCount(pendingApproval);
        kpi.setCompletedCount(completed);
        kpi.setCancelledCount(cancelled);
        kpi.setOverdueCount(overdueCount);

        double denom = Math.max(1, total);
        kpi.setNotStartedPercentage(Math.round((notStarted * 100.0 / denom) * 100.0) / 100.0);
        kpi.setDraftingPercentage(Math.round((drafting * 100.0 / denom) * 100.0) / 100.0);
        kpi.setPendingManagerPercentage(Math.round((pendingManager * 100.0 / denom) * 100.0) / 100.0);
        kpi.setPendingApprovalPercentage(Math.round((pendingApproval * 100.0 / denom) * 100.0) / 100.0);
        kpi.setCompletedPercentage(Math.round((completed * 100.0 / denom) * 100.0) / 100.0);
        kpi.setCancelledPercentage(Math.round((cancelled * 100.0 / denom) * 100.0) / 100.0);
        kpi.setOverduePercentage(Math.round((overdueCount * 100.0 / denom) * 100.0) / 100.0);

        dto.setKpiProgress(kpi);
        dto.setDepartmentProgress(new java.util.ArrayList<>(deptProgressMap.values()));
        dto.setOverdueRecords(overdueList);

        return dto;
    }

    private void checkPeriodEditable(EvaluationPeriod period) {
        if (period.getStatus() != PeriodStatus.DRAFT) {
            throw new IdInvalidException("Kỳ đánh giá đã kích hoạt hoặc đã đóng, không thể chỉnh sửa");
        }
    }

    private void assertPeriodInCurrentScope(EvaluationPeriod period) {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope == null) {
            throw new PermissionException("Không tìm thấy phạm vi truy cập hiện tại");
        }
        if (scope.isSuperAdmin() || scope.isAdminLevel()) {
            return;
        }
        Long companyId = period.getCompany() != null ? period.getCompany().getId() : null;
        if (companyId == null || scope.companyIds() == null || !scope.companyIds().contains(companyId)) {
            throw new PermissionException("Bạn không có quyền thao tác kỳ đánh giá của công ty này");
        }
    }

    private boolean userBelongsToCompany(String userId, Long companyId) {
        return userPositionRepo.findActiveFullByUserId(userId).stream().anyMatch(up -> {
            if ("COMPANY".equals(up.getSource()) && up.getCompanyJobTitle() != null) {
                return Objects.equals(up.getCompanyJobTitle().getCompany().getId(), companyId);
            }
            if ("DEPARTMENT".equals(up.getSource()) && up.getDepartmentJobTitle() != null) {
                return Objects.equals(up.getDepartmentJobTitle().getDepartment().getCompany().getId(), companyId);
            }
            if ("SECTION".equals(up.getSource()) && up.getSectionJobTitle() != null) {
                return Objects.equals(up.getSectionJobTitle().getSection().getDepartment().getCompany().getId(), companyId);
            }
            return false;
        });
    }

    private void normalizePeriodText(EvaluationPeriod period) {
        if (period == null) return;
        if (period.getName() != null) {
            String normalizedName = period.getName().trim().replaceAll("\\s+", " ");
            if (normalizedName.isBlank()) {
                throw new IdInvalidException("Vui lòng nhập tên kỳ đánh giá");
            }
            if (normalizedName.length() > 200) {
                throw new IdInvalidException("Tên kỳ đánh giá không vượt quá 200 ký tự");
            }
            period.setName(normalizedName);
        }
        if (period.getDescription() != null) {
            period.setDescription(period.getDescription().trim());
        }
    }

    @Transactional
    public EvaluationPeriod adjustStartDate(Long periodId, Instant newStartDate) {
        EvaluationPeriod period = fetchPeriodById(periodId);

        // Chỉ cho phép điều chỉnh khi kỳ đang ở trạng thái ACTIVE hoặc DRAFT
        if (period.getStatus() != PeriodStatus.ACTIVE && period.getStatus() != PeriodStatus.DRAFT) {
            throw new IdInvalidException("Chỉ có thể điều chỉnh ngày mở cổng của kỳ đánh giá ở trạng thái DRAFT hoặc ACTIVE");
        }

        period.setEmployeeStartDate(newStartDate);
        // validate các mốc ngày (employeeStartDate phải trước employeeDeadline)
        validatePeriodDates(period, false);

        // Nếu kỳ đang ACTIVE, ta cần cập nhật trạng thái của các bản ghi chưa bắt đầu đánh giá
        if (period.getStatus() == PeriodStatus.ACTIVE) {
            Instant now = Instant.now();
            boolean employeePhaseStarted = !now.isBefore(newStartDate);

            String email = vn.system.app.common.util.SecurityUtil.getCurrentUserLogin().orElse(null);
            User actor = email != null ? userRepo.findByEmail(email) : null;

            if (employeePhaseStarted) {
                // Chuyển các bản ghi NOT_STARTED -> EMPLOYEE_DRAFTING
                List<EvaluationRecord> notStartedRecords = recordRepo.findByPeriodIdAndStatus(periodId, RecordStatus.NOT_STARTED);
                java.util.List<String> employeeIds = new java.util.ArrayList<>();
                for (EvaluationRecord record : notStartedRecords) {
                    RecordStatus oldStatus = record.getStatus();
                    record.setStatus(RecordStatus.EMPLOYEE_DRAFTING);

                    EvaluationHistory history = new EvaluationHistory();
                    history.setEvaluationRecord(record);
                    history.setFromStatus(oldStatus);
                    history.setToStatus(RecordStatus.EMPLOYEE_DRAFTING);
                    history.setPerformedBy(actor);
                    history.setNote("Đổi ngày mở cổng về quá khứ/hiện tại: Tự động mở cổng tự đánh giá");
                    historyRepo.save(history);

                    employeeIds.add(record.getEmployee().getId());
                }
                if (!notStartedRecords.isEmpty()) {
                    recordRepo.saveAll(notStartedRecords);
                }
                if (!employeeIds.isEmpty()) {
                    // Gửi thông báo đến nhân viên
                    for (User employee : userRepo.findAllById(employeeIds)) {
                        sendNotification(employee, "PERIOD_OPENED",
                                String.format("Kỳ đánh giá \"%s\" đã được điều chỉnh ngày mở cổng và hiện đã mở. Vui lòng hoàn thành tự đánh giá.", period.getName()),
                                "/admin/evaluation/my-records");
                    }
                }
            } else {
                // Chuyển các bản ghi EMPLOYEE_DRAFTING (chưa có điểm tự đánh giá hoặc chưa nộp) về NOT_STARTED
                List<EvaluationRecord> draftingRecords = recordRepo.findByPeriodIdAndStatus(periodId, RecordStatus.EMPLOYEE_DRAFTING);
                for (EvaluationRecord record : draftingRecords) {
                    RecordStatus oldStatus = record.getStatus();
                    record.setStatus(RecordStatus.NOT_STARTED);

                    EvaluationHistory history = new EvaluationHistory();
                    history.setEvaluationRecord(record);
                    history.setFromStatus(oldStatus);
                    history.setToStatus(RecordStatus.NOT_STARTED);
                    history.setPerformedBy(actor);
                    history.setNote("Đổi ngày mở cổng về tương lai: Tự động khóa cổng tự đánh giá");
                    historyRepo.save(history);
                }
                if (!draftingRecords.isEmpty()) {
                    recordRepo.saveAll(draftingRecords);
                }
            }
        }

        return periodRepo.save(period);
    }

    private void validatePeriodDates(EvaluationPeriod period, boolean validateStartDateNotPast) {
        Instant now = Instant.now();

        if (validateStartDateNotPast && period.getEmployeeStartDate() != null
                && period.getEmployeeStartDate().isBefore(now)) {
            throw new IdInvalidException("Ngày mở cổng tự đánh giá không được nằm trong quá khứ!");
        }
        if (period.getEmployeeDeadline() != null && period.getEmployeeDeadline().isBefore(now)) {
            throw new IdInvalidException("Hạn chót Nhân viên nộp không được nằm trong quá khứ!");
        }
        if (period.getManagerDeadline() != null && period.getManagerDeadline().isBefore(now)) {
            throw new IdInvalidException("Hạn chót Quản lý chấm xong không được nằm trong quá khứ!");
        }
        if (period.getApprovalDeadline() != null && period.getApprovalDeadline().isBefore(now)) {
            throw new IdInvalidException("Hạn chót Quản lý gián tiếp duyệt không được nằm trong quá khứ!");
        }

        if (period.getEmployeeStartDate() == null || period.getEmployeeDeadline() == null
                || period.getManagerDeadline() == null || period.getApprovalDeadline() == null) {
            return; // allowed to be null in draft before full configuration
        }

        if (!period.getEmployeeStartDate().isBefore(period.getEmployeeDeadline())) {
            throw new IdInvalidException("Ngày mở cổng phải diễn ra trước Hạn chót Nhân viên nộp!");
        }
        if (!period.getEmployeeDeadline().isBefore(period.getManagerDeadline())) {
            throw new IdInvalidException("Hạn chót Nhân viên nộp phải diễn ra trước Hạn chót Quản lý chấm xong!");
        }
        if (!period.getManagerDeadline().isBefore(period.getApprovalDeadline())) {
            throw new IdInvalidException("Hạn chót Quản lý chấm xong phải diễn ra trước Hạn chót Quản lý gián tiếp duyệt!");
        }
    }
    private void sendNotification(User recipient, String type, String content, String actionLink) {
        if (recipient == null) return;
        eventPublisher.publishEvent(new vn.system.app.modules.notification.event.AppNotificationEvent(
                List.of(recipient.getId()), "EVALUATION", type, content, actionLink));
    }
}
