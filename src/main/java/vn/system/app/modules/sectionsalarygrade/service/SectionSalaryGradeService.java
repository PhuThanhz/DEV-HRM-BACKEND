package vn.system.app.modules.sectionsalarygrade.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import vn.system.app.common.util.SecurityUtil;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.department.domain.Department;
import vn.system.app.modules.department.service.DepartmentService;
import vn.system.app.modules.sectionjobtitle.repository.SectionJobTitleRepository;
import vn.system.app.modules.sectionsalarygrade.domain.SectionSalaryGrade;
import vn.system.app.modules.sectionsalarygrade.domain.request.*;
import vn.system.app.modules.sectionsalarygrade.domain.response.*;
import vn.system.app.modules.sectionsalarygrade.repository.SectionSalaryGradeRepository;
import vn.system.app.modules.user.domain.User;
import vn.system.app.modules.user.repository.UserRepository;
import vn.system.app.modules.userposition.repository.UserPositionRepository;

@Service
@RequiredArgsConstructor
public class SectionSalaryGradeService {

    private final SectionSalaryGradeRepository repo;
    private final SectionJobTitleRepository sectionJobTitleRepo;
    private final DepartmentService departmentService;
    private final UserRepository userRepo;
    private final UserPositionRepository userPositionRepo;

    private void validateGrade(Integer grade) {
        if (grade == null || grade <= 0) {
            throw new IdInvalidException("gradeLevel phải lớn hơn 0");
        }
    }

    // IDOR protection — sectionJobTitleId phải thuộc bộ phận/phòng ban trong phạm vi người gọi
    public void checkScope(Long sectionJobTitleId) {
        Department dept = sectionJobTitleRepo.findById(sectionJobTitleId)
                .map(sjt -> sjt.getSection() != null ? sjt.getSection().getDepartment() : null)
                .orElse(null);
        if (dept == null) {
            throw new IdInvalidException("SectionJobTitle ID không tồn tại");
        }
        departmentService.checkDepartmentScope(dept);
    }

    // ============================
    // CREATE
    // ============================
    @Transactional
    public ResSectionSalaryGradeDTO create(ReqCreateSectionSalaryGradeDTO req) {
        validateGrade(req.getGradeLevel());

        if (!sectionJobTitleRepo.existsById(req.getSectionJobTitleId())) {
            throw new IdInvalidException("SectionJobTitle ID không tồn tại");
        }
        checkScope(req.getSectionJobTitleId());

        if (repo.existsBySectionJobTitleIdAndGradeLevel(
                req.getSectionJobTitleId(), req.getGradeLevel())) {
            throw new IdInvalidException("Bậc lương đã tồn tại");
        }

        SectionSalaryGrade sg = new SectionSalaryGrade();
        sg.setSectionJobTitleId(req.getSectionJobTitleId());
        sg.setGradeLevel(req.getGradeLevel());

        return toDTO(repo.save(sg));
    }

    // ============================
    // UPDATE
    // ============================
    @Transactional
    public ResSectionSalaryGradeDTO update(Long id, ReqUpdateSectionSalaryGradeDTO req) {
        validateGrade(req.getGradeLevel());

        SectionSalaryGrade sg = repo.findById(id)
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy ID = " + id));
        checkScope(sg.getSectionJobTitleId());

        if (!sg.isActive()) {
            throw new IdInvalidException("Bậc lương đã bị vô hiệu");
        }

        boolean existed = repo.existsBySectionJobTitleIdAndGradeLevel(
                sg.getSectionJobTitleId(), req.getGradeLevel());

        if (existed && !req.getGradeLevel().equals(sg.getGradeLevel())) {
            throw new IdInvalidException("Bậc lương đã tồn tại");
        }

        sg.setGradeLevel(req.getGradeLevel());
        return toDTO(repo.save(sg));
    }

    // ============================
    // DELETE (SOFT)
    // ============================
    @Transactional
    public void delete(Long id) {
        SectionSalaryGrade sg = repo.findById(id)
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy ID = " + id));
        checkScope(sg.getSectionJobTitleId());

        if (!sg.isActive()) {
            throw new IdInvalidException("Bậc lương đã bị vô hiệu trước đó");
        }

        sg.setActive(false);
        repo.save(sg);
    }

    // ============================
    // RESTORE
    // ============================
    @Transactional
    public ResSectionSalaryGradeDTO restore(Long id) {
        SectionSalaryGrade sg = repo.findById(id)
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy ID = " + id));
        checkScope(sg.getSectionJobTitleId());

        if (sg.isActive()) {
            throw new IdInvalidException("Bậc lương đang hoạt động, không cần khôi phục");
        }

        sg.setActive(true);
        return toDTO(repo.save(sg));
    }

    // ============================
    // FETCH ALL theo sectionJobTitleId (admin)
    // ============================
    public List<ResSectionSalaryGradeDTO> fetchBySectionJobTitle(Long sectionJobTitleId) {
        if (sectionJobTitleId == null || sectionJobTitleId <= 0) {
            throw new IdInvalidException("sectionJobTitleId không hợp lệ");
        }
        checkScope(sectionJobTitleId);

        return repo.findBySectionJobTitleIdOrderByGradeLevelAsc(sectionJobTitleId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    // ============================
    // FETCH CÁ NHÂN — chỉ thấy chức danh của mình
    // ============================
    public List<ResSectionSalaryGradeDTO> fetchMy() {
        String email = SecurityUtil.getCurrentUserLogin()
                .orElseThrow(() -> new IdInvalidException("Chưa đăng nhập"));

        User user = userRepo.findByEmail(email);
        if (user == null)
            throw new IdInvalidException("Không tìm thấy user");

        List<Long> myJobTitleIds = userPositionRepo
                .findByUser_IdAndActiveTrue(user.getId())
                .stream()
                .filter(p -> "SECTION".equalsIgnoreCase(p.getSource()))
                .map(p -> p.getSectionJobTitle().getId())
                .collect(Collectors.toList());

        if (myJobTitleIds.isEmpty())
            return List.of();

        return repo.findBySectionJobTitleIdInAndActiveTrue(myJobTitleIds)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    // ============================
    // FETCH THEO BỘ PHẬN — trưởng bộ phận xem toàn bộ phận
    // ============================
    public List<ResSectionSalaryGradeDTO> fetchByMySection() {
        String email = SecurityUtil.getCurrentUserLogin()
                .orElseThrow(() -> new IdInvalidException("Chưa đăng nhập"));

        User user = userRepo.findByEmail(email);
        if (user == null)
            throw new IdInvalidException("Không tìm thấy user");

        // Lấy tất cả sectionId mà user có position
        List<Long> sectionIds = userPositionRepo
                .findByUser_IdAndActiveTrue(user.getId())
                .stream()
                .filter(p -> "SECTION".equalsIgnoreCase(p.getSource()))
                .map(p -> p.getSectionJobTitle().getSection().getId())
                .collect(Collectors.toList());

        if (sectionIds.isEmpty())
            return List.of();

        // Lấy tất cả sectionJobTitleId thuộc các section đó
        List<Long> jobTitleIds = sectionJobTitleRepo
                .findBySection_IdIn(sectionIds)
                .stream()
                .map(sjt -> sjt.getId())
                .collect(Collectors.toList());

        if (jobTitleIds.isEmpty())
            return List.of();

        return repo.findBySectionJobTitleIdIn(jobTitleIds)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    // ============================
    // MAPPER
    // ============================
    private ResSectionSalaryGradeDTO toDTO(SectionSalaryGrade sg) {
        ResSectionSalaryGradeDTO res = new ResSectionSalaryGradeDTO();
        res.setId(sg.getId());
        res.setSectionJobTitleId(sg.getSectionJobTitleId());
        res.setGradeLevel(sg.getGradeLevel());
        res.setActive(sg.isActive());
        res.setCreatedAt(sg.getCreatedAt());
        res.setUpdatedAt(sg.getUpdatedAt());
        res.setCreatedBy(sg.getCreatedBy());
        res.setUpdatedBy(sg.getUpdatedBy());
        return res;
    }
}