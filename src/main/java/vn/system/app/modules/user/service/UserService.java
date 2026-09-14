package vn.system.app.modules.user.service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.JoinType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.system.app.common.util.UserScopeContext;

import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.SecurityUtil;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.common.util.error.PermissionException;
import vn.system.app.modules.email.service.EmailService;
import vn.system.app.modules.employeecareerpath.repository.EmployeeCareerPathRepository;
import vn.system.app.modules.role.domain.Role;
import vn.system.app.modules.role.service.RoleService;
import vn.system.app.modules.user.domain.User;
import vn.system.app.modules.user.domain.request.ReqCreateUserDTO;
import vn.system.app.modules.user.domain.request.ReqUpdateUserDTO;
import vn.system.app.modules.user.domain.response.ResCreateUserDTO;
import vn.system.app.modules.user.domain.response.ResCrossCompanyUserDTO;
import vn.system.app.modules.user.domain.response.ResUpdateUserDTO;
import vn.system.app.modules.user.domain.response.ResUserDTO;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import vn.system.app.modules.user.repository.UserRepository;
import vn.system.app.modules.userinfo.domain.UserInfo;
import vn.system.app.modules.userinfo.repository.UserInfoRepository;
import vn.system.app.modules.userposition.domain.UserPosition;
import vn.system.app.modules.userposition.repository.UserPositionRepository;
import vn.system.app.modules.usersession.service.UserSessionService;
import vn.system.app.modules.jobtitle.domain.JobTitle;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Transactional
public class UserService {

    private static final long LAST_LOGIN_UPDATE_INTERVAL_MS = Duration.ofMinutes(10).toMillis();

    private final UserRepository userRepository;
    private final UserInfoRepository userInfoRepository;
    private final UserPositionRepository userPositionRepository;
    private final EmployeeCareerPathRepository employeeCareerPathRepository;
    private final RoleService roleService;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final UserSessionService userSessionService;
    private final ConcurrentHashMap<String, Long> lastLoginUpdateTimes = new ConcurrentHashMap<>();

    public UserService(
            UserRepository userRepository,
            UserInfoRepository userInfoRepository,
            UserPositionRepository userPositionRepository,
            EmployeeCareerPathRepository employeeCareerPathRepository,
            RoleService roleService,
            EmailService emailService,
            PasswordEncoder passwordEncoder,
            UserSessionService userSessionService) {
        this.userRepository = userRepository;
        this.userInfoRepository = userInfoRepository;
        this.userPositionRepository = userPositionRepository;
        this.employeeCareerPathRepository = employeeCareerPathRepository;
        this.roleService = roleService;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
        this.userSessionService = userSessionService;
    }

    // ======================================================
    // CREATE USER + USERINFO (Admin - UserController)
    // ======================================================
    public ResCreateUserDTO handleCreateUser(ReqCreateUserDTO req) {

        if (this.isEmailExist(req.getEmail())) {
            throw new IdInvalidException("Email " + req.getEmail() + " đã tồn tại");
        }

        if (req.getEmployeeCode() != null
                && userInfoRepository.existsByEmployeeCode(req.getEmployeeCode())) {
            throw new IdInvalidException("Mã nhân viên " + req.getEmployeeCode() + " đã tồn tại");
        }

        User user = new User();
        user.setName(req.getName());
        user.setEmail(req.getEmail());

        if (req.getPassword() != null && !req.getPassword().isEmpty()) {
            user.setPassword(passwordEncoder.encode(req.getPassword()));
        }

        user.setActive(true);

        if (req.getActive() != null) {
            user.setActive(req.getActive());
        }

        if (req.getRoleId() != null) {
            Role r = roleService.fetchById(req.getRoleId());
            user.setRole(r);
        }

        // ⭐ THÊM CHO HQCV
        if (req.getDirectManagerId() != null && !req.getDirectManagerId().isEmpty()) {
            User dm = userRepository.findById(req.getDirectManagerId())
                    .orElseThrow(() -> new IdInvalidException("Quản lý trực tiếp không tồn tại"));
            user.setDirectManager(dm);
        }

        User savedUser = userRepository.save(user);

        boolean hasUserInfo = req.getEmployeeCode() != null
                || req.getPhone() != null
                || req.getDateOfBirth() != null
                || req.getGender() != null
                || req.getStartDate() != null
                || req.getContractSignDate() != null
                || req.getContractExpireDate() != null;

        UserInfo savedUserInfo = null;

        if (hasUserInfo) {
            UserInfo userInfo = new UserInfo();
            userInfo.setUser(savedUser);
            userInfo.setEmployeeCode(req.getEmployeeCode());
            userInfo.setPhone(req.getPhone());
            userInfo.setDateOfBirth(req.getDateOfBirth());
            userInfo.setGender(req.getGender());
            userInfo.setStartDate(req.getStartDate());
            userInfo.setContractSignDate(req.getContractSignDate());
            userInfo.setContractExpireDate(req.getContractExpireDate());
            savedUserInfo = userInfoRepository.save(userInfo);
        }

        return convertToResCreateUserDTO(savedUser, savedUserInfo);
    }

    // ======================================================
    // CREATE USER đơn giản (AuthController - /auth/register)
    // ======================================================
    public User handleCreateUser(User user) {

        if (user.getRole() != null) {
            Role r = this.roleService.fetchById(user.getRole().getId());
            user.setRole(r != null ? r : null);
        }

        user.setActive(true);

        return this.userRepository.save(user);
    }

    // ======================================================
    // SAVE USER
    // ======================================================
    public User save(User user) {
        return this.userRepository.save(user);
    }

    // ======================================================
    // DELETE USER
    // ======================================================
    public void handleDeleteUser(String id) {

        if (!userRepository.existsById(id)) {
            throw new IdInvalidException("User với id = " + id + " không tồn tại");
        }
        checkUserScope(id);

        this.userRepository.deleteById(id);
    }

    // ======================================================
    // SCOPE CHECK (IDOR protection — công ty/phòng ban của user đích phải
    // nằm trong phạm vi công ty/phòng ban mà người gọi được thấy)
    // ======================================================
    private void checkUserScope(String targetUserId) {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope == null || scope.isSuperAdmin() || scope.isAdminLevel()) {
            return;
        }

        List<Long> targetCompanyIds = userPositionRepository.findActiveCompanyIdsByUserId(targetUserId);
        List<Long> targetDepartmentIds = userPositionRepository.findActiveDepartmentIdsByUserId(targetUserId);

        boolean allowedByCompany = scope.companyIds() != null
                && targetCompanyIds.stream().anyMatch(scope.companyIds()::contains);
        boolean allowedByDepartment = scope.departmentIds() != null
                && targetDepartmentIds.stream().anyMatch(scope.departmentIds()::contains);

        if (!allowedByCompany && !allowedByDepartment) {
            throw new PermissionException("Bạn không có quyền thao tác trên user này");
        }
    }

    // ======================================================
    // FIND USER
    // ======================================================
    public User fetchUserById(String id) {
        Optional<User> userOptional = this.userRepository.findById(id);
        if (userOptional.isPresent()) {
            checkUserScope(id);
        }
        return userOptional.orElse(null);
    }

    // ======================================================
    // FETCH ALL WITH PAGINATION
    // ======================================================
    public ResultPaginationDTO fetchAllUser(Specification<User> spec, Pageable pageable) {

        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope != null && !scope.isAdminLevel()) {

            if (scope.isAdminLevel()) {
                // ADMIN_SUB_1 → thấy toàn bộ, không filter

            } else if (scope.isDepartmentLevel()) {
                if (scope.departmentIds() == null || scope.departmentIds().isEmpty()) {
                    ResultPaginationDTO rs = new ResultPaginationDTO();
                    ResultPaginationDTO.Meta mt = new ResultPaginationDTO.Meta();
                    mt.setPage(pageable.getPageNumber() + 1);
                    mt.setPageSize(pageable.getPageSize());
                    mt.setPages(0);
                    mt.setTotal(0);
                    rs.setMeta(mt);
                    rs.setResult(List.of());
                    return rs;
                }

                Specification<User> scopeSpec = (root, query, cb) -> {
                    var sub = query.subquery(String.class);
                    var posRoot = sub.from(UserPosition.class);
                    var djtDept = posRoot.join("departmentJobTitle", JoinType.LEFT).join("department", JoinType.LEFT);
                    var sjtDept = posRoot.join("sectionJobTitle", JoinType.LEFT).join("section", JoinType.LEFT).join("department", JoinType.LEFT);

                    sub.select(posRoot.get("user").get("id"))
                            .where(cb.and(
                                    cb.isTrue(posRoot.get("active")),
                                    cb.or(
                                            cb.and(
                                                    cb.equal(posRoot.get("source"), "DEPARTMENT"),
                                                    djtDept.get("id").in(scope.departmentIds())),
                                            cb.and(
                                                    cb.equal(posRoot.get("source"), "SECTION"),
                                                    sjtDept.get("id").in(scope.departmentIds())))));

                    return root.get("id").in(sub);
                };

                spec = Specification.where(spec).and(scopeSpec);

            } else if (scope.companyIds().isEmpty()) {
                // Không có công ty nào → trả về rỗng
                ResultPaginationDTO rs = new ResultPaginationDTO();
                ResultPaginationDTO.Meta mt = new ResultPaginationDTO.Meta();
                mt.setPage(pageable.getPageNumber() + 1);
                mt.setPageSize(pageable.getPageSize());
                mt.setPages(0);
                mt.setTotal(0);
                rs.setMeta(mt);
                rs.setResult(List.of());
                return rs;

            } else {
                // ADMIN_SUB_2 + Employee → filter theo companyIds
                Specification<User> scopeSpec = (root, query, cb) -> {
                    var sub = query.subquery(String.class);
                    var posRoot = sub.from(UserPosition.class);
                    var cjtCompany = posRoot.join("companyJobTitle", JoinType.LEFT).join("company", JoinType.LEFT);
                    var djtCompany = posRoot.join("departmentJobTitle", JoinType.LEFT).join("department", JoinType.LEFT).join("company", JoinType.LEFT);
                    var sjtCompany = posRoot.join("sectionJobTitle", JoinType.LEFT).join("section", JoinType.LEFT).join("department", JoinType.LEFT).join("company", JoinType.LEFT);

                    sub.select(posRoot.get("user").get("id"))
                            .where(cb.and(
                                    cb.isTrue(posRoot.get("active")),
                                    cb.or(
                                            cb.and(
                                                    cb.equal(posRoot.get("source"), "COMPANY"),
                                                    cjtCompany.get("id").in(scope.companyIds())),
                                            cb.and(
                                                    cb.equal(posRoot.get("source"), "DEPARTMENT"),
                                                    djtCompany.get("id").in(scope.companyIds())),
                                            cb.and(
                                                    cb.equal(posRoot.get("source"), "SECTION"),
                                                    sjtCompany.get("id").in(scope.companyIds())))));

                    return root.get("id").in(sub);
                };

                spec = Specification.where(spec).and(scopeSpec);
            }
        }

        Page<User> pageUser = this.userRepository.findAll(spec, pageable);

        ResultPaginationDTO rs = new ResultPaginationDTO();
        ResultPaginationDTO.Meta mt = new ResultPaginationDTO.Meta();
        mt.setPage(pageable.getPageNumber() + 1);
        mt.setPageSize(pageable.getPageSize());
        mt.setPages(pageUser.getTotalPages());
        mt.setTotal(pageUser.getTotalElements());
        rs.setMeta(mt);

        List<User> users = pageUser.getContent();
        List<String> userIds = users.stream().map(User::getId).toList();
        Map<String, List<UserPosition>> positionsByUserId = userIds.isEmpty()
                ? Map.of()
                : userPositionRepository.findActiveFullByUserIds(userIds).stream()
                        .collect(Collectors.groupingBy(position -> position.getUser().getId()));

        List<ResUserDTO> listUser = users.stream()
                .map(user -> convertToResUserDTO(user, positionsByUserId.getOrDefault(user.getId(), List.of())))
                .collect(Collectors.toList());

        rs.setResult(listUser);
        return rs;
    }

    // ======================================================
    // UPDATE USER (ADMIN)
    // ======================================================
    public User handleUpdateUser(ReqUpdateUserDTO req) {

        User currentUser = this.fetchUserById(req.getId());

        if (currentUser == null) {
            throw new IdInvalidException("User với id = " + req.getId() + " không tồn tại");
        }

        if (req.getName() != null) {
            currentUser.setName(req.getName());
        }

        if (req.getActive() != null) {
            currentUser.setActive(req.getActive());

            if (!req.getActive()) {
                this.userSessionService.deleteAllSessionsForUser(currentUser.getEmail());
            }
        }

        if (req.getRoleId() != null) {
            Role r = this.roleService.fetchById(req.getRoleId());
            currentUser.setRole(r);
        }

        // ⭐ THÊM CHO HQCV
        if (req.getDirectManagerId() != null && !req.getDirectManagerId().isEmpty()) {
            User dm = userRepository.findById(req.getDirectManagerId())
                    .orElseThrow(() -> new IdInvalidException("Quản lý trực tiếp không tồn tại"));
            currentUser.setDirectManager(dm);
        } else {
            currentUser.setDirectManager(null);
        }

        User saved = this.userRepository.save(currentUser);

        UserInfo userInfo = userInfoRepository.findByUser_Id(currentUser.getId())
                .orElse(null);

        if (userInfo == null) {
            userInfo = new UserInfo();
            userInfo.setUser(currentUser);
        }

        userInfo.setEmployeeCode(req.getEmployeeCode());
        userInfo.setPhone(req.getPhone());
        userInfo.setDateOfBirth(req.getDateOfBirth());
        userInfo.setGender(req.getGender());
        userInfo.setStartDate(req.getStartDate());
        userInfo.setContractSignDate(req.getContractSignDate());
        userInfo.setContractExpireDate(req.getContractExpireDate());

        userInfoRepository.save(userInfo);

        User fullUser = this.userRepository.findWithUserInfoById(saved.getId());

        return fullUser;
    }

    // ======================================================
    // UPDATE PROFILE (USER TỰ CẬP NHẬT)
    // ======================================================
    public User getCurrentUser() {

        String email = SecurityUtil.getCurrentUserLogin().orElse(null);

        if (email == null) {
            return null;
        }

        return this.userRepository.findByEmail(email);
    }

    public User updateProfile(String name, String avatar) {

        User currentUser = getCurrentUser();

        if (currentUser == null) {
            throw new IdInvalidException("Không tìm thấy người dùng hiện tại");
        }

        currentUser.setName(name);
        currentUser.setAvatar(avatar);

        return this.userRepository.save(currentUser);
    }

    // ======================================================
    // LOGIN SUPPORT
    // ======================================================
    public User handleGetUserCredentialsByUsername(String username) {
        return this.userRepository.findByEmail(username);
    }

    public User handleGetUserByUsername(String username) {
        return this.userRepository.findWithAuthByEmail(username);
    }

    public boolean isEmailExist(String email) {
        return this.userRepository.existsByEmail(email);
    }
    
    public void updateLastLoginIfNecessary(String userId, String ip) {
        long nowMillis = System.currentTimeMillis();
        AtomicBoolean shouldUpdate = new AtomicBoolean(false);

        lastLoginUpdateTimes.compute(userId, (key, previousUpdate) -> {
            if (previousUpdate == null || nowMillis - previousUpdate >= LAST_LOGIN_UPDATE_INTERVAL_MS) {
                shouldUpdate.set(true);
                return nowMillis;
            }
            return previousUpdate;
        });

        if (!shouldUpdate.get()) {
            return;
        }

        Instant now = Instant.ofEpochMilli(nowMillis);
        Instant threshold = now.minus(10, ChronoUnit.MINUTES);
        try {
            this.userRepository.updateLastLogin(userId, ip, now, threshold);
        } catch (RuntimeException ex) {
            lastLoginUpdateTimes.remove(userId, nowMillis);
            throw ex;
        }
    }

    // ======================================================
    // CONVERT DTO
    // ======================================================
    public ResCreateUserDTO convertToResCreateUserDTO(User user, UserInfo userInfo) {

        ResCreateUserDTO res = new ResCreateUserDTO();
        res.setId(user.getId());
        res.setEmail(user.getEmail());
        res.setName(user.getName());
        res.setActive(user.isActive());
        res.setCreatedAt(user.getCreatedAt());

        if (userInfo != null) {
            ResCreateUserDTO.UserInfoBasic info = new ResCreateUserDTO.UserInfoBasic();
            info.setEmployeeCode(userInfo.getEmployeeCode());
            info.setPhone(userInfo.getPhone());
            info.setDateOfBirth(userInfo.getDateOfBirth());
            info.setGender(userInfo.getGender());
            info.setStartDate(userInfo.getStartDate());
            info.setContractSignDate(userInfo.getContractSignDate());
            info.setContractExpireDate(userInfo.getContractExpireDate());
            res.setUserInfo(info);
        }

        return res;
    }

    public ResCreateUserDTO convertToResCreateUserDTO(User user) {
        return convertToResCreateUserDTO(user, null);
    }

    public ResUpdateUserDTO convertToResUpdateUserDTO(User user) {

        ResUpdateUserDTO res = new ResUpdateUserDTO();
        res.setId(user.getId());
        res.setName(user.getName());
        res.setActive(user.isActive());
        res.setUpdatedAt(user.getUpdatedAt());
        // ← THÊM ĐOẠN NÀY
        UserInfo info = user.getUserInfo();
        if (info != null) {
            ResUpdateUserDTO.UserInfoBasic userInfoBasic = new ResUpdateUserDTO.UserInfoBasic();
            userInfoBasic.setEmployeeCode(info.getEmployeeCode());
            userInfoBasic.setPhone(info.getPhone());
            userInfoBasic.setDateOfBirth(info.getDateOfBirth());
            userInfoBasic.setGender(info.getGender());
            userInfoBasic.setStartDate(info.getStartDate());
            userInfoBasic.setContractSignDate(info.getContractSignDate());
            userInfoBasic.setContractExpireDate(info.getContractExpireDate());
            res.setUserInfo(userInfoBasic);
        }
        return res;
    }

    public ResUserDTO convertToResUserDTO(User user) {
        return convertToResUserDTO(user, userPositionRepository.findActiveFullByUserId(user.getId()));
    }

    private ResUserDTO convertToResUserDTO(User user, List<UserPosition> posList) {

        ResUserDTO res = new ResUserDTO();

        if (user.getRole() != null) {
            ResUserDTO.RoleUser roleUser = new ResUserDTO.RoleUser();
            roleUser.setId(user.getRole().getId());
            roleUser.setName(user.getRole().getName());
            res.setRole(roleUser);
        }

        // ⭐ THÊM CHO HQCV
        if (user.getDirectManager() != null) {
            User directManager = user.getDirectManager();
            res.setDirectManager(new ResUserDTO.ManagerRef(
                    directManager.getId(),
                    directManager.getName(),
                    directManager.getEmail()));

            User indirectManager = directManager.getDirectManager();
            if (indirectManager != null) {
                res.setIndirectManager(new ResUserDTO.ManagerRef(
                        indirectManager.getId(),
                        indirectManager.getName(),
                        indirectManager.getEmail()));
            }
        }

        res.setId(user.getId());
        res.setEmail(user.getEmail());
        res.setName(user.getName());
        res.setActive(user.isActive());
        res.setAvatar(user.getAvatar());
        res.setUpdatedAt(user.getUpdatedAt());
        res.setCreatedAt(user.getCreatedAt());
        res.setLastLoginAt(user.getLastLoginAt());
        res.setLastLoginIp(user.getLastLoginIp());

        if (user.getLastLoginAt() != null) {
            long minutes = Duration.between(user.getLastLoginAt(), Instant.now()).toMinutes();
            if (minutes < 15)
                res.setLastSeenStatus("Vừa đăng nhập");
            else if (minutes < 1440)
                res.setLastSeenStatus("Hôm nay");
            else if (minutes < 10080)
                res.setLastSeenStatus("Gần đây");
            else
                res.setLastSeenStatus("Lâu rồi");
        } else {
            res.setLastSeenStatus("Chưa đăng nhập");
        }

        UserInfo info = user.getUserInfo();
        if (info != null) {
            ResUserDTO.UserInfoBasic userInfoBasic = new ResUserDTO.UserInfoBasic();
            userInfoBasic.setEmployeeCode(info.getEmployeeCode());
            userInfoBasic.setPhone(info.getPhone());
            userInfoBasic.setDateOfBirth(info.getDateOfBirth());
            userInfoBasic.setGender(info.getGender());
            userInfoBasic.setStartDate(info.getStartDate());
            userInfoBasic.setContractSignDate(info.getContractSignDate());
            userInfoBasic.setContractExpireDate(info.getContractExpireDate());
            res.setUserInfo(userInfoBasic);
        }

        if (posList != null && !posList.isEmpty()) {
            List<ResUserDTO.PositionBasic> positionBasics = posList.stream()
                    .map(p -> {
                        ResUserDTO.PositionBasic pb = new ResUserDTO.PositionBasic();
                        pb.setId(p.getId());
                        pb.setSource(p.getSource());

                        switch (p.getSource().toUpperCase()) {
                            case "COMPANY" -> {
                                pb.setCompanyName(p.getCompanyJobTitle().getCompany().getName());
                                pb.setJobTitleNameVi(p.getCompanyJobTitle().getJobTitle().getNameVi());
                            }
                            case "DEPARTMENT" -> {
                                pb.setCompanyName(p.getDepartmentJobTitle().getDepartment().getCompany().getName());
                                pb.setDepartmentName(p.getDepartmentJobTitle().getDepartment().getName());
                                pb.setJobTitleNameVi(p.getDepartmentJobTitle().getJobTitle().getNameVi());
                            }
                            case "SECTION" -> {
                                pb.setCompanyName(
                                        p.getSectionJobTitle().getSection().getDepartment().getCompany().getName());
                                pb.setDepartmentName(p.getSectionJobTitle().getSection().getDepartment().getName());
                                pb.setSectionName(p.getSectionJobTitle().getSection().getName());
                                pb.setJobTitleNameVi(p.getSectionJobTitle().getJobTitle().getNameVi());
                            }
                        }
                        return pb;
                    })
                    .collect(Collectors.toList());

            res.setPositions(positionBasics);
        }

        return res;
    }

    // ======================================================
    // CHANGE PASSWORD
    // ======================================================
    public void changePassword(String oldPassword, String newPassword, PasswordEncoder passwordEncoder) {

        User currentUser = getCurrentUser();

        if (currentUser == null) {
            throw new IdInvalidException("Không tìm thấy người dùng");
        }

        if (!passwordEncoder.matches(oldPassword, currentUser.getPassword())) {
            throw new IdInvalidException("Mật khẩu cũ không chính xác");
        }

        currentUser.setPassword(passwordEncoder.encode(newPassword));
        this.userRepository.save(currentUser);
    }

    // ======================================================
    // GET USERS UNASSIGNED CAREER PATH BY DEPARTMENT
    // ======================================================
    public List<ResUserDTO> getUsersUnassignedCareerPath(Long departmentId) {

        List<String> userIds = userPositionRepository.findUserIdsByDepartmentId(departmentId);

        if (userIds.isEmpty())
            return List.of();

        List<String> assignedIds = employeeCareerPathRepository.findAssignedUserIdsByDepartmentId(departmentId);

        List<String> unassignedIds = userIds.stream()
                .filter(id -> !assignedIds.contains(id))
                .collect(Collectors.toList());

        if (unassignedIds.isEmpty())
            return List.of();

        // ✅ Fix N+1: fetch hàng loạt bằng findAllById thay vì findById từng user trong loop
        return userRepository.findAllById(unassignedIds)
                .stream()
                .map(this::convertToResUserDTO)
                .collect(Collectors.toList());
    }
    private ResultPaginationDTO emptyPagination(Pageable pageable) {
        ResultPaginationDTO rs = new ResultPaginationDTO();
        ResultPaginationDTO.Meta mt = new ResultPaginationDTO.Meta();
        mt.setPage(pageable.getPageNumber() + 1);
        mt.setPageSize(pageable.getPageSize());
        mt.setPages(0);
        mt.setTotal(0L);
        rs.setMeta(mt);
        rs.setResult(List.of());
        return rs;
    }

    // ======================================================
    // FETCH CROSS COMPANY USERS WITH PAGINATION
    // ======================================================
    public ResultPaginationDTO fetchCrossCompanyUsers(String search, Long companyId, java.util.List<Long> departmentIds, Long sectionId, Boolean hasDirectManager, Pageable pageable) {
        Specification<User> spec = (root, query, cb) -> cb.equal(root.get("active"), true);

        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope != null && !scope.isAdminLevel()) {
            if (scope.companyIds().isEmpty()) {
                return emptyPagination(pageable);
            }

            if (companyId != null) {
                if (!scope.companyIds().contains(companyId)) {
                    throw new IdInvalidException("Bạn không có quyền truy cập dữ liệu nhân sự của công ty này");
                }
            } else {
                Specification<User> scopeSpec = (root, query, cb) -> {
                    var sub = query.subquery(String.class);
                    var posRoot = sub.from(UserPosition.class);
                    var cjtCompany = posRoot.join("companyJobTitle", JoinType.LEFT).join("company", JoinType.LEFT);
                    var djtCompany = posRoot.join("departmentJobTitle", JoinType.LEFT).join("department", JoinType.LEFT).join("company", JoinType.LEFT);
                    var sjtCompany = posRoot.join("sectionJobTitle", JoinType.LEFT).join("section", JoinType.LEFT).join("department", JoinType.LEFT).join("company", JoinType.LEFT);

                    sub.select(posRoot.get("user").get("id"))
                            .where(cb.and(
                                    cb.isTrue(posRoot.get("active")),
                                    cb.or(
                                            cb.and(
                                                    cb.equal(posRoot.get("source"), "COMPANY"),
                                                    cjtCompany.get("id").in(scope.companyIds())),
                                            cb.and(
                                                    cb.equal(posRoot.get("source"), "DEPARTMENT"),
                                                    djtCompany.get("id").in(scope.companyIds())),
                                            cb.and(
                                                    cb.equal(posRoot.get("source"), "SECTION"),
                                                    sjtCompany.get("id").in(scope.companyIds())))));

                    return root.get("id").in(sub);
                };
                spec = Specification.where(spec).and(scopeSpec);
            }
        }

        if (search != null && !search.trim().isEmpty()) {
            Specification<User> searchSpec = (root, query, cb) -> {
                String likeSearch = "%" + search.trim().toLowerCase() + "%";
                return cb.or(
                        cb.like(cb.lower(root.get("name")), likeSearch),
                        cb.like(cb.lower(root.get("email")), likeSearch)
                );
            };
            spec = spec.and(searchSpec);
        }

        if (companyId != null) {
            Specification<User> companySpec = (root, query, cb) -> {
                var sub = query.subquery(String.class);
                var posRoot = sub.from(UserPosition.class);
                // LEFT JOIN từng association: mỗi user_position chỉ có đúng 1 FK khác null,
                // navigate ngầm bằng .get() sẽ sinh INNER JOIN cả 3 → loại sạch mọi row.
                var cjtCompany = posRoot.join("companyJobTitle", JoinType.LEFT).join("company", JoinType.LEFT);
                var djtCompany = posRoot.join("departmentJobTitle", JoinType.LEFT).join("department", JoinType.LEFT).join("company", JoinType.LEFT);
                var sjtCompany = posRoot.join("sectionJobTitle", JoinType.LEFT).join("section", JoinType.LEFT).join("department", JoinType.LEFT).join("company", JoinType.LEFT);
                sub.select(posRoot.get("user").get("id"))
                        .where(cb.and(
                                cb.isTrue(posRoot.get("active")),
                                cb.or(
                                        cb.and(cb.equal(posRoot.get("source"), "COMPANY"), cb.equal(cjtCompany.get("id"), companyId)),
                                        cb.and(cb.equal(posRoot.get("source"), "DEPARTMENT"), cb.equal(djtCompany.get("id"), companyId)),
                                        cb.and(cb.equal(posRoot.get("source"), "SECTION"), cb.equal(sjtCompany.get("id"), companyId))
                                )
                        ));
                return root.get("id").in(sub);
            };
            spec = spec.and(companySpec);
        }

        if (departmentIds != null && !departmentIds.isEmpty()) {
            Specification<User> deptSpec = (root, query, cb) -> {
                var sub = query.subquery(String.class);
                var posRoot = sub.from(UserPosition.class);
                var djtDept = posRoot.join("departmentJobTitle", JoinType.LEFT).join("department", JoinType.LEFT);
                var sjtDept = posRoot.join("sectionJobTitle", JoinType.LEFT).join("section", JoinType.LEFT).join("department", JoinType.LEFT);
                sub.select(posRoot.get("user").get("id"))
                        .where(cb.and(
                                cb.isTrue(posRoot.get("active")),
                                cb.or(
                                        cb.and(cb.equal(posRoot.get("source"), "DEPARTMENT"), djtDept.get("id").in(departmentIds)),
                                        cb.and(cb.equal(posRoot.get("source"), "SECTION"), sjtDept.get("id").in(departmentIds))
                                )
                        ));
                return root.get("id").in(sub);
            };
            spec = spec.and(deptSpec);
        }

        if (sectionId != null) {
            Specification<User> sectionSpec = (root, query, cb) -> {
                var sub = query.subquery(String.class);
                var posRoot = sub.from(UserPosition.class);
                sub.select(posRoot.get("user").get("id"))
                        .where(cb.and(
                                cb.isTrue(posRoot.get("active")),
                                cb.equal(posRoot.get("source"), "SECTION"),
                                cb.equal(posRoot.get("sectionJobTitle").get("section").get("id"), sectionId)
                        ));
                return root.get("id").in(sub);
            };
            spec = spec.and(sectionSpec);
        }

        if (hasDirectManager != null) {
            Specification<User> directManagerSpec = (root, query, cb) -> {
                jakarta.persistence.criteria.Join<User, User> managerJoin = root.join("directManager", jakarta.persistence.criteria.JoinType.LEFT);
                if (hasDirectManager) {
                    return cb.and(
                        cb.isNotNull(root.get("directManager")),
                        cb.isNotNull(managerJoin.get("directManager"))
                    );
                } else {
                    return cb.or(
                        cb.isNull(root.get("directManager")),
                        cb.isNull(managerJoin.get("directManager"))
                    );
                }
            };
            spec = spec.and(directManagerSpec);
        }

        Page<User> pageUser = this.userRepository.findAll(spec, pageable);

        ResultPaginationDTO rs = new ResultPaginationDTO();
        ResultPaginationDTO.Meta mt = new ResultPaginationDTO.Meta();
        mt.setPage(pageable.getPageNumber() + 1);
        mt.setPageSize(pageable.getPageSize());
        mt.setPages(pageUser.getTotalPages());
        mt.setTotal(pageUser.getTotalElements());
        rs.setMeta(mt);

        List<User> content = pageUser.getContent();
        if (content.isEmpty()) {
            rs.setResult(List.of());
            return rs;
        }

        // Fetch all active positions for the users on this page in one query
        List<String> userIds = content.stream().map(User::getId).collect(Collectors.toList());
        List<UserPosition> positions = userPositionRepository.findActiveFullByUserIds(userIds);

        // Group positions by userId
        Map<String, List<UserPosition>> positionsByUser = new HashMap<>();
        for (UserPosition up : positions) {
            if (up.getUser() != null) {
                positionsByUser.computeIfAbsent(up.getUser().getId(), k -> new ArrayList<>()).add(up);
            }
        }

        List<ResCrossCompanyUserDTO> dtoList = content.stream().map(user -> {
            ResCrossCompanyUserDTO dto = new ResCrossCompanyUserDTO();
            dto.setId(user.getId());
            dto.setName(user.getName());
            dto.setEmail(user.getEmail());
            dto.setAvatar(user.getAvatar());
            dto.setActive(user.isActive());
            
            if (user.getUserInfo() != null) {
                dto.setEmployeeCode(user.getUserInfo().getEmployeeCode());
            }
            
            if (user.getDirectManager() != null) {
                dto.setDirectManagerId(user.getDirectManager().getId());
                dto.setDirectManagerName(user.getDirectManager().getName());
                dto.setDirectManagerCompanyIds(userPositionRepository.findActiveCompanyIdsByUserId(user.getDirectManager().getId()));
                
                User indirect = user.getDirectManager().getDirectManager();
                if (indirect != null) {
                    dto.setIndirectManagerId(indirect.getId());
                    dto.setIndirectManagerName(indirect.getName());
                    dto.setIndirectManagerCompanyIds(userPositionRepository.findActiveCompanyIdsByUserId(indirect.getId()));
                }
            }
            
            List<UserPosition> userPos = positionsByUser.getOrDefault(user.getId(), List.of());
            populateCompanyAndDepartment(dto, userPos);
            
            return dto;
        }).collect(Collectors.toList());

        rs.setResult(dtoList);
        return rs;
    }

    private void populateCompanyAndDepartment(ResCrossCompanyUserDTO dto, List<UserPosition> positions) {
        if (positions == null || positions.isEmpty()) {
            return;
        }
        // Prefer DEPARTMENT or SECTION positions since they contain departmentName
        UserPosition chosen = null;
        for (UserPosition p : positions) {
            if ("DEPARTMENT".equalsIgnoreCase(p.getSource()) || "SECTION".equalsIgnoreCase(p.getSource())) {
                chosen = p;
                break;
            }
        }
        if (chosen == null) {
            chosen = positions.get(0);
        }

        JobTitle jt = null;
        switch (chosen.getSource().toUpperCase()) {
            case "COMPANY" -> {
                if (chosen.getCompanyJobTitle() != null) {
                    jt = chosen.getCompanyJobTitle().getJobTitle();
                    if (chosen.getCompanyJobTitle().getCompany() != null) {
                        dto.setCompanyName(chosen.getCompanyJobTitle().getCompany().getName());
                    }
                }
            }
            case "DEPARTMENT" -> {
                if (chosen.getDepartmentJobTitle() != null) {
                    jt = chosen.getDepartmentJobTitle().getJobTitle();
                    if (chosen.getDepartmentJobTitle().getDepartment() != null) {
                        var dept = chosen.getDepartmentJobTitle().getDepartment();
                        dto.setDepartmentName(dept.getName());
                        if (dept.getCompany() != null) {
                            dto.setCompanyName(dept.getCompany().getName());
                        }
                    }
                }
            }
            case "SECTION" -> {
                if (chosen.getSectionJobTitle() != null) {
                    jt = chosen.getSectionJobTitle().getJobTitle();
                    if (chosen.getSectionJobTitle().getSection() != null) {
                        var sec = chosen.getSectionJobTitle().getSection();
                        if (sec.getDepartment() != null) {
                            dto.setDepartmentName(sec.getDepartment().getName());
                            if (sec.getDepartment().getCompany() != null) {
                                dto.setCompanyName(sec.getDepartment().getCompany().getName());
                            }
                        }
                    }
                }
            }
        }
        if (jt != null) {
            dto.setJobTitle(jt.getNameVi());
            if (jt.getPositionLevel() != null) {
                dto.setPositionLevel(jt.getPositionLevel().getCode());
            }
        }
    }
}
