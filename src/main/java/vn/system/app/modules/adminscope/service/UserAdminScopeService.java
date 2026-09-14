package vn.system.app.modules.adminscope.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.common.util.SecurityUtil;
import vn.system.app.common.util.UserScopeContext;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.common.util.error.PermissionException;
import vn.system.app.modules.adminscope.domain.UserAdminScope;
import vn.system.app.modules.adminscope.domain.request.ReqUpsertUserAdminScopesDTO;
import vn.system.app.modules.adminscope.domain.request.ReqUserAdminScopeItemDTO;
import vn.system.app.modules.adminscope.domain.response.ResUserAdminScopeDTO;
import vn.system.app.modules.adminscope.repository.UserAdminScopeRepository;
import vn.system.app.modules.company.domain.Company;
import vn.system.app.modules.company.repository.CompanyRepository;
import vn.system.app.modules.department.domain.Department;
import vn.system.app.modules.department.repository.DepartmentRepository;
import vn.system.app.modules.user.domain.User;
import vn.system.app.modules.user.repository.UserRepository;
import vn.system.app.modules.userposition.repository.UserPositionRepository;

@Service
public class UserAdminScopeService {

    public static final String SCOPE_COMPANY = "COMPANY";
    public static final String SCOPE_DEPARTMENT = "DEPARTMENT";

    public static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";
    public static final String ROLE_ADMIN_SUB_1 = "ADMIN_SUB_1";
    public static final String ROLE_ADMIN_SUB_2 = "ADMIN_SUB_2";
    public static final String ROLE_ADMIN_SUB_3 = "ADMIN_SUB_3";
    public static final String ROLE_DEPARTMENT_MANAGER = "DEPARTMENT_MANAGER";

    private final UserAdminScopeRepository repo;
    private final UserRepository userRepo;
    private final CompanyRepository companyRepo;
    private final DepartmentRepository departmentRepo;
    private final UserPositionRepository userPositionRepo;

    public UserAdminScopeService(
            UserAdminScopeRepository repo,
            UserRepository userRepo,
            CompanyRepository companyRepo,
            DepartmentRepository departmentRepo,
            UserPositionRepository userPositionRepo) {
        this.repo = repo;
        this.userRepo = userRepo;
        this.companyRepo = companyRepo;
        this.departmentRepo = departmentRepo;
        this.userPositionRepo = userPositionRepo;
    }

    @Transactional(readOnly = true)
    public List<ResUserAdminScopeDTO> fetchByUser(String userId) {
        ensureUserExists(userId);
        checkTargetUserScope(userId);
        return repo.findByUser_IdAndActiveTrue(userId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    // IDOR protection — chỉ xem được phạm vi quản trị của user thuộc
    // công ty/phòng ban trong phạm vi người gọi
    private void checkTargetUserScope(String targetUserId) {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope == null || scope.isSuperAdmin() || scope.isAdminLevel()) {
            return;
        }

        List<Long> targetCompanyIds = userPositionRepo.findActiveCompanyIdsByUserId(targetUserId);
        List<Long> targetDepartmentIds = userPositionRepo.findActiveDepartmentIdsByUserId(targetUserId);

        boolean allowedByCompany = scope.companyIds() != null
                && targetCompanyIds.stream().anyMatch(scope.companyIds()::contains);
        boolean allowedByDepartment = scope.departmentIds() != null
                && targetDepartmentIds.stream().anyMatch(scope.departmentIds()::contains);

        if (!allowedByCompany && !allowedByDepartment) {
            throw new PermissionException("Bạn không có quyền xem phạm vi quản trị của user này");
        }
    }

    @Transactional
    public List<ResUserAdminScopeDTO> replaceScopes(String userId, ReqUpsertUserAdminScopesDTO req) {
        User targetUser = userRepo.findById(userId)
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy user ID = " + userId));

        validateActorCanManageScopes(req);

        String targetRole = targetUser.getRole() != null ? targetUser.getRole().getName() : "";
        List<ReqUserAdminScopeItemDTO> requested = normalizeScopesForRole(targetUser.getId(), targetRole, req);

        List<UserAdminScope> current = repo.findByUser_IdAndActiveTrue(userId);
        Set<String> requestedKeys = requested.stream()
                .map(this::keyOf)
                .collect(Collectors.toSet());

        for (UserAdminScope scope : current) {
            if (!requestedKeys.contains(keyOf(scope))) {
                scope.setActive(false);
                repo.save(scope);
            }
        }

        for (ReqUserAdminScopeItemDTO item : requested) {
            UserAdminScope scope = findExisting(userId, item)
                    .orElseGet(() -> {
                        UserAdminScope created = new UserAdminScope();
                        created.setUser(targetUser);
                        return created;
                    });

            scope.setScopeType(item.getScopeType().toUpperCase());
            scope.setActive(true);

            Company company = companyRepo.findById(item.getCompanyId())
                    .orElseThrow(() -> new IdInvalidException("Không tìm thấy công ty ID = " + item.getCompanyId()));
            scope.setCompany(company);

            if (SCOPE_DEPARTMENT.equals(scope.getScopeType())) {
                Department department = departmentRepo.findById(item.getDepartmentId())
                        .orElseThrow(() -> new IdInvalidException(
                                "Không tìm thấy phòng ban ID = " + item.getDepartmentId()));
                scope.setDepartment(department);
            } else {
                scope.setDepartment(null);
            }

            repo.save(scope);
        }

        return fetchByUser(userId);
    }

    public Set<Long> getCompanyScopeIds(String userId) {
        Set<Long> scopedCompanyIds = repo.findActiveCompanyScopeIdsByUserId(userId)
                .stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (scopedCompanyIds.isEmpty()) {
            scopedCompanyIds.addAll(userPositionRepo.findActiveCompanyIdsByUserId(userId)
                    .stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet()));
        }
        return scopedCompanyIds;
    }

    public Set<Long> getDepartmentScopeIds(String userId, String roleName) {
        if (ROLE_DEPARTMENT_MANAGER.equals(roleName)) {
            return userPositionRepo.findActiveDepartmentIdsByUserId(userId)
                    .stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        }

        Set<Long> scopedDepartmentIds = repo.findActiveDepartmentScopeIdsByUserId(userId)
                .stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (scopedDepartmentIds.isEmpty()) {
            scopedDepartmentIds.addAll(userPositionRepo.findActiveDepartmentIdsByUserId(userId)
                    .stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet()));
        }
        return scopedDepartmentIds;
    }

    public Set<Long> getCompanyIdsFromDepartmentScopes(String userId, String roleName) {
        if (ROLE_DEPARTMENT_MANAGER.equals(roleName)) {
            return userPositionRepo.findActiveCompanyIdsByUserId(userId)
                    .stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        }

        Set<Long> scopedCompanyIds = repo.findCompanyIdsFromDepartmentScopesByUserId(userId)
                .stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (scopedCompanyIds.isEmpty()) {
            scopedCompanyIds.addAll(userPositionRepo.findActiveCompanyIdsByUserId(userId)
                    .stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet()));
        }
        return scopedCompanyIds;
    }

    private void validateActorCanManageScopes(ReqUpsertUserAdminScopesDTO req) {
        String email = SecurityUtil.getCurrentUserLogin().orElse("");
        if (email.isBlank()) {
            return;
        }

        User actor = userRepo.findByEmail(email);
        String actorRole = actor != null && actor.getRole() != null ? actor.getRole().getName() : "";

        if (ROLE_SUPER_ADMIN.equals(actorRole)) {
            return;
        }

        if (!ROLE_ADMIN_SUB_2.equals(actorRole)) {
            throw new PermissionException("Bạn không có quyền gán phạm vi quản trị.");
        }

        UserScopeContext.UserScope scope = UserScopeContext.get();
        Set<Long> allowedCompanyIds = scope != null && scope.companyIds() != null
                ? scope.companyIds()
                : getCompanyScopeIds(actor.getId());

        Set<Long> requestedCompanyIds = req == null || req.getScopes() == null
                ? Set.of()
                : req.getScopes().stream()
                        .map(ReqUserAdminScopeItemDTO::getCompanyId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());

        if (!allowedCompanyIds.containsAll(requestedCompanyIds)) {
            throw new PermissionException("Bạn chỉ được gán phạm vi trong công ty được quản lý.");
        }
    }

    private List<ReqUserAdminScopeItemDTO> normalizeScopesForRole(
            String targetUserId,
            String roleName,
            ReqUpsertUserAdminScopesDTO req) {
        List<ReqUserAdminScopeItemDTO> items = req != null && req.getScopes() != null
                ? req.getScopes()
                : List.of();

        if (!ROLE_ADMIN_SUB_2.equals(roleName) && !ROLE_ADMIN_SUB_3.equals(roleName)) {
            return List.of();
        }

        List<ReqUserAdminScopeItemDTO> normalized = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (ReqUserAdminScopeItemDTO item : items) {
            if (item == null) {
                continue;
            }

            item.setScopeType(item.getScopeType() != null ? item.getScopeType().toUpperCase() : "");

            validateScopeItemForRole(targetUserId, roleName, item);

            String key = keyOf(item);
            if (seen.add(key)) {
                normalized.add(item);
            }
        }

        return normalized;
    }

    private void validateScopeItemForRole(String targetUserId, String roleName, ReqUserAdminScopeItemDTO item) {
        if (ROLE_ADMIN_SUB_2.equals(roleName)) {
            if (!SCOPE_COMPANY.equals(item.getScopeType())) {
                throw new IdInvalidException("ADMIN_SUB_2 chỉ được gán phạm vi công ty.");
            }
            if (item.getCompanyId() == null) {
                throw new IdInvalidException("companyId không được để trống.");
            }
            item.setDepartmentId(null);
            if (!companyRepo.existsById(item.getCompanyId())) {
                throw new IdInvalidException("Không tìm thấy công ty ID = " + item.getCompanyId());
            }
            return;
        }

        if (ROLE_ADMIN_SUB_3.equals(roleName)) {
            if (!SCOPE_DEPARTMENT.equals(item.getScopeType())) {
                throw new IdInvalidException("ADMIN_SUB_3 chỉ được gán phạm vi phòng ban.");
            }
            if (item.getCompanyId() == null || item.getDepartmentId() == null) {
                throw new IdInvalidException("companyId và departmentId không được để trống.");
            }

            Department department = departmentRepo.findById(item.getDepartmentId())
                    .orElseThrow(() -> new IdInvalidException("Không tìm thấy phòng ban ID = " + item.getDepartmentId()));
            if (department.getCompany() == null
                    || !Objects.equals(department.getCompany().getId(), item.getCompanyId())) {
                throw new IdInvalidException("Phòng ban không thuộc công ty đã chọn.");
            }
            Set<Long> assignedCompanyIds = userPositionRepo.findActiveCompanyIdsByUserId(targetUserId)
                    .stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            if (!assignedCompanyIds.contains(item.getCompanyId())) {
                throw new IdInvalidException("Chỉ được gán phạm vi phòng ban trong công ty đã có chức danh của user.");
            }
        }
    }

    private java.util.Optional<UserAdminScope> findExisting(String userId, ReqUserAdminScopeItemDTO item) {
        if (SCOPE_COMPANY.equals(item.getScopeType())) {
            return repo.findByUser_IdAndScopeTypeAndCompany_IdAndDepartmentIsNull(
                    userId,
                    item.getScopeType(),
                    item.getCompanyId());
        }

        return repo.findByUser_IdAndScopeTypeAndCompany_IdAndDepartment_Id(
                userId,
                item.getScopeType(),
                item.getCompanyId(),
                item.getDepartmentId());
    }

    private String keyOf(ReqUserAdminScopeItemDTO item) {
        return item.getScopeType() + ":" + item.getCompanyId() + ":" + item.getDepartmentId();
    }

    private String keyOf(UserAdminScope scope) {
        Long companyId = scope.getCompany() != null ? scope.getCompany().getId() : null;
        Long departmentId = scope.getDepartment() != null ? scope.getDepartment().getId() : null;
        return scope.getScopeType() + ":" + companyId + ":" + departmentId;
    }

    private void ensureUserExists(String userId) {
        if (!userRepo.existsById(userId)) {
            throw new IdInvalidException("Không tìm thấy user ID = " + userId);
        }
    }

    private ResUserAdminScopeDTO toDTO(UserAdminScope scope) {
        ResUserAdminScopeDTO res = new ResUserAdminScopeDTO();
        res.setId(scope.getId());
        res.setScopeType(scope.getScopeType());
        res.setActive(scope.isActive());
        res.setCreatedAt(scope.getCreatedAt());
        res.setUpdatedAt(scope.getUpdatedAt());

        if (scope.getCompany() != null) {
            ResUserAdminScopeDTO.SimpleRef company = new ResUserAdminScopeDTO.SimpleRef();
            company.setId(scope.getCompany().getId());
            company.setName(scope.getCompany().getName());
            res.setCompany(company);
        }

        if (scope.getDepartment() != null) {
            ResUserAdminScopeDTO.SimpleRef department = new ResUserAdminScopeDTO.SimpleRef();
            department.setId(scope.getDepartment().getId());
            department.setName(scope.getDepartment().getName());
            res.setDepartment(department);
        }

        return res;
    }
}
