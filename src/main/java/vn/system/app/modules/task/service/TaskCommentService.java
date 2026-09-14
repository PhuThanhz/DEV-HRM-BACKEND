package vn.system.app.modules.task.service;

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
import vn.system.app.modules.task.domain.TaskComment;
import vn.system.app.modules.task.domain.TaskParticipant;
import vn.system.app.modules.task.domain.enums.TaskCommentType;
import vn.system.app.modules.task.domain.enums.TaskParticipantRole;
import vn.system.app.modules.task.domain.request.ReqCreateCommentDTO;
import vn.system.app.modules.task.domain.response.ResTaskCommentDTO;
import vn.system.app.modules.task.repository.TaskCommentRepository;
import vn.system.app.modules.task.repository.TaskParticipantRepository;
import vn.system.app.modules.task.repository.TaskRepository;
import vn.system.app.modules.user.domain.User;
import vn.system.app.modules.user.repository.UserRepository;

@Service
public class TaskCommentService {

    private static final Logger log = LoggerFactory.getLogger(TaskCommentService.class);

    private final TaskCommentRepository commentRepository;
    private final TaskRepository taskRepository;
    private final TaskParticipantRepository participantRepository;
    private final UserRepository userRepository;
    private final TaskAccessService taskAccessService;
    private final NotificationService notificationService;

    public TaskCommentService(
            TaskCommentRepository commentRepository,
            TaskRepository taskRepository,
            TaskParticipantRepository participantRepository,
            UserRepository userRepository,
            TaskAccessService taskAccessService,
            NotificationService notificationService) {
        this.commentRepository = commentRepository;
        this.taskRepository = taskRepository;
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.taskAccessService = taskAccessService;
        this.notificationService = notificationService;
    }

    @Transactional
    public ResTaskCommentDTO createComment(Long taskId, ReqCreateCommentDTO req) {
        String currentUserId = SecurityUtil.getCurrentUserId()
                .orElseThrow(() -> new IdInvalidException("Bạn chưa đăng nhập"));

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IdInvalidException("Tác vụ không tồn tại"));

        User actor = userRepository.findById(currentUserId)
                .orElseThrow(() -> new IdInvalidException("Tài khoản người dùng không tồn tại"));

        boolean isCreator = participantRepository.existsByTaskIdAndUserIdAndRole(taskId, currentUserId, TaskParticipantRole.CREATOR);
        boolean isAssignee = participantRepository.existsByTaskIdAndUserIdAndRole(taskId, currentUserId, TaskParticipantRole.ASSIGNEE);
        boolean isCollab = participantRepository.existsByTaskIdAndUserIdAndRole(taskId, currentUserId, TaskParticipantRole.COLLABORATOR);
        boolean isObserver = participantRepository.existsByTaskIdAndUserIdAndRole(taskId, currentUserId, TaskParticipantRole.OBSERVER);
        UserScopeContext.UserScope scope = UserScopeContext.get();
        boolean isAdmin = scope != null && (scope.isSuperAdmin() || scope.isAdminLevel());

        if (!isAdmin && !isCreator && !isAssignee && !isCollab) {
            if (isObserver) {
                throw new IdInvalidException("Người quan sát không được phép gửi bình luận thảo luận");
            }
            throw new IdInvalidException("Bạn không có quyền gửi bình luận cho tác vụ này");
        }

        TaskComment comment = new TaskComment();
        comment.setTask(task);
        comment.setUser(actor);
        comment.setContent(req.getContent().trim());
        comment.setAttachmentUrl(req.getAttachmentUrl());
        comment.setType(TaskCommentType.COMMENT);

        TaskComment saved = commentRepository.save(comment);
        notifyNewComment(task, actor, currentUserId);
        return convertToResDTO(saved);
    }

    private void notifyNewComment(Task task, User actor, String actorId) {
        List<TaskParticipant> participants = participantRepository.findByTaskId(task.getId());
        List<String> recipientIds = participants.stream()
                .map(p -> p.getUser().getId())
                .filter(uid -> !uid.equals(actorId))
                .distinct()
                .collect(Collectors.toList());

        if (recipientIds.isEmpty()) {
            return;
        }
        try {
            notificationService.sendNotifications(
                    recipientIds,
                    "TASK",
                    "TASK_COMMENT_ADDED",
                    actor.getName() + " đã bình luận trong tác vụ: " + task.getTitle(),
                    "/admin/tasks?taskId=" + task.getId());
        } catch (Exception e) {
            log.warn("Không thể gửi thông báo bình luận mới cho task {}: {}", task.getId(), e.getMessage());
        }
    }

    @Transactional
    public TaskComment writeAuditLog(Task task, User actor, String content, Integer relatedSubmissionRound) {
        TaskComment auditLog = new TaskComment();
        auditLog.setTask(task);
        auditLog.setUser(actor);
        auditLog.setContent(content);
        auditLog.setType(TaskCommentType.AUDIT_LOG);
        auditLog.setRelatedSubmissionRound(relatedSubmissionRound);

        return commentRepository.save(auditLog);
    }

    @Transactional(readOnly = true)
    public List<ResTaskCommentDTO> fetchCommentsByTask(Long taskId) {
        String currentUserId = SecurityUtil.getCurrentUserId()
                .orElseThrow(() -> new IdInvalidException("Bạn chưa đăng nhập"));

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IdInvalidException("Tác vụ không tồn tại"));

        taskAccessService.assertCanViewTask(task, currentUserId);

        List<TaskComment> list = commentRepository.findByTaskIdOrderByCreatedAtDesc(taskId);
        return list.stream().map(this::convertToResDTO).collect(Collectors.toList());
    }

    public ResTaskCommentDTO convertToResDTO(TaskComment comment) {
        ResTaskCommentDTO dto = new ResTaskCommentDTO();
        dto.setId(comment.getId());
        dto.setTaskId(comment.getTask().getId());

        if (comment.getUser() != null) {
            dto.setUserId(comment.getUser().getId());
            dto.setUserName(comment.getUser().getName());
            dto.setUserAvatar(comment.getUser().getAvatar());
        }

        dto.setContent(comment.getContent());
        dto.setAttachmentUrl(comment.getAttachmentUrl());
        dto.setType(comment.getType());
        dto.setRelatedSubmissionRound(comment.getRelatedSubmissionRound());
        dto.setCreatedAt(comment.getCreatedAt());

        return dto;
    }
}
