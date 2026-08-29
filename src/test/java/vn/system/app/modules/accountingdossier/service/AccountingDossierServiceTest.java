package vn.system.app.modules.accountingdossier.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import vn.system.app.modules.accountingdossier.repository.AccountingDossierListProjection;
import org.springframework.data.repository.query.FluentQuery;
import java.util.function.Function;

import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.common.util.error.PermissionException;
import vn.system.app.common.util.error.DuplicateInvoiceWarningException;
import vn.system.app.common.config.AppProperties;
import vn.system.app.modules.accountingdossier.domain.AccountingDossier;
import vn.system.app.modules.accountingdossier.domain.AccountingDossierCategory;
import vn.system.app.modules.accountingdossier.domain.AccountingDossierCategoryDocument;
import vn.system.app.modules.accountingdossier.domain.AccountingDossierDocument;
import vn.system.app.modules.accountingdossier.domain.AccountingApprovalInstance;
import vn.system.app.modules.accountingdossier.domain.AccountingApprovalWorkflowScope;
import vn.system.app.modules.accountingdossier.domain.AccountingApprovalWorkflowStep;
import vn.system.app.modules.accountingdossier.domain.AccountingApprovalWorkflowTemplate;
import vn.system.app.modules.accountingdossier.domain.enums.AccountingDossierCategoryMode;
import vn.system.app.modules.accountingdossier.domain.enums.AccountingDossierStatus;
import vn.system.app.modules.accountingdossier.domain.enums.ApproverType;
import vn.system.app.modules.accountingdossier.domain.enums.ApprovalStepStatus;
import vn.system.app.modules.accountingdossier.domain.enums.ApproverStrategy;
import vn.system.app.modules.accountingdossier.domain.enums.WorkflowInstanceStatus;
import vn.system.app.modules.accountingdossier.domain.enums.WorkflowScopeType;
import vn.system.app.modules.accountingdossier.domain.enums.WorkflowTemplateStatus;
import vn.system.app.modules.accountingdossier.domain.response.ResAccountingDossierBulkActionDTO;
import vn.system.app.modules.accountingdossier.domain.response.ResAccountingDossierDTO;
import vn.system.app.modules.accountingdossier.domain.request.AccountingDossierSubmitRequest;
import vn.system.app.modules.accountingdossier.domain.request.AccountingDossierDocumentRequest;
import vn.system.app.modules.accountingdossier.domain.AccountingDossierApprovalStep;
import vn.system.app.modules.accountingdossier.repository.AccountingDossierAuditLogRepository;
import vn.system.app.modules.accountingdossier.repository.AccountingDossierCategoryRepository;
import vn.system.app.modules.accountingdossier.repository.AccountingDossierDocumentRepository;
import vn.system.app.modules.accountingdossier.repository.AccountingDossierDocumentVersionRepository;
import vn.system.app.modules.accountingdossier.repository.AccountingDossierApprovalStepRepository;
import vn.system.app.modules.accountingdossier.repository.AccountingDossierRepository;
import vn.system.app.modules.accountingdossier.repository.AccountingDossierOutboxRepository;
import vn.system.app.modules.accountingdossier.repository.AccountingApprovalInstanceRepository;
import vn.system.app.modules.accountingdossier.repository.AccountingApprovalWorkflowTemplateRepository;
import vn.system.app.modules.accountingdossier.repository.AccountingDossierSequenceRepository;
import vn.system.app.modules.user.domain.User;
import vn.system.app.modules.company.domain.Company;
import vn.system.app.modules.company.repository.CompanyRepository;
import vn.system.app.modules.department.domain.Department;
import vn.system.app.modules.department.repository.DepartmentRepository;
import vn.system.app.modules.document.domain.AccountingDocumentCategory;
import vn.system.app.modules.document.repository.AccountingDocumentCategoryRepository;
import vn.system.app.modules.document.repository.DocumentRepository;
import vn.system.app.modules.section.repository.SectionRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountingDossierServiceTest {
    private static final String ACCOUNTANT_APPROVAL_PERMISSION = "Phê duyệt bộ chứng từ kế toán - Kế toán";
    private static final String CHIEF_ACCOUNTANT_APPROVAL_PERMISSION = "Phê duyệt bộ chứng từ kế toán - Kế toán trưởng";
    private static final String DIRECTOR_APPROVAL_PERMISSION = "Phê duyệt bộ chứng từ kế toán - Giám đốc";

    @Mock
    private AccountingDossierRepository repository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private DepartmentRepository departmentRepository;
    @Mock
    private SectionRepository sectionRepository;
    @Mock
    private AccountingDossierSequenceRepository sequenceRepository;
    @Mock
    private AccountingDossierAuditLogRepository auditLogRepository;
    @Mock
    private AccountingDossierDocumentRepository documentItemRepository;
    @Mock
    private AccountingDossierCategoryRepository dossierCategoryRepository;
    @Mock
    private AccountingDocumentCategoryRepository accountingCategoryRepository;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private AccountingDossierDocumentVersionRepository documentVersionRepository;
    @Mock
    private vn.system.app.modules.user.repository.UserRepository userRepository;
    @Mock
    private AccountingDossierApprovalStepRepository approvalStepRepository;
    @Mock
    private AppProperties appProperties;
    @Mock
    private AccountingDossierOutboxRepository outboxRepository;
    @Mock
    private AccountingApprovalDelegationService delegationService;
    @Mock
    private AccountingDossierNotificationService notificationService;
    @Mock
    private AccountingApprovalWorkflowTemplateRepository workflowTemplateRepository;
    @Mock
    private AccountingApprovalInstanceRepository approvalInstanceRepository;

    private AccountingDossierService service;

    private SecurityContext originalSecurityContext;

    @BeforeEach
    void setUp() {
        ApproverResolutionService approverResolutionService = new ApproverResolutionService(userRepository);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        DossierAuditService dossierAuditService = new DossierAuditService(auditLogRepository);
        ApprovalStepGenerationService approvalStepGenerationService = new ApprovalStepGenerationService(
                approvalStepRepository, workflowTemplateRepository, approvalInstanceRepository,
                userRepository, approverResolutionService, mapper, dossierAuditService
        );

        service = new AccountingDossierService(
                repository, companyRepository, departmentRepository, sectionRepository,
                sequenceRepository, auditLogRepository, documentItemRepository,
                dossierCategoryRepository, accountingCategoryRepository, documentRepository,
                documentVersionRepository, userRepository, approvalStepRepository, appProperties,
                approverResolutionService, approvalStepGenerationService, dossierAuditService,
                outboxRepository, delegationService, notificationService
        );

        // Unit tests: bypass security by setting superAdmin scope
        vn.system.app.common.util.UserScopeContext.set(
            new vn.system.app.common.util.UserScopeContext.UserScope(
                "test-user", null, null, true, true, false, false
            )
        );

        originalSecurityContext = SecurityContextHolder.getContext();
        SecurityContext securityContext = mock(SecurityContext.class);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn("test-user");
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);
        when(appProperties.getBaseUrl()).thenReturn("http://localhost:8080");
        when(workflowTemplateRepository.findByStatusOrderByPriorityAscIdAsc(WorkflowTemplateStatus.ACTIVE))
                .thenReturn(Collections.emptyList());
        User authenticatedUser = new User();
        authenticatedUser.setId("test-user");
        authenticatedUser.setEmail("test-user");
        lenient().when(userRepository.findByEmail("test-user")).thenReturn(authenticatedUser);
        // saveDossierWithOptimisticLockCheck uses saveAndFlush — stub it globally
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.setContext(originalSecurityContext);
        vn.system.app.common.util.UserScopeContext.clear();
    }

    @Test
    void testDeleteDossierSuccess() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setStatus(AccountingDossierStatus.DRAFT);
        dossier.setActive(true);
        dossier.setCompany(company);

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(documentItemRepository.findByDossierIdAndActiveTrue(dossierId)).thenReturn(Collections.emptyList());

        // We also need to stub user scopes check inside validateCompanyScope if any.
        // Let's check how validateCompanyScope is implemented in service.

        service.delete(dossierId);

        assertFalse(dossier.isActive());
        assertNotNull(dossier.getDeletedAt());
        assertEquals("test-user", dossier.getDeletedBy());
        verify(repository, times(1)).save(dossier);
    }

    @Test
    void testSubmitDossierMissingRequiredDocuments() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setStatus(AccountingDossierStatus.DRAFT);
        dossier.setCategoryMode(AccountingDossierCategoryMode.TEMPLATE);
        dossier.setCompany(company);

        AccountingDossierCategory category = new AccountingDossierCategory();
        category.setId(5L);

        AccountingDocumentCategory docCategory = new AccountingDocumentCategory();
        docCategory.setId(20L);
        docCategory.setCategoryName("Hóa đơn");

        AccountingDossierCategoryDocument categoryDoc = new AccountingDossierCategoryDocument();
        categoryDoc.setDocumentCategory(docCategory);
        categoryDoc.setRequired(true);

        category.setCategoryDocuments(List.of(categoryDoc));
        dossier.setDossierCategory(category);

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(documentItemRepository.countByDossierIdAndActiveTrue(dossierId)).thenReturn(1L);
        // Returns an empty list, so the required "Hóa đơn" document category is missing.
        when(documentItemRepository.findByDossierIdAndActiveTrue(dossierId)).thenReturn(Collections.emptyList());

        IdInvalidException exception = assertThrows(IdInvalidException.class, () -> {
            service.submit(dossierId);
        });

        assertTrue(exception.getMessage().contains("Thiếu chứng từ bắt buộc: Hóa đơn"));
    }

    @Test
    void testSubmitDossierRequiredDocumentWithoutAttachmentThrowsException() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setStatus(AccountingDossierStatus.DRAFT);
        dossier.setCategoryMode(AccountingDossierCategoryMode.TEMPLATE);
        dossier.setCompany(company);

        AccountingDossierCategory category = new AccountingDossierCategory();
        category.setId(5L);

        AccountingDocumentCategory docCategory = new AccountingDocumentCategory();
        docCategory.setId(20L);
        docCategory.setCategoryName("Hóa đơn");

        AccountingDossierCategoryDocument categoryDoc = new AccountingDossierCategoryDocument();
        categoryDoc.setDocumentCategory(docCategory);
        categoryDoc.setRequired(true);

        AccountingDossierDocument placeholder = new AccountingDossierDocument();
        placeholder.setAccountingCategory(docCategory);
        placeholder.setDocumentName("Hóa đơn");

        category.setCategoryDocuments(List.of(categoryDoc));
        dossier.setDossierCategory(category);

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(documentItemRepository.countByDossierIdAndActiveTrue(dossierId)).thenReturn(1L);
        when(documentItemRepository.findByDossierIdAndActiveTrue(dossierId)).thenReturn(List.of(placeholder));

        IdInvalidException exception = assertThrows(IdInvalidException.class, () -> service.submit(dossierId));

        assertTrue(exception.getMessage().contains("chưa có file/link đính kèm: Hóa đơn"));
    }

    @Test
    void testSubmitDossierGeneratesApprovalSteps() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setStatus(AccountingDossierStatus.DRAFT);
        dossier.setCategoryMode(AccountingDossierCategoryMode.UNSTRUCTURED);
        dossier.setCompany(company);
        dossier.setDossierCode("BCT-2026-000001");

        User creator = new User();
        creator.setId("user-creator-id");
        creator.setEmail("test-user");
        
        User manager = new User();
        manager.setId("user-manager-id");
        manager.setEmail("manager-email");
        creator.setDirectManager(manager);

        User accountant = new User();
        accountant.setId("user-accountant-id");

        User chief = new User();
        chief.setId("user-chief-id");
        chief.setActive(true);
        vn.system.app.modules.role.domain.Role role = new vn.system.app.modules.role.domain.Role();
        role.setName("CHIEF_ACCOUNTANT");
        chief.setRole(role);

        User director = new User();
        director.setId("user-director-id");
        director.setActive(true);

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(documentItemRepository.countByDossierIdAndActiveTrue(dossierId)).thenReturn(1L);
        when(userRepository.findByEmail("test-user")).thenReturn(creator);
        when(approvalStepRepository.findByDossierIdAndActiveTrue(dossierId)).thenReturn(Collections.emptyList());
        stubApproverLookup(accountant, chief, director);
        when(repository.save(any(AccountingDossier.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResAccountingDossierDTO result = service.submit(dossierId);

        assertEquals(AccountingDossierStatus.SUBMITTED, result.getStatus());
        verify(approvalStepRepository, times(1)).saveAll(anyList());
    }

    @Test
    void testSubmitDossierCreatesRoleBasedAccountantStep() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setStatus(AccountingDossierStatus.DRAFT);
        dossier.setCategoryMode(AccountingDossierCategoryMode.UNSTRUCTURED);
        dossier.setCompany(company);
        dossier.setDossierCode("BCT-2026-000001");

        User creator = new User();
        creator.setId("user-creator-id");
        creator.setEmail("test-user");

        User chief = new User();
        chief.setId("user-chief-id");
        chief.setActive(true);
        vn.system.app.modules.role.domain.Role chiefRole = new vn.system.app.modules.role.domain.Role();
        chiefRole.setName("CHIEF_ACCOUNTANT");
        chief.setRole(chiefRole);

        User director = new User();
        director.setId("director-id");
        director.setActive(true);

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(documentItemRepository.countByDossierIdAndActiveTrue(dossierId)).thenReturn(1L);
        when(userRepository.findByEmail("test-user")).thenReturn(creator);
        when(approvalStepRepository.findByDossierIdAndActiveTrue(dossierId)).thenReturn(Collections.emptyList());
        stubApproverLookup(null, chief, director);
        when(repository.save(any(AccountingDossier.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.submit(dossierId);

        verify(approvalStepRepository).saveAll(argThat(steps -> {
            List<AccountingDossierApprovalStep> list = (List<AccountingDossierApprovalStep>) steps;
            return list.stream().anyMatch(step ->
                    step.getApproverType() == ApproverType.ACCOUNTANT
                            && step.getApproverUserId() == null);
        }));
    }

    @Test
    void testSubmitDossierWithCustomSteps() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setStatus(AccountingDossierStatus.DRAFT);
        dossier.setCompany(company);

        User creator = new User();
        creator.setId("user-creator-id");
        creator.setEmail("test-user");

        User director = new User();
        director.setId("director-id");
        director.setActive(true);

        User chief = new User();
        chief.setId("chief-id");
        chief.setActive(true);

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(documentItemRepository.countByDossierIdAndActiveTrue(dossierId)).thenReturn(1L);
        when(userRepository.findByEmail("test-user")).thenReturn(creator);
        stubApproverLookup(null, chief, director);
        when(repository.save(any(AccountingDossier.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AccountingDossierSubmitRequest req = new AccountingDossierSubmitRequest();
        AccountingDossierSubmitRequest.CustomStep step1 = new AccountingDossierSubmitRequest.CustomStep();
        step1.setStepOrder(1);
        step1.setStepName("Bước duyệt tùy chọn");
        step1.setApproverType("CUSTOM_USER");
        step1.setApproverUserId("user-custom-id");
        req.setCustomSteps(List.of(step1));

        ResAccountingDossierDTO result = service.submit(dossierId, req);

        assertEquals(AccountingDossierStatus.SUBMITTED, result.getStatus());
        verify(approvalStepRepository, times(1)).saveAll(anyList());
    }

    @Test
    void testSubmitDossierWithCategoryStepsConfig() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);

        AccountingDossierCategory category = new AccountingDossierCategory();
        category.setId(100L);
        category.setApprovalStepsConfig("[{\"stepOrder\":1,\"stepName\":\"Bước mẫu\",\"approverType\":\"ACCOUNTANT\"}]");

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setStatus(AccountingDossierStatus.DRAFT);
        dossier.setCompany(company);
        dossier.setDossierCategory(category);

        User creator = new User();
        creator.setId("user-creator-id");
        creator.setEmail("test-user");

        User accountant = new User();
        accountant.setId("user-accountant-id");

        User chief = new User();
        chief.setId("user-chief-id");
        chief.setActive(true);

        User director = new User();
        director.setId("user-director-id");
        director.setActive(true);

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(documentItemRepository.countByDossierIdAndActiveTrue(dossierId)).thenReturn(1L);
        when(userRepository.findByEmail("test-user")).thenReturn(creator);
        stubApproverLookup(accountant, chief, director);
        when(repository.save(any(AccountingDossier.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResAccountingDossierDTO result = service.submit(dossierId, null);

        assertEquals(AccountingDossierStatus.SUBMITTED, result.getStatus());
        verify(approvalStepRepository, times(1)).saveAll(anyList());
    }

    @Test
    void testSubmitDossierUsesActiveWorkflowTemplateV2AndCreatesInstance() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);

        Department department = new Department();
        department.setId(20L);
        department.setCompany(company);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setStatus(AccountingDossierStatus.DRAFT);
        dossier.setCategoryMode(AccountingDossierCategoryMode.UNSTRUCTURED);
        dossier.setCompany(company);
        dossier.setDepartment(department);
        dossier.setDossierCode("BCT-2026-000001");

        User creator = new User();
        creator.setId("user-creator-id");
        creator.setEmail("test-user");

        User manager = new User();
        manager.setId("user-manager-id");
        creator.setDirectManager(manager);

        User accountant = new User();
        accountant.setId("user-accountant-id");

        User chief = new User();
        chief.setId("user-chief-id");
        chief.setActive(true);

        User director = new User();
        director.setId("user-director-id");
        director.setActive(true);

        AccountingApprovalWorkflowTemplate template = new AccountingApprovalWorkflowTemplate();
        template.setId(500L);
        template.setCode("WF-PAYMENT");
        template.setName("Luồng thanh toán chuẩn");
        template.setCompanyId(10L);
        template.setPriority(1);
        template.setDefaultTemplate(true);
        template.setStatus(WorkflowTemplateStatus.ACTIVE);
        template.setVersion(3);

        AccountingApprovalWorkflowScope scope = new AccountingApprovalWorkflowScope();
        scope.setTemplate(template);
        scope.setScopeType(WorkflowScopeType.COMPANY);
        scope.setScopeId(10L);
        template.getScopes().add(scope);

        template.getSteps().add(templateStep(template, "MANAGER", 1, "Trưởng bộ phận duyệt",
                ApproverStrategy.REQUESTER_MANAGER, null));
        template.getSteps().add(templateStep(template, "ACCOUNTANT", 2, "Kế toán kiểm tra",
                ApproverStrategy.COMPANY_ROLE, ACCOUNTANT_APPROVAL_PERMISSION));
        template.getSteps().add(templateStep(template, "CHIEF", 3, "Kế toán trưởng duyệt",
                ApproverStrategy.COMPANY_ROLE, CHIEF_ACCOUNTANT_APPROVAL_PERMISSION));
        template.getSteps().add(templateStep(template, "DIRECTOR", 4, "Giám đốc phê duyệt",
                ApproverStrategy.COMPANY_DIRECTOR, null));

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(documentItemRepository.countByDossierIdAndActiveTrue(dossierId)).thenReturn(1L);
        when(userRepository.findByEmail("test-user")).thenReturn(creator);
        when(approvalStepRepository.findByDossierIdAndActiveTrue(dossierId)).thenReturn(Collections.emptyList());
        when(workflowTemplateRepository.findByStatusOrderByPriorityAscIdAsc(WorkflowTemplateStatus.ACTIVE))
                .thenReturn(List.of(template));
        when(approvalInstanceRepository.findFirstByDossierIdAndStatusOrderByIdDesc(dossierId, WorkflowInstanceStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(approvalInstanceRepository.countByDossierId(dossierId)).thenReturn(0L);
        when(approvalInstanceRepository.saveAndFlush(any(AccountingApprovalInstance.class))).thenAnswer(invocation -> {
            AccountingApprovalInstance instance = invocation.getArgument(0);
            instance.setId(900L);
            return instance;
        });
        stubApproverLookup(accountant, chief, director);
        when(repository.save(any(AccountingDossier.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AccountingDossierSubmitRequest req = new AccountingDossierSubmitRequest();
        AccountingDossierSubmitRequest.CustomStep maliciousStep = new AccountingDossierSubmitRequest.CustomStep();
        maliciousStep.setStepOrder(1);
        maliciousStep.setStepName("Không được dùng");
        maliciousStep.setApproverType("CUSTOM_USER");
        maliciousStep.setApproverUserId("malicious-user-id");
        req.setCustomSteps(List.of(maliciousStep));

        ResAccountingDossierDTO result = service.submit(dossierId, req);

        assertEquals(AccountingDossierStatus.SUBMITTED, result.getStatus());
        verify(approvalInstanceRepository).saveAndFlush(argThat(instance ->
                instance.getTemplateId().equals(500L)
                        && instance.getTemplateVersion().equals(3)
                        && instance.getSubmissionNo().equals(1)
                        && instance.getSnapshotJson().contains("\"source\":\"WORKFLOW_TEMPLATE_V2\"")));
        verify(approvalStepRepository).saveAll(argThat(steps -> {
            List<AccountingDossierApprovalStep> list = (List<AccountingDossierApprovalStep>) steps;
            return list.size() == 4
                    && list.stream().allMatch(step -> Long.valueOf(900L).equals(step.getInstanceId()))
                    && list.stream().noneMatch(step -> "malicious-user-id".equals(step.getApproverUserId()))
                    && list.get(0).getStatus() == ApprovalStepStatus.CURRENT
                    && list.get(0).getApproverUserId().equals("user-manager-id")
                    && list.get(3).getApproverType() == ApproverType.DIRECTOR
                    && list.get(3).getApproverUserId().equals("user-director-id");
        }));
    }

    @Test
    void testSubmitDossierTemplateV2SupportsUserSelectableStepByStepKey() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);

        Department department = new Department();
        department.setId(20L);
        department.setCompany(company);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setStatus(AccountingDossierStatus.DRAFT);
        dossier.setCompany(company);
        dossier.setDepartment(department);
        dossier.setDossierCode("BCT-2026-000001");

        User creator = new User();
        creator.setId("user-creator-id");
        creator.setEmail("test-user");

        User chief = new User();
        chief.setId("user-chief-id");
        chief.setActive(true);

        User director = new User();
        director.setId("user-director-id");
        director.setActive(true);

        AccountingApprovalWorkflowTemplate template = new AccountingApprovalWorkflowTemplate();
        template.setId(501L);
        template.setCode("WF-USER-SELECTABLE");
        template.setName("Luồng có bước người lập chọn");
        template.setCompanyId(10L);
        template.setPriority(1);
        template.setStatus(WorkflowTemplateStatus.ACTIVE);
        template.setVersion(1);

        template.getSteps().add(templateStep(template, "MANUAL_REVIEWER", 1, "Người xử lý do người lập chọn",
                ApproverStrategy.USER_SELECTABLE, null));
        template.getSteps().add(templateStep(template, "CHIEF", 2, "Kế toán trưởng duyệt",
                ApproverStrategy.COMPANY_ROLE, CHIEF_ACCOUNTANT_APPROVAL_PERMISSION));
        template.getSteps().add(templateStep(template, "DIRECTOR", 3, "Giám đốc phê duyệt",
                ApproverStrategy.COMPANY_DIRECTOR, null));

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(documentItemRepository.countByDossierIdAndActiveTrue(dossierId)).thenReturn(1L);
        when(userRepository.findByEmail("test-user")).thenReturn(creator);
        when(approvalStepRepository.findByDossierIdAndActiveTrue(dossierId)).thenReturn(Collections.emptyList());
        when(workflowTemplateRepository.findByStatusOrderByPriorityAscIdAsc(WorkflowTemplateStatus.ACTIVE))
                .thenReturn(List.of(template));
        when(approvalInstanceRepository.findFirstByDossierIdAndStatusOrderByIdDesc(dossierId, WorkflowInstanceStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(approvalInstanceRepository.countByDossierId(dossierId)).thenReturn(0L);
        when(approvalInstanceRepository.saveAndFlush(any(AccountingApprovalInstance.class))).thenAnswer(invocation -> {
            AccountingApprovalInstance instance = invocation.getArgument(0);
            instance.setId(901L);
            return instance;
        });
        stubApproverLookup(null, chief, director);
        when(repository.save(any(AccountingDossier.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AccountingDossierSubmitRequest req = new AccountingDossierSubmitRequest();
        AccountingDossierSubmitRequest.CustomStep selected = new AccountingDossierSubmitRequest.CustomStep();
        selected.setStepKey("MANUAL_REVIEWER");
        selected.setStepOrder(1);
        selected.setStepName("Người xử lý do người lập chọn");
        selected.setApproverUserId("selected-reviewer-id");
        req.setCustomSteps(List.of(selected));

        service.submit(dossierId, req);

        verify(approvalStepRepository).saveAll(argThat(steps -> {
            List<AccountingDossierApprovalStep> list = (List<AccountingDossierApprovalStep>) steps;
            return list.get(0).getStepKey().equals("MANUAL_REVIEWER")
                    && list.get(0).getApproverType() == ApproverType.CUSTOM
                    && list.get(0).getApproverUserId().equals("selected-reviewer-id")
                    && list.get(0).getStatus() == ApprovalStepStatus.CURRENT;
        }));
    }

    @Test
    void testApproveDossierTransition() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setStatus(AccountingDossierStatus.IN_REVIEW);
        dossier.setCompany(company);

        User approver = new User();
        approver.setId("user-manager-id");
        approver.setEmail("test-user");

        AccountingDossierApprovalStep step1 = new AccountingDossierApprovalStep();
        step1.setId(101L);
        step1.setStatus(ApprovalStepStatus.CURRENT);
        step1.setApproverUserId("user-manager-id");
        step1.setStepOrder(1);
        step1.setApproverType(ApproverType.DEPARTMENT_MANAGER);

        AccountingDossierApprovalStep step2 = new AccountingDossierApprovalStep();
        step2.setId(102L);
        step2.setStatus(ApprovalStepStatus.PENDING);
        step2.setStepOrder(2);
        step2.setApproverType(ApproverType.ACCOUNTANT);
        step2.setApproverUserId("user-accountant-id"); // Set approverUserId so it doesn't try dynamic resolution

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(userRepository.findByEmail("test-user")).thenReturn(approver);
        when(approvalStepRepository.findByDossierIdAndActiveTrueOrderByStepOrderAsc(dossierId)).thenReturn(List.of(step1, step2));
        when(repository.save(any(AccountingDossier.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.approve(dossierId, null);

        assertEquals(ApprovalStepStatus.APPROVED, step1.getStatus());
        assertEquals(ApprovalStepStatus.CURRENT, step2.getStatus());
        verify(approvalStepRepository, atLeastOnce()).save(any(AccountingDossierApprovalStep.class));
    }

    @Test
    void testAccountantRoleCanApproveRoleBasedAccountantStep() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setStatus(AccountingDossierStatus.IN_REVIEW);
        dossier.setCompany(company);

        User accountant = new User();
        accountant.setId("accountant-1");
        accountant.setEmail("test-user");
        vn.system.app.modules.role.domain.Role role = new vn.system.app.modules.role.domain.Role();
        role.setName("KETOAN");
        accountant.setRole(role);

        AccountingDossierApprovalStep step = new AccountingDossierApprovalStep();
        step.setId(101L);
        step.setStatus(ApprovalStepStatus.CURRENT);
        step.setApproverType(ApproverType.ACCOUNTANT);
        step.setStepOrder(2);

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(userRepository.findByEmail("test-user")).thenReturn(accountant);
        when(approvalStepRepository.findByDossierIdAndActiveTrueOrderByStepOrderAsc(dossierId)).thenReturn(List.of(step));
        when(repository.save(any(AccountingDossier.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResAccountingDossierDTO result = service.approve(dossierId, null);

        assertEquals(ApprovalStepStatus.APPROVED, step.getStatus());
        assertEquals(AccountingDossierStatus.APPROVED, result.getStatus());
    }

    @Test
    void testApproveFinalDossierLocksApproved() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setStatus(AccountingDossierStatus.IN_REVIEW);
        dossier.setCompany(company);

        User approver = new User();
        approver.setId("user-chief-id");
        approver.setEmail("test-user");

        AccountingDossierApprovalStep step1 = new AccountingDossierApprovalStep();
        step1.setId(101L);
        step1.setStatus(ApprovalStepStatus.CURRENT);
        step1.setApproverUserId("user-chief-id");
        step1.setStepOrder(3);
        step1.setApproverType(ApproverType.CHIEF_ACCOUNTANT);

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(userRepository.findByEmail("test-user")).thenReturn(approver);
        when(approvalStepRepository.findByDossierIdAndActiveTrueOrderByStepOrderAsc(dossierId)).thenReturn(List.of(step1));
        when(repository.save(any(AccountingDossier.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResAccountingDossierDTO result = service.approve(dossierId, null);

        assertEquals(ApprovalStepStatus.APPROVED, step1.getStatus());
        assertEquals(AccountingDossierStatus.APPROVED, result.getStatus());
        assertNotNull(result.getApprovedAt());
    }

    @Test
    void testRejectDossierTransitionsToRejected() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setStatus(AccountingDossierStatus.IN_REVIEW);
        dossier.setCompany(company);

        User approver = new User();
        approver.setId("user-manager-id");
        approver.setEmail("test-user");

        AccountingDossierApprovalStep step1 = new AccountingDossierApprovalStep();
        step1.setId(101L);
        step1.setStatus(ApprovalStepStatus.CURRENT);
        step1.setApproverUserId("user-manager-id");
        step1.setApproverType(ApproverType.DEPARTMENT_MANAGER);

        vn.system.app.modules.accountingdossier.domain.request.AccountingDossierActionRequest req = 
            new vn.system.app.modules.accountingdossier.domain.request.AccountingDossierActionRequest();
        req.setNote("Sai chứng từ");

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(userRepository.findByEmail("test-user")).thenReturn(approver);
        when(approvalStepRepository.findByDossierIdAndActiveTrueOrderByStepOrderAsc(dossierId)).thenReturn(List.of(step1));
        when(repository.save(any(AccountingDossier.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResAccountingDossierDTO result = service.reject(dossierId, req);

        assertEquals(ApprovalStepStatus.REJECTED, step1.getStatus());
        assertEquals(AccountingDossierStatus.REJECTED, result.getStatus());
    }

    @Test
    void testTerminateDossierTransitionsToTerminated() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setStatus(AccountingDossierStatus.IN_REVIEW);
        dossier.setCompany(company);

        User chiefAccountant = new User();
        chiefAccountant.setId("user-chief-id");
        chiefAccountant.setEmail("test-user");
        vn.system.app.modules.role.domain.Role role = new vn.system.app.modules.role.domain.Role();
        role.setName("CHIEF_ACCOUNTANT");
        chiefAccountant.setRole(role);

        vn.system.app.modules.accountingdossier.domain.request.AccountingDossierActionRequest req = 
            new vn.system.app.modules.accountingdossier.domain.request.AccountingDossierActionRequest();
        req.setNote("Gian lận");

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(userRepository.findByEmail("test-user")).thenReturn(chiefAccountant);
        when(repository.save(any(AccountingDossier.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResAccountingDossierDTO result = service.terminate(dossierId, req);

        assertEquals(AccountingDossierStatus.TERMINATED, result.getStatus());
        assertNotNull(result.getTerminatedAt());
    }

    @Test
    void testSubmitDossierReturnLimitExceededThrowsException() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setStatus(AccountingDossierStatus.DRAFT);
        dossier.setReturnCount(3);
        dossier.setCompany(company);
        dossier.setDossierCode("BCT-2026-000001");

        User creator = new User();
        creator.setId("user-creator-id");
        creator.setEmail("test-user");
        creator.setDirectManager(null); // No manager configured

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(documentItemRepository.countByDossierIdAndActiveTrue(dossierId)).thenReturn(1L);
        when(userRepository.findByEmail("test-user")).thenReturn(creator);
        when(approvalStepRepository.findByDossierIdAndActiveTrue(dossierId)).thenReturn(Collections.emptyList());

        IdInvalidException exception = assertThrows(IdInvalidException.class, () -> {
            service.submit(dossierId);
        });

        assertTrue(exception.getMessage().contains("Hồ sơ đã bị hoàn trả 3 lần"));
    }

    @Test
    void testFetchPendingMyApprovalUsesApprovalStepSpecification() {
        User currentUser = new User();
        currentUser.setId("user-manager-id");
        currentUser.setEmail("test-user");
        vn.system.app.modules.role.domain.Role role = new vn.system.app.modules.role.domain.Role();
        role.setName("ACCOUNTANT");
        currentUser.setRole(role);

        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(99L);
        dossier.setCompany(company);
        dossier.setDepartment(new Department());
        dossier.getDepartment().setId(20L);
        dossier.setStatus(AccountingDossierStatus.IN_REVIEW);
        dossier.setActive(true);

        when(userRepository.findByEmail("test-user")).thenReturn(currentUser);

        org.springframework.data.domain.Page<AccountingDossier> page = new PageImpl<>(List.of(dossier), PageRequest.of(0, 10), 1);
        when(approvalStepRepository.findCurrentDossiersForApprover(anyString(), anyList(), any(Pageable.class)))
                .thenReturn(page);

        var result = service.fetchPendingMyApproval(PageRequest.of(0, 10));

        assertNotNull(result);
        assertEquals(1, result.getMeta().getTotal());
        assertEquals(1, ((List<?>) result.getResult()).size());
        verify(approvalStepRepository, times(1)).findCurrentDossiersForApprover(anyString(), anyList(), any(Pageable.class));
    }

    @Test
    void testBulkApproveReturnsMixedResults() {
        Long approvedId = 1L;
        Long rejectedId = 2L;

        Company company = new Company();
        company.setId(10L);

        AccountingDossier approvedDossier = new AccountingDossier();
        approvedDossier.setId(approvedId);
        approvedDossier.setStatus(AccountingDossierStatus.IN_REVIEW);
        approvedDossier.setCompany(company);

        AccountingDossier rejectedDossier = new AccountingDossier();
        rejectedDossier.setId(rejectedId);
        rejectedDossier.setStatus(AccountingDossierStatus.IN_REVIEW);
        rejectedDossier.setCompany(company);

        User approver = new User();
        approver.setId("user-approver-id");
        approver.setEmail("test-user");

        AccountingDossierApprovalStep approvedStep = new AccountingDossierApprovalStep();
        approvedStep.setId(11L);
        approvedStep.setStatus(ApprovalStepStatus.CURRENT);
        approvedStep.setApproverUserId("user-approver-id");
        approvedStep.setApproverType(ApproverType.ACCOUNTANT);
        approvedStep.setStepOrder(1);

        AccountingDossierApprovalStep rejectedStep = new AccountingDossierApprovalStep();
        rejectedStep.setId(12L);
        rejectedStep.setStatus(ApprovalStepStatus.CURRENT);
        rejectedStep.setApproverUserId("different-user-id");
        rejectedStep.setApproverType(ApproverType.ACCOUNTANT);
        rejectedStep.setStepOrder(1);

        when(repository.findById(approvedId)).thenReturn(Optional.of(approvedDossier));
        when(repository.findById(rejectedId)).thenReturn(Optional.of(rejectedDossier));
        when(userRepository.findByEmail("test-user")).thenReturn(approver);
        when(approvalStepRepository.findByDossierIdAndActiveTrueOrderByStepOrderAsc(approvedId))
                .thenReturn(List.of(approvedStep));
        when(approvalStepRepository.findByDossierIdAndActiveTrueOrderByStepOrderAsc(rejectedId))
                .thenReturn(List.of(rejectedStep));
        when(repository.save(any(AccountingDossier.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResAccountingDossierBulkActionDTO result = service.bulkApprove(List.of(approvedId, rejectedId), null);

        assertNotNull(result.getBulkActionId());
        assertEquals(2, result.getTotal());
        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getFailureCount());
        assertEquals(2, result.getItems().size());
        assertTrue(result.getItems().get(0).isSuccess());
        assertFalse(result.getItems().get(1).isSuccess());
        assertTrue(result.getItems().get(1).getError().contains("không có quyền"));
    }

    @Test
    void testBulkCheckDocumentsUpdatesMatchedDocuments() {
        Long dossierId = 1L;

        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setCompany(company);
        dossier.setStatus(AccountingDossierStatus.IN_REVIEW);
        dossier.setActive(true);

        User accountant = new User();
        accountant.setId("user-accountant-id");
        accountant.setEmail("test-user");

        AccountingDossierApprovalStep step = new AccountingDossierApprovalStep();
        step.setStatus(ApprovalStepStatus.CURRENT);
        step.setApproverType(ApproverType.ACCOUNTANT);
        step.setApproverUserId("user-accountant-id");

        AccountingDossierDocument doc = new AccountingDossierDocument();
        doc.setId(55L);
        doc.setDocumentName("Hóa đơn");
        doc.setCheckStatus("VALID");

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(userRepository.findByEmail("test-user")).thenReturn(accountant);
        when(approvalStepRepository.findByDossierIdAndActiveTrueOrderByStepOrderAsc(dossierId))
                .thenReturn(List.of(step));
        when(documentItemRepository.findByIdInAndDossierIdAndActiveTrue(List.of(55L), dossierId))
                .thenReturn(List.of(doc));
        ResAccountingDossierBulkActionDTO result = service.bulkCheckDocuments(
                dossierId,
                List.of(55L),
                "NEED_SUPPLEMENT",
                "Thiếu file đính kèm");

        assertEquals("NEED_SUPPLEMENT", doc.getCheckStatus());
        assertEquals("Thiếu file đính kèm", doc.getCheckNote());
        assertNotNull(result.getBulkActionId());
        assertEquals(1, result.getTotal());
        assertEquals(1, result.getSuccessCount());
        assertEquals(0, result.getFailureCount());
        verify(documentItemRepository, never()).save(any(AccountingDossierDocument.class));
    }

    @Test
    void testBulkCheckTemplateDossierCannotMarkNotRequired() {
        Long dossierId = 1L;

        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setCompany(company);
        dossier.setStatus(AccountingDossierStatus.IN_REVIEW);
        dossier.setCategoryMode(AccountingDossierCategoryMode.TEMPLATE);
        dossier.setActive(true);

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));

        PermissionException exception = assertThrows(PermissionException.class, () ->
                service.bulkCheckDocuments(dossierId, List.of(55L), "NOT_REQUIRED", null));

        assertTrue(exception.getMessage().contains("Không yêu cầu"));
        verify(documentItemRepository, never()).save(any());
    }

    @Test
    void testReviewDocumentRequiresCurrentAccountantStep() {
        Long dossierId = 1L;
        Long docId = 55L;

        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setCompany(company);
        dossier.setStatus(AccountingDossierStatus.IN_REVIEW);
        dossier.setActive(true);

        User manager = new User();
        manager.setId("manager-id");
        manager.setEmail("test-user");

        AccountingDossierApprovalStep step = new AccountingDossierApprovalStep();
        step.setStatus(ApprovalStepStatus.CURRENT);
        step.setApproverType(ApproverType.DEPARTMENT_MANAGER);
        step.setApproverUserId("manager-id");

        vn.system.app.modules.accountingdossier.domain.request.AccountingDossierDocumentCheckRequest req =
                new vn.system.app.modules.accountingdossier.domain.request.AccountingDossierDocumentCheckRequest();
        req.setCheckStatus("VALID");

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(userRepository.findByEmail("test-user")).thenReturn(manager);
        when(approvalStepRepository.findByDossierIdAndActiveTrueOrderByStepOrderAsc(dossierId))
                .thenReturn(List.of(step));

        PermissionException exception = assertThrows(PermissionException.class, () ->
                service.reviewDocument(dossierId, docId, req));

        assertTrue(exception.getMessage().contains("kế toán kiểm tra"));
    }

    @Test
    void testHandleReturnResponseRequiresCurrentApprover() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setCompany(company);
        dossier.setStatus(AccountingDossierStatus.RETURN_REQUESTED);
        dossier.setCreatorId("creator-id");
        dossier.setReturnCount(0);

        User approver = new User();
        approver.setId("accountant-id");
        approver.setEmail("test-user");

        AccountingDossierApprovalStep step = new AccountingDossierApprovalStep();
        step.setStatus(ApprovalStepStatus.CURRENT);
        step.setApproverType(ApproverType.ACCOUNTANT);
        step.setApproverUserId("accountant-id");

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(userRepository.findByEmail("test-user")).thenReturn(approver);
        when(approvalStepRepository.findByDossierIdAndActiveTrueOrderByStepOrderAsc(dossierId))
                .thenReturn(List.of(step));
        when(repository.save(any(AccountingDossier.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResAccountingDossierDTO result = service.handleReturnResponse(dossierId, "ACCEPT", null);

        assertEquals(AccountingDossierStatus.RETURNED, result.getStatus());
        assertEquals(ApprovalStepStatus.RETURNED, step.getStatus());
        assertEquals(1, dossier.getReturnCount());
    }

    @Test
    void testRejectTemplateSyncTurnsOffSyncRequest() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setCompany(company);
        dossier.setCategoryMode(AccountingDossierCategoryMode.UNSTRUCTURED);
        dossier.setSyncCategoryRequested(true);

        User chief = new User();
        chief.setId("user-chief-id");
        chief.setEmail("test-user");
        vn.system.app.modules.role.domain.Role role = new vn.system.app.modules.role.domain.Role();
        role.setName("CHIEF_ACCOUNTANT");
        chief.setRole(role);

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(userRepository.findByEmail("test-user")).thenReturn(chief);
        when(repository.save(any(AccountingDossier.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResAccountingDossierDTO result = service.rejectTemplateSync(dossierId, null);

        assertFalse(dossier.isSyncCategoryRequested());
        assertFalse(result.isSyncCategoryRequested());
        verify(repository, times(1)).save(dossier);
    }

    @Test
    void archive_Success() {
        Long id = 1L;
        vn.system.app.modules.accountingdossier.domain.request.AccountingDossierActionRequest req = new vn.system.app.modules.accountingdossier.domain.request.AccountingDossierActionRequest();
        req.setNote("Archive this");

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(id);
        dossier.setStatus(AccountingDossierStatus.APPROVED);
        dossier.setActive(true);
        vn.system.app.modules.company.domain.Company company = new vn.system.app.modules.company.domain.Company();
        company.setId(100L);
        dossier.setCompany(company);

        User chief = new User();
        chief.setId("user-chief-id");
        chief.setEmail("test-user");
        vn.system.app.modules.role.domain.Role role = new vn.system.app.modules.role.domain.Role();
        role.setName("CHIEF_ACCOUNTANT");
        chief.setRole(role);

        when(repository.findById(id)).thenReturn(Optional.of(dossier));
        when(repository.save(any(AccountingDossier.class))).thenReturn(dossier);
        when(userRepository.findByEmail("test-user")).thenReturn(chief);

        ResAccountingDossierDTO res = service.archive(id, req);

        assertNotNull(res);
        assertEquals(vn.system.app.modules.accountingdossier.domain.enums.AccountingDossierStorageStatus.ARCHIVED, dossier.getStorageStatus());
        verify(repository, times(1)).saveAndFlush(dossier);
        verify(auditLogRepository, times(1)).save(any());
    }

    @Test
    void refreshExpiredRetentionStatuses_Success() {
        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(1L);
        dossier.setStorageStatus(vn.system.app.modules.accountingdossier.domain.enums.AccountingDossierStorageStatus.IN_RETENTION);

        when(repository.findByActiveTrueAndStorageStatusAndRetentionUntilBefore(
                eq(vn.system.app.modules.accountingdossier.domain.enums.AccountingDossierStorageStatus.IN_RETENTION),
                any(java.time.Instant.class)
        )).thenReturn(List.of(dossier));

        int count = service.refreshExpiredRetentionStatuses();

        assertEquals(1, count);
        assertEquals(vn.system.app.modules.accountingdossier.domain.enums.AccountingDossierStorageStatus.EXPIRED, dossier.getStorageStatus());
        verify(repository, times(1)).saveAll(any());
    }

    @Test
    void getStorageSummary_Success() {
        when(repository.countByActiveTrue()).thenReturn(10L);
        when(repository.countByActiveTrueAndStorageStatus(vn.system.app.modules.accountingdossier.domain.enums.AccountingDossierStorageStatus.IN_RETENTION)).thenReturn(5L);
        when(repository.countByActiveTrueAndStorageStatus(vn.system.app.modules.accountingdossier.domain.enums.AccountingDossierStorageStatus.EXPIRED)).thenReturn(3L);
        when(repository.countByActiveTrueAndStorageStatus(vn.system.app.modules.accountingdossier.domain.enums.AccountingDossierStorageStatus.ARCHIVED)).thenReturn(2L);
        when(repository.countActiveGroupByStatus(null)).thenReturn(Collections.emptyList());
        when(repository.countActiveGroupByStorageStatus(null)).thenReturn(Collections.emptyList());
        
        vn.system.app.modules.accountingdossier.domain.response.ResAccountingDossierStorageSummaryDTO res = service.getStorageSummary(null);
        
        assertNotNull(res);
        assertEquals(10L, res.getTotal());
        assertEquals(5L, res.getInRetention());
        assertEquals(3L, res.getExpired());
        assertEquals(2L, res.getArchived());
    }

    @Test
    void reportByStatus_Success() {
        Object[] row1 = new Object[]{AccountingDossierStatus.SUBMITTED, 5L};
        when(repository.countActiveGroupByStatus(null)).thenReturn(Collections.singletonList(row1));

        List<vn.system.app.modules.accountingdossier.domain.response.ResAccountingDossierReportRowDTO> res = service.reportByStatus(null);
        
        assertNotNull(res);
        assertEquals(1, res.size());
        assertEquals("SUBMITTED", res.get(0).getKey());
        assertEquals(5L, res.get(0).getCount());
    }

    @Test
    void testSubmitDossierWithReturnCountGe3ForcedManager() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);
        company.setCode("CMP");
        company.setName("Test Company");

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setStatus(AccountingDossierStatus.DRAFT);
        dossier.setCompany(company);
        dossier.setReturnCount(3);

        User creator = new User();
        creator.setId("creator-id");
        creator.setEmail("test-user");

        User manager = new User();
        manager.setId("manager-id");
        creator.setDirectManager(manager);

        User director = new User();
        director.setId("director-id");
        director.setActive(true);

        User chief = new User();
        chief.setId("chief-id");
        chief.setActive(true);

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(documentItemRepository.countByDossierIdAndActiveTrue(dossierId)).thenReturn(1L);
        when(userRepository.findByEmail("test-user")).thenReturn(creator);
        stubApproverLookup(null, chief, director);
        when(repository.save(any(AccountingDossier.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResAccountingDossierDTO result = service.submit(dossierId, null);

        assertNotNull(result);
        assertEquals(AccountingDossierStatus.SUBMITTED, dossier.getStatus());
        verify(approvalStepRepository, times(1)).saveAll(argThat(steps -> {
            List<AccountingDossierApprovalStep> list = (List<AccountingDossierApprovalStep>) steps;
            return list.stream().anyMatch(s -> s.getApproverType() == ApproverType.DEPARTMENT_MANAGER && "manager-id".equals(s.getApproverUserId()) && s.getStatus() != ApprovalStepStatus.SKIPPED);
        }));
    }

    @Test
    void testSubmitDossierWithReturnCountGe3ForcedManagerMissingManager() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);
        company.setCode("CMP");
        company.setName("Test Company");

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setStatus(AccountingDossierStatus.DRAFT);
        dossier.setCompany(company);
        dossier.setReturnCount(3);

        User creator = new User();
        creator.setId("creator-id");
        creator.setEmail("test-user");
        creator.setDirectManager(null);

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(documentItemRepository.countByDossierIdAndActiveTrue(dossierId)).thenReturn(1L);
        when(userRepository.findByEmail("test-user")).thenReturn(creator);

        assertThrows(IdInvalidException.class, () -> service.submit(dossierId, null));
    }

    @Test
    void testAddDocumentWhileSubmittedRequiresReturnToBeAcceptedFirst() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setStatus(AccountingDossierStatus.SUBMITTED);
        dossier.setCompany(company);
        dossier.setReturnCount(1);
        dossier.setActive(true);
        dossier.setCreatorId("creator-id");

        User creator = new User();
        creator.setId("creator-id");
        creator.setEmail("test-user");

        AccountingDossierDocumentRequest req = new AccountingDossierDocumentRequest();
        req.setAccountingCategoryId(100L);
        req.setDocumentName("Hóa đơn VAT");
        req.setDocumentType("PDF");

        AccountingDocumentCategory category = new AccountingDocumentCategory();
        category.setId(100L);

        AccountingDossierDocument item = new AccountingDossierDocument();
        item.setId(5L);
        item.setDossier(dossier);
        item.setDocumentName("Hóa đơn VAT");

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(userRepository.findByEmail("test-user")).thenReturn(creator);
        assertThrows(PermissionException.class, () -> service.addDocument(dossierId, req));
        assertEquals(AccountingDossierStatus.SUBMITTED, dossier.getStatus());
        assertEquals(1, dossier.getReturnCount());
        verify(documentItemRepository, never()).save(any());
    }

    @Test
    void testNonCreatorCannotDeleteDocumentFromDraftDossier() {
        // Clear superAdmin scope so the creator restriction check is enforced
        vn.system.app.common.util.UserScopeContext.clear();

        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);
        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setCompany(company);
        dossier.setStatus(AccountingDossierStatus.DRAFT);
        dossier.setActive(true);
        dossier.setCreatorId("another-user");

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));

        assertThrows(PermissionException.class, () -> service.deleteDocument(dossierId, 99L));
        verify(documentItemRepository, never()).findById(anyLong());
    }

    @Test
    void testTerminateDossierByNonChiefThrowsException() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setStatus(AccountingDossierStatus.IN_REVIEW);
        dossier.setCompany(company);

        User accountant = new User();
        accountant.setId("user-accountant-id");
        accountant.setEmail("test-user");
        vn.system.app.modules.role.domain.Role role = new vn.system.app.modules.role.domain.Role();
        role.setName("KETOAN");
        accountant.setRole(role);

        vn.system.app.modules.accountingdossier.domain.request.AccountingDossierActionRequest req =
                new vn.system.app.modules.accountingdossier.domain.request.AccountingDossierActionRequest();
        req.setNote("Gian lận");

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(userRepository.findByEmail(anyString())).thenReturn(accountant);

        PermissionException exception = assertThrows(PermissionException.class, () ->
                service.terminate(dossierId, req));

        assertTrue(exception.getMessage().contains("Super Admin mới được quyền chấm dứt"));
    }

    @Test
    void testRequestReturnByNonCreatorThrowsException() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setCompany(company);
        dossier.setCreatorId("creator-id");
        dossier.setStatus(AccountingDossierStatus.IN_REVIEW);

        User anotherUser = new User();
        anotherUser.setId("another-user-id");
        anotherUser.setEmail("test-user");

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(userRepository.findByEmail("test-user")).thenReturn(anotherUser);

        PermissionException exception = assertThrows(PermissionException.class,
                () -> service.requestReturn(dossierId, null));

        assertTrue(exception.getMessage().contains("Chỉ người lập hồ sơ"));
        verify(repository, never()).save(dossier);
    }

    @Test
    void testTerminateDossierWithUnknownUserThrowsException() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setStatus(AccountingDossierStatus.IN_REVIEW);
        dossier.setCompany(company);

        vn.system.app.modules.accountingdossier.domain.request.AccountingDossierActionRequest req =
                new vn.system.app.modules.accountingdossier.domain.request.AccountingDossierActionRequest();
        req.setNote("Dừng xử lý");

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(userRepository.findByEmail("test-user")).thenReturn(null);

        PermissionException exception = assertThrows(PermissionException.class,
                () -> service.terminate(dossierId, req));

        assertTrue(exception.getMessage().contains("Không xác định được quyền chấm dứt"));
        verify(repository, never()).save(dossier);
    }

    @Test
    void testApproveDossierByNonCurrentApproverThrowsException() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setStatus(AccountingDossierStatus.IN_REVIEW);
        dossier.setCompany(company);

        User randomUser = new User();
        randomUser.setId("user-random-id");
        randomUser.setEmail("test-user");
        vn.system.app.modules.role.domain.Role role = new vn.system.app.modules.role.domain.Role();
        role.setName("EMPLOYEE");
        randomUser.setRole(role);

        AccountingDossierApprovalStep step1 = new AccountingDossierApprovalStep();
        step1.setId(101L);
        step1.setStatus(ApprovalStepStatus.CURRENT);
        step1.setApproverUserId("user-manager-id");
        step1.setApproverType(ApproverType.DEPARTMENT_MANAGER);
        step1.setStepOrder(1);

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(userRepository.findByEmail("test-user")).thenReturn(randomUser);
        when(approvalStepRepository.findByDossierIdAndActiveTrueOrderByStepOrderAsc(dossierId)).thenReturn(List.of(step1));

        PermissionException exception = assertThrows(PermissionException.class, () ->
                service.approve(dossierId, null));

        assertTrue(exception.getMessage().contains("Bạn không có quyền xử lý"));
    }

    @Test
    void testArchiveDossierNotApprovedThrowsException() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setCompany(company);
        dossier.setStatus(AccountingDossierStatus.IN_REVIEW);

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));

        PermissionException exception = assertThrows(PermissionException.class, () ->
                service.archive(dossierId, null));

        assertTrue(exception.getMessage().contains("Chỉ bộ chứng từ đã duyệt"));
    }

    @Test
    void testAddDocumentDuplicateInvoiceWarning() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setStatus(AccountingDossierStatus.DRAFT);
        dossier.setCompany(company);
        dossier.setActive(true);
        dossier.setCreatorId("creator-id");

        User creator = new User();
        creator.setId("creator-id");
        creator.setEmail("test-user");

        AccountingDossierDocumentRequest req = new AccountingDossierDocumentRequest();
        req.setAccountingCategoryId(100L);
        req.setDocumentName("Hóa đơn");
        req.setInvoiceNumber("INV-001");
        req.setPartnerName("Partner A");

        AccountingDocumentCategory category = new AccountingDocumentCategory();
        category.setId(100L);

        AccountingDossier duplicateDossier = new AccountingDossier();
        duplicateDossier.setId(2L);
        duplicateDossier.setDossierCode("BCT-2");

        AccountingDossierDocument duplicateDoc = new AccountingDossierDocument();
        duplicateDoc.setId(99L);
        duplicateDoc.setInvoiceNumber("INV-001");
        duplicateDoc.setPartnerName("Partner A");
        duplicateDoc.setDossier(duplicateDossier);

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(userRepository.findByEmail("test-user")).thenReturn(creator);
        when(accountingCategoryRepository.findById(100L)).thenReturn(Optional.of(category));
        when(documentItemRepository.findDuplicateInvoices(eq("INV-001"), eq("Partner A"), eq(dossierId), any()))
                .thenReturn(List.of(duplicateDoc));

        assertThrows(DuplicateInvoiceWarningException.class, () -> service.addDocument(dossierId, req));
    }

    @Test
    void testAddDocumentDuplicateInvoiceEvenIfConfirmedIsBlocked() {
        Long dossierId = 1L;
        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(dossierId);
        dossier.setStatus(AccountingDossierStatus.DRAFT);
        dossier.setCompany(company);
        dossier.setActive(true);
        dossier.setCreatorId("creator-id");

        User creator = new User();
        creator.setId("creator-id");
        creator.setEmail("test-user");

        AccountingDossierDocumentRequest req = new AccountingDossierDocumentRequest();
        req.setAccountingCategoryId(100L);
        req.setDocumentName("Hóa đơn");
        req.setInvoiceNumber("INV-001");
        req.setPartnerName("Partner A");
        req.setConfirmDuplicate(true);
        req.setDuplicateReason("Lý do hợp lệ");

        AccountingDocumentCategory category = new AccountingDocumentCategory();
        category.setId(100L);

        AccountingDossier duplicateDossier = new AccountingDossier();
        duplicateDossier.setId(2L);
        duplicateDossier.setDossierCode("BCT-2");

        AccountingDossierDocument duplicateDoc = new AccountingDossierDocument();
        duplicateDoc.setId(99L);
        duplicateDoc.setInvoiceNumber("INV-001");
        duplicateDoc.setPartnerName("Partner A");
        duplicateDoc.setDossier(duplicateDossier);

        when(repository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(userRepository.findByEmail("test-user")).thenReturn(creator);
        when(accountingCategoryRepository.findById(100L)).thenReturn(Optional.of(category));
        when(documentItemRepository.findDuplicateInvoices(eq("INV-001"), eq("Partner A"), eq(dossierId), any()))
                .thenReturn(List.of(duplicateDoc));

        assertThrows(DuplicateInvoiceWarningException.class, () -> service.addDocument(dossierId, req));
    }

    @Test
    void testGetByQrTokenSuccess() {
        String token = "qr-token-123";
        Company company = new Company();
        company.setId(10L);

        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(1L);
        dossier.setQrToken(token);
        dossier.setActive(true);
        dossier.setCompany(company);

        when(repository.findByQrToken(token)).thenReturn(Optional.of(dossier));

        ResAccountingDossierDTO res = service.getByQrToken(token);

        assertNotNull(res);
        assertEquals(token, res.getQrToken());
    }

    @Test
    void testGetByQrTokenNotFound() {
        String token = "invalid-token";
        when(repository.findByQrToken(token)).thenReturn(Optional.empty());

        assertThrows(IdInvalidException.class, () -> service.getByQrToken(token));
    }

    @Test
    void testGetByQrTokenInactive() {
        String token = "qr-token-inactive";
        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(1L);
        dossier.setQrToken(token);
        dossier.setActive(false);

        when(repository.findByQrToken(token)).thenReturn(Optional.of(dossier));

        assertThrows(IdInvalidException.class, () -> service.getByQrToken(token));
    }

    @Test
    void testApproverTypeFromStringMapping() {
        assertEquals(ApproverType.ACCOUNTANT, ApproverType.fromString("ACOUNTANT"));
        assertEquals(ApproverType.CHIEF_ACCOUNTANT, ApproverType.fromString("CHIEF_ACOUNTANT"));
        assertEquals(ApproverType.CUSTOM, ApproverType.fromString("CUSTOM_USER"));
        assertEquals(ApproverType.CUSTOM, ApproverType.fromString("CUSTOM"));
        assertEquals(ApproverType.DEPARTMENT_MANAGER, ApproverType.fromString("DEPARTMENT_MANAGER"));
        assertEquals(ApproverType.DIRECTOR, ApproverType.fromString("DIRECTOR"));
        assertNull(ApproverType.fromString(null));

        assertThrows(IllegalArgumentException.class, () -> ApproverType.fromString("SOME_UNKNOWN_VALUE"));
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

    private AccountingApprovalWorkflowStep templateStep(
            AccountingApprovalWorkflowTemplate template,
            String key,
            int order,
            String name,
            ApproverStrategy strategy,
            String approverRefId) {
        AccountingApprovalWorkflowStep step = new AccountingApprovalWorkflowStep();
        step.setTemplate(template);
        step.setStepKey(key);
        step.setStepOrder(order);
        step.setStepName(name);
        step.setApproverStrategy(strategy);
        step.setApproverRefId(approverRefId);
        step.setRequired(true);
        return step;
    }

    @Test
    void reassignDirector_DossierStatusGuard_ThrowsException() {
        AccountingDossier dossier = new AccountingDossier();
        dossier.setId(1L);
        dossier.setStatus(vn.system.app.modules.accountingdossier.domain.enums.AccountingDossierStatus.APPROVED);

        vn.system.app.modules.user.domain.User actor = new vn.system.app.modules.user.domain.User();
        vn.system.app.modules.role.domain.Role role = new vn.system.app.modules.role.domain.Role();
        role.setName("SUPER_ADMIN");
        actor.setRole(role);

        when(userRepository.findByEmail(anyString())).thenReturn(actor);
        when(repository.findById(1L)).thenReturn(Optional.of(dossier));

        assertThrows(IdInvalidException.class, () -> {
            service.reassignDirector(1L, "new-user-id", "Testing status guard");
        });
    }

    @Test
    void refreshExpiredRetentionStatusesByUser_NoPermission_ThrowsException() {
        vn.system.app.modules.user.domain.User actor = new vn.system.app.modules.user.domain.User();
        vn.system.app.modules.role.domain.Role role = new vn.system.app.modules.role.domain.Role();
        role.setName("USER");
        role.setPermissions(Collections.emptyList());
        actor.setRole(role);

        when(userRepository.findByEmail(anyString())).thenReturn(actor);

        assertThrows(PermissionException.class, () -> {
            service.refreshExpiredRetentionStatusesByUser();
        });
    }

    @Test
    void refreshExpiredRetentionStatusesByUser_Success() {
        vn.system.app.modules.user.domain.User actor = new vn.system.app.modules.user.domain.User();
        vn.system.app.modules.role.domain.Role role = new vn.system.app.modules.role.domain.Role();
        role.setName("SUPER_ADMIN");
        actor.setRole(role);

        when(userRepository.findByEmail(anyString())).thenReturn(actor);
        when(repository.findByActiveTrueAndStorageStatusAndRetentionUntilBefore(any(), any())).thenReturn(List.of());

        int count = service.refreshExpiredRetentionStatusesByUser();
        assertEquals(0, count);
    }
}
