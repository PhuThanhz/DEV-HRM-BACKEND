package vn.system.app.modules.task.service;

import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.ScopeSpec;
import vn.system.app.common.util.SecurityUtil;
import vn.system.app.common.util.UserScopeContext;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.approvaldelegation.service.ApprovalDelegationService;
import vn.system.app.modules.department.domain.Department;
import vn.system.app.modules.department.repository.DepartmentRepository;
import vn.system.app.modules.jd.jobdescriptiontask.repository.JobDescriptionTaskItemRepository;
import vn.system.app.modules.jd.jobdescriptiontask.repository.JobDescriptionTaskRepository;
import vn.system.app.modules.notification.service.NotificationService;
import vn.system.app.modules.task.domain.*;
import vn.system.app.modules.task.domain.enums.TaskParticipantRole;
import vn.system.app.modules.task.domain.enums.TaskPriority;
import vn.system.app.modules.task.domain.enums.TaskStatus;
import vn.system.app.modules.task.domain.request.*;
import vn.system.app.modules.task.domain.response.*;
import vn.system.app.modules.task.repository.*;
import vn.system.app.modules.user.domain.User;
import vn.system.app.modules.user.repository.UserRepository;
import vn.system.app.modules.userposition.domain.UserPosition;
import vn.system.app.modules.userposition.repository.UserPositionRepository;

@Slf4j
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskParticipantRepository taskParticipantRepository;
    private final TaskChecklistRepository taskChecklistRepository;
    private final TaskCommentRepository taskCommentRepository;
    private final TaskAttachmentRepository taskAttachmentRepository;
    private final TaskSubmissionRepository taskSubmissionRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final JobDescriptionTaskRepository jobDescriptionTaskRepository;
    private final JobDescriptionTaskItemRepository jobDescriptionTaskItemRepository;
    private final UserPositionRepository userPositionRepository;

    private final TaskCommentService taskCommentService;
    private final TaskChecklistService taskChecklistService;
    private final TaskAttachmentService taskAttachmentService;
    private final TaskSubmissionService taskSubmissionService;
    private final TaskExtensionRequestService taskExtensionRequestService;
    private final ApprovalDelegationService approvalDelegationService;
    private final NotificationService notificationService;
    private final TaskAccessService taskAccessService;

    public TaskService(
            TaskRepository taskRepository,
            TaskParticipantRepository taskParticipantRepository,
            TaskChecklistRepository taskChecklistRepository,
            TaskCommentRepository taskCommentRepository,
            TaskAttachmentRepository taskAttachmentRepository,
            TaskSubmissionRepository taskSubmissionRepository,
            UserRepository userRepository,
            DepartmentRepository departmentRepository,
            JobDescriptionTaskRepository jobDescriptionTaskRepository,
            JobDescriptionTaskItemRepository jobDescriptionTaskItemRepository,
            UserPositionRepository userPositionRepository,
            TaskCommentService taskCommentService,
            TaskChecklistService taskChecklistService,
            TaskAttachmentService taskAttachmentService,
            TaskSubmissionService taskSubmissionService,
            TaskExtensionRequestService taskExtensionRequestService,
            ApprovalDelegationService approvalDelegationService,
            NotificationService notificationService,
            TaskAccessService taskAccessService) {
        this.taskRepository = taskRepository;
        this.taskParticipantRepository = taskParticipantRepository;
        this.taskChecklistRepository = taskChecklistRepository;
        this.taskCommentRepository = taskCommentRepository;
        this.taskAttachmentRepository = taskAttachmentRepository;
        this.taskSubmissionRepository = taskSubmissionRepository;
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
        this.jobDescriptionTaskRepository = jobDescriptionTaskRepository;
        this.jobDescriptionTaskItemRepository = jobDescriptionTaskItemRepository;
        this.userPositionRepository = userPositionRepository;
        this.taskCommentService = taskCommentService;
        this.taskChecklistService = taskChecklistService;
        this.taskAttachmentService = taskAttachmentService;
        this.taskSubmissionService = taskSubmissionService;
        this.taskExtensionRequestService = taskExtensionRequestService;
        this.approvalDelegationService = approvalDelegationService;
        this.notificationService = notificationService;
        this.taskAccessService = taskAccessService;
    }

    /*
     * =====================================================
     * CREATE TASK
     * =====================================================
     */
    @Transactional
    public ResTaskDTO handleCreate(ReqCreateTaskDTO req) {

        String currentUserId = getCurrentUserIdOrThrow();
        User creator = userRepository.findById(currentUserId)
                .orElseThrow(() -> new IdInvalidException("Tài khoản tạo tác vụ không tồn tại"));

        if (!creator.isActive()) {
            throw new IdInvalidException("Tài khoản của bạn đã bị khóa hoặc ngưng hoạt động");
        }

        // Rule 3: dueDate không ở quá khứ khi tạo mới (status = TODO)
        if (req.getDueDate() != null && req.getDueDate().isBefore(Instant.now())) {
            throw new IdInvalidException("Hạn chót không được ở quá khứ khi tạo mới tác vụ");
        }
        if (req.getStartDate() != null && req.getDueDate() != null && req.getStartDate().isAfter(req.getDueDate())) {
            throw new IdInvalidException("Ngày bắt đầu không được sau hạn chót");
        }

        // Rule 4: estimatedHours >= 0
        if (req.getEstimatedHours() != null && req.getEstimatedHours() < 0) {
            throw new IdInvalidException("Thời gian ước tính phải lớn hơn hoặc bằng 0");
        }

        // Rule 1: Single Assignee
        if (req.getAssigneeId() == null || req.getAssigneeId().isBlank()) {
            throw new IdInvalidException("Người thực hiện chính không được để trống");
        }

        User assignee = userRepository.findById(req.getAssigneeId())
                .orElseThrow(() -> new IdInvalidException("Người thực hiện chính không tồn tại"));

        // Rule 5: Participant phải đang active
        if (!assignee.isActive()) {
            throw new IdInvalidException("Người thực hiện chính đã ngưng hoạt động");
        }

        // Clean & deduplicate collaborator & observer lists (tự động loại trừ người giao việc nếu có vì người giao việc đã là Creator)
        List<String> collabIds = sanitizeUserIds(req.getCollaboratorIds()).stream()
                .filter(id -> !id.equals(creator.getId()))
                .collect(Collectors.toList());
        List<String> obsIds = sanitizeUserIds(req.getObserverIds()).stream()
                .filter(id -> !id.equals(creator.getId()) && !collabIds.contains(id))
                .collect(Collectors.toList());

        // Rule 2: 1 user không giữ 2 role thi công/giám sát xung đột
        if (collabIds.contains(assignee.getId()) || obsIds.contains(assignee.getId())) {
            throw new IdInvalidException("Người thực hiện chính không thể đồng thời làm Người phối hợp hoặc Người quan sát");
        }

        if (req.getDueDate() == null) {
            throw new IdInvalidException("Vui lòng chọn Hạn chót hoàn thành cho tác vụ");
        }

        // Rule 5: Validate collaborator and observer users exist and are active
        List<User> collaborators = validateAndFetchActiveUsers(collabIds, "Người phối hợp");
        List<User> observers = validateAndFetchActiveUsers(obsIds, "Người quan sát");

        // Rule 6: Creator participant scope check
        validateParticipantScope(creator, assignee, collaborators, observers);

        // Validate JD task reference if provided
        if (req.getJobDescriptionTaskId() != null) {
            if (!jobDescriptionTaskRepository.existsById(req.getJobDescriptionTaskId())) {
                throw new IdInvalidException("Nhiệm vụ JD không tồn tại");
            }
        }
        validateJdTaskItem(req.getJobDescriptionTaskId(), req.getJobDescriptionTaskItemId());

        // Determine department (explicitly selected or creator's default department)
        Department targetDepartment;
        if (req.getDepartmentId() != null) {
            targetDepartment = departmentRepository.findById(req.getDepartmentId())
                    .orElseThrow(() -> new IdInvalidException("Phòng ban không tồn tại với ID: " + req.getDepartmentId()));
        } else {
            targetDepartment = findCreatorDepartment(creator);
        }

        // Build & save Task
        Task task = new Task();
        task.setTitle(req.getTitle().trim());
        task.setDescription(req.getDescription());
        task.setPriority(req.getPriority() != null ? req.getPriority() : TaskPriority.MEDIUM);
        task.setStatus(TaskStatus.TODO);
        task.setStartDate(req.getStartDate());
        task.setDueDate(req.getDueDate());
        task.setEstimatedHours(req.getEstimatedHours() != null ? req.getEstimatedHours() : 0.0);
        task.setReworkCount(0);
        task.setJobDescriptionTaskId(req.getJobDescriptionTaskId());
        task.setJobDescriptionTaskItemId(req.getJobDescriptionTaskItemId());
        task.setDepartment(targetDepartment);

        Task savedTask = taskRepository.save(task);

        // Save participants
        List<TaskParticipant> participants = new ArrayList<>();

        // CREATOR
        TaskParticipant pCreator = new TaskParticipant();
        pCreator.setTask(savedTask);
        pCreator.setUser(creator);
        pCreator.setRole(TaskParticipantRole.CREATOR);
        participants.add(pCreator);

        // ASSIGNEE
        TaskParticipant pAssignee = new TaskParticipant();
        pAssignee.setTask(savedTask);
        pAssignee.setUser(assignee);
        pAssignee.setRole(TaskParticipantRole.ASSIGNEE);
        participants.add(pAssignee);

        // COLLABORATORS
        for (User collabUser : collaborators) {
            TaskParticipant pCollab = new TaskParticipant();
            pCollab.setTask(savedTask);
            pCollab.setUser(collabUser);
            pCollab.setRole(TaskParticipantRole.COLLABORATOR);
            participants.add(pCollab);
        }

        // OBSERVERS
        for (User obsUser : observers) {
            TaskParticipant pObs = new TaskParticipant();
            pObs.setTask(savedTask);
            pObs.setUser(obsUser);
            pObs.setRole(TaskParticipantRole.OBSERVER);
            participants.add(pObs);
        }

        taskParticipantRepository.saveAll(participants);

        // Write Audit Log & Notify Assignee
        taskCommentService.writeAuditLog(savedTask, creator, "Đã giao tác vụ cho " + assignee.getName(), null);
        notifyUser(assignee.getId(), "TASK_ASSIGNED", creator.getName() + " đã giao cho bạn tác vụ: " + savedTask.getTitle(), "/admin/tasks?taskId=" + savedTask.getId());
        notifyAddedParticipants(savedTask, creator, collaborators, observers);

        return convertToResDTO(savedTask, participants);
    }

    /*
     * =====================================================
     * UPDATE TASK
     * =====================================================
     */
    @Transactional
    public ResTaskDTO handleUpdate(Long id, ReqUpdateTaskDTO req) {

        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new IdInvalidException("Tác vụ không tồn tại với ID: " + id));

        // Rule 8: PUT chặn sửa khi status COMPLETED / CANCELLED
        if (task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.CANCELLED) {
            throw new IdInvalidException("Không thể chỉnh sửa tác vụ đã hoàn thành hoặc đã bị hủy");
        }

        // Rule 13: IDOR check — chỉ Creator hoặc Admin được sửa
        checkCreatorOrAdminPermission(task, "Bạn không có quyền chỉnh sửa tác vụ này");

        User actor = userRepository.findById(getCurrentUserIdOrThrow()).orElse(null);

        // Rule 3: StartDate <= DueDate
        if (req.getStartDate() != null && req.getDueDate() != null && req.getStartDate().isAfter(req.getDueDate())) {
            throw new IdInvalidException("Ngày bắt đầu không được sau hạn chót");
        }

        // Rule 4: estimatedHours >= 0
        if (req.getEstimatedHours() != null && req.getEstimatedHours() < 0) {
            throw new IdInvalidException("Thời gian ước tính phải lớn hơn hoặc bằng 0");
        }

        // Rule 1: Single Assignee
        if (req.getAssigneeId() == null || req.getAssigneeId().isBlank()) {
            throw new IdInvalidException("Người thực hiện chính không được để trống");
        }

        User newAssignee = userRepository.findById(req.getAssigneeId())
                .orElseThrow(() -> new IdInvalidException("Người thực hiện chính không tồn tại"));

        // Rule 5: Active user
        if (!newAssignee.isActive()) {
            throw new IdInvalidException("Người thực hiện chính đã ngưng hoạt động");
        }

        // Fetch original Creator of the task
        TaskParticipant creatorParticipant = taskParticipantRepository.findByTaskIdAndRole(task.getId(), TaskParticipantRole.CREATOR)
                .orElse(null);
        User creator = creatorParticipant != null ? creatorParticipant.getUser() : null;

        List<String> collabIds = sanitizeUserIds(req.getCollaboratorIds()).stream()
                .filter(uId -> (creator == null || !uId.equals(creator.getId())) && !uId.equals(newAssignee.getId()))
                .collect(Collectors.toList());
        List<String> obsIds = sanitizeUserIds(req.getObserverIds()).stream()
                .filter(uId -> (creator == null || !uId.equals(creator.getId())) && !uId.equals(newAssignee.getId()) && !collabIds.contains(uId))
                .collect(Collectors.toList());

        List<User> collaborators = validateAndFetchActiveUsers(collabIds, "Người phối hợp");
        List<User> observers = validateAndFetchActiveUsers(obsIds, "Người quan sát");

        // Rule 6: Creator participant scope check
        validateParticipantScope(creator, newAssignee, collaborators, observers);

        // Check if Assignee changed
        TaskParticipant oldAssigneeParticipant = taskParticipantRepository.findByTaskIdAndRole(task.getId(), TaskParticipantRole.ASSIGNEE).orElse(null);
        boolean assigneeChanged = oldAssigneeParticipant != null && !oldAssigneeParticipant.getUser().getId().equals(newAssignee.getId());

        // Rule 14: Chỉ được đổi Người thực hiện chính khi tác vụ còn ở trạng thái TODO
        if (assigneeChanged && task.getStatus() != TaskStatus.TODO) {
            throw new IdInvalidException("Không thể đổi người thực hiện chính sau khi tác vụ đã bắt đầu triển khai (chỉ được đổi khi tác vụ còn ở trạng thái Chưa thực hiện)");
        }

        // Check if DueDate changed
        Instant oldDueDate = task.getDueDate();
        boolean dueDateChanged = (oldDueDate == null && req.getDueDate() != null) || (oldDueDate != null && !oldDueDate.equals(req.getDueDate()));

        // Validate JD task reference if provided
        if (req.getJobDescriptionTaskId() != null) {
            if (!jobDescriptionTaskRepository.existsById(req.getJobDescriptionTaskId())) {
                throw new IdInvalidException("Nhiệm vụ JD không tồn tại");
            }
        }
        validateJdTaskItem(req.getJobDescriptionTaskId(), req.getJobDescriptionTaskItemId());

        if (req.getDepartmentId() != null) {
            Department targetDepartment = departmentRepository.findById(req.getDepartmentId())
                    .orElseThrow(() -> new IdInvalidException("Phòng ban không tồn tại với ID: " + req.getDepartmentId()));
            task.setDepartment(targetDepartment);
        }

        // Update task attributes
        task.setTitle(req.getTitle().trim());
        task.setDescription(req.getDescription());
        if (req.getPriority() != null) {
            task.setPriority(req.getPriority());
        }
        task.setStartDate(req.getStartDate());
        task.setDueDate(req.getDueDate());
        if (req.getEstimatedHours() != null) {
            task.setEstimatedHours(req.getEstimatedHours());
        }

        // If Assignee changed, reset jobDescriptionTaskId + Audit Log + Notify
        if (assigneeChanged) {
            task.setJobDescriptionTaskId(null);
            task.setJobDescriptionTaskItemId(null);
            taskCommentService.writeAuditLog(task, actor, "Đã đổi người thực hiện chính thành " + newAssignee.getName() + " (đã gỡ liên kết Nhiệm vụ JD cũ)", null);
            notifyUser(newAssignee.getId(), "TASK_ASSIGNED", actor.getName() + " đã giao cho bạn tác vụ: " + task.getTitle(), "/admin/tasks?taskId=" + task.getId());
        } else if (req.getJobDescriptionTaskId() != null) {
            task.setJobDescriptionTaskId(req.getJobDescriptionTaskId());
            task.setJobDescriptionTaskItemId(req.getJobDescriptionTaskItemId());
        }

        // If DueDate changed, Audit Log + Notify
        if (dueDateChanged) {
            notifyDueDateChanged(task, actor, newAssignee.getId());
        }

        // Chụp lại danh sách Phối hợp/Quan sát cũ trước khi xoá, để chỉ thông báo cho
        // người MỚI được thêm vào (tránh spam mỗi lần sửa task mà không đổi người).
        Set<String> oldCollabObsUserIds = taskParticipantRepository.findByTaskId(task.getId()).stream()
                .filter(p -> p.getRole() == TaskParticipantRole.COLLABORATOR || p.getRole() == TaskParticipantRole.OBSERVER)
                .map(p -> p.getUser().getId())
                .collect(Collectors.toSet());

        Task savedTask = taskRepository.save(task);

        // CREATOR không đổi/xoá được qua PUT — giữ nguyên CREATOR, chỉ xoá các role khác
        taskParticipantRepository.deleteByTaskIdAndRoleNot(savedTask.getId(), TaskParticipantRole.CREATOR);

        List<TaskParticipant> updatedParticipants = new ArrayList<>();
        List<TaskParticipant> existingCreator = taskParticipantRepository.findByTaskId(savedTask.getId());
        updatedParticipants.addAll(existingCreator);

        // Re-add ASSIGNEE
        TaskParticipant pAssignee = new TaskParticipant();
        pAssignee.setTask(savedTask);
        pAssignee.setUser(newAssignee);
        pAssignee.setRole(TaskParticipantRole.ASSIGNEE);
        updatedParticipants.add(pAssignee);

        // Re-add COLLABORATORS
        for (User collabUser : collaborators) {
            TaskParticipant pCollab = new TaskParticipant();
            pCollab.setTask(savedTask);
            pCollab.setUser(collabUser);
            pCollab.setRole(TaskParticipantRole.COLLABORATOR);
            updatedParticipants.add(pCollab);
        }

        // Re-add OBSERVERS
        for (User obsUser : observers) {
            TaskParticipant pObs = new TaskParticipant();
            pObs.setTask(savedTask);
            pObs.setUser(obsUser);
            pObs.setRole(TaskParticipantRole.OBSERVER);
            updatedParticipants.add(pObs);
        }

        taskParticipantRepository.saveAll(updatedParticipants);

        List<User> newlyAddedCollaborators = collaborators.stream()
                .filter(u -> !oldCollabObsUserIds.contains(u.getId()))
                .collect(Collectors.toList());
        List<User> newlyAddedObservers = observers.stream()
                .filter(u -> !oldCollabObsUserIds.contains(u.getId()))
                .collect(Collectors.toList());
        notifyAddedParticipants(savedTask, creator != null ? creator : actor, newlyAddedCollaborators, newlyAddedObservers);

        return convertToResDTO(savedTask, updatedParticipants);
    }

    /*
     * =====================================================
     * PATCH STATUS (TODO -> IN_PROGRESS ONLY)
     * =====================================================
     */
    @Transactional
    public ResTaskDTO updateStatus(Long id, TaskStatus newStatus) {

        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new IdInvalidException("Tác vụ không tồn tại với ID: " + id));

        // Rule 13: IDOR check — Creator, Assignee, hoặc Admin được đổi status
        checkParticipantOrAdminPermission(task, "Bạn không có quyền thay đổi trạng thái tác vụ này");

        // Rule 7: PATCH /status chỉ cho TODO -> IN_PROGRESS ở Phase 1/2
        if (task.getStatus() != TaskStatus.TODO || newStatus != TaskStatus.IN_PROGRESS) {
            throw new IdInvalidException("Thao tác này chỉ hỗ trợ chuyển trạng thái từ TODO sang IN_PROGRESS");
        }

        User actor = userRepository.findById(getCurrentUserIdOrThrow()).orElse(null);

        task.setStatus(TaskStatus.IN_PROGRESS);
        Task updatedTask = taskRepository.save(task);

        taskCommentService.writeAuditLog(updatedTask, actor, "Đã bắt đầu làm tác vụ (TODO -> IN_PROGRESS)", null);

        // Thông báo WebSocket cho Người giao việc (Creator) nếu người thực hiện khác người tạo
        TaskParticipant creatorP = taskParticipantRepository.findByTaskIdAndRole(id, TaskParticipantRole.CREATOR).orElse(null);
        if (creatorP != null && creatorP.getUser() != null) {
            String creatorId = creatorP.getUser().getId();
            if (actor == null || !creatorId.equals(actor.getId())) {
                notifyUser(creatorId, "TASK_STARTED", (actor != null ? actor.getName() : "Nhân viên") + " đã bắt đầu thực hiện tác vụ: " + task.getTitle(), "/admin/tasks?taskId=" + task.getId());
            }
        }

        List<TaskParticipant> participants = taskParticipantRepository.findByTaskId(updatedTask.getId());
        return convertToResDTO(updatedTask, participants);
    }

    /*
     * =====================================================
     * PATCH DUE DATE (KANBAN DEADLINE DRAG - CHỈ ĐỔI HẠN CHÓT)
     * =====================================================
     */
    @Transactional
    public ResTaskDTO updateDueDate(Long id, Instant newDueDate) {

        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new IdInvalidException("Tác vụ không tồn tại với ID: " + id));

        // Rule 8: chặn sửa khi status COMPLETED / CANCELLED
        if (task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.CANCELLED) {
            throw new IdInvalidException("Không thể chỉnh sửa tác vụ đã hoàn thành hoặc đã bị hủy");
        }

        // Rule 13: IDOR check — chỉ Creator hoặc Admin được sửa
        checkCreatorOrAdminPermission(task, "Bạn không có quyền chỉnh sửa tác vụ này");

        // Rule 3: StartDate <= DueDate
        if (newDueDate != null && task.getStartDate() != null && task.getStartDate().isAfter(newDueDate)) {
            throw new IdInvalidException("Hạn chót không được đứng trước ngày bắt đầu của tác vụ");
        }

        Instant oldDueDate = task.getDueDate();
        boolean dueDateChanged = (oldDueDate == null && newDueDate != null)
                || (oldDueDate != null && !oldDueDate.equals(newDueDate));

        task.setDueDate(newDueDate);
        Task savedTask = taskRepository.save(task);

        if (dueDateChanged) {
            User actor = userRepository.findById(getCurrentUserIdOrThrow()).orElse(null);
            String assigneeId = taskParticipantRepository.findByTaskIdAndRole(savedTask.getId(), TaskParticipantRole.ASSIGNEE)
                    .map(p -> p.getUser().getId())
                    .orElse(null);
            notifyDueDateChanged(savedTask, actor, assigneeId);
        }

        List<TaskParticipant> participants = taskParticipantRepository.findByTaskId(savedTask.getId());
        return convertToResDTO(savedTask, participants);
    }

    /*
     * =====================================================
     * SUBMIT RESULT (BƯỚC 3: IN_PROGRESS / REWORK -> PENDING_REVIEW)
     * =====================================================
     */
    @Transactional
    public ResTaskDTO submitResult(Long id, ReqSubmitResultDTO req) {

        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new IdInvalidException("Tác vụ không tồn tại với ID: " + id));

        String currentUserId = getCurrentUserIdOrThrow();
        User actor = userRepository.findById(currentUserId)
                .orElseThrow(() -> new IdInvalidException("Tài khoản người dùng không tồn tại"));

        // Only Assignee or Admin can submit result
        boolean isAssignee = taskParticipantRepository.existsByTaskIdAndUserIdAndRole(id, currentUserId, TaskParticipantRole.ASSIGNEE);
        if (!isAssignee && !isAdminOrSuperAdmin()) {
            throw new IdInvalidException("Chỉ Người thực hiện chính mới được phép nộp báo cáo kết quả");
        }

        if (task.getStatus() != TaskStatus.IN_PROGRESS && task.getStatus() != TaskStatus.REWORK) {
            throw new IdInvalidException("Chỉ được nộp báo cáo khi tác vụ đang ở trạng thái Đang làm (IN_PROGRESS) hoặc Yêu cầu làm lại (REWORK)");
        }

        // Calculate next submission round
        Optional<TaskSubmission> topSub = taskSubmissionRepository.findTopByTaskIdOrderBySubmissionRoundDesc(id);
        int nextRound = topSub.map(s -> s.getSubmissionRound() + 1).orElse(1);

        // Save TaskSubmission
        TaskSubmission sub = new TaskSubmission();
        sub.setTask(task);
        sub.setSubmissionRound(nextRound);
        sub.setResultSummary(req.getResultSummary().trim());
        sub.setDeliverables(trimToNull(req.getDeliverables()));
        sub.setIssues(trimToNull(req.getIssues()));
        sub.setNextSteps(trimToNull(req.getNextSteps()));
        sub.setSubmittedBy(actor);

        taskSubmissionRepository.save(sub);

        // Save result attachments if provided
        if (req.getAttachments() != null && !req.getAttachments().isEmpty()) {
            for (ReqSubmitResultDTO.AttachmentInput att : req.getAttachments()) {
                if (att.getFileName() != null && !att.getFileName().isBlank()) {
                    taskAttachmentService.registerResultAttachment(
                            task.getId(),
                            att.getFileName(),
                            att.getFolder(),
                            att.getFileSize(),
                            nextRound
                    );
                }
            }
        }

        // Update task status to PENDING_REVIEW
        task.setStatus(TaskStatus.PENDING_REVIEW);
        Task updatedTask = taskRepository.save(task);

        // Audit log
        taskCommentService.writeAuditLog(updatedTask, actor, "Đã nộp báo cáo kết quả hoàn thành tác vụ (Lần nộp thứ " + nextRound + ")", nextRound);

        // Notify Creator
        TaskParticipant creatorP = taskParticipantRepository.findByTaskIdAndRole(id, TaskParticipantRole.CREATOR).orElse(null);
        if (creatorP != null && creatorP.getUser() != null) {
            String creatorId = creatorP.getUser().getId();
            notifyUser(creatorId, "TASK_SUBMITTED", actor.getName() + " đã nộp báo cáo kết quả cho tác vụ: " + task.getTitle(), "/admin/tasks?taskId=" + task.getId());
        }

        List<TaskParticipant> participants = taskParticipantRepository.findByTaskId(updatedTask.getId());
        return convertToResDTO(updatedTask, participants);
    }

    /*
     * =====================================================
     * APPROVE / REWORK (BƯỚC 4: PENDING_REVIEW -> COMPLETED / REWORK)
     * =====================================================
     */
    @Transactional
    public ResTaskDTO approve(Long id, ReqApproveTaskDTO req) {

        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new IdInvalidException("Tác vụ không tồn tại với ID: " + id));

        if (task.getStatus() != TaskStatus.PENDING_REVIEW) {
            throw new IdInvalidException("Chỉ được thẩm định / phê duyệt khi tác vụ đang ở trạng thái Chờ duyệt (PENDING_REVIEW)");
        }

        String currentUserId = getCurrentUserIdOrThrow();
        User actor = userRepository.findById(currentUserId)
                .orElseThrow(() -> new IdInvalidException("Tài khoản người dùng không tồn tại"));

        TaskParticipant creatorP = taskParticipantRepository.findByTaskIdAndRole(id, TaskParticipantRole.CREATOR).orElse(null);
        String creatorId = creatorP != null && creatorP.getUser() != null ? creatorP.getUser().getId() : "";

        boolean isCreator = creatorId.equals(currentUserId);
        boolean isDelegate = approvalDelegationService.canActAsDelegate("TASK", creatorId, currentUserId);
        boolean isAdmin = isAdminOrSuperAdmin();

        if (!isCreator && !isDelegate && !isAdmin) {
            throw new IdInvalidException("Bạn không có quyền thẩm định / phê duyệt tác vụ này (chỉ Người giao việc hoặc người được ủy quyền duyệt)");
        }

        Optional<TaskSubmission> topSub = taskSubmissionRepository.findTopByTaskIdOrderBySubmissionRoundDesc(id);
        int currentRound = topSub.map(TaskSubmission::getSubmissionRound).orElse(1);

        TaskParticipant assigneeP = taskParticipantRepository.findByTaskIdAndRole(id, TaskParticipantRole.ASSIGNEE).orElse(null);
        String assigneeId = assigneeP != null && assigneeP.getUser() != null ? assigneeP.getUser().getId() : null;

        if (req.getDecision() != null && req.getDecision().equalsIgnoreCase("APPROVE")) {
            // Approve -> COMPLETED
            task.setStatus(TaskStatus.COMPLETED);
            Instant completedNow = Instant.now();
            task.setCompletedAt(completedNow);
            boolean isOnTime = task.getDueDate() == null || !completedNow.isAfter(task.getDueDate());
            task.setIsOnTime(isOnTime);

            Task updatedTask = taskRepository.save(task);

            taskCommentService.writeAuditLog(updatedTask, actor, "Đã phê duyệt nghiệm thu tác vụ thành công", currentRound);

            if (assigneeId != null) {
                notifyUser(assigneeId, "TASK_APPROVED", actor.getName() + " đã phê duyệt hoàn thành tác vụ: " + task.getTitle(), "/admin/tasks?taskId=" + task.getId());
            }

            List<TaskParticipant> participants = taskParticipantRepository.findByTaskId(updatedTask.getId());
            return convertToResDTO(updatedTask, participants);

        } else if (req.getDecision() != null && req.getDecision().equalsIgnoreCase("REWORK")) {
            // Rework -> REWORK
            if (req.getReworkReason() == null || req.getReworkReason().isBlank()) {
                throw new IdInvalidException("Lý do yêu cầu làm lại không được để trống");
            }

            String reason = req.getReworkReason().trim();
            task.setStatus(TaskStatus.REWORK);
            task.setReworkReason(reason);
            task.setReworkCount(task.getReworkCount() != null ? task.getReworkCount() + 1 : 1);

            Task updatedTask = taskRepository.save(task);

            taskCommentService.writeAuditLog(updatedTask, actor, "Yêu cầu làm lại: " + reason, currentRound);

            if (assigneeId != null) {
                notifyUser(assigneeId, "TASK_REWORK", actor.getName() + " yêu cầu làm lại tác vụ: " + task.getTitle() + ". Lý do: " + reason, "/admin/tasks?taskId=" + task.getId());
            }

            List<TaskParticipant> participants = taskParticipantRepository.findByTaskId(updatedTask.getId());
            return convertToResDTO(updatedTask, participants);
        } else {
            throw new IdInvalidException("Quyết định phê duyệt không hợp lệ (chỉ chấp nhận APPROVE hoặc REWORK)");
        }
    }

    /*
     * =====================================================
     * CANCEL TASK (SOFT)
     * =====================================================
     */
    @Transactional
    public void cancel(Long id) {

        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new IdInvalidException("Tác vụ không tồn tại với ID: " + id));

        // Rule 13: IDOR check — Creator hoặc Admin
        checkCreatorOrAdminPermission(task, "Bạn không có quyền hủy tác vụ này");

        // Rule 10: Hủy từ mọi status trừ COMPLETED / CANCELLED
        if (task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.CANCELLED) {
            throw new IdInvalidException("Không thể hủy tác vụ đã hoàn thành hoặc đã bị hủy trước đó");
        }

        User actor = userRepository.findById(getCurrentUserIdOrThrow()).orElse(null);

        task.setStatus(TaskStatus.CANCELLED);
        taskRepository.save(task);

        taskCommentService.writeAuditLog(task, actor, "Đã hủy tác vụ", null);
    }

    /*
     * =====================================================
     * DELETE TASK (HARD - ONLY WHEN TODO)
     * =====================================================
     */
    @Transactional
    public void delete(Long id) {

        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new IdInvalidException("Tác vụ không tồn tại với ID: " + id));

        // Rule 13: IDOR check — Creator hoặc Admin
        checkCreatorOrAdminPermission(task, "Bạn không có quyền xóa tác vụ này");

        // Rule 11: Hard-delete chỉ khi status = TODO
        if (task.getStatus() != TaskStatus.TODO) {
            throw new IdInvalidException("Chỉ được phép xóa cứng tác vụ khi đang ở trạng thái TODO");
        }

        taskChecklistRepository.deleteByTaskId(task.getId());
        taskCommentRepository.deleteByTaskId(task.getId());
        taskAttachmentRepository.deleteByTaskId(task.getId());
        taskSubmissionRepository.deleteByTaskId(task.getId());
        taskParticipantRepository.deleteByTaskId(task.getId());

        taskRepository.delete(task);
    }

    /*
     * =====================================================
     * GET ONE (DETAIL)
     * =====================================================
     */
    @Transactional(readOnly = true)
    public ResTaskDetailDTO fetchOne(Long id) {

        Task task = taskRepository.findById(id)
                .orElseThrow(() -> new IdInvalidException("Tác vụ không tồn tại với ID: " + id));

        List<TaskParticipant> participants = taskParticipantRepository.findByTaskId(task.getId());

        // Security / IDOR Check: Chặn xem chi tiết nếu không phải participant và ngoài phạm vi quản lý (company/department) thực tế của task
        String currentUserId = getCurrentUserIdOrThrow();
        taskAccessService.assertCanViewTask(task, currentUserId);

        Map<Long, String> jdTaskTitleMap = new HashMap<>();
        if (task.getJobDescriptionTaskId() != null) {
            jobDescriptionTaskRepository.findById(task.getJobDescriptionTaskId())
                    .ifPresent(jdTask -> jdTaskTitleMap.put(jdTask.getId(), jdTask.getTitle()));
        }
        Map<Long, String> jdTaskItemContentMap = new HashMap<>();
        if (task.getJobDescriptionTaskItemId() != null) {
            jobDescriptionTaskItemRepository.findById(task.getJobDescriptionTaskItemId())
                    .ifPresent(jdItem -> jdTaskItemContentMap.put(jdItem.getId(), jdItem.getContent()));
        }
        ResTaskDTO taskDTO = convertToResDTO(task, participants, null, jdTaskTitleMap, jdTaskItemContentMap);

        ResTaskDetailDTO detailDTO = new ResTaskDetailDTO();
        BeanUtils.copyProperties(taskDTO, detailDTO);

        List<ResTaskDetailDTO.TaskParticipantDTO> collaborators = new ArrayList<>();
        List<ResTaskDetailDTO.TaskParticipantDTO> observers = new ArrayList<>();

        for (TaskParticipant tp : participants) {
            User u = tp.getUser();
            ResTaskDetailDTO.TaskParticipantDTO pUser = new ResTaskDetailDTO.TaskParticipantDTO(
                    tp.getId(),
                    u.getId(),
                    u.getName(),
                    u.getEmail(),
                    u.getAvatar(),
                    tp.getRole()
            );

            if (tp.getRole() == TaskParticipantRole.COLLABORATOR) {
                collaborators.add(pUser);
            } else if (tp.getRole() == TaskParticipantRole.OBSERVER) {
                observers.add(pUser);
            }
        }

        detailDTO.setCollaborators(collaborators);
        detailDTO.setObservers(observers);

        // Phase 2 Detail Lists: Checklists, Comments, Submissions, Attachments
        detailDTO.setChecklists(taskChecklistService.fetchChecklistsByTask(id));
        detailDTO.setComments(taskCommentService.fetchCommentsByTask(id));
        detailDTO.setSubmissions(taskSubmissionService.fetchSubmissionsByTask(id));
        detailDTO.setAttachments(taskAttachmentService.fetchAttachmentsByTask(id));
        detailDTO.setExtensionRequests(taskExtensionRequestService.fetchExtensionsByTask(id));

        return detailDTO;
    }

    /*
     * =====================================================
     * GET ALL (PAGINATION + TAB FILTER + SCOPE)
     * =====================================================
     */
    @Transactional(readOnly = true)
    public ResultPaginationDTO fetchAll(Specification<Task> spec, Pageable pageable, String tab) {
        Specification<Task> finalSpec = applyVisibilityScope(spec, tab);

        Page<Task> pageData = taskRepository.findAll(finalSpec, pageable);

        List<Task> tasks = pageData.getContent();
        List<Long> taskIds = tasks.stream().map(Task::getId).collect(Collectors.toList());

        // Batch fetch participants, checklists, and latest submissions for list denormalization to prevent N+1 query
        Map<Long, List<TaskParticipant>> participantMap = new HashMap<>();
        Map<Long, Integer> doneChecklistMap = new HashMap<>();
        Map<Long, Integer> totalChecklistMap = new HashMap<>();
        Map<Long, TaskSubmission> latestSubmissionMap = new HashMap<>();
        Map<Long, String> jdTaskTitleMap = new HashMap<>();
        Map<Long, String> jdTaskItemContentMap = new HashMap<>();

        if (!taskIds.isEmpty()) {
            List<TaskParticipant> allParticipants = taskParticipantRepository.findByTaskIdIn(taskIds);
            for (TaskParticipant tp : allParticipants) {
                participantMap.computeIfAbsent(tp.getTask().getId(), k -> new ArrayList<>()).add(tp);
            }

            List<TaskChecklist> allChecklists = taskChecklistRepository.findByTaskIdIn(taskIds);
            for (TaskChecklist chk : allChecklists) {
                Long tId = chk.getTask().getId();
                totalChecklistMap.put(tId, totalChecklistMap.getOrDefault(tId, 0) + 1);
                if (chk.isCompleted()) {
                    doneChecklistMap.put(tId, doneChecklistMap.getOrDefault(tId, 0) + 1);
                }
            }

            List<TaskSubmission> allSubmissions = taskSubmissionRepository.findByTaskIdInOrderByTaskIdAscSubmissionRoundDesc(taskIds);
            for (TaskSubmission sub : allSubmissions) {
                Long tId = sub.getTask().getId();
                latestSubmissionMap.putIfAbsent(tId, sub);
            }

            List<Long> jdTaskIds = tasks.stream()
                    .map(Task::getJobDescriptionTaskId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            if (!jdTaskIds.isEmpty()) {
                jobDescriptionTaskRepository.findAllById(jdTaskIds)
                        .forEach(jdTask -> jdTaskTitleMap.put(jdTask.getId(), jdTask.getTitle()));
            }

            List<Long> jdTaskItemIds = tasks.stream()
                    .map(Task::getJobDescriptionTaskItemId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            if (!jdTaskItemIds.isEmpty()) {
                jobDescriptionTaskItemRepository.findAllById(jdTaskItemIds)
                        .forEach(jdItem -> jdTaskItemContentMap.put(jdItem.getId(), jdItem.getContent()));
            }
        }

        List<ResTaskDTO> resList = tasks.stream()
                .map(t -> {
                    ResTaskDTO dto = convertToResDTO(t, participantMap.getOrDefault(t.getId(), Collections.emptyList()), latestSubmissionMap, jdTaskTitleMap, jdTaskItemContentMap);
                    int totalChk = totalChecklistMap.getOrDefault(t.getId(), 0);
                    int doneChk = doneChecklistMap.getOrDefault(t.getId(), 0);
                    dto.setChecklistTotalCount(totalChk);
                    dto.setChecklistDoneCount(doneChk);
                    dto.setProgress(totalChk > 0 ? (int) Math.round((double) doneChk * 100 / totalChk) : null);
                    return dto;
                })
                .collect(Collectors.toList());

        ResultPaginationDTO rs = new ResultPaginationDTO();
        ResultPaginationDTO.Meta meta = new ResultPaginationDTO.Meta();
        meta.setPage(pageable.getPageNumber() + 1);
        meta.setPageSize(pageable.getPageSize());
        meta.setPages(pageData.getTotalPages());
        meta.setTotal(pageData.getTotalElements());

        rs.setMeta(meta);
        rs.setResult(resList);

        return rs;
    }

    @Transactional(readOnly = true)
    public List<ResTaskDTO> fetchCalendar(
            Specification<Task> spec,
            Instant from,
            Instant to,
            String tab) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IdInvalidException("Khoảng thời gian lịch không hợp lệ");
        }
        if (Duration.between(from, to).toDays() > 370) {
            throw new IdInvalidException("Khoảng thời gian lịch không được vượt quá 370 ngày");
        }

        Specification<Task> dueDateRangeSpec = (root, query, cb) ->
                cb.between(root.get("dueDate"), from, to);
        Specification<Task> finalSpec = applyVisibilityScope(
                Specification.where(spec).and(dueDateRangeSpec),
                tab
        );

        Sort calendarSort = Sort.by(
                Sort.Order.asc("dueDate"),
                Sort.Order.desc("createdAt"),
                Sort.Order.desc("id")
        );
        List<Task> tasks = taskRepository.findAll(finalSpec, calendarSort);
        if (tasks.isEmpty()) {
            return List.of();
        }

        List<Long> taskIds = tasks.stream().map(Task::getId).toList();
        Map<Long, List<TaskParticipant>> participantMap = taskParticipantRepository
                .findByTaskIdIn(taskIds)
                .stream()
                .collect(Collectors.groupingBy(participant -> participant.getTask().getId()));

        return tasks.stream()
                .map(task -> convertToCalendarDTO(
                        task,
                        participantMap.getOrDefault(task.getId(), List.of())
                ))
                .toList();
    }

    /*
     * =====================================================
     * HELPER METHODS
     * =====================================================
     */

    private Specification<Task> applyVisibilityScope(Specification<Task> spec, String tab) {
        String currentUserId = SecurityUtil.getCurrentUserId().orElse("");
        UserScopeContext.UserScope scope = UserScopeContext.get();
        Specification<Task> finalSpec = Specification.where(spec);

        boolean isSuperAdminOrAdmin = scope != null && (scope.isSuperAdmin() || scope.isAdminLevel());

        // Specific tab: filter by exact participant role for everyone
        if (tab != null && !tab.isBlank() && !tab.equalsIgnoreCase("ALL")) {
            TaskParticipantRole roleToFilter = parseTabToRole(tab);
            Specification<Task> participantSpec = (root, query, cb) -> {
                var sub = query.subquery(Long.class);
                var participantRoot = sub.from(TaskParticipant.class);
                sub.select(participantRoot.get("task").get("id"))
                        .where(cb.and(
                                cb.equal(participantRoot.get("user").get("id"), currentUserId),
                                cb.equal(participantRoot.get("role"), roleToFilter)
                        ));
                return root.get("id").in(sub);
            };
            return finalSpec.and(participantSpec);
        }

        // Tab ALL / no tab
        if (isSuperAdminOrAdmin) {
            // SuperAdmin và Admin thấy toàn bộ task — không lọc thêm
            return finalSpec;
        }

        // Mọi user còn lại (DEPARTMENT_MANAGER, EMPLOYEE, ...): "Tất cả các vai trò"
        // = union 4 tab (participant với bất kỳ role nào) HOẶC task nằm trong phạm vi
        // quản lý phòng ban/công ty của user — đồng bộ với TaskAccessService.canViewTask
        // để quản lý thấy được task cấp dưới ngay ở danh sách/kanban, không chỉ khi mở chi tiết.
        Specification<Task> anyParticipantSpec = (root, query, cb) -> {
            var sub = query.subquery(Long.class);
            var participantRoot = sub.from(TaskParticipant.class);
            sub.select(participantRoot.get("task").get("id"))
                    .where(cb.equal(participantRoot.get("user").get("id"), currentUserId));
            return root.get("id").in(sub);
        };

        Specification<Task> managementScopeSpec = buildManagementScopeSpec(scope);
        if (managementScopeSpec == null) {
            return finalSpec.and(anyParticipantSpec);
        }
        Specification<Task> visibilitySpec = anyParticipantSpec.or(managementScopeSpec);
        return finalSpec.and(visibilitySpec);
    }

    /**
     * Điều kiện "task nằm trong phạm vi quản lý phòng ban/công ty" — tương đương
     * {@link TaskAccessService#isTaskInManagementScope}, viết dưới dạng Specification
     * để dùng được ở tầng list (springfilter) thay vì chỉ ở tầng detail.
     * - isDepartmentLevel (DEPARTMENT_MANAGER, ADMIN_SUB_3): chỉ lọc theo departmentIds
     *   (phòng ban họ quản lý) — KHÔNG dùng companyIds, vì companyIds ở role này chỉ là
     *   "công ty chứa phòng ban họ quản lý" (dùng cho mục đích khác), không phải phạm vi
     *   được xem toàn công ty.
     * - isCompanyLevel (ADMIN_SUB_2): lọc theo companyIds (công ty họ quản lý).
     * User thường (không phải 2 loại trên) không được coi là có phạm vi quản lý task, dù
     * companyIds/departmentIds của họ cũng có giá trị (chỉ để phục vụ mục đích khác).
     */
    private Specification<Task> buildManagementScopeSpec(UserScopeContext.UserScope scope) {
        if (scope == null) {
            return null;
        }
        boolean hasDeptScope = scope.isDepartmentLevel() && scope.departmentIds() != null && !scope.departmentIds().isEmpty();
        boolean hasCompanyScope = scope.isCompanyLevel() && scope.companyIds() != null && !scope.companyIds().isEmpty();
        if (!hasDeptScope && !hasCompanyScope) {
            return null;
        }
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (hasDeptScope) {
                predicates.add(root.get("department").get("id").in(scope.departmentIds()));
            }
            if (hasCompanyScope) {
                predicates.add(root.get("department").get("company").get("id").in(scope.companyIds()));
            }
            return cb.or(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    private ResTaskDTO convertToCalendarDTO(Task task, List<TaskParticipant> participants) {
        ResTaskDTO dto = new ResTaskDTO();
        dto.setId(task.getId());
        dto.setTitle(task.getTitle());
        dto.setDescription(task.getDescription());
        dto.setStatus(task.getStatus());
        dto.setPriority(task.getPriority());
        dto.setStartDate(task.getStartDate());
        dto.setDueDate(task.getDueDate());
        dto.setCompletedAt(task.getCompletedAt());
        dto.setIsOnTime(task.getIsOnTime());
        dto.setJobDescriptionTaskId(task.getJobDescriptionTaskId());
        dto.setJobDescriptionTaskItemId(task.getJobDescriptionTaskItemId());
        dto.setCreatedAt(task.getCreatedAt());

        if (task.getJobDescriptionTask() != null) {
            dto.setJobDescriptionTaskTitle(task.getJobDescriptionTask().getTitle());
        }
        if (task.getJobDescriptionTaskItem() != null) {
            dto.setJobDescriptionTaskItemContent(task.getJobDescriptionTaskItem().getContent());
        }
        if (task.getDepartment() != null) {
            dto.setDepartmentId(task.getDepartment().getId());
            dto.setDepartmentName(task.getDepartment().getName());
            if (task.getDepartment().getCompany() != null) {
                dto.setCompanyId(task.getDepartment().getCompany().getId());
                dto.setCompanyName(task.getDepartment().getCompany().getName());
            }
        }

        boolean overdue = task.getDueDate() != null
                && task.getDueDate().isBefore(Instant.now())
                && task.getStatus() != TaskStatus.COMPLETED
                && task.getStatus() != TaskStatus.CANCELLED;
        dto.setOverdue(overdue);

        for (TaskParticipant participant : participants) {
            User participantUser = participant.getUser();
            if (participant.getRole() == TaskParticipantRole.ASSIGNEE) {
                dto.setAssigneeId(participantUser.getId());
                dto.setAssigneeName(participantUser.getName());
                dto.setAssigneeAvatar(participantUser.getAvatar());
                dto.setAssigneeActive(participantUser.isActive());
            } else if (participant.getRole() == TaskParticipantRole.CREATOR) {
                dto.setCreatorId(participantUser.getId());
                dto.setCreatorName(participantUser.getName());
                dto.setCreatorAvatar(participantUser.getAvatar());
            }
        }
        return dto;
    }

    private String getCurrentUserIdOrThrow() {
        return SecurityUtil.getCurrentUserId()
                .orElseThrow(() -> new IdInvalidException("Bạn chưa đăng nhập hoặc token không hợp lệ"));
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private List<String> sanitizeUserIds(List<String> rawList) {
        if (rawList == null) return Collections.emptyList();
        return rawList.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    private void validateJdTaskItem(Long jobDescriptionTaskId, Long jobDescriptionTaskItemId) {
        if (jobDescriptionTaskItemId == null) {
            return;
        }
        vn.system.app.modules.jd.jobdescriptiontask.domain.JobDescriptionTaskItem item = jobDescriptionTaskItemRepository
                .findById(jobDescriptionTaskItemId)
                .orElseThrow(() -> new IdInvalidException("Mục con của Nhiệm vụ JD không tồn tại"));

        if (jobDescriptionTaskId != null && !item.getJobDescriptionTask().getId().equals(jobDescriptionTaskId)) {
            throw new IdInvalidException("Mục con không thuộc Nhiệm vụ JD đã chọn");
        }
    }

    private List<User> validateAndFetchActiveUsers(List<String> userIds, String roleLabel) {
        if (userIds.isEmpty()) return Collections.emptyList();

        List<User> users = userRepository.findAllById(userIds);
        if (users.size() != userIds.size()) {
            throw new IdInvalidException("Danh sách " + roleLabel + " chứa người dùng không tồn tại");
        }

        for (User u : users) {
            if (!u.isActive()) {
                throw new IdInvalidException(roleLabel + " '" + u.getName() + "' (ID: " + u.getId() + ") đã ngưng hoạt động");
            }
        }

        return users;
    }

    private void validateParticipantScope(User creator, User assignee, List<User> collaborators, List<User> observers) {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope == null || scope.isSuperAdmin() || scope.isAdminLevel()) {
            return;
        }

        boolean hasDeptScope = scope.departmentIds() != null && !scope.departmentIds().isEmpty();
        boolean hasCompanyScope = scope.companyIds() != null && !scope.companyIds().isEmpty();
        if (!hasDeptScope && !hasCompanyScope) {
            throw new IdInvalidException("Bạn chưa được gán phòng ban/công ty nào trong hệ thống, không thể tạo hoặc giao việc cho người khác. Vui lòng liên hệ quản trị viên để được gán phòng ban.");
        }

        Set<String> allowedUserIds = new HashSet<>();

        if (hasDeptScope) {
            List<String> deptUserIds = userPositionRepository.findUserIdsByDepartmentIdsWithSubSections(scope.departmentIds());
            allowedUserIds.addAll(deptUserIds);
        } else {
            List<String> compUserIds = userPositionRepository.findUserIdsByCompanyIds(scope.companyIds());
            allowedUserIds.addAll(compUserIds);
        }

        if (creator != null) {
            allowedUserIds.add(creator.getId());
        }

        if (assignee != null && !allowedUserIds.contains(assignee.getId())) {
            throw new IdInvalidException("Người thực hiện chính '" + assignee.getName() + "' nằm ngoài phạm vi quản lý của bạn");
        }

        for (User collab : collaborators) {
            if (!allowedUserIds.contains(collab.getId())) {
                throw new IdInvalidException("Người phối hợp '" + collab.getName() + "' nằm ngoài phạm vi quản lý của bạn");
            }
        }

        for (User obs : observers) {
            if (!allowedUserIds.contains(obs.getId())) {
                throw new IdInvalidException("Người quan sát '" + obs.getName() + "' nằm ngoài phạm vi quản lý của bạn");
            }
        }
    }

    private Department findCreatorDepartment(User creator) {
        List<UserPosition> positions = userPositionRepository.findByUser_IdAndActiveTrue(creator.getId());
        for (UserPosition pos : positions) {
            if (pos.getDepartmentJobTitle() != null && pos.getDepartmentJobTitle().getDepartment() != null) {
                return pos.getDepartmentJobTitle().getDepartment();
            }
            if (pos.getSectionJobTitle() != null && pos.getSectionJobTitle().getSection() != null
                    && pos.getSectionJobTitle().getSection().getDepartment() != null) {
                return pos.getSectionJobTitle().getSection().getDepartment();
            }
        }

        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope != null && scope.departmentIds() != null && !scope.departmentIds().isEmpty()) {
            Long deptId = scope.departmentIds().iterator().next();
            return departmentRepository.findById(deptId)
                    .orElseThrow(() -> new IdInvalidException("Phòng ban của người tạo không tồn tại"));
        }

        throw new IdInvalidException("Người tạo tác vụ chưa được gán vào phòng ban nào trong hệ thống");
    }

    private void checkCreatorOrAdminPermission(Task task, String errorMessage) {
        if (isAdminOrSuperAdmin()) return;

        String currentUserId = SecurityUtil.getCurrentUserId().orElse("");
        boolean isCreator = taskParticipantRepository.existsByTaskIdAndUserIdAndRole(
                task.getId(), currentUserId, TaskParticipantRole.CREATOR);

        if (!isCreator) {
            throw new IdInvalidException(errorMessage);
        }
    }

    private void checkParticipantOrAdminPermission(Task task, String errorMessage) {
        if (isAdminOrSuperAdmin()) return;

        String currentUserId = SecurityUtil.getCurrentUserId().orElse("");
        boolean isCreator = taskParticipantRepository.existsByTaskIdAndUserIdAndRole(
                task.getId(), currentUserId, TaskParticipantRole.CREATOR);
        boolean isAssignee = taskParticipantRepository.existsByTaskIdAndUserIdAndRole(
                task.getId(), currentUserId, TaskParticipantRole.ASSIGNEE);

        if (!isCreator && !isAssignee) {
            throw new IdInvalidException(errorMessage);
        }
    }

    private boolean isAdminOrSuperAdmin() {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        return scope != null && (scope.isSuperAdmin() || scope.isAdminLevel());
    }

    private TaskParticipantRole parseTabToRole(String tab) {
        if (tab == null) return TaskParticipantRole.ASSIGNEE;
        return switch (tab.toUpperCase()) {
            case "CREATED", "MY_CREATED" -> TaskParticipantRole.CREATOR;
            case "COLLABORATING", "MY_COLLABORATING" -> TaskParticipantRole.COLLABORATOR;
            case "OBSERVING", "MY_OBSERVING" -> TaskParticipantRole.OBSERVER;
            default -> TaskParticipantRole.ASSIGNEE;
        };
    }

    private void notifyUser(String userId, String type, String content, String actionLink) {
        try {
            notificationService.sendNotification(userId, "TASK", type, content, actionLink);
        } catch (Exception e) {
            log.warn("Không thể gửi thông báo TASK/{} cho user {}: {}", type, userId, e.getMessage());
        }
    }

    private void notifyDueDateChanged(Task task, User actor, String assigneeId) {
        taskCommentService.writeAuditLog(task, actor, "Đã gia hạn/thay đổi hạn chót tác vụ", null);
        notifyUser(assigneeId, "TASK_DEADLINE_CHANGED", actor.getName() + " đã thay đổi hạn chót tác vụ: " + task.getTitle(), "/admin/tasks?taskId=" + task.getId());
    }

    private void notifyAddedParticipants(Task task, User actor, List<User> collaborators, List<User> observers) {
        String actorName = actor != null ? actor.getName() : "Người giao việc";
        for (User collab : collaborators) {
            notifyUser(collab.getId(), "TASK_ADDED_AS_COLLABORATOR", actorName + " đã thêm bạn làm Người phối hợp cho tác vụ: " + task.getTitle(), "/admin/tasks?taskId=" + task.getId());
        }
        for (User obs : observers) {
            notifyUser(obs.getId(), "TASK_ADDED_AS_OBSERVER", actorName + " đã thêm bạn làm Người quan sát cho tác vụ: " + task.getTitle(), "/admin/tasks?taskId=" + task.getId());
        }
    }

    public ResTaskDTO convertToResDTO(Task task, List<TaskParticipant> participants) {
        return convertToResDTO(task, participants, null, null, null);
    }

    public ResTaskDTO convertToResDTO(Task task, List<TaskParticipant> participants, Map<Long, TaskSubmission> latestSubmissionMap) {
        return convertToResDTO(task, participants, latestSubmissionMap, null, null);
    }

    public ResTaskDTO convertToResDTO(
            Task task,
            List<TaskParticipant> participants,
            Map<Long, TaskSubmission> latestSubmissionMap,
            Map<Long, String> jdTaskTitleMap) {
        return convertToResDTO(task, participants, latestSubmissionMap, jdTaskTitleMap, null);
    }

    public ResTaskDTO convertToResDTO(
            Task task,
            List<TaskParticipant> participants,
            Map<Long, TaskSubmission> latestSubmissionMap,
            Map<Long, String> jdTaskTitleMap,
            Map<Long, String> jdTaskItemContentMap) {
        ResTaskDTO dto = new ResTaskDTO();
        dto.setId(task.getId());
        dto.setTitle(task.getTitle());
        dto.setDescription(task.getDescription());
        dto.setStatus(task.getStatus());
        dto.setPriority(task.getPriority());
        dto.setStartDate(task.getStartDate());
        dto.setDueDate(task.getDueDate());
        dto.setCompletedAt(task.getCompletedAt());
        dto.setIsOnTime(task.getIsOnTime());
        dto.setEstimatedHours(task.getEstimatedHours());
        dto.setReworkCount(task.getReworkCount());
        dto.setReworkReason(task.getReworkReason());
        dto.setJobDescriptionTaskId(task.getJobDescriptionTaskId());
        if (task.getJobDescriptionTaskId() != null && jdTaskTitleMap != null) {
            dto.setJobDescriptionTaskTitle(jdTaskTitleMap.get(task.getJobDescriptionTaskId()));
        }
        dto.setJobDescriptionTaskItemId(task.getJobDescriptionTaskItemId());
        if (task.getJobDescriptionTaskItemId() != null && jdTaskItemContentMap != null) {
            dto.setJobDescriptionTaskItemContent(jdTaskItemContentMap.get(task.getJobDescriptionTaskItemId()));
        }
        if (task.getDepartment() != null) {
            dto.setDepartmentId(task.getDepartment().getId());
            dto.setDepartmentName(task.getDepartment().getName());
            if (task.getDepartment().getCompany() != null) {
                dto.setCompanyId(task.getDepartment().getCompany().getId());
                dto.setCompanyName(task.getDepartment().getCompany().getName());
            }
        }

        boolean isOverdue = task.getDueDate() != null
                && task.getDueDate().isBefore(Instant.now())
                && task.getStatus() != TaskStatus.COMPLETED
                && task.getStatus() != TaskStatus.CANCELLED;
        dto.setOverdue(isOverdue);

        // Fetch latest submission preview (using pre-fetched map if available to prevent N+1 query)
        if (latestSubmissionMap != null) {
            TaskSubmission sub = latestSubmissionMap.get(task.getId());
            if (sub != null) {
                dto.setLatestResultSummary(sub.getResultSummary());
            }
        } else {
            Optional<TaskSubmission> topSub = taskSubmissionRepository.findTopByTaskIdOrderBySubmissionRoundDesc(task.getId());
            topSub.ifPresent(sub -> dto.setLatestResultSummary(sub.getResultSummary()));
        }

        dto.setVersion(task.getVersion());
        dto.setCreatedAt(task.getCreatedAt());
        dto.setUpdatedAt(task.getUpdatedAt());
        dto.setCreatedBy(task.getCreatedBy());
        dto.setUpdatedBy(task.getUpdatedBy());

        int collabCount = 0;
        int obsCount = 0;

        for (TaskParticipant p : participants) {
            User u = p.getUser();
            if (p.getRole() == TaskParticipantRole.CREATOR) {
                dto.setCreatorId(u.getId());
                dto.setCreatorName(u.getName());
                dto.setCreatorAvatar(u.getAvatar());
            } else if (p.getRole() == TaskParticipantRole.ASSIGNEE) {
                dto.setAssigneeId(u.getId());
                dto.setAssigneeName(u.getName());
                dto.setAssigneeAvatar(u.getAvatar());
                dto.setAssigneeActive(u.isActive());
            } else if (p.getRole() == TaskParticipantRole.COLLABORATOR) {
                collabCount++;
            } else if (p.getRole() == TaskParticipantRole.OBSERVER) {
                obsCount++;
            }
        }

        dto.setCollaboratorCount(collabCount);
        dto.setObserverCount(obsCount);

        return dto;
    }
}
