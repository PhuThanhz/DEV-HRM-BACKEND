package vn.system.app.modules.accountingdossier.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import vn.system.app.common.util.UserScopeContext;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.accountingdossier.domain.AccountingApprovalDelegation;
import vn.system.app.modules.accountingdossier.domain.enums.DelegationStatus;
import vn.system.app.modules.accountingdossier.domain.request.AccountingApprovalDelegationRequest;
import vn.system.app.modules.accountingdossier.domain.response.ResAccountingApprovalDelegationDTO;
import vn.system.app.modules.accountingdossier.repository.AccountingApprovalDelegationRepository;
import vn.system.app.modules.user.repository.UserRepository;
import vn.system.app.modules.userposition.repository.UserPositionRepository;

@ExtendWith(MockitoExtension.class)
class AccountingApprovalDelegationServiceTest {

    @Mock
    private AccountingApprovalDelegationRepository delegationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserPositionRepository userPositionRepository;

    @Mock
    private AccountingDossierNotificationService notificationService;

    @InjectMocks
    private AccountingApprovalDelegationService service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        UserScopeContext.set(new UserScopeContext.UserScope(
                "user-1", Collections.emptySet(), Collections.emptySet(), false, false, false, false));
        lenient().when(userPositionRepository.findActiveCompanyIdsByUserId(anyString())).thenReturn(List.of(77L));
        lenient().when(notificationService.formatInstant(any(Instant.class))).thenReturn("01/01/2026 00:00");
        lenient().doNothing().when(notificationService).notifyUsers(anyCollection(), anyString(), anyString(), any());
        lenient().when(userRepository.findAllById(anyCollection())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        UserScopeContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void testCreateDelegationToSelfThrowsError() {
        AccountingApprovalDelegationRequest req = new AccountingApprovalDelegationRequest();
        req.setDelegatorUserId("user-1");
        req.setDelegateUserId("user-1");
        req.setValidFrom(Instant.now());
        req.setValidTo(Instant.now().plus(1, ChronoUnit.DAYS));
        req.setReason("Công tác");

        IdInvalidException exception = assertThrows(IdInvalidException.class, () -> service.create(req));
        assertTrue(exception.getMessage().contains("Không được tự ủy quyền cho chính mình"));
    }

    @Test
    void testCreateDelegatorNotExistsThrowsError() {
        AccountingApprovalDelegationRequest req = new AccountingApprovalDelegationRequest();
        req.setDelegatorUserId("user-1");
        req.setDelegateUserId("user-2");
        req.setValidFrom(Instant.now());
        req.setValidTo(Instant.now().plus(1, ChronoUnit.DAYS));
        req.setReason("Công tác");

        when(userRepository.existsById("user-1")).thenReturn(false);

        IdInvalidException exception = assertThrows(IdInvalidException.class, () -> service.create(req));
        assertTrue(exception.getMessage().contains("Người ủy quyền không tồn tại"));
    }

    @Test
    void testCreateDelegateNotExistsThrowsError() {
        AccountingApprovalDelegationRequest req = new AccountingApprovalDelegationRequest();
        req.setDelegatorUserId("user-1");
        req.setDelegateUserId("user-2");
        req.setValidFrom(Instant.now());
        req.setValidTo(Instant.now().plus(1, ChronoUnit.DAYS));
        req.setReason("Công tác");

        when(userRepository.existsById("user-1")).thenReturn(true);
        when(userRepository.existsById("user-2")).thenReturn(false);

        IdInvalidException exception = assertThrows(IdInvalidException.class, () -> service.create(req));
        assertTrue(exception.getMessage().contains("Người nhận ủy quyền không tồn tại"));
    }

    @Test
    void testCreateInvalidDateRangeThrowsError() {
        AccountingApprovalDelegationRequest req = new AccountingApprovalDelegationRequest();
        req.setDelegatorUserId("user-1");
        req.setDelegateUserId("user-2");
        req.setValidFrom(Instant.now().plus(2, ChronoUnit.DAYS));
        req.setValidTo(Instant.now().plus(1, ChronoUnit.DAYS));
        req.setReason("Công tác");

        when(userRepository.existsById("user-1")).thenReturn(true);
        when(userRepository.existsById("user-2")).thenReturn(true);

        IdInvalidException exception = assertThrows(IdInvalidException.class, () -> service.create(req));
        assertTrue(exception.getMessage().contains("Thời gian kết thúc ủy quyền phải sau thời gian bắt đầu"));
    }

    @Test
    void testCreateCircularLoopThrowsError() {
        AccountingApprovalDelegationRequest req = new AccountingApprovalDelegationRequest();
        req.setDelegatorUserId("user-1");
        req.setDelegateUserId("user-2");
        req.setValidFrom(Instant.now());
        req.setValidTo(Instant.now().plus(1, ChronoUnit.DAYS));
        req.setReason("Công tác");

        when(userRepository.existsById("user-1")).thenReturn(true);
        when(userRepository.existsById("user-2")).thenReturn(true);
        
        when(delegationRepository.existsByDelegatorUserIdAndDelegateUserIdAndStatusInAndValidFromLessThanEqualAndValidToGreaterThanEqual(
                eq("user-2"), eq("user-1"), anyList(), any(), any()))
                .thenReturn(true);

        IdInvalidException exception = assertThrows(IdInvalidException.class, () -> service.create(req));
        assertTrue(exception.getMessage().contains("Không được tạo vòng lặp ủy quyền hai chiều"));
    }

    @Test
    void testCreateValidDelegationSucceeds() {
        AccountingApprovalDelegationRequest req = new AccountingApprovalDelegationRequest();
        req.setDelegatorUserId("user-1");
        req.setDelegateUserId("user-2");
        req.setValidFrom(Instant.now());
        req.setValidTo(Instant.now().plus(1, ChronoUnit.DAYS));
        req.setCompanyId(77L);
        req.setReason("Công tác");

        when(userRepository.existsById("user-1")).thenReturn(true);
        when(userRepository.existsById("user-2")).thenReturn(true);
        AccountingApprovalDelegation saved = new AccountingApprovalDelegation();
        saved.setId(100L);
        saved.setDelegatorUserId("user-1");
        saved.setDelegateUserId("user-2");
        saved.setCompanyId(77L);
        saved.setValidFrom(req.getValidFrom());
        saved.setValidTo(req.getValidTo());
        saved.setReason("Công tác");
        saved.setStatus(DelegationStatus.DRAFT);

        when(delegationRepository.save(any(AccountingApprovalDelegation.class))).thenReturn(saved);

        ResAccountingApprovalDelegationDTO dto = service.create(req);
        assertNotNull(dto);
        assertEquals(100L, dto.getId());
        assertEquals("user-1", dto.getDelegatorUserId());
        assertEquals("user-2", dto.getDelegateUserId());
        assertEquals(DelegationStatus.DRAFT, dto.getStatus());
    }

    @Test
    void testCreateDelegationRejectsDelegateOutsideCompany() {
        AccountingApprovalDelegationRequest req = new AccountingApprovalDelegationRequest();
        req.setDelegatorUserId("user-1");
        req.setDelegateUserId("user-2");
        req.setCompanyId(77L);
        req.setValidFrom(Instant.now());
        req.setValidTo(Instant.now().plus(1, ChronoUnit.DAYS));
        req.setReason("Công tác");
        when(userRepository.existsById("user-1")).thenReturn(true);
        when(userRepository.existsById("user-2")).thenReturn(true);
        when(userPositionRepository.findActiveCompanyIdsByUserId("user-2")).thenReturn(List.of(88L));

        IdInvalidException exception = assertThrows(IdInvalidException.class, () -> service.create(req));
        assertTrue(exception.getMessage().contains("Người nhận ủy quyền không có vị trí"));
    }

    @Test
    void testActivateDelegation() {
        AccountingApprovalDelegation delegation = new AccountingApprovalDelegation();
        delegation.setId(100L);
        delegation.setDelegatorUserId("user-1");
        delegation.setDelegateUserId("user-2");
        delegation.setValidFrom(Instant.now());
        delegation.setValidTo(Instant.now().plus(1, ChronoUnit.DAYS));
        delegation.setStatus(DelegationStatus.DRAFT);

        when(delegationRepository.findById(100L)).thenReturn(Optional.of(delegation));
        when(delegationRepository.save(any(AccountingApprovalDelegation.class))).thenAnswer(i -> i.getArgument(0));

        ResAccountingApprovalDelegationDTO dto = service.activate(100L);
        assertNotNull(dto);
        assertEquals(DelegationStatus.ACTIVE, dto.getStatus());
    }

    @Test
    void testRevokeDelegation() {
        AccountingApprovalDelegation delegation = new AccountingApprovalDelegation();
        delegation.setId(100L);
        delegation.setDelegatorUserId("user-1");
        delegation.setDelegateUserId("user-2");
        delegation.setValidFrom(Instant.now());
        delegation.setValidTo(Instant.now().plus(1, ChronoUnit.DAYS));
        delegation.setStatus(DelegationStatus.ACTIVE);

        when(delegationRepository.findById(100L)).thenReturn(Optional.of(delegation));
        when(delegationRepository.save(any(AccountingApprovalDelegation.class))).thenAnswer(i -> i.getArgument(0));

        ResAccountingApprovalDelegationDTO dto = service.revoke(100L);
        assertNotNull(dto);
        assertEquals(DelegationStatus.REVOKED, dto.getStatus());
    }

    @Test
    void testExpireOverdueDelegations() {
        AccountingApprovalDelegation d1 = new AccountingApprovalDelegation();
        d1.setId(1L);
        d1.setDelegatorUserId("user-1");
        d1.setDelegateUserId("user-2");
        d1.setStatus(DelegationStatus.ACTIVE);
        AccountingApprovalDelegation d2 = new AccountingApprovalDelegation();
        d2.setId(2L);
        d2.setDelegatorUserId("user-3");
        d2.setDelegateUserId("user-4");
        d2.setStatus(DelegationStatus.ACTIVE);

        List<AccountingApprovalDelegation> overdue = new ArrayList<>();
        overdue.add(d1);
        overdue.add(d2);

        when(delegationRepository.findByStatusAndValidToBefore(eq(DelegationStatus.ACTIVE), any(Instant.class)))
                .thenReturn(overdue);

        int count = service.expireOverdueDelegations();
        assertEquals(2, count);
        assertEquals(DelegationStatus.EXPIRED, d1.getStatus());
        assertEquals(DelegationStatus.EXPIRED, d2.getStatus());
        verify(delegationRepository, times(1)).saveAll(overdue);
    }

    @Test
    void testListForSuperAdminReturnsAll() {
        UserScopeContext.set(new UserScopeContext.UserScope(
                "admin-1",
                Collections.emptySet(),
                Collections.emptySet(),
                true, // isSuperAdmin
                true, // isAdminLevel
                false,
                false
        ));

        try {
            AccountingApprovalDelegation d1 = new AccountingApprovalDelegation();
            d1.setId(1L);
            d1.setDelegatorUserId("user-1");
            d1.setDelegateUserId("user-2");
            AccountingApprovalDelegation d2 = new AccountingApprovalDelegation();
            d2.setId(2L);
            d2.setDelegatorUserId("user-3");
            d2.setDelegateUserId("user-4");

            when(delegationRepository.findVisible(anyBoolean(), anyBoolean(), anyString(), anyCollection(), any(), any(), any(), any(), any()))
                    .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(d1, d2)));
            vn.system.app.common.response.ResultPaginationDTO page = service.list(org.springframework.data.domain.PageRequest.of(0, 10), null, null, null);
            assertEquals(2, ((List<?>) page.getResult()).size());
        } finally {
            UserScopeContext.clear();
        }
    }

    @Test
    void testListForRegularUserFiltersOnlyOwnDelegations() {
        UserScopeContext.set(new UserScopeContext.UserScope(
                "user-1",
                Collections.emptySet(),
                Collections.emptySet(),
                false, // isSuperAdmin
                false, // isAdminLevel
                false,
                false
        ));

        try {
            AccountingApprovalDelegation d1 = new AccountingApprovalDelegation();
            d1.setId(1L);
            d1.setDelegatorUserId("user-1"); // Involved as delegator
            d1.setDelegateUserId("user-2");
            AccountingApprovalDelegation d2 = new AccountingApprovalDelegation();
            d2.setId(2L);
            d2.setDelegatorUserId("user-3");
            d2.setDelegateUserId("user-1"); // Involved as delegate
            AccountingApprovalDelegation d3 = new AccountingApprovalDelegation();
            d3.setId(3L);
            d3.setDelegatorUserId("user-4"); // Unrelated delegation
            d3.setDelegateUserId("user-5");

            when(delegationRepository.findVisible(anyBoolean(), anyBoolean(), anyString(), anyCollection(), any(), any(), any(), any(), any()))
                    .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(d1, d2)));
            vn.system.app.common.response.ResultPaginationDTO page = service.list(org.springframework.data.domain.PageRequest.of(0, 10), null, null, null);
            List<ResAccountingApprovalDelegationDTO> list = (List<ResAccountingApprovalDelegationDTO>) page.getResult();
            assertEquals(2, list.size());
        } finally {
            UserScopeContext.clear();
        }
    }

    @Test
    void testCreateValidDelegationWithoutCompanyIdResolvesFromUserPositions() {
        AccountingApprovalDelegationRequest req = new AccountingApprovalDelegationRequest();
        req.setDelegatorUserId("user-1");
        req.setDelegateUserId("user-2");
        req.setValidFrom(Instant.now());
        req.setValidTo(Instant.now().plus(1, ChronoUnit.DAYS));
        req.setCompanyId(null); // No companyId provided
        req.setReason("Công tác");

        when(userRepository.existsById("user-1")).thenReturn(true);
        when(userRepository.existsById("user-2")).thenReturn(true);
        // Stub findActiveCompanyIdsByUserId to return company 77L
        when(userPositionRepository.findActiveCompanyIdsByUserId("user-1")).thenReturn(List.of(77L));

        AccountingApprovalDelegation saved = new AccountingApprovalDelegation();
        saved.setId(101L);
        saved.setDelegatorUserId("user-1");
        saved.setDelegateUserId("user-2");
        saved.setCompanyId(77L); // Expect resolved company ID
        saved.setValidFrom(req.getValidFrom());
        saved.setValidTo(req.getValidTo());
        saved.setStatus(DelegationStatus.DRAFT);

        when(delegationRepository.save(any(AccountingApprovalDelegation.class))).thenReturn(saved);

        ResAccountingApprovalDelegationDTO dto = service.create(req);
        assertNotNull(dto);
        assertEquals(101L, dto.getId());
        assertEquals(77L, dto.getCompanyId());
    }
}
