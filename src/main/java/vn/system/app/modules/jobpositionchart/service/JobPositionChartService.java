package vn.system.app.modules.jobpositionchart.service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.UserScopeContext;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.department.domain.Department;
import vn.system.app.modules.department.repository.DepartmentRepository;
import vn.system.app.modules.department.service.DepartmentService;
import vn.system.app.modules.jobpositionchart.domain.JobPositionChart;
import vn.system.app.modules.jobpositionchart.domain.request.ReqCreateJobPositionChartDTO;
import vn.system.app.modules.jobpositionchart.domain.request.ReqUpdateJobPositionChartDTO;
import vn.system.app.modules.jobpositionchart.domain.response.ResJobPositionChartDTO;
import vn.system.app.modules.jobpositionchart.repository.JobPositionChartRepository;
import vn.system.app.modules.jobpositionnode.repository.JobPositionNodeRepository;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobPositionChartService {

    private final JobPositionChartRepository chartRepository;
    private final DepartmentRepository departmentRepository;
    private final DepartmentService departmentService;
    private final JobPositionNodeRepository nodeRepository;

    public JobPositionChartService(
            JobPositionChartRepository chartRepository,
            DepartmentRepository departmentRepository,
            DepartmentService departmentService,
            JobPositionNodeRepository nodeRepository) {
        this.chartRepository = chartRepository;
        this.departmentRepository = departmentRepository;
        this.departmentService = departmentService;
        this.nodeRepository = nodeRepository;
    }

    /*
     * ==================================
     * VALIDATE SCOPE (IDOR Protection)
     * Chart COMPANY-type: so companyId với scope.companyIds()
     * Chart DEPARTMENT-type: resolve Department rồi tái dùng
     * DepartmentService.checkDepartmentScope (xử lý cả company-level lẫn
     * department-level)
     * ==================================
     */
    public void validateScope(JobPositionChart chart) {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope == null || scope.isSuperAdmin() || scope.isAdminLevel()) {
            return;
        }

        if ("DEPARTMENT".equals(chart.getChartType()) && chart.getDepartmentId() != null) {
            Department dept = departmentRepository.findById(chart.getDepartmentId()).orElse(null);
            if (dept == null) {
                throw new IdInvalidException("Bạn không có quyền thao tác trên sơ đồ này");
            }
            departmentService.checkDepartmentScope(dept);
            return;
        }

        if (chart.getCompanyId() != null) {
            if (scope.companyIds() == null || !scope.companyIds().contains(chart.getCompanyId())) {
                throw new IdInvalidException("Bạn không có quyền thao tác trên sơ đồ này");
            }
            return;
        }

        throw new IdInvalidException("Bạn không có quyền thao tác trên sơ đồ này");
    }

    /*
     * ==========================
     * CREATE CHART
     * ==========================
     */
    public JobPositionChart handleCreateChart(ReqCreateJobPositionChartDTO req) {
        JobPositionChart chart = new JobPositionChart();
        chart.setName(req.getName());
        chart.setChartType(req.getChartType());
        chart.setCompanyId(req.getCompanyId());
        chart.setDepartmentId(req.getDepartmentId());

        validateScope(chart);
        return this.chartRepository.save(chart);
    }

    /*
     * ==========================
     * DELETE CHART
     * ==========================
     */
    @Transactional
    public void handleDeleteChart(Long id) {
        JobPositionChart chart = this.chartRepository.findById(id)
                .orElseThrow(() -> new IdInvalidException("Sơ đồ với id = " + id + " không tồn tại"));
        validateScope(chart);

        // 1. Xóa tất cả các node thuộc sơ đồ này trước
        this.nodeRepository.deleteByChartId(id);

        // 2. Xóa sơ đồ
        this.chartRepository.deleteById(id);
    }

    /*
     * ==========================
     * FIND BY ID
     * ==========================
     */
    public JobPositionChart fetchChartById(Long id) {
        Optional<JobPositionChart> chartOptional = this.chartRepository.findById(id);
        chartOptional.ifPresent(this::validateScope);
        return chartOptional.orElse(null);
    }

    /*
     * ==========================
     * UPDATE CHART
     * ==========================
     */
    public JobPositionChart handleUpdateChart(ReqUpdateJobPositionChartDTO req) {

        Optional<JobPositionChart> currentOptional = this.chartRepository.findById(req.getId());
        JobPositionChart current = currentOptional.orElse(null);

        if (current != null) {
            validateScope(current);

            current.setName(req.getName());
            current.setChartType(req.getChartType());
            current.setCompanyId(req.getCompanyId());
            current.setDepartmentId(req.getDepartmentId());
            validateScope(current);

            current = this.chartRepository.save(current);
        }

        return current;
    }

    /*
     * ==========================
     * FETCH ALL WITH PAGINATION
     * ==========================
     */
    public ResultPaginationDTO fetchAllCharts(Specification<JobPositionChart> spec, Pageable pageable) {

        // ── ADMIN_SUB_2: filter theo company ──────────────
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope != null && !scope.isAdminLevel()) {

            if (scope.companyIds().isEmpty()) {
                ResultPaginationDTO rs = new ResultPaginationDTO();
                ResultPaginationDTO.Meta mt = new ResultPaginationDTO.Meta();
                mt.setPage(pageable.getPageNumber() + 1);
                mt.setPageSize(pageable.getPageSize());
                mt.setPages(0);
                mt.setTotal(0);
                rs.setMeta(mt);
                rs.setResult(List.of());
                return rs;
            }

            // Lấy tất cả departmentId thuộc company của user
            List<Long> deptIds = departmentRepository
                    .findByCompany_IdIn(scope.companyIds())
                    .stream()
                    .map(d -> d.getId())
                    .toList();

            Specification<JobPositionChart> scopeSpec = (root, query, cb) -> cb.or(
                    // chart gắn thẳng vào company
                    root.get("companyId").in(scope.companyIds()),
                    // chart gắn vào department thuộc company
                    deptIds.isEmpty()
                            ? cb.disjunction()
                            : root.get("departmentId").in(deptIds));

            spec = Specification.where(spec).and(scopeSpec);
        }
        // ── HẾT FILTER ────────────────────────────────────

        Page<JobPositionChart> page = this.chartRepository.findAll(spec, pageable);

        ResultPaginationDTO rs = new ResultPaginationDTO();
        ResultPaginationDTO.Meta mt = new ResultPaginationDTO.Meta();

        mt.setPage(pageable.getPageNumber() + 1);
        mt.setPageSize(pageable.getPageSize());
        mt.setPages(page.getTotalPages());
        mt.setTotal(page.getTotalElements());

        rs.setMeta(mt);

        List<ResJobPositionChartDTO> result = page.getContent()
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());

        rs.setResult(result);

        return rs;
    }

    /*
     * ==========================
     * CONVERT DTO
     * ==========================
     */
    public ResJobPositionChartDTO convertToDTO(JobPositionChart chart) {

        ResJobPositionChartDTO res = new ResJobPositionChartDTO();

        res.setId(chart.getId());
        res.setName(chart.getName());
        res.setChartType(chart.getChartType());
        res.setCompanyId(chart.getCompanyId());
        res.setDepartmentId(chart.getDepartmentId());

        return res;
    }
}