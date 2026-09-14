package vn.system.app.modules.salarygrade.service;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.UserScopeContext;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.common.util.error.PermissionException;
import vn.system.app.modules.companyjobtitle.repository.CompanyJobTitleRepository;
import vn.system.app.modules.departmentjobtitle.repository.DepartmentJobTitleRepository;
import vn.system.app.modules.sectionjobtitle.repository.SectionJobTitleRepository;
import vn.system.app.modules.salarygrade.domain.SalaryGrade;
import vn.system.app.modules.salarygrade.domain.request.ReqCreateSalaryGradeDTO;
import vn.system.app.modules.salarygrade.domain.response.ResSalaryGradeDTO;
import vn.system.app.modules.salarygrade.repository.SalaryGradeRepository;

@Service
public class SalaryGradeService {

    private final SalaryGradeRepository salaryGradeRepo;
    private final CompanyJobTitleRepository companyJobTitleRepo;
    private final DepartmentJobTitleRepository departmentJobTitleRepo;
    private final SectionJobTitleRepository sectionJobTitleRepo;

    @PersistenceContext
    private EntityManager entityManager;

    public SalaryGradeService(
            SalaryGradeRepository salaryGradeRepo,
            CompanyJobTitleRepository companyJobTitleRepo,
            DepartmentJobTitleRepository departmentJobTitleRepo,
            SectionJobTitleRepository sectionJobTitleRepo) {

        this.salaryGradeRepo = salaryGradeRepo;
        this.companyJobTitleRepo = companyJobTitleRepo;
        this.departmentJobTitleRepo = departmentJobTitleRepo;
        this.sectionJobTitleRepo = sectionJobTitleRepo;
    }

    // CREATE
    @Transactional
    public ResSalaryGradeDTO handleCreate(ReqCreateSalaryGradeDTO req) {
        validateContext(req.getContextType(), req.getContextId());
        checkContextScope(req.getContextType(), req.getContextId());

        if (req.getGradeLevel() == null || req.getGradeLevel() <= 0) {
            throw new IdInvalidException("GradeLevel phải là số nguyên dương");
        }

        boolean existed = salaryGradeRepo.existsByContextTypeAndContextIdAndGradeLevel(
                req.getContextType(), req.getContextId(), req.getGradeLevel());

        if (existed) {
            throw new IdInvalidException("Bậc lương " + req.getGradeLevel() + " đã tồn tại");
        }

        SalaryGrade sg = new SalaryGrade();
        sg.setContextType(req.getContextType());
        sg.setContextId(req.getContextId());
        sg.setGradeLevel(req.getGradeLevel());

        sg = salaryGradeRepo.save(sg);
        return convertToResDTO(sg);
    }

    // DELETE SOFT
    @Transactional
    public void handleDelete(Long id) {
        SalaryGrade sg = fetchEntityById(id);
        checkContextScope(sg.getContextType(), sg.getContextId());
        if (!sg.isActive()) {
            throw new IdInvalidException("Bậc lương đã bị vô hiệu hóa");
        }
        sg.setActive(false);
        salaryGradeRepo.save(sg);
    }

    // FETCH ONE
    public SalaryGrade fetchEntityById(Long id) {
        return salaryGradeRepo.findById(id)
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy bậc lương ID = " + id));
    }

    // FETCH BY CONTEXT - SỬ DỤNG NATIVE JPQL ĐỂ TRÁNH BUG SPECIFICATION
    public ResultPaginationDTO fetchByContext(
            String contextType,
            Long contextId,
            Pageable pageable) {



        validateContext(contextType, contextId);
        checkContextScope(contextType, contextId);

        // JPQL native - an toàn và rõ ràng
        String jpql = "SELECT s FROM SalaryGrade s " +
                "WHERE s.contextType = :type " +
                "AND s.contextId = :id " +
                "AND s.active = true " +
                "ORDER BY s.gradeLevel ASC";

        TypedQuery<SalaryGrade> query = entityManager.createQuery(jpql, SalaryGrade.class);
        query.setParameter("type", contextType);
        query.setParameter("id", contextId);
        query.setFirstResult((int) pageable.getOffset());
        query.setMaxResults(pageable.getPageSize());

        List<SalaryGrade> content = query.getResultList();

        // Tính total count
        String countJpql = "SELECT COUNT(s) FROM SalaryGrade s " +
                "WHERE s.contextType = :type " +
                "AND s.contextId = :id " +
                "AND s.active = true";

        TypedQuery<Long> countQuery = entityManager.createQuery(countJpql, Long.class);
        countQuery.setParameter("type", contextType);
        countQuery.setParameter("id", contextId);
        Long total = countQuery.getSingleResult();



        ResultPaginationDTO rs = new ResultPaginationDTO();
        ResultPaginationDTO.Meta meta = new ResultPaginationDTO.Meta();

        meta.setPage(pageable.getPageNumber() + 1);
        meta.setPageSize(pageable.getPageSize());
        meta.setPages((int) Math.ceil(total.doubleValue() / pageable.getPageSize()));
        meta.setTotal(total);
        rs.setMeta(meta);

        rs.setResult(
                content.stream()
                        .map(this::convertToResDTO)
                        .collect(Collectors.toList()));

        return rs;
    }

    // FETCH ALL (ADMIN)
    public ResultPaginationDTO fetchAll(
            Specification<SalaryGrade> spec,
            Pageable pageable) {

        Page<SalaryGrade> page = salaryGradeRepo.findAll(spec, pageable);

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

    // SCOPE CHECK (IDOR protection — contextId trỏ tới company_job_titles /
    // department_job_titles / section_job_titles, cần resolve về company/department
    // thực tế rồi so với phạm vi người gọi trước khi cho đọc/sửa dữ liệu lương)
    public void checkContextScope(String contextType, Long contextId) {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope == null || scope.isSuperAdmin() || scope.isAdminLevel()) {
            return;
        }

        Long companyId = null;
        Long departmentId = null;

        if ("COMPANY".equals(contextType)) {
            companyId = companyJobTitleRepo.findById(contextId)
                    .map(cjt -> cjt.getCompany().getId())
                    .orElse(null);
        } else if ("DEPARTMENT".equals(contextType)) {
            var djt = departmentJobTitleRepo.findById(contextId).orElse(null);
            if (djt != null && djt.getDepartment() != null) {
                departmentId = djt.getDepartment().getId();
                companyId = djt.getDepartment().getCompany() != null
                        ? djt.getDepartment().getCompany().getId()
                        : null;
            }
        } else if ("SECTION".equals(contextType)) {
            var sjt = sectionJobTitleRepo.findById(contextId).orElse(null);
            if (sjt != null && sjt.getSection() != null && sjt.getSection().getDepartment() != null) {
                departmentId = sjt.getSection().getDepartment().getId();
                companyId = sjt.getSection().getDepartment().getCompany() != null
                        ? sjt.getSection().getDepartment().getCompany().getId()
                        : null;
            }
        }

        boolean allowedByCompany = companyId != null && scope.companyIds() != null
                && scope.companyIds().contains(companyId);
        boolean allowedByDepartment = departmentId != null && scope.departmentIds() != null
                && scope.departmentIds().contains(departmentId);

        if (!allowedByCompany && !allowedByDepartment) {
            throw new PermissionException("Bạn không có quyền thao tác trên dữ liệu lương này");
        }
    }

    // VALIDATE CONTEXT
    private void validateContext(String contextType, Long contextId) {
        if (!List.of("COMPANY", "DEPARTMENT", "SECTION").contains(contextType)) {
            throw new IdInvalidException("ContextType không hợp lệ (COMPANY | DEPARTMENT | SECTION)");
        }

        if (contextId == null || contextId <= 0) {
            throw new IdInvalidException("ContextId không hợp lệ");
        }

        boolean exists = false;
        String table = "";

        switch (contextType) {
            case "COMPANY":
                table = "company_job_titles";
                exists = companyJobTitleRepo.existsById(contextId);
                break;
            case "DEPARTMENT":
                table = "department_job_titles";
                exists = departmentJobTitleRepo.existsById(contextId);
                break;
            case "SECTION":
                table = "section_job_titles";
                exists = sectionJobTitleRepo.existsById(contextId);
                break;
        }



        if (!exists) {
            throw new IdInvalidException("ContextId " + contextId + " không tồn tại trong " + table);
        }
    }

    // CONVERT DTO
    public ResSalaryGradeDTO convertToResDTO(SalaryGrade sg) {
        ResSalaryGradeDTO res = new ResSalaryGradeDTO();
        res.setId(sg.getId());
        res.setContextType(sg.getContextType());
        res.setContextId(sg.getContextId());
        res.setGradeLevel(sg.getGradeLevel());
        res.setActive(sg.isActive());
        res.setCreatedAt(sg.getCreatedAt());
        res.setUpdatedAt(sg.getUpdatedAt());
        res.setCreatedBy(sg.getCreatedBy());
        res.setUpdatedBy(sg.getUpdatedBy());
        return res;
    }
}