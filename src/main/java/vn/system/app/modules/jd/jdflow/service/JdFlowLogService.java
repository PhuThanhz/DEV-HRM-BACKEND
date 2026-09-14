package vn.system.app.modules.jd.jdflow.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.common.util.UserScopeContext;
import vn.system.app.common.util.error.PermissionException;
import vn.system.app.modules.jd.jdflow.domain.JdFlowLog;
import vn.system.app.modules.jd.jdflow.domain.response.ResJdFlowLogDTO;
import vn.system.app.modules.jd.jdflow.repository.JdFlowLogRepository;
import vn.system.app.modules.jd.jobdescription.domain.JobDescription;
import vn.system.app.modules.jd.jobdescription.repository.JobDescriptionRepository;
import vn.system.app.modules.user.domain.User;
import vn.system.app.modules.userposition.domain.UserPosition;
import vn.system.app.modules.userposition.repository.UserPositionRepository;

import lombok.Getter;

@Service
public class JdFlowLogService {

    private final JdFlowLogRepository repository;
    private final UserPositionRepository userPositionRepository;
    private final JobDescriptionRepository jobDescriptionRepository;

    public JdFlowLogService(
            JdFlowLogRepository repository,
            UserPositionRepository userPositionRepository,
            JobDescriptionRepository jobDescriptionRepository) {
        this.repository = repository;
        this.userPositionRepository = userPositionRepository;
        this.jobDescriptionRepository = jobDescriptionRepository;
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
     * SAVE LOG
     * ==========================================
     */
    public void saveLog(JobDescription jd, User from, User to, String action, String comment) {
        JdFlowLog log = new JdFlowLog();
        log.setJobDescription(jd);
        log.setFromUser(from);
        log.setToUser(to);
        log.setAction(action);
        log.setComment(comment);
        repository.save(log);
    }

    @Transactional
    public void deleteByJobDescription(Long jdId) {
        repository.deleteAll(repository.findByJobDescriptionIdOrderByCreatedAtAsc(jdId));
    }

    /*
     * ==========================================
     * FIND USER ĐÃ SUBMIT JD (NGƯỜI TẠO LUỒNG)
     * ==========================================
     */
    public User findSubmitUser(Long jdId) {
        List<JdFlowLog> logs = repository
                .findByJobDescriptionIdOrderByCreatedAtAsc(jdId);
        return findSubmitUserFromLogs(logs);
    }

    public User findSubmitUserFromLogs(List<JdFlowLog> logs) {
        if (logs == null || logs.isEmpty()) return null;
        return logs.stream()
                .filter(log -> "SUBMIT".equals(log.getAction())
                        || "SUBMIT_TO_FINAL".equals(log.getAction()))
                .map(JdFlowLog::getFromUser)
                .findFirst()
                .orElse(null);
    }

    /*
     * ==========================================
     * FIND NGƯỜI GỬI GẦN NHẤT (DÙNG CHO INBOX & REJECT)
     * ==========================================
     */
    public User findLastSender(Long jdId) {
        List<JdFlowLog> logs = repository
                .findByJobDescriptionIdOrderByCreatedAtDesc(jdId);
        return logs.stream()
                .map(JdFlowLog::getFromUser)
                .filter(user -> user != null)
                .findFirst()
                .orElse(null);
    }

    /**
     * ==========================================
     * METHOD MỚI: TÌM NGƯỜI GỬI TRƯỚC ĐÓ TRƯỚC KHI BỊ TỪ CHỐI
     * ==========================================
     */
    public User findLastSenderBeforeReject(Long jdId) {
        List<JdFlowLog> logs = repository
                .findByJobDescriptionIdOrderByCreatedAtAsc(jdId);
        return findLastSenderBeforeRejectFromLogs(logs);
    }

    public User findLastSenderBeforeRejectFromLogs(List<JdFlowLog> logs) {
        if (logs == null || logs.isEmpty()) return null;

        int rejectIndex = -1;
        for (int i = logs.size() - 1; i >= 0; i--) {
            if ("REJECT".equals(logs.get(i).getAction())) {
                rejectIndex = i;
                break;
            }
        }

        if (rejectIndex == -1)
            return null;

        JdFlowLog rejectLog = logs.get(rejectIndex);

        if (rejectLog.getToUser() == null)
            return null;

        String receiverId = rejectLog.getToUser().getId();

        for (int i = rejectIndex - 1; i >= 0; i--) {
            JdFlowLog log = logs.get(i);

            if (log.getToUser() != null
                    && log.getToUser().getId().equals(receiverId)
                    && ("SUBMIT".equals(log.getAction())
                            || "APPROVE".equals(log.getAction())
                            || "SUBMIT_TO_FINAL".equals(log.getAction()))) {

                return log.getFromUser();
            }
        }

        return null;
    }

    /*
     * ==========================================
     * FIND LOG TỪ CHỐI GẦN NHẤT
     * ==========================================
     */
    public JdFlowLog findLatestRejectLog(Long jdId) {
        List<JdFlowLog> logs = repository
                .findByJobDescriptionIdOrderByCreatedAtDesc(jdId);
        return logs.stream()
                .filter(log -> "REJECT".equals(log.getAction()))
                .findFirst()
                .orElse(null);
    }

    /*
     * ==========================================
     * REJECT INFO — trả đủ tên + chức danh + phòng ban + cấp bậc
     * ==========================================
     */
    @Getter
    public static class RejectInfo {
        private final String comment;
        private final String rejectorName;
        private final String rejectorPosition;
        private final String rejectorDepartment;
        private final String rejectorPositionCode;

        public RejectInfo(String comment, String rejectorName,
                String rejectorPosition, String rejectorDepartment,
                String rejectorPositionCode) {
            this.comment = comment;
            this.rejectorName = rejectorName;
            this.rejectorPosition = rejectorPosition;
            this.rejectorDepartment = rejectorDepartment;
            this.rejectorPositionCode = rejectorPositionCode;
        }
    }

    /**
     * Tối ưu hóa: Lấy thông tin từ danh sách logs đã có sẵn
     */
    public RejectInfo findRejectInfoFromLogs(List<JdFlowLog> logs) {
        if (logs == null || logs.isEmpty())
            return null;

        JdFlowLog log = logs.stream()
                .filter(l -> "REJECT".equals(l.getAction()))
                .sorted((l1, l2) -> l2.getCreatedAt().compareTo(l1.getCreatedAt()))
                .findFirst()
                .orElse(null);

        if (log == null || log.getFromUser() == null)
            return null;

        return buildRejectInfo(log);
    }

    public RejectInfo findLatestRejectInfo(Long jdId) {
        JdFlowLog log = findLatestRejectLog(jdId);
        if (log == null || log.getFromUser() == null)
            return null;

        return buildRejectInfo(log);
    }

    private RejectInfo buildRejectInfo(JdFlowLog log) {
        User rejector = log.getFromUser();
        String position = null;
        String department = null;
        String positionCode = null;

        List<UserPosition> ups = userPositionRepository
                .findByUser_IdAndActiveTrue(rejector.getId());

        if (ups != null && !ups.isEmpty()) {
            position = extractPosition(ups);
            department = extractDepartment(ups);
            positionCode = extractPositionCode(ups);
        }

        return new RejectInfo(log.getComment(), rejector.getName(),
                position, department, positionCode);
    }

    private String extractPosition(List<UserPosition> ups) {
        String position = ups.stream()
                .filter(up -> "DEPARTMENT".equals(up.getSource())
                        && up.getDepartmentJobTitle() != null
                        && up.getDepartmentJobTitle().getJobTitle() != null)
                .map(up -> up.getDepartmentJobTitle().getJobTitle().getNameVi())
                .findFirst().orElse(null);

        if (position == null) {
            position = ups.stream()
                    .filter(up -> "SECTION".equals(up.getSource())
                            && up.getSectionJobTitle() != null
                            && up.getSectionJobTitle().getJobTitle() != null)
                    .map(up -> up.getSectionJobTitle().getJobTitle().getNameVi())
                    .findFirst().orElse(null);
        }

        if (position == null) {
            position = ups.stream()
                    .filter(up -> "COMPANY".equals(up.getSource())
                            && up.getCompanyJobTitle() != null
                            && up.getCompanyJobTitle().getJobTitle() != null)
                    .map(up -> up.getCompanyJobTitle().getJobTitle().getNameVi())
                    .findFirst().orElse(null);
        }
        return position;
    }

    private String extractDepartment(List<UserPosition> ups) {
        String department = ups.stream()
                .filter(up -> "DEPARTMENT".equals(up.getSource())
                        && up.getDepartmentJobTitle() != null
                        && up.getDepartmentJobTitle().getDepartment() != null)
                .map(up -> up.getDepartmentJobTitle().getDepartment().getName())
                .findFirst().orElse(null);

        if (department == null) {
            department = ups.stream()
                    .filter(up -> "SECTION".equals(up.getSource())
                            && up.getSectionJobTitle() != null
                            && up.getSectionJobTitle().getSection() != null
                            && up.getSectionJobTitle().getSection()
                                    .getDepartment() != null)
                    .map(up -> up.getSectionJobTitle().getSection().getDepartment()
                            .getName())
                    .findFirst().orElse(null);
        }
        return department;
    }

    private String extractPositionCode(List<UserPosition> ups) {
        String positionCode = ups.stream()
                .filter(up -> "DEPARTMENT".equals(up.getSource())
                        && up.getDepartmentJobTitle() != null
                        && up.getDepartmentJobTitle().getJobTitle() != null
                        && up.getDepartmentJobTitle().getJobTitle()
                                .getPositionLevel() != null)
                .map(up -> up.getDepartmentJobTitle().getJobTitle().getPositionLevel()
                        .getCode())
                .findFirst().orElse(null);

        if (positionCode == null) {
            positionCode = ups.stream()
                    .filter(up -> "SECTION".equals(up.getSource())
                            && up.getSectionJobTitle() != null
                            && up.getSectionJobTitle().getJobTitle() != null
                            && up.getSectionJobTitle().getJobTitle()
                                    .getPositionLevel() != null)
                    .map(up -> up.getSectionJobTitle().getJobTitle().getPositionLevel()
                            .getCode())
                    .findFirst().orElse(null);
        }

        if (positionCode == null) {
            positionCode = ups.stream()
                    .filter(up -> "COMPANY".equals(up.getSource())
                            && up.getCompanyJobTitle() != null
                            && up.getCompanyJobTitle().getJobTitle() != null
                            && up.getCompanyJobTitle().getJobTitle()
                                    .getPositionLevel() != null)
                    .map(up -> up.getCompanyJobTitle().getJobTitle().getPositionLevel()
                            .getCode())
                    .findFirst().orElse(null);
        }
        return positionCode;
    }

    public JdFlowLog findLatestReturnLog(Long jdId) {
        List<JdFlowLog> logs = repository
                .findByJobDescriptionIdOrderByCreatedAtDesc(jdId);
        return findLatestReturnLogFromLogs(logs);
    }

    public JdFlowLog findLatestReturnLogFromLogs(List<JdFlowLog> logs) {
        if (logs == null || logs.isEmpty()) return null;
        return logs.stream()
                .filter(log -> ("SUBMIT".equals(log.getAction())
                        || "SUBMIT_TO_FINAL".equals(log.getAction()))
                        && log.getComment() != null
                        && log.getComment().startsWith("[TRẢ VỀ]"))
                .findFirst()
                .orElse(null);
    }

    public List<ResJdFlowLogDTO> fetchLogs(Long jdId) {
        JobDescription jd = jobDescriptionRepository.findById(jdId)
                .orElseThrow(() -> new RuntimeException("JD không tồn tại"));
        validateScope(jd.getCompany() != null ? jd.getCompany().getId() : null);

        List<JdFlowLog> logs = repository
                .findByJobDescriptionIdOrderByCreatedAtAsc(jdId);

        return logs.stream().map(log -> {
            ResJdFlowLogDTO dto = new ResJdFlowLogDTO();
            dto.setId(log.getId());
            dto.setJdId(log.getJobDescription().getId());
            dto.setAction(log.getAction());
            dto.setComment(log.getComment());
            dto.setCreatedAt(log.getCreatedAt());

            if (log.getFromUser() != null) {
                dto.setFromUser(new ResJdFlowLogDTO.UserInfo(
                        log.getFromUser().getId(),
                        log.getFromUser().getName()));
            }
            if (log.getToUser() != null) {
                dto.setToUser(new ResJdFlowLogDTO.UserInfo(
                        log.getToUser().getId(),
                        log.getToUser().getName()));
            }
            return dto;
        }).collect(Collectors.toList());
    }
}
