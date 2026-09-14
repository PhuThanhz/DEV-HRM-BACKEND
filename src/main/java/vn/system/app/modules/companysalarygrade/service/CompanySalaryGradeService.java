package vn.system.app.modules.companysalarygrade.service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import vn.system.app.common.util.SecurityUtil;
import vn.system.app.common.util.UserScopeContext;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.common.util.error.PermissionException;
import vn.system.app.modules.companysalarygrade.domain.CompanySalaryGrade;
import vn.system.app.modules.companysalarygrade.domain.request.*;
import vn.system.app.modules.companysalarygrade.domain.response.*;
import vn.system.app.modules.companysalarygrade.repository.CompanySalaryGradeRepository;
import vn.system.app.modules.companyjobtitle.repository.CompanyJobTitleRepository;
import vn.system.app.modules.user.domain.User;
import vn.system.app.modules.user.repository.UserRepository;
import vn.system.app.modules.userposition.repository.UserPositionRepository;

@Service
@RequiredArgsConstructor
public class CompanySalaryGradeService {

    private final CompanySalaryGradeRepository repo;
    private final CompanyJobTitleRepository companyJobTitleRepo;
    private final UserRepository userRepo;
    private final UserPositionRepository userPositionRepo;

    private void validateGrade(Integer gradeLevel) {
        if (gradeLevel == null || gradeLevel <= 0) {
            throw new IdInvalidException("gradeLevel phải lớn hơn 0");
        }
    }

    // IDOR protection — companyJobTitleId phải thuộc công ty trong phạm vi người gọi
    public void checkScope(Long companyJobTitleId) {
        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope == null || scope.isSuperAdmin() || scope.isAdminLevel()) {
            return;
        }
        Long companyId = companyJobTitleRepo.findById(companyJobTitleId)
                .map(cjt -> cjt.getCompany().getId())
                .orElse(null);
        if (companyId == null || scope.companyIds() == null || !scope.companyIds().contains(companyId)) {
            throw new PermissionException("Bạn không có quyền thao tác trên khung lương của công ty này");
        }
    }

    // ======================================================
    // CREATE
    // ======================================================
    @Transactional
    public ResCompanySalaryGradeDTO create(ReqCreateCompanySalaryGradeDTO req) {
        validateGrade(req.getGradeLevel());

        if (!companyJobTitleRepo.existsById(req.getCompanyJobTitleId())) {
            throw new IdInvalidException("CompanyJobTitle ID không tồn tại");
        }
        checkScope(req.getCompanyJobTitleId());

        if (repo.existsByCompanyJobTitleIdAndGradeLevel(
                req.getCompanyJobTitleId(), req.getGradeLevel())) {
            throw new IdInvalidException("Bậc lương đã tồn tại");
        }

        CompanySalaryGrade entity = new CompanySalaryGrade();
        entity.setCompanyJobTitleId(req.getCompanyJobTitleId());
        entity.setGradeLevel(req.getGradeLevel());

        return toDTO(repo.save(entity));
    }

    // ======================================================
    // UPDATE
    // ======================================================
    @Transactional
    public ResCompanySalaryGradeDTO update(Long id, ReqUpdateCompanySalaryGradeDTO req) {
        validateGrade(req.getGradeLevel());

        CompanySalaryGrade entity = repo.findById(id)
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy bậc lương"));
        checkScope(entity.getCompanyJobTitleId());

        if (!entity.isActive()) {
            throw new IdInvalidException("Bậc lương đã vô hiệu, không thể cập nhật");
        }

        boolean exists = repo.existsByCompanyJobTitleIdAndGradeLevel(
                entity.getCompanyJobTitleId(), req.getGradeLevel());

        if (exists && !req.getGradeLevel().equals(entity.getGradeLevel())) {
            throw new IdInvalidException("Bậc lương mới đã tồn tại");
        }

        entity.setGradeLevel(req.getGradeLevel());
        return toDTO(repo.save(entity));
    }

    // ======================================================
    // DELETE (SOFT DELETE)
    // ======================================================
    @Transactional
    public void delete(Long id) {
        CompanySalaryGrade entity = repo.findById(id)
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy bậc lương"));
        checkScope(entity.getCompanyJobTitleId());

        if (!entity.isActive()) {
            throw new IdInvalidException("Bậc lương đã bị vô hiệu");
        }

        entity.setActive(false);
        repo.save(entity);
    }

    // ======================================================
    // RESTORE
    // ======================================================
    @Transactional
    public ResCompanySalaryGradeDTO restore(Long id) {
        CompanySalaryGrade entity = repo.findById(id)
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy bậc lương"));
        checkScope(entity.getCompanyJobTitleId());

        if (entity.isActive()) {
            throw new IdInvalidException("Bậc lương đang hoạt động");
        }

        entity.setActive(true);
        return toDTO(repo.save(entity));
    }

    // ======================================================
    // FETCH ALL theo companyJobTitleId (admin dùng)
    // ======================================================
    public List<ResCompanySalaryGradeDTO> fetch(Long companyJobTitleId) {
        if (companyJobTitleId == null || companyJobTitleId <= 0) {
            throw new IdInvalidException("companyJobTitleId không hợp lệ");
        }
        checkScope(companyJobTitleId);

        return repo.findByCompanyJobTitleIdOrderByGradeLevelAsc(companyJobTitleId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    // ======================================================
    // FETCH CÁ NHÂN — chỉ thấy khung lương của chức danh mình
    // ======================================================
    public List<ResCompanySalaryGradeDTO> fetchMy() {
        String email = SecurityUtil.getCurrentUserLogin()
                .orElseThrow(() -> new IdInvalidException("Chưa đăng nhập"));

        User user = userRepo.findByEmail(email);
        if (user == null)
            throw new IdInvalidException("Không tìm thấy user");

        List<Long> myJobTitleIds = userPositionRepo
                .findByUser_IdAndActiveTrue(user.getId())
                .stream()
                .filter(p -> "COMPANY".equalsIgnoreCase(p.getSource()))
                .map(p -> p.getCompanyJobTitle().getId())
                .collect(Collectors.toList()); // ← đổi thành toList()

        if (myJobTitleIds.isEmpty())
            return List.of();

        return repo.findByCompanyJobTitleIdInAndActiveTrue(myJobTitleIds)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    // ======================================================
    // FETCH THEO CÔNG TY — thấy tất cả active theo company
    // của user (dùng cho HR / trưởng phòng xem toàn công ty)
    // ======================================================
    public List<ResCompanySalaryGradeDTO> fetchByMyCompany() {
        String email = SecurityUtil.getCurrentUserLogin()
                .orElseThrow(() -> new IdInvalidException("Chưa đăng nhập"));

        User user = userRepo.findByEmail(email);
        if (user == null)
            throw new IdInvalidException("Không tìm thấy user");

        List<Long> companyIds = userPositionRepo
                .findByUser_IdAndActiveTrue(user.getId())
                .stream()
                .map(p -> switch (p.getSource().toUpperCase()) {
                    case "COMPANY" -> p.getCompanyJobTitle().getCompany().getId();
                    case "DEPARTMENT" -> p.getDepartmentJobTitle().getDepartment().getCompany().getId();
                    case "SECTION" -> p.getSectionJobTitle().getSection().getDepartment().getCompany().getId();
                    default -> null;
                })
                .filter(id -> id != null)
                .collect(Collectors.toList());

        if (companyIds.isEmpty())
            return List.of();

        List<Long> jobTitleIds = companyJobTitleRepo
                .findByCompany_IdIn(companyIds)
                .stream()
                .map(cjt -> cjt.getId())
                .collect(Collectors.toList());
        if (jobTitleIds.isEmpty())
            return List.of();

        return repo.findByCompanyJobTitleIdIn(jobTitleIds)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    // ======================================================
    // MAPPER
    // ======================================================
    private ResCompanySalaryGradeDTO toDTO(CompanySalaryGrade e) {
        ResCompanySalaryGradeDTO dto = new ResCompanySalaryGradeDTO();
        dto.setId(e.getId());
        dto.setCompanyJobTitleId(e.getCompanyJobTitleId());
        dto.setGradeLevel(e.getGradeLevel());
        dto.setActive(e.isActive());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        dto.setCreatedBy(e.getCreatedBy());
        dto.setUpdatedBy(e.getUpdatedBy());
        return dto;
    }
}