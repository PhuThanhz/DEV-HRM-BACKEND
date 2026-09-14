package vn.system.app.modules.salarystructure.service;

import java.util.*;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import vn.system.app.common.util.SecurityUtil;
import vn.system.app.common.util.error.IdInvalidException;

import vn.system.app.modules.companysalarygrade.domain.CompanySalaryGrade;
import vn.system.app.modules.companysalarygrade.repository.CompanySalaryGradeRepository;

import vn.system.app.modules.companyjobtitle.domain.CompanyJobTitle;
import vn.system.app.modules.companyjobtitle.repository.CompanyJobTitleRepository;

import vn.system.app.modules.departmentsalarygrade.domain.DepartmentSalaryGrade;
import vn.system.app.modules.departmentsalarygrade.repository.DepartmentSalaryGradeRepository;

import vn.system.app.modules.departmentjobtitle.domain.DepartmentJobTitle;
import vn.system.app.modules.departmentjobtitle.repository.DepartmentJobTitleRepository;

import vn.system.app.modules.sectionsalarygrade.domain.SectionSalaryGrade;
import vn.system.app.modules.sectionsalarygrade.repository.SectionSalaryGradeRepository;

import vn.system.app.modules.sectionjobtitle.domain.SectionJobTitle;
import vn.system.app.modules.sectionjobtitle.repository.SectionJobTitleRepository;

import vn.system.app.modules.salarystructure.domain.OwnerLevel;
import vn.system.app.modules.salarystructure.domain.SalaryStructure;
import vn.system.app.modules.salarystructure.domain.response.ResSalaryStructureDTO;
import vn.system.app.modules.salarystructure.repository.SalaryStructureRepository;

import vn.system.app.modules.departmentjobtitle.domain.response.ResDepartmentJobTitleDTO;
import vn.system.app.modules.departmentjobtitle.service.DepartmentJobTitleScopeService;

import vn.system.app.modules.department.service.DepartmentService;

import vn.system.app.modules.user.domain.User;
import vn.system.app.modules.user.repository.UserRepository;
import vn.system.app.modules.userposition.repository.UserPositionRepository;

@Service
@RequiredArgsConstructor
public class SalaryMatrixService {

    private final DepartmentJobTitleScopeService scopeService;
    private final DepartmentService departmentService;

    private final CompanyJobTitleRepository companyJobTitleRepo;
    private final DepartmentJobTitleRepository deptJobTitleRepo;
    private final SectionJobTitleRepository sectionJobTitleRepo;

    private final CompanySalaryGradeRepository companyGradeRepo;
    private final DepartmentSalaryGradeRepository deptGradeRepo;
    private final SectionSalaryGradeRepository sectionGradeRepo;

    private final SalaryStructureRepository structureRepo;

    // ← THÊM MỚI
    private final UserRepository userRepo;
    private final UserPositionRepository userPositionRepo;

    // ======================================================
    // ADMIN — xem full phòng ban
    // ======================================================
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getDepartmentSalaryMatrix(Long departmentId) {

        departmentService.checkDepartmentScope(departmentService.fetchEntityById(departmentId));

        List<ResDepartmentJobTitleDTO> jobTitles = scopeService.fetchByScope(departmentId, true);
        List<Map<String, Object>> result = new ArrayList<>();

        for (ResDepartmentJobTitleDTO jt : jobTitles) {
            result.add(buildJobTitleRow(jt));
        }

        return result;
    }

    // ======================================================
    // USER — chỉ xem chức danh của mình trong phòng ban
    // ======================================================
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getMyDepartmentSalaryMatrix(Long departmentId) {

        String email = SecurityUtil.getCurrentUserLogin()
                .orElseThrow(() -> new IdInvalidException("Chưa đăng nhập"));

        User user = userRepo.findByEmail(email);
        if (user == null)
            throw new IdInvalidException("Không tìm thấy user");

        // Lấy tất cả jobTitleId mà user có trong phòng ban này
        Set<Long> myJobTitleIds = userPositionRepo
                .findByUser_IdAndActiveTrue(user.getId())
                .stream()
                .filter(p -> {
                    String src = p.getSource().toUpperCase();
                    if ("COMPANY".equals(src))
                        return true;
                    if ("DEPARTMENT".equals(src))
                        return p.getDepartmentJobTitle()
                                .getDepartment().getId().equals(departmentId);
                    if ("SECTION".equals(src))
                        return p.getSectionJobTitle()
                                .getSection().getDepartment().getId().equals(departmentId);
                    return false;
                })
                .map(p -> {
                    String s = p.getSource().toUpperCase();
                    if ("COMPANY".equals(s)) return p.getCompanyJobTitle().getJobTitle().getId();
                    if ("DEPARTMENT".equals(s)) return p.getDepartmentJobTitle().getJobTitle().getId();
                    if ("SECTION".equals(s)) return p.getSectionJobTitle().getJobTitle().getId();
                    return null;
                })
                .collect(Collectors.toSet());

        if (myJobTitleIds.isEmpty())
            return List.of();

        // Gọi fetchByScope(..., true) để lấy toàn bộ các JobTitle mà không bị chặn phân quyền
        List<ResDepartmentJobTitleDTO> allJobTitles = scopeService.fetchByScope(departmentId, true);
        
        List<Map<String, Object>> result = new ArrayList<>();
        for (ResDepartmentJobTitleDTO jt : allJobTitles) {
            if (myJobTitleIds.contains(jt.getJobTitle().getId())) {
                result.add(buildJobTitleRow(jt));
            }
        }
        
        return result;
    }

    // ======================================================
    // HELPER — build 1 row chức danh (tái dụng cho cả 2 method)
    // ======================================================
    private Map<String, Object> buildJobTitleRow(ResDepartmentJobTitleDTO jt) {

        Long jobTitleId = jt.getJobTitle().getId();

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("jobTitleId", jobTitleId);
        row.put("jobTitleName", jt.getJobTitle().getNameVi());
        row.put("band", jt.getJobTitle().getBand());
        row.put("level", jt.getJobTitle().getLevel());
        row.put("source", jt.getSource());

        List<Map<String, Object>> grades = new ArrayList<>();

        List<?> gradeList = switch (jt.getSource()) {

            case "COMPANY" -> {
                List<Long> ids = companyJobTitleRepo
                        .findByJobTitle_IdAndActiveTrue(jobTitleId)
                        .stream().map(CompanyJobTitle::getId).toList();

                yield ids.isEmpty()
                        ? Collections.emptyList()
                        : companyGradeRepo.findByCompanyJobTitleIdIn(ids);
            }

            case "DEPARTMENT" -> {
                List<Long> ids = deptJobTitleRepo
                        .findByJobTitle_IdAndActiveTrue(jobTitleId)
                        .stream().map(DepartmentJobTitle::getId).toList();

                yield ids.isEmpty()
                        ? Collections.emptyList()
                        : deptGradeRepo.findByDepartmentJobTitleIdIn(ids);
            }

            case "SECTION" -> {
                List<Long> ids = sectionJobTitleRepo
                        .findByJobTitle_IdAndActiveTrue(jobTitleId)
                        .stream().map(SectionJobTitle::getId).toList();

                yield ids.isEmpty()
                        ? Collections.emptyList()
                        : sectionGradeRepo.findBySectionJobTitleIdIn(ids);
            }

            default -> Collections.emptyList();
        };

        for (Object g : gradeList) {

            Integer gradeLevel = null;
            Long gradeId = null;
            OwnerLevel ownerLevel = OwnerLevel.valueOf(jt.getSource());

            if (g instanceof CompanySalaryGrade cg) {
                gradeLevel = cg.getGradeLevel();
                gradeId = cg.getId();
            } else if (g instanceof DepartmentSalaryGrade dg) {
                gradeLevel = dg.getGradeLevel();
                gradeId = dg.getId();
            } else if (g instanceof SectionSalaryGrade sg) {
                gradeLevel = sg.getGradeLevel();
                gradeId = sg.getId();
            }

            if (gradeId == null)
                continue;

            Optional<SalaryStructure> ss = structureRepo
                    .findByOwnerLevelAndOwnerJobTitleIdAndSalaryGradeId(
                            ownerLevel, jobTitleId, gradeId);

            Map<String, Object> gRow = new LinkedHashMap<>();
            gRow.put("gradeLevel", gradeLevel);
            gRow.put("gradeId", gradeId);
            gRow.put("structure", ss.map(this::convertToDTO).orElse(null));

            grades.add(gRow);
        }

        row.put("grades", grades);
        return row;
    }

    // ======================================================
    // DTO CONVERTER
    // ======================================================
    private ResSalaryStructureDTO convertToDTO(SalaryStructure e) {
        ResSalaryStructureDTO r = new ResSalaryStructureDTO();

        r.setId(e.getId());
        r.setOwnerLevel(e.getOwnerLevel());
        r.setOwnerJobTitleId(e.getOwnerJobTitleId());
        r.setSalaryGradeId(e.getSalaryGradeId());

        r.setMonthBaseSalary(e.getMonthBaseSalary());
        r.setMonthPositionAllowance(e.getMonthPositionAllowance());
        r.setMonthMealAllowance(e.getMonthMealAllowance());
        r.setMonthFuelSupport(e.getMonthFuelSupport());
        r.setMonthPhoneSupport(e.getMonthPhoneSupport());
        r.setMonthOtherSupport(e.getMonthOtherSupport());
        r.setMonthKpiBonusA(e.getMonthKpiBonusA());
        r.setMonthKpiBonusB(e.getMonthKpiBonusB());
        r.setMonthKpiBonusC(e.getMonthKpiBonusC());
        r.setMonthKpiBonusD(e.getMonthKpiBonusD());

        r.setHourBaseSalary(e.getHourBaseSalary());
        r.setHourPositionAllowance(e.getHourPositionAllowance());
        r.setHourMealAllowance(e.getHourMealAllowance());
        r.setHourFuelSupport(e.getHourFuelSupport());
        r.setHourPhoneSupport(e.getHourPhoneSupport());
        r.setHourOtherSupport(e.getHourOtherSupport());
        r.setHourKpiBonusA(e.getHourKpiBonusA());
        r.setHourKpiBonusB(e.getHourKpiBonusB());
        r.setHourKpiBonusC(e.getHourKpiBonusC());
        r.setHourKpiBonusD(e.getHourKpiBonusD());

        r.setCreatedAt(e.getCreatedAt());
        r.setUpdatedAt(e.getUpdatedAt());

        return r;
    }
}