package vn.system.app.modules.companyjobtitle.service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.SecurityUtil;
import vn.system.app.common.util.error.IdInvalidException;

import vn.system.app.modules.company.domain.Company;
import vn.system.app.modules.company.service.CompanyService;
import vn.system.app.modules.companyjobtitle.domain.CompanyJobTitle;
import vn.system.app.modules.companyjobtitle.domain.request.ReqCompanyJobTitleDTO;
import vn.system.app.modules.companyjobtitle.domain.response.ResCompanyJobTitleDTO;
import vn.system.app.modules.companyjobtitle.repository.CompanyJobTitleRepository;
import vn.system.app.modules.departmentjobtitle.domain.DepartmentJobTitle;
import vn.system.app.modules.departmentjobtitle.repository.DepartmentJobTitleRepository;
import vn.system.app.modules.jobtitle.domain.JobTitle;
import vn.system.app.modules.jobtitle.service.JobTitleService;
import vn.system.app.modules.sectionjobtitle.domain.SectionJobTitle;
import vn.system.app.modules.sectionjobtitle.repository.SectionJobTitleRepository;
import vn.system.app.common.util.ScopeSpec;
import vn.system.app.common.util.UserScopeContext;
import vn.system.app.common.util.error.PermissionException;

@Service
public class CompanyJobTitleService {

    private final CompanyJobTitleRepository repository;
    private final CompanyService companyService;
    private final JobTitleService jobTitleService;
    private final DepartmentJobTitleRepository departmentRepo;
    private final SectionJobTitleRepository sectionRepo;

    public CompanyJobTitleService(
            CompanyJobTitleRepository repository,
            CompanyService companyService,
            JobTitleService jobTitleService,
            DepartmentJobTitleRepository departmentRepo,
            SectionJobTitleRepository sectionRepo) {

        this.repository = repository;
        this.companyService = companyService;
        this.jobTitleService = jobTitleService;
        this.departmentRepo = departmentRepo;
        this.sectionRepo = sectionRepo;
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
     * CREATE / REACTIVATE (GÁN Ở CÔNG TY)
     * =====================================================
     */
    @Transactional
    public CompanyJobTitle handleCreate(ReqCompanyJobTitleDTO dto) {

        validateScope(dto.getCompanyId());

        Company company = companyService.fetchEntityById(dto.getCompanyId());
        JobTitle jobTitle = jobTitleService.fetchEntityById(dto.getJobTitleId());

        Long companyId = company.getId();
        Long jobTitleId = jobTitle.getId();

        // ❌ Đã tồn tại ở PHÒNG BAN
        if (departmentRepo.existsByDepartment_Company_IdAndJobTitle_IdAndActiveTrue(companyId, jobTitleId)) {
            throw new IdInvalidException(
                    "Chức danh đã được gán ở phòng ban, không thể gán ở công ty.");
        }

        // ❌ Đã tồn tại ở BỘ PHẬN
        if (sectionRepo.existsBySection_Department_Company_IdAndJobTitle_IdAndActiveTrue(companyId, jobTitleId)) {
            throw new IdInvalidException(
                    "Chức danh đã được gán ở bộ phận, không thể gán ở công ty.");
        }

        CompanyJobTitle existing = repository.findByCompany_IdAndJobTitle_Id(companyId, jobTitleId);

        if (existing != null) {
            if (existing.isActive()) {
                throw new IdInvalidException(
                        "Chức danh đã được gán và đang hoạt động ở công ty.");
            }

            existing.setActive(true);
            existing.setUpdatedAt(Instant.now());
            existing.setUpdatedBy(SecurityUtil.getCurrentUserLogin().orElse(""));
            return repository.save(existing);
        }

        CompanyJobTitle entity = new CompanyJobTitle();
        entity.setCompany(company);
        entity.setJobTitle(jobTitle);
        entity.setActive(true);

        return repository.save(entity);
    }

    /*
     * =====================================================
     * SOFT DELETE
     * =====================================================
     */
    @Transactional
    public void handleDelete(Long id) {

        CompanyJobTitle entity = fetchEntityById(id);
        validateScope(entity.getCompany().getId());

        if (!entity.isActive())
            return;

        entity.setActive(false);
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(SecurityUtil.getCurrentUserLogin().orElse(""));
        repository.save(entity);
    }

    /*
     * =====================================================
     * RESTORE (BỔ SUNG VALIDATE ĐỘC QUYỀN)
     * =====================================================
     */
    @Transactional
    public CompanyJobTitle restore(Long id) {

        CompanyJobTitle entity = fetchEntityById(id);
        validateScope(entity.getCompany().getId());

        if (entity.isActive()) {
            throw new IdInvalidException("Bản ghi đang hoạt động, không cần khôi phục.");
        }

        Long companyId = entity.getCompany().getId();
        Long jobTitleId = entity.getJobTitle().getId();

        // ❌ Nếu đang active ở phòng ban
        if (departmentRepo.existsByDepartment_Company_IdAndJobTitle_IdAndActiveTrue(companyId, jobTitleId)) {
            throw new IdInvalidException(
                    "Chức danh đang được gán ở phòng ban, không thể khôi phục ở công ty.");
        }

        // ❌ Nếu đang active ở bộ phận
        if (sectionRepo.existsBySection_Department_Company_IdAndJobTitle_IdAndActiveTrue(companyId, jobTitleId)) {
            throw new IdInvalidException(
                    "Chức danh đang được gán ở bộ phận, không thể khôi phục ở công ty.");
        }

        entity.setActive(true);
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(SecurityUtil.getCurrentUserLogin().orElse(""));

        return repository.save(entity);
    }

    /*
     * =====================================================
     * FETCH ENTITY
     * =====================================================
     */
    public CompanyJobTitle fetchEntityById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy CompanyJobTitle với id = " + id));
    }

    public CompanyJobTitle fetchEntityByIdWithScopeCheck(Long id) {
        CompanyJobTitle entity = fetchEntityById(id);
        validateScope(entity.getCompany().getId());
        return entity;
    }

    /*
     * =====================================================
     * FETCH ALL (BẢNG COMPANY_JOB_TITLES)
     * =====================================================
     */
    public ResultPaginationDTO fetchAll(
            Specification<CompanyJobTitle> spec,
            Pageable pageable) {
        // ── ADMIN_SUB_2 filter ────────────────────────────────
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope != null && !scope.isAdminLevel()) {
            Specification<CompanyJobTitle> scopeSpec = ScopeSpec.byCompanyScope("company.id");
            spec = Specification.where(spec).and(scopeSpec);
        }
        Page<CompanyJobTitle> page = repository.findAll(spec, pageable);

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

    /*
     * =====================================================
     * GET TOÀN BỘ CHỨC DANH CỦA CÔNG TY (ĐỔ TỪ DƯỚI LÊN)
     * =====================================================
     */
    @Transactional(readOnly = true)
    public List<ResCompanyJobTitleDTO> fetchAllJobTitlesOfCompany(Long companyId) {

        validateScope(companyId);

        Company company = companyService.fetchEntityById(companyId);

        Map<Long, CompanyJobTitle> companyMap = repository.findByCompany_IdAndActiveTrue(companyId)
                .stream()
                .collect(Collectors.toMap(
                        e -> e.getJobTitle().getId(),
                        e -> e));

        List<DepartmentJobTitle> deptList = departmentRepo.findByDepartment_Company_IdAndActiveTrue(companyId);

        List<SectionJobTitle> sectionList = sectionRepo.findBySection_Department_Company_IdAndActiveTrue(companyId);

        Map<Long, ResCompanyJobTitleDTO> resultMap = new LinkedHashMap<>();

        for (SectionJobTitle sjt : sectionList) {
            Long jobId = sjt.getJobTitle().getId();
            ResCompanyJobTitleDTO dto = convertToResDTO(buildVirtual(company, sjt.getJobTitle()));
            dto.setSource("SECTION");
            resultMap.put(jobId, dto);
        }

        for (DepartmentJobTitle djt : deptList) {
            Long jobId = djt.getJobTitle().getId();
            if (!resultMap.containsKey(jobId)) {
                ResCompanyJobTitleDTO dto = convertToResDTO(buildVirtual(company, djt.getJobTitle()));
                dto.setSource("DEPARTMENT");
                resultMap.put(jobId, dto);
            }
        }

        for (CompanyJobTitle cjt : companyMap.values()) {
            Long jobId = cjt.getJobTitle().getId();
            if (!resultMap.containsKey(jobId)) {
                ResCompanyJobTitleDTO dto = convertToResDTO(cjt);
                dto.setSource("COMPANY");
                resultMap.put(jobId, dto);
            }
        }

        return resultMap.values().stream()
                .sorted(Comparator
                        .comparing((ResCompanyJobTitleDTO dto) -> {
                            var jt = dto.getJobTitle();
                            return jt != null && jt.getBandOrder() != null
                                    ? jt.getBandOrder()
                                    : Integer.MAX_VALUE;
                        })
                        .thenComparing(dto -> {
                            var jt = dto.getJobTitle();
                            return jt != null && jt.getLevelNumber() != null
                                    ? jt.getLevelNumber()
                                    : Integer.MAX_VALUE;
                        }))
                .collect(Collectors.toList());
    }

    /*
     * =====================================================
     * UNASSIGNED JOB TITLES
     * =====================================================
     */
    @Transactional(readOnly = true)
    public List<ResCompanyJobTitleDTO> fetchUnassignedJobTitlesOfCompany(Long companyId) {

        validateScope(companyId);

        List<JobTitle> allActiveJobTitles = jobTitleService.findAllActive();
        if (allActiveJobTitles.isEmpty())
            return Collections.emptyList();

        Set<Long> assignedJobTitleIds = new HashSet<>();

        repository.findByCompany_IdAndActiveTrue(companyId)
                .forEach(cjt -> assignedJobTitleIds.add(cjt.getJobTitle().getId()));

        departmentRepo.findByDepartment_Company_IdAndActiveTrue(companyId)
                .forEach(djt -> assignedJobTitleIds.add(djt.getJobTitle().getId()));

        sectionRepo.findBySection_Department_Company_IdAndActiveTrue(companyId)
                .forEach(sjt -> assignedJobTitleIds.add(sjt.getJobTitle().getId()));

        Company company = companyService.fetchEntityById(companyId);

        return allActiveJobTitles.stream()
                .filter(jt -> !assignedJobTitleIds.contains(jt.getId()))
                .map(jt -> {
                    ResCompanyJobTitleDTO dto = new ResCompanyJobTitleDTO();
                    dto.setId(null);
                    dto.setActive(false);
                    dto.setSource("UNASSIGNED");

                    ResCompanyJobTitleDTO.JobTitleInfo jtInfo = new ResCompanyJobTitleDTO.JobTitleInfo();
                    jtInfo.setId(jt.getId());
                    jtInfo.setNameVi(jt.getNameVi());

                    if (jt.getPositionLevel() != null) {
                        var pl = jt.getPositionLevel();
                        jtInfo.setPositionCode(pl.getCode());
                        jtInfo.setBand(pl.getCode().replaceAll("[0-9]", ""));
                        jtInfo.setLevel(Integer.parseInt(pl.getCode().replaceAll("[^0-9]", "")));
                        jtInfo.setBandOrder(pl.getBandOrder());
                        jtInfo.setLevelNumber(jtInfo.getLevel());
                    }

                    dto.setJobTitle(jtInfo);

                    ResCompanyJobTitleDTO.CompanyInfo ci = new ResCompanyJobTitleDTO.CompanyInfo();
                    ci.setId(company.getId());
                    ci.setName(company.getName());
                    dto.setCompany(ci);

                    return dto;
                })
                .sorted(Comparator
                        .comparing((ResCompanyJobTitleDTO dto) -> dto.getJobTitle().getBandOrder() != null
                                ? dto.getJobTitle().getBandOrder()
                                : Integer.MAX_VALUE)
                        .thenComparing(dto -> dto.getJobTitle().getLevelNumber() != null
                                ? dto.getJobTitle().getLevelNumber()
                                : Integer.MAX_VALUE))
                .collect(Collectors.toList());
    }

    public List<Long> getActiveDepartmentJobTitleIdsByCompany(Long companyId) {
        return departmentRepo.findByDepartment_Company_IdAndActiveTrue(companyId)
                .stream()
                .map(d -> d.getJobTitle().getId())
                .distinct()
                .collect(Collectors.toList());
    }

    public List<Long> getActiveSectionJobTitleIdsByCompany(Long companyId) {
        return sectionRepo.findBySection_Department_Company_IdAndActiveTrue(companyId)
                .stream()
                .map(s -> s.getJobTitle().getId())
                .distinct()
                .collect(Collectors.toList());
    }

    private CompanyJobTitle buildVirtual(Company company, JobTitle jt) {
        CompanyJobTitle fake = new CompanyJobTitle();
        fake.setCompany(company);
        fake.setJobTitle(jt);
        fake.setActive(false);
        return fake;
    }

    public ResCompanyJobTitleDTO convertToResDTO(CompanyJobTitle e) {

        ResCompanyJobTitleDTO dto = new ResCompanyJobTitleDTO();
        dto.setId(e.getId());
        dto.setActive(e.isActive());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        dto.setCreatedBy(e.getCreatedBy());
        dto.setUpdatedBy(e.getUpdatedBy());

        ResCompanyJobTitleDTO.JobTitleInfo jt = new ResCompanyJobTitleDTO.JobTitleInfo();
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

        dto.setJobTitle(jt);

        ResCompanyJobTitleDTO.CompanyInfo c = new ResCompanyJobTitleDTO.CompanyInfo();
        c.setId(e.getCompany().getId());
        c.setName(e.getCompany().getName());
        dto.setCompany(c);

        return dto;
    }
}
