package vn.system.app.modules.task.service;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.common.util.SecurityUtil;
import vn.system.app.common.util.UserScopeContext;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.approvaldelegation.service.ApprovalDelegationService;
import vn.system.app.modules.notification.service.NotificationService;
import vn.system.app.modules.task.domain.Task;
import vn.system.app.modules.task.domain.TaskExtensionRequest;
import vn.system.app.modules.task.domain.TaskParticipant;
import vn.system.app.modules.task.domain.enums.TaskExtensionStatus;
import vn.system.app.modules.task.domain.enums.TaskParticipantRole;
import vn.system.app.modules.task.domain.enums.TaskStatus;
import vn.system.app.modules.task.domain.request.ReqDecideExtensionDTO;
import vn.system.app.modules.task.domain.request.ReqRequestExtensionDTO;
import vn.system.app.modules.task.domain.response.ResTaskExtensionRequestDTO;
import vn.system.app.modules.task.repository.TaskExtensionRequestRepository;
import vn.system.app.modules.task.repository.TaskParticipantRepository;
import vn.system.app.modules.task.repository.TaskRepository;
import vn.system.app.modules.user.domain.User;
import vn.system.app.modules.user.repository.UserRepository;

@Service
public class TaskExtensionRequestService {

    private final TaskExtensionRequestRepository extensionRepository;
    private final TaskRepository taskRepository;
    private final TaskParticipantRepository participantRepository;
    private final UserRepository userRepository;
    private final TaskCommentService taskCommentService;
    private final NotificationService notificationService;
    private final ApprovalDelegationService approvalDelegationService;
    private final TaskAccessService taskAccessService;

    public TaskExtensionRequestService(
            TaskExtensionRequestRepository extensionRepository,
            TaskRepository taskRepository,
            TaskParticipantRepository participantRepository,
            UserRepository userRepository,
            TaskCommentService taskCommentService,
            NotificationService notificationService,
            ApprovalDelegationService approvalDelegationService,
            TaskAccessService taskAccessService) {
        this.extensionRepository = extensionRepository;
        this.taskRepository = taskRepository;
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.taskCommentService = taskCommentService;
        this.notificationService = notificationService;
        this.approvalDelegationService = approvalDelegationService;
        this.taskAccessService = taskAccessService;
    }

    /*
     * =====================================================
     * NHÂN VIÊN XIN GIA HẠN (IN_PROGRESS / REWORK)
     * =====================================================
     */
    @Transactional
    public ResTaskExtensionRequestDTO requestExtension(Long taskId, ReqRequestExtensionDTO req) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IdInvalidException("Tác vụ không tồn tại"));

        String currentUserId = SecurityUtil.getCurrentUserId()
                .orElseThrow(() -> new IdInvalidException("Bạn chưa đăng nhập"));
        User actor = userRepository.findById(currentUserId)
                .orElseThrow(() -> new IdInvalidException("Tài khoản người dùng không tồn tại"));

        boolean isAssignee = participantRepository.existsByTaskIdAndUserIdAndRole(taskId, currentUserId, TaskParticipantRole.ASSIGNEE);
        if (!isAssignee && !isAdminOrSuperAdmin()) {
            throw new IdInvalidException("Chỉ Người thực hiện chính mới được phép xin gia hạn tác vụ");
        }

        if (task.getStatus() != TaskStatus.IN_PROGRESS && task.getStatus() != TaskStatus.REWORK) {
            throw new IdInvalidException("Chỉ được xin gia hạn khi tác vụ đang ở trạng thái Đang làm hoặc Yêu cầu làm lại");
        }

        if (extensionRepository.existsByTaskIdAndStatus(taskId, TaskExtensionStatus.PENDING)) {
            throw new IdInvalidException("Tác vụ đang có 1 yêu cầu gia hạn chờ duyệt, vui lòng chờ xử lý xong trước khi gửi yêu cầu mới");
        }

        if (req.getRequestedDueDate().isBefore(Instant.now())) {
            throw new IdInvalidException("Hạn chót đề xuất không được ở trong quá khứ");
        }
        if (task.getDueDate() != null && !req.getRequestedDueDate().isAfter(task.getDueDate())) {
            throw new IdInvalidException("Hạn chót đề xuất phải muộn hơn hạn chót hiện tại");
        }

        TaskExtensionRequest extension = new TaskExtensionRequest();
        extension.setTask(task);
        extension.setCurrentDueDate(task.getDueDate());
        extension.setRequestedDueDate(req.getRequestedDueDate());
        extension.setReason(req.getReason().trim());
        extension.setStatus(TaskExtensionStatus.PENDING);
        extension.setRequestedBy(actor);

        TaskExtensionRequest saved = extensionRepository.save(extension);

        taskCommentService.writeAuditLog(task, actor,
                "Đã xin gia hạn hạn chót đến " + req.getRequestedDueDate() + ". Lý do: " + extension.getReason(), null);

        TaskParticipant creatorP = participantRepository.findByTaskIdAndRole(taskId, TaskParticipantRole.CREATOR).orElse(null);
        if (creatorP != null && creatorP.getUser() != null) {
            notificationService.sendNotification(
                    creatorP.getUser().getId(),
                    "TASK",
                    "TASK_EXTENSION_REQUESTED",
                    actor.getName() + " xin gia hạn tác vụ: " + task.getTitle(),
                    "/admin/tasks?taskId=" + task.getId());
        }

        return convertToResDTO(saved);
    }

    /*
     * =====================================================
     * QUẢN LÝ DUYỆT / TỪ CHỐI GIA HẠN
     * =====================================================
     */
    @Transactional
    public ResTaskExtensionRequestDTO decideExtension(Long taskId, Long extensionId, ReqDecideExtensionDTO req) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IdInvalidException("Tác vụ không tồn tại"));

        TaskExtensionRequest extension = extensionRepository.findById(extensionId)
                .orElseThrow(() -> new IdInvalidException("Yêu cầu gia hạn không tồn tại"));

        if (!extension.getTask().getId().equals(taskId)) {
            throw new IdInvalidException("Yêu cầu gia hạn không thuộc tác vụ này");
        }
        if (extension.getStatus() != TaskExtensionStatus.PENDING) {
            throw new IdInvalidException("Yêu cầu gia hạn này đã được xử lý");
        }
        if (task.getStatus() != TaskStatus.IN_PROGRESS && task.getStatus() != TaskStatus.REWORK) {
            throw new IdInvalidException("Không thể duyệt gia hạn vì tác vụ không còn ở trạng thái Đang làm hoặc Yêu cầu làm lại");
        }

        String currentUserId = SecurityUtil.getCurrentUserId()
                .orElseThrow(() -> new IdInvalidException("Bạn chưa đăng nhập"));
        User actor = userRepository.findById(currentUserId)
                .orElseThrow(() -> new IdInvalidException("Tài khoản người dùng không tồn tại"));

        TaskParticipant creatorP = participantRepository.findByTaskIdAndRole(taskId, TaskParticipantRole.CREATOR).orElse(null);
        String creatorId = creatorP != null && creatorP.getUser() != null ? creatorP.getUser().getId() : "";

        boolean isCreator = creatorId.equals(currentUserId);
        boolean isDelegate = approvalDelegationService.canActAsDelegate("TASK", creatorId, currentUserId);
        boolean isAdmin = isAdminOrSuperAdmin();

        if (!isCreator && !isDelegate && !isAdmin) {
            throw new IdInvalidException("Bạn không có quyền duyệt yêu cầu gia hạn tác vụ này (chỉ Người giao việc hoặc người được ủy quyền duyệt)");
        }

        TaskParticipant assigneeP = participantRepository.findByTaskIdAndRole(taskId, TaskParticipantRole.ASSIGNEE).orElse(null);
        String assigneeId = assigneeP != null && assigneeP.getUser() != null ? assigneeP.getUser().getId() : null;

        String decision = req.getDecision() == null ? "" : req.getDecision().trim();

        if (decision.equalsIgnoreCase("APPROVE")) {
            task.setDueDate(extension.getRequestedDueDate());
            taskRepository.save(task);

            extension.setStatus(TaskExtensionStatus.APPROVED);
            extension.setDecidedBy(actor);
            extension.setDecidedAt(Instant.now());
            extension.setDecisionNote(trimToNull(req.getDecisionNote()));
            TaskExtensionRequest saved = extensionRepository.save(extension);

            taskCommentService.writeAuditLog(task, actor,
                    "Đã duyệt gia hạn hạn chót tác vụ đến " + extension.getRequestedDueDate(), null);

            if (assigneeId != null) {
                notificationService.sendNotification(
                        assigneeId,
                        "TASK",
                        "TASK_EXTENSION_APPROVED",
                        actor.getName() + " đã duyệt gia hạn tác vụ: " + task.getTitle(),
                        "/admin/tasks?taskId=" + task.getId());
            }

            return convertToResDTO(saved);

        } else if (decision.equalsIgnoreCase("REJECT")) {
            if (req.getDecisionNote() == null || req.getDecisionNote().isBlank()) {
                throw new IdInvalidException("Lý do từ chối gia hạn không được để trống");
            }

            extension.setStatus(TaskExtensionStatus.REJECTED);
            extension.setDecidedBy(actor);
            extension.setDecidedAt(Instant.now());
            extension.setDecisionNote(req.getDecisionNote().trim());
            TaskExtensionRequest saved = extensionRepository.save(extension);

            taskCommentService.writeAuditLog(task, actor,
                    "Đã từ chối gia hạn. Lý do: " + extension.getDecisionNote(), null);

            if (assigneeId != null) {
                notificationService.sendNotification(
                        assigneeId,
                        "TASK",
                        "TASK_EXTENSION_REJECTED",
                        actor.getName() + " đã từ chối yêu cầu gia hạn tác vụ: " + task.getTitle(),
                        "/admin/tasks?taskId=" + task.getId());
            }

            return convertToResDTO(saved);

        } else {
            throw new IdInvalidException("Quyết định không hợp lệ (chỉ chấp nhận APPROVE hoặc REJECT)");
        }
    }

    /*
     * =====================================================
     * TỰ ĐỘNG TỪ CHỐI REQUEST PENDING KHI TASK RỜI KHỎI IN_PROGRESS/REWORK
     * (vd: assignee nộp kết quả trong lúc request gia hạn còn chờ duyệt)
     * để tránh request "mồ côi" bị duyệt nhầm sau khi task đã qua giai đoạn khác.
     * =====================================================
     */
    @Transactional
    public void autoRejectPendingExtension(Task task, String reason) {
        extensionRepository.findByTaskIdAndStatus(task.getId(), TaskExtensionStatus.PENDING)
                .ifPresent(extension -> {
                    extension.setStatus(TaskExtensionStatus.REJECTED);
                    extension.setDecidedAt(Instant.now());
                    extension.setDecisionNote(reason);
                    extensionRepository.save(extension);
                });
    }

    @Transactional(readOnly = true)
    public List<ResTaskExtensionRequestDTO> fetchExtensionsByTask(Long taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IdInvalidException("Tác vụ không tồn tại"));
        String currentUserId = SecurityUtil.getCurrentUserId()
                .orElseThrow(() -> new IdInvalidException("Bạn chưa đăng nhập"));
        taskAccessService.assertCanViewTask(task, currentUserId);

        return extensionRepository.findByTaskIdOrderByRequestedAtDesc(taskId).stream()
                .map(this::convertToResDTO)
                .collect(Collectors.toList());
    }

    private ResTaskExtensionRequestDTO convertToResDTO(TaskExtensionRequest ext) {
        ResTaskExtensionRequestDTO dto = new ResTaskExtensionRequestDTO();
        dto.setId(ext.getId());
        dto.setTaskId(ext.getTask().getId());
        dto.setCurrentDueDate(ext.getCurrentDueDate());
        dto.setRequestedDueDate(ext.getRequestedDueDate());
        dto.setReason(ext.getReason());
        dto.setStatus(ext.getStatus());

        if (ext.getRequestedBy() != null) {
            dto.setRequestedById(ext.getRequestedBy().getId());
            dto.setRequestedByName(ext.getRequestedBy().getName());
            dto.setRequestedByAvatar(ext.getRequestedBy().getAvatar());
        }
        dto.setRequestedAt(ext.getRequestedAt());

        if (ext.getDecidedBy() != null) {
            dto.setDecidedById(ext.getDecidedBy().getId());
            dto.setDecidedByName(ext.getDecidedBy().getName());
        }
        dto.setDecidedAt(ext.getDecidedAt());
        dto.setDecisionNote(ext.getDecisionNote());

        return dto;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isAdminOrSuperAdmin() {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        return scope != null && (scope.isSuperAdmin() || scope.isAdminLevel());
    }
}
