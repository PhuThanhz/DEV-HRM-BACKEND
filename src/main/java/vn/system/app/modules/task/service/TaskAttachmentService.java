package vn.system.app.modules.task.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.common.util.SecurityUtil;
import vn.system.app.common.util.UserScopeContext;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.task.domain.Task;
import vn.system.app.modules.task.domain.TaskAttachment;
import vn.system.app.modules.task.domain.enums.TaskAttachmentCategory;
import vn.system.app.modules.task.domain.enums.TaskParticipantRole;
import vn.system.app.modules.task.domain.enums.TaskStatus;
import vn.system.app.modules.task.domain.request.ReqRegisterTaskAttachmentDTO;
import vn.system.app.modules.task.domain.response.ResTaskAttachmentDTO;
import vn.system.app.modules.task.repository.TaskAttachmentRepository;
import vn.system.app.modules.task.repository.TaskParticipantRepository;
import vn.system.app.modules.task.repository.TaskRepository;
import vn.system.app.modules.user.domain.User;
import vn.system.app.modules.user.repository.UserRepository;

@Service
public class TaskAttachmentService {

    private final TaskAttachmentRepository attachmentRepository;
    private final TaskRepository taskRepository;
    private final UserRepository userRepository;
    private final TaskParticipantRepository participantRepository;
    private final TaskAccessService taskAccessService;

    public TaskAttachmentService(
            TaskAttachmentRepository attachmentRepository,
            TaskRepository taskRepository,
            UserRepository userRepository,
            TaskParticipantRepository participantRepository,
            TaskAccessService taskAccessService) {
        this.attachmentRepository = attachmentRepository;
        this.taskRepository = taskRepository;
        this.userRepository = userRepository;
        this.participantRepository = participantRepository;
        this.taskAccessService = taskAccessService;
    }

    @Transactional
    public ResTaskAttachmentDTO registerGeneralAttachment(
            Long taskId,
            ReqRegisterTaskAttachmentDTO req) {

        String currentUserId = SecurityUtil.getCurrentUserId()
                .orElseThrow(() -> new IdInvalidException("Bạn chưa đăng nhập"));

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IdInvalidException("Tác vụ không tồn tại"));

        User actor = userRepository.findById(currentUserId)
                .orElseThrow(() -> new IdInvalidException("Tài khoản người dùng không tồn tại"));

        ensureTaskAllowsGeneralAttachment(task);

        boolean isCreator = hasRole(taskId, currentUserId, TaskParticipantRole.CREATOR);
        boolean isAssignee = hasRole(taskId, currentUserId, TaskParticipantRole.ASSIGNEE);
        boolean isCollaborator = hasRole(taskId, currentUserId, TaskParticipantRole.COLLABORATOR);
        boolean isAdmin = isAdminOrSuperAdmin(actor);

        TaskAttachmentCategory category = req.getAttachmentCategory();
        if (category == null) {
            category = isCreator || isAdmin
                    ? TaskAttachmentCategory.GUIDANCE
                    : TaskAttachmentCategory.WORKING;
        }

        if (category == TaskAttachmentCategory.RESULT) {
            throw new IdInvalidException("Tệp báo cáo kết quả chỉ được tải lên trong luồng Nộp báo cáo");
        }

        if (category == TaskAttachmentCategory.GUIDANCE && !isCreator && !isAdmin) {
            throw new IdInvalidException("Chỉ Người giao việc hoặc Quản trị viên được tải Tài liệu giao việc");
        }

        if (category == TaskAttachmentCategory.WORKING
                && !isAssignee
                && !isCollaborator
                && !isAdmin) {
            throw new IdInvalidException("Chỉ Người thực hiện, Người phối hợp hoặc Quản trị viên được tải Tệp làm việc chung");
        }

        return saveAttachment(
                task,
                actor,
                req.getFileName(),
                req.getFolder(),
                req.getFileSize(),
                category,
                1
        );
    }

    @Transactional
    public ResTaskAttachmentDTO registerResultAttachment(
            Long taskId,
            String fileName,
            String folder,
            Long fileSize,
            Integer submissionRound) {

        String currentUserId = SecurityUtil.getCurrentUserId()
                .orElseThrow(() -> new IdInvalidException("Bạn chưa đăng nhập"));

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IdInvalidException("Tác vụ không tồn tại"));

        User actor = userRepository.findById(currentUserId)
                .orElseThrow(() -> new IdInvalidException("Tài khoản người dùng không tồn tại"));

        boolean isAssignee = hasRole(taskId, currentUserId, TaskParticipantRole.ASSIGNEE);
        if (!isAssignee && !isAdminOrSuperAdmin(actor)) {
            throw new IdInvalidException("Chỉ Người thực hiện chính được tải tệp báo cáo kết quả");
        }

        return saveAttachment(
                task,
                actor,
                fileName,
                folder,
                fileSize,
                TaskAttachmentCategory.RESULT,
                submissionRound
        );
    }

    private ResTaskAttachmentDTO saveAttachment(
            Task task,
            User actor,
            String fileName,
            String folder,
            Long fileSize,
            TaskAttachmentCategory category,
            Integer submissionRound) {

        if (fileName == null || fileName.isBlank()) {
            throw new IdInvalidException("Tên file không được để trống");
        }

        TaskAttachment attachment = new TaskAttachment();
        attachment.setTask(task);
        attachment.setFileName(fileName.trim());
        attachment.setFolder(folder != null && !folder.isBlank() ? folder.trim() : "documents");
        attachment.setFileSize(fileSize != null ? fileSize : 0L);
        attachment.setUploadedBy(actor);
        attachment.setAttachmentCategory(category);
        attachment.setResultAttachment(category == TaskAttachmentCategory.RESULT);
        attachment.setSubmissionRound(
                category == TaskAttachmentCategory.RESULT && submissionRound != null
                        ? submissionRound
                        : 1
        );

        TaskAttachment saved = attachmentRepository.save(attachment);
        return convertToResDTO(saved);
    }

    @Transactional(readOnly = true)
    public List<ResTaskAttachmentDTO> fetchAttachmentsByTask(Long taskId) {
        String currentUserId = SecurityUtil.getCurrentUserId()
                .orElseThrow(() -> new IdInvalidException("Bạn chưa đăng nhập"));

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IdInvalidException("Tác vụ không tồn tại"));

        User actor = userRepository.findById(currentUserId)
                .orElseThrow(() -> new IdInvalidException("Tài khoản người dùng không tồn tại"));

        if (!taskAccessService.canViewTask(task, currentUserId) && !isAdminRole(actor)) {
            throw new IdInvalidException("Bạn không có quyền xem tệp đính kèm của tác vụ này");
        }

        List<TaskAttachment> list = attachmentRepository.findByTaskIdOrderByCreatedAtDesc(taskId);
        return list.stream().map(this::convertToResDTO).collect(Collectors.toList());
    }

    /**
     * Trả về null nếu file không phải Task attachment (caller phải tự kiểm tra nguồn khác),
     * trả về true/false nếu xác định được đây là Task attachment.
     */
    @Transactional(readOnly = true)
    public Boolean checkAccessIfTaskAttachment(String fileName, String folder) {
        TaskAttachment attachment = attachmentRepository.findFirstByFileNameAndFolder(fileName, folder)
                .orElse(null);
        if (attachment == null) {
            return null;
        }

        String currentUserId = SecurityUtil.getCurrentUserId().orElse(null);
        if (currentUserId == null) {
            return false;
        }

        User actor = userRepository.findById(currentUserId).orElse(null);
        return taskAccessService.canViewTask(attachment.getTask(), currentUserId)
                || isAdminRole(actor);
    }

    public ResTaskAttachmentDTO convertToResDTO(TaskAttachment attachment) {
        ResTaskAttachmentDTO dto = new ResTaskAttachmentDTO();
        dto.setId(attachment.getId());
        dto.setTaskId(attachment.getTask().getId());
        dto.setFileName(attachment.getFileName());
        dto.setFolder(attachment.getFolder());
        dto.setFileSize(attachment.getFileSize());

        if (attachment.getUploadedBy() != null) {
            dto.setUploadedById(attachment.getUploadedBy().getId());
            dto.setUploadedByName(attachment.getUploadedBy().getName());
        }

        dto.setAttachmentCategory(resolveCategory(attachment));
        dto.setResultAttachment(attachment.isResultAttachment());
        dto.setSubmissionRound(attachment.getSubmissionRound());
        dto.setCreatedAt(attachment.getCreatedAt());

        return dto;
    }

    private void ensureTaskAllowsGeneralAttachment(Task task) {
        if (task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.CANCELLED) {
            throw new IdInvalidException("Không thể tải thêm tệp cho tác vụ đã hoàn thành hoặc đã hủy");
        }
    }

    private boolean hasRole(Long taskId, String userId, TaskParticipantRole role) {
        return participantRepository.existsByTaskIdAndUserIdAndRole(taskId, userId, role);
    }

    private boolean isAdminOrSuperAdmin(User actor) {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        return (scope != null && (scope.isSuperAdmin() || scope.isAdminLevel()))
                || isAdminRole(actor);
    }

    private boolean isAdminRole(User actor) {
        if (actor == null || actor.getRole() == null || actor.getRole().getName() == null) {
            return false;
        }
        String roleName = actor.getRole().getName().toUpperCase();
        return "SUPER_ADMIN".equals(roleName) || "ADMIN_SUB_1".equals(roleName);
    }

    private TaskAttachmentCategory resolveCategory(TaskAttachment attachment) {
        if (attachment.getAttachmentCategory() != null) {
            return attachment.getAttachmentCategory();
        }
        if (attachment.isResultAttachment()) {
            return TaskAttachmentCategory.RESULT;
        }

        User uploader = attachment.getUploadedBy();
        if (uploader != null) {
            Long taskId = attachment.getTask().getId();
            if (hasRole(taskId, uploader.getId(), TaskParticipantRole.CREATOR)
                    || isAdminRole(uploader)) {
                return TaskAttachmentCategory.GUIDANCE;
            }
        }
        return TaskAttachmentCategory.WORKING;
    }
}
