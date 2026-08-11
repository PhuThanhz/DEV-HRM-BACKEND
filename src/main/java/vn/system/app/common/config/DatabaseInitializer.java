package vn.system.app.common.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.modules.permission.domain.Permission;
import vn.system.app.modules.permission.repository.PermissionRepository;
import vn.system.app.modules.role.domain.Role;
import vn.system.app.modules.role.repository.RoleRepository;
import vn.system.app.modules.user.domain.User;
import vn.system.app.modules.user.repository.UserRepository;
import vn.system.app.modules.documentcategory.domain.DocumentCategory;
import vn.system.app.modules.documentcategory.repository.DocumentCategoryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.Environment;

/**
 * Seed dữ liệu dev. @Order(1) đảm bảo chạy trước các runner phụ thuộc vào dữ liệu
 * seed này (vd JobDescriptionItemBackfillRunner cần seed job_description_tasks
 * đã tồn tại thì mới tách được sang bảng item).
 */
@Service
@Order(1)
public class DatabaseInitializer implements CommandLineRunner {
    private static final String ACCOUNTING_DOSSIERS_MODULE = "ACCOUNTING_DOSSIERS";
    private static final Set<String> ALL_ACCOUNTING_DOSSIERS_SUBMODULES = Set.of(
            "ACCOUNTING_DOSSIERS_MGMT",
            "ACCOUNTING_DOSSIERS_DOCS",
            "ACCOUNTING_DOSSIERS_WORKFLOW",
            "ACCOUNTING_DOSSIERS_TEMPLATES",
            "ACCOUNTING_DOSSIERS_REPORTS"
    );
    private static final String ACCOUNTING_DOCUMENTS_MODULE = "ACCOUNTING_DOCUMENTS";
    private static final String ACCOUNTING_WORKFLOWS_MODULE = "ACCOUNTING_WORKFLOWS";
    private static final String ACCOUNTING_DELEGATIONS_MODULE = "ACCOUNTING_DELEGATIONS";
    private static final String DIRECTOR_APPROVAL_PERMISSION_NAME = "Phê duyệt bộ chứng từ kế toán - Giám đốc";
    private static final String ACCOUNTANT_APPROVAL_PERMISSION_NAME = "Phê duyệt bộ chứng từ kế toán - Kế toán";
    private static final String CHIEF_ACCOUNTANT_APPROVAL_PERMISSION_NAME = "Phê duyệt bộ chứng từ kế toán - Kế toán trưởng";
    private static final Set<String> NON_ADMIN_BUSINESS_APPROVER_PERMISSION_NAMES = Set.of(
            ACCOUNTANT_APPROVAL_PERMISSION_NAME,
            CHIEF_ACCOUNTANT_APPROVAL_PERMISSION_NAME
    );

    private final PermissionRepository permissionRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final DocumentCategoryRepository documentCategoryRepository;
    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;
    private List<Permission> permissionCache;
    private Set<PermissionKey> permissionKeyCache;
    private Set<String> permissionNameCache;
    private Map<String, Role> roleCache;

    private record PermissionKey(String module, String apiPath, String method) {
        private static PermissionKey of(String module, String apiPath, String method) {
            return new PermissionKey(module, apiPath, method.toUpperCase(Locale.ROOT));
        }
    }

    public DatabaseInitializer(
            PermissionRepository permissionRepository,
            RoleRepository roleRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            DocumentCategoryRepository documentCategoryRepository,
            JdbcTemplate jdbcTemplate,
            Environment environment) {
        this.permissionRepository = permissionRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.documentCategoryRepository = documentCategoryRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
    }

    private boolean isAccountingDossiersModule(String module) {
        return ACCOUNTING_DOSSIERS_MODULE.equals(module) || ALL_ACCOUNTING_DOSSIERS_SUBMODULES.contains(module);
    }

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        System.out.println(">>> START INIT DATABASE");

        // Tự động migration gộp các module phân quyền ACCOUNTING_DOSSIERS_% lại thành ACCOUNTING_DOSSIERS cũ
        try {
            String updateSql = "UPDATE permissions SET module = 'ACCOUNTING_DOSSIERS' WHERE module LIKE 'ACCOUNTING_DOSSIERS_%'";
            int updatedRows = this.jdbcTemplate.update(updateSql);
            if (updatedRows > 0) {
                System.out.println(">>> MIGRATION: Merged " + updatedRows + " ACCOUNTING_DOSSIERS submodules back to ACCOUNTING_DOSSIERS");
            }
        } catch (Exception e) {
            System.err.println(">>> MIGRATION ERROR: Failed to merge ACCOUNTING_DOSSIERS submodules: " + e.getMessage());
        }

        initializePermissionCache();
        initializeRoleCache();
        long countPermissions = this.permissionCache.size();
        long countRoles = this.roleCache.size();
        long countUsers = this.userRepository.count();

        // Only seed test data on local/demo/UAT, never on production
        boolean isProd = false;
        if (this.environment != null) {
            for (String profile : this.environment.getActiveProfiles()) {
                if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                    isProd = true;
                    break;
                }
            }
            String dbUrl = this.environment.getProperty("spring.datasource.url");
            if (dbUrl != null && !dbUrl.contains("localhost") && !dbUrl.contains("127.0.0.1") && !dbUrl.contains("hrm_0107") && !dbUrl.contains("hrm_21062026")) {
                isProd = true;
            }
        }

        // Kiểm tra và khởi tạo danh mục chứng từ kế toán
        DocumentCategory accCategory = null;
        try {
            accCategory = this.documentCategoryRepository.findByCategoryCode("ACCOUNTING_DOC").orElse(null);
        } catch (Exception e) {}

        if (accCategory == null) {
            accCategory = new DocumentCategory();
            accCategory.setCategoryCode("ACCOUNTING_DOC");
            accCategory.setCategoryName("Chứng từ Kế toán");
            accCategory.setSymbol("CTKT");
            accCategory.setDefinition("Danh mục dùng riêng cho kho lưu trữ chứng từ của phòng Kế toán");
            accCategory.setActive(true);
            accCategory.setMappingProcedure(false);
            accCategory.setCrossCompany(false);
            this.documentCategoryRepository.save(accCategory);
            System.out.println(">>> SEED: ACCOUNTING_DOC Category created");
        }

        ensureEvaluationCommentTypeSupportsOverrideNote();
        ensureDashboardCompletenessIndexesExist();
        ensureEvaluationWeightUnitsAreCorrect();
        normalizeEvaluationSubcriteriaWeights();

        // Tự động seed quyền cho ACCOUNTING_DOCUMENTS
        String accModule = ACCOUNTING_DOCUMENTS_MODULE;
        List<Permission> existingAccPerms = allPermissions().stream()
                .filter(p -> accModule.equals(p.getModule()))
                .toList();
        
        if (existingAccPerms.isEmpty()) {
            List<Permission> newPerms = new ArrayList<>();
            newPerms.add(createPermission("Danh sách chứng từ kế toán", "/api/v1/accounting-documents", "GET", accModule));
            newPerms.add(createPermission("Chi tiết chứng từ kế toán", "/api/v1/accounting-documents/{id}", "GET", accModule));
            newPerms.add(createPermission("Tạo mới chứng từ kế toán", "/api/v1/accounting-documents", "POST", accModule));
            newPerms.add(createPermission("Cập nhật chứng từ kế toán", "/api/v1/accounting-documents/{id}", "PUT", accModule));
            newPerms.add(createPermission("Xóa chứng từ kế toán", "/api/v1/accounting-documents/{id}", "DELETE", accModule));
            savePermissions(newPerms);
            System.out.println(">>> SEED: ACCOUNTING_DOCUMENTS permissions created");
            
            // Cập nhật lại adminRole nếu nó đã tồn tại
            Role adminRole = findRole("SUPER_ADMIN");
            if (adminRole != null) {
                List<Permission> allPerms = new ArrayList<>(adminRole.getPermissions());
                allPerms.addAll(newPerms);
                adminRole.setPermissions(allPerms);
                saveRole(adminRole);
            }
        }

        List<Permission> newPerms = new ArrayList<>();
        String dossierModule = ACCOUNTING_DOSSIERS_MODULE;
        addPermissionIfMissing(newPerms, "Danh sách bộ chứng từ kế toán", "/api/v1/accounting-dossiers", "GET", dossierModule);
        addPermissionIfMissing(newPerms, "Chi tiết bộ chứng từ kế toán", "/api/v1/accounting-dossiers/{id}", "GET", dossierModule);
        addPermissionIfMissing(newPerms, "Tạo mới bộ chứng từ kế toán", "/api/v1/accounting-dossiers", "POST", dossierModule);
        addPermissionIfMissing(newPerms, "Cập nhật bộ chứng từ kế toán", "/api/v1/accounting-dossiers/{id}", "PUT", dossierModule);
        addPermissionIfMissing(newPerms, "Xóa bộ chứng từ kế toán", "/api/v1/accounting-dossiers/{id}", "DELETE", dossierModule);
        addPermissionIfMissing(newPerms, "Chuyển xử lý bộ chứng từ kế toán", "/api/v1/accounting-dossiers/{id}/submit", "POST", dossierModule);
        addPermissionIfMissing(newPerms, "Yêu cầu hoàn bộ chứng từ kế toán", "/api/v1/accounting-dossiers/{id}/request-return", "POST", dossierModule);
        addPermissionIfMissing(newPerms, "Xem lịch sử bộ chứng từ kế toán", "/api/v1/accounting-dossiers/{id}/logs", "GET", dossierModule);
        addPermissionIfMissing(newPerms, "Danh sách mẫu bộ chứng từ kế toán", "/api/v1/accounting-dossiers/categories", "GET", dossierModule);
        addPermissionIfMissing(newPerms, "Danh sách mẫu bộ chứng từ kế toán đang dùng", "/api/v1/accounting-dossiers/categories/active", "GET", dossierModule);
        addPermissionIfMissing(newPerms, "Tạo mẫu bộ chứng từ kế toán", "/api/v1/accounting-dossiers/categories", "POST", dossierModule);
        addPermissionIfMissing(newPerms, "Cập nhật mẫu bộ chứng từ kế toán", "/api/v1/accounting-dossiers/categories/{categoryId}", "PUT", dossierModule);
        addPermissionIfMissing(newPerms, "Bật/tắt mẫu bộ chứng từ kế toán", "/api/v1/accounting-dossiers/categories/{categoryId}/active", "PUT", dossierModule);
        addPermissionIfMissing(newPerms, "Xóa mẫu bộ chứng từ kế toán", "/api/v1/accounting-dossiers/categories/{categoryId}", "DELETE", dossierModule);
        addPermissionIfMissing(newPerms, "Tra cứu chứng từ con theo bộ chứng từ", "/api/v1/accounting-dossiers/documents", "GET", dossierModule);
        addPermissionIfMissing(newPerms, "Danh sách chứng từ con trong bộ", "/api/v1/accounting-dossiers/{id}/documents", "GET", dossierModule);
        addPermissionIfMissing(newPerms, "Thêm chứng từ con vào bộ", "/api/v1/accounting-dossiers/{id}/documents", "POST", dossierModule);
        addPermissionIfMissing(newPerms, "Sửa chứng từ con trong bộ", "/api/v1/accounting-dossiers/{id}/documents/{docId}", "PUT", dossierModule);
        addPermissionIfMissing(newPerms, "Xóa chứng từ con trong bộ", "/api/v1/accounting-dossiers/{id}/documents/{docId}", "DELETE", dossierModule);
        addPermissionIfMissing(newPerms, "Kiểm tra chứng từ con trong bộ", "/api/v1/accounting-dossiers/{id}/documents/{docId}/check", "POST", dossierModule);
        addPermissionIfMissing(newPerms, "Danh sách bộ chứng từ chờ tôi duyệt", "/api/v1/accounting-dossiers/pending-my-approval", "GET", dossierModule);
        addPermissionIfMissing(newPerms, "Phê duyệt bước bộ chứng từ kế toán", "/api/v1/accounting-dossiers/{id}/approve", "POST", dossierModule);
        addPermissionIfMissing(newPerms, "Từ chối bộ chứng từ kế toán", "/api/v1/accounting-dossiers/{id}/reject", "POST", dossierModule);
        addPermissionIfMissing(newPerms, "Chấm dứt bộ chứng từ kế toán", "/api/v1/accounting-dossiers/{id}/terminate", "POST", dossierModule);
        addPermissionIfMissing(newPerms, "Mở lại bộ chứng từ kế toán", "/api/v1/accounting-dossiers/{id}/reopen", "POST", dossierModule);
        addPermissionIfMissing(newPerms, "Đưa bộ chứng từ kế toán vào lưu trữ", "/api/v1/accounting-dossiers/{id}/archive", "POST", dossierModule);
        addPermissionIfMissing(newPerms, "Phản hồi yêu cầu hoàn bộ chứng từ", "/api/v1/accounting-dossiers/{id}/return-response", "POST", dossierModule);
        addPermissionIfMissing(newPerms, "Nhận xử lý bước duyệt bộ chứng từ", "/api/v1/accounting-dossiers/{id}/claim", "POST", dossierModule);
        addPermissionIfMissing(newPerms, "Từ chối đồng bộ bộ chứng từ phi cấu trúc thành mẫu", "/api/v1/accounting-dossiers/{id}/sync-template/reject", "POST", dossierModule);
        addPermissionIfMissing(newPerms, "Danh sách tiến trình duyệt bộ chứng từ", "/api/v1/accounting-dossiers/{id}/approval-steps", "GET", dossierModule);
        addPermissionIfMissing(newPerms, "Duyệt hàng loạt bộ chứng từ kế toán", "/api/v1/accounting-dossiers/bulk-approve", "POST", dossierModule);
        addPermissionIfMissing(newPerms, "Duyệt hàng loạt bộ chứng từ kế toán (lộ trình)", "/api/v1/accounting-dossiers/bulk/approve", "POST", dossierModule);
        addPermissionIfMissing(newPerms, "Từ chối hàng loạt bộ chứng từ kế toán", "/api/v1/accounting-dossiers/bulk/reject", "POST", dossierModule);
        addPermissionIfMissing(newPerms, "Kiểm tra hàng loạt chứng từ con", "/api/v1/accounting-dossiers/{id}/documents/bulk-check", "POST", dossierModule);
        addPermissionIfMissing(newPerms, "Kiểm tra hàng loạt chứng từ con (lộ trình)", "/api/v1/accounting-dossiers/{id}/documents/bulk/check", "POST", dossierModule);
        addPermissionIfMissing(newPerms, "Cập nhật trạng thái hết thời hạn lưu trữ", "/api/v1/accounting-dossiers/storage/refresh-expired", "POST", dossierModule);
        addPermissionIfMissing(newPerms, "Tổng quan lưu trữ bộ chứng từ kế toán", "/api/v1/accounting-dossiers/dashboard/summary", "GET", dossierModule);
        addPermissionIfMissing(newPerms, "Thống kê bộ chứng từ đang chờ duyệt theo vai trò", "/api/v1/accounting-dossiers/dashboard/pending-by-role", "GET", dossierModule);
        addPermissionIfMissing(newPerms, "Báo cáo bộ chứng từ theo trạng thái", "/api/v1/accounting-dossiers/reports/by-status", "GET", dossierModule);
        addPermissionIfMissing(newPerms, "Báo cáo bộ chứng từ theo phòng ban", "/api/v1/accounting-dossiers/reports/by-department", "GET", dossierModule);
        addPermissionIfMissing(newPerms, "Báo cáo bộ chứng từ theo danh mục", "/api/v1/accounting-dossiers/reports/by-category", "GET", dossierModule);
        addPermissionIfMissing(newPerms, "Tra cứu bộ chứng từ qua QR", "/api/v1/accounting-dossiers/qr/{token}", "GET", dossierModule);
        addPermissionByNameIfMissing(newPerms, DIRECTOR_APPROVAL_PERMISSION_NAME, "/api/v1/accounting-dossiers/{id}/approve", "POST", dossierModule);
        addPermissionByNameIfMissing(newPerms, ACCOUNTANT_APPROVAL_PERMISSION_NAME, "/api/v1/accounting-dossiers/{id}/approve", "POST", dossierModule);
        addPermissionByNameIfMissing(newPerms, CHIEF_ACCOUNTANT_APPROVAL_PERMISSION_NAME, "/api/v1/accounting-dossiers/{id}/approve", "POST", dossierModule);
        addPermissionIfMissing(newPerms, "Emergency Reassign Giám đốc", "/api/v1/accounting-dossiers/{id}/reassign-director", "POST", dossierModule);
        addPermissionIfMissing(newPerms, "Quét quá hạn SLA duyệt bộ chứng từ", "/api/v1/accounting-approval-sla/scan-overdue", "POST", dossierModule);

        if (!newPerms.isEmpty()) {
            savePermissions(newPerms);
            System.out.println(">>> SEED: ACCOUNTING_DOSSIERS permissions created");

            Role adminRole = findRole("SUPER_ADMIN");
            if (adminRole != null) {
                List<Permission> allPerms = new ArrayList<>(adminRole.getPermissions());
                allPerms.addAll(newPerms);
                adminRole.setPermissions(allPerms);
                saveRole(adminRole);
            }
        }

        // Ensure KETOAN role and user exist
        Role ketoanRole = findRole("KETOAN");
        if (ketoanRole == null) {
            ketoanRole = new Role();
            ketoanRole.setName("KETOAN");
            ketoanRole.setDescription("Kế toán viên");
            ketoanRole.setActive(true);
            List<Permission> allPerms = allPermissions().stream()
                    .filter(p -> isAccountingDossiersModule(p.getModule()) || ACCOUNTING_DOCUMENTS_MODULE.equals(p.getModule()))
                    .toList();
            ketoanRole.setPermissions(allPerms);
            ketoanRole = saveRole(ketoanRole);
        }

        if (!isProd) {
            User ketoanUser = this.userRepository.findByEmail("ketoan@gmail.com");
            if (ketoanUser == null) {
                ketoanUser = new User();
                ketoanUser.setEmail("ketoan@gmail.com");
                ketoanUser.setName("Kế Toán Viên Test");
                ketoanUser.setPassword(this.passwordEncoder.encode("123456"));
                ketoanUser.setActive(true);
                ketoanUser.setRole(ketoanRole);
                this.userRepository.save(ketoanUser);
            }
        }

        // Ensure KETOANTRUONG role and user exist
        Role ketoanTruongRole = findRole("KETOANTRUONG");
        if (ketoanTruongRole == null) {
            ketoanTruongRole = new Role();
            ketoanTruongRole.setName("KETOANTRUONG");
            ketoanTruongRole.setDescription("Kế toán trưởng");
            ketoanTruongRole.setActive(true);
            List<Permission> allPerms = allPermissions().stream()
                    .filter(p -> isAccountingDossiersModule(p.getModule()) || ACCOUNTING_DOCUMENTS_MODULE.equals(p.getModule()))
                    .toList();
            ketoanTruongRole.setPermissions(allPerms);
            ketoanTruongRole = saveRole(ketoanTruongRole);
        }

        if (!isProd) {
            User ketoanTruongUser = this.userRepository.findByEmail("ketoantruong@gmail.com");
            if (ketoanTruongUser == null) {
                ketoanTruongUser = new User();
                ketoanTruongUser.setEmail("ketoantruong@gmail.com");
                ketoanTruongUser.setName("Kế Toán Trưởng Test");
                ketoanTruongUser.setPassword(this.passwordEncoder.encode("123456"));
                ketoanTruongUser.setActive(true);
                ketoanTruongUser.setRole(ketoanTruongRole);
                this.userRepository.save(ketoanTruongUser);
            }
        }

        // Mỗi role nghiệp vụ chỉ giữ đúng permission phê duyệt của mình, không giữ chéo permission phê duyệt
        // của cấp khác (kể cả role đã tồn tại từ trước) — nếu không resolver permission-based sẽ chọn nhầm người duyệt.
        List<Permission> accountantApprovalPerm = allPermissions().stream()
                .filter(p -> ACCOUNTANT_APPROVAL_PERMISSION_NAME.equals(p.getName()))
                .toList();
        List<Permission> chiefApprovalPerm = allPermissions().stream()
                .filter(p -> CHIEF_ACCOUNTANT_APPROVAL_PERMISSION_NAME.equals(p.getName()))
                .toList();
        addPermissionsToRoleIfMissing("KETOAN", accountantApprovalPerm);
        removePermissionsFromRoleIfPresent("KETOAN",
                Set.of(CHIEF_ACCOUNTANT_APPROVAL_PERMISSION_NAME, DIRECTOR_APPROVAL_PERMISSION_NAME));
        addPermissionsToRoleIfMissing("KETOANTRUONG", chiefApprovalPerm);
        removePermissionsFromRoleIfPresent("KETOANTRUONG",
                Set.of(ACCOUNTANT_APPROVAL_PERMISSION_NAME, DIRECTOR_APPROVAL_PERMISSION_NAME));

        // Ensure EMPLOYEE and DEPARTMENT_MANAGER roles have accounting permissions
        Role employeeRole = findRole("EMPLOYEE");
        if (employeeRole != null) {
            // Keep non-accounting permissions
            List<Permission> nonAccPerms = employeeRole.getPermissions().stream()
                    .filter(p -> !isAccountingDossiersModule(p.getModule()) && !ACCOUNTING_DOCUMENTS_MODULE.equals(p.getModule()))
                    .toList();
            
            // Get restricted accounting permissions for EMPLOYEE
            List<Permission> allowedAccPerms = allPermissions().stream()
                    .filter(p -> isAccountingDossiersModule(p.getModule()) || ACCOUNTING_DOCUMENTS_MODULE.equals(p.getModule()))
                    .filter(p -> {
                        String path = p.getApiPath();
                        // Filter out approval, template, and report paths
                        if (path.contains("/approve") || path.contains("/reject") || path.contains("/terminate") || path.contains("/archive") || path.contains("/bulk")) {
                            return false;
                        }
                        if (path.equals("/api/v1/accounting-dossiers/categories") && "POST".equalsIgnoreCase(p.getMethod())) {
                            return false;
                        }
                        if (path.startsWith("/api/v1/accounting-dossiers/categories/{categoryId}")) {
                            return false;
                        }
                        if (path.contains("/dashboard/") || path.contains("/reports/")) {
                            return false;
                        }
                        return true;
                    })
                    .toList();
                    
            List<Permission> finalPerms = new ArrayList<>(nonAccPerms);
            finalPerms.addAll(allowedAccPerms);
            replaceRolePermissionsIfChanged(employeeRole, finalPerms);
        }

        Role deptMgrRole = findRole("DEPARTMENT_MANAGER");
        if (deptMgrRole != null) {
            List<Permission> dossierPerms = allPermissions().stream()
                    .filter(p -> isAccountingDossiersModule(p.getModule()) || ACCOUNTING_DOCUMENTS_MODULE.equals(p.getModule()))
                    .filter(p -> !DIRECTOR_APPROVAL_PERMISSION_NAME.equals(p.getName()))
                    .filter(p -> !NON_ADMIN_BUSINESS_APPROVER_PERMISSION_NAMES.contains(p.getName()))
                    .toList();
            List<Permission> currentPerms = new ArrayList<>(deptMgrRole.getPermissions());
            currentPerms.removeIf(p -> p != null && DIRECTOR_APPROVAL_PERMISSION_NAME.equals(p.getName()));
            currentPerms.removeIf(p -> p != null && NON_ADMIN_BUSINESS_APPROVER_PERMISSION_NAMES.contains(p.getName()));
            for (Permission p : dossierPerms) {
                boolean exists = currentPerms.stream()
                        .filter(Objects::nonNull)
                        .anyMatch(item -> item.getId() == p.getId());
                if (!exists) {
                    currentPerms.add(p);
                }
            }
            replaceRolePermissionsIfChanged(deptMgrRole, currentPerms);
        }
        if (!isProd) {
            // Create manager@gmail.com
            User managerUser = this.userRepository.findByEmail("manager@gmail.com");
            if (managerUser == null) {
                managerUser = new User();
                managerUser.setEmail("manager@gmail.com");
                managerUser.setName("Trưởng Bộ Phận Test");
                managerUser.setPassword(this.passwordEncoder.encode("123456"));
                managerUser.setActive(true);
                managerUser.setRole(deptMgrRole);
                managerUser = this.userRepository.save(managerUser);
            }

            // Create creator@gmail.com and link to manager
            User creatorUser = this.userRepository.findByEmail("creator@gmail.com");
            if (creatorUser == null) {
                creatorUser = new User();
                creatorUser.setEmail("creator@gmail.com");
                creatorUser.setName("Nhân Viên Lập Test");
                creatorUser.setPassword(this.passwordEncoder.encode("123456"));
                creatorUser.setActive(true);
                creatorUser.setRole(employeeRole);
                creatorUser.setDirectManager(managerUser);
                this.userRepository.save(creatorUser);
            }
        }

        // Ensure DIRECTOR role and user exist (only for non-production environments)
        Role directorRole = findRole("DIRECTOR");
        if (directorRole == null) {
            directorRole = new Role();
            directorRole.setName("DIRECTOR");
            directorRole.setDescription("Giám đốc");
            directorRole.setActive(true);
            List<Permission> allPerms = allPermissions().stream()
                    .filter(p -> isAccountingDossiersModule(p.getModule()) || ACCOUNTING_DOCUMENTS_MODULE.equals(p.getModule()))
                    .filter(p -> !NON_ADMIN_BUSINESS_APPROVER_PERMISSION_NAMES.contains(p.getName()))
                    .toList();
            directorRole.setPermissions(allPerms);
            directorRole = saveRole(directorRole);
        } else {
            // Đảm bảo DIRECTOR role luôn có đầy đủ ACCOUNTING_DOSSIERS permissions (bao gồm cả permission mới thêm)
            List<Permission> dossierPerms = allPermissions().stream()
                    .filter(p -> isAccountingDossiersModule(p.getModule()) || ACCOUNTING_DOCUMENTS_MODULE.equals(p.getModule()))
                    .filter(p -> !NON_ADMIN_BUSINESS_APPROVER_PERMISSION_NAMES.contains(p.getName()))
                    .toList();
            List<Permission> currentDirPerms = new ArrayList<>(directorRole.getPermissions());
            currentDirPerms.removeIf(p -> p != null && NON_ADMIN_BUSINESS_APPROVER_PERMISSION_NAMES.contains(p.getName()));
            boolean updated = false;
            for (Permission p : dossierPerms) {
                if (!currentDirPerms.contains(p)) {
                    currentDirPerms.add(p);
                    updated = true;
                }
            }
            if (updated) {
                directorRole.setPermissions(currentDirPerms);
                directorRole = saveRole(directorRole);
                System.out.println(">>> SEED: Updated DIRECTOR role with new accounting permissions");
            }
        }

        // Only seed director@gmail.com on local/demo/UAT, never on production
        if (!isProd) {
            User directorUser = this.userRepository.findByEmail("director@gmail.com");
            if (directorUser == null) {
                directorUser = new User();
                directorUser.setEmail("director@gmail.com");
                directorUser.setName("Giám Đốc Test");
                directorUser.setPassword(this.passwordEncoder.encode("123456"));
                directorUser.setActive(true);
                directorUser.setRole(directorRole);
                directorUser = this.userRepository.save(directorUser);

                // Add user position mapping for Director dynamically to map to Company ID 1
                try {
                    String dId = directorUser.getId();
                    jdbcTemplate.update("insert into user_info (user_id, employee_code, phone, gender, created_at, created_by) values " +
                        "(?, 'NV-GD-01', '0900000001', 'MALE', now(), 'system')", dId);

                    Long deptJobTitleId = null;
                    try {
                        deptJobTitleId = jdbcTemplate.queryForObject(
                            "SELECT djt.id FROM department_job_titles djt " +
                            "JOIN departments d ON djt.department_id = d.id " +
                            "WHERE d.company_id = 1 LIMIT 1", Long.class);
                    } catch (Exception ex) {
                        try {
                            deptJobTitleId = jdbcTemplate.queryForObject(
                                "SELECT id FROM department_job_titles LIMIT 1", Long.class);
                        } catch (Exception e2) {}
                    }

                    if (deptJobTitleId != null) {
                        Integer posCount = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM user_positions WHERE user_id = ?", Integer.class, dId);
                        if (posCount == null || posCount == 0) {
                            jdbcTemplate.update(
                                "insert into user_positions (active, created_at, department_job_title_id, source, user_id, created_by, updated_by) values " +
                                "(1, now(), ?, 'DEPARTMENT', ?, 'system', 'system')",
                                deptJobTitleId, dId);
                        }
                    }
                } catch (Exception ex) {
                    System.err.println(">>> SEED: Error linking position for director: " + ex.getMessage());
                }
            }
        }

        seedDepartmentObjectivePermissionsAndRole();
        seedAdminScopePermissionsAndRole();
        seedAccountingWorkflowPermissionsAndRole();
        seedAccountingDelegationPermissionsAndRole();
        seedEvaluationPermissionsAndRole();
        seedEvaluationTemplateDeletePermission();
        seedEvaluationReassignPermission();
        seedTaskPermissionsAndRole(isProd);
        seedApprovalDelegationPermissionsAndRole();
        syncFullPermissionRoles();

        if (countRoles == 0) {
            List<Permission> allPermissions = allPermissions();

            Role adminRole = new Role();
            adminRole.setName("SUPER_ADMIN");
            adminRole.setDescription("Admin thì full permissions");
            adminRole.setActive(true);
            adminRole.setPermissions(allPermissions);

            saveRole(adminRole);
        }

        if (countUsers == 0) {
            User adminUser = new User();
            adminUser.setEmail("admin@gmail.com");
            adminUser.setName("I'm super admin");
            adminUser.setPassword(this.passwordEncoder.encode("123456"));
            adminUser.setActive(true); // ✅ FIX CHÍNH Ở ĐÂY

            Role adminRole = findRole("SUPER_ADMIN");
            if (adminRole != null) {
                adminUser.setRole(adminRole);
            }

            this.userRepository.save(adminUser);
        }

        if (!isProd) {
            // Link UserInfo and UserPositions for test accounts using JdbcTemplate to prevent circular entity dependencies
            try {
                // Check if user_info already exists for ketoantruong
                Integer infoCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM user_info WHERE user_id = '5226bbeb-e360-4700-a76a-a0ed0a332d4a'", Integer.class);
                if (infoCount == null || infoCount == 0) {
                    jdbcTemplate.update("insert into user_info (user_id, employee_code, phone, gender, created_at, created_by) values " +
                        "('5226bbeb-e360-4700-a76a-a0ed0a332d4a', 'NV-KTT-01', '0912345678', 'MALE', now(), 'system')");
                }
                
                Integer infoCount2 = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM user_info WHERE user_id = '8937e8c6-c695-4602-af78-93ff80449f1e'", Integer.class);
                if (infoCount2 == null || infoCount2 == 0) {
                    jdbcTemplate.update("insert into user_info (user_id, employee_code, phone, gender, created_at, created_by) values " +
                        "('8937e8c6-c695-4602-af78-93ff80449f1e', 'NV-KTV-02', '0987654321', 'FEMALE', now(), 'system')");
                }
                
                Integer posCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM user_positions WHERE user_id = '5226bbeb-e360-4700-a76a-a0ed0a332d4a'", Integer.class);
                if (posCount == null || posCount == 0) {
                    jdbcTemplate.update("insert into user_positions (active, created_at, department_job_title_id, source, user_id, created_by, updated_by) values " +
                        "(1, now(), 123, 'DEPARTMENT', '5226bbeb-e360-4700-a76a-a0ed0a332d4a', 'system', 'system')");
                }
                
                Integer posCount2 = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM user_positions WHERE user_id = '8937e8c6-c695-4602-af78-93ff80449f1e'", Integer.class);
                if (posCount2 == null || posCount2 == 0) {
                    jdbcTemplate.update("insert into user_positions (active, created_at, department_job_title_id, source, user_id, created_by, updated_by) values " +
                        "(1, now(), 128, 'DEPARTMENT', '8937e8c6-c695-4602-af78-93ff80449f1e', 'system', 'system')");
                }
            } catch (Exception e) {
                System.err.println(">>> SEED: Error creating test accountant user positions/info: " + e.getMessage());
            }

            seedUatUsers();
            seedAccountingApprovalDelegations();
        }

        // Đảm bảo 100% user trong DB (kể cả cũ hay mới) đều có user_positions & user_info
        ensureAllUsersHavePositionsAndInfo();

        if (countPermissions > 0 && countRoles > 0 && countUsers > 0) {
            System.out.println(">>> SKIP INIT DATABASE ~ ALREADY HAVE DATA...");
        } else
        System.out.println(">>> END INIT DATABASE");
    }

    private void ensureEvaluationCommentTypeSupportsOverrideNote() {
        try {
            String columnType = this.jdbcTemplate.queryForObject("""
                    SELECT COLUMN_TYPE
                    FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 'evaluation_comments'
                      AND COLUMN_NAME = 'comment_type'
                    """, String.class);

            if (columnType != null && columnType.contains("APPROVER_OVERRIDE_NOTE")) {
                System.out.println(">>> SCHEMA: evaluation_comments.comment_type already supports APPROVER_OVERRIDE_NOTE");
                return;
            }

            this.jdbcTemplate.execute("""
                    ALTER TABLE evaluation_comments
                    MODIFY comment_type ENUM('SELF_REVIEW', 'MANAGER_FEEDBACK', 'REJECTION_REASON', 'APPROVER_OVERRIDE_NOTE') NOT NULL
                    """);
            System.out.println(">>> SCHEMA: evaluation_comments.comment_type upgraded with APPROVER_OVERRIDE_NOTE");
        } catch (Exception e) {
            System.out.println(">>> SCHEMA: skip evaluation_comments.comment_type upgrade: " + e.getMessage());
        }
    }

    private void ensureDashboardCompletenessIndexesExist() {
        try {
            createIndexIfMissing("job_position_charts", "idx_job_position_charts_department", "department_id");
            createIndexIfMissing("permission_categories", "idx_permission_categories_department_active", "department_id, active");
            createIndexIfMissing("career_paths", "idx_career_paths_department_active", "department_id, active");
            createIndexIfMissing("department_job_titles", "idx_department_job_titles_department_active", "department_id, active");
        } catch (Exception e) {
            System.out.println(">>> SCHEMA: skip index creation: " + e.getMessage());
        }
    }

    private void createIndexIfMissing(String tableName, String indexName, String columns) {
        try {
            Integer exists = this.jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.statistics
                    WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
                    """, Integer.class, tableName, indexName);
            if (exists == null || exists == 0) {
                this.jdbcTemplate.execute("CREATE INDEX `" + indexName + "` ON `" + tableName + "` (" + columns + ")");
                System.out.println(">>> SCHEMA: index " + indexName + " created successfully.");
            }
        } catch (Exception e) {
            System.out.println(">>> SCHEMA: skip creating index " + indexName + ": " + e.getMessage());
        }
    }

    private void ensureEvaluationWeightUnitsAreCorrect() {
        try {
            int secRows = this.jdbcTemplate.update("""
                    UPDATE template_sections
                    SET weight = weight / 100.0
                    WHERE id > 0 AND weight > 1.0
                    """);
            int critRows = this.jdbcTemplate.update("""
                    UPDATE template_criteria
                    SET weight = weight / 100.0
                    WHERE id > 0 AND weight > 1.0
                    """);
            if (secRows > 0 || critRows > 0) {
                System.out.println(">>> SCHEMA: Fixed weight units (sections updated: " + secRows + ", criteria updated: " + critRows + ")");
            }
        } catch (Exception e) {
            System.out.println(">>> SCHEMA: skip weight units fix: " + e.getMessage());
        }
    }

    private void normalizeEvaluationSubcriteriaWeights() {
        try {
            int rows = this.jdbcTemplate.update("""
                    UPDATE template_criteria
                    SET weight = 0
                    WHERE id > 0
                      AND parent_id IS NOT NULL
                      AND weight <> 0
                    """);
            if (rows > 0) {
                System.out.println(">>> SCHEMA: Normalized " + rows + " legacy sub-criteria weights to 0.");
            }
        } catch (Exception e) {
            System.out.println(">>> SCHEMA: skip subcriteria weight normalization: " + e.getMessage());
        }
    }

    private void seedAccountingApprovalDelegations() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM accounting_approval_delegations", Integer.class);
            if (count != null && count > 0) {
                return;
            }

            System.out.println(">>> SEED: Seeding mock accounting approval delegations...");

            String delegatorId = null;
            try {
                delegatorId = jdbcTemplate.queryForObject(
                    "SELECT id FROM users WHERE email = 'manager@gmail.com' LIMIT 1", String.class);
            } catch (Exception e) {}

            String delegateId = null;
            try {
                delegateId = jdbcTemplate.queryForObject(
                    "SELECT id FROM users WHERE email = 'ketoan@gmail.com' LIMIT 1", String.class);
            } catch (Exception e) {}

            String directorId = null;
            try {
                directorId = jdbcTemplate.queryForObject(
                    "SELECT id FROM users WHERE email = 'director@gmail.com' LIMIT 1", String.class);
            } catch (Exception e) {}

            String chiefAccountantId = null;
            try {
                chiefAccountantId = jdbcTemplate.queryForObject(
                    "SELECT id FROM users WHERE email = 'ketoantruong@gmail.com' LIMIT 1", String.class);
            } catch (Exception e) {}

            String adminId = null;
            try {
                adminId = jdbcTemplate.queryForObject(
                    "SELECT id FROM users WHERE email = 'admin@gmail.com' LIMIT 1", String.class);
            } catch (Exception e) {}

            java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());
            java.sql.Timestamp yesterday = new java.sql.Timestamp(System.currentTimeMillis() - 86400000L);
            java.sql.Timestamp fiveDaysLater = new java.sql.Timestamp(System.currentTimeMillis() + 5L * 86400000L);
            java.sql.Timestamp tenDaysAgo = new java.sql.Timestamp(System.currentTimeMillis() - 10L * 86400000L);
            java.sql.Timestamp twoDaysAgo = new java.sql.Timestamp(System.currentTimeMillis() - 2L * 86400000L);
            java.sql.Timestamp threeDaysAgo = new java.sql.Timestamp(System.currentTimeMillis() - 3L * 86400000L);
            java.sql.Timestamp threeDaysLater = new java.sql.Timestamp(System.currentTimeMillis() + 3L * 86400000L);
            java.sql.Timestamp twoDaysLater = new java.sql.Timestamp(System.currentTimeMillis() + 2L * 86400000L);
            java.sql.Timestamp twelveDaysLater = new java.sql.Timestamp(System.currentTimeMillis() + 12L * 86400000L);

            if (delegatorId != null && delegateId != null) {
                // 1. Active delegation
                jdbcTemplate.update(
                    "INSERT INTO accounting_approval_delegations " +
                    "(company_id, delegator_user_id, delegate_user_id, valid_from, valid_to, scope_type, scope_ref_id, reason, status, created_at, created_by) " +
                    "VALUES (1, ?, ?, ?, ?, 'ALL', NULL, 'Duyệt hồ sơ đề nghị tạm ứng chi phí đi công tác', 'ACTIVE', ?, 'system')",
                    delegatorId, delegateId, yesterday, fiveDaysLater, now
                );

                // 2. Expired delegation
                jdbcTemplate.update(
                    "INSERT INTO accounting_approval_delegations " +
                    "(company_id, delegator_user_id, delegate_user_id, valid_from, valid_to, scope_type, scope_ref_id, reason, status, created_at, created_by) " +
                    "VALUES (1, ?, ?, ?, ?, 'ALL', NULL, 'Ủy quyền duyệt chứng từ thanh toán tạm thời khi đi công tác nước ngoài', 'EXPIRED', ?, 'system')",
                    delegatorId, delegateId, tenDaysAgo, twoDaysAgo, now
                );
            }

            if (directorId != null && chiefAccountantId != null) {
                // 3. Revoked delegation
                jdbcTemplate.update(
                    "INSERT INTO accounting_approval_delegations " +
                    "(company_id, delegator_user_id, delegate_user_id, valid_from, valid_to, scope_type, scope_ref_id, reason, status, created_at, created_by, revoked_at, revoked_by) " +
                    "VALUES (1, ?, ?, ?, ?, 'ALL', NULL, 'Ủy quyền phê duyệt thanh toán hợp đồng dự án UAT', 'REVOKED', ?, 'system', ?, 'director@gmail.com')",
                    directorId, chiefAccountantId, threeDaysAgo, threeDaysLater, now, yesterday
                );
            }

            if (adminId != null && delegateId != null) {
                // 4. Draft delegation
                jdbcTemplate.update(
                    "INSERT INTO accounting_approval_delegations " +
                    "(company_id, delegator_user_id, delegate_user_id, valid_from, valid_to, scope_type, scope_ref_id, reason, status, created_at, created_by) " +
                    "VALUES (1, ?, ?, ?, ?, 'ALL', NULL, 'Ủy quyền dự phòng duyệt chứng từ trước kỳ nghỉ phép năm', 'DRAFT', ?, 'system')",
                    adminId, delegateId, twoDaysLater, twelveDaysLater, now
                );
            }

            System.out.println(">>> SEED: Mock delegations successfully created");

        } catch (Exception e) {
            System.err.println(">>> SEED: Error seeding delegations: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private Permission createPermission(String name, String apiPath, String method, String module) {
        Permission p = new Permission();
        p.setName(name);
        p.setApiPath(apiPath);
        p.setMethod(method);
        p.setModule(module);
        return p;
    }

    private List<Permission> allPermissions() {
        if (this.permissionCache == null) {
            initializePermissionCache();
        }
        return this.permissionCache;
    }

    private void initializePermissionCache() {
        this.permissionCache = new ArrayList<>(this.permissionRepository.findAll());
        this.permissionKeyCache = new HashSet<>();
        this.permissionNameCache = new HashSet<>();
        this.permissionCache.forEach(this::cachePermission);
    }

    private void cachePermission(Permission permission) {
        this.permissionKeyCache.add(PermissionKey.of(
                permission.getModule(), permission.getApiPath(), permission.getMethod()));
        this.permissionNameCache.add(permission.getName());
    }

    private List<Permission> savePermissions(List<Permission> permissions) {
        List<Permission> saved = this.permissionRepository.saveAll(permissions);
        allPermissions().addAll(saved);
        saved.forEach(this::cachePermission);
        return saved;
    }

    private Permission savePermission(Permission permission) {
        Permission saved = this.permissionRepository.save(permission);
        allPermissions().add(saved);
        cachePermission(saved);
        return saved;
    }

    private void initializeRoleCache() {
        this.roleCache = new HashMap<>();
        this.roleRepository.findAllWithPermissions().forEach(role -> this.roleCache.put(role.getName(), role));
    }

    private Role findRole(String name) {
        return this.roleCache.get(name);
    }

    private List<Role> allRoles() {
        return new ArrayList<>(this.roleCache.values());
    }

    private Role saveRole(Role role) {
        Role saved = this.roleRepository.save(role);
        this.roleCache.put(saved.getName(), saved);
        return saved;
    }

    private boolean replaceRolePermissionsIfChanged(Role role, List<Permission> permissions) {
        Map<Long, Permission> uniquePermissions = new LinkedHashMap<>();
        permissions.stream()
                .filter(Objects::nonNull)
                .forEach(permission -> uniquePermissions.put(permission.getId(), permission));

        Set<Long> currentIds = new HashSet<>();
        if (role.getPermissions() != null) {
            role.getPermissions().stream()
                    .filter(Objects::nonNull)
                    .forEach(permission -> currentIds.add(permission.getId()));
        }
        if (currentIds.equals(uniquePermissions.keySet())) {
            return false;
        }

        role.setPermissions(new ArrayList<>(uniquePermissions.values()));
        saveRole(role);
        return true;
    }

    private void addPermissionIfMissing(List<Permission> target, String name, String apiPath, String method, String module) {
        allPermissions();
        if (this.permissionKeyCache.add(PermissionKey.of(module, apiPath, method))) {
            target.add(createPermission(name, apiPath, method, module));
            this.permissionNameCache.add(name);
        }
    }

    private void addPermissionByNameIfMissing(List<Permission> target, String name, String apiPath, String method, String module) {
        allPermissions();
        if (this.permissionNameCache.add(name)) {
            target.add(createPermission(name, apiPath, method, module));
            this.permissionKeyCache.add(PermissionKey.of(module, apiPath, method));
        }
    }

    private void seedAdminScopePermissionsAndRole() {
        List<Permission> newPerms = new ArrayList<>();
        addPermissionIfMissing(newPerms, "Danh sách phạm vi quản trị user",
                "/api/v1/users/{userId}/admin-scopes", "GET", "USERS");
        addPermissionIfMissing(newPerms, "Cập nhật phạm vi quản trị user",
                "/api/v1/users/{userId}/admin-scopes", "PUT", "USERS");
        addPermissionIfMissing(newPerms, "Tìm kiếm người dùng dạng dropdown",
                "/api/v1/users/cross-company", "GET", "USERS");

        if (!newPerms.isEmpty()) {
            savePermissions(newPerms);
            System.out.println(">>> SEED: USER ADMIN SCOPES permissions created");

            Role adminRole = findRole("SUPER_ADMIN");
            if (adminRole != null) {
                List<Permission> allPerms = new ArrayList<>(adminRole.getPermissions());
                allPerms.addAll(newPerms);
                adminRole.setPermissions(allPerms);
                saveRole(adminRole);
            }
        }

        List<Permission> adminScopePermissions = allPermissions().stream()
                .filter(p -> "USERS".equals(p.getModule()))
                .filter(p -> "/api/v1/users/{userId}/admin-scopes".equals(p.getApiPath()))
                .filter(p -> "GET".equalsIgnoreCase(p.getMethod()) || "PUT".equalsIgnoreCase(p.getMethod()))
                .toList();

        addPermissionsToRoleIfMissing("SUPER_ADMIN", adminScopePermissions);

        Role departmentManagerRole = findRole("DEPARTMENT_MANAGER");
        if (departmentManagerRole == null) {
            departmentManagerRole = new Role();
            departmentManagerRole.setName("DEPARTMENT_MANAGER");
            departmentManagerRole.setActive(true);
            departmentManagerRole.setDescription("Trưởng bộ phận - chỉ quản lý phòng ban trực thuộc chức danh active");
            saveRole(departmentManagerRole);
        } else if (departmentManagerRole.getDescription() == null) {
            departmentManagerRole.setDescription("Trưởng bộ phận - chỉ quản lý phòng ban trực thuộc chức danh active");
            saveRole(departmentManagerRole);
        }

        List<Permission> managerPermissions = new ArrayList<>(allPermissions().stream()
                .filter(p -> "USERS".equals(p.getModule())
                        || "DEPARTMENTS".equals(p.getModule())
                        || "SECTIONS".equals(p.getModule())
                        || "DASHBOARD".equals(p.getModule())
                        || "DEPARTMENT_OBJECTIVES".equals(p.getModule())
                        || "DEPARTMENT_JOB_TITLES".equals(p.getModule())
                        || "USER_POSITION".equals(p.getModule()))
                .filter(p -> "GET".equalsIgnoreCase(p.getMethod()))
                .filter(p -> !"/api/v1/users/{userId}/admin-scopes".equals(p.getApiPath()))
                .toList());

        List<Permission> deptManagerPermissions = managerPermissions.stream()
                .filter(p -> !"USERS".equals(p.getModule()))
                .toList();

        addPermissionsToRoleIfMissing("DEPARTMENT_MANAGER", deptManagerPermissions);

        Role adminSub3Role = findRole("ADMIN_SUB_3");
        if (adminSub3Role == null) {
            adminSub3Role = new Role();
            adminSub3Role.setName("ADMIN_SUB_3");
            adminSub3Role.setActive(true);
            adminSub3Role.setDescription("Quản trị viên cấp Phòng ban - quản trị danh sách phòng ban được gán");
            saveRole(adminSub3Role);
        } else if (adminSub3Role.getDescription() == null) {
            adminSub3Role.setDescription("Quản trị viên cấp Phòng ban - quản trị danh sách phòng ban được gán");
            saveRole(adminSub3Role);
        }
        addPermissionsToRoleIfMissing("ADMIN_SUB_3", managerPermissions);
    }

    private void seedDepartmentObjectivePermissionsAndRole() {
        List<Permission> newPerms = new ArrayList<>();
        addPermissionIfMissing(newPerms, "Tổng hợp mục tiêu phòng ban",
                "/api/v1/department-objectives/summary", "GET", "DEPARTMENT_OBJECTIVES");
        addPermissionIfMissing(newPerms, "Lịch sử phiên bản mục tiêu phòng ban",
                "/api/v1/departments/{departmentId}/objectives/versions", "GET", "DEPARTMENT_OBJECTIVES");
        addPermissionIfMissing(newPerms, "Ban hành mục tiêu phòng ban",
                "/api/v1/department-objectives/publish", "POST", "DEPARTMENT_OBJECTIVES");

        if (!newPerms.isEmpty()) {
            savePermissions(newPerms);
            System.out.println(">>> SEED: DEPARTMENT_OBJECTIVES extra permissions created");
        }

        List<Permission> departmentObjectivePermissions = allPermissions().stream()
                .filter(p -> "DEPARTMENT_OBJECTIVES".equals(p.getModule()))
                .toList();

        addPermissionsToRoleIfMissing("SUPER_ADMIN", departmentObjectivePermissions);
        addPermissionsToRoleIfMissing("HR_MANAGER", departmentObjectivePermissions);
        addPermissionsToRoleIfMissing("DEPARTMENT_MANAGER", departmentObjectivePermissions);
    }

    /**
     * Xóa mẫu đánh giá chỉ áp dụng cho bản nháp. Cấp quyền này cho đúng các role
     * vốn đã có quyền sửa mẫu, tránh mở rộng quyền xóa cho các role chỉ được xem.
     */
    private void seedEvaluationTemplateDeletePermission() {
        final String module = "EVALUATION_TEMPLATE";
        final String deletePath = "/api/v1/evaluation/templates/{id}";
        final String updatePath = "/api/v1/evaluation/templates/{id}";

        Permission deletePermission = allPermissions().stream()
                .filter(permission -> module.equals(permission.getModule())
                        && deletePath.equals(permission.getApiPath())
                        && "DELETE".equalsIgnoreCase(permission.getMethod()))
                .findFirst()
                .orElseGet(() -> savePermission(
                        createPermission("Xóa mẫu đánh giá", deletePath, "DELETE", module)));

        for (Role role : allRoles()) {
            boolean canUpdateTemplate = role.getPermissions().stream()
                    .filter(Objects::nonNull)
                    .anyMatch(permission -> module.equals(permission.getModule())
                            && updatePath.equals(permission.getApiPath())
                            && "PUT".equalsIgnoreCase(permission.getMethod()));
            if (canUpdateTemplate) {
                addPermissionsToRoleIfMissing(role.getName(), List.of(deletePermission));
            }
        }
    }

    private void seedEvaluationReassignPermission() {
        final String module = "EVALUATION_MANAGER";
        final String path = "/api/v1/evaluation/records/reassign-evaluator";
        final String method = "PATCH";
        final String name = "Điều chuyển người chấm/duyệt bản đánh giá";

        Permission reassignPermission = allPermissions().stream()
                .filter(p -> path.equals(p.getApiPath()) && method.equalsIgnoreCase(p.getMethod()))
                .findFirst()
                .orElseGet(() -> {
                    Permission newPerm = createPermission(name, path, method, module);
                    return savePermission(newPerm);
                });

        // Add to admins
        addPermissionsToRoleIfMissing("SUPER_ADMIN", List.of(reassignPermission));
        addPermissionsToRoleIfMissing("ADMIN_SUB_1", List.of(reassignPermission));
        addPermissionsToRoleIfMissing("ADMIN_SUB_2", List.of(reassignPermission));
    }

    /**
     * Quyền cấu hình luồng duyệt được tách riêng khỏi quyền thao tác bộ chứng từ.
     * ADMIN_SUB_2 chỉ có thể chuẩn bị và kiểm tra nháp; áp dụng/ngưng áp dụng
     * thuộc nhóm quản trị cấp cao hơn (SUPER_ADMIN, ADMIN_SUB_1).
     */
    private void seedAccountingWorkflowPermissionsAndRole() {
        List<Permission> newPerms = new ArrayList<>();
        addPermissionIfMissing(newPerms, "Xem danh sách luồng duyệt chứng từ kế toán",
                "/api/v1/accounting-approval-workflows", "GET", ACCOUNTING_WORKFLOWS_MODULE);
        addPermissionIfMissing(newPerms, "Tạo nháp luồng duyệt chứng từ kế toán",
                "/api/v1/accounting-approval-workflows", "POST", ACCOUNTING_WORKFLOWS_MODULE);
        addPermissionIfMissing(newPerms, "Cập nhật nháp luồng duyệt chứng từ kế toán",
                "/api/v1/accounting-approval-workflows/{id}/draft", "PUT", ACCOUNTING_WORKFLOWS_MODULE);
        addPermissionIfMissing(newPerms, "Kiểm tra cấu hình luồng duyệt chứng từ kế toán",
                "/api/v1/accounting-approval-workflows/{id}/validate", "POST", ACCOUNTING_WORKFLOWS_MODULE);
        addPermissionIfMissing(newPerms, "Áp dụng luồng duyệt chứng từ kế toán",
                "/api/v1/accounting-approval-workflows/{id}/publish", "POST", ACCOUNTING_WORKFLOWS_MODULE);
        addPermissionIfMissing(newPerms, "Ngưng áp dụng luồng duyệt chứng từ kế toán",
                "/api/v1/accounting-approval-workflows/{id}/deactivate", "POST", ACCOUNTING_WORKFLOWS_MODULE);
        addPermissionIfMissing(newPerms, "Kích hoạt lại luồng duyệt chứng từ kế toán",
                "/api/v1/accounting-approval-workflows/{id}/reactivate", "POST", ACCOUNTING_WORKFLOWS_MODULE);
        addPermissionIfMissing(newPerms, "Sao chép luồng duyệt chứng từ kế toán thành bản nháp",
                "/api/v1/accounting-approval-workflows/{id}/copy", "POST", ACCOUNTING_WORKFLOWS_MODULE);
        addPermissionIfMissing(newPerms, "Xem trước luồng duyệt bộ chứng từ",
                "/api/v1/accounting-approval-workflows/dossiers/{dossierId}/preview", "POST", ACCOUNTING_WORKFLOWS_MODULE);

        if (!newPerms.isEmpty()) {
            savePermissions(newPerms);
            System.out.println(">>> SEED: ACCOUNTING_WORKFLOWS permissions created");
        }

        List<Permission> workflowPermissions = allPermissions().stream()
                .filter(p -> ACCOUNTING_WORKFLOWS_MODULE.equals(p.getModule()))
                .toList();
        List<Permission> draftWorkflowPermissions = workflowPermissions.stream()
                .filter(p -> "/api/v1/accounting-approval-workflows".equals(p.getApiPath())
                        || "/api/v1/accounting-approval-workflows/{id}/draft".equals(p.getApiPath())
                        || "/api/v1/accounting-approval-workflows/{id}/validate".equals(p.getApiPath())
                        || "/api/v1/accounting-approval-workflows/{id}/copy".equals(p.getApiPath()))
                .toList();

        addPermissionsToRoleIfMissing("SUPER_ADMIN", workflowPermissions);
        addPermissionsToRoleIfMissing("ADMIN_SUB_1", workflowPermissions);
        addPermissionsToRoleIfMissing("ADMIN_SUB_2", draftWorkflowPermissions);
    }

    private void seedAccountingDelegationPermissionsAndRole() {
        List<Permission> newPerms = new ArrayList<>();
        addPermissionIfMissing(newPerms, "Xem danh sách ủy quyền xử lý phê duyệt chứng từ",
                "/api/v1/accounting-approval-delegations", "GET", ACCOUNTING_DELEGATIONS_MODULE);
        addPermissionIfMissing(newPerms, "Tạo ủy quyền xử lý phê duyệt chứng từ",
                "/api/v1/accounting-approval-delegations", "POST", ACCOUNTING_DELEGATIONS_MODULE);
        addPermissionIfMissing(newPerms, "Kích hoạt ủy quyền xử lý phê duyệt chứng từ",
                "/api/v1/accounting-approval-delegations/{id}/activate", "POST", ACCOUNTING_DELEGATIONS_MODULE);
        addPermissionIfMissing(newPerms, "Thu hồi ủy quyền xử lý phê duyệt chứng từ",
                "/api/v1/accounting-approval-delegations/{id}/revoke", "POST", ACCOUNTING_DELEGATIONS_MODULE);

        if (!newPerms.isEmpty()) {
            savePermissions(newPerms);
            System.out.println(">>> SEED: ACCOUNTING_DELEGATIONS permissions created");
        }

        List<Permission> delegationPermissions = allPermissions().stream()
                .filter(p -> ACCOUNTING_DELEGATIONS_MODULE.equals(p.getModule()))
                .toList();
        addPermissionsToRoleIfMissing("SUPER_ADMIN", delegationPermissions);
        addPermissionsToRoleIfMissing("ADMIN_SUB_1", delegationPermissions);
        addPermissionsToRoleIfMissing("ADMIN_SUB_2", delegationPermissions);
        addPermissionsToRoleIfMissing("KETOANTRUONG", delegationPermissions);
    }

    private void seedTaskPermissionsAndRole(boolean isProd) {
        List<Permission> newPerms = new ArrayList<>();

        final String taskModule = "TASKS";
        addPermissionIfMissing(newPerms, "Xem danh sách tác vụ", "/api/v1/tasks", "GET", taskModule);
        addPermissionIfMissing(newPerms, "Xem lịch tác vụ", "/api/v1/tasks/calendar", "GET", taskModule);
        addPermissionIfMissing(newPerms, "Tạo mới tác vụ", "/api/v1/tasks", "POST", taskModule);
        addPermissionIfMissing(newPerms, "Xem chi tiết tác vụ", "/api/v1/tasks/{id}", "GET", taskModule);
        addPermissionIfMissing(newPerms, "Cập nhật tác vụ", "/api/v1/tasks/{id}", "PUT", taskModule);
        addPermissionIfMissing(newPerms, "Chuyển trạng thái tác vụ", "/api/v1/tasks/{id}/status", "PATCH", taskModule);
        addPermissionIfMissing(newPerms, "Hủy tác vụ", "/api/v1/tasks/{id}/cancel", "POST", taskModule);
        addPermissionIfMissing(newPerms, "Xóa cứng tác vụ", "/api/v1/tasks/{id}", "DELETE", taskModule);
        addPermissionIfMissing(newPerms, "Nộp kết quả nghiệm thu tác vụ", "/api/v1/tasks/{id}/submit-result", "POST", taskModule);
        addPermissionIfMissing(newPerms, "Thẩm định nghiệm thu tác vụ", "/api/v1/tasks/{id}/approve", "POST", taskModule);
        addPermissionIfMissing(newPerms, "Báo cáo tổng kết công việc", "/api/v1/tasks/summary-report", "GET", taskModule);
        addPermissionIfMissing(newPerms, "Xuất báo cáo tổng kết công việc Excel", "/api/v1/tasks/summary-report/export", "GET", taskModule);

        final String checklistModule = "TASK_CHECKLISTS";
        addPermissionIfMissing(newPerms, "Tạo mục kiểm tra", "/api/v1/tasks/{id}/checklists", "POST", checklistModule);
        addPermissionIfMissing(newPerms, "Cập nhật mục kiểm tra", "/api/v1/tasks/{id}/checklists/{checklistId}", "PUT", checklistModule);
        addPermissionIfMissing(newPerms, "Tick hoàn thành mục kiểm tra", "/api/v1/tasks/{id}/checklists/{checklistId}/toggle", "PATCH", checklistModule);
        addPermissionIfMissing(newPerms, "Xóa mục kiểm tra", "/api/v1/tasks/{id}/checklists/{checklistId}", "DELETE", checklistModule);
        addPermissionIfMissing(newPerms, "Xem danh sách mục kiểm tra", "/api/v1/tasks/{id}/checklists", "GET", checklistModule);

        final String commentModule = "TASK_COMMENTS";
        addPermissionIfMissing(newPerms, "Gửi bình luận tác vụ", "/api/v1/tasks/{id}/comments", "POST", commentModule);
        addPermissionIfMissing(newPerms, "Xem danh sách bình luận tác vụ", "/api/v1/tasks/{id}/comments", "GET", commentModule);

        final String attachmentModule = "TASK_ATTACHMENTS";
        addPermissionIfMissing(newPerms, "Đăng ký file đính kèm tác vụ", "/api/v1/tasks/{id}/attachments", "POST", attachmentModule);
        addPermissionIfMissing(newPerms, "Xem danh sách file đính kèm tác vụ", "/api/v1/tasks/{id}/attachments", "GET", attachmentModule);

        final String jdModule = "JOB_DESCRIPTIONS";
        addPermissionIfMissing(newPerms, "Xem danh sách nhiệm vụ JD gợi ý theo nhân viên", "/api/v1/job-descriptions/by-user/{userId}", "GET", jdModule);

        final String employeeModule = "EMPLOYEES";
        addPermissionIfMissing(newPerms, "Xem danh sách nhân viên", "/api/v1/employees", "GET", employeeModule);
        addPermissionIfMissing(newPerms, "Xem chi tiết nhân viên", "/api/v1/employees/{id}", "GET", employeeModule);
        addPermissionIfMissing(newPerms, "Tạo mới nhân viên", "/api/v1/employees", "POST", employeeModule);
        addPermissionIfMissing(newPerms, "Cập nhật nhân viên", "/api/v1/employees", "PUT", employeeModule);
        addPermissionIfMissing(newPerms, "Xóa nhân viên", "/api/v1/employees/{id}", "DELETE", employeeModule);

        if (!newPerms.isEmpty()) {
            savePermissions(newPerms);
            System.out.println(">>> SEED: TASK & EMPLOYEE module permissions created");
        }

        List<Permission> taskPermissions = allPermissions().stream()
                .filter(p -> taskModule.equals(p.getModule()) || checklistModule.equals(p.getModule())
                        || commentModule.equals(p.getModule()) || attachmentModule.equals(p.getModule()))
                .toList();

        List<Permission> userJdReadPermissions = allPermissions().stream()
                .filter(p -> ("USERS".equals(p.getModule()) && "GET".equalsIgnoreCase(p.getMethod()) && "/api/v1/users/cross-company".equals(p.getApiPath()))
                        || ("/api/v1/job-descriptions/by-user/{userId}".equals(p.getApiPath())))
                .toList();

        List<Role> allRoles = roleRepository.findAll();
        for (Role r : allRoles) {
            addPermissionsToRoleIfMissing(r.getName(), taskPermissions);
            addPermissionsToRoleIfMissing(r.getName(), userJdReadPermissions);
        }

        if (!isProd) {
            Role deptMgrRole = findRole("DEPARTMENT_MANAGER");
            Role employeeRole = findRole("EMPLOYEE");

            User taskMgr = createUserIfNotExist("manager.task@gmail.com", "Trưởng Phòng Giao Việc Test", deptMgrRole != null ? deptMgrRole : findRole("SUPER_ADMIN"));
            User taskAssignee = createUserIfNotExist("assignee.task@gmail.com", "Nhân Viên Làm Chính Test", employeeRole != null ? employeeRole : findRole("SUPER_ADMIN"));
            User taskCollab = createUserIfNotExist("collab.task@gmail.com", "Nhân Viên Phối Hợp Test", employeeRole != null ? employeeRole : findRole("SUPER_ADMIN"));
            User taskObserver = createUserIfNotExist("observer.task@gmail.com", "Nhân Viên Quan Sát Test", employeeRole != null ? employeeRole : findRole("SUPER_ADMIN"));
            User taskDelegate = createUserIfNotExist("delegate.task@gmail.com", "Nhân Viên Nhận Ủy Quyền Test", employeeRole != null ? employeeRole : findRole("SUPER_ADMIN"));
            User taskCollabMgr = createUserIfNotExist("collab.manager@gmail.com", "Trưởng Phòng Phối Hợp Test", deptMgrRole != null ? deptMgrRole : findRole("SUPER_ADMIN"));

            if (taskAssignee != null && taskMgr != null) {
                taskAssignee.setDirectManager(taskMgr);
                this.userRepository.save(taskAssignee);
            }

            if (taskCollab != null && taskCollabMgr != null) {
                taskCollab.setDirectManager(taskCollabMgr);
                this.userRepository.save(taskCollab);
            }

            ensureUserPositionAndInfo(taskMgr, "NV-TASK-MGR", "0900000101", "MALE");
            ensureUserPositionAndInfo(taskAssignee, "NV-TASK-ASN", "0900000102", "FEMALE");
            ensureUserPositionAndInfo(taskCollab, "NV-TASK-CLB", "0900000103", "MALE");
            ensureUserPositionAndInfo(taskObserver, "NV-TASK-OBS", "0900000104", "FEMALE");
            ensureUserPositionAndInfo(taskDelegate, "NV-TASK-DLG", "0900000105", "MALE");
            ensureUserPositionAndInfo(taskCollabMgr, "NV-TASK-CLM", "0900000109", "MALE");

            User mgrUser = this.userRepository.findByEmail("manager@gmail.com");
            User creatorUser = this.userRepository.findByEmail("creator@gmail.com");
            User adminUser = this.userRepository.findByEmail("admin@gmail.com");
            ensureUserPositionAndInfo(mgrUser, "NV-MGR-01", "0900000106", "MALE");
            ensureUserPositionAndInfo(creatorUser, "NV-CTR-01", "0900000107", "FEMALE");
            ensureUserPositionAndInfo(adminUser, "NV-ADM-01", "0900000108", "MALE");

            seedSampleJdTasksIfNotExist();
        }
    }

    private void seedApprovalDelegationPermissionsAndRole() {
        List<Permission> newPerms = new ArrayList<>();
        final String delegationModule = "APPROVAL_DELEGATIONS";
        addPermissionIfMissing(newPerms, "Tạo mới ủy quyền duyệt", "/api/v1/approval-delegations", "POST", delegationModule);
        addPermissionIfMissing(newPerms, "Xem danh sách ủy quyền duyệt của tôi", "/api/v1/approval-delegations/mine", "GET", delegationModule);
        addPermissionIfMissing(newPerms, "Xem danh sách ủy quyền duyệt người khác giao cho tôi", "/api/v1/approval-delegations/delegated-to-me", "GET", delegationModule);
        addPermissionIfMissing(newPerms, "Thu hồi ủy quyền duyệt", "/api/v1/approval-delegations/{id}/revoke", "POST", delegationModule);

        if (!newPerms.isEmpty()) {
            savePermissions(newPerms);
            System.out.println(">>> SEED: APPROVAL_DELEGATIONS permissions created");
        }

        List<Permission> delegationPermissions = allPermissions().stream()
                .filter(p -> delegationModule.equals(p.getModule()))
                .toList();

        List<Role> allRoles = roleRepository.findAll();
        for (Role r : allRoles) {
            addPermissionsToRoleIfMissing(r.getName(), delegationPermissions);
        }
    }

    private void seedEvaluationPermissionsAndRole() {
        List<Permission> newPerms = new ArrayList<>();

        final String templateModule = "EVALUATION_TEMPLATE";
        addPermissionIfMissing(newPerms, "Tạo mẫu đánh giá", "/api/v1/evaluation/templates", "POST", templateModule);
        addPermissionIfMissing(newPerms, "Cập nhật mẫu đánh giá", "/api/v1/evaluation/templates/{id}", "PUT", templateModule);
        addPermissionIfMissing(newPerms, "Công bố mẫu đánh giá", "/api/v1/evaluation/templates/{id}/publish", "PATCH", templateModule);
        addPermissionIfMissing(newPerms, "Lưu trữ mẫu đánh giá", "/api/v1/evaluation/templates/{id}/archive", "PATCH", templateModule);
        addPermissionIfMissing(newPerms, "Xóa mẫu đánh giá", "/api/v1/evaluation/templates/{id}", "DELETE", templateModule);
        addPermissionIfMissing(newPerms, "Chi tiết mẫu đánh giá", "/api/v1/evaluation/templates/{id}", "GET", templateModule);
        addPermissionIfMissing(newPerms, "Danh sách mẫu đánh giá", "/api/v1/evaluation/templates", "GET", templateModule);
        addPermissionIfMissing(newPerms, "Danh sách mẫu đánh giá đang dùng", "/api/v1/evaluation/templates/active", "GET", templateModule);
        addPermissionIfMissing(newPerms, "Tạo nhóm tiêu chí mẫu đánh giá", "/api/v1/evaluation/templates/{templateId}/sections", "POST", templateModule);
        addPermissionIfMissing(newPerms, "Cập nhật nhóm tiêu chí mẫu đánh giá", "/api/v1/evaluation/sections/{sectionId}", "PUT", templateModule);
        addPermissionIfMissing(newPerms, "Xóa nhóm tiêu chí mẫu đánh giá", "/api/v1/evaluation/sections/{sectionId}", "DELETE", templateModule);
        addPermissionIfMissing(newPerms, "Danh sách nhóm tiêu chí mẫu đánh giá", "/api/v1/evaluation/templates/{templateId}/sections", "GET", templateModule);
        addPermissionIfMissing(newPerms, "Tạo tiêu chí đánh giá", "/api/v1/evaluation/sections/{sectionId}/criteria", "POST", templateModule);
        addPermissionIfMissing(newPerms, "Cập nhật tiêu chí đánh giá", "/api/v1/evaluation/criteria/{criteriaId}", "PUT", templateModule);
        addPermissionIfMissing(newPerms, "Xóa tiêu chí đánh giá", "/api/v1/evaluation/criteria/{criteriaId}", "DELETE", templateModule);
        addPermissionIfMissing(newPerms, "Tạo mức điểm tiêu chí", "/api/v1/evaluation/criteria/{criteriaId}/levels", "POST", templateModule);
        addPermissionIfMissing(newPerms, "Cập nhật mức điểm tiêu chí", "/api/v1/evaluation/levels/{levelId}", "PUT", templateModule);
        addPermissionIfMissing(newPerms, "Danh sách mức điểm tiêu chí", "/api/v1/evaluation/criteria/{criteriaId}/levels", "GET", templateModule);

        final String periodModule = "EVALUATION_PERIOD";
        addPermissionIfMissing(newPerms, "Tạo kỳ đánh giá", "/api/v1/evaluation/periods", "POST", periodModule);
        addPermissionIfMissing(newPerms, "Cập nhật kỳ đánh giá", "/api/v1/evaluation/periods/{id}", "PUT", periodModule);
        addPermissionIfMissing(newPerms, "Chi tiết kỳ đánh giá", "/api/v1/evaluation/periods/{id}", "GET", periodModule);
        addPermissionIfMissing(newPerms, "Tiến độ kỳ đánh giá", "/api/v1/evaluation/periods/{id}/progress", "GET", periodModule);
        addPermissionIfMissing(newPerms, "Danh sách kỳ đánh giá", "/api/v1/evaluation/periods", "GET", periodModule);
        addPermissionIfMissing(newPerms, "Gắn mẫu vào kỳ đánh giá", "/api/v1/evaluation/periods/{periodId}/templates", "POST", periodModule);
        addPermissionIfMissing(newPerms, "Danh sách mẫu trong kỳ đánh giá", "/api/v1/evaluation/periods/{periodId}/templates", "GET", periodModule);
        addPermissionIfMissing(newPerms, "Gỡ mẫu khỏi kỳ đánh giá", "/api/v1/evaluation/periods/{periodId}/templates/{templateId}", "DELETE", periodModule);
        addPermissionIfMissing(newPerms, "Thêm nhân viên vào kỳ đánh giá", "/api/v1/evaluation/periods/{periodId}/employees", "POST", periodModule);
        addPermissionIfMissing(newPerms, "Hủy hồ sơ nhân viên trong kỳ đánh giá", "/api/v1/evaluation/period-employees/{id}/cancel", "PATCH", periodModule);
        addPermissionIfMissing(newPerms, "Danh sách nhân viên trong kỳ đánh giá", "/api/v1/evaluation/periods/{periodId}/employees", "GET", periodModule);
        addPermissionIfMissing(newPerms, "Kích hoạt kỳ đánh giá", "/api/v1/evaluation/periods/{id}/activate", "PATCH", periodModule);
        addPermissionIfMissing(newPerms, "Đóng kỳ đánh giá", "/api/v1/evaluation/periods/{id}/close", "PATCH", periodModule);
        addPermissionIfMissing(newPerms, "Danh sách hồ sơ chưa hoàn tất trong kỳ", "/api/v1/evaluation/periods/{id}/unfinished-records", "GET", periodModule);
        addPermissionIfMissing(newPerms, "Gia hạn hạn xử lý bản đánh giá", "/api/v1/evaluation/records/deadline-extension", "PATCH", periodModule);
        addPermissionIfMissing(newPerms, "Báo cáo tổng hợp đánh giá hoàn tất", "/api/v1/evaluation/summary/completed", "GET", periodModule);
        addPermissionIfMissing(newPerms, "Danh sách toàn bộ bản đánh giá", "/api/v1/evaluation/records", "GET", periodModule);
        addPermissionIfMissing(newPerms, "Phân bổ trạng thái trong kỳ đánh giá", "/api/v1/evaluation/periods/{periodId}/status-distribution", "GET", periodModule);
        addPermissionIfMissing(newPerms, "Phân bổ xếp loại trong kỳ đánh giá", "/api/v1/evaluation/periods/{periodId}/grade-distribution", "GET", periodModule);

        final String employeeModule = "EVALUATION_EMPLOYEE";
        addPermissionIfMissing(newPerms, "Danh sách bản đánh giá cá nhân", "/api/v1/evaluation/my-records", "GET", employeeModule);
        addPermissionIfMissing(newPerms, "Nhân viên chấm điểm tự đánh giá", "/api/v1/evaluation/records/{recordId}/employee-scores", "POST", employeeModule);
        addPermissionIfMissing(newPerms, "Nhân viên nộp tự đánh giá", "/api/v1/evaluation/records/{recordId}/employee-submit", "POST", employeeModule);
        addPermissionIfMissing(newPerms, "Nhân viên lưu tự nhận xét", "/api/v1/evaluation/records/{recordId}/self-review", "POST", employeeModule);
        addPermissionIfMissing(newPerms, "Nhân viên xác nhận kết quả đánh giá", "/api/v1/evaluation/records/{recordId}/employee-confirm", "POST", employeeModule);

        final String managerModule = "EVALUATION_MANAGER";
        addPermissionIfMissing(newPerms, "Chi tiết bản đánh giá", "/api/v1/evaluation/records/{id}", "GET", managerModule);
        addPermissionIfMissing(newPerms, "Danh sách bản đánh giá cho quản lý trực tiếp theo kỳ", "/api/v1/evaluation/manager/periods/{periodId}/records", "GET", managerModule);
        addPermissionIfMissing(newPerms, "Danh sách chờ quản lý trực tiếp chấm", "/api/v1/evaluation/manager/pending", "GET", managerModule);
        addPermissionIfMissing(newPerms, "Lịch sử chấm điểm của quản lý trực tiếp", "/api/v1/evaluation/manager/records", "GET", managerModule);
        addPermissionIfMissing(newPerms, "Danh sách bản đánh giá cho quản lý gián tiếp theo kỳ", "/api/v1/evaluation/approval/periods/{periodId}/records", "GET", managerModule);
        addPermissionIfMissing(newPerms, "Danh sách chờ duyệt cấp trên", "/api/v1/evaluation/approval/pending", "GET", managerModule);
        addPermissionIfMissing(newPerms, "Lịch sử duyệt cấp trên", "/api/v1/evaluation/approval/records", "GET", managerModule);
        addPermissionIfMissing(newPerms, "Quản lý trực tiếp chấm điểm", "/api/v1/evaluation/records/{recordId}/manager-scores", "POST", managerModule);
        addPermissionIfMissing(newPerms, "Quản lý trực tiếp gửi duyệt cấp trên", "/api/v1/evaluation/records/{recordId}/manager-submit", "POST", managerModule);
        addPermissionIfMissing(newPerms, "Quản lý trực tiếp lưu nhận xét", "/api/v1/evaluation/records/{recordId}/manager-feedback", "POST", managerModule);
        addPermissionIfMissing(newPerms, "Quản lý lưu kế hoạch đào tạo", "/api/v1/evaluation/records/{recordId}/training-plans", "POST", managerModule);
        addPermissionIfMissing(newPerms, "Cấp trên điều chỉnh điểm duyệt", "/api/v1/evaluation/records/{recordId}/approver-scores", "POST", managerModule);
        addPermissionIfMissing(newPerms, "Duyệt cấp trên bản đánh giá", "/api/v1/evaluation/records/{recordId}/approve", "POST", managerModule);
        addPermissionIfMissing(newPerms, "Duyệt cấp trên hàng loạt", "/api/v1/evaluation/records/batch-approve", "POST", managerModule);
        addPermissionIfMissing(newPerms, "Trả lại bản đánh giá", "/api/v1/evaluation/records/{recordId}/reject", "POST", managerModule);
        addPermissionIfMissing(newPerms, "Điều chuyển người chấm/duyệt bản đánh giá", "/api/v1/evaluation/records/reassign-evaluator", "PATCH", managerModule);
        addPermissionIfMissing(newPerms, "Lịch sử trạng thái bản đánh giá", "/api/v1/evaluation/records/{recordId}/history", "GET", managerModule);
        addPermissionIfMissing(newPerms, "Audit thay đổi điểm bản đánh giá", "/api/v1/evaluation/records/{recordId}/score-audits", "GET", managerModule);
        addPermissionIfMissing(newPerms, "Số lần bản đánh giá bị trả lại", "/api/v1/evaluation/records/{recordId}/rejection-count", "GET", managerModule);

        if (!newPerms.isEmpty()) {
            savePermissions(newPerms);
            System.out.println(">>> SEED: EVALUATION permissions created");
        }

        List<Permission> templatePermissions = allPermissions().stream()
                .filter(p -> templateModule.equals(p.getModule()))
                .toList();
        List<Permission> periodPermissions = allPermissions().stream()
                .filter(p -> periodModule.equals(p.getModule()))
                .toList();
        List<Permission> employeePermissions = allPermissions().stream()
                .filter(p -> employeeModule.equals(p.getModule()))
                .toList();
        List<Permission> managerPermissions = allPermissions().stream()
                .filter(p -> managerModule.equals(p.getModule()))
                .toList();
        List<Permission> employeeSharedReadPermissions = managerPermissions.stream()
                .filter(p -> ("GET".equalsIgnoreCase(p.getMethod()))
                        && ("/api/v1/evaluation/records/{id}".equals(p.getApiPath())
                        || "/api/v1/evaluation/records/{recordId}/history".equals(p.getApiPath())
                        || "/api/v1/evaluation/records/{recordId}/rejection-count".equals(p.getApiPath())))
                .toList();

        addPermissionsToRoleIfMissing("SUPER_ADMIN", allPermissions().stream()
                .filter(p -> templateModule.equals(p.getModule())
                        || periodModule.equals(p.getModule())
                        || employeeModule.equals(p.getModule())
                        || managerModule.equals(p.getModule()))
                .toList());
        addPermissionsToRoleIfMissing("ADMIN_SUB_1", allPermissions().stream()
                .filter(p -> templateModule.equals(p.getModule())
                        || periodModule.equals(p.getModule())
                        || employeeModule.equals(p.getModule())
                        || managerModule.equals(p.getModule()))
                .toList());
        addPermissionsToRoleIfMissing("ADMIN_SUB_2", periodPermissions);
        addPermissionsToRoleIfMissing("ADMIN_SUB_2", templatePermissions);
        addPermissionsToRoleIfMissing("HR_MANAGER", periodPermissions);
        addPermissionsToRoleIfMissing("HR_MANAGER", templatePermissions);
        addPermissionsToRoleIfMissing("HR_MANAGER", managerPermissions);
        addPermissionsToRoleIfMissing("DEPARTMENT_MANAGER", managerPermissions);
        addPermissionsToRoleIfMissing("EMPLOYEE", employeePermissions);
        addPermissionsToRoleIfMissing("EMPLOYEE", employeeSharedReadPermissions);
    }

    private void syncFullPermissionRoles() {
        List<Permission> superAdminPermissions = allPermissions().stream()
                .filter(permission -> !NON_ADMIN_BUSINESS_APPROVER_PERMISSION_NAMES.contains(permission.getName()))
                .toList();
        addPermissionsToRoleIfMissing("SUPER_ADMIN", superAdminPermissions);

        List<Permission> adminSub1Permissions = allPermissions().stream()
                .filter(permission -> !NON_ADMIN_BUSINESS_APPROVER_PERMISSION_NAMES.contains(permission.getName()))
                .filter(permission -> {
                    String module = permission.getModule();
                    if ("ROLES".equalsIgnoreCase(module) || "PERMISSIONS".equalsIgnoreCase(module)) {
                        return false;
                    }
                    if ("USERS".equalsIgnoreCase(module)) {
                        String method = permission.getMethod();
                        String path = permission.getApiPath();
                        return "GET".equalsIgnoreCase(method) && "/api/v1/users".equals(path);
                    }
                    return true;
                })
                .toList();

        stripRestrictedManagementPermissionsFromNonSuperAdminRoles();
        addPermissionsToRoleIfMissing("ADMIN_SUB_1", adminSub1Permissions);

        removePermissionsFromRoleIfPresent("SUPER_ADMIN", NON_ADMIN_BUSINESS_APPROVER_PERMISSION_NAMES);
        removePermissionsFromRoleIfPresent("ADMIN_SUB_1", NON_ADMIN_BUSINESS_APPROVER_PERMISSION_NAMES);
        removePermissionsFromRoleIfPresent("ADMIN_SUB_2", NON_ADMIN_BUSINESS_APPROVER_PERMISSION_NAMES);
    }

    private void stripRestrictedManagementPermissionsFromNonSuperAdminRoles() {
        List<String> nonSuperAdminRoles = List.of("ADMIN_SUB_1", "ADMIN_SUB_2", "ADMIN_SUB_3", "EMPLOYEE", "DEPARTMENT_MANAGER", "KETOAN", "KETOANTRUONG", "DIRECTOR");
        for (String roleName : nonSuperAdminRoles) {
            Role role = findRole(roleName);
            if (role == null || role.getPermissions() == null || role.getPermissions().isEmpty()) {
                continue;
            }
            List<Permission> filtered = role.getPermissions().stream()
                    .filter(p -> p != null)
                    .filter(p -> {
                        String module = p.getModule();
                        if ("ROLES".equalsIgnoreCase(module) || "PERMISSIONS".equalsIgnoreCase(module) || "EMPLOYEES".equalsIgnoreCase(module)) {
                            return false;
                        }
                        if ("USERS".equalsIgnoreCase(module)) {
                            String method = p.getMethod();
                            String path = p.getApiPath();
                            return "GET".equalsIgnoreCase(method) && "/api/v1/users/cross-company".equals(path);
                        }
                        return true;
                    })
                    .toList();
            if (filtered.size() != role.getPermissions().size()) {
                replaceRolePermissionsIfChanged(role, filtered);
            }
        }
    }

    private void removePermissionsFromRoleIfPresent(String roleName, Set<String> permissionNames) {
        Role role = findRole(roleName);
        if (role == null || role.getPermissions() == null || role.getPermissions().isEmpty()) {
            return;
        }

        List<Permission> filteredPermissions = role.getPermissions().stream()
                .filter(permission -> permission == null || !permissionNames.contains(permission.getName()))
                .toList();
        if (filteredPermissions.size() != role.getPermissions().size()) {
            replaceRolePermissionsIfChanged(role, filteredPermissions);
        }
    }

    private void addPermissionsToRoleIfMissing(String roleName, List<Permission> permissions) {
        Role role = findRole(roleName);
        if (role == null || permissions == null || permissions.isEmpty()) {
            return;
        }

        List<Permission> currentPermissions = role.getPermissions() != null
                ? new ArrayList<>(role.getPermissions())
                : new ArrayList<>();

        Set<Long> permissionIds = new HashSet<>();
        currentPermissions.stream()
                .filter(Objects::nonNull)
                .forEach(permission -> permissionIds.add(permission.getId()));
        for (Permission permission : permissions) {
            if (permission != null && permissionIds.add(permission.getId())) {
                currentPermissions.add(permission);
            }
        }
        replaceRolePermissionsIfChanged(role, currentPermissions);
    }

    private void seedUatUsers() {
        Role ketoanRole = findRole("KETOAN");
        Role ketoanTruongRole = findRole("KETOANTRUONG");

        User emp1 = createUserIfNotExist("emp1@lotte.vn", "UAT EMP 1 Gương mẫu", ketoanRole);
        ensureUserPositionAndInfo(emp1, "NV-UAT-01", "0911111111", "MALE");

        User emp2 = createUserIfNotExist("emp2@lotte.vn", "UAT EMP 2 Nghỉ việc", ketoanRole);
        ensureUserPositionAndInfo(emp2, "NV-UAT-02", "0922222222", "FEMALE");

        User emp3 = createUserIfNotExist("emp3@lotte.vn", "UAT EMP 3 Hay quên", ketoanRole);
        ensureUserPositionAndInfo(emp3, "NV-UAT-03", "0933333333", "MALE");

        User mgr1 = createUserIfNotExist("mgr1@lotte.vn", "UAT MGR 1 Quản lý", ketoanTruongRole);
        ensureUserPositionAndInfo(mgr1, "NV-UAT-MGR1", "0944444444", "MALE");

        User mgr2 = createUserIfNotExist("mgr2@lotte.vn", "UAT MGR 2 Quản lý mới", ketoanTruongRole);
        ensureUserPositionAndInfo(mgr2, "NV-UAT-MGR2", "0955555555", "FEMALE");

        User director = createUserIfNotExist("director@lotte.vn", "UAT Director / Approver", ketoanTruongRole);
        ensureUserPositionAndInfo(director, "NV-UAT-DIR", "0966666666", "MALE");
    }

    private User createUserIfNotExist(String email, String name, Role role) {
        User user = this.userRepository.findByEmail(email);
        if (user == null) {
            user = new User();
            user.setEmail(email);
            user.setName(name);
            user.setPassword(this.passwordEncoder.encode("123456"));
            user.setActive(true);
            user.setRole(role);
            user = this.userRepository.save(user);
            System.out.println(">>> SEED: Test User created: " + email);
        }
        return user;
    }

    private void ensureUserPositionAndInfo(User user, String employeeCode, String phone, String gender) {
        if (user == null || user.getId() == null) return;
        try {
            String userId = user.getId();
            Integer infoCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_info WHERE user_id = ?", Integer.class, userId);
            if (infoCount == null || infoCount == 0) {
                jdbcTemplate.update(
                    "INSERT INTO user_info (user_id, employee_code, phone, gender, created_at, created_by) VALUES (?, ?, ?, ?, NOW(), 'system')",
                    userId, employeeCode, phone, gender);
            }

            Long deptJobTitleId = null;
            try {
                deptJobTitleId = jdbcTemplate.queryForObject(
                    "SELECT djt.id FROM department_job_titles djt JOIN departments d ON djt.department_id = d.id WHERE d.company_id = 1 LIMIT 1",
                    Long.class);
            } catch (Exception ex) {
                try {
                    deptJobTitleId = jdbcTemplate.queryForObject("SELECT id FROM department_job_titles LIMIT 1", Long.class);
                } catch (Exception e2) {}
            }

            if (deptJobTitleId != null) {
                Integer posCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM user_positions WHERE user_id = ?", Integer.class, userId);
                if (posCount == null || posCount == 0) {
                    jdbcTemplate.update(
                        "INSERT INTO user_positions (active, created_at, department_job_title_id, source, user_id, created_by, updated_by) VALUES " +
                        "(1, NOW(), ?, 'DEPARTMENT', ?, 'system', 'system')",
                        deptJobTitleId, userId);
                }
            }
        } catch (Exception e) {
            System.err.println(">>> SEED: Error linking user position for " + user.getEmail() + ": " + e.getMessage());
        }
    }

    private void ensureAllUsersHavePositionsAndInfo() {
        try {
            Long deptJobTitleId = null;
            try {
                deptJobTitleId = jdbcTemplate.queryForObject(
                    "SELECT djt.id FROM department_job_titles djt JOIN departments d ON djt.department_id = d.id WHERE d.company_id = 1 LIMIT 1",
                    Long.class);
            } catch (Exception ex) {
                try {
                    deptJobTitleId = jdbcTemplate.queryForObject("SELECT id FROM department_job_titles LIMIT 1", Long.class);
                } catch (Exception e2) {}
            }

            if (deptJobTitleId != null) {
                List<String> userIdsWithoutPosition = jdbcTemplate.queryForList(
                    "SELECT id FROM users WHERE id NOT IN (SELECT DISTINCT user_id FROM user_positions WHERE user_id IS NOT NULL)",
                    String.class);

                for (String uId : userIdsWithoutPosition) {
                    jdbcTemplate.update(
                        "INSERT INTO user_positions (active, created_at, department_job_title_id, source, user_id, created_by, updated_by) VALUES " +
                        "(1, NOW(), ?, 'DEPARTMENT', ?, 'system', 'system')",
                        deptJobTitleId, uId);
                }
                if (!userIdsWithoutPosition.isEmpty()) {
                    System.out.println(">>> SEED: Linked user_positions for " + userIdsWithoutPosition.size() + " users missing positions.");
                }
            }

            List<String> userIdsWithoutInfo = jdbcTemplate.queryForList(
                "SELECT id FROM users WHERE id NOT IN (SELECT DISTINCT user_id FROM user_info WHERE user_id IS NOT NULL)",
                String.class);

            int idx = 1;
            for (String uId : userIdsWithoutInfo) {
                String empCode = "NV-AUTO-" + String.format("%04d", idx++);
                jdbcTemplate.update(
                    "INSERT INTO user_info (user_id, employee_code, phone, gender, created_at, created_by) VALUES (?, ?, '0900000000', 'MALE', NOW(), 'system')",
                    uId, empCode);
            }
            if (!userIdsWithoutInfo.isEmpty()) {
                System.out.println(">>> SEED: Linked user_info for " + userIdsWithoutInfo.size() + " users missing info.");
            }
        } catch (Exception e) {
            System.err.println(">>> SEED: Error in ensureAllUsersHavePositionsAndInfo: " + e.getMessage());
        }
    }

    private void seedSampleJdTasksIfNotExist() {
        try {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM job_descriptions", Integer.class);
            if (count == null || count == 0) {
                Long djtId = null;
                try {
                    djtId = jdbcTemplate.queryForObject("SELECT id FROM department_job_titles LIMIT 1", Long.class);
                } catch (Exception ex) {}

                if (djtId != null) {
                    jdbcTemplate.update(
                        "INSERT INTO job_descriptions (code, status, department_job_title_id, version, created_at, created_by) " +
                        "VALUES ('JD-MGR-01', 'PUBLISHED', ?, 1, NOW(), 'system')", djtId);

                    Long jdId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                    if (jdId != null) {
                        jdbcTemplate.update(
                            "INSERT INTO job_description_tasks (job_description_id, order_no, title, content, created_at, created_by) VALUES " +
                            "(?, 1, 'Lập kế hoạch & Theo dõi tiến độ công việc tuần/tháng', 'Xây dựng kế hoạch làm việc cho bộ phận và phân công nhiệm vụ', NOW(), 'system'), " +
                            "(?, 2, 'Kiểm soát & Đánh giá kết quả thực hiện KPI phòng ban', 'Rà soát chỉ số KPI định kỳ và đề xuất giải pháp cải tiến', NOW(), 'system'), " +
                            "(?, 3, 'Thẩm định & Phê duyệt bộ chứng từ/hồ sơ nghiệm thu', 'Kiểm tra tính hợp lệ và phê duyệt các chứng từ thuộc thẩm quyền', NOW(), 'system'), " +
                            "(?, 4, 'Đào tạo & Quản lý phát triển năng lực nhân viên', 'Hướng dẫn quy trình làm việc và hỗ trợ giải quyết vướng mắc cho nhân sự', NOW(), 'system'), " +
                            "(?, 5, 'Phối hợp liên phòng ban & Báo cáo Ban Giám đốc', 'Tham gia các cuộc họp giao ban và tổng hợp báo cáo kết quả hoạt động', NOW(), 'system')",
                            jdId, jdId, jdId, jdId, jdId);
                        System.out.println(">>> SEED: Sample Published JD & JD Tasks created successfully");
                    }
                }
            } else {
                // Ensure existing JDs are PUBLISHED so dropdown query findPublishedJdsByJobTitles finds them!
                jdbcTemplate.update("UPDATE job_descriptions SET status = 'PUBLISHED' WHERE status IS NULL OR status = 'DRAFT' LIMIT 5");

                // Check if any job_description_tasks exist, if not, add sample tasks to existing JDs!
                Integer taskCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM job_description_tasks", Integer.class);
                if (taskCount == null || taskCount == 0) {
                    List<Long> jdIds = jdbcTemplate.queryForList("SELECT id FROM job_descriptions LIMIT 5", Long.class);
                    for (Long jdId : jdIds) {
                        jdbcTemplate.update(
                            "INSERT INTO job_description_tasks (job_description_id, order_no, title, content, created_at, created_by) VALUES " +
                            "(?, 1, 'Lập kế hoạch & Theo dõi tiến độ công việc tuần/tháng', 'Xây dựng kế hoạch làm việc cho bộ phận và phân công nhiệm vụ', NOW(), 'system'), " +
                            "(?, 2, 'Kiểm soát & Đánh giá kết quả thực hiện KPI phòng ban', 'Rà soát chỉ số KPI định kỳ và đề xuất giải pháp cải tiến', NOW(), 'system'), " +
                            "(?, 3, 'Thẩm định & Phê duyệt bộ chứng từ/hồ sơ nghiệm thu', 'Kiểm tra tính hợp lệ và phê duyệt các chứng từ thuộc thẩm quyền', NOW(), 'system'), " +
                            "(?, 4, 'Đào tạo & Quản lý phát triển năng lực nhân viên', 'Hướng dẫn quy trình làm việc và hỗ trợ giải quyết vướng mắc cho nhân sự', NOW(), 'system'), " +
                            "(?, 5, 'Phối hợp liên phòng ban & Báo cáo Ban Giám đốc', 'Tham gia các cuộc họp giao ban và tổng hợp báo cáo kết quả hoạt động', NOW(), 'system')",
                            jdId, jdId, jdId, jdId, jdId);
                    }
                    System.out.println(">>> SEED: Added sample JD Tasks to existing JDs");
                }
            }
        } catch (Exception e) {
            System.err.println(">>> SEED: Error seeding sample JDs: " + e.getMessage());
        }
    }
}
