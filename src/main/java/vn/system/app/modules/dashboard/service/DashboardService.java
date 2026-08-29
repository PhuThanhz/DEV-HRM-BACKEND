package vn.system.app.modules.dashboard.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Comparator;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.UserScopeContext;
import vn.system.app.modules.company.repository.CompanyRepository;
import vn.system.app.modules.department.domain.Department;
import vn.system.app.modules.department.repository.DepartmentRepository;
import vn.system.app.modules.section.repository.SectionRepository;
import vn.system.app.modules.dashboard.domain.response.DashboardSummaryDTO;
import vn.system.app.modules.dashboard.domain.response.DepartmentCompletenessDTO;
import vn.system.app.modules.dashboard.domain.response.DepartmentCompletenessProjection;
import vn.system.app.modules.dashboard.domain.response.DepartmentCompletenessOverviewDTO;
import vn.system.app.modules.departmentjobtitle.domain.DepartmentJobTitle;
import vn.system.app.modules.jobpositionchart.domain.JobPositionChart;
import vn.system.app.modules.departmentobjective.domain.DepartmentObjective;
import vn.system.app.modules.departmentprocedure.domain.DepartmentProcedure;
import vn.system.app.modules.permissioncategory.domain.PermissionCategory;
import vn.system.app.modules.careerpath.domain.CareerPath;
import vn.system.app.modules.salarygrade.domain.SalaryGrade;

@Service
public class DashboardService {

        private final CompanyRepository companyRepository;
        private final DepartmentRepository departmentRepository;
        private final SectionRepository sectionRepository;

        public DashboardService(
                        CompanyRepository companyRepository,
                        DepartmentRepository departmentRepository,
                        SectionRepository sectionRepository) {

                this.companyRepository = companyRepository;
                this.departmentRepository = departmentRepository;
                this.sectionRepository = sectionRepository;
        }

        /*
         * ====================================================
         * DASHBOARD SUMMARY (KPI)
         * ====================================================
         */
        public DashboardSummaryDTO getSummary() {

                UserScopeContext.UserScope scope = UserScopeContext.get();

                long totalCompany;
                long totalDepartment;
                long totalSection;

                // SUPER_ADMIN và ADMIN_SUB_1 đều thấy toàn bộ hệ thống
                if (scope == null) {
                        return new DashboardSummaryDTO(0, 0, 0);
                } else if (scope.isSuperAdmin() || scope.isAdminLevel()) {
                        totalCompany = companyRepository.count();
                        totalDepartment = departmentRepository.count();
                        totalSection = sectionRepository.count();
                } else if (scope.isDepartmentLevel()) {
                        var departmentIds = scope.departmentIds();
                        if (departmentIds == null || departmentIds.isEmpty()) {
                                return new DashboardSummaryDTO(0, 0, 0);
                        }
                        totalCompany = scope.companyIds() != null ? scope.companyIds().size() : 0;
                        totalDepartment = departmentIds.size();
                        totalSection = sectionRepository.countByDepartment_IdIn(departmentIds);
                } else {
                        var companyIds = scope.companyIds();
                        if (companyIds.isEmpty()) {
                                return new DashboardSummaryDTO(0, 0, 0);
                        }
                        totalCompany = companyIds.size();
                        totalDepartment = departmentRepository.countByCompany_IdIn(companyIds);
                        totalSection = sectionRepository.countByDepartment_Company_IdIn(companyIds);
                }

                return new DashboardSummaryDTO(totalCompany, totalDepartment, totalSection);
        }

        /*
         * ====================================================
         * DEPARTMENT COMPLETENESS
         * ====================================================
         */
        public ResultPaginationDTO getDepartmentCompleteness(String search, String companyName,
                        String status, String missing, Pageable pageable) {

                Specification<Department> spec = Specification
                                .where(scopeSpecification(UserScopeContext.get()))
                                .and(searchSpecification(search))
                                .and(companySpecification(companyName))
                                .and(statusSpecification(status))
                                .and(missingSpecification(missing));

                Page<Department> departmentPage = departmentRepository.findAll(spec, pageable);
                List<Department> departments = departmentPage.getContent();
                List<Long> deptIds = departments.stream().map(Department::getId).collect(Collectors.toList());
                Map<Long, DepartmentCompletenessDTO> completenessById = deptIds.isEmpty()
                                ? Map.of()
                                : departmentRepository.findCompletenessOverviewByIdIn(deptIds).stream()
                                                .map(this::buildCompleteness)
                                                .collect(Collectors.toMap(
                                                                DepartmentCompletenessDTO::getDepartmentId,
                                                                row -> row));
                List<DepartmentCompletenessDTO> result = departments.stream()
                                .map(department -> completenessById.get(department.getId()))
                                .toList();

                ResultPaginationDTO response = new ResultPaginationDTO();
                ResultPaginationDTO.Meta meta = new ResultPaginationDTO.Meta();
                meta.setPage(departmentPage.getNumber() + 1);
                meta.setPageSize(departmentPage.getSize());
                meta.setPages(departmentPage.getTotalPages());
                meta.setTotal(departmentPage.getTotalElements());
                response.setMeta(meta);
                response.setResult(result);
                return response;
        }

        public DepartmentCompletenessOverviewDTO getDepartmentCompletenessOverview() {
                List<DepartmentCompletenessDTO> rows = findScopedCompletenessOverview(UserScopeContext.get()).stream()
                                .map(this::buildCompleteness)
                                .toList();

                long full = rows.stream().filter(row -> row.getScore() == 7).count();
                long empty = rows.stream().filter(row -> row.getScore() == 0).count();
                List<DepartmentCompletenessDTO> topMissing = rows.stream()
                                .filter(row -> row.getScore() < 7)
                                .sorted(Comparator.comparingInt(DepartmentCompletenessDTO::getScore)
                                                .thenComparing(DepartmentCompletenessDTO::getDepartmentName,
                                                                String.CASE_INSENSITIVE_ORDER))
                                .limit(5)
                                .toList();

                return new DepartmentCompletenessOverviewDTO(
                                rows.size(), full, rows.size() - full - empty, empty,
                                rows.stream().filter(row -> !row.isOrgChart()).count(),
                                rows.stream().filter(row -> !row.isObjectives()).count(),
                                rows.stream().filter(row -> !row.isDepartmentProcedure()).count(),
                                rows.stream().filter(row -> !row.isPermissions()).count(),
                                rows.stream().filter(row -> !row.isCareerPath()).count(),
                                rows.stream().filter(row -> !row.isSalaryGrade()).count(),
                                rows.stream().filter(row -> !row.isJobTitleMap()).count(),
                                topMissing);
        }

        private List<DepartmentCompletenessProjection> findScopedCompletenessOverview(UserScopeContext.UserScope scope) {
                if (scope == null) {
                        return List.of();
                }
                if (scope.isSuperAdmin() || scope.isAdminLevel()) {
                        return departmentRepository.findCompletenessOverviewAll();
                }
                if (scope.isDepartmentLevel()) {
                        var departmentIds = scope.departmentIds();
                        return departmentIds == null || departmentIds.isEmpty()
                                        ? List.of()
                                        : departmentRepository.findCompletenessOverviewByIdIn(departmentIds);
                }
                var companyIds = scope.companyIds();
                return companyIds == null || companyIds.isEmpty()
                                ? List.of()
                                : departmentRepository.findCompletenessOverviewByCompanyIdIn(companyIds);
        }

        private Specification<Department> scopeSpecification(UserScopeContext.UserScope scope) {
                return (root, query, cb) -> {
                        if (scope == null) {
                                return cb.disjunction();
                        }
                        if (scope.isSuperAdmin() || scope.isAdminLevel()) {
                                return cb.conjunction();
                        }
                        if (scope.isDepartmentLevel()) {
                                var departmentIds = scope.departmentIds();
                                return departmentIds == null || departmentIds.isEmpty()
                                                ? cb.disjunction()
                                                : root.get("id").in(departmentIds);
                        }
                        var companyIds = scope.companyIds();
                        return companyIds == null || companyIds.isEmpty()
                                        ? cb.disjunction()
                                        : root.get("company").get("id").in(companyIds);
                };
        }

        private Specification<Department> searchSpecification(String search) {
                if (isBlank(search)) {
                        return null;
                }
                String keyword = "%" + normalize(search) + "%";
                return (root, query, cb) -> cb.or(
                                cb.like(cb.lower(root.get("name")), keyword),
                                cb.like(cb.lower(root.get("code")), keyword),
                                cb.like(cb.lower(root.get("company").get("name")), keyword));
        }

        private Specification<Department> companySpecification(String companyName) {
                if (isBlank(companyName)) {
                        return null;
                }
                return (root, query, cb) -> cb.equal(
                                cb.lower(root.get("company").get("name")), normalize(companyName));
        }

        private Specification<Department> statusSpecification(String status) {
                if (isBlank(status) || "all".equalsIgnoreCase(status)) {
                        return null;
                }
                return (root, query, cb) -> {
                        Predicate[] present = completenessPredicates(root, query, cb);
                        Predicate full = cb.and(present);
                        Predicate empty = cb.and(java.util.Arrays.stream(present)
                                        .map(cb::not)
                                        .toArray(Predicate[]::new));
                        return switch (status.toLowerCase()) {
                                case "full" -> full;
                                case "partial" -> cb.and(cb.not(full), cb.not(empty));
                                case "empty" -> empty;
                                default -> cb.conjunction();
                        };
                };
        }

        private Specification<Department> missingSpecification(String missing) {
                if (isBlank(missing) || "all".equalsIgnoreCase(missing)) {
                        return null;
                }
                return (root, query, cb) -> {
                        Predicate[] present = completenessPredicates(root, query, cb);
                        int index = switch (missing) {
                                case "orgChart" -> 0;
                                case "objectives" -> 1;
                                case "departmentProcedure" -> 2;
                                case "permissions" -> 3;
                                case "careerPath" -> 4;
                                case "salaryGrade" -> 5;
                                case "jobTitleMap" -> 6;
                                default -> -1;
                        };
                        return index < 0 ? cb.conjunction() : cb.not(present[index]);
                };
        }

        private Predicate[] completenessPredicates(Root<Department> department, jakarta.persistence.criteria.CriteriaQuery<?> query,
                        jakarta.persistence.criteria.CriteriaBuilder cb) {
                return new Predicate[] {
                                existsByScalarDepartmentId(query, cb, department, JobPositionChart.class, "departmentId", false),
                                existsByDepartmentRelation(query, cb, department, DepartmentObjective.class, "department", false),
                                existsByProcedureDepartment(query, cb, department),
                                existsByDepartmentRelation(query, cb, department, PermissionCategory.class, "department", true),
                                existsByDepartmentRelation(query, cb, department, CareerPath.class, "department", true),
                                existsBySalaryGrade(query, cb, department),
                                existsByDepartmentRelation(query, cb, department, DepartmentJobTitle.class, "department", true)
                };
        }

        private <T> Predicate existsByDepartmentRelation(jakarta.persistence.criteria.CriteriaQuery<?> query,
                        jakarta.persistence.criteria.CriteriaBuilder cb, Root<Department> department,
                        Class<T> entityType, String relation, boolean activeOnly) {
                Subquery<Long> subquery = query.subquery(Long.class);
                Root<T> item = subquery.from(entityType);
                Predicate departmentMatch = cb.equal(item.get(relation).get("id"), department.get("id"));
                subquery.select(cb.literal(1L));
                subquery.where(activeOnly ? cb.and(departmentMatch, cb.isTrue(item.get("active"))) : departmentMatch);
                return cb.exists(subquery);
        }

        private <T> Predicate existsByScalarDepartmentId(jakarta.persistence.criteria.CriteriaQuery<?> query,
                        jakarta.persistence.criteria.CriteriaBuilder cb, Root<Department> department,
                        Class<T> entityType, String departmentIdField, boolean activeOnly) {
                Subquery<Long> subquery = query.subquery(Long.class);
                Root<T> item = subquery.from(entityType);
                Predicate departmentMatch = cb.equal(item.get(departmentIdField), department.get("id"));
                subquery.select(cb.literal(1L));
                subquery.where(activeOnly ? cb.and(departmentMatch, cb.isTrue(item.get("active"))) : departmentMatch);
                return cb.exists(subquery);
        }

        private Predicate existsByProcedureDepartment(jakarta.persistence.criteria.CriteriaQuery<?> query,
                        jakarta.persistence.criteria.CriteriaBuilder cb, Root<Department> department) {
                Subquery<Long> subquery = query.subquery(Long.class);
                Root<DepartmentProcedure> procedure = subquery.from(DepartmentProcedure.class);
                Join<DepartmentProcedure, Department> mappedDepartment = procedure.join("departments");
                subquery.select(cb.literal(1L));
                subquery.where(cb.equal(mappedDepartment.get("id"), department.get("id")));
                return cb.exists(subquery);
        }

        private Predicate existsBySalaryGrade(jakarta.persistence.criteria.CriteriaQuery<?> query,
                        jakarta.persistence.criteria.CriteriaBuilder cb, Root<Department> department) {
                Subquery<Long> subquery = query.subquery(Long.class);
                Root<SalaryGrade> salaryGrade = subquery.from(SalaryGrade.class);
                subquery.select(cb.literal(1L));
                subquery.where(
                                cb.equal(salaryGrade.get("contextType"), "DEPARTMENT"),
                                cb.equal(salaryGrade.get("contextId"), department.get("id")),
                                cb.isTrue(salaryGrade.get("active")));
                return cb.exists(subquery);
        }

        private boolean isBlank(String value) {
                return value == null || value.trim().isEmpty();
        }

        private String normalize(String value) {
                return value == null ? "" : value.trim().toLowerCase();
        }

        private DepartmentCompletenessDTO buildCompleteness(DepartmentCompletenessProjection dept) {
                boolean orgChart = isPresent(dept.getOrgChart());
                boolean hasObjectives = isPresent(dept.getObjectives());
                boolean departmentProcedure = isPresent(dept.getDepartmentProcedure());
                boolean hasPermissions = isPresent(dept.getPermissions());
                boolean careerPath = isPresent(dept.getCareerPath());
                boolean salaryGrade = isPresent(dept.getSalaryGrade());
                boolean jobTitleMap = isPresent(dept.getJobTitleMap());
                int score = (orgChart ? 1 : 0)
                                + (hasObjectives ? 1 : 0)
                                + (departmentProcedure ? 1 : 0)
                                + (hasPermissions ? 1 : 0)
                                + (careerPath ? 1 : 0)
                                + (salaryGrade ? 1 : 0)
                                + (jobTitleMap ? 1 : 0);

                return new DepartmentCompletenessDTO(dept.getDepartmentId(), dept.getDepartmentName(), dept.getCompanyName(),
                                orgChart, hasObjectives, departmentProcedure, hasPermissions, careerPath,
                                salaryGrade, jobTitleMap, score);
        }

        private boolean isPresent(Integer value) {
                return value != null && value != 0;
        }
}
