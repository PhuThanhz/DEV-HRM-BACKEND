package vn.system.app.modules.departmentjobtitle.service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.ScopeSpec;
import vn.system.app.common.util.SecurityUtil;
import vn.system.app.common.util.UserScopeContext;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.common.util.error.PermissionException;

import vn.system.app.modules.companyjobtitle.domain.CompanyJobTitle;
import vn.system.app.modules.companyjobtitle.repository.CompanyJobTitleRepository;
import vn.system.app.modules.department.domain.Department;
import vn.system.app.modules.department.service.DepartmentService;
import vn.system.app.modules.departmentjobtitle.domain.DepartmentJobTitle;
import vn.system.app.modules.departmentjobtitle.domain.request.ReqDepartmentJobTitleDTO;
import vn.system.app.modules.departmentjobtitle.domain.response.ResDepartmentJobTitleDTO;
import vn.system.app.modules.departmentjobtitle.domain.response.ResJobTitleAssignStatusDTO;
import vn.system.app.modules.departmentjobtitle.repository.DepartmentJobTitleRepository;
import vn.system.app.modules.jobtitle.domain.JobTitle;
import vn.system.app.modules.jobtitle.service.JobTitleService;
import vn.system.app.modules.sectionjobtitle.domain.SectionJobTitle;
import vn.system.app.modules.sectionjobtitle.repository.SectionJobTitleRepository;

@Service
public class DepartmentJobTitleService {

    private final DepartmentJobTitleRepository repository;
    private final SectionJobTitleRepository sectionRepo;
    private final CompanyJobTitleRepository companyRepo;
    private final JobTitleService jobTitleService;
    private final DepartmentService departmentService;

    public DepartmentJobTitleService(
            DepartmentJobTitleRepository repository,
            SectionJobTitleRepository sectionRepo,
            CompanyJobTitleRepository companyRepo,
            JobTitleService jobTitleService,
            DepartmentService departmentService) {

        this.repository = repository;
        this.sectionRepo = sectionRepo;
        this.companyRepo = companyRepo;
        this.jobTitleService = jobTitleService;
        this.departmentService = departmentService;
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

    public List<DepartmentJobTitle> fetchEntitiesByIds(List<Long> ids) {
        return repository.findAllById(ids);
    }

    /*
     * =====================================================
     * CREATE + REACTIVATE
     * =====================================================
     */
    @Transactional
    public DepartmentJobTitle handleCreate(ReqDepartmentJobTitleDTO dto) {

        JobTitle jobTitle = jobTitleService.fetchEntityById(dto.getJobTitleId());
        Department department = departmentService.fetchEntityById(dto.getDepartmentId());

        validateScope(department.getCompany() != null ? department.getCompany().getId() : null);

        Long deptId = department.getId();
        Long jobId = jobTitle.getId();
        Long companyId = department.getCompany().getId();

        if (companyRepo.existsByCompany_IdAndJobTitle_IdAndActiveTrue(companyId, jobId)) {
            throw new IdInvalidException(
                    "Chức danh đã được gán ở cấp công ty, không thể gán ở phòng ban.");
        }

        if (sectionRepo.existsBySection_Department_IdAndJobTitle_IdAndActiveTrue(deptId, jobId)) {
            throw new IdInvalidException(
                    "Chức danh đang được gán ở bộ phận, không thể gán trực tiếp vào phòng ban.");
        }

        DepartmentJobTitle existing = repository.findByDepartment_IdAndJobTitle_Id(deptId, jobId);

        if (existing != null) {
            if (existing.isActive()) {
                throw new IdInvalidException(
                        "Chức danh đã được gán và đang hoạt động trong phòng ban.");
            }
            existing.setActive(true);
            existing.setUpdatedAt(Instant.now());
            existing.setUpdatedBy(SecurityUtil.getCurrentUserLogin().orElse("system"));
            return repository.save(existing);
        }

        DepartmentJobTitle entity = new DepartmentJobTitle();
        entity.setDepartment(department);
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

        DepartmentJobTitle entity = fetchEntityById(id);
        validateScope(entity.getDepartment().getCompany() != null ? entity.getDepartment().getCompany().getId() : null);

        if (!entity.isActive()) {
            return;
        }

        entity.setActive(false);
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(SecurityUtil.getCurrentUserLogin().orElse("system"));

        repository.save(entity);
    }

    /*
     * =====================================================
     * RESTORE
     * =====================================================
     */
    @Transactional
    public DepartmentJobTitle restore(Long id) {

        DepartmentJobTitle entity = fetchEntityById(id);
        validateScope(entity.getDepartment().getCompany() != null ? entity.getDepartment().getCompany().getId() : null);

        if (entity.isActive()) {
            throw new IdInvalidException("Bản ghi đang hoạt động, không cần khôi phục.");
        }

        Long companyId = entity.getDepartment().getCompany().getId();
        Long jobId = entity.getJobTitle().getId();
        Long deptId = entity.getDepartment().getId();

        if (companyRepo.existsByCompany_IdAndJobTitle_IdAndActiveTrue(companyId, jobId)) {
            throw new IdInvalidException(
                    "Chức danh đang được gán ở cấp công ty, không thể khôi phục.");
        }

        if (sectionRepo.existsBySection_Department_IdAndJobTitle_IdAndActiveTrue(deptId, jobId)) {
            throw new IdInvalidException(
                    "Chức danh đang được gán ở bộ phận, không thể khôi phục.");
        }

        entity.setActive(true);
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(SecurityUtil.getCurrentUserLogin().orElse("system"));

        return repository.save(entity);
    }

    /*
     * =====================================================
     * CHECK ACTIVE IN DEPARTMENT
     * =====================================================
     */
    public boolean existsActiveInDepartment(Long departmentId, Long jobTitleId) {
        return repository.existsByDepartment_IdAndJobTitle_IdAndActiveTrue(departmentId, jobTitleId);
    }

    /*
     * =====================================================
     * AUTO SYNC FROM SECTION → DEPARTMENT
     * =====================================================
     */
    @Transactional
    public void assignIfNotExists(Long departmentId, Long jobTitleId) {

        Department dept = departmentService.fetchEntityById(departmentId);
        validateScope(dept.getCompany() != null ? dept.getCompany().getId() : null);
        Long companyId = dept.getCompany().getId();

        if (companyRepo.existsByCompany_IdAndJobTitle_IdAndActiveTrue(companyId, jobTitleId)) {
            return;
        }

        DepartmentJobTitle existing = repository.findByDepartment_IdAndJobTitle_Id(departmentId, jobTitleId);

        if (existing != null) {
            if (!existing.isActive()) {
                existing.setActive(true);
                existing.setUpdatedAt(Instant.now());
                existing.setUpdatedBy(SecurityUtil.getCurrentUserLogin().orElse("system"));
                repository.save(existing);
            }
            return;
        }

        JobTitle jt = jobTitleService.fetchEntityById(jobTitleId);

        DepartmentJobTitle entity = new DepartmentJobTitle();
        entity.setDepartment(dept);
        entity.setJobTitle(jt);
        entity.setActive(true);

        repository.save(entity);
    }

    /*
     * =====================================================
     * AUTO DEACTIVATE WHEN SECTION REMOVE
     * =====================================================
     */
    @Transactional
    public void inactiveIfExists(Long departmentId, Long jobTitleId) {

        DepartmentJobTitle entity = repository.findByDepartment_IdAndJobTitle_Id(departmentId, jobTitleId);
        if (entity != null) {
            validateScope(entity.getDepartment().getCompany() != null ? entity.getDepartment().getCompany().getId() : null);
        }

        if (entity != null && entity.isActive()) {
            entity.setActive(false);
            entity.setUpdatedAt(Instant.now());
            entity.setUpdatedBy(SecurityUtil.getCurrentUserLogin().orElse("system"));
            repository.save(entity);
        }
    }

    /*
     * =====================================================
     * VIEW TỔNG HỢP COMPANY_JOB_TITLE TẠI PHÒNG BAN
     * =====================================================
     */
    @Transactional(readOnly = true)
    public List<ResDepartmentJobTitleDTO> fetchAllCompanyJobTitlesOfDepartment(Long departmentId) {

        Department department = departmentService.fetchEntityById(departmentId);
        validateScope(department.getCompany() != null ? department.getCompany().getId() : null);
        Long companyId = department.getCompany().getId();

        List<SectionJobTitle> sectionList = sectionRepo.findBySection_Department_IdAndActiveTrue(departmentId);
        List<DepartmentJobTitle> departmentList = repository.findByDepartment_IdAndActiveTrue(departmentId);
        List<CompanyJobTitle> companyList = companyRepo.findByCompany_IdAndActiveTrue(companyId);

        List<DepartmentJobTitle> allDeptJobs = repository.findByDepartment_Company_IdAndActiveTrue(companyId);

        Map<Long, List<String>> jobUsedInDepartments = new HashMap<>();
        for (DepartmentJobTitle djt : allDeptJobs) {
            Long jobId = djt.getJobTitle().getId();
            Long deptId = djt.getDepartment().getId();
            if (!deptId.equals(departmentId)) {
                jobUsedInDepartments
                        .computeIfAbsent(jobId, k -> new ArrayList<>())
                        .add(djt.getDepartment().getName());
            }
        }

        Map<Long, ResDepartmentJobTitleDTO> resultMap = new LinkedHashMap<>();

        for (SectionJobTitle sjt : sectionList) {
            if (!sjt.getJobTitle().isActive())
                continue;
            Long jobId = sjt.getJobTitle().getId();
            ResDepartmentJobTitleDTO dto = convertToResDTO(
                    buildVirtualDepartmentJobTitle(department, sjt.getJobTitle()));
            dto.setSource("SECTION");
            dto.setUsedInDepartments(
                    jobUsedInDepartments.getOrDefault(jobId, Collections.emptyList()));
            resultMap.put(jobId, dto);
        }

        for (DepartmentJobTitle djt : departmentList) {
            if (!djt.getJobTitle().isActive())
                continue;
            Long jobId = djt.getJobTitle().getId();
            if (!resultMap.containsKey(jobId)) {
                ResDepartmentJobTitleDTO dto = convertToResDTO(djt);
                dto.setSource("DEPARTMENT");
                dto.setUsedInDepartments(
                        jobUsedInDepartments.getOrDefault(jobId, Collections.emptyList()));
                resultMap.put(jobId, dto);
            }
        }

        for (CompanyJobTitle cjt : companyList) {
            if (!cjt.getJobTitle().isActive())
                continue;
            Long jobId = cjt.getJobTitle().getId();
            if (!resultMap.containsKey(jobId)) {
                ResDepartmentJobTitleDTO dto = convertToResDTO(
                        buildVirtualDepartmentJobTitle(department, cjt.getJobTitle()));
                dto.setSource("COMPANY");
                dto.setUsedInDepartments(
                        jobUsedInDepartments.getOrDefault(jobId, Collections.emptyList()));
                resultMap.put(jobId, dto);
            }
        }

        return resultMap.values().stream()
                .sorted(Comparator
                        .comparing((ResDepartmentJobTitleDTO dto) -> dto.getJobTitle().getBandOrder() != null
                                ? dto.getJobTitle().getBandOrder()
                                : Integer.MAX_VALUE)
                        .thenComparing(dto -> dto.getJobTitle().getLevelNumber() != null
                                ? dto.getJobTitle().getLevelNumber()
                                : Integer.MAX_VALUE))
                .collect(Collectors.toList());
    }

    /*
     * =====================================================
     * FETCH ENTITY
     * =====================================================
     */
    public DepartmentJobTitle fetchEntityById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IdInvalidException(
                        "Không tìm thấy gán chức danh - phòng ban với id: " + id));
    }

    public DepartmentJobTitle fetchEntityByIdWithScopeCheck(Long id) {
        DepartmentJobTitle entity = fetchEntityById(id);
        validateScope(entity.getDepartment().getCompany() != null ? entity.getDepartment().getCompany().getId() : null);
        return entity;
    }

    /*
     * =====================================================
     * FETCH IDS BY DEPARTMENT
     * =====================================================
     */
    public List<Long> fetchIdsByDepartment(Long departmentId) {
        return repository.findByDepartment_Id(departmentId)
                .stream()
                .map(DepartmentJobTitle::getId)
                .collect(Collectors.toList());
    }

    /*
     * =====================================================
     * FETCH ALL (PAGINATION)
     * =====================================================
     */
    public ResultPaginationDTO fetchAll(
            Specification<DepartmentJobTitle> spec,
            Pageable pageable) {

        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope != null && !scope.isAdminLevel()) {
            if (scope.isAdminLevel()) {
                // ADMIN_SUB_1 → thấy toàn bộ, không filter
            } else if (scope.isCompanyLevel()) {
                spec = Specification.where(spec)
                        .and(ScopeSpec.byCompanyScope("department.company.id"));
            } else {
                spec = Specification.where(spec)
                        .and(ScopeSpec.byDepartmentScope("department.id"));
            }
        }

        Page<DepartmentJobTitle> page = repository.findAll(spec, pageable);

        ResultPaginationDTO rs = new ResultPaginationDTO();
        ResultPaginationDTO.Meta meta = new ResultPaginationDTO.Meta();

        meta.setPage(pageable.getPageNumber() + 1);
        meta.setPageSize(pageable.getPageSize());
        meta.setPages(page.getTotalPages());
        meta.setTotal(page.getTotalElements());

        rs.setMeta(meta);
        rs.setResult(
                page.getContent().stream()
                        .map(this::convertToResDTO)
                        .collect(Collectors.toList()));

        return rs;
    }

    /*
     * =====================================================
     * FETCH ALL DEPARTMENT_JOB_TITLE (ACTIVE)
     * =====================================================
     */
    public List<DepartmentJobTitle> fetchAllDepartmentJobTitles() {
        return repository.findByActiveTrue();
    }

    /*
     * =====================================================
     * FETCH DEPARTMENT_JOB_TITLE BY ID (STANDARD)
     * =====================================================
     */
    public DepartmentJobTitle fetchDepartmentJobTitleById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IdInvalidException(
                        "Không tìm thấy DepartmentJobTitle với id = " + id));
    }

    /*
     * =====================================================
     * FETCH ACTIVE BY DEPARTMENT
     * =====================================================
     */
    public List<DepartmentJobTitle> fetchByDepartment(Long departmentId) {
        return repository.findByDepartment_IdAndActiveTrue(departmentId);
    }

    /*
     * =====================================================
     * FETCH ALL ACTIVE (FOR PERMISSION MATRIX)
     * =====================================================
     */
    public List<DepartmentJobTitle> fetchAll() {
        return repository.findByActiveTrue();
    }

    /*
     * =====================================================
     * FETCH JOB TITLES WITH ASSIGN STATUS — CÓ FILTER + PAGINATION
     * =====================================================
     */
    @Transactional(readOnly = true)
    public ResultPaginationDTO fetchJobTitlesWithAssignStatus(
            Long departmentId,
            String search,
            String status,
            String band,
            Integer level,
            Pageable pageable) {

        Department department = departmentService.fetchEntityById(departmentId);
        validateScope(department.getCompany() != null ? department.getCompany().getId() : null);
        Long companyId = department.getCompany().getId();

        // 1. Lấy toàn bộ JobTitle active của công ty
        List<JobTitle> allJobTitles = jobTitleService.findAllActiveByCompany(companyId);

        // 2. assignedIds — gán trực tiếp ở phòng ban này
        Set<Long> assignedIds = new HashSet<>(
                repository.findActiveJobTitleIdsByDepartment(departmentId));

        // 3. sectionAssignedIds — gán qua section của phòng ban này
        Set<Long> sectionAssignedIds = sectionRepo
                .findBySection_Department_IdAndActiveTrue(departmentId)
                .stream()
                .map(s -> s.getJobTitle().getId())
                .collect(Collectors.toSet());

        // 4. companyAssignedIds — gán ở cấp công ty
        Set<Long> companyAssignedIds = companyRepo
                .findByCompany_IdAndActiveTrue(companyId)
                .stream()
                .map(c -> c.getJobTitle().getId())
                .collect(Collectors.toSet());

        // 5. usedInDepartments map — chỉ lấy các phòng ban khác trong cùng company
        // dùng query mới trong repo (tránh findAll toàn bảng)
        List<DepartmentJobTitle> othersActive = repository.findAllActiveByCompanyIdExcludingDepartment(companyId,
                departmentId);

        Map<Long, List<String>> jobUsedInDepartments = new HashMap<>();
        for (DepartmentJobTitle djt : othersActive) {
            jobUsedInDepartments
                    .computeIfAbsent(djt.getJobTitle().getId(), k -> new ArrayList<>())
                    .add(djt.getDepartment().getName());
        }

        // 6. Map sang DTO + tính assignSource + canAssign
        List<ResJobTitleAssignStatusDTO> dtoList = allJobTitles.stream()
                .map(jt -> {
                    ResJobTitleAssignStatusDTO dto = new ResJobTitleAssignStatusDTO();
                    dto.setId(jt.getId());
                    dto.setNameVi(jt.getNameVi());
                    dto.setNameEn(jt.getNameEn());

                    if (jt.getPositionLevel() != null) {
                        var pl = jt.getPositionLevel();
                        dto.setPositionCode(pl.getCode());
                        dto.setBand(pl.getCode().replaceAll("[0-9]", ""));
                        dto.setLevel(Integer.parseInt(pl.getCode().replaceAll("[^0-9]", "")));
                        dto.setBandOrder(pl.getBandOrder());
                        dto.setLevelNumber(dto.getLevel());
                    }

                    boolean isAssigned = assignedIds.contains(jt.getId());
                    dto.setAssigned(isAssigned);

                    // assignSource: ưu tiên COMPANY > SECTION > DEPARTMENT > null
                    String assignSource = null;
                    if (companyAssignedIds.contains(jt.getId())) {
                        assignSource = "COMPANY";
                    } else if (sectionAssignedIds.contains(jt.getId())) {
                        assignSource = "SECTION";
                    } else if (isAssigned) {
                        assignSource = "DEPARTMENT";
                    }
                    dto.setAssignSource(assignSource);

                    dto.setUsedInDepartments(
                            jobUsedInDepartments.getOrDefault(jt.getId(), Collections.emptyList()));

                    // canAssign: chưa gán ở phòng này + không phải từ COMPANY/SECTION
                    boolean canAssign = !isAssigned
                            && !"COMPANY".equals(assignSource)
                            && !"SECTION".equals(assignSource);
                    dto.setCanAssign(canAssign);

                    return dto;
                })
                .collect(Collectors.toList());

        // 7. Filter: search (nameVi / nameEn / positionCode)
        if (search != null && !search.isBlank()) {
            String keyword = search.trim().toLowerCase();
            dtoList = dtoList.stream()
                    .filter(d -> (d.getNameVi() != null && d.getNameVi().toLowerCase().contains(keyword))
                            || (d.getNameEn() != null && d.getNameEn().toLowerCase().contains(keyword))
                            || (d.getPositionCode() != null && d.getPositionCode().toLowerCase().contains(keyword)))
                    .collect(Collectors.toList());
        }

        // 8. Filter: status (AVAILABLE / ASSIGNED / USED)
        if (status != null && !status.isBlank()) {
            dtoList = switch (status.toUpperCase()) {
                case "ASSIGNED" -> dtoList.stream()
                        .filter(ResJobTitleAssignStatusDTO::isAssigned)
                        .collect(Collectors.toList());
                case "AVAILABLE" -> dtoList.stream()
                        .filter(d -> !d.isAssigned())
                        .collect(Collectors.toList());
                case "USED" -> dtoList.stream()
                        .filter(d -> d.getUsedInDepartments() != null
                                && !d.getUsedInDepartments().isEmpty())
                        .collect(Collectors.toList());
                default -> dtoList;
            };
        }

        // 9. Filter: band (prefix của positionCode, ví dụ: A, B, C)
        if (band != null && !band.isBlank()) {
            String bandUpper = band.trim().toUpperCase();
            dtoList = dtoList.stream()
                    .filter(d -> bandUpper.equals(d.getBand()))
                    .collect(Collectors.toList());
        }

        // 10. Filter: level (số trong positionCode)
        if (level != null) {
            dtoList = dtoList.stream()
                    .filter(d -> level.equals(d.getLevel()))
                    .collect(Collectors.toList());
        }

        // 11. Sort: bandOrder ASC, levelNumber ASC
        dtoList.sort(Comparator
                .comparing((ResJobTitleAssignStatusDTO d) -> d.getBandOrder() != null
                        ? d.getBandOrder()
                        : Integer.MAX_VALUE)
                .thenComparing(d -> d.getLevelNumber() != null
                        ? d.getLevelNumber()
                        : Integer.MAX_VALUE));

        // 12. Pagination thủ công (in-memory)
        long total = dtoList.size();
        int pageNumber = pageable.getPageNumber(); // 0-based
        int pageSize = pageable.getPageSize();
        int fromIndex = pageNumber * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, (int) total);

        List<ResJobTitleAssignStatusDTO> pagedResult = (fromIndex >= total)
                ? Collections.emptyList()
                : dtoList.subList(fromIndex, toIndex);

        // 13. Build ResultPaginationDTO
        ResultPaginationDTO rs = new ResultPaginationDTO();
        ResultPaginationDTO.Meta meta = new ResultPaginationDTO.Meta();

        meta.setPage(pageNumber + 1); // trả về 1-based cho frontend
        meta.setPageSize(pageSize);
        meta.setTotal(total);
        meta.setPages((int) Math.ceil((double) total / pageSize));

        rs.setMeta(meta);
        rs.setResult(pagedResult);

        return rs;
    }

    /*
     * =====================================================
     * CONVERT TO DTO
     * =====================================================
     */
    public ResDepartmentJobTitleDTO convertToResDTO(DepartmentJobTitle e) {

        ResDepartmentJobTitleDTO dto = new ResDepartmentJobTitleDTO();

        dto.setId(e.getId());
        dto.setActive(e.isActive());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        dto.setCreatedBy(e.getCreatedBy());
        dto.setUpdatedBy(e.getUpdatedBy());

        ResDepartmentJobTitleDTO.JobTitleInfo jt = new ResDepartmentJobTitleDTO.JobTitleInfo();
        jt.setId(e.getJobTitle().getId());
        jt.setNameVi(e.getJobTitle().getNameVi());
        jt.setNameEn(e.getJobTitle().getNameEn());

        if (e.getJobTitle().getPositionLevel() != null) {
            var pl = e.getJobTitle().getPositionLevel();
            jt.setPositionCode(pl.getCode());
            jt.setBand(pl.getCode().replaceAll("[0-9]", ""));
            jt.setLevel(Integer.parseInt(pl.getCode().replaceAll("[^0-9]", "")));
            jt.setBandOrder(pl.getBandOrder());
            jt.setLevelNumber(jt.getLevel());
        }

        dto.setJobTitle(jt);

        ResDepartmentJobTitleDTO.DepartmentInfo dep = new ResDepartmentJobTitleDTO.DepartmentInfo();
        dep.setId(e.getDepartment().getId());
        dep.setName(e.getDepartment().getName());
        dto.setDepartment(dep);

        return dto;
    }

    /*
     * =====================================================
     * BUILD VIRTUAL DEPARTMENT_JOB_TITLE
     * =====================================================
     */
    private DepartmentJobTitle buildVirtualDepartmentJobTitle(
            Department department,
            JobTitle jobTitle) {

        DepartmentJobTitle fake = new DepartmentJobTitle();
        fake.setDepartment(department);
        fake.setJobTitle(jobTitle);
        fake.setActive(false);
        return fake;
    }
}
