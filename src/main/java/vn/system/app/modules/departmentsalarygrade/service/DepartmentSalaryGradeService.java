package vn.system.app.modules.departmentsalarygrade.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import vn.system.app.common.util.SecurityUtil;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.department.domain.Department;
import vn.system.app.modules.department.service.DepartmentService;
import vn.system.app.modules.departmentjobtitle.repository.DepartmentJobTitleRepository;
import vn.system.app.modules.departmentsalarygrade.domain.DepartmentSalaryGrade;
import vn.system.app.modules.departmentsalarygrade.domain.request.*;
import vn.system.app.modules.departmentsalarygrade.domain.response.*;
import vn.system.app.modules.departmentsalarygrade.repository.DepartmentSalaryGradeRepository;
import vn.system.app.modules.user.domain.User;
import vn.system.app.modules.user.repository.UserRepository;
import vn.system.app.modules.userposition.repository.UserPositionRepository;

@Service
@RequiredArgsConstructor
public class DepartmentSalaryGradeService {

    private final DepartmentSalaryGradeRepository repo;
    private final DepartmentJobTitleRepository deptJobTitleRepo;
    private final DepartmentService departmentService;
    private final UserRepository userRepo;
    private final UserPositionRepository userPositionRepo;

    private void validate(Integer grade) {
        if (grade == null || grade <= 0) {
            throw new IdInvalidException("gradeLevel phải > 0");
        }
    }

    // IDOR protection — departmentJobTitleId phải thuộc phòng ban trong phạm vi người gọi
    public void checkScope(Long departmentJobTitleId) {
        Department dept = deptJobTitleRepo.findById(departmentJobTitleId)
                .map(djt -> djt.getDepartment())
                .orElse(null);
        if (dept == null) {
            throw new IdInvalidException("DepartmentJobTitle ID không tồn tại");
        }
        departmentService.checkDepartmentScope(dept);
    }

    // ============================================================
    // CREATE
    // ============================================================
    @Transactional
    public ResDepartmentSalaryGradeDTO create(ReqCreateDepartmentSalaryGradeDTO req) {
        validate(req.getGradeLevel());

        if (!deptJobTitleRepo.existsById(req.getDepartmentJobTitleId())) {
            throw new IdInvalidException("DepartmentJobTitle ID không tồn tại");
        }
        checkScope(req.getDepartmentJobTitleId());

        if (repo.existsByDepartmentJobTitleIdAndGradeLevel(
                req.getDepartmentJobTitleId(), req.getGradeLevel())) {
            throw new IdInvalidException("Bậc lương đã tồn tại");
        }

        DepartmentSalaryGrade sg = new DepartmentSalaryGrade();
        sg.setDepartmentJobTitleId(req.getDepartmentJobTitleId());
        sg.setGradeLevel(req.getGradeLevel());

        return toDTO(repo.save(sg));
    }

    // ============================================================
    // UPDATE
    // ============================================================
    @Transactional
    public ResDepartmentSalaryGradeDTO update(Long id, ReqUpdateDepartmentSalaryGradeDTO req) {
        validate(req.getGradeLevel());

        DepartmentSalaryGrade sg = repo.findById(id)
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy ID"));
        checkScope(sg.getDepartmentJobTitleId());

        if (!sg.isActive()) {
            throw new IdInvalidException("Bậc lương đã bị vô hiệu");
        }

        boolean existed = repo.existsByDepartmentJobTitleIdAndGradeLevel(
                sg.getDepartmentJobTitleId(), req.getGradeLevel());

        if (existed && !req.getGradeLevel().equals(sg.getGradeLevel())) {
            throw new IdInvalidException("Bậc lương đã tồn tại");
        }

        sg.setGradeLevel(req.getGradeLevel());
        return toDTO(repo.save(sg));
    }

    // ============================================================
    // DELETE (SOFT DELETE)
    // ============================================================
    @Transactional
    public void delete(Long id) {
        DepartmentSalaryGrade sg = repo.findById(id)
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy ID"));
        checkScope(sg.getDepartmentJobTitleId());

        if (!sg.isActive()) {
            throw new IdInvalidException("Bậc lương đã bị vô hiệu trước đó");
        }

        sg.setActive(false);
        repo.save(sg);
    }

    // ============================================================
    // RESTORE
    // ============================================================
    @Transactional
    public ResDepartmentSalaryGradeDTO restore(Long id) {
        DepartmentSalaryGrade sg = repo.findById(id)
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy ID"));
        checkScope(sg.getDepartmentJobTitleId());

        if (sg.isActive()) {
            throw new IdInvalidException("Bậc lương đang hoạt động");
        }

        sg.setActive(true);
        return toDTO(repo.save(sg));
    }

    // ============================================================
    // FETCH ALL theo departmentJobTitleId (admin dùng)
    // ============================================================
    public List<ResDepartmentSalaryGradeDTO> fetchByDepartmentJobTitle(Long deptJobTitleId) {
        if (deptJobTitleId == null || deptJobTitleId <= 0) {
            throw new IdInvalidException("departmentJobTitleId không hợp lệ");
        }
        checkScope(deptJobTitleId);

        return repo.findByDepartmentJobTitleIdOrderByGradeLevelAsc(deptJobTitleId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    // ============================================================
    // FETCH CÁ NHÂN — chỉ thấy khung lương chức danh của mình
    // ============================================================
    public List<ResDepartmentSalaryGradeDTO> fetchMy() {
        String email = SecurityUtil.getCurrentUserLogin()
                .orElseThrow(() -> new IdInvalidException("Chưa đăng nhập"));

        User user = userRepo.findByEmail(email);
        if (user == null)
            throw new IdInvalidException("Không tìm thấy user");

        List<Long> myJobTitleIds = userPositionRepo
                .findByUser_IdAndActiveTrue(user.getId())
                .stream()
                .filter(p -> "DEPARTMENT".equalsIgnoreCase(p.getSource()))
                .map(p -> p.getDepartmentJobTitle().getId())
                .collect(Collectors.toList());

        if (myJobTitleIds.isEmpty())
            return List.of();

        return repo.findByDepartmentJobTitleIdInAndActiveTrue(myJobTitleIds)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    // ============================================================
    // FETCH THEO PHÒNG BAN — HR / trưởng phòng xem toàn phòng ban
    // ============================================================
    public List<ResDepartmentSalaryGradeDTO> fetchByMyDepartment() {
        String email = SecurityUtil.getCurrentUserLogin()
                .orElseThrow(() -> new IdInvalidException("Chưa đăng nhập"));

        User user = userRepo.findByEmail(email);
        if (user == null)
            throw new IdInvalidException("Không tìm thấy user");

        // Lấy tất cả departmentId mà user có position
        List<Long> departmentIds = userPositionRepo
                .findByUser_IdAndActiveTrue(user.getId())
                .stream()
                .filter(p -> "DEPARTMENT".equalsIgnoreCase(p.getSource()))
                .map(p -> p.getDepartmentJobTitle().getDepartment().getId())
                .collect(Collectors.toList());

        if (departmentIds.isEmpty())
            return List.of();

        // Lấy tất cả departmentJobTitleId thuộc các department đó
        List<Long> jobTitleIds = deptJobTitleRepo
                .findByDepartment_IdIn(departmentIds)
                .stream()
                .map(djt -> djt.getId())
                .collect(Collectors.toList());

        if (jobTitleIds.isEmpty())
            return List.of();

        return repo.findByDepartmentJobTitleIdIn(jobTitleIds)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    // ============================================================
    // MAPPER
    // ============================================================
    private ResDepartmentSalaryGradeDTO toDTO(DepartmentSalaryGrade sg) {
        ResDepartmentSalaryGradeDTO res = new ResDepartmentSalaryGradeDTO();
        res.setId(sg.getId());
        res.setDepartmentJobTitleId(sg.getDepartmentJobTitleId());
        res.setGradeLevel(sg.getGradeLevel());
        res.setActive(sg.isActive());
        res.setCreatedAt(sg.getCreatedAt());
        res.setUpdatedAt(sg.getUpdatedAt());
        res.setCreatedBy(sg.getCreatedBy());
        res.setUpdatedBy(sg.getUpdatedBy());
        return res;
    }
}