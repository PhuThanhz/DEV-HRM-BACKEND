package vn.system.app.modules.departmentobjective.service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;

import jakarta.transaction.Transactional;
import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.ScopeSpec;
import vn.system.app.common.util.SecurityUtil;
import vn.system.app.common.util.UserScopeContext;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.department.domain.Department;
import vn.system.app.modules.department.service.DepartmentService;
import vn.system.app.modules.section.domain.Section;
import vn.system.app.modules.section.service.SectionService;
import vn.system.app.modules.user.domain.User;
import vn.system.app.modules.user.repository.UserRepository;
import vn.system.app.modules.departmentobjective.domain.DepartmentMission;
import vn.system.app.modules.departmentobjective.domain.DepartmentMissionVersion;
import vn.system.app.modules.departmentobjective.domain.DepartmentObjective;
import vn.system.app.modules.departmentobjective.domain.request.ReqCreateDepartmentObjective;
import vn.system.app.modules.departmentobjective.domain.request.ReqPublishDepartmentObjective;
import vn.system.app.modules.departmentobjective.domain.response.ResDepartmentMissionTreeDTO;
import vn.system.app.modules.departmentobjective.domain.response.ResDepartmentMissionVersionDTO;
import vn.system.app.modules.departmentobjective.domain.response.ResDepartmentObjectiveDTO;
import vn.system.app.modules.departmentobjective.repository.DepartmentMissionRepository;
import vn.system.app.modules.departmentobjective.repository.DepartmentMissionVersionRepository;
import vn.system.app.modules.departmentobjective.repository.DepartmentObjectiveRepository;

@Service
public class DepartmentObjectiveService {

    private final DepartmentObjectiveRepository repository;
    private final DepartmentMissionRepository missionRepository;
    private final DepartmentMissionVersionRepository versionRepository;
    private final DepartmentService departmentService;
    private final SectionService sectionService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public DepartmentObjectiveService(
            DepartmentObjectiveRepository repository,
            DepartmentMissionRepository missionRepository,
            DepartmentMissionVersionRepository versionRepository,
            DepartmentService departmentService,
            SectionService sectionService,
            UserRepository userRepository,
            ObjectMapper objectMapper) {

        this.repository = repository;
        this.missionRepository = missionRepository;
        this.versionRepository = versionRepository;
        this.departmentService = departmentService;
        this.sectionService = sectionService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    /*
     * ==========================================
     * CREATE MISSION TREE
     * ==========================================
     */
    @Transactional
    public void handleCreate(ReqCreateDepartmentObjective req) {

        Department department = departmentService.fetchEntityById(req.getDepartmentId());
        departmentService.checkDepartmentScope(department);

        LocalDate issueDate = req.getIssueDate() != null
                ? req.getIssueDate()
                : LocalDate.now();

        /*
         * UPSERT TỪNG DÒNG
         * Load dòng hiện có, map theo id để phân biệt update / insert / delete.
         */
        List<DepartmentObjective> existing = repository.findByDepartmentId(department.getId());
        Map<Long, DepartmentObjective> existingById = existing.stream()
                .collect(Collectors.toMap(DepartmentObjective::getId, e -> e));

        Set<Long> keepIds = new HashSet<>();

        /*
         * OBJECTIVES
         */
        if (req.getObjectives() != null) {

            for (ReqCreateDepartmentObjective.ObjectiveItem o : req.getObjectives()) {

                DepartmentObjective entity = resolveEntity(o.getId(), existingById, keepIds);

                entity.setType("OBJECTIVE");
                entity.setContent(o.getContent());
                entity.setOrderNo(o.getOrderNo());
                entity.setIssueDate(issueDate);
                entity.setDepartment(department);
                entity.setSection(null);

                repository.save(entity);
            }
        }

        /*
         * TASKS
         * sectionId có thể null nếu phòng ban không có bộ phận
         */
        if (req.getTasks() != null) {

            for (ReqCreateDepartmentObjective.SectionTask st : req.getTasks()) {

                Section section = null;

                if (st.getSectionId() != null) {

                    section = sectionService.fetchEntityById(st.getSectionId());

                    if (!section.getDepartment().getId()
                            .equals(department.getId())) {

                        throw new IdInvalidException(
                                "Section không thuộc phòng ban này");
                    }
                }

                if (st.getItems() != null) {

                    for (ReqCreateDepartmentObjective.TaskItem t : st.getItems()) {

                        DepartmentObjective entity = resolveEntity(t.getId(), existingById, keepIds);

                        entity.setType("TASK");
                        entity.setContent(t.getContent());
                        entity.setOrderNo(t.getOrderNo());
                        entity.setIssueDate(issueDate);
                        entity.setDepartment(department);
                        entity.setSection(section); // null nếu không có bộ phận

                        repository.save(entity);
                    }
                }
            }
        }

        /*
         * AUTHORITIES
         */
        if (req.getAuthorities() != null) {

            for (ReqCreateDepartmentObjective.AuthorityItem a : req.getAuthorities()) {

                DepartmentObjective entity = resolveEntity(a.getId(), existingById, keepIds);

                entity.setType("AUTHORITY");
                entity.setContent(a.getContent());
                entity.setOrderNo(a.getOrderNo());
                entity.setIssueDate(issueDate);
                entity.setDepartment(department);
                entity.setSection(null);

                repository.save(entity);
            }
        }

        /*
         * XÓA các dòng cũ không còn trong payload
         */
        List<Long> toDelete = existingById.keySet().stream()
                .filter(id -> !keepIds.contains(id))
                .collect(Collectors.toList());

        if (!toDelete.isEmpty()) {
            repository.deleteAllById(toDelete);
        }

        /*
         * UPSERT HEADER (audit tổng) — cập nhật người/lúc sửa cuối
         */
        DepartmentMission mission = missionRepository.findByDepartmentId(department.getId())
                .orElseGet(() -> {
                    DepartmentMission m = new DepartmentMission();
                    m.setDepartment(department);
                    return m;
                });

        mission.setIssueDate(issueDate);
        mission.setStatus(req.getStatus() != null ? req.getStatus() : "DRAFT");
        mission.setLastUpdatedAt(Instant.now());
        mission.setLastUpdatedBy(SecurityUtil.getCurrentUserLogin().orElse(""));

        missionRepository.save(mission);
    }

    /*
     * Với id có sẵn và thuộc phòng ban → lấy entity cũ để update (giữ createdBy
     * gốc,
     * kích hoạt @PreUpdate). Ngược lại tạo mới (@PrePersist).
     */
    private DepartmentObjective resolveEntity(
            Long id,
            Map<Long, DepartmentObjective> existingById,
            Set<Long> keepIds) {

        if (id != null && existingById.containsKey(id)) {
            keepIds.add(id);
            return existingById.get(id);
        }

        return new DepartmentObjective();
    }

    /*
     * ==========================================
     * PUBLISH (Ban hành)
     * ==========================================
     */
    @Transactional
    public void handlePublish(ReqPublishDepartmentObjective req) {

        Long departmentId = req.getDepartmentId();
        Department department = departmentService.fetchEntityById(departmentId);
        departmentService.checkDepartmentScope(department);

        DepartmentMission mission = missionRepository.findByDepartmentId(departmentId)
                .orElseThrow(() -> new IdInvalidException(
                        "Chưa có nội dung để ban hành. Vui lòng lưu trước."));

        List<DepartmentObjective> items = repository.findByDepartmentId(departmentId);
        long objectiveCount = items.stream().filter(i -> "OBJECTIVE".equals(i.getType())).count();
        long taskCount = items.stream().filter(i -> "TASK".equals(i.getType())).count();
        long authorityCount = items.stream().filter(i -> "AUTHORITY".equals(i.getType())).count();

        if (objectiveCount + taskCount + authorityCount == 0) {
            throw new IdInvalidException("Chưa có nội dung để tạo version. Vui lòng thiết lập trước.");
        }

        String currentUser = SecurityUtil.getCurrentUserLogin().orElse("");
        Instant now = Instant.now();
        Integer nextVersion = (mission.getVersion() == null ? 0 : mission.getVersion()) + 1;

        mission.setStatus("PUBLISHED");
        mission.setIssuedAt(now);
        mission.setIssuedBy(currentUser);
        mission.setVersion(nextVersion);

        missionRepository.save(mission);

        DepartmentMissionVersion history = new DepartmentMissionVersion();
        history.setDepartment(department);
        history.setMission(mission);
        history.setVersion(nextVersion);
        history.setTitle(
                req.getTitle() != null && !req.getTitle().isBlank()
                        ? req.getTitle().trim()
                        : "Version " + nextVersion);
        history.setChangeSummary(req.getChangeSummary());
        history.setEffectiveDate(req.getEffectiveDate());
        history.setIssueDate(mission.getIssueDate());
        history.setObjectiveCount(objectiveCount);
        history.setTaskCount(taskCount);
        history.setAuthorityCount(authorityCount);
        history.setSnapshotJson(buildVersionSnapshot(department, mission, items));
        history.setCreatedBy(currentUser);
        history.setCreatedAt(now);

        versionRepository.save(history);
    }

    private String buildVersionSnapshot(Department department, DepartmentMission mission,
            List<DepartmentObjective> items) {
        List<DepartmentObjective> sortedItems = new ArrayList<>(items);
        sortedItems.sort(Comparator.comparing(DepartmentObjective::getOrderNo,
                Comparator.nullsLast(Comparator.naturalOrder())));

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("departmentId", department.getId());
        snapshot.put("departmentName", department.getName());
        snapshot.put("issueDate", mission.getIssueDate());

        snapshot.put("objectives", sortedItems.stream()
                .filter(i -> "OBJECTIVE".equals(i.getType()))
                .map(this::snapshotItem)
                .collect(Collectors.toList()));

        snapshot.put("generalTasks", sortedItems.stream()
                .filter(i -> "TASK".equals(i.getType()) && i.getSection() == null)
                .map(this::snapshotItem)
                .collect(Collectors.toList()));

        Map<Long, List<DepartmentObjective>> sectionTaskMap = sortedItems.stream()
                .filter(i -> "TASK".equals(i.getType()) && i.getSection() != null)
                .collect(Collectors.groupingBy(i -> i.getSection().getId(), LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> sectionTasks = new ArrayList<>();
        sectionTaskMap.forEach((sectionId, tasks) -> {
            Section section = tasks.get(0).getSection();
            Map<String, Object> sectionSnapshot = new LinkedHashMap<>();
            sectionSnapshot.put("sectionId", sectionId);
            sectionSnapshot.put("sectionName", section.getName());
            sectionSnapshot.put("tasks", tasks.stream().map(this::snapshotItem).collect(Collectors.toList()));
            sectionTasks.add(sectionSnapshot);
        });
        snapshot.put("sectionTasks", sectionTasks);

        snapshot.put("authorities", sortedItems.stream()
                .filter(i -> "AUTHORITY".equals(i.getType()))
                .map(this::snapshotItem)
                .collect(Collectors.toList()));

        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không thể tạo snapshot version", e);
        }
    }

    private Map<String, Object> snapshotItem(DepartmentObjective item) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", item.getId());
        data.put("content", item.getContent());
        data.put("orderNo", item.getOrderNo());
        return data;
    }

    /*
     * ==========================================
     * DELETE
     * ==========================================
     */
    public void handleDelete(Long id) {

        DepartmentObjective entity = fetchById(id);

        if (entity == null) {
            throw new IdInvalidException("Không tìm thấy id = " + id);
        }

        departmentService.checkDepartmentScope(entity.getDepartment());

        repository.deleteById(id);
    }

    /*
     * ==========================================
     * FIND BY ID
     * ==========================================
     */
    public DepartmentObjective fetchById(Long id) {

        Optional<DepartmentObjective> optional = repository.findById(id);

        if (optional.isPresent()) {
            departmentService.checkDepartmentScope(optional.get().getDepartment());
            return optional.get();
        }

        return null;
    }

    /*
     * ==========================================
     * FETCH SUMMARY
     * ==========================================
     */
    public List<vn.system.app.modules.departmentobjective.domain.response.ResDepartmentMissionSummaryDTO> fetchSummary() {
        UserScopeContext.UserScope scope = UserScopeContext.get();

        // Không xác định được phạm vi → không trả dữ liệu (an toàn hơn là coi như admin)
        if (scope == null) {
            return java.util.Collections.emptyList();
        }

        // Admin level → load ALL departments
        if (scope.isAdminLevel()) {
            return repository.getSummaryAll();
        }

        // Scoped user → filter by allowed company IDs
        java.util.Collection<Long> companyIds = scope.companyIds();
        if (companyIds == null || companyIds.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        return repository.getSummaryByCompanyIds(companyIds);
    }

    /*
     * ==========================================
     * FETCH ALL
     * ==========================================
     */
    public ResultPaginationDTO fetchAll(
            Specification<DepartmentObjective> spec,
            Pageable pageable) {

        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope != null && !scope.isAdminLevel()) {
            Specification<DepartmentObjective> scopeSpec = ScopeSpec.byCompanyScope("department.company.id");
            spec = Specification.where(spec).and(scopeSpec);
        }

        Page<DepartmentObjective> page = repository.findAll(spec, pageable);

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
                        .map(this::convert)
                        .collect(Collectors.toList()));

        return rs;
    }

    /*
     * ==========================================
     * CONVERT ENTITY -> DTO
     * ==========================================
     */
    public ResDepartmentObjectiveDTO convert(DepartmentObjective e) {

        ResDepartmentObjectiveDTO res = new ResDepartmentObjectiveDTO();

        res.setId(e.getId());
        res.setType(e.getType());
        res.setContent(e.getContent());
        res.setOrderNo(e.getOrderNo());
        res.setIssueDate(e.getIssueDate());
        res.setStatus(e.getStatus());
        res.setCreatedAt(e.getCreatedAt());
        res.setUpdatedAt(e.getUpdatedAt());
        res.setCreatedBy(e.getCreatedBy());
        res.setUpdatedBy(e.getUpdatedBy());

        ResDepartmentObjectiveDTO.DepartmentInfo d = new ResDepartmentObjectiveDTO.DepartmentInfo();

        d.setId(e.getDepartment().getId());
        d.setName(e.getDepartment().getName());

        res.setDepartment(d);

        ResDepartmentObjectiveDTO.CompanyInfo c = new ResDepartmentObjectiveDTO.CompanyInfo();

        c.setId(e.getDepartment().getCompany().getId());
        c.setName(e.getDepartment().getCompany().getName());

        res.setCompany(c);

        if (e.getSection() != null) {

            ResDepartmentObjectiveDTO.SectionInfo s = new ResDepartmentObjectiveDTO.SectionInfo();

            s.setId(e.getSection().getId());
            s.setName(e.getSection().getName());

            res.setSection(s);
        }

        return res;
    }

    /*
     * ==========================================
     * LOAD MISSION TREE
     * ==========================================
     */
    public ResDepartmentMissionTreeDTO fetchMissionTree(Long departmentId) {

        Department department = departmentService.fetchEntityById(departmentId);
        departmentService.checkDepartmentScope(department);

        List<DepartmentObjective> list = repository.findByDepartmentId(departmentId);

        // Sắp xếp theo orderNo
        list.sort(Comparator.comparing(DepartmentObjective::getOrderNo,
                Comparator.nullsLast(Comparator.naturalOrder())));

        boolean hasSections = sectionService.existsByDepartmentId(departmentId);

        ResDepartmentMissionTreeDTO res = new ResDepartmentMissionTreeDTO();

        ResDepartmentMissionTreeDTO.DepartmentInfo d = new ResDepartmentMissionTreeDTO.DepartmentInfo();

        d.setId(department.getId());
        d.setName(department.getName());

        res.setDepartment(d);
        res.setHasSections(hasSections);

        /*
         * HEADER (audit tổng) — null nếu phòng ban chưa từng lưu
         */
        DepartmentMission mission = missionRepository.findByDepartmentId(departmentId).orElse(null);

        if (mission != null) {
            res.setStatus(mission.getStatus());
            res.setIssuedBy(mission.getIssuedBy());
            res.setIssuedByName(resolveName(mission.getIssuedBy()));
            res.setIssuedAt(mission.getIssuedAt());
            res.setLastUpdatedBy(mission.getLastUpdatedBy());
            res.setLastUpdatedByName(resolveName(mission.getLastUpdatedBy()));
            res.setLastUpdatedAt(mission.getLastUpdatedAt());
            res.setVersion(mission.getVersion());
            res.setIssueDate(mission.getIssueDate());
        } else {
            res.setIssueDate(
                    list.isEmpty()
                            ? null
                            : list.get(0).getIssueDate());
        }

        /*
         * OBJECTIVES
         */
        res.setObjectives(
                list.stream()
                        .filter(i -> "OBJECTIVE".equals(i.getType()))
                        .map(i -> {

                            ResDepartmentMissionTreeDTO.ObjectiveItem o = new ResDepartmentMissionTreeDTO.ObjectiveItem();

                            o.setId(i.getId());
                            o.setContent(i.getContent());
                            o.setOrderNo(i.getOrderNo());
                            o.setCreatedBy(i.getCreatedBy());
                            o.setUpdatedBy(i.getUpdatedBy());
                            o.setCreatedAt(i.getCreatedAt());
                            o.setUpdatedAt(i.getUpdatedAt());

                            return o;

                        })
                        .collect(Collectors.toList()));

        /*
         * TASKS - PHÂN LOẠI NHIỆM VỤ
         */
        // 1. Nhiệm vụ chung (không thuộc bộ phận nào)
        List<ResDepartmentMissionTreeDTO.TaskItem> generalTasks = list.stream()
                .filter(i -> "TASK".equals(i.getType()) && i.getSection() == null)
                .map(this::mapToTaskItem)
                .collect(Collectors.toList());

        res.setGeneralTasks(generalTasks);

        // 2. Nhiệm vụ theo bộ phận (nếu có bộ phận)
        if (hasSections) {

            // Nhóm theo Section ID để an toàn (tránh lỗi hashCode/equals của Entity)
            Map<Long, List<DepartmentObjective>> map = list.stream()
                    .filter(i -> "TASK".equals(i.getType()) && i.getSection() != null)
                    .collect(Collectors.groupingBy(i -> i.getSection().getId()));

            List<ResDepartmentMissionTreeDTO.SectionTask> sectionTasks = new ArrayList<>();

            map.forEach((sectionId, tasks) -> {

                ResDepartmentMissionTreeDTO.SectionTask st = new ResDepartmentMissionTreeDTO.SectionTask();

                // Lấy thông tin section từ entity đầu tiên trong nhóm
                Section section = tasks.get(0).getSection();
                st.setSectionId(sectionId);
                st.setSectionName(section.getName());

                st.setTasks(
                        tasks.stream()
                                .map(this::mapToTaskItem)
                                .collect(Collectors.toList()));

                sectionTasks.add(st);
            });

            // Sắp xếp các SectionTask theo tên bộ phận hoặc ID nếu cần
            sectionTasks.sort(Comparator.comparing(ResDepartmentMissionTreeDTO.SectionTask::getSectionName));

            res.setTasks(sectionTasks);
        }

        /*
         * AUTHORITIES
         */
        res.setAuthorities(
                list.stream()
                        .filter(i -> "AUTHORITY".equals(i.getType()))
                        .map(i -> {

                            ResDepartmentMissionTreeDTO.AuthorityItem a = new ResDepartmentMissionTreeDTO.AuthorityItem();

                            a.setId(i.getId());
                            a.setContent(i.getContent());
                            a.setOrderNo(i.getOrderNo());
                            a.setCreatedBy(i.getCreatedBy());
                            a.setUpdatedBy(i.getUpdatedBy());
                            a.setCreatedAt(i.getCreatedAt());
                            a.setUpdatedAt(i.getUpdatedAt());

                            return a;

                        })
                        .collect(Collectors.toList()));

        return res;
    }

    public List<ResDepartmentMissionVersionDTO> fetchVersions(Long departmentId) {
        Department department = departmentService.fetchEntityById(departmentId);
        departmentService.checkDepartmentScope(department);

        return versionRepository.findByDepartmentIdOrderByVersionDesc(departmentId)
                .stream()
                .map(this::convertVersion)
                .collect(Collectors.toList());
    }

    private ResDepartmentMissionVersionDTO convertVersion(DepartmentMissionVersion version) {
        ResDepartmentMissionVersionDTO dto = new ResDepartmentMissionVersionDTO();
        dto.setId(version.getId());
        dto.setVersion(version.getVersion());
        dto.setTitle(version.getTitle());
        dto.setChangeSummary(version.getChangeSummary());
        dto.setEffectiveDate(version.getEffectiveDate());
        dto.setIssueDate(version.getIssueDate());
        dto.setObjectiveCount(version.getObjectiveCount());
        dto.setTaskCount(version.getTaskCount());
        dto.setAuthorityCount(version.getAuthorityCount());
        dto.setSnapshotJson(version.getSnapshotJson());
        dto.setCreatedBy(version.getCreatedBy());
        dto.setCreatedByName(resolveName(version.getCreatedBy()));
        dto.setCreatedAt(version.getCreatedAt());
        return dto;
    }

    private ResDepartmentMissionTreeDTO.TaskItem mapToTaskItem(DepartmentObjective t) {
        ResDepartmentMissionTreeDTO.TaskItem ti = new ResDepartmentMissionTreeDTO.TaskItem();
        ti.setId(t.getId());
        ti.setContent(t.getContent());
        ti.setOrderNo(t.getOrderNo());
        ti.setCreatedBy(t.getCreatedBy());
        ti.setUpdatedBy(t.getUpdatedBy());
        ti.setCreatedAt(t.getCreatedAt());
        ti.setUpdatedAt(t.getUpdatedAt());
        return ti;
    }

    /*
     * Resolve login (email) → họ tên hiển thị. Không tìm thấy thì trả về chính
     * login.
     */
    private String resolveName(String login) {
        if (login == null || login.isBlank()) {
            return login;
        }
        User user = userRepository.findByEmail(login);
        if (user != null && user.getName() != null && !user.getName().isBlank()) {
            return user.getName();
        }
        return login;
    }
}
