package vn.system.app.modules.salarystructure.service;

import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.error.IdInvalidException;

import vn.system.app.modules.companysalarygrade.repository.CompanySalaryGradeRepository;
import vn.system.app.modules.companysalarygrade.service.CompanySalaryGradeService;
import vn.system.app.modules.departmentsalarygrade.repository.DepartmentSalaryGradeRepository;
import vn.system.app.modules.departmentsalarygrade.service.DepartmentSalaryGradeService;
import vn.system.app.modules.sectionsalarygrade.repository.SectionSalaryGradeRepository;
import vn.system.app.modules.sectionsalarygrade.service.SectionSalaryGradeService;

import vn.system.app.modules.salarystructure.domain.OwnerLevel;
import vn.system.app.modules.salarystructure.domain.SalaryStructure;
import vn.system.app.modules.salarystructure.domain.request.ReqUpsertSalaryStructureDTO;
import vn.system.app.modules.salarystructure.domain.response.ResSalaryStructureDTO;
import vn.system.app.modules.salarystructure.repository.SalaryStructureRepository;

@Service
@RequiredArgsConstructor
public class SalaryStructureService {

    private final SalaryStructureRepository repo;

    private final CompanySalaryGradeRepository companyGradeRepo;
    private final DepartmentSalaryGradeRepository departmentGradeRepo;
    private final SectionSalaryGradeRepository sectionGradeRepo;

    private final CompanySalaryGradeService companySalaryGradeService;
    private final DepartmentSalaryGradeService departmentSalaryGradeService;
    private final SectionSalaryGradeService sectionSalaryGradeService;

    // IDOR protection — resolve salaryGradeId theo ownerLevel rồi tái dùng
    // checkScope tương ứng của CompanySalaryGrade/DepartmentSalaryGrade/SectionSalaryGrade
    private void checkOwnerScope(OwnerLevel ownerLevel, Long salaryGradeId) {
        if (ownerLevel == null || salaryGradeId == null) {
            return;
        }
        switch (ownerLevel) {
            case COMPANY -> companyGradeRepo.findById(salaryGradeId)
                    .ifPresent(g -> companySalaryGradeService.checkScope(g.getCompanyJobTitleId()));
            case DEPARTMENT -> departmentGradeRepo.findById(salaryGradeId)
                    .ifPresent(g -> departmentSalaryGradeService.checkScope(g.getDepartmentJobTitleId()));
            case SECTION -> sectionGradeRepo.findById(salaryGradeId)
                    .ifPresent(g -> sectionSalaryGradeService.checkScope(g.getSectionJobTitleId()));
        }
    }

    /*
     * =====================================================
     * VALIDATE INPUT
     * =====================================================
     */
    private void validate(ReqUpsertSalaryStructureDTO req) {

        if (req.getOwnerLevel() == null)
            throw new IdInvalidException("ownerLevel không được bỏ trống");

        if (req.getOwnerJobTitleId() == null || req.getOwnerJobTitleId() <= 0)
            throw new IdInvalidException("ownerJobTitleId không hợp lệ");

        if (req.getSalaryGradeId() == null || req.getSalaryGradeId() <= 0)
            throw new IdInvalidException("salaryGradeId không hợp lệ");

        boolean hasMonthly = req.getMonthBaseSalary() != null ||
                req.getMonthPositionAllowance() != null ||
                req.getMonthMealAllowance() != null ||
                req.getMonthFuelSupport() != null ||
                req.getMonthPhoneSupport() != null ||
                req.getMonthOtherSupport() != null ||
                req.getMonthKpiBonusA() != null ||
                req.getMonthKpiBonusB() != null ||
                req.getMonthKpiBonusC() != null ||
                req.getMonthKpiBonusD() != null;

        boolean hasHourly = req.getHourBaseSalary() != null ||
                req.getHourPositionAllowance() != null ||
                req.getHourMealAllowance() != null ||
                req.getHourFuelSupport() != null ||
                req.getHourPhoneSupport() != null ||
                req.getHourOtherSupport() != null ||
                req.getHourKpiBonusA() != null ||
                req.getHourKpiBonusB() != null ||
                req.getHourKpiBonusC() != null ||
                req.getHourKpiBonusD() != null;

        if (!hasMonthly && !hasHourly)
            throw new IdInvalidException("Phải nhập dữ liệu tháng hoặc dữ liệu giờ");

        // === VALIDATE SỐ ÂM ===
        validateNonNegative(req);

        // Check salary grade active
        boolean isActive = switch (req.getOwnerLevel()) {
            case COMPANY ->
                companyGradeRepo.findById(req.getSalaryGradeId())
                        .orElseThrow(() -> new IdInvalidException("Bậc lương không tồn tại"))
                        .isActive();

            case DEPARTMENT ->
                departmentGradeRepo.findById(req.getSalaryGradeId())
                        .orElseThrow(() -> new IdInvalidException("Bậc lương không tồn tại"))
                        .isActive();

            case SECTION ->
                sectionGradeRepo.findById(req.getSalaryGradeId())
                        .orElseThrow(() -> new IdInvalidException("Bậc lương không tồn tại"))
                        .isActive();
        };

        if (!isActive)
            throw new IdInvalidException("Bậc lương đang tắt, không thể tạo cấu trúc lương");
    }

    /*
     * =====================================================
     * KHÔNG CHO PHÉP SỐ ÂM
     * =====================================================
     */
    private void validateNonNegative(ReqUpsertSalaryStructureDTO req) {

        Double[] values = {
                req.getMonthBaseSalary(),
                req.getMonthPositionAllowance(),
                req.getMonthMealAllowance(),
                req.getMonthFuelSupport(),
                req.getMonthPhoneSupport(),
                req.getMonthOtherSupport(),
                req.getMonthKpiBonusA(),
                req.getMonthKpiBonusB(),
                req.getMonthKpiBonusC(),
                req.getMonthKpiBonusD(),

                req.getHourBaseSalary(),
                req.getHourPositionAllowance(),
                req.getHourMealAllowance(),
                req.getHourFuelSupport(),
                req.getHourPhoneSupport(),
                req.getHourOtherSupport(),
                req.getHourKpiBonusA(),
                req.getHourKpiBonusB(),
                req.getHourKpiBonusC(),
                req.getHourKpiBonusD(),
        };

        for (Double v : values) {
            if (v != null && v < 0)
                throw new IdInvalidException("Giá trị không được âm");
        }
    }

    /*
     * =====================================================
     * UPSERT — MERGE MODE (KHÔNG XÓA FIELD KHÁC)
     * =====================================================
     */
    @Transactional
    public SalaryStructure upsert(ReqUpsertSalaryStructureDTO req) {

        validate(req);
        checkOwnerScope(req.getOwnerLevel(), req.getSalaryGradeId());

        SalaryStructure entity = repo
                .findByOwnerLevelAndOwnerJobTitleIdAndSalaryGradeId(
                        req.getOwnerLevel(),
                        req.getOwnerJobTitleId(),
                        req.getSalaryGradeId())
                .orElseGet(() -> {
                    SalaryStructure s = new SalaryStructure();
                    s.setOwnerLevel(req.getOwnerLevel());
                    s.setOwnerJobTitleId(req.getOwnerJobTitleId());
                    s.setSalaryGradeId(req.getSalaryGradeId());
                    return s;
                });

        /* ======== MONTH (MERGE) ======== */
        if (req.getMonthBaseSalary() != null)
            entity.setMonthBaseSalary(req.getMonthBaseSalary());
        if (req.getMonthPositionAllowance() != null)
            entity.setMonthPositionAllowance(req.getMonthPositionAllowance());
        if (req.getMonthMealAllowance() != null)
            entity.setMonthMealAllowance(req.getMonthMealAllowance());
        if (req.getMonthFuelSupport() != null)
            entity.setMonthFuelSupport(req.getMonthFuelSupport());
        if (req.getMonthPhoneSupport() != null)
            entity.setMonthPhoneSupport(req.getMonthPhoneSupport());
        if (req.getMonthOtherSupport() != null)
            entity.setMonthOtherSupport(req.getMonthOtherSupport());

        if (req.getMonthKpiBonusA() != null)
            entity.setMonthKpiBonusA(req.getMonthKpiBonusA());
        if (req.getMonthKpiBonusB() != null)
            entity.setMonthKpiBonusB(req.getMonthKpiBonusB());
        if (req.getMonthKpiBonusC() != null)
            entity.setMonthKpiBonusC(req.getMonthKpiBonusC());
        if (req.getMonthKpiBonusD() != null)
            entity.setMonthKpiBonusD(req.getMonthKpiBonusD());

        /* ======== HOUR (MERGE) ======== */
        if (req.getHourBaseSalary() != null)
            entity.setHourBaseSalary(req.getHourBaseSalary());
        if (req.getHourPositionAllowance() != null)
            entity.setHourPositionAllowance(req.getHourPositionAllowance());
        if (req.getHourMealAllowance() != null)
            entity.setHourMealAllowance(req.getHourMealAllowance());
        if (req.getHourFuelSupport() != null)
            entity.setHourFuelSupport(req.getHourFuelSupport());
        if (req.getHourPhoneSupport() != null)
            entity.setHourPhoneSupport(req.getHourPhoneSupport());
        if (req.getHourOtherSupport() != null)
            entity.setHourOtherSupport(req.getHourOtherSupport());

        if (req.getHourKpiBonusA() != null)
            entity.setHourKpiBonusA(req.getHourKpiBonusA());
        if (req.getHourKpiBonusB() != null)
            entity.setHourKpiBonusB(req.getHourKpiBonusB());
        if (req.getHourKpiBonusC() != null)
            entity.setHourKpiBonusC(req.getHourKpiBonusC());
        if (req.getHourKpiBonusD() != null)
            entity.setHourKpiBonusD(req.getHourKpiBonusD());

        return repo.save(entity);
    }

    /*
     * =====================================================
     * FIND BY ID
     * =====================================================
     */
    public ResSalaryStructureDTO findById(Long id) {
        SalaryStructure entity = repo.findById(id)
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy ID"));
        checkOwnerScope(entity.getOwnerLevel(), entity.getSalaryGradeId());
        return toDTO(entity);
    }

    /*
     * =====================================================
     * PAGINATION
     * =====================================================
     */
    public ResultPaginationDTO fetchAll(Specification<SalaryStructure> spec, Pageable pageable) {

        Page<SalaryStructure> page = repo.findAll(spec, pageable);

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
                        .map(this::toDTO)
                        .collect(Collectors.toList()));

        return rs;
    }

    /*
     * =====================================================
     * ENTITY → DTO
     * =====================================================
     */
    public ResSalaryStructureDTO toDTO(SalaryStructure e) {

        ResSalaryStructureDTO r = new ResSalaryStructureDTO();

        r.setId(e.getId());
        r.setOwnerLevel(e.getOwnerLevel());
        r.setOwnerJobTitleId(e.getOwnerJobTitleId());
        r.setSalaryGradeId(e.getSalaryGradeId());

        // ===== MONTH =====
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

        // ===== HOUR =====
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
