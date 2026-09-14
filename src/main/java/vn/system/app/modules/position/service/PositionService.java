package vn.system.app.modules.position.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.common.util.error.IdInvalidException;

import vn.system.app.modules.company.domain.Company;
import vn.system.app.modules.company.service.CompanyService;
import vn.system.app.modules.companyjobtitle.repository.CompanyJobTitleRepository;

import vn.system.app.modules.department.domain.Department;
import vn.system.app.modules.department.service.DepartmentService;
import vn.system.app.modules.departmentjobtitle.repository.DepartmentJobTitleRepository;

import vn.system.app.modules.section.domain.Section;
import vn.system.app.modules.section.service.SectionService;
import vn.system.app.modules.sectionjobtitle.repository.SectionJobTitleRepository;

import vn.system.app.modules.jobtitle.domain.JobTitle;
import vn.system.app.modules.jobtitle.service.JobTitleService;

import vn.system.app.modules.position.domain.Position;
import vn.system.app.modules.position.domain.request.ReqPositionDTO;
import vn.system.app.modules.position.domain.response.ResPositionDTO;
import vn.system.app.modules.position.repository.PositionRepository;

@Service
public class PositionService {

    private final PositionRepository repo;
    private final CompanyService companyService;
    private final DepartmentService departmentService;
    private final SectionService sectionService;
    private final JobTitleService jobTitleService;

    private final CompanyJobTitleRepository companyJTRepo;
    private final DepartmentJobTitleRepository departmentJTRepo;
    private final SectionJobTitleRepository sectionJTRepo;

    public PositionService(
            PositionRepository repo,
            CompanyService companyService,
            DepartmentService departmentService,
            SectionService sectionService,
            JobTitleService jobTitleService,
            CompanyJobTitleRepository companyJTRepo,
            DepartmentJobTitleRepository departmentJTRepo,
            SectionJobTitleRepository sectionJTRepo) {

        this.repo = repo;
        this.companyService = companyService;
        this.departmentService = departmentService;
        this.sectionService = sectionService;
        this.jobTitleService = jobTitleService;

        this.companyJTRepo = companyJTRepo;
        this.departmentJTRepo = departmentJTRepo;
        this.sectionJTRepo = sectionJTRepo;
    }

    // =======================================
    // CREATE POSITION
    // =======================================
    @Transactional
    public Position create(ReqPositionDTO dto) {

        Company company = companyService.fetchEntityById(dto.getCompanyId());
        Department department = null;
        Section section = null;

        if (dto.getDepartmentId() != null) {
            department = departmentService.fetchEntityById(dto.getDepartmentId());
        }

        if (dto.getSectionId() != null) {
            section = sectionService.fetchEntityById(dto.getSectionId());
        }

        checkScope(company, department, section);

        JobTitle jobTitle = jobTitleService.fetchEntityById(dto.getJobTitleId());

        validateJobTitleLevel(company, department, section, jobTitle);

        Position p = new Position();
        p.setCompany(company);
        p.setDepartment(department);
        p.setSection(section);
        p.setJobTitle(jobTitle);

        if (dto.getReportsToId() != null) {
            Position boss = repo.findById(dto.getReportsToId())
                    .orElseThrow(() -> new IdInvalidException("Không tìm thấy vị trí reportsTo"));
            p.setReportsTo(boss);
        }

        return repo.save(p);
    }

    // =======================================
    // SCOPE CHECK (IDOR protection)
    // =======================================
    private void checkScope(Company company, Department department, Section section) {
        if (section != null) {
            departmentService.checkDepartmentScope(section.getDepartment());
        } else if (department != null) {
            departmentService.checkDepartmentScope(department);
        } else {
            companyService.checkCompanyScope(company.getId());
        }
    }

    // =======================================
    // VALIDATE JOBTITLE
    // =======================================
    private void validateJobTitleLevel(
            Company company,
            Department department,
            Section section,
            JobTitle jobTitle) {

        Long jobId = jobTitle.getId();

        if (section != null) {
            boolean ok = sectionJTRepo.existsBySection_IdAndJobTitle_IdAndActiveTrue(section.getId(), jobId);
            if (!ok) {
                throw new IdInvalidException("Chức danh không tồn tại trong SectionJobTitle.");
            }
            return;
        }

        if (department != null) {
            boolean ok = departmentJTRepo.existsByDepartment_IdAndJobTitle_IdAndActiveTrue(department.getId(), jobId);
            if (!ok) {
                throw new IdInvalidException("Chức danh không tồn tại trong DepartmentJobTitle.");
            }
            return;
        }

        boolean ok = companyJTRepo.existsByCompany_IdAndJobTitle_IdAndActiveTrue(company.getId(), jobId);
        if (!ok) {
            throw new IdInvalidException("Chức danh không tồn tại trong CompanyJobTitle.");
        }
    }

    // =======================================
    // FETCH ENTITY
    // =======================================
    public Position fetchEntity(Long id) {
        Position p = repo.findById(id)
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy Position id = " + id));
        checkScope(p.getCompany(), p.getDepartment(), p.getSection());
        return p;
    }

    // =======================================
    // CONVERT DTO
    // =======================================
    public ResPositionDTO convert(Position p) {

        ResPositionDTO dto = new ResPositionDTO();

        dto.setId(p.getId());
        dto.setActive(p.isActive());
        dto.setCreatedAt(p.getCreatedAt());
        dto.setUpdatedAt(p.getUpdatedAt());
        dto.setCreatedBy(p.getCreatedBy());
        dto.setUpdatedBy(p.getUpdatedBy());

        // COMPANY
        ResPositionDTO.CompanyInfo ci = new ResPositionDTO.CompanyInfo();
        ci.setId(p.getCompany().getId());
        ci.setName(p.getCompany().getName());
        dto.setCompany(ci);

        // DEPT
        if (p.getDepartment() != null) {
            ResPositionDTO.DepartmentInfo di = new ResPositionDTO.DepartmentInfo();
            di.setId(p.getDepartment().getId());
            di.setName(p.getDepartment().getName());
            dto.setDepartment(di);
        }

        // SECTION
        if (p.getSection() != null) {
            ResPositionDTO.SectionInfo si = new ResPositionDTO.SectionInfo();
            si.setId(p.getSection().getId());
            si.setName(p.getSection().getName());
            dto.setSection(si);
        }

        // JOBTITLE
        ResPositionDTO.JobTitleInfo jt = new ResPositionDTO.JobTitleInfo();
        jt.setId(p.getJobTitle().getId());
        jt.setNameVi(p.getJobTitle().getNameVi());

        if (p.getJobTitle().getPositionLevel() != null) {
            var pl = p.getJobTitle().getPositionLevel();
            jt.setPositionCode(pl.getCode());
            jt.setBandOrder(pl.getBandOrder());
            jt.setLevelNumber(Integer.parseInt(pl.getCode().replaceAll("[^0-9]", "")));
        }

        dto.setJobTitle(jt);

        // REPORTS TO
        if (p.getReportsTo() != null) {
            ResPositionDTO.ReportsToInfo ri = new ResPositionDTO.ReportsToInfo();
            ri.setId(p.getReportsTo().getId());
            ri.setJobTitleName(p.getReportsTo().getJobTitle().getNameVi());
            dto.setReportsTo(ri);
        }

        return dto;
    }
}