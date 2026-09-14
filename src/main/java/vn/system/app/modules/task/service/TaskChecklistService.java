package vn.system.app.modules.task.service;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.common.util.SecurityUtil;
import vn.system.app.common.util.UserScopeContext;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.notification.service.NotificationService;
import vn.system.app.modules.task.domain.Task;
import vn.system.app.modules.task.domain.TaskChecklist;
import vn.system.app.modules.task.domain.enums.TaskParticipantRole;
import vn.system.app.modules.task.domain.request.ReqCreateChecklistDTO;
import vn.system.app.modules.task.domain.request.ReqUpdateChecklistDTO;
import vn.system.app.modules.task.domain.response.ResTaskChecklistDTO;
import vn.system.app.modules.task.repository.TaskChecklistRepository;
import vn.system.app.modules.task.repository.TaskParticipantRepository;
import vn.system.app.modules.task.repository.TaskRepository;
import vn.system.app.modules.user.domain.User;
import vn.system.app.modules.user.repository.UserRepository;

@Service
public class TaskChecklistService {

    private static final Logger log = LoggerFactory.getLogger(TaskChecklistService.class);

    private final TaskChecklistRepository checklistRepository;
    private final TaskRepository taskRepository;
    private final TaskParticipantRepository participantRepository;
    private final UserRepository userRepository;
    private final TaskAccessService taskAccessService;
    private final NotificationService notificationService;

    public TaskChecklistService(
            TaskChecklistRepository checklistRepository,
            TaskRepository taskRepository,
            TaskParticipantRepository participantRepository,
            UserRepository userRepository,
            TaskAccessService taskAccessService,
            NotificationService notificationService) {
        this.checklistRepository = checklistRepository;
        this.taskRepository = taskRepository;
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.taskAccessService = taskAccessService;
        this.notificationService = notificationService;
    }

    @Transactional
    public ResTaskChecklistDTO createItem(Long taskId, ReqCreateChecklistDTO req) {
        String currentUserId = SecurityUtil.getCurrentUserId()
                .orElseThrow(() -> new IdInvalidException("Bạn chưa đăng nhập"));

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IdInvalidException("Tác vụ không tồn tại"));

        checkCanModifyTaskChecklist(task, currentUserId);

        User assignedUser = null;
        if (req.getAssignedUserId() != null && !req.getAssignedUserId().isBlank()) {
            assignedUser = validateAssignedUserIsParticipant(taskId, req.getAssignedUserId());
        }

        TaskChecklist item = new TaskChecklist();
        item.setTask(task);
        item.setTitle(req.getTitle().trim());
        item.setCompleted(false);
        item.setAssignedUser(assignedUser);
        item.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);

        TaskChecklist saved = checklistRepository.save(item);
        notifyChecklistAssigned(task, assignedUser, currentUserId);
        return convertToResDTO(saved);
    }

    @Transactional
    public ResTaskChecklistDTO updateItem(Long taskId, Long checklistId, ReqUpdateChecklistDTO req) {
        String currentUserId = SecurityUtil.getCurrentUserId()
                .orElseThrow(() -> new IdInvalidException("Bạn chưa đăng nhập"));

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IdInvalidException("Tác vụ không tồn tại"));

        TaskChecklist item = checklistRepository.findById(checklistId)
                .orElseThrow(() -> new IdInvalidException("Mục kiểm tra không tồn tại"));

        if (!item.getTask().getId().equals(taskId)) {
            throw new IdInvalidException("Mục kiểm tra không thuộc tác vụ này");
        }

        checkCanModifyTaskChecklist(task, currentUserId);

        if (req.getTitle() != null && !req.getTitle().isBlank()) {
            item.setTitle(req.getTitle().trim());
        }

        if (req.getAssignedUserId() != null) {
            if (req.getAssignedUserId().isBlank()) {
                item.setAssignedUser(null);
            } else {
                String oldAssignedUserId = item.getAssignedUser() != null ? item.getAssignedUser().getId() : null;
                User assignedUser = validateAssignedUserIsParticipant(taskId, req.getAssignedUserId());
                item.setAssignedUser(assignedUser);
                if (!assignedUser.getId().equals(oldAssignedUserId)) {
                    notifyChecklistAssigned(task, assignedUser, currentUserId);
                }
            }
        }

        if (req.getSortOrder() != null) {
            item.setSortOrder(req.getSortOrder());
        }

        if (req.getIsCompleted() != null) {
            toggleItemInternal(item, currentUserId, req.getIsCompleted());
        }

        TaskChecklist saved = checklistRepository.save(item);
        return convertToResDTO(saved);
    }

    @Transactional
    public ResTaskChecklistDTO toggleItem(Long taskId, Long checklistId, Boolean isCompleted) {
        String currentUserId = SecurityUtil.getCurrentUserId()
                .orElseThrow(() -> new IdInvalidException("Bạn chưa đăng nhập"));

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IdInvalidException("Tác vụ không tồn tại"));

        TaskChecklist item = checklistRepository.findById(checklistId)
                .orElseThrow(() -> new IdInvalidException("Mục kiểm tra không tồn tại"));

        if (!item.getTask().getId().equals(taskId)) {
            throw new IdInvalidException("Mục kiểm tra không thuộc tác vụ này");
        }

        User actor = userRepository.findById(currentUserId)
                .orElseThrow(() -> new IdInvalidException("Tài khoản người dùng không tồn tại"));

        // Check tick permissions based on action matrix:
        // Creator/Assignee can tick any item; Collaborator can tick ONLY items assigned to self
        boolean isCreator = participantRepository.existsByTaskIdAndUserIdAndRole(taskId, currentUserId, TaskParticipantRole.CREATOR);
        boolean isAssignee = participantRepository.existsByTaskIdAndUserIdAndRole(taskId, currentUserId, TaskParticipantRole.ASSIGNEE);
        boolean isCollab = participantRepository.existsByTaskIdAndUserIdAndRole(taskId, currentUserId, TaskParticipantRole.COLLABORATOR);
        boolean isAdmin = isAdminOrSuperAdmin();

        if (!isAdmin && !isCreator && !isAssignee) {
            if (isCollab) {
                if (item.getAssignedUser() == null || !item.getAssignedUser().getId().equals(currentUserId)) {
                    throw new IdInvalidException("Người phối hợp chỉ được phép tick mục kiểm tra được gán riêng cho mình");
                }
            } else {
                throw new IdInvalidException("Bạn không có quyền tick mục kiểm tra này");
            }
        }

        boolean targetCompleted = isCompleted != null ? isCompleted : !item.isCompleted();
        toggleItemInternal(item, currentUserId, targetCompleted);

        TaskChecklist saved = checklistRepository.save(item);
        return convertToResDTO(saved);
    }

    @Transactional
    public void deleteItem(Long taskId, Long checklistId) {
        String currentUserId = SecurityUtil.getCurrentUserId()
                .orElseThrow(() -> new IdInvalidException("Bạn chưa đăng nhập"));

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IdInvalidException("Tác vụ không tồn tại"));

        checkCanModifyTaskChecklist(task, currentUserId);

        TaskChecklist item = checklistRepository.findById(checklistId)
                .orElseThrow(() -> new IdInvalidException("Mục kiểm tra không tồn tại"));

        if (!item.getTask().getId().equals(taskId)) {
            throw new IdInvalidException("Mục kiểm tra không thuộc tác vụ này");
        }

        checklistRepository.delete(item);
    }

    @Transactional(readOnly = true)
    public List<ResTaskChecklistDTO> fetchChecklistsByTask(Long taskId) {
        String currentUserId = SecurityUtil.getCurrentUserId()
                .orElseThrow(() -> new IdInvalidException("Bạn chưa đăng nhập"));

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IdInvalidException("Tác vụ không tồn tại"));

        taskAccessService.assertCanViewTask(task, currentUserId);

        List<TaskChecklist> list = checklistRepository.findByTaskIdOrderBySortOrderAscIdAsc(taskId);
        return list.stream().map(this::convertToResDTO).collect(Collectors.toList());
    }

    private void notifyChecklistAssigned(Task task, User assignedUser, String actorId) {
        if (assignedUser == null || assignedUser.getId().equals(actorId)) {
            return;
        }
        try {
            notificationService.sendNotification(
                    assignedUser.getId(),
                    "TASK",
                    "TASK_CHECKLIST_ASSIGNED",
                    "Bạn được gán 1 mục kiểm tra trong tác vụ: " + task.getTitle(),
                    "/admin/tasks?taskId=" + task.getId());
        } catch (Exception e) {
            log.warn("Không thể gửi thông báo gán mục kiểm tra cho task {}: {}", task.getId(), e.getMessage());
        }
    }

    private void toggleItemInternal(TaskChecklist item, String actorUserId, boolean completed) {
        item.setCompleted(completed);
        if (completed) {
            User actor = userRepository.findById(actorUserId).orElse(null);
            item.setCompletedBy(actor);
            item.setCompletedAt(Instant.now());
        } else {
            item.setCompletedBy(null);
            item.setCompletedAt(null);
        }
    }

    private User validateAssignedUserIsParticipant(Long taskId, String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IdInvalidException("Người được gán mục kiểm tra không tồn tại"));

        if (!user.isActive()) {
            throw new IdInvalidException("Người được gán mục kiểm tra đã ngưng hoạt động");
        }

        boolean isParticipant = participantRepository.existsByTaskIdAndUserIdAndRole(taskId, userId, TaskParticipantRole.CREATOR)
                || participantRepository.existsByTaskIdAndUserIdAndRole(taskId, userId, TaskParticipantRole.ASSIGNEE)
                || participantRepository.existsByTaskIdAndUserIdAndRole(taskId, userId, TaskParticipantRole.COLLABORATOR);

        if (!isParticipant) {
            throw new IdInvalidException("Người được gán mục kiểm tra phải là một trong các vai trò (Creator, Assignee, Collaborator) của tác vụ này");
        }

        return user;
    }

    private void checkCanModifyTaskChecklist(Task task, String currentUserId) {
        if (isAdminOrSuperAdmin()) return;

        boolean isCreator = participantRepository.existsByTaskIdAndUserIdAndRole(task.getId(), currentUserId, TaskParticipantRole.CREATOR);
        boolean isAssignee = participantRepository.existsByTaskIdAndUserIdAndRole(task.getId(), currentUserId, TaskParticipantRole.ASSIGNEE);

        if (!isCreator && !isAssignee) {
            throw new IdInvalidException("Chỉ Người giao việc hoặc Người thực hiện chính mới được thêm/sửa/xóa mục kiểm tra");
        }
    }

    private boolean isAdminOrSuperAdmin() {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        return scope != null && (scope.isSuperAdmin() || scope.isAdminLevel());
    }

    public ResTaskChecklistDTO convertToResDTO(TaskChecklist item) {
        ResTaskChecklistDTO dto = new ResTaskChecklistDTO();
        dto.setId(item.getId());
        dto.setTaskId(item.getTask().getId());
        dto.setTitle(item.getTitle());
        dto.setCompleted(item.isCompleted());

        if (item.getAssignedUser() != null) {
            dto.setAssignedUserId(item.getAssignedUser().getId());
            dto.setAssignedUserName(item.getAssignedUser().getName());
            dto.setAssignedUserAvatar(item.getAssignedUser().getAvatar());
        }

        if (item.getCompletedBy() != null) {
            dto.setCompletedById(item.getCompletedBy().getId());
            dto.setCompletedByName(item.getCompletedBy().getName());
            dto.setCompletedAt(item.getCompletedAt());
        }

        dto.setSortOrder(item.getSortOrder());

        return dto;
    }
}
