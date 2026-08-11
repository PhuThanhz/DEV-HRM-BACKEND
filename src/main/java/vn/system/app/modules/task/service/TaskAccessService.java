package vn.system.app.modules.task.service;

import org.springframework.stereotype.Service;

import vn.system.app.common.util.UserScopeContext;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.task.domain.Task;
import vn.system.app.modules.task.repository.TaskParticipantRepository;

@Service
public class TaskAccessService {

    private final TaskParticipantRepository participantRepository;

    public TaskAccessService(TaskParticipantRepository participantRepository) {
        this.participantRepository = participantRepository;
    }

    public boolean isParticipant(Long taskId, String userId) {
        return userId != null && participantRepository.existsByTaskIdAndUserId(taskId, userId);
    }

    public boolean isAdminOrSuperAdmin(UserScopeContext.UserScope scope) {
        return scope != null && (scope.isSuperAdmin() || scope.isAdminLevel());
    }

    public boolean isTaskInManagementScope(Task task, UserScopeContext.UserScope scope) {
        if (scope == null || task.getDepartment() == null) {
            return false;
        }
        if (scope.isSuperAdmin() || scope.isAdminLevel()) {
            return true;
        }
        // companyIds/departmentIds trong UserScope cũng được set cho NHÂN VIÊN THƯỜNG
        // (để phục vụ filter đồng nghiệp/tài liệu cùng công ty-phòng ban ở module khác),
        // không đại diện cho "phạm vi quản lý". Chỉ coi là management scope khi user thực
        // sự có vai trò company-level (ADMIN_SUB_2) hoặc department-level (DEPARTMENT_MANAGER, ADMIN_SUB_3).
        //
        // Với isDepartmentLevel: chỉ so departmentIds — companyIds ở role này chỉ là "công ty
        // chứa phòng ban họ quản lý" (KHÔNG phải phạm vi được xem toàn công ty), nên không dùng
        // để mở rộng quyền xem. Với isCompanyLevel: so companyIds (công ty họ thực sự quản lý).
        if (scope.isDepartmentLevel()) {
            Long departmentId = task.getDepartment().getId();
            if (scope.departmentIds() != null && scope.departmentIds().contains(departmentId)) {
                return true;
            }
        }
        if (scope.isCompanyLevel()) {
            Long companyId = task.getDepartment().getCompany() != null
                    ? task.getDepartment().getCompany().getId()
                    : null;
            if (companyId != null && scope.companyIds() != null && scope.companyIds().contains(companyId)) {
                return true;
            }
        }
        return false;
    }

    public boolean canViewTask(Task task, String currentUserId) {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        return isParticipant(task.getId(), currentUserId)
                || isTaskInManagementScope(task, scope)
                || isAdminOrSuperAdmin(scope);
    }

    public void assertCanViewTask(Task task, String currentUserId) {
        if (!canViewTask(task, currentUserId)) {
            throw new IdInvalidException("Bạn không có quyền truy cập dữ liệu của tác vụ này");
        }
    }
}
