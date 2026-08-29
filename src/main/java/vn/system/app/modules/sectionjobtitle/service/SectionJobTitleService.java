package vn.system.app.modules.sectionjobtitle.service;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.SecurityUtil;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.common.util.error.PermissionException;
import vn.system.app.common.util.UserScopeContext;
import vn.system.app.common.util.ScopeSpec;

import vn.system.app.modules.companyjobtitle.repository.CompanyJobTitleRepository;
import vn.system.app.modules.departmentjobtitle.service.DepartmentJobTitleService;
import vn.system.app.modules.jobtitle.domain.JobTitle;
import vn.system.app.modules.jobtitle.service.JobTitleService;
import vn.system.app.modules.section.domain.Section;
import vn.system.app.modules.section.service.SectionService;
import vn.system.app.modules.sectionjobtitle.domain.SectionJobTitle;
import vn.system.app.modules.sectionjobtitle.domain.request.ReqSectionJobTitleDTO;
import vn.system.app.modules.sectionjobtitle.domain.response.ResSectionJobTitleDTO;
import vn.system.app.modules.sectionjobtitle.repository.SectionJobTitleRepository;

@Service
public class SectionJobTitleService {

    private final SectionJobTitleRepository repository;
    private final JobTitleService jobTitleService;
    private final SectionService sectionService;
    private final DepartmentJobTitleService departmentJobTitleService;
    private final CompanyJobTitleRepository companyRepo;

    public SectionJobTitleService(
            SectionJobTitleRepository repository,
            JobTitleService jobTitleService,
            SectionService sectionService,
            DepartmentJobTitleService departmentJobTitleService,
            CompanyJobTitleRepository companyRepo) {

        this.repository = repository;
        this.jobTitleService = jobTitleService;
        this.sectionService = sectionService;
        this.departmentJobTitleService = departmentJobTitleService;
        this.companyRepo = companyRepo;
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

    /*
     * =====================================================
     * CREATE + REACTIVATE
     * =====================================================
     */
    @Transactional
    public SectionJobTitle handleCreate(ReqSectionJobTitleDTO dto) {

        JobTitle jobTitle = jobTitleService.fetchEntityById(dto.getJobTitleId());
        Section section = sectionService.fetchEntityById(dto.getSectionId());

        validateScope(section.getDepartment().getCompany() != null ? section.getDepartment().getCompany().getId() : null);

        Long deptId = section.getDepartment().getId();
        Long jobId = jobTitle.getId();
        Long companyId = section.getDepartment().getCompany().getId();

        // Không cho gán nếu đã active ở cấp công ty
        if (companyRepo.existsByCompany_IdAndJobTitle_IdAndActiveTrue(companyId, jobId)) {
            throw new IdInvalidException(
                    "Chức danh đã được gán ở cấp công ty, không thể gán vào bộ phận.");
        }

        // Không cho gán nếu đã active trực tiếp ở phòng ban
        if (departmentJobTitleService.existsActiveInDepartment(deptId, jobId)) {
            throw new IdInvalidException(
                    "Chức danh đã được gán trực tiếp vào phòng ban, không thể gán vào bộ phận.");
        }

        SectionJobTitle existing = repository.findByJobTitle_IdAndSection_Id(jobId, dto.getSectionId());

        if (existing != null) {
            if (existing.isActive()) {
                throw new IdInvalidException(
                        "Chức danh đã được gán và đang hoạt động trong bộ phận.");
            }

            existing.setActive(true);
            existing.setUpdatedAt(Instant.now());
            existing.setUpdatedBy(SecurityUtil.getCurrentUserLogin().orElse("system"));

            SectionJobTitle saved = repository.save(existing);

            // sync lên department
            departmentJobTitleService.assignIfNotExists(deptId, jobId);

            return saved;
        }

        SectionJobTitle entity = new SectionJobTitle();
        entity.setJobTitle(jobTitle);
        entity.setSection(section);
        entity.setActive(true);

        SectionJobTitle saved = repository.save(entity);

        // sync lên department
        departmentJobTitleService.assignIfNotExists(deptId, jobId);

        return saved;
    }

    /*
     * =====================================================
     * RESTORE
     * =====================================================
     */
    @Transactional
    public SectionJobTitle restore(Long id) {

        SectionJobTitle entity = fetchEntityById(id);
        validateScope(entity.getSection().getDepartment().getCompany() != null ? entity.getSection().getDepartment().getCompany().getId() : null);

        if (entity.isActive()) {
            throw new IdInvalidException("Bản ghi đang hoạt động, không cần khôi phục.");
        }

        Long deptId = entity.getSection().getDepartment().getId();
        Long jobId = entity.getJobTitle().getId();
        Long companyId = entity.getSection().getDepartment().getCompany().getId();

        if (companyRepo.existsByCompany_IdAndJobTitle_IdAndActiveTrue(companyId, jobId)) {
            throw new IdInvalidException(
                    "Chức danh đang được gán ở cấp công ty, không thể khôi phục.");
        }

        if (departmentJobTitleService.existsActiveInDepartment(deptId, jobId)) {
            throw new IdInvalidException(
                    "Chức danh đang được gán trực tiếp ở phòng ban, không thể khôi phục.");
        }

        entity.setActive(true);
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(SecurityUtil.getCurrentUserLogin().orElse("system"));

        SectionJobTitle saved = repository.save(entity);

        departmentJobTitleService.assignIfNotExists(deptId, jobId);

        return saved;
    }

    /*
     * =====================================================
     * SOFT DELETE
     * =====================================================
     */
    @Transactional
    public void handleSoftDelete(Long id) {

        SectionJobTitle entity = fetchEntityById(id);
        validateScope(entity.getSection().getDepartment().getCompany() != null ? entity.getSection().getDepartment().getCompany().getId() : null);

        if (!entity.isActive()) {
            return;
        }

        entity.setActive(false);
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(SecurityUtil.getCurrentUserLogin().orElse("system"));
        repository.save(entity);

        Long deptId = entity.getSection().getDepartment().getId();
        Long jobId = entity.getJobTitle().getId();

        boolean stillUsed = repository.existsBySection_Department_IdAndJobTitle_IdAndActiveTrue(deptId, jobId);

        if (!stillUsed) {
            departmentJobTitleService.inactiveIfExists(deptId, jobId);
        }
    }

    /*
     * =====================================================
     * FETCH ENTITY
     * =====================================================
     */
    public SectionJobTitle fetchEntityById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy gán chức danh - bộ phận với id: " + id));
    }

    public SectionJobTitle fetchEntityByIdWithScopeCheck(Long id) {
        SectionJobTitle entity = fetchEntityById(id);
        validateScope(entity.getSection().getDepartment().getCompany() != null
                ? entity.getSection().getDepartment().getCompany().getId()
                : null);
        return entity;
    }

    /*
     * =====================================================
     * FETCH ALL
     * =====================================================
     */
    public ResultPaginationDTO fetchAll(
            Specification<SectionJobTitle> spec,
            Pageable pageable) {

        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope != null && !scope.isAdminLevel()) {
            if (scope.isAdminLevel()) {
                // ADMIN_SUB_1 → thấy toàn bộ, không filter
            } else if (scope.isCompanyLevel()) {
                spec = Specification.where(spec)
                        .and(ScopeSpec.byCompanyScope("section.department.company.id"));
            } else {
                spec = Specification.where(spec)
                        .and(ScopeSpec.byDepartmentScope("section.department.id"));
            }
        }

        Page<SectionJobTitle> page = repository.findAll(spec, pageable);

        ResultPaginationDTO rs = new ResultPaginationDTO();
        ResultPaginationDTO.Meta meta = new ResultPaginationDTO.Meta();

        meta.setPage(pageable.getPageNumber() + 1);
        meta.setPageSize(pageable.getPageSize());
        meta.setPages(page.getTotalPages());
        meta.setTotal(page.getTotalElements());

        rs.setMeta(meta);
        rs.setResult(
                page.getContent()
                        .stream()
                        .map(this::convertToResDTO)
                        .collect(Collectors.toList()));

        return rs;
    }

    public List<SectionJobTitle> fetchActiveBySection(Long sectionId) {
        return repository.findBySection_IdAndActiveTrue(sectionId);
    }

    /*
     * =====================================================
     * CONVERT TO DTO
     * =====================================================
     */
    public ResSectionJobTitleDTO convertToResDTO(SectionJobTitle e) {

        ResSectionJobTitleDTO res = new ResSectionJobTitleDTO();
        res.setId(e.getId());
        res.setActive(e.isActive());
        res.setCreatedAt(e.getCreatedAt());
        res.setUpdatedAt(e.getUpdatedAt());
        res.setCreatedBy(e.getCreatedBy());
        res.setUpdatedBy(e.getUpdatedBy());

        ResSectionJobTitleDTO.JobTitleInfo jt = new ResSectionJobTitleDTO.JobTitleInfo();
        jt.setId(e.getJobTitle().getId());
        jt.setNameVi(e.getJobTitle().getNameVi());

        if (e.getJobTitle().getPositionLevel() != null) {
            var pl = e.getJobTitle().getPositionLevel();
            jt.setPositionCode(pl.getCode());
            jt.setBand(pl.getCode().replaceAll("[0-9]", ""));
            jt.setLevel(Integer.parseInt(pl.getCode().replaceAll("[^0-9]", "")));
            jt.setBandOrder(pl.getBandOrder());
            jt.setLevelNumber(jt.getLevel());
        }

        res.setJobTitle(jt);

        ResSectionJobTitleDTO.SectionInfo sc = new ResSectionJobTitleDTO.SectionInfo();
        sc.setId(e.getSection().getId());
        sc.setName(e.getSection().getName());
        res.setSection(sc);

        ResSectionJobTitleDTO.DepartmentInfo dp = new ResSectionJobTitleDTO.DepartmentInfo();
        dp.setId(e.getSection().getDepartment().getId());
        dp.setName(e.getSection().getDepartment().getName());
        res.setDepartment(dp);

        return res;
    }
}
