package vn.system.app.modules.jd.jdflow.service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.common.util.SecurityUtil;
import vn.system.app.common.util.UserScopeContext;
import vn.system.app.common.util.error.PermissionException;
import vn.system.app.modules.jd.jdflow.domain.JdFlow;
import vn.system.app.modules.jd.jdflow.domain.JdFlowLog;
import vn.system.app.modules.jd.jdflow.domain.response.ResJdApproverDTO;
import vn.system.app.modules.jd.jdflow.domain.response.ResJdFlowDTO;
import vn.system.app.modules.jd.jdflow.domain.response.ResJdInboxDTO;
import vn.system.app.modules.jd.jdflow.repository.JdFlowRepository;
import vn.system.app.modules.jd.jobdescription.domain.JobDescription;
import vn.system.app.modules.jd.jobdescription.repository.JobDescriptionRepository;
import vn.system.app.modules.notification.service.NotificationService;
import vn.system.app.modules.user.domain.User;
import vn.system.app.modules.user.repository.UserRepository;
import vn.system.app.modules.userposition.repository.UserPositionRepository;

@Service
public class JdFlowService {

    private final JdFlowRepository jdFlowRepository;
    private final UserRepository userRepository;
    private final JobDescriptionRepository jobDescriptionRepository;
    private final JdFlowLogService logService;
    private final JdPermissionService permissionService;
    private final UserPositionRepository userPositionRepository;
    private final NotificationService notificationService;

    public JdFlowService(
            JdFlowRepository jdFlowRepository,
            UserRepository userRepository,
            JobDescriptionRepository jobDescriptionRepository,
            JdFlowLogService logService,
            JdPermissionService permissionService,
            UserPositionRepository userPositionRepository,
            NotificationService notificationService) {

        this.jdFlowRepository = jdFlowRepository;
        this.userRepository = userRepository;
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.logService = logService;
        this.permissionService = permissionService;
        this.userPositionRepository = userPositionRepository;
        this.notificationService = notificationService;
    }

    /**
     * Kiểm tra phạm vi truy cập của người dùng
     */
    private void validateScope(Long companyId) {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope == null)
            throw new PermissionException("Không xác định được phạm vi truy cập của người dùng");

        if (scope.isSuperAdmin() || scope.isAdminLevel())
            return;

        if (companyId == null) {
            throw new PermissionException("Chỉ Quản trị viên hệ thống mới có quyền thao tác dữ liệu toàn cục");
        }

        if (scope.isCompanyLevel()) {
            if (scope.companyIds() == null || !scope.companyIds().contains(companyId)) {
                throw new PermissionException("Bạn không có quyền thao tác dữ liệu cho công ty này");
            }
        } else {
            throw new PermissionException("Bạn không có quyền thực hiện thao tác này");
        }
    }

    /*
     * ==========================================
     * FETCH FLOW BY JD
     * ==========================================
     */
    public ResJdFlowDTO fetchFlowByJd(Long jdId) {
        JdFlow flow = jdFlowRepository.findByJobDescriptionId(jdId);
        if (flow == null)
            return null;
        validateScope(flow.getJobDescription().getCompany() != null ? flow.getJobDescription().getCompany().getId() : null);
        return convertToDTO(flow);
    }

    /*
     * ==========================================
     * SUBMIT JD / GỬI LẠI DUYỆT
     * Khi JD bị REJECTED → hỗ trợ 2 nút:
     * 1. Gửi lại cho người vừa từ chối → returnToPrevious = false
     * 2. Gửi về người trước đó + mang lý do → returnToPrevious = true
     * ==========================================
     */
    @Transactional
    public JdFlow submitFlow(Long jdId, String nextUserId, Boolean returnToPrevious, String comment) {

        JobDescription jd = jobDescriptionRepository.findById(jdId)
                .orElseThrow(() -> new RuntimeException("JD không tồn tại"));

        validateScope(jd.getCompany() != null ? jd.getCompany().getId() : null);

        String email = SecurityUtil.getCurrentUserLogin().orElse("");
        User fromUser = userRepository.findByEmail(email);

        if (fromUser == null)
            throw new RuntimeException("Không xác định được người gửi JD");

        if ("PUBLISHED".equals(jd.getStatus()))
            throw new RuntimeException("JD đã ban hành, không thể gửi duyệt");

        String finalComment = comment;

        // ✅ Chặn re-route flow đang xử lý: chỉ người đang giữ JD (currentUser)
        // mới được submit tiếp; JD hoàn toàn mới (chưa có flow) thì chỉ người tạo
        // hoặc Admin mới được gửi duyệt lần đầu, tránh bị người khác chiếm quyền submit
        if (!"REJECTED".equals(jd.getStatus())) {
            JdFlow existingFlowCheck = jdFlowRepository.findByJobDescriptionId(jdId);
            if (existingFlowCheck == null) {
                UserScopeContext.UserScope scope = UserScopeContext.get();
                boolean isAdmin = scope != null && (scope.isSuperAdmin() || scope.isAdminLevel());
                if (!isAdmin && !email.equals(jd.getCreatedBy())) {
                    throw new RuntimeException("Chỉ người tạo JD mới có quyền gửi duyệt lần đầu");
                }
            } else if (existingFlowCheck.getCurrentUser() != null
                    && !fromUser.getId().equals(existingFlowCheck.getCurrentUser().getId())) {
                throw new RuntimeException("Bạn không phải người đang giữ JD này, không có quyền gửi duyệt");
            }
        }

        // ================== XỬ LÝ GỬI LẠI SAU KHI BỊ REJECTED ==================
        if ("REJECTED".equals(jd.getStatus())) {
            // ✅ CHỈ currentUser mới được gửi lại
            JdFlow existingFlow = jdFlowRepository.findByJobDescriptionId(jdId);
            if (existingFlow == null)
                throw new RuntimeException("JD Flow không tồn tại");
            if (!fromUser.getId().equals(existingFlow.getCurrentUser().getId()))
                throw new RuntimeException("Bạn không phải người được trả JD về, không có quyền gửi lại");

            User targetUser;

            if (Boolean.TRUE.equals(returnToPrevious)) {
                // === GỬI VỀ NGƯỜI TRƯỚC ===

                if (comment == null || comment.trim().isEmpty()) {
                    throw new RuntimeException("Phải nhập lý do khi gửi về người trước");
                }

                targetUser = logService.findLastSenderBeforeReject(jdId);

                if (targetUser == null)
                    throw new RuntimeException("Không tìm thấy người duyệt trước đó");

                finalComment = "[TRẢ VỀ] " + comment;

                nextUserId = targetUser.getId();

            } else {
                // === GỬI LẠI CHO NGƯỜI TỪ CHỐI ===

                targetUser = logService.findLastSender(jdId);

                if (targetUser == null)
                    throw new RuntimeException("Không tìm thấy người từ chối gần nhất");
            }

            // Không cho phép gửi lại cho chính mình
            if (targetUser.getId().equals(fromUser.getId()))
                throw new RuntimeException("Bạn không thể gửi lại JD cho chính mình sau khi bị từ chối");

            // 🔥 LUÔN dùng user backend tính
            nextUserId = targetUser.getId();
        }

        if (nextUserId == null)
            throw new RuntimeException("Người duyệt tiếp theo không được để trống");

        User toUser = userRepository.findById(nextUserId)
                .orElseThrow(() -> new RuntimeException("User không tồn tại"));

        if (jd.getCompany() != null && !isUserInCompany(toUser, jd.getCompany().getId())) {
            throw new RuntimeException("Người nhận không thuộc công ty quản lý của JD này");
        }

        if (fromUser.getId().equals(toUser.getId()))
            throw new RuntimeException("Không thể gửi JD duyệt cho chính mình");

        User submitUserCheck = logService.findSubmitUser(jdId);
        boolean isSendingBackToCreator = Boolean.TRUE.equals(returnToPrevious)
                && submitUserCheck != null
                && toUser.getId().equals(submitUserCheck.getId());

        if (!isSendingBackToCreator
                && !permissionService.hasApprovePermission(toUser)
                && !permissionService.hasApproveFinalPermission(toUser)) {
            throw new RuntimeException("Người nhận không có quyền duyệt JD");
        }

        // Reset status khi gửi lại từ REJECTED
        if ("REJECTED".equals(jd.getStatus()))
            jd.setStatus("DRAFT");

        JdFlow flow = jdFlowRepository.findByJobDescriptionId(jdId);
        if (flow == null) {
            flow = new JdFlow();
            flow.setJobDescription(jd);
        }

        flow.setFromUser(fromUser);
        flow.setCurrentUser(toUser);

        // ✅ Dùng lại submitUserCheck thay vì gọi lại lần 2
        boolean isReturningToCreator = submitUserCheck != null
                && toUser.getId().equals(submitUserCheck.getId());

        if (Boolean.TRUE.equals(returnToPrevious) && isReturningToCreator) {
            flow.setStatus("REJECTED");
            jd.setStatus("REJECTED");
        } else {
            flow.setStatus("IN_REVIEW");
            jd.setStatus("IN_REVIEW");
        }
        jobDescriptionRepository.save(jd);
        jdFlowRepository.save(flow);

        // Lưu log với comment (đặc biệt quan trọng khi gửi về trước)
        if (permissionService.hasApproveFinalPermission(toUser)) {
            logService.saveLog(jd, fromUser, toUser, "SUBMIT_TO_FINAL", finalComment);
        } else {
            logService.saveLog(jd, fromUser, toUser, "SUBMIT", finalComment);
        }

        if (Boolean.TRUE.equals(returnToPrevious) && isReturningToCreator) {
            notificationService.sendNotification(toUser.getId(), "JD_FLOW", "JD_REJECTED", 
                "Mô tả công việc (" + jd.getCode() + ") đã bị trả về với lý do: " + comment, "/admin/job-descriptions?tab=inbox");
        } else {
            notificationService.sendNotification(toUser.getId(), "JD_FLOW", "JD_SUBMITTED", 
                "Bạn có một bản Mô tả công việc (" + (jd.getCode() != null ? jd.getCode() : "Mới") + ") cần phê duyệt.", "/admin/job-descriptions?tab=inbox");
        }

        return flow;
    }

    /*
     * ==========================================
     * APPROVE JD
     * ==========================================
     */
    @Transactional
    public JdFlow approveFlow(Long jdId, String nextUserId) {

        JdFlow flow = jdFlowRepository.findByJobDescriptionId(jdId);

        if (flow == null)
            throw new RuntimeException("JD Flow không tồn tại");

        validateScope(flow.getJobDescription().getCompany() != null ? flow.getJobDescription().getCompany().getId() : null);

        if ("REJECTED".equals(flow.getStatus()))
            throw new RuntimeException("JD đã bị từ chối");

        if ("ISSUED".equals(flow.getStatus()))
            throw new RuntimeException("JD đã ban hành");

        String email = SecurityUtil.getCurrentUserLogin().orElse("");
        User fromUser = userRepository.findByEmail(email);

        if (fromUser == null || !fromUser.getId().equals(flow.getCurrentUser().getId()))
            throw new RuntimeException("Bạn không phải người đang duyệt JD này");

        if (permissionService.hasApproveFinalPermission(fromUser)) {

            if (nextUserId == null)
                throw new RuntimeException("Phải chọn người ban hành JD");

            User issuer = userRepository.findById(nextUserId)
                    .orElseThrow(() -> new RuntimeException("User không tồn tại"));

            if (flow.getJobDescription().getCompany() != null && !isUserInCompany(issuer, flow.getJobDescription().getCompany().getId())) {
                throw new RuntimeException("Người nhận không thuộc công ty quản lý của JD này");
            }

            if (!permissionService.hasIssuePermission(issuer))
                throw new RuntimeException("User không có quyền ban hành JD");

            if (fromUser.getId().equals(issuer.getId()))
                throw new RuntimeException("Không thể tự chỉ định mình là người ban hành");

            JobDescription jd = flow.getJobDescription();
            jd.setStatus("APPROVED");

            flow.setStatus("APPROVED");
            flow.setFromUser(fromUser);
            flow.setCurrentUser(issuer);

            jobDescriptionRepository.save(jd);
            jdFlowRepository.save(flow);

            logService.saveLog(jd, fromUser, issuer, "APPROVE_FINAL", null);

            notificationService.sendNotification(issuer.getId(), "JD_FLOW", "JD_APPROVED", 
                "Bản Mô tả công việc (" + (jd.getCode() != null ? jd.getCode() : "Mới") + ") đã được duyệt và đang chờ bạn ban hành.", "/admin/job-descriptions?tab=inbox");

            return flow;
        }

        if (nextUserId == null)
            throw new RuntimeException("Phải chọn người duyệt tiếp theo");

        User toUser = userRepository.findById(nextUserId)
                .orElseThrow(() -> new RuntimeException("User tiếp theo không tồn tại"));

        if (flow.getJobDescription().getCompany() != null && !isUserInCompany(toUser, flow.getJobDescription().getCompany().getId())) {
            throw new RuntimeException("Người nhận không thuộc công ty quản lý của JD này");
        }

        if (fromUser.getId().equals(toUser.getId()))
            throw new RuntimeException("Không thể chuyển duyệt cho chính mình");

        if (!permissionService.hasApprovePermission(toUser)
                && !permissionService.hasApproveFinalPermission(toUser))
            throw new RuntimeException("User tiếp theo không có quyền duyệt JD");

        JobDescription jd = flow.getJobDescription();
        jd.setStatus("IN_REVIEW");

        flow.setFromUser(fromUser);
        flow.setCurrentUser(toUser);
        flow.setStatus("IN_REVIEW");

        jobDescriptionRepository.save(jd);
        jdFlowRepository.save(flow);

        logService.saveLog(jd, fromUser, toUser, "APPROVE", null);

        notificationService.sendNotification(toUser.getId(), "JD_FLOW", "JD_APPROVED", 
            "Bản Mô tả công việc (" + (jd.getCode() != null ? jd.getCode() : "Mới") + ") đã được duyệt ở bước trước và đang chờ bạn xử lý.", "/admin/job-descriptions?tab=inbox");

        return flow;
    }

    /*
     * ==========================================
     * REJECT JD
     * ==========================================
     */
    @Transactional
    public JdFlow rejectFlow(Long jdId, String comment) {

        JdFlow flow = jdFlowRepository.findByJobDescriptionId(jdId);

        if (flow == null)
            throw new RuntimeException("JD Flow không tồn tại");

        validateScope(flow.getJobDescription().getCompany() != null ? flow.getJobDescription().getCompany().getId() : null);

        if ("ISSUED".equals(flow.getStatus()) || "REJECTED".equals(flow.getStatus()))
            throw new RuntimeException("JD không thể từ chối ở trạng thái này");

        String email = SecurityUtil.getCurrentUserLogin().orElse("");
        User rejectUser = userRepository.findByEmail(email);

        if (rejectUser == null || !rejectUser.getId().equals(flow.getCurrentUser().getId()))
            throw new RuntimeException("Bạn không phải người đang xử lý JD này");

        User submitUser = logService.findSubmitUser(jdId);
        if (submitUser != null && rejectUser.getId().equals(submitUser.getId()))
            throw new RuntimeException("Người tạo JD không có quyền từ chối");

        User previousSender = logService.findLastSender(jdId);
        if (previousSender == null)
            throw new RuntimeException("Không tìm thấy người gửi JD gần nhất");

        if (previousSender.getId().equals(rejectUser.getId()))
            throw new RuntimeException("Không thể từ chối và trả về chính mình");

        JobDescription jd = flow.getJobDescription();

        flow.setCurrentUser(previousSender);
        flow.setStatus("REJECTED");
        jd.setStatus("REJECTED");

        jobDescriptionRepository.save(jd);
        jdFlowRepository.save(flow);

        logService.saveLog(jd, rejectUser, previousSender, "REJECT", comment);

        notificationService.sendNotification(previousSender.getId(), "JD_FLOW", "JD_REJECTED", 
            "Bản Mô tả công việc (" + (jd.getCode() != null ? jd.getCode() : "Mới") + ") đã bị trả về với lý do: " + comment, "/admin/job-descriptions?tab=inbox");

        return flow;
    }

    /*
     * ==========================================
     * ISSUE JD
     * ==========================================
     */
    @Transactional
    public JdFlow issueFlow(Long jdId) {

        JdFlow flow = jdFlowRepository.findByJobDescriptionId(jdId);

        if (flow == null)
            throw new RuntimeException("JD Flow không tồn tại");

        validateScope(flow.getJobDescription().getCompany() != null ? flow.getJobDescription().getCompany().getId() : null);

        if (!"APPROVED".equals(flow.getStatus()))
            throw new RuntimeException("JD chưa được duyệt hoàn tất");

        String email = SecurityUtil.getCurrentUserLogin().orElse("");
        User fromUser = userRepository.findByEmail(email);

        if (fromUser == null || !fromUser.getId().equals(flow.getCurrentUser().getId()))
            throw new RuntimeException("Bạn không phải người ban hành JD này");

        if (!permissionService.hasIssuePermission(fromUser))
            throw new RuntimeException("Bạn không có quyền ban hành JD");

        flow.setStatus("ISSUED");
        flow.setCurrentUser(null);

        JobDescription jd = flow.getJobDescription();
        jd.setStatus("PUBLISHED");

        if (jd.getVersion() == null) {
            jd.setVersion(1);
        } else {
            jd.setVersion(jd.getVersion() + 1);
        }

        jobDescriptionRepository.save(jd);
        jdFlowRepository.save(flow);

        logService.saveLog(jd, fromUser, null, "ISSUE", null);

        User submitUser = logService.findSubmitUser(jdId);
        if (submitUser != null) {
            notificationService.sendNotification(submitUser.getId(), "JD_FLOW", "JD_ISSUED", 
                "Bản Mô tả công việc (" + (jd.getCode() != null ? jd.getCode() : "Mới") + ") của bạn đã được ban hành chính thức.", "/jd/my-jd");
        }

        return flow;
    }

    /*
     * ==========================================
     * JD CHỜ TÔI XỬ LÝ (INBOX)
     * ==========================================
     */
    @Transactional(readOnly = true)
    public List<ResJdInboxDTO> fetchInbox() {

        String email = SecurityUtil.getCurrentUserLogin().orElse("");
        User user = userRepository.findByEmail(email);

        if (user == null)
            return List.of();

        List<JdFlow> flows = jdFlowRepository
                .findByCurrentUserIdAndStatusIn(
                        user.getId(),
                        List.of("IN_REVIEW", "APPROVED", "REJECTED"));

        return flows.stream().map(flow -> {

            JobDescription jd = flow.getJobDescription();
            ResJdInboxDTO dto = new ResJdInboxDTO();

            dto.setJdId(jd.getId());
            dto.setCode(jd.getCode());
            dto.setStatus(jd.getStatus());
            dto.setUpdatedAt(flow.getUpdatedAt() != null ? flow.getUpdatedAt() : flow.getCreatedAt());

            if (jd.getCompany() != null)
                dto.setCompanyName(jd.getCompany().getName());

            if (jd.getDepartment() != null)
                dto.setDepartmentName(jd.getDepartment().getName());

            // THAY BẰNG — ưu tiên department > section > company
            if (jd.getDepartmentJobTitle() != null && jd.getDepartmentJobTitle().getJobTitle() != null) {
                dto.setJobTitleName(jd.getDepartmentJobTitle().getJobTitle().getNameVi());
            } else if (jd.getSectionJobTitle() != null && jd.getSectionJobTitle().getJobTitle() != null) {
                dto.setJobTitleName(jd.getSectionJobTitle().getJobTitle().getNameVi());
            } else if (jd.getCompanyJobTitle() != null && jd.getCompanyJobTitle().getJobTitle() != null) {
                dto.setJobTitleName(jd.getCompanyJobTitle().getJobTitle().getNameVi());
            }

            if (flow.getCurrentUser() != null) {
                ResJdInboxDTO.UserSimple current = new ResJdInboxDTO.UserSimple();
                current.setId(flow.getCurrentUser().getId());
                current.setName(flow.getCurrentUser().getName());
                dto.setCurrentUser(current);
            }

            User fromUser = findLastSender(jd);
            if (fromUser != null) {
                ResJdInboxDTO.UserSimple from = new ResJdInboxDTO.UserSimple();
                from.setId(fromUser.getId());
                from.setName(fromUser.getName());
                dto.setFromUser(from);
            }

            if ("REJECTED".equals(jd.getStatus())) {
                List<JdFlowLog> logs = jd.getLogs();
                JdFlowLogService.RejectInfo rejectInfo = logService.findRejectInfoFromLogs(logs);
                User lastSender = fromUser;
                JdFlowLog rejectLog = findLatestRejectLog(jd);

                boolean rejectorIsLastSender = lastSender != null
                        && rejectLog != null
                        && rejectLog.getFromUser() != null
                        && lastSender.getId().equals(rejectLog.getFromUser().getId());

                if (rejectInfo != null && rejectorIsLastSender) {
                    dto.setRejectComment(rejectInfo.getComment());
                    dto.setRejectorName(rejectInfo.getRejectorName());
                    dto.setRejectorPosition(rejectInfo.getRejectorPosition());
                    dto.setRejectorDepartment(rejectInfo.getRejectorDepartment());
                    dto.setRejectorPositionCode(rejectInfo.getRejectorPositionCode());
                } else {
                    // ✅ Hiện comment [TRẢ VỀ] từ người gửi về
                    JdFlowLog returnLog = logService.findLatestReturnLogFromLogs(logs);
                    if (returnLog != null && returnLog.getComment() != null) {
                        dto.setRejectComment(returnLog.getComment());
                        if (returnLog.getFromUser() != null) {
                            dto.setRejectorName(returnLog.getFromUser().getName());
                        }
                    }
                }

                User senderBefore = logService.findLastSenderBeforeRejectFromLogs(logs);
                User submitUser = logService.findSubmitUserFromLogs(logs);
                boolean isCreator = submitUser != null
                        && flow.getCurrentUser() != null
                        && submitUser.getId().equals(flow.getCurrentUser().getId());

                dto.setCreator(isCreator);

                dto.setCanReturnToPrevious(
                        !isCreator &&
                                senderBefore != null &&
                                !senderBefore.getId().equals(flow.getCurrentUser().getId()));
            }

            return dto;

        }).collect(Collectors.toList());
    }

    // ====================== HELPER METHODS ======================
    
    private User findLastSender(JobDescription jd) {
        if (jd.getLogs() == null || jd.getLogs().isEmpty()) return null;
        return jd.getLogs().stream()
                .sorted((l1, l2) -> l2.getCreatedAt().compareTo(l1.getCreatedAt()))
                .map(JdFlowLog::getFromUser)
                .filter(u -> u != null)
                .findFirst()
                .orElse(null);
    }

    private JdFlowLog findLatestRejectLog(JobDescription jd) {
        if (jd.getLogs() == null || jd.getLogs().isEmpty()) return null;
        return jd.getLogs().stream()
                .filter(log -> "REJECT".equals(log.getAction()))
                .sorted((l1, l2) -> l2.getCreatedAt().compareTo(l1.getCreatedAt()))
                .findFirst()
                .orElse(null);
    }

    private boolean isUserInScope(User u, UserScopeContext.UserScope scope) {
        if (scope == null)
            return false;
        if (scope.isSuperAdmin())
            return true;
        return userPositionRepository.findByUser_IdAndActiveTrue(u.getId())
                .stream()
                .anyMatch(pos -> {
                    Long companyId = switch (pos.getSource().toUpperCase()) {
                        case "COMPANY" -> pos.getCompanyJobTitle().getCompany().getId();
                        case "DEPARTMENT" -> pos.getDepartmentJobTitle().getDepartment().getCompany().getId();
                        case "SECTION" -> pos.getSectionJobTitle().getSection().getDepartment().getCompany().getId();
                        default -> null;
                    };
                    return companyId != null && scope.companyIds().contains(companyId);
                });
    }

    // ← THÊM VÀO ĐÂY
    private boolean isUserInCompany(User u, Long companyId) {
        return userPositionRepository.findByUser_IdAndActiveTrue(u.getId())
                .stream()
                .anyMatch(pos -> {
                    Long cId = switch (pos.getSource().toUpperCase()) {
                        case "COMPANY" -> pos.getCompanyJobTitle().getCompany().getId();
                        case "DEPARTMENT" -> pos.getDepartmentJobTitle().getDepartment().getCompany().getId();
                        case "SECTION" -> pos.getSectionJobTitle().getSection().getDepartment().getCompany().getId();
                        default -> null;
                    };
                    return companyId.equals(cId);
                });
    }

    private List<ResJdApproverDTO.PositionInfo> buildPositions(String userId) {
        return userPositionRepository.findByUser_IdAndActiveTrue(userId)
                .stream()
                .map(pos -> {
                    ResJdApproverDTO.PositionInfo info = new ResJdApproverDTO.PositionInfo();
                    info.setSource(pos.getSource());

                    switch (pos.getSource().toUpperCase()) {
                        case "COMPANY" -> {
                            var cjt = pos.getCompanyJobTitle();
                            info.setCompanyName(cjt.getCompany().getName());
                            info.setJobTitleName(cjt.getJobTitle().getNameVi());
                            if (cjt.getJobTitle().getPositionLevel() != null)
                                info.setPositionCode(cjt.getJobTitle().getPositionLevel().getCode());
                        }
                        case "DEPARTMENT" -> {
                            var djt = pos.getDepartmentJobTitle();
                            info.setCompanyName(djt.getDepartment().getCompany().getName());
                            info.setDepartmentName(djt.getDepartment().getName());
                            info.setJobTitleName(djt.getJobTitle().getNameVi());
                            if (djt.getJobTitle().getPositionLevel() != null)
                                info.setPositionCode(djt.getJobTitle().getPositionLevel().getCode());
                        }
                        case "SECTION" -> {
                            var sjt = pos.getSectionJobTitle();
                            info.setCompanyName(sjt.getSection().getDepartment().getCompany().getName());
                            info.setDepartmentName(sjt.getSection().getDepartment().getName());
                            info.setJobTitleName(sjt.getJobTitle().getNameVi());
                            if (sjt.getJobTitle().getPositionLevel() != null)
                                info.setPositionCode(sjt.getJobTitle().getPositionLevel().getCode());
                        }
                    }
                    return info;
                })
                .collect(Collectors.toList());
    }

    // Thành:
    @Transactional(readOnly = true)
    public List<ResJdApproverDTO> fetchApprovers(Long jdId) {
        List<Long> companyIds = null;
        if (jdId != null) {
            JobDescription jd = jobDescriptionRepository.findById(jdId).orElse(null);
            if (jd != null && jd.getCompany() != null) {
                companyIds = List.of(jd.getCompany().getId());
            }
        }

        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope != null && !scope.isAdminLevel()) {
            if (companyIds == null) {
                companyIds = new ArrayList<>(scope.companyIds());
            } else {
                // Giao điểm giữa công ty của JD và công ty được phép truy cập
                companyIds = companyIds.stream()
                        .filter(id -> scope.companyIds().contains(id))
                        .collect(Collectors.toList());
            }
        }


        if (companyIds != null && companyIds.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        List<Long> queryCompanyIds = (companyIds == null) ? List.of(-1L) : companyIds;
        List<User> approvers = userRepository.findUsersByPermissionAndCompany(
                List.of("JD_APPROVE", "JD_APPROVE_FINAL"),
                queryCompanyIds, companyIds != null);

        return approvers.stream()
                .map(u -> {
                    ResJdApproverDTO dto = new ResJdApproverDTO(
                            u.getId(),
                            u.getName(),
                            u.getEmail(),
                            u.getAvatar(),
                            permissionService.hasApproveFinalPermission(u));
                    dto.setPositions(buildPositions(u.getId()));
                    return dto;
                })
                .collect(Collectors.toList());
    }

    // Thành:
    @Transactional(readOnly = true)
    public List<ResJdApproverDTO> fetchIssuers(Long jdId) {
        List<Long> companyIds = null;
        if (jdId != null) {
            JobDescription jd = jobDescriptionRepository.findById(jdId).orElse(null);
            if (jd != null && jd.getCompany() != null) {
                companyIds = List.of(jd.getCompany().getId());
            }
        }

        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope != null && !scope.isAdminLevel()) {
            if (companyIds == null) {
                companyIds = new ArrayList<>(scope.companyIds());
            } else {
                companyIds = companyIds.stream()
                        .filter(id -> scope.companyIds().contains(id))
                        .collect(Collectors.toList());
            }
        }

        if (companyIds != null && companyIds.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        
        List<Long> queryCompanyIds = (companyIds == null) ? List.of(-1L) : companyIds;

        List<User> issuers = userRepository.findUsersByPermissionAndCompany(
                List.of("JD_ISSUE"),
                queryCompanyIds, companyIds != null);

        return issuers.stream()
                .map(u -> {
                    ResJdApproverDTO dto = new ResJdApproverDTO(
                            u.getId(),
                            u.getName(),
                            u.getEmail(),
                            u.getAvatar(),
                            false);
                    dto.setPositions(buildPositions(u.getId()));
                    return dto;
                })
                .collect(Collectors.toList());
    }

    private ResJdFlowDTO convertToDTO(JdFlow flow) {
        ResJdFlowDTO dto = new ResJdFlowDTO();

        dto.setJdId(flow.getJobDescription().getId());
        dto.setCode(flow.getJobDescription().getCode());
        dto.setStatus(flow.getJobDescription().getStatus());
        dto.setUpdatedAt(flow.getUpdatedAt() != null ? flow.getUpdatedAt() : flow.getCreatedAt());

        if (flow.getCurrentUser() != null) {
            dto.setCurrentUser(new ResJdFlowDTO.UserInfo(
                    flow.getCurrentUser().getId(),
                    flow.getCurrentUser().getName()));
            dto.setCurrentUserIsFinal(
                    permissionService.hasApproveFinalPermission(flow.getCurrentUser()));
        }

        User fromUser = logService.findLastSender(flow.getJobDescription().getId());
        if (fromUser != null) {
            dto.setFromUser(new ResJdFlowDTO.UserInfo(
                    fromUser.getId(),
                    fromUser.getName()));
        }

        return dto;
    }
}
