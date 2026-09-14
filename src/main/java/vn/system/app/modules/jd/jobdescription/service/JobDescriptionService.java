package vn.system.app.modules.jd.jobdescription.service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.SecurityUtil;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.common.util.error.PermissionException;

import vn.system.app.modules.jd.jobdescription.domain.JobDescription;
import vn.system.app.modules.jd.jobdescription.domain.request.ReqCreateJobDescriptionDTO;
import vn.system.app.modules.jd.jobdescription.domain.response.ResJobDescriptionDTO;
import vn.system.app.modules.jd.jobdescription.repository.JobDescriptionRepository;

import vn.system.app.modules.company.service.CompanyService;
import vn.system.app.modules.department.service.DepartmentService;

import vn.system.app.modules.companyjobtitle.service.CompanyJobTitleService;
import vn.system.app.modules.departmentjobtitle.service.DepartmentJobTitleService;
import vn.system.app.modules.sectionjobtitle.service.SectionJobTitleService;
import vn.system.app.modules.user.repository.UserRepository;
import vn.system.app.modules.jd.jobdescriptionrequirement.service.JobDescriptionRequirementService;
import vn.system.app.modules.jd.jobdescriptiontask.service.JobDescriptionTaskService;
import vn.system.app.modules.jd.jobdescriptionposition.service.JobDescriptionPositionService;
import vn.system.app.common.util.ScopeSpec;
import vn.system.app.common.util.UserScopeContext;
import vn.system.app.modules.jd.jdflow.domain.JdFlow;
import vn.system.app.modules.jd.jdflow.repository.JdFlowRepository;
import vn.system.app.modules.jd.jdflow.service.JdFlowLogService;
import vn.system.app.modules.jd.jdflow.service.JdPermissionService;
import vn.system.app.modules.user.domain.User;

@Service
@RequiredArgsConstructor
public class JobDescriptionService {

    private final JobDescriptionRepository repository;
    private final JdFlowLogService jdFlowLogService;
    private final UserRepository userRepository;
    private final JdPermissionService permissionService;
    private final JdFlowRepository jdFlowRepository;

    private final CompanyService companyService;
    private final DepartmentService departmentService;

    private final CompanyJobTitleService companyJobTitleService;
    private final DepartmentJobTitleService departmentJobTitleService;
    private final SectionJobTitleService sectionJobTitleService;

    private final JobDescriptionRequirementService requirementService;
    private final JobDescriptionTaskService taskService;
    private final JobDescriptionPositionService positionService;
    private final vn.system.app.modules.userposition.repository.UserPositionRepository userPositionRepository;
    private final vn.system.app.modules.jd.jobdescriptiontask.repository.JobDescriptionTaskItemRepository jobDescriptionTaskItemRepository;

    @Transactional(readOnly = true)
    public List<vn.system.app.modules.jd.jobdescriptiontask.domain.response.ResJobDescriptionTaskDTO> fetchJdTasksByUser(String userId) {
        List<vn.system.app.modules.userposition.domain.UserPosition> positions = userPositionRepository.findByUser_IdAndActiveTrue(userId);
        List<JobDescription> jds = new java.util.ArrayList<>();

        if (!positions.isEmpty()) {
            List<Long> compTitleIds = positions.stream()
                    .map(vn.system.app.modules.userposition.domain.UserPosition::getCompanyJobTitle)
                    .filter(java.util.Objects::nonNull)
                    .map(vn.system.app.modules.companyjobtitle.domain.CompanyJobTitle::getId)
                    .collect(Collectors.toList());

            List<Long> deptTitleIds = positions.stream()
                    .map(vn.system.app.modules.userposition.domain.UserPosition::getDepartmentJobTitle)
                    .filter(java.util.Objects::nonNull)
                    .map(vn.system.app.modules.departmentjobtitle.domain.DepartmentJobTitle::getId)
                    .collect(Collectors.toList());

            List<Long> secTitleIds = positions.stream()
                    .map(vn.system.app.modules.userposition.domain.UserPosition::getSectionJobTitle)
                    .filter(java.util.Objects::nonNull)
                    .map(vn.system.app.modules.sectionjobtitle.domain.SectionJobTitle::getId)
                    .collect(Collectors.toList());

            if (compTitleIds.isEmpty()) compTitleIds = List.of(-1L);
            if (deptTitleIds.isEmpty()) deptTitleIds = List.of(-1L);
            if (secTitleIds.isEmpty()) secTitleIds = List.of(-1L);

            jds = repository.findPublishedJdsByJobTitles(compTitleIds, deptTitleIds, secTitleIds);
        }

        if (jds.isEmpty()) {
            List<Long> companyIds = positions.stream()
                    .map(this::resolveCompanyIdFromPosition)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());

            jds = companyIds.isEmpty()
                    ? List.of()
                    : repository.findByStatusAndCompany_IdIn("PUBLISHED", companyIds);
        }

        List<Long> taskIds = jds.stream()
                .filter(jd -> jd.getTasks() != null)
                .flatMap(jd -> jd.getTasks().stream())
                .map(vn.system.app.modules.jd.jobdescriptiontask.domain.JobDescriptionTask::getId)
                .collect(Collectors.toList());

        // Batch fetch items để tránh N+1 query trong vòng lặp bên dưới
        java.util.Map<Long, List<vn.system.app.modules.jd.jobdescriptiontask.domain.response.ResJobDescriptionTaskItemDTO>> itemsByTaskId =
                new java.util.HashMap<>();
        if (!taskIds.isEmpty()) {
            for (vn.system.app.modules.jd.jobdescriptiontask.domain.JobDescriptionTaskItem item :
                    jobDescriptionTaskItemRepository.findByJobDescriptionTask_IdInOrderByOrderNo(taskIds)) {
                vn.system.app.modules.jd.jobdescriptiontask.domain.response.ResJobDescriptionTaskItemDTO itemDto =
                        new vn.system.app.modules.jd.jobdescriptiontask.domain.response.ResJobDescriptionTaskItemDTO();
                itemDto.setId(item.getId());
                itemDto.setOrderNo(item.getOrderNo());
                itemDto.setContent(item.getContent());
                itemsByTaskId.computeIfAbsent(item.getJobDescriptionTask().getId(), k -> new java.util.ArrayList<>())
                        .add(itemDto);
            }
        }

        List<vn.system.app.modules.jd.jobdescriptiontask.domain.response.ResJobDescriptionTaskDTO> result = new java.util.ArrayList<>();

        for (JobDescription jd : jds) {
            if (jd.getTasks() != null) {
                for (vn.system.app.modules.jd.jobdescriptiontask.domain.JobDescriptionTask task : jd.getTasks()) {
                    vn.system.app.modules.jd.jobdescriptiontask.domain.response.ResJobDescriptionTaskDTO dto = new vn.system.app.modules.jd.jobdescriptiontask.domain.response.ResJobDescriptionTaskDTO();
                    dto.setId(task.getId());
                    dto.setJobDescriptionId(jd.getId());
                    dto.setJobDescriptionCode(jd.getCode());
                    dto.setOrderNo(task.getOrderNo());
                    dto.setTitle(task.getTitle());
                    dto.setItems(itemsByTaskId.getOrDefault(task.getId(), java.util.Collections.emptyList()));

                    result.add(dto);
                }
            }
        }

        return result;
    }

    private Long resolveCompanyIdFromPosition(vn.system.app.modules.userposition.domain.UserPosition pos) {
        return switch (pos.getSource().toUpperCase()) {
            case "COMPANY" -> pos.getCompanyJobTitle() != null
                    ? pos.getCompanyJobTitle().getCompany().getId() : null;
            case "DEPARTMENT" -> pos.getDepartmentJobTitle() != null
                    ? pos.getDepartmentJobTitle().getDepartment().getCompany().getId() : null;
            case "SECTION" -> pos.getSectionJobTitle() != null
                    ? pos.getSectionJobTitle().getSection().getDepartment().getCompany().getId() : null;
            default -> null;
        };
    }

    /*
     * ==========================================
     * CREATE
     * ==========================================
     */
    @Transactional
    public JobDescription handleCreate(ReqCreateJobDescriptionDTO req) {

        if (req.getCode() != null && repository.existsByCode(req.getCode())) {
            throw new IdInvalidException("Mã mô tả công việc " + req.getCode() + " đã tồn tại");
        }

        JobDescription jd = new JobDescription();

        if (req.getCompanyId() != null) {
            jd.setCompany(companyService.fetchEntityById(req.getCompanyId()));
        }

        if (req.getDepartmentId() != null) {
            jd.setDepartment(departmentService.fetchEntityById(req.getDepartmentId()));
        }

        jd.setCode(req.getCode());
        jd.setReportTo(req.getReportTo());
        jd.setBelongsTo(req.getBelongsTo());
        jd.setCollaborateWith(req.getCollaborateWith());

        if (Boolean.TRUE.equals(req.getPublishDirectly())) {
            String email = SecurityUtil.getCurrentUserLogin().orElse("");
            User currentUser = userRepository.findByEmail(email);
            if (currentUser == null) {
                throw new RuntimeException("Không xác định được người tạo JD");
            }
            UserScopeContext.UserScope scope = UserScopeContext.get();
            boolean isSuperAdmin = scope != null && scope.isSuperAdmin();
            if (!isSuperAdmin && !permissionService.hasIssuePermission(currentUser)) {
                throw new RuntimeException("Bạn không có quyền ban hành trực tiếp JD này");
            }
            jd.setStatus("PUBLISHED");
            jd.setVersion(1);
        } else {
            jd.setStatus("DRAFT");
        }

        jd.setEffectiveDate(req.getEffectiveDate());

        if (req.getDepartmentJobTitleId() != null) {
            jd.setDepartmentJobTitle(
                    departmentJobTitleService.fetchEntityById(req.getDepartmentJobTitleId()));
        }

        if (req.getCompanyJobTitleId() != null) {
            jd.setCompanyJobTitle(
                    companyJobTitleService.fetchEntityById(req.getCompanyJobTitleId()));
        }

        if (req.getSectionJobTitleId() != null) {
            jd.setSectionJobTitle(
                    sectionJobTitleService.fetchEntityById(req.getSectionJobTitleId()));
        }

        validateScope(jd);

        jd = repository.save(jd);

        requirementService.createFromDTO(jd, req.getRequirements());
        taskService.createFromDTO(jd, req.getTasks());
        positionService.createFromDTO(jd, req.getPositions());

        if (Boolean.TRUE.equals(req.getPublishDirectly())) {
            String email = SecurityUtil.getCurrentUserLogin().orElse("");
            User currentUser = userRepository.findByEmail(email);
            
            JdFlow flow = new JdFlow();
            flow.setJobDescription(jd);
            flow.setFromUser(currentUser);
            flow.setCurrentUser(null);
            flow.setStatus("ISSUED");
            jdFlowRepository.save(flow);

            jdFlowLogService.saveLog(jd, currentUser, null, "ISSUE", "Ban hành trực tiếp");
        }

        return jd;
    }

    /*
     * ==========================================
     * UPDATE
     * ==========================================
     */
    @Transactional
    public JobDescription handleUpdate(Long id, ReqCreateJobDescriptionDTO req) {

        JobDescription current = fetchById(id);

        if ("PUBLISHED".equals(current.getStatus())) {
            throw new RuntimeException("JD đã ban hành, không thể chỉnh sửa");
        }

        if ("IN_REVIEW".equals(current.getStatus())) {
            throw new RuntimeException("JD đang duyệt, không thể chỉnh sửa");
        }

        assertOwnerOrAdmin(current);

        if (Boolean.TRUE.equals(req.getPublishDirectly())) {
            String email = SecurityUtil.getCurrentUserLogin().orElse("");
            User currentUser = userRepository.findByEmail(email);
            if (currentUser == null) {
                throw new RuntimeException("Không xác định được người chỉnh sửa JD");
            }
            UserScopeContext.UserScope scope = UserScopeContext.get();
            boolean isSuperAdmin = scope != null && scope.isSuperAdmin();
            if (!isSuperAdmin && !permissionService.hasIssuePermission(currentUser)) {
                throw new RuntimeException("Bạn không có quyền ban hành trực tiếp JD này");
            }
            current.setStatus("PUBLISHED");
        }

        current.setReportTo(req.getReportTo());
        current.setBelongsTo(req.getBelongsTo());
        current.setCollaborateWith(req.getCollaborateWith());
        current.setEffectiveDate(req.getEffectiveDate());

        current = repository.save(current); // ← đổi return thành gán

        // ✅ THÊM 3 DÒNG NÀY
        requirementService.update(current.getId(), req.getRequirements());
        taskService.updateFromDTO(current, req.getTasks());
        positionService.updateFromDTO(current, req.getPositions());

        if (Boolean.TRUE.equals(req.getPublishDirectly())) {
            String email = SecurityUtil.getCurrentUserLogin().orElse("");
            User currentUser = userRepository.findByEmail(email);
            
            JdFlow flow = new JdFlow();
            flow.setJobDescription(current);
            flow.setFromUser(currentUser);
            flow.setCurrentUser(null);
            flow.setStatus("ISSUED");
            jdFlowRepository.save(flow);

            jdFlowLogService.saveLog(current, currentUser, null, "ISSUE", "Ban hành trực tiếp");
        }

        return current; // ← đổi chỗ này thôi
    }

    /*
     * ==========================================
     * DELETE
     * ==========================================
     */
    @Transactional
    public void handleDelete(Long id) {

        JobDescription jd = fetchById(id);

        assertOwnerOrAdmin(jd);

        if ("PUBLISHED".equals(jd.getStatus())) {
            throw new RuntimeException("Không thể xóa JD đã ban hành");
        }

        if ("IN_REVIEW".equals(jd.getStatus())) {
            throw new RuntimeException("JD đang duyệt không thể xóa");
        }

        if (jd.getTasks() != null) {
            jd.getTasks().forEach(t -> taskService.delete(t.getId()));
        }
        if (jd.getRequirement() != null) {
            requirementService.delete(jd.getRequirement().getId());
        }
        if (jd.getPositions() != null) {
            jd.getPositions().forEach(p -> positionService.delete(p.getId()));
        }
        jdFlowLogService.deleteByJobDescription(id);

        JdFlow flow = jdFlowRepository.findByJobDescriptionId(id);
        if (flow != null) {
            jdFlowRepository.delete(flow);
        }

        repository.delete(jd);
    }

    /*
     * ==========================================
     * FETCH BY ID
     * ==========================================
     */
    public JobDescription fetchById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IdInvalidException(
                        "JobDescription không tồn tại với id = " + id));
    }

    /*
     * ==========================================
     * FETCH ALL (có filter)
     * ==========================================
     */
    public ResultPaginationDTO fetchAll(
            Specification<JobDescription> spec,
            Pageable pageable) {

        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope != null && !scope.isAdminLevel()) {
            spec = Specification.where(spec).and(ScopeSpec.byCompanyScope("company.id"));
        }
        return buildPaginationDTO(repository.findAll(spec, pageable));
    }

    /*
     * ==========================================
     * JD TÔI TẠO
     * ==========================================
     */
    public ResultPaginationDTO fetchMyJobDescriptions(Pageable pageable) {
        String email = SecurityUtil.getCurrentUserLogin().orElse("");
        return buildPaginationDTO(repository.findByCreatedBy(email, pageable));
    }

    /*
     * ==========================================
     * JD ĐÃ BAN HÀNH
     * ==========================================
     */
    public ResultPaginationDTO fetchPublished(Pageable pageable) {
        Specification<JobDescription> spec = (root, query, cb) -> cb.equal(root.get("status"), "PUBLISHED");
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope != null && !scope.isAdminLevel()) {
            spec = spec.and(ScopeSpec.byCompanyScope("company.id"));
        }
        return buildPaginationDTO(repository.findAll(spec, pageable));
    }

    /*
     * ==========================================
     * JD ĐÃ TỪ CHỐI
     * ==========================================
     */
    // SỬA THÀNH — chỉ lấy JD mà TÔI đã từ chối
    public ResultPaginationDTO fetchRejected(Pageable pageable) {
        String email = SecurityUtil.getCurrentUserLogin().orElse("");
        User me = userRepository.findByEmail(email);
        if (me == null)
            return buildPaginationDTO(Page.empty());
        return buildPaginationDTO(
                repository.findRejectedByUser(me.getId(), pageable));
    }

    /*
     * ==========================================
     * TẤT CẢ JD (admin)
     * ==========================================
     */
    public ResultPaginationDTO fetchAllJd(Pageable pageable) {
        Specification<JobDescription> spec = Specification.where(null);
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope != null && !scope.isAdminLevel()) {
            spec = spec.and(ScopeSpec.byCompanyScope("company.id"));
        }
        return buildPaginationDTO(repository.findAll(spec, pageable));
    }

    /*
     * ==========================================
     * VALIDATE
     * ==========================================
     */
    private void validateScope(JobDescription jd) {
        if (jd.getCompanyJobTitle() == null
                && jd.getDepartmentJobTitle() == null
                && jd.getSectionJobTitle() == null) {
            throw new IdInvalidException(
                    "JobDescription phải gắn ít nhất 1 chức danh");
        }
    }

    /*
     * ==========================================
     * QUYỀN SỞ HỮU + PHẠM VI CÔNG TY (cho sửa/xóa JD)
     * ==========================================
     */
    private void assertOwnerOrAdmin(JobDescription jd) {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        boolean isAdmin = scope != null && (scope.isSuperAdmin() || scope.isAdminLevel());

        if (!isAdmin) {
            String email = SecurityUtil.getCurrentUserLogin().orElse("");
            if (!email.equals(jd.getCreatedBy())) {
                throw new PermissionException("Bạn không có quyền thao tác trên JD này");
            }
        }

        if (scope != null && !isAdmin && scope.isCompanyLevel()) {
            Long companyId = jd.getCompany() != null ? jd.getCompany().getId() : null;
            if (companyId == null || scope.companyIds() == null || !scope.companyIds().contains(companyId)) {
                throw new PermissionException("Bạn không có quyền thao tác dữ liệu cho công ty này");
            }
        }
    }

    /*
     * ==========================================
     * HELPER — build pagination DTO
     * ==========================================
     */
    private ResultPaginationDTO buildPaginationDTO(Page<JobDescription> page) {
        ResultPaginationDTO rs = new ResultPaginationDTO();
        ResultPaginationDTO.Meta meta = new ResultPaginationDTO.Meta();

        meta.setPage(page.getNumber() + 1);
        meta.setPageSize(page.getSize());
        meta.setPages(page.getTotalPages());
        meta.setTotal(page.getTotalElements());

        rs.setMeta(meta);
        rs.setResult(
                page.getContent()
                        .stream()
                        .map(this::convertToDTO)
                        .collect(Collectors.toList()));
        return rs;
    }

    /*
     * ==========================================
     * DTO
     * ==========================================
     */
    public ResJobDescriptionDTO convertToDTO(JobDescription jd) {

        ResJobDescriptionDTO res = new ResJobDescriptionDTO();

        res.setId(jd.getId());
        res.setCode(jd.getCode());
        res.setReportTo(jd.getReportTo());
        res.setBelongsTo(jd.getBelongsTo());
        res.setCollaborateWith(jd.getCollaborateWith());
        res.setStatus(jd.getStatus());
        res.setVersion(jd.getVersion());
        res.setEffectiveDate(jd.getEffectiveDate());
        res.setCreatedAt(jd.getCreatedAt());
        res.setUpdatedAt(jd.getUpdatedAt());
        res.setCreatedBy(jd.getCreatedBy());
        res.setUpdatedBy(jd.getUpdatedBy());

        if (jd.getCompany() != null) {
            res.setCompanyId(jd.getCompany().getId());
            res.setCompanyName(jd.getCompany().getName());
        }

        if (jd.getDepartment() != null) {
            res.setDepartmentId(jd.getDepartment().getId());
            res.setDepartmentName(jd.getDepartment().getName());
        }

        if (jd.getCompanyJobTitle() != null) {
            res.setCompanyJobTitleId(jd.getCompanyJobTitle().getId());
        }

        if (jd.getDepartmentJobTitle() != null) {
            res.setDepartmentJobTitleId(jd.getDepartmentJobTitle().getId());
            if (jd.getDepartmentJobTitle().getJobTitle() != null) {
                res.setJobTitleName(
                        jd.getDepartmentJobTitle().getJobTitle().getNameVi());
            }
        }

        if (jd.getSectionJobTitle() != null) {
            res.setSectionJobTitleId(jd.getSectionJobTitle().getId());
        }

        // Tối ưu hóa: Sử dụng trực tiếp từ thực thể nhờ OneToOne/OneToMany bidirectional
        res.setRequirements(requirementService.convertToDTO(jd.getRequirement()));

        if (jd.getTasks() != null) {
            res.setTasks(jd.getTasks().stream()
                    .map(taskService::convertToDTO)
                    .collect(Collectors.toList()));
        }

        if (jd.getPositions() != null) {
            res.setPositions(jd.getPositions().stream()
                    .map(positionService::convertToDTO)
                    .collect(Collectors.toList()));
        }

        if ("REJECTED".equals(jd.getStatus()) || "RETURNED".equals(jd.getStatus())) {
            JdFlowLogService.RejectInfo rejectInfo = jdFlowLogService.findLatestRejectInfo(jd.getId());
            if (rejectInfo != null) {
                res.setRejectComment(rejectInfo.getComment());
                res.setRejectorName(rejectInfo.getRejectorName());
                res.setRejectorPosition(rejectInfo.getRejectorPosition());
                res.setRejectorDepartment(rejectInfo.getRejectorDepartment());
                res.setRejectorPositionCode(rejectInfo.getRejectorPositionCode());
            }
        }
        return res;
    }
}