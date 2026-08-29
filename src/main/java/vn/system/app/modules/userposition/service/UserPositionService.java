package vn.system.app.modules.userposition.service;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.common.util.UserScopeContext;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.companyjobtitle.domain.CompanyJobTitle;
import vn.system.app.modules.companyjobtitle.repository.CompanyJobTitleRepository;
import vn.system.app.modules.departmentjobtitle.domain.DepartmentJobTitle;
import vn.system.app.modules.departmentjobtitle.repository.DepartmentJobTitleRepository;
import vn.system.app.modules.sectionjobtitle.domain.SectionJobTitle;
import vn.system.app.modules.sectionjobtitle.repository.SectionJobTitleRepository;
import vn.system.app.modules.user.domain.User;
import vn.system.app.modules.user.repository.UserRepository;
import vn.system.app.modules.userposition.domain.UserPosition;
import vn.system.app.modules.userposition.domain.request.ReqCreateUserPositionDTO;
import vn.system.app.modules.userposition.domain.response.ResUserPositionDTO;
import vn.system.app.modules.userposition.repository.UserPositionRepository;

@Service
public class UserPositionService {

    private final UserPositionRepository repo;
    private final UserRepository userRepo;
    private final CompanyJobTitleRepository companyJobTitleRepo;
    private final DepartmentJobTitleRepository departmentJobTitleRepo;
    private final SectionJobTitleRepository sectionJobTitleRepo;

    public UserPositionService(
            UserPositionRepository repo,
            UserRepository userRepo,
            CompanyJobTitleRepository companyJobTitleRepo,
            DepartmentJobTitleRepository departmentJobTitleRepo,
            SectionJobTitleRepository sectionJobTitleRepo) {

        this.repo = repo;
        this.userRepo = userRepo;
        this.companyJobTitleRepo = companyJobTitleRepo;
        this.departmentJobTitleRepo = departmentJobTitleRepo;
        this.sectionJobTitleRepo = sectionJobTitleRepo;
    }

    // =====================================================
    // CREATE (hoặc reactivate nếu đã tồn tại record cũ)
    // =====================================================
    @Transactional
    public ResUserPositionDTO handleCreate(String userId, ReqCreateUserPositionDTO req) {

        User user = userRepo.findById(userId)
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy user ID = " + userId));

        switch (req.getSource().toUpperCase()) {

            case "COMPANY" -> {
                if (req.getCompanyJobTitleId() == null)
                    throw new IdInvalidException("companyJobTitleId không được để trống.");

                // Đã active rồi → báo lỗi
                if (repo.existsByUser_IdAndCompanyJobTitle_IdAndActiveTrue(userId, req.getCompanyJobTitleId()))
                    throw new IdInvalidException("Chức danh này đã được gán cho user ở cấp công ty.");

                CompanyJobTitle cjt = companyJobTitleRepo.findById(req.getCompanyJobTitleId())
                        .orElseThrow(() -> new IdInvalidException("Không tìm thấy CompanyJobTitle."));

                if (!cjt.getJobTitle().isActive())
                    throw new IdInvalidException("Chức danh đã ngừng hoạt động, không thể gán.");

                // Tìm record cũ đã bị soft-delete → reactivate, không tạo mới
                UserPosition position = repo
                        .findByUser_IdAndCompanyJobTitle_IdAndActiveFalse(userId, req.getCompanyJobTitleId())
                        .orElseGet(() -> {
                            UserPosition p = new UserPosition();
                            p.setUser(user);
                            p.setSource(req.getSource().toUpperCase());
                            p.setCompanyJobTitle(cjt);
                            return p;
                        });

                position.setActive(true);
                return convertToResDTO(repo.save(position));
            }

            case "DEPARTMENT" -> {
                if (req.getDepartmentJobTitleId() == null)
                    throw new IdInvalidException("departmentJobTitleId không được để trống.");

                if (repo.existsByUser_IdAndDepartmentJobTitle_IdAndActiveTrue(userId, req.getDepartmentJobTitleId()))
                    throw new IdInvalidException("Chức danh này đã được gán cho user ở cấp phòng ban.");

                DepartmentJobTitle djt = departmentJobTitleRepo.findById(req.getDepartmentJobTitleId())
                        .orElseThrow(() -> new IdInvalidException("Không tìm thấy DepartmentJobTitle."));

                if (!djt.getJobTitle().isActive())
                    throw new IdInvalidException("Chức danh đã ngừng hoạt động, không thể gán.");

                UserPosition position = repo
                        .findByUser_IdAndDepartmentJobTitle_IdAndActiveFalse(userId, req.getDepartmentJobTitleId())
                        .orElseGet(() -> {
                            UserPosition p = new UserPosition();
                            p.setUser(user);
                            p.setSource(req.getSource().toUpperCase());
                            p.setDepartmentJobTitle(djt);
                            return p;
                        });

                position.setActive(true);
                return convertToResDTO(repo.save(position));
            }

            case "SECTION" -> {
                if (req.getSectionJobTitleId() == null)
                    throw new IdInvalidException("sectionJobTitleId không được để trống.");

                if (repo.existsByUser_IdAndSectionJobTitle_IdAndActiveTrue(userId, req.getSectionJobTitleId()))
                    throw new IdInvalidException("Chức danh này đã được gán cho user ở cấp bộ phận.");

                SectionJobTitle sjt = sectionJobTitleRepo.findById(req.getSectionJobTitleId())
                        .orElseThrow(() -> new IdInvalidException("Không tìm thấy SectionJobTitle."));

                if (!sjt.getJobTitle().isActive())
                    throw new IdInvalidException("Chức danh đã ngừng hoạt động, không thể gán.");

                UserPosition position = repo
                        .findByUser_IdAndSectionJobTitle_IdAndActiveFalse(userId, req.getSectionJobTitleId())
                        .orElseGet(() -> {
                            UserPosition p = new UserPosition();
                            p.setUser(user);
                            p.setSource(req.getSource().toUpperCase());
                            p.setSectionJobTitle(sjt);
                            return p;
                        });

                position.setActive(true);
                return convertToResDTO(repo.save(position));
            }

            default ->
                throw new IdInvalidException("Source không hợp lệ. Chỉ chấp nhận: COMPANY, DEPARTMENT, SECTION.");
        }
    }

    // =====================================================
    // SOFT DELETE
    // =====================================================
    @Transactional
    public void handleDelete(Long id) {

        UserPosition position = repo.findById(id)
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy vị trí ID = " + id));

        if (!position.isActive()) {
            throw new IdInvalidException("Vị trí này đã bị vô hiệu hóa rồi.");
        }

        position.setActive(false);
        repo.save(position);
    }

    // =====================================================
    // GET ALL BY USER
    // =====================================================
    public List<ResUserPositionDTO> fetchByUser(String userId) {

        if (!userRepo.existsById(userId)) {
            throw new IdInvalidException("Không tìm thấy user ID = " + userId);
        }

        return repo.findActiveFullByUserId(userId)
                .stream()
                .map(this::convertToResDTO)
                .collect(Collectors.toList());
    }

    // =====================================================
    // GET USERS BY COMPANY
    // =====================================================
    public List<ResUserPositionDTO> fetchByCompany(Long companyId) {

        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope != null && !scope.isAdminLevel() && !scope.companyIds().contains(companyId)) {
            return List.of();
        }

        return repo.findActiveByCompanyId(companyId)
                .stream()
                .map(this::convertToResDTO)
                .collect(Collectors.toList());
    }

    // =====================================================
    // GET COMPANY IDs BY USER
    // =====================================================
    public Set<Long> getCompanyIdsByUser(String userId) {
        return repo.findActiveCompanyIdsByUserId(userId)
                .stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    // =====================================================
    // GET DEPARTMENT IDs BY USER
    // =====================================================
    public Set<Long> getDepartmentIdsByUser(String userId) {
        return repo.findActiveDepartmentIdsByUserId(userId)
                .stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    // =====================================================
    // CONVERTER
    // =====================================================
    private ResUserPositionDTO convertToResDTO(UserPosition p) {

        ResUserPositionDTO res = new ResUserPositionDTO();
        res.setId(p.getId());
        res.setSource(p.getSource());
        res.setActive(p.isActive());
        res.setCreatedAt(p.getCreatedAt());
        res.setUpdatedAt(p.getUpdatedAt());
        res.setCreatedBy(p.getCreatedBy());
        res.setUpdatedBy(p.getUpdatedBy());

        ResUserPositionDTO.UserInfo userInfo = new ResUserPositionDTO.UserInfo();
        userInfo.setId(p.getUser().getId());
        userInfo.setName(p.getUser().getName());
        userInfo.setEmail(p.getUser().getEmail());
        res.setUser(userInfo);

        switch (p.getSource().toUpperCase()) {

            case "COMPANY" -> {
                var cjt = p.getCompanyJobTitle();
                if (cjt == null) break;
                res.setJobTitle(buildJobTitleInfo(cjt.getJobTitle()));

                ResUserPositionDTO.CompanyInfo ci = new ResUserPositionDTO.CompanyInfo();
                ci.setId(cjt.getCompany().getId());
                ci.setName(cjt.getCompany().getName());
                res.setCompany(ci);
            }

            case "DEPARTMENT" -> {
                var djt = p.getDepartmentJobTitle();
                if (djt == null) break;
                res.setJobTitle(buildJobTitleInfo(djt.getJobTitle()));

                ResUserPositionDTO.CompanyInfo ci = new ResUserPositionDTO.CompanyInfo();
                ci.setId(djt.getDepartment().getCompany().getId());
                ci.setName(djt.getDepartment().getCompany().getName());
                res.setCompany(ci);

                ResUserPositionDTO.DepartmentInfo di = new ResUserPositionDTO.DepartmentInfo();
                di.setId(djt.getDepartment().getId());
                di.setName(djt.getDepartment().getName());
                res.setDepartment(di);
            }

            case "SECTION" -> {
                var sjt = p.getSectionJobTitle();
                if (sjt == null) break;
                res.setJobTitle(buildJobTitleInfo(sjt.getJobTitle()));

                ResUserPositionDTO.CompanyInfo ci = new ResUserPositionDTO.CompanyInfo();
                ci.setId(sjt.getSection().getDepartment().getCompany().getId());
                ci.setName(sjt.getSection().getDepartment().getCompany().getName());
                res.setCompany(ci);

                ResUserPositionDTO.DepartmentInfo di = new ResUserPositionDTO.DepartmentInfo();
                di.setId(sjt.getSection().getDepartment().getId());
                di.setName(sjt.getSection().getDepartment().getName());
                res.setDepartment(di);

                ResUserPositionDTO.SectionInfo si = new ResUserPositionDTO.SectionInfo();
                si.setId(sjt.getSection().getId());
                si.setName(sjt.getSection().getName());
                res.setSection(si);
            }

            default -> {
            }
        }

        return res;
    }

    private ResUserPositionDTO.JobTitleInfo buildJobTitleInfo(
            vn.system.app.modules.jobtitle.domain.JobTitle jt) {

        ResUserPositionDTO.JobTitleInfo info = new ResUserPositionDTO.JobTitleInfo();
        info.setId(jt.getId());
        info.setNameVi(jt.getNameVi());
        info.setNameEn(jt.getNameEn());

        if (jt.getPositionLevel() != null) {
            String code = jt.getPositionLevel().getCode();
            info.setPositionCode(code);
            info.setBand(code.replaceAll("[0-9]", ""));
            info.setLevelNumber(Integer.parseInt(code.replaceAll("[^0-9]", "")));
            info.setBandOrder(jt.getPositionLevel().getBandOrder());
        }

        return info;
    }
}
