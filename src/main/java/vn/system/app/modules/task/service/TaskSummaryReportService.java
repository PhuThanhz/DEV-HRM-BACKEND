package vn.system.app.modules.task.service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.common.util.ExcelExportUtil;
import vn.system.app.common.util.ScopeSpec;
import vn.system.app.common.util.UserScopeContext;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.task.domain.Task;
import vn.system.app.modules.task.domain.TaskParticipant;
import vn.system.app.modules.task.domain.TaskSubmission;
import vn.system.app.modules.task.domain.enums.KpiCycleType;
import vn.system.app.modules.task.domain.enums.TaskParticipantRole;
import vn.system.app.modules.task.domain.enums.TaskPriority;
import vn.system.app.modules.task.domain.enums.TaskStatus;
import vn.system.app.modules.task.domain.response.ResTaskDTO;
import vn.system.app.modules.task.domain.response.ResTaskSummaryReportDTO;
import vn.system.app.modules.task.repository.TaskParticipantRepository;
import vn.system.app.modules.task.repository.TaskRepository;
import vn.system.app.modules.task.repository.TaskSubmissionRepository;

@Service
public class TaskSummaryReportService {

    private static final long MAX_REPORT_RANGE_DAYS = 366;

    private final TaskRepository taskRepository;
    private final TaskParticipantRepository participantRepository;
    private final TaskSubmissionRepository submissionRepository;
    private final TaskService taskService;

    public TaskSummaryReportService(
            TaskRepository taskRepository,
            TaskParticipantRepository participantRepository,
            TaskSubmissionRepository submissionRepository,
            TaskService taskService) {
        this.taskRepository = taskRepository;
        this.participantRepository = participantRepository;
        this.submissionRepository = submissionRepository;
        this.taskService = taskService;
    }

    private Specification<Task> buildSpecification(
            Instant filterFrom, Instant filterTo, Long departmentId, Long companyId, String assigneeId, TaskPriority priority, String title,
            Boolean isOnTime, String createdBy, Boolean isJdTask, Long kpiGroupId, KpiCycleType kpiCycleType) {

        Specification<Task> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("status"), TaskStatus.COMPLETED));

            if (filterFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("completedAt"), filterFrom));
            }
            if (filterTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("completedAt"), filterTo));
            }
            if (departmentId != null) {
                predicates.add(cb.equal(root.get("department").get("id"), departmentId));
            }
            if (companyId != null) {
                predicates.add(cb.equal(root.get("department").get("company").get("id"), companyId));
            }
            if (priority != null) {
                predicates.add(cb.equal(root.get("priority"), priority));
            }
            if (title != null && !title.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("title")), "%" + title.toLowerCase() + "%"));
            }
            if (isOnTime != null) {
                predicates.add(cb.equal(root.get("isOnTime"), isOnTime));
            }
            if (isJdTask != null) {
                predicates.add(isJdTask
                        ? cb.isNotNull(root.get("jobDescriptionTaskId"))
                        : cb.isNull(root.get("jobDescriptionTaskId")));
            }
            if (kpiGroupId != null) {
                predicates.add(cb.equal(root.get("kpiGroupId"), kpiGroupId));
            }
            if (kpiCycleType != null) {
                predicates.add(cb.equal(root.get("kpiCycleType"), kpiCycleType));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        // Department Scope check
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope != null && !scope.isSuperAdmin() && !scope.isAdminLevel()) {
            spec = spec.and(ScopeSpec.byCompanyOrDepartmentScope("department.company.id", "department.id"));
        }

        // Task không có collection JPA-mapped tới TaskParticipant, phải lọc bằng subquery thủ công
        if (assigneeId != null) {
            Specification<Task> assigneeSpec = (root, query, cb) -> {
                var sub = query.subquery(Long.class);
                var participantRoot = sub.from(TaskParticipant.class);
                sub.select(participantRoot.get("task").get("id"))
                        .where(cb.and(
                                cb.equal(participantRoot.get("user").get("id"), assigneeId),
                                cb.equal(participantRoot.get("role"), TaskParticipantRole.ASSIGNEE)
                        ));
                return root.get("id").in(sub);
            };
            spec = spec.and(assigneeSpec);
        }

        if (createdBy != null && !createdBy.isBlank()) {
            Specification<Task> creatorSpec = (root, query, cb) -> {
                var sub = query.subquery(Long.class);
                var participantRoot = sub.from(TaskParticipant.class);
                sub.select(participantRoot.get("task").get("id"))
                        .where(cb.and(
                                cb.equal(participantRoot.get("user").get("id"), createdBy),
                                cb.equal(participantRoot.get("role"), TaskParticipantRole.CREATOR)
                        ));
                return root.get("id").in(sub);
            };
            spec = spec.and(creatorSpec);
        }

        return spec;
    }

    // Boundary protection: chặn khoảng thời gian báo cáo quá lớn (full scan/export toàn bộ dữ liệu completed)
    private Instant[] resolveReportDateRange(Instant from, Instant to) {
        Instant filterFrom = (from == null)
                ? Instant.now().minus(90, java.time.temporal.ChronoUnit.DAYS)
                : from;
        Instant filterTo = to;
        Instant effectiveTo = filterTo != null ? filterTo : Instant.now();
        if (Duration.between(filterFrom, effectiveTo).toDays() > MAX_REPORT_RANGE_DAYS) {
            throw new IdInvalidException("Khoảng thời gian báo cáo không được vượt quá " + MAX_REPORT_RANGE_DAYS + " ngày");
        }
        return new Instant[] { filterFrom, filterTo };
    }

    private List<ResTaskDTO> convertTasksToDtos(List<Task> tasks) {
        List<Long> taskIds = tasks.stream().map(Task::getId).toList();

        Map<Long, List<TaskParticipant>> participantMap = new HashMap<>();
        Map<Long, TaskSubmission> latestSubmissionMap = new HashMap<>();
        if (!taskIds.isEmpty()) {
            List<TaskParticipant> participants = participantRepository.findByTaskIdIn(taskIds);
            for (TaskParticipant p : participants) {
                participantMap.computeIfAbsent(p.getTask().getId(), k -> new ArrayList<>()).add(p);
            }

            List<TaskSubmission> submissions = submissionRepository
                    .findByTaskIdInOrderByTaskIdAscSubmissionRoundDesc(taskIds);
            for (TaskSubmission submission : submissions) {
                latestSubmissionMap.putIfAbsent(submission.getTask().getId(), submission);
            }
        }

        return tasks.stream()
                .map(t -> taskService.convertToResDTO(
                        t,
                        participantMap.getOrDefault(t.getId(), List.of()),
                        latestSubmissionMap))
                .toList();
    }

    private static double onTimePercentageOf(List<ResTaskDTO> tasks) {
        if (tasks.isEmpty()) {
            return 0.0;
        }
        long onTime = tasks.stream().filter(t -> Boolean.TRUE.equals(t.getIsOnTime())).count();
        return Math.round((double) onTime * 1000.0 / tasks.size()) / 10.0;
    }

    private List<ResTaskSummaryReportDTO.DepartmentGroupDTO> buildDepartmentGroups(List<ResTaskDTO> taskDtos) {
        Map<Long, List<ResTaskDTO>> byDepartment = taskDtos.stream()
                .collect(Collectors.groupingBy(ResTaskDTO::getDepartmentId, LinkedHashMap::new, Collectors.toList()));

        List<ResTaskSummaryReportDTO.DepartmentGroupDTO> departmentGroups = new ArrayList<>();
        for (Map.Entry<Long, List<ResTaskDTO>> deptEntry : byDepartment.entrySet()) {
            List<ResTaskDTO> deptTasks = deptEntry.getValue();

            Map<String, List<ResTaskDTO>> byAssignee = deptTasks.stream()
                    .collect(Collectors.groupingBy(
                            t -> t.getAssigneeId() != null ? t.getAssigneeId() : "__unassigned__",
                            LinkedHashMap::new,
                            Collectors.toList()));

            List<ResTaskSummaryReportDTO.EmployeeGroupDTO> employeeGroups = new ArrayList<>();
            for (List<ResTaskDTO> empTasks : byAssignee.values()) {
                ResTaskDTO first = empTasks.get(0);

                ResTaskSummaryReportDTO.EmployeeGroupDTO eg = new ResTaskSummaryReportDTO.EmployeeGroupDTO();
                eg.setAssigneeId(first.getAssigneeId());
                eg.setAssigneeName(first.getAssigneeName() != null ? first.getAssigneeName() : "Chưa gán người thực hiện");
                eg.setAssigneeAvatar(first.getAssigneeAvatar());
                eg.setTaskCount(empTasks.size());
                eg.setOnTimeCount((int) empTasks.stream().filter(t -> Boolean.TRUE.equals(t.getIsOnTime())).count());
                eg.setOnTimePercentage(onTimePercentageOf(empTasks));
                eg.setTasks(empTasks);
                employeeGroups.add(eg);
            }
            employeeGroups.sort(Comparator.comparing(
                    ResTaskSummaryReportDTO.EmployeeGroupDTO::getAssigneeName, Comparator.nullsLast(String::compareTo)));

            ResTaskDTO firstDeptTask = deptTasks.get(0);
            ResTaskSummaryReportDTO.DepartmentGroupDTO dg = new ResTaskSummaryReportDTO.DepartmentGroupDTO();
            dg.setDepartmentId(deptEntry.getKey());
            dg.setDepartmentName(firstDeptTask.getDepartmentName());
            dg.setCompanyName(firstDeptTask.getCompanyName());
            dg.setTaskCount(deptTasks.size());
            dg.setOnTimeCount((int) deptTasks.stream().filter(t -> Boolean.TRUE.equals(t.getIsOnTime())).count());
            dg.setOnTimePercentage(onTimePercentageOf(deptTasks));
            dg.setEmployeeGroups(employeeGroups);
            departmentGroups.add(dg);
        }

        departmentGroups.sort(Comparator.comparing(
                ResTaskSummaryReportDTO.DepartmentGroupDTO::getDepartmentName, Comparator.nullsLast(String::compareTo)));

        return departmentGroups;
    }

    @Transactional(readOnly = true)
    public ResTaskSummaryReportDTO generateReport(
            Instant from, Instant to, Long departmentId, Long companyId, String assigneeId, TaskPriority priority, String title,
            Boolean isOnTime, String createdBy, Boolean isJdTask, Long kpiGroupId, KpiCycleType kpiCycleType) {

        Instant[] range = resolveReportDateRange(from, to);
        Instant filterFrom = range[0];
        Instant filterTo = range[1];

        Specification<Task> spec = buildSpecification(filterFrom, filterTo, departmentId, companyId, assigneeId, priority, title, isOnTime, createdBy, isJdTask, kpiGroupId, kpiCycleType);
        List<Task> completedTasks = taskRepository.findAll(spec);
        List<ResTaskDTO> taskDtos = convertTasksToDtos(completedTasks);

        int totalCount = taskDtos.size();
        int totalOnTime = (int) taskDtos.stream().filter(t -> Boolean.TRUE.equals(t.getIsOnTime())).count();

        ResTaskSummaryReportDTO report = new ResTaskSummaryReportDTO();
        report.setTotalTaskCount(totalCount);
        report.setCompletedTaskCount(totalCount);
        report.setOnTimeTaskCount(totalOnTime);
        report.setOnTimePercentage(totalCount > 0 ? Math.round((double) totalOnTime * 1000.0 / totalCount) / 10.0 : 0.0);
        report.setDepartmentGroups(buildDepartmentGroups(taskDtos));

        return report;
    }

    private static final DateTimeFormatter EXCEL_DATE_FORMATTER = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private String formatExcelDate(Instant instant) {
        if (instant == null) return "N/A";
        return EXCEL_DATE_FORMATTER.format(instant);
    }

    private String formatCycleLabel(KpiCycleType cycleType) {
        if (cycleType == null) return "N/A";
        return switch (cycleType) {
            case MONTHLY -> "Tháng";
            case QUARTERLY -> "Quý";
            case YEARLY -> "Năm";
            case PHASE -> "Giai đoạn";
            case PROJECT -> "Dự án";
        };
    }

    private String formatPriorityLabel(TaskPriority priority) {
        if (priority == null) return "Trung bình";
        return switch (priority) {
            case URGENT -> "Khẩn cấp";
            case HIGH -> "Cao";
            case MEDIUM -> "Trung bình";
            case LOW -> "Thấp";
        };
    }

    @Transactional(readOnly = true)
    public byte[] exportToExcel(Instant from, Instant to, Long departmentId, Long companyId, String assigneeId, TaskPriority priority, String title,
            Boolean isOnTime, String createdBy, Boolean isJdTask, Long kpiGroupId, KpiCycleType kpiCycleType) {
        Instant[] range = resolveReportDateRange(from, to);
        Instant filterFrom = range[0];
        Instant filterTo = range[1];

        Specification<Task> spec = buildSpecification(filterFrom, filterTo, departmentId, companyId, assigneeId, priority, title, isOnTime, createdBy, isJdTask, kpiGroupId, kpiCycleType);
        List<Task> completedTasks = taskRepository.findAll(spec);
        List<ResTaskDTO> taskDtos = convertTasksToDtos(completedTasks);

        taskDtos = taskDtos.stream()
                .sorted(Comparator
                        .comparing(ResTaskDTO::getCompanyName, Comparator.nullsLast(String::compareTo))
                        .thenComparing(ResTaskDTO::getDepartmentName, Comparator.nullsLast(String::compareTo))
                        .thenComparing(ResTaskDTO::getAssigneeName, Comparator.nullsLast(String::compareTo)))
                .toList();

        List<String> headers = List.of(
                "STT",
                "Công ty",
                "Phòng ban",
                "Người thực hiện chính",
                "Tên tác vụ",
                "Loại công việc",
                "Nhóm KPI",
                "Chu kỳ KPI",
                "Độ ưu tiên",
                "Ngày bắt đầu",
                "Hạn chót",
                "Ngày hoàn thành",
                "Đánh giá tiến độ"
        );

        List<List<Object>> rows = new ArrayList<>();
        int stt = 1;

        for (ResTaskDTO t : taskDtos) {
            rows.add(List.of(
                    stt++,
                    t.getCompanyName() != null ? t.getCompanyName() : "N/A",
                    t.getDepartmentName() != null ? t.getDepartmentName() : "N/A",
                    t.getAssigneeName() != null ? t.getAssigneeName() : "Chưa phân công",
                    t.getTitle() != null ? t.getTitle() : "N/A",
                    t.getJobDescriptionTaskId() != null ? "Trong JD" : "Ngoài JD",
                    t.getKpiGroupName() != null ? t.getKpiGroupName() : "N/A",
                    formatCycleLabel(t.getKpiCycleType()),
                    formatPriorityLabel(t.getPriority()),
                    formatExcelDate(t.getStartDate()),
                    formatExcelDate(t.getDueDate()),
                    formatExcelDate(t.getCompletedAt()),
                    Boolean.TRUE.equals(t.getIsOnTime()) ? "Đúng hạn" : "Trễ hạn"
            ));
        }

        try {
            return ExcelExportUtil.createExcelReport("Báo cáo tổng kết công việc", headers, rows);
        } catch (IOException e) {
            throw new IdInvalidException("Lỗi khi xuất file báo cáo Excel: " + e.getMessage());
        }
    }
}
