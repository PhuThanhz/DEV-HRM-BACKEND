package vn.system.app.modules.accountingdossier.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import vn.system.app.common.config.AppProperties;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.common.util.error.PermissionException;
import vn.system.app.modules.accountingdossier.domain.AccountingDossier;
import vn.system.app.modules.accountingdossier.domain.AccountingDossierCategory;
import vn.system.app.modules.accountingdossier.domain.AccountingDossierApprovalStep;
import vn.system.app.modules.accountingdossier.domain.AccountingDossierOutbox;
import vn.system.app.modules.accountingdossier.domain.enums.ApprovalStepStatus;
import vn.system.app.modules.accountingdossier.domain.enums.ApproverType;
import vn.system.app.modules.accountingdossier.domain.enums.AccountingDossierStatus;
import vn.system.app.modules.accountingdossier.domain.enums.WorkflowTemplateStatus;
import vn.system.app.modules.accountingdossier.domain.request.AccountingDossierActionRequest;
import vn.system.app.modules.accountingdossier.domain.request.AccountingDossierSubmitRequest;
import vn.system.app.modules.accountingdossier.domain.response.ResAccountingDossierBulkActionDTO;
import vn.system.app.modules.accountingdossier.domain.response.ResAccountingDossierDTO;
import vn.system.app.modules.accountingdossier.repository.*;
import vn.system.app.modules.company.domain.Company;
import vn.system.app.modules.company.repository.CompanyRepository;
import vn.system.app.modules.department.domain.Department;
import vn.system.app.modules.department.repository.DepartmentRepository;
import vn.system.app.modules.document.repository.AccountingDocumentCategoryRepository;
import vn.system.app.modules.document.repository.DocumentRepository;
import vn.system.app.modules.permission.domain.Permission;
import vn.system.app.modules.role.domain.Role;
import vn.system.app.modules.section.repository.SectionRepository;
import vn.system.app.modules.user.domain.User;
import vn.system.app.modules.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountingDossierPhase1Test {
    private static final String ACCOUNTANT_APPROVAL_PERMISSION = "Phê duyệt bộ chứng từ kế toán - Kế toán";
    private static final String CHIEF_ACCOUNTANT_APPROVAL_PERMISSION = "Phê duyệt bộ chứng từ kế toán - Kế toán trưởng";
    private static final String DIRECTOR_APPROVAL_PERMISSION = "Phê duyệt bộ chứng từ kế toán - Giám đốc";

    @Mock private AccountingDossierRepository repository;
    @Mock private CompanyRepository companyRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private SectionRepository sectionRepository;
    @Mock private AccountingDossierSequenceRepository sequenceRepository;
    @Mock private AccountingDossierAuditLogRepository auditLogRepository;
    @Mock private AccountingDossierDocumentRepository documentItemRepository;
    @Mock private AccountingDossierCategoryRepository dossierCategoryRepository;
    @Mock private AccountingDocumentCategoryRepository accountingCategoryRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private AccountingDossierDocumentVersionRepository documentVersionRepository;
    @Mock private UserRepository userRepository;
    @Mock private AccountingDossierApprovalStepRepository approvalStepRepository;
    @Mock private AccountingApprovalWorkflowTemplateRepository workflowTemplateRepository;
    @Mock private AccountingApprovalInstanceRepository approvalInstanceRepository;
    @Mock private AppProperties appProperties;
    @Mock private AccountingDossierOutboxRepository outboxRepository;
    @Mock private AccountingApprovalDelegationService delegationService;
    @Mock private AccountingDossierNotificationService notificationService;

    private AccountingDossierService service;
    private ApprovalStepGenerationService stepGenerationService;
    private ApproverResolutionService approverResolutionService;

    @BeforeEach
    void setUp() {
        vn.system.app.common.util.UserScopeContext.set(new vn.system.app.common.util.UserScopeContext.UserScope(
                null, null, null, true, true, false, false));
        approverResolutionService = new ApproverResolutionService(userRepository);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        DossierAuditService dossierAuditService = new DossierAuditService(auditLogRepository);
        stepGenerationService = new ApprovalStepGenerationService(
                approvalStepRepository, workflowTemplateRepository, approvalInstanceRepository,
                userRepository, approverResolutionService, mapper, dossierAuditService
        );

        service = new AccountingDossierService(
                repository, companyRepository, departmentRepository, sectionRepository,
                sequenceRepository, auditLogRepository, documentItemRepository,
                dossierCategoryRepository, accountingCategoryRepository, documentRepository,
                documentVersionRepository, userRepository, approvalStepRepository, appProperties,
                approverResolutionService, stepGenerationService, dossierAuditService,
                outboxRepository, delegationService, notificationService
        );

        // Security context mock
        SecurityContext securityContext = mock(SecurityContext.class);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn("creator@gmail.com");
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);
        when(workflowTemplateRepository.findByStatusOrderByPriorityAscIdAsc(WorkflowTemplateStatus.ACTIVE))
                .thenReturn(Collections.emptyList());
    }

    @AfterEach
    void tearDown() {
        vn.system.app.common.util.UserScopeContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void testDirectorNotConfiguredThrowsException() {
        User creator = new User();
        creator.setId("creator-id");
        creator.setEmail("creator@gmail.com");
        when(userRepository.findByEmail("creator@gmail.com")).thenReturn(creator);

        Company company = new Company();
        company.setId(1L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(100L);
        dossier.setCompany(company);

        // No directors configured
        when(userRepository.findUsersByPermissionAndCompany(anyList(), anyList(), anyBoolean()))
                .thenReturn(Collections.emptyList());

        IdInvalidException ex = assertThrows(IdInvalidException.class, () -> {
            stepGenerationService.generateApprovalSteps(dossier, null);
        });
        assertTrue(ex.getMessage().contains("DIRECTOR_NOT_CONFIGURED"));
    }

    @Test
    void testDirectorAmbiguousThrowsException() {
        User creator = new User();
        creator.setId("creator-id");
        creator.setEmail("creator@gmail.com");
        when(userRepository.findByEmail("creator@gmail.com")).thenReturn(creator);

        Company company = new Company();
        company.setId(1L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(100L);
        dossier.setCompany(company);

        // Two directors configured
        User d1 = new User(); d1.setId("dir-1");
        User d2 = new User(); d2.setId("dir-2");
        when(userRepository.findUsersByPermissionAndCompany(anyList(), anyList(), anyBoolean()))
                .thenReturn(List.of(d1, d2));

        IdInvalidException ex = assertThrows(IdInvalidException.class, () -> {
            stepGenerationService.generateApprovalSteps(dossier, null);
        });
        assertTrue(ex.getMessage().contains("DIRECTOR_MAPPING_AMBIGUOUS"));
    }

    @Test
    void testAdminSub1WithDirectorPermissionIsIgnoredWhenResolvingBusinessDirector() {
        User creator = creator("creator-id");
        when(userRepository.findByEmail("creator@gmail.com")).thenReturn(creator);

        Company company = company(1L);
        AccountingDossier dossier = dossier(100L, company, AccountingDossierStatus.DRAFT);

        User director = userWithRole("director-id", "DIRECTOR");
        User adminSub1 = userWithRole("admin-sub-1-id", "ADMIN_SUB_1");
        when(userRepository.findUsersByPermissionAndCompany(anyList(), anyList(), anyBoolean()))
                .thenReturn(List.of(director, adminSub1));

        List<User> resolvedDirectors = approverResolutionService.resolveAllDirectorUserIds(company.getId());
        assertEquals(1, resolvedDirectors.size());
        assertEquals("director-id", resolvedDirectors.get(0).getId());

        stubApproverLookup(userWithRole("accountant-id", "KETOAN"),
                userWithRole("chief-id", "KETOANTRUONG"),
                director);
        assertDoesNotThrow(() -> stepGenerationService.generateApprovalSteps(dossier, null));

        List<AccountingDossierApprovalStep> steps = captureSavedSteps();
        AccountingDossierApprovalStep directorStep = steps.stream()
                .filter(step -> step.getApproverType() == ApproverType.DIRECTOR)
                .findFirst()
                .orElseThrow();
        assertEquals("director-id", directorStep.getApproverUserId());
    }

    @Test
    void testAdminOnlyWithDirectorPermissionIsNotConfiguredAsBusinessDirector() {
        User creator = creator("creator-id");
        when(userRepository.findByEmail("creator@gmail.com")).thenReturn(creator);

        Company company = company(1L);
        AccountingDossier dossier = dossier(100L, company, AccountingDossierStatus.DRAFT);

        User adminSub1 = userWithRole("admin-sub-1-id", "ADMIN_SUB_1");
        when(userRepository.findUsersByPermissionAndCompany(anyList(), anyList(), anyBoolean()))
                .thenReturn(List.of(adminSub1));

        assertTrue(approverResolutionService.resolveAllDirectorUserIds(company.getId()).isEmpty());

        IdInvalidException ex = assertThrows(IdInvalidException.class, () -> {
            stepGenerationService.generateApprovalSteps(dossier, null);
        });
        assertTrue(ex.getMessage().contains("DIRECTOR_NOT_CONFIGURED"));
    }

    @Test
    void testCategoryInvalidApprovalStepsConfigFailsFastWithoutDefaultSteps() {
        User creator = creator("creator-id");
        when(userRepository.findByEmail("creator@gmail.com")).thenReturn(creator);

        AccountingDossierCategory category = new AccountingDossierCategory();
        category.setApprovalStepsConfig("{invalid-json");
        AccountingDossier dossier = dossier(100L, company(1L), AccountingDossierStatus.DRAFT);
        dossier.setDossierCategory(category);

        IdInvalidException ex = assertThrows(IdInvalidException.class, () -> {
            stepGenerationService.generateApprovalSteps(dossier, null);
        });

        assertTrue(ex.getMessage().contains("INVALID_WORKFLOW_CONFIG"));
        verify(approvalStepRepository, never()).saveAll(anyList());
    }

    @Test
    void testCategoryWithoutApprovalStepsConfigStillUsesDefaultStepsAndAppendsDirector() {
        User creator = creator("creator-id");
        when(userRepository.findByEmail("creator@gmail.com")).thenReturn(creator);
        stubApproverLookup(userWithRole("accountant-id", "KETOAN"),
                userWithRole("chief-id", "KETOANTRUONG"),
                userWithRole("director-id", "DIRECTOR"));

        AccountingDossierCategory category = new AccountingDossierCategory();
        category.setApprovalStepsConfig("   ");
        AccountingDossier dossier = dossier(100L, company(1L), AccountingDossierStatus.DRAFT);
        dossier.setDossierCategory(category);

        stepGenerationService.generateApprovalSteps(dossier, null);

        List<AccountingDossierApprovalStep> steps = captureSavedSteps();
        assertEquals(4, steps.size());
        assertEquals(ApproverType.DEPARTMENT_MANAGER, steps.get(0).getApproverType());
        assertEquals(ApproverType.ACCOUNTANT, steps.get(1).getApproverType());
        assertEquals(ApproverType.CHIEF_ACCOUNTANT, steps.get(2).getApproverType());
        assertEquals("chief-id", steps.get(2).getApproverUserId());
        assertEquals(ApproverType.DIRECTOR, steps.get(3).getApproverType());
        assertEquals("director-id", steps.get(3).getApproverUserId());
    }

    @Test
    void testAccountantResolverUsesPermissionAndIgnoresAdminWithoutFallback() {
        User admin = userWithRole("admin-id", "SUPER_ADMIN");
        User accountant2 = userWithRole("accountant-2", "KETOAN");
        User accountant1 = userWithRole("accountant-1", "KETOAN");
        when(userRepository.findUsersByPermissionAndCompany(anyList(), anyList(), anyBoolean()))
                .thenReturn(List.of(admin, accountant2, accountant1))
                .thenReturn(List.of(admin));

        assertEquals("accountant-1", approverResolutionService.resolveAccountantUserId(1L));
        assertNull(approverResolutionService.resolveAccountantUserId(1L));
        verify(userRepository, times(2)).findUsersByPermissionAndCompany(
                eq(List.of(ACCOUNTANT_APPROVAL_PERMISSION)), eq(List.of(1L)), eq(true));
        verify(userRepository, never()).findAll();
    }

    @Test
    void testChiefAccountantResolverUsesPermissionAndIgnoresAdminWithoutFallback() {
        User admin = userWithRole("admin-id", "ADMIN_SUB_1");
        User chief2 = userWithRole("chief-2", "KETOANTRUONG");
        User chief1 = userWithRole("chief-1", "KETOANTRUONG");
        when(userRepository.findUsersByPermissionAndCompany(anyList(), anyList(), anyBoolean()))
                .thenReturn(List.of(admin, chief2, chief1))
                .thenReturn(List.of(admin));

        assertEquals("chief-1", approverResolutionService.resolveChiefAccountantUserId(1L));
        assertNull(approverResolutionService.resolveChiefAccountantUserId(1L));
        verify(userRepository, times(2)).findUsersByPermissionAndCompany(
                eq(List.of(CHIEF_ACCOUNTANT_APPROVAL_PERMISSION)), eq(List.of(1L)), eq(true));
        verify(userRepository, never()).findAll();
    }

    @Test
    void testSelfApprovalBlocked() {
        User creator = new User();
        creator.setId("director-id");
        creator.setEmail("creator@gmail.com");
        when(userRepository.findByEmail("creator@gmail.com")).thenReturn(creator);

        Company company = new Company();
        company.setId(1L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(100L);
        dossier.setCompany(company);

        // Director is same as creator
        User director = new User();
        director.setId("director-id");
        when(userRepository.findUsersByPermissionAndCompany(anyList(), anyList(), anyBoolean()))
                .thenReturn(List.of(director));

        IdInvalidException ex = assertThrows(IdInvalidException.class, () -> {
            stepGenerationService.generateApprovalSteps(dossier, null);
        });
        assertTrue(ex.getMessage().contains("SELF_APPROVAL_BLOCKED"));
    }

    @Test
    void testSameUserApprovalBlocked() {
        User creator = new User();
        creator.setId("creator-id");
        creator.setEmail("creator@gmail.com");
        when(userRepository.findByEmail("creator@gmail.com")).thenReturn(creator);

        Company company = new Company();
        company.setId(1L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(100L);
        dossier.setCompany(company);

        // Chief accountant and director are the same
        User director = new User();
        director.setId("same-id");
        when(userRepository.findUsersByPermissionAndCompany(anyList(), anyList(), anyBoolean()))
                .thenReturn(List.of(director));

        User chiefAcc = new User();
        chiefAcc.setId("same-id");
        chiefAcc.setActive(true);
        Role kttRole = new Role(); kttRole.setName("KETOANTRUONG"); kttRole.setActive(true);
        chiefAcc.setRole(kttRole);
        // userRepository.findUsersByPermissionAndCompany inside resolveChiefAccountantUserId
        when(userRepository.findAll()).thenReturn(List.of(chiefAcc));

        IdInvalidException ex = assertThrows(IdInvalidException.class, () -> {
            stepGenerationService.generateApprovalSteps(dossier, null);
        });
        assertTrue(ex.getMessage().contains("SAME_USER_APPROVAL_BLOCKED"));
    }

    @Test
    void testDirectorReturnNoteRequired() {
        // Director là người đang đăng nhập trong security context ("creator@gmail.com")
        User director = new User();
        director.setId("director-id");
        director.setEmail("creator@gmail.com");
        Role r = new Role(); r.setName("DIRECTOR");
        Permission dirPerm = new Permission();
        dirPerm.setName("Phê duyệt bộ chứng từ kế toán - Giám đốc");
        r.setPermissions(new java.util.ArrayList<>(List.of(dirPerm)));
        director.setRole(r);
        when(userRepository.findByEmail("creator@gmail.com")).thenReturn(director);

        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(100L);
        dossier.setActive(true);
        dossier.setCompany(company);
        dossier.setStatus(AccountingDossierStatus.RETURN_REQUESTED);
        dossier.setReturnCount(1);

        AccountingDossierApprovalStep step = new AccountingDossierApprovalStep();
        step.setDossier(dossier);
        step.setStatus(ApprovalStepStatus.CURRENT);
        step.setApproverType(ApproverType.DIRECTOR);
        step.setApproverUserId("director-id");

        when(repository.findById(100L)).thenReturn(Optional.of(dossier));
        when(approvalStepRepository.findByDossierIdAndActiveTrueOrderByStepOrderAsc(100L))
                .thenReturn(List.of(step));

        // note rỗng → phải throw IdInvalidException
        AccountingDossierActionRequest req = new AccountingDossierActionRequest();
        req.setNote("");

        Exception ex = assertThrows(Exception.class, () ->
                service.handleReturnResponse(100L, "ACCEPT", req));
        // Tìm tới gốc exception nếu là NestedServletException/InvocationTargetException
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        assertTrue(cause.getMessage() != null &&
                cause.getMessage().contains("Vui lòng nhập lý do hoàn trả tại bước Giám đốc"));
    }

    @Test
    void testEmergencyReassignDirectorSuccess() {
        // Actor is SUPER_ADMIN
        User admin = new User();
        admin.setId("admin-id");
        admin.setEmail("admin@gmail.com");
        Role adminRole = new Role(); adminRole.setName("SUPER_ADMIN");
        admin.setRole(adminRole);
        when(userRepository.findByEmail("creator@gmail.com")).thenReturn(admin);

        Company company = new Company(); company.setId(1L);
        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(100L);
        dossier.setStatus(AccountingDossierStatus.SUBMITTED);
        dossier.setCompany(company);

        AccountingDossierApprovalStep step = new AccountingDossierApprovalStep();
        step.setDossier(dossier);
        step.setStatus(ApprovalStepStatus.CURRENT);
        step.setApproverType(ApproverType.DIRECTOR);
        step.setApproverUserId("old-director-id");

        when(repository.findById(100L)).thenReturn(Optional.of(dossier));
        when(approvalStepRepository.findByDossierIdAndActiveTrueOrderByStepOrderAsc(100L))
                .thenReturn(List.of(step));

        // Target director is valid and active
        User newDirector = new User();
        newDirector.setId("new-director-id");
        newDirector.setActive(true);
        when(userRepository.findById("new-director-id")).thenReturn(Optional.of(newDirector));

        // Mock resolveAllDirectorUserIds
        when(userRepository.findUsersByPermissionAndCompany(anyList(), anyList(), anyBoolean()))
                .thenReturn(List.of(newDirector));

        ResAccountingDossierDTO res = service.reassignDirector(100L, "new-director-id", "Change director");
        assertNotNull(res);
        assertEquals("new-director-id", step.getApproverUserId());
        verify(outboxRepository, times(1)).saveAndFlush(any(AccountingDossierOutbox.class));
    }

    @Test
    void testCustomStepsWithoutDirectorStillAppendsDirectorLast() {
        User creator = creator("creator-id");
        when(userRepository.findByEmail("creator@gmail.com")).thenReturn(creator);
        stubApproverLookup(userWithRole("accountant-id", "KETOAN"),
                userWithRole("chief-id", "KETOANTRUONG"),
                userWithRole("director-id", "DIRECTOR"));

        AccountingDossier dossier = dossier(100L, company(1L), AccountingDossierStatus.DRAFT);

        AccountingDossierSubmitRequest req = new AccountingDossierSubmitRequest();
        AccountingDossierSubmitRequest.CustomStep custom = new AccountingDossierSubmitRequest.CustomStep();
        custom.setStepOrder(1);
        custom.setStepName("Custom verifier");
        custom.setApproverType("CUSTOM_USER");
        custom.setApproverUserId("custom-user-id");
        req.setCustomSteps(List.of(custom));

        stepGenerationService.generateApprovalSteps(dossier, req);

        List<AccountingDossierApprovalStep> steps = captureSavedSteps();
        assertEquals(2, steps.size());
        assertEquals(ApproverType.CUSTOM, steps.get(0).getApproverType());
        assertEquals(ApproverType.DIRECTOR, steps.get(1).getApproverType());
        assertEquals("director-id", steps.get(1).getApproverUserId());
        assertEquals(ApprovalStepStatus.PENDING, steps.get(1).getStatus());
    }

    @Test
    void testCategoryJsonWithoutDirectorStillAppendsDirectorLast() {
        User creator = creator("creator-id");
        when(userRepository.findByEmail("creator@gmail.com")).thenReturn(creator);
        stubApproverLookup(userWithRole("accountant-id", "KETOAN"),
                userWithRole("chief-id", "KETOANTRUONG"),
                userWithRole("director-id", "DIRECTOR"));

        AccountingDossierCategory category = new AccountingDossierCategory();
        category.setApprovalStepsConfig("[{\"stepOrder\":1,\"stepName\":\"Kế toán kiểm tra\",\"approverType\":\"ACCOUNTANT\"}]");
        AccountingDossier dossier = dossier(100L, company(1L), AccountingDossierStatus.DRAFT);
        dossier.setDossierCategory(category);

        stepGenerationService.generateApprovalSteps(dossier, null);

        List<AccountingDossierApprovalStep> steps = captureSavedSteps();
        assertEquals(2, steps.size());
        assertEquals(ApproverType.ACCOUNTANT, steps.get(0).getApproverType());
        assertEquals(ApproverType.DIRECTOR, steps.get(1).getApproverType());
        assertEquals("director-id", steps.get(1).getApproverUserId());
        assertEquals(ApprovalStepStatus.PENDING, steps.get(1).getStatus());
    }

    @Test
    void testChiefAccountantApprovalBeforeDirectorKeepsDossierInReview() {
        User chief = user("chief-id");
        chief.setEmail("creator@gmail.com");
        when(userRepository.findByEmail("creator@gmail.com")).thenReturn(chief);

        AccountingDossier dossier = dossier(100L, company(1L), AccountingDossierStatus.IN_REVIEW);
        AccountingDossierApprovalStep chiefStep = step(301L, dossier, 3, ApproverType.CHIEF_ACCOUNTANT,
                "chief-id", ApprovalStepStatus.CURRENT);
        AccountingDossierApprovalStep directorStep = step(302L, dossier, 4, ApproverType.DIRECTOR,
                "director-id", ApprovalStepStatus.PENDING);

        when(repository.findById(100L)).thenReturn(Optional.of(dossier));
        when(approvalStepRepository.findByDossierIdAndActiveTrueOrderByStepOrderAsc(100L))
                .thenReturn(List.of(chiefStep, directorStep));
        when(repository.save(any(AccountingDossier.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResAccountingDossierDTO result = service.approve(100L, null);

        assertEquals(AccountingDossierStatus.IN_REVIEW, result.getStatus());
        assertNull(dossier.getApprovedAt());
        assertEquals(ApprovalStepStatus.APPROVED, chiefStep.getStatus());
        assertEquals(ApprovalStepStatus.CURRENT, directorStep.getStatus());
    }

    @Test
    void testDirectorFinalApprovalApprovesDossierAndSetsApprovedAt() {
        User director = user("director-id");
        director.setEmail("creator@gmail.com");
        when(userRepository.findByEmail("creator@gmail.com")).thenReturn(director);

        AccountingDossier dossier = dossier(100L, company(1L), AccountingDossierStatus.IN_REVIEW);
        AccountingDossierApprovalStep directorStep = step(401L, dossier, 4, ApproverType.DIRECTOR,
                "director-id", ApprovalStepStatus.CURRENT);

        when(repository.findById(100L)).thenReturn(Optional.of(dossier));
        when(approvalStepRepository.findByDossierIdAndActiveTrueOrderByStepOrderAsc(100L))
                .thenReturn(List.of(directorStep));
        when(repository.save(any(AccountingDossier.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResAccountingDossierDTO result = service.approve(100L, null);

        assertEquals(AccountingDossierStatus.APPROVED, result.getStatus());
        assertNotNull(dossier.getApprovedAt());
        assertEquals(ApprovalStepStatus.APPROVED, directorStep.getStatus());
    }

    @Test
    void testBulkApproveChiefCannotApproveCurrentDirectorStepButDirectorCan() {
        User chief = user("chief-id");
        chief.setEmail("creator@gmail.com");
        when(userRepository.findByEmail("creator@gmail.com")).thenReturn(chief);

        AccountingDossier chiefAttemptDossier = dossier(100L, company(1L), AccountingDossierStatus.IN_REVIEW);
        AccountingDossierApprovalStep directorStepForChiefAttempt = step(501L, chiefAttemptDossier, 4,
                ApproverType.DIRECTOR, "director-id", ApprovalStepStatus.CURRENT);

        when(repository.findById(100L)).thenReturn(Optional.of(chiefAttemptDossier));
        when(approvalStepRepository.findByDossierIdAndActiveTrueOrderByStepOrderAsc(100L))
                .thenReturn(List.of(directorStepForChiefAttempt));

        ResAccountingDossierBulkActionDTO chiefResult = service.bulkApprove(List.of(100L), null);

        assertEquals(0, chiefResult.getSuccessCount());
        assertEquals(1, chiefResult.getFailureCount());
        assertEquals(AccountingDossierStatus.IN_REVIEW, chiefAttemptDossier.getStatus());
        assertEquals(ApprovalStepStatus.CURRENT, directorStepForChiefAttempt.getStatus());

        User director = user("director-id");
        director.setEmail("creator@gmail.com");
        when(userRepository.findByEmail("creator@gmail.com")).thenReturn(director);

        AccountingDossier directorAttemptDossier = dossier(101L, company(1L), AccountingDossierStatus.IN_REVIEW);
        AccountingDossierApprovalStep directorStep = step(502L, directorAttemptDossier, 4,
                ApproverType.DIRECTOR, "director-id", ApprovalStepStatus.CURRENT);

        when(repository.findById(101L)).thenReturn(Optional.of(directorAttemptDossier));
        when(approvalStepRepository.findByDossierIdAndActiveTrueOrderByStepOrderAsc(101L))
                .thenReturn(List.of(directorStep));
        when(repository.save(any(AccountingDossier.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResAccountingDossierBulkActionDTO directorResult = service.bulkApprove(List.of(101L), null);

        assertEquals(1, directorResult.getSuccessCount());
        assertEquals(0, directorResult.getFailureCount());
        assertEquals(AccountingDossierStatus.APPROVED, directorAttemptDossier.getStatus());
        assertEquals(ApprovalStepStatus.APPROVED, directorStep.getStatus());
    }

    @Test
    void testV002BackfillHasNotExistsGuardToPreventDuplicateDirectorSteps() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/manual/V002__phase1_director_setup.sql"));

        assertTrue(sql.contains("NOT EXISTS"));
        assertTrue(sql.contains("approver_type = 'DIRECTOR'"));
        assertTrue(sql.contains("active = 1"));
    }

    private List<AccountingDossierApprovalStep> captureSavedSteps() {
        ArgumentCaptor<List<AccountingDossierApprovalStep>> captor = ArgumentCaptor.forClass(List.class);
        verify(approvalStepRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private Company company(Long id) {
        Company company = new Company();
        company.setId(id);
        return company;
    }

    private AccountingDossier dossier(Long id, Company company, AccountingDossierStatus status) {
        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(id);
        dossier.setCompany(company);
        dossier.setStatus(status);
        dossier.setActive(true);
        dossier.setDossierCode("BCT-TEST-" + id);
        return dossier;
    }

    private User creator(String id) {
        User creator = user(id);
        creator.setEmail("creator@gmail.com");
        return creator;
    }

    private User user(String id) {
        User user = new User();
        user.setId(id);
        user.setActive(true);
        return user;
    }

    private User userWithRole(String id, String roleName) {
        User user = user(id);
        Role role = new Role();
        role.setName(roleName);
        role.setActive(true);
        user.setRole(role);
        return user;
    }

    private void stubApproverLookup(User accountant, User chiefAccountant, User director) {
        when(userRepository.findUsersByPermissionAndCompany(anyList(), anyList(), anyBoolean()))
                .thenAnswer(invocation -> {
                    List<String> permissionNames = invocation.getArgument(0);
                    if (permissionNames.contains(ACCOUNTANT_APPROVAL_PERMISSION)) {
                        return accountant == null ? List.of() : List.of(accountant);
                    }
                    if (permissionNames.contains(CHIEF_ACCOUNTANT_APPROVAL_PERMISSION)) {
                        return chiefAccountant == null ? List.of() : List.of(chiefAccountant);
                    }
                    if (permissionNames.contains(DIRECTOR_APPROVAL_PERMISSION)) {
                        return director == null ? List.of() : List.of(director);
                    }
                    return List.of();
                });
    }

    private User chiefAccountant(String id) {
        User chief = user(id);
        Role role = new Role();
        role.setName("CHIEF_ACCOUNTANT");
        role.setActive(true);
        chief.setRole(role);
        return chief;
    }

    private AccountingDossierApprovalStep step(Long id, AccountingDossier dossier, int order,
            ApproverType type, String approverUserId, ApprovalStepStatus status) {
        AccountingDossierApprovalStep step = new AccountingDossierApprovalStep();
        step.setId(id);
        step.setDossier(dossier);
        step.setStepOrder(order);
        step.setStepName(type.name());
        step.setApproverType(type);
        step.setApproverUserId(approverUserId);
        step.setStatus(status);
        step.setActive(true);
        return step;
    }
}
