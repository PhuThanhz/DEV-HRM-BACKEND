package vn.system.app.modules.confidentialprocedure.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.SecurityUtil;
import vn.system.app.common.util.ScopeSpec;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.common.util.error.PermissionException;
import vn.system.app.common.util.UserScopeContext;
import vn.system.app.modules.user.domain.User;
import vn.system.app.modules.user.repository.UserRepository;
import vn.system.app.modules.department.domain.Department;
import vn.system.app.modules.department.repository.DepartmentRepository;
import vn.system.app.modules.procedure.qr.service.ProcedureQrService;
import vn.system.app.modules.section.domain.Section;
import vn.system.app.modules.section.repository.SectionRepository;
import vn.system.app.modules.confidentialprocedure.domain.*;
import vn.system.app.modules.confidentialprocedure.domain.request.ConfidentialProcedureRequest;
import vn.system.app.modules.confidentialprocedure.domain.request.ShareRequest;
import vn.system.app.modules.confidentialprocedure.domain.response.ResConfidentialProcedureDTO;
import vn.system.app.modules.confidentialprocedure.domain.response.ResConfidentialProcedureHistoryDTO;
import vn.system.app.modules.confidentialprocedure.domain.response.ResAccessDTO;
import vn.system.app.modules.confidentialprocedure.domain.response.ResShareLogDTO;
import org.springframework.context.ApplicationEventPublisher;
import vn.system.app.modules.notification.event.AppNotificationEvent;
import vn.system.app.modules.confidentialprocedure.repository.*;

@Service
public class ConfidentialProcedureService {
    private final ProcedureQrService qrService;
    private final ConfidentialProcedureRepository repository;
    private final ConfidentialProcedureHistoryRepository historyRepository;
    private final ConfidentialProcedureAccessRepository accessRepository;
    private final DepartmentRepository departmentRepository;
    private final SectionRepository sectionRepository;
    private final UserRepository userRepository;

    private final ConfidentialAccessService accessService;
    private final ConfidentialShareLogService shareLogService;
    private final ApplicationEventPublisher eventPublisher;

    private static final ObjectMapper mapper = new ObjectMapper();

    public ConfidentialProcedureService(
            ConfidentialProcedureRepository repository,
            ConfidentialProcedureHistoryRepository historyRepository,
            ConfidentialProcedureAccessRepository accessRepository,
            DepartmentRepository departmentRepository,
            SectionRepository sectionRepository,
            UserRepository userRepository,
            ConfidentialAccessService accessService,
            ConfidentialShareLogService shareLogService,
            ProcedureQrService qrService,
            ApplicationEventPublisher eventPublisher) {

        this.repository = repository;
        this.historyRepository = historyRepository;
        this.accessRepository = accessRepository;
        this.departmentRepository = departmentRepository;
        this.sectionRepository = sectionRepository;
        this.userRepository = userRepository;
        this.accessService = accessService;
        this.shareLogService = shareLogService;
        this.qrService = qrService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Kiểm tra phạm vi truy cập của người dùng
     */
    private void validateScope(Long companyId) {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope == null)
            throw new PermissionException("Không xác định được phạm vi truy cập");

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

    // =====================================================
    // CREATE
    // =====================================================
    @Transactional
    public ResConfidentialProcedureDTO handleCreate(ConfidentialProcedureRequest req) {

        if (req.getUserIds() == null || req.getUserIds().isEmpty()) {
            throw new IdInvalidException("Vui lòng chọn ít nhất 1 người được phép xem quy trình bảo mật");
        }

        String code = req.getProcedureCode().trim().toUpperCase();

        if (req.getDepartmentId() != null &&
                repository.existsByDepartment_IdAndProcedureCode(req.getDepartmentId(), code)) {
            throw new IdInvalidException("Mã quy trình đã tồn tại trong phòng ban này");
        }

        Department department = departmentRepository.findById(req.getDepartmentId())
                .orElseThrow(() -> new IdInvalidException("Phòng ban không tồn tại"));

        validateScope(department.getCompany() != null ? department.getCompany().getId() : null);

        Section section = resolveSection(req.getSectionId());
        if (section != null && department != null && !section.getDepartment().getId().equals(department.getId())) {
            throw new IdInvalidException("Bộ phận không thuộc phòng ban đã chọn");
        }

        ConfidentialProcedure entity = new ConfidentialProcedure();
        entity.setProcedureCode(code);
        entity.setProcedureName(req.getProcedureName());
        entity.setStatus(req.getStatus());
        entity.setPlanYear(req.getPlanYear());
        entity.setFileUrls(toJsonArray(req.getFileUrls()));
        entity.setNote(req.getNote());
        entity.setActive(true);
        entity.setVersion(1);
        entity.setDepartment(department);
        entity.setSection(section);
        entity.setIssuedDate(req.getIssuedDate());

        ConfidentialProcedure saved = repository.save(entity);

        // Sinh QR sau khi có ID
        saved.setQrToken(qrService.buildQrToken());
        saved.setQrCode(qrService.buildQrBase64(saved.getQrToken()));
        repository.save(saved);

        // logShare = true: lần đầu tạo mới → ghi log SHARE thật sự
        accessService.saveAccessList(saved, req, true);



        return convertToDTO(saved);
    }

    // =====================================================
    // UPDATE
    // =====================================================
    @Transactional
    public ResConfidentialProcedureDTO handleUpdate(Long id, ConfidentialProcedureRequest req) {

        if (req.getUserIds() == null || req.getUserIds().isEmpty()) {
            throw new IdInvalidException("Vui lòng chọn ít nhất 1 người được phép xem quy trình bảo mật");
        }

        ConfidentialProcedure current = fetchById(id);
        
        Long currentCompanyId = (current.getDepartment() != null && current.getDepartment().getCompany() != null)
                ? current.getDepartment().getCompany().getId() : null;
        validateScope(currentCompanyId);

        String code = req.getProcedureCode().trim().toUpperCase();

        checkDuplicateCode(req.getDepartmentId(), code, id);

        Department department = departmentRepository.findById(req.getDepartmentId())
                .orElseThrow(() -> new IdInvalidException("Phòng ban không tồn tại"));

        Long targetCompanyId = (department.getCompany() != null) ? department.getCompany().getId() : null;
        validateScope(targetCompanyId);

        Section section = resolveSection(req.getSectionId());
        if (section != null && department != null && !section.getDepartment().getId().equals(department.getId())) {
            throw new IdInvalidException("Bộ phận không thuộc phòng ban đã chọn");
        }

        current.setProcedureCode(code);
        current.setProcedureName(req.getProcedureName());
        current.setStatus(req.getStatus());
        current.setPlanYear(req.getPlanYear());
        current.setFileUrls(toJsonArray(req.getFileUrls()));
        current.setNote(req.getNote());
        current.setDepartment(department);
        current.setSection(section);
        current.setIssuedDate(req.getIssuedDate());
        ConfidentialProcedure saved = repository.save(current);

        if (req.getUserIds() != null) {
            accessService.syncAccessList(saved, req.getUserIds());
        }

        return convertToDTO(saved);

    }

    // =====================================================
    // REVISE
    // =====================================================
    @Transactional
    public ResConfidentialProcedureDTO handleRevise(Long id, ConfidentialProcedureRequest req) {

        ConfidentialProcedure current = fetchById(id);
        if (!current.isActive()) {
            throw new IdInvalidException("Không thể revise quy trình đã bị vô hiệu hóa");
        }

        Long currentCompanyId = (current.getDepartment() != null && current.getDepartment().getCompany() != null)
                ? current.getDepartment().getCompany().getId() : null;
        validateScope(currentCompanyId);

        String code = req.getProcedureCode().trim().toUpperCase();

        checkDuplicateCode(req.getDepartmentId(), code, id);

        Department department = departmentRepository.findById(req.getDepartmentId())
                .orElseThrow(() -> new IdInvalidException("Phòng ban không tồn tại"));

        Long targetCompanyId = (department.getCompany() != null) ? department.getCompany().getId() : null;
        validateScope(targetCompanyId);

        Section section = resolveSection(req.getSectionId());
        if (section != null && department != null && !section.getDepartment().getId().equals(department.getId())) {
            throw new IdInvalidException("Bộ phận không thuộc phòng ban đã chọn");
        }

        saveHistory(current, "REVISE");

        current.setProcedureCode(code);
        current.setProcedureName(req.getProcedureName());
        current.setStatus(req.getStatus());
        current.setPlanYear(req.getPlanYear());
        current.setFileUrls(toJsonArray(req.getFileUrls()));
        current.setNote(req.getNote());
        current.setDepartment(department);
        current.setSection(section);
        current.setVersion(current.getVersion() + 1);
        current.setIssuedDate(req.getIssuedDate());

        ConfidentialProcedure saved = repository.save(current);

        if (req.getUserIds() != null) {
            accessService.syncAccessList(saved, req.getUserIds());
        }

        return convertToDTO(saved);

    }

    // =====================================================
    // TOGGLE ACTIVE
    // =====================================================
    @Transactional
    public void handleToggleActive(Long id) {
        ConfidentialProcedure current = fetchById(id);
        Long companyId = (current.getDepartment() != null && current.getDepartment().getCompany() != null)
                ? current.getDepartment().getCompany().getId() : null;
        validateScope(companyId);
        current.setActive(!current.isActive());
        repository.save(current);
    }

    // =====================================================
    // DELETE
    // =====================================================
    @Transactional
    public void handleDelete(Long id) {
        ConfidentialProcedure current = fetchById(id);
        Long companyId = (current.getDepartment() != null && current.getDepartment().getCompany() != null)
                ? current.getDepartment().getCompany().getId() : null;
        validateScope(companyId);
        repository.deleteById(id);
    }

    // =====================================================
    // SHARE — delegate sang AccessService
    // =====================================================
    public void handleShare(Long procedureId, ShareRequest req) {
        accessService.handleShare(procedureId, req);
    }

    // =====================================================
    // ACCESS LIST — delegate sang AccessService
    // =====================================================
    public List<ResAccessDTO> handleGetAccessList(Long procedureId) {
        return accessService.handleGetAccessList(procedureId);
    }

    // =====================================================
    // REVOKE — delegate sang AccessService
    // =====================================================
    public void handleRevoke(Long procedureId, String userId) {
        accessService.handleRevoke(procedureId, userId);
    }

    // =====================================================
    // CHECK ACCESS — delegate sang AccessService
    // =====================================================
    public void checkAccess(Long procedureId) {
        accessService.checkAccess(procedureId);
    }

    // =====================================================
    // HAS ACCESS — delegate sang AccessService
    // =====================================================
    public boolean hasAccess(Long procedureId, String userId, List<Long> userRoleIds) {
        return accessService.hasAccess(procedureId, userId, userRoleIds);
    }

    // =====================================================
    // SHARE LOG — delegate sang ShareLogService
    // =====================================================
    public List<ResShareLogDTO> handleGetSentLog() {
        return shareLogService.handleGetSentLog();
    }

    public List<ResShareLogDTO> handleGetReceivedLog() {
        return shareLogService.handleGetReceivedLog();
    }

    public ResultPaginationDTO handleGetAllLog(Pageable pageable) {
        return shareLogService.handleGetAllLog(pageable);
    }

    // =====================================================
    // FETCH ONE
    // =====================================================
    public ConfidentialProcedure fetchById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IdInvalidException("Quy trình không tồn tại"));
    }

    public void validateReadAccess(ConfidentialProcedure procedure) {
        String username = SecurityUtil.getCurrentUserLogin()
                .orElseThrow(() -> new IdInvalidException("Không xác định được người dùng"));

        User user = userRepository.findByEmail(username);
        if (user == null) {
            throw new IdInvalidException("Người dùng không tồn tại");
        }

        if (user.getRole() != null) {
            String roleName = user.getRole().getName();
            if ("SUPER_ADMIN".equals(roleName) || "ADMIN_SUB_1".equals(roleName)) {
                return;
            }
        }

        if (procedure.getCreatedBy() == null || !procedure.getCreatedBy().equals(username)) {
            throw new PermissionException("Bạn không có quyền truy cập quy trình mật này");
        }
    }

    // =====================================================
    // FETCH ALL
    // =====================================================
    public ResultPaginationDTO fetchAll(
            Specification<ConfidentialProcedure> spec, Pageable pageable) {

        Specification<ConfidentialProcedure> userSpec = filterByCurrentUser();
        Specification<ConfidentialProcedure> finalSpec = spec == null ? userSpec : spec.and(userSpec);

        Page<ConfidentialProcedure> page = repository.findAll(finalSpec, pageable);

        ResultPaginationDTO rs = new ResultPaginationDTO();
        ResultPaginationDTO.Meta meta = new ResultPaginationDTO.Meta();
        meta.setPage(pageable.getPageNumber() + 1);
        meta.setPageSize(pageable.getPageSize());
        meta.setPages(page.getTotalPages());
        meta.setTotal(page.getTotalElements());
        rs.setMeta(meta);
        rs.setResult(page.getContent().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList()));

        return rs;
    }

    // =====================================================
    // FETCH BY COMPANY / DEPARTMENT / SECTION
    // =====================================================
    public List<ResConfidentialProcedureDTO> fetchByCompany(Long companyId) {
        return repository.findByDepartment_Company_Id(companyId)
                .stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    public List<ResConfidentialProcedureDTO> fetchByDepartment(Long departmentId) {
        Specification<ConfidentialProcedure> spec = (root, query, cb) -> cb.equal(
                root.get("department").get("id"), departmentId);
        spec = spec.and(ScopeSpec.byCompanyScope("department.company.id"));
        spec = spec.and(filterByCurrentUser());
        return repository.findAll(spec)
                .stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    public List<ResConfidentialProcedureDTO> fetchBySection(Long sectionId) {
        Specification<ConfidentialProcedure> spec = (root, query, cb) -> cb.equal(
                root.get("section").get("id"), sectionId);
        spec = spec.and(ScopeSpec.byCompanyScope("department.company.id"));
        spec = spec.and(filterByCurrentUser());
        return repository.findAll(spec)
                .stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    // =====================================================
    // FETCH HISTORY
    // =====================================================
    public List<ResConfidentialProcedureHistoryDTO> fetchHistory(Long procedureId) {
        fetchById(procedureId);
        return historyRepository.findByProcedure_IdOrderByVersionDesc(procedureId)
                .stream().map(this::convertHistoryToDTO).collect(Collectors.toList());
    }

    // =====================================================
    // CONVERT TO DTO
    // =====================================================
    public ResConfidentialProcedureDTO convertToDTO(ConfidentialProcedure e) {
        ResConfidentialProcedureDTO dto = new ResConfidentialProcedureDTO();
        dto.setId(e.getId());

        if (e.getDepartment() != null) {
            dto.setDepartmentId(e.getDepartment().getId());
            dto.setDepartmentName(e.getDepartment().getName());
            if (e.getDepartment().getCompany() != null) {
                dto.setCompanyId(e.getDepartment().getCompany().getId()); // ← THÊM
                dto.setCompanyCode(e.getDepartment().getCompany().getCode());
                dto.setCompanyName(e.getDepartment().getCompany().getName());
            }
        }
        if (e.getSection() != null) {
            dto.setSectionId(e.getSection().getId());
            dto.setSectionName(e.getSection().getName());
        }

        dto.setProcedureCode(e.getProcedureCode());
        dto.setProcedureName(e.getProcedureName());
        dto.setStatus(e.getStatus());
        dto.setPlanYear(e.getPlanYear());
        dto.setFileUrls(fromJsonArray(e.getFileUrls()));
        dto.setNote(e.getNote());
        dto.setActive(e.isActive());
        dto.setVersion(e.getVersion());
        dto.setIssuedDate(e.getIssuedDate());
        // MỚI
        if (e.getQrToken() != null) {
            dto.setQrToken(e.getQrToken());
            dto.setQrCode(qrService.buildQrBase64(e.getQrToken()));
        }

        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        dto.setCreatedBy(e.getCreatedBy());
        // ← THÊM
        if (e.getCreatedBy() != null) {
            User creator = userRepository.findByEmail(e.getCreatedBy());
            if (creator != null) {
                dto.setCreatedByName(creator.getName());
            }
        }
        dto.setUpdatedBy(e.getUpdatedBy());

        List<ConfidentialProcedureAccess> accessList = e.getAccessList();

        List<String> assignedByList = accessList.stream()
                .filter(a -> "USER".equals(a.getAccessType()))
                .map(a -> {
                    if (a.getAssignedBy() == null)
                        return null;
                    return userRepository.findById(a.getAssignedBy())
                            .map(User::getName)
                            .orElse(null);
                })
                .filter(x -> x != null)
                .distinct()
                .collect(Collectors.toList());

        dto.setAssignedByList(assignedByList);
        dto.setUserIds(accessList.stream()
                .filter(a -> "USER".equals(a.getAccessType()))
                .map(ConfidentialProcedureAccess::getUserId)
                .collect(Collectors.toList()));
        dto.setRoleIds(accessList.stream()
                .filter(a -> "ROLE".equals(a.getAccessType()))
                .map(ConfidentialProcedureAccess::getRoleId)
                .collect(Collectors.toList()));

        return dto;
    }

    // =====================================================
    // CONVERT HISTORY TO DTO
    // =====================================================
    public ResConfidentialProcedureHistoryDTO convertHistoryToDTO(ConfidentialProcedureHistory h) {
        ResConfidentialProcedureHistoryDTO dto = new ResConfidentialProcedureHistoryDTO();
        dto.setId(h.getId());
        dto.setProcedureId(h.getProcedure().getId());
        dto.setVersion(h.getVersion());
        dto.setProcedureCode(h.getProcedureCode());
        dto.setProcedureName(h.getProcedureName());
        dto.setStatus(h.getStatus());
        dto.setPlanYear(h.getPlanYear());
        dto.setIssuedDate(h.getIssuedDate());
        dto.setFileUrls(fromJsonArray(h.getFileUrls()));
        dto.setNote(h.getNote());
        dto.setDepartmentName(h.getDepartmentName());
        dto.setSectionName(h.getSectionName());
        dto.setAction(h.getAction());
        dto.setChangedAt(h.getChangedAt());
        dto.setChangedBy(h.getChangedBy());
        return dto;
    }

    // =====================================================
    // FILTER BY CURRENT USER (Specification)
    // =====================================================
    private Specification<ConfidentialProcedure> filterByCurrentUser() {
        return (root, query, cb) -> {
            String username = SecurityUtil.getCurrentUserLogin()
                    .orElseThrow(() -> new IdInvalidException("Không xác định được người dùng"));

            User user = userRepository.findByEmail(username);
            if (user == null)
                throw new IdInvalidException("Người dùng không tồn tại");

            if (user.getRole() != null) {
                String roleName = user.getRole().getName();
                if ("SUPER_ADMIN".equals(roleName) || "ADMIN_SUB_1".equals(roleName)) {
                    return cb.conjunction();
                }
            }

            return cb.equal(root.get("createdBy"), username);
        };
    }

    // =====================================================
    // SAVE HISTORY (private)
    // =====================================================
    private void saveHistory(ConfidentialProcedure e, String action) {
        ConfidentialProcedureHistory history = new ConfidentialProcedureHistory();
        history.setProcedure(e);
        history.setVersion(e.getVersion());
        history.setProcedureCode(e.getProcedureCode());
        history.setProcedureName(e.getProcedureName());
        history.setStatus(e.getStatus());
        history.setPlanYear(e.getPlanYear());
        history.setFileUrls(e.getFileUrls());
        history.setNote(e.getNote());
        history.setIssuedDate(e.getIssuedDate());
        history.setDepartmentName(e.getDepartment() != null ? e.getDepartment().getName() : null);
        history.setSectionName(e.getSection() != null ? e.getSection().getName() : null);
        history.setAction(action);
        history.setChangedAt(Instant.now());
        history.setChangedBy(SecurityUtil.getCurrentUserLogin().orElse(""));
        historyRepository.save(history);
    }

    // =====================================================
    // PRIVATE HELPERS
    // =====================================================
    private void checkDuplicateCode(Long departmentId, String code, Long excludeId) {
        if (departmentId != null &&
                repository.existsByDepartment_IdAndProcedureCodeAndIdNot(departmentId, code, excludeId)) {
            throw new IdInvalidException("Mã quy trình đã tồn tại trong phòng ban này");
        }
    }

    private Section resolveSection(Long sectionId) {
        if (sectionId == null)
            return null;
        return sectionRepository.findById(sectionId)
                .orElseThrow(() -> new IdInvalidException("Bộ phận không tồn tại"));
    }

    private String toJsonArray(List<String> urls) {
        try {
            return mapper.writeValueAsString(urls != null ? urls : new ArrayList<>());
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> fromJsonArray(String json) {
        if (json == null || json.isBlank())
            return new ArrayList<>();
        try {
            if (!json.trim().startsWith("["))
                return List.of(json);
            return mapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
