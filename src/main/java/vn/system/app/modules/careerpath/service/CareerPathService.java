package vn.system.app.modules.careerpath.service;

import java.util.*;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.careerpath.domain.CareerPath;
import vn.system.app.modules.careerpath.domain.request.CareerPathBulkRequest;
import vn.system.app.modules.careerpath.domain.request.CareerPathRequest;
import vn.system.app.modules.careerpath.domain.response.CareerPathBulkResult;
import vn.system.app.modules.careerpath.domain.response.CareerPathPreviewResponse;
import vn.system.app.modules.careerpath.domain.response.CareerPathResponse;
import vn.system.app.modules.careerpath.domain.response.ResCareerPathBandGroupDTO;
import vn.system.app.modules.careerpath.repository.CareerPathRepository;
import vn.system.app.modules.department.domain.Department;
import vn.system.app.modules.department.repository.DepartmentRepository;
import vn.system.app.modules.departmentjobtitle.repository.DepartmentJobTitleRepository;
import vn.system.app.modules.jobtitle.domain.JobTitle;
import vn.system.app.modules.jobtitle.domain.response.JobTitleByLevelResponse;
import vn.system.app.modules.jobtitle.repository.JobTitleRepository;

@Service
public class CareerPathService {

    private final CareerPathRepository repo;
    private final DepartmentRepository departmentRepo;
    private final JobTitleRepository jobTitleRepo;
    private final DepartmentJobTitleRepository departmentJobTitleRepo;

    public CareerPathService(
            CareerPathRepository repo,
            DepartmentRepository departmentRepo,
            JobTitleRepository jobTitleRepo,
            DepartmentJobTitleRepository departmentJobTitleRepo) {

        this.repo = repo;
        this.departmentRepo = departmentRepo;
        this.jobTitleRepo = jobTitleRepo;
        this.departmentJobTitleRepo = departmentJobTitleRepo;
    }

    // =====================================================
    // CREATE
    // =====================================================
    @Transactional
    public CareerPathResponse handleCreate(CareerPathRequest req) {

        if (repo.existsByDepartment_IdAndJobTitle_Id(req.getDepartmentId(), req.getJobTitleId())) {
            throw new IdInvalidException("Phòng ban này đã có lộ trình cho chức danh này");
        }

        Department dep = departmentRepo.findById(req.getDepartmentId())
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy phòng ban"));

        JobTitle jt = jobTitleRepo.findById(req.getJobTitleId())
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy chức danh"));

        CareerPath e = new CareerPath();
        e.setDepartment(dep);
        e.setJobTitle(jt);
        e.setJobStandard(req.getJobStandard());
        e.setTrainingRequirement(req.getTrainingRequirement());
        e.setEvaluationMethod(req.getEvaluationMethod());
        e.setRequiredTime(req.getRequiredTime());
        e.setTrainingOutcome(req.getTrainingOutcome());
        e.setPerformanceRequirement(req.getPerformanceRequirement());
        e.setSalaryNote(req.getSalaryNote());
        e.setStatus(req.getStatus());
        e.setActive(true);

        repo.save(e);
        return convertToResponse(e);
    }

    // =====================================================
    // UPDATE
    // =====================================================
    @Transactional
    public CareerPathResponse handleUpdate(Long id, CareerPathRequest req) {
        CareerPath e = fetchById(id);

        e.setJobStandard(req.getJobStandard());
        e.setTrainingRequirement(req.getTrainingRequirement());
        e.setEvaluationMethod(req.getEvaluationMethod());
        e.setRequiredTime(req.getRequiredTime());
        e.setTrainingOutcome(req.getTrainingOutcome());
        e.setPerformanceRequirement(req.getPerformanceRequirement());
        e.setSalaryNote(req.getSalaryNote());
        e.setStatus(req.getStatus());

        repo.save(e);
        return convertToResponse(e);
    }

    // =====================================================
    // DEACTIVATE
    // =====================================================
    @Transactional
    public void handleDeactivate(Long id) {
        CareerPath e = fetchById(id);
        if (!e.isActive())
            return;
        e.setActive(false);
        repo.save(e);
    }

    // =====================================================
    // FETCH BASIC
    // =====================================================
    public CareerPath fetchById(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy lộ trình thăng tiến"));
    }

    public List<CareerPathResponse> fetchByDepartment(Long departmentId) {
        return repo.findByDepartment_IdOrderByJobTitle_PositionLevel_BandOrderDesc(departmentId)
                .stream()
                .map(this::convertToResponse)
                .toList();
    }

    public List<CareerPathResponse> fetchAllActive() {
        return repo.findByActiveTrue()
                .stream()
                .map(this::convertToResponse)
                .toList();
    }

    public List<CareerPathResponse> fetchActiveByDepartment(Long departmentId) {
        return repo.findByDepartment_IdAndActiveTrue(departmentId)
                .stream()
                .map(this::convertToResponse)
                .toList();
    }

    // =====================================================
    // GROUP BY BAND
    // =====================================================
    public List<ResCareerPathBandGroupDTO> fetchByDepartmentGroupedByBand(Long departmentId) {

        List<CareerPath> list = repo.findByDepartment_IdOrderByJobTitle_PositionLevel_BandOrderDesc(departmentId);

        var grouped = list.stream()
                .collect(Collectors.groupingBy(cp -> {
                    var pl = cp.getJobTitle().getPositionLevel();
                    return pl != null ? extractBand(pl.getCode()) : "Unknown";
                }));

        return grouped.entrySet().stream()
                .map(entry -> {
                    String band = entry.getKey();
                    List<CareerPath> items = entry.getValue();

                    // ✅ Fix NPE: guard null positionLevel khi sort
                    items.sort(Comparator.comparingInt(cp -> {
                        var pl = cp.getJobTitle().getPositionLevel();
                        return pl != null ? extractLevel(pl.getCode()) : 0;
                    }));

                    ResCareerPathBandGroupDTO dto = new ResCareerPathBandGroupDTO();
                    dto.setBand(band);
                    // bandOrder lấy từ item đầu tiên sau khi sort — guard null để tránh NPE
                    var firstPl = items.get(0).getJobTitle().getPositionLevel();
                    dto.setBandOrder(firstPl != null ? firstPl.getBandOrder() : 0);
                    dto.setPositions(items.stream().map(this::convertToResponse).toList());

                    return dto;
                })
                .sorted(Comparator.comparingInt(ResCareerPathBandGroupDTO::getBandOrder).reversed())
                .toList();
    }

    // =====================================================
    // GLOBAL SORT
    // =====================================================
    public List<CareerPathResponse> fetchGlobalCareerPath(Long departmentId) {
        return repo
                .findByDepartment_IdOrderByJobTitle_PositionLevel_BandOrderDescJobTitle_PositionLevel_CodeAsc(
                        departmentId)
                .stream()
                .map(this::convertToResponse)
                .toList();
    }

    // =====================================================
    // BULK CREATE
    // =====================================================
    @Transactional
    public CareerPathBulkResult handleBulkCreate(CareerPathBulkRequest req) {

        Department dep = departmentRepo.findById(req.getDepartmentId())
                .orElseThrow(() -> new IdInvalidException("Không tìm thấy phòng ban"));

        // ✅ Fix N+1: load toàn bộ jobTitleId đã tồn tại bằng 1 query duy nhất
        Set<Long> existingJobTitleIds = repo.findExistingJobTitleIds(req.getDepartmentId());

        // Load toàn bộ JobTitle cần thiết bằng 1 query (findAllById dùng IN clause)
        List<Long> requestedIds = req.getJobTitleIds();
        Map<Long, JobTitle> jobTitleMap = jobTitleRepo.findAllById(requestedIds)
                .stream()
                .collect(Collectors.toMap(JobTitle::getId, jt -> jt));

        List<CareerPathBulkResult.SkippedItem> skipped = new ArrayList<>();
        List<CareerPath> toCreate = new ArrayList<>();

        for (Long jobTitleId : requestedIds) {

            if (existingJobTitleIds.contains(jobTitleId)) {
                JobTitle jt = jobTitleMap.get(jobTitleId);
                skipped.add(CareerPathBulkResult.SkippedItem.builder()
                        .jobTitleId(jobTitleId)
                        .jobTitleName(jt != null ? jt.getNameVi() : "Không xác định")
                        .reason("Đã tồn tại trong phòng ban này")
                        .build());
                continue;
            }

            JobTitle jt = jobTitleMap.get(jobTitleId);
            if (jt == null) {
                throw new IdInvalidException("Không tìm thấy chức danh: " + jobTitleId);
            }

            CareerPath e = new CareerPath();
            e.setDepartment(dep);
            e.setJobTitle(jt);
            e.setJobStandard(req.getJobStandard());
            e.setTrainingRequirement(req.getTrainingRequirement());
            e.setEvaluationMethod(req.getEvaluationMethod());
            e.setRequiredTime(req.getRequiredTime());
            e.setTrainingOutcome(req.getTrainingOutcome());
            e.setPerformanceRequirement(req.getPerformanceRequirement());
            e.setSalaryNote(req.getSalaryNote());
            e.setStatus(req.getStatus());
            e.setActive(true);

            toCreate.add(e);
        }

        // ✅ Fix N+1: ghi hàng loạt bằng 1 lần saveAll thay vì save() từng bản ghi trong loop
        List<CareerPathResponse> created = repo.saveAll(toCreate)
                .stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());

        return CareerPathBulkResult.builder()
                .created(created)
                .skipped(skipped)
                .totalRequested(requestedIds.size())
                .totalCreated(created.size())
                .totalSkipped(skipped.size())
                .build();
    }

    // =====================================================
    // PREVIEW BULK CREATE
    // =====================================================
    public CareerPathPreviewResponse previewBulkCreate(Long departmentId, List<Long> jobTitleIds) {

        // ✅ Fix N+1: load toàn bộ existingIds và jobTitles bằng 2 query thay vì 2N
        Set<Long> existingJobTitleIds = repo.findExistingJobTitleIds(departmentId);

        Map<Long, JobTitle> jobTitleMap = jobTitleRepo.findAllById(jobTitleIds)
                .stream()
                .collect(Collectors.toMap(JobTitle::getId, jt -> jt));

        List<CareerPathPreviewResponse.PreviewItem> willCreate = new ArrayList<>();
        List<CareerPathPreviewResponse.PreviewItem> willSkip = new ArrayList<>();

        for (Long jobTitleId : jobTitleIds) {
            JobTitle jt = jobTitleMap.get(jobTitleId);

            if (jt == null) {
                throw new IdInvalidException("Không tìm thấy chức danh: " + jobTitleId);
            }

            String name = jt.getNameVi();
            String levelCode = (jt != null && jt.getPositionLevel() != null)
                    ? jt.getPositionLevel().getCode()
                    : null;

            if (existingJobTitleIds.contains(jobTitleId)) {
                willSkip.add(CareerPathPreviewResponse.PreviewItem.builder()
                        .jobTitleId(jobTitleId)
                        .jobTitleName(name)
                        .positionLevelCode(levelCode)
                        .reason("Đã tồn tại trong phòng ban này")
                        .build());
            } else {
                willCreate.add(CareerPathPreviewResponse.PreviewItem.builder()
                        .jobTitleId(jobTitleId)
                        .jobTitleName(name)
                        .positionLevelCode(levelCode)
                        .reason(null)
                        .build());
            }
        }

        return CareerPathPreviewResponse.builder()
                .willCreate(willCreate)
                .willSkip(willSkip)
                .build();
    }

    // =====================================================
    // COPY CONTENT FROM LEVEL
    // =====================================================
    public CareerPathRequest copyContentFromLevel(Long departmentId, String positionLevelCode) {

        List<CareerPath> matches = repo.findByDepartment_IdAndActiveTrue(departmentId)
                .stream()
                .filter(cp -> cp.getJobTitle().getPositionLevel() != null
                        && positionLevelCode.equals(cp.getJobTitle().getPositionLevel().getCode()))
                .toList();

        if (matches.isEmpty()) {
            throw new IdInvalidException(
                    "Không tìm thấy lộ trình active nào ở level " + positionLevelCode + " trong phòng ban này");
        }

        CareerPath source = matches.get(0);

        CareerPathRequest req = new CareerPathRequest();
        // Không set departmentId / jobTitleId — frontend tự điền
        req.setJobStandard(source.getJobStandard());
        req.setTrainingRequirement(source.getTrainingRequirement());
        req.setEvaluationMethod(source.getEvaluationMethod());
        req.setRequiredTime(source.getRequiredTime());
        req.setTrainingOutcome(source.getTrainingOutcome());
        req.setPerformanceRequirement(source.getPerformanceRequirement());
        req.setSalaryNote(source.getSalaryNote());
        req.setStatus(source.getStatus());

        return req;
    }

    // =====================================================
    // FETCH JOB TITLES BY LEVEL
    // =====================================================
    public List<JobTitleByLevelResponse> fetchJobTitlesByLevel(String positionLevelCode, Long departmentId) {

        Set<Long> existingIds = repo.findExistingJobTitleIds(departmentId);

        return departmentJobTitleRepo
                .findByDepartment_IdAndJobTitle_PositionLevel_CodeAndActiveTrue(departmentId, positionLevelCode)
                .stream()
                .map(djt -> {
                    JobTitle jt = djt.getJobTitle();

                    boolean exists = existingIds.contains(jt.getId());

                    return JobTitleByLevelResponse.builder()
                            .id(jt.getId())
                            .nameVi(jt.getNameVi())
                            .nameEn(jt.getNameEn())
                            .positionLevelCode(jt.getPositionLevel() != null ? jt.getPositionLevel().getCode() : null)
                            .bandOrder(jt.getPositionLevel() != null ? jt.getPositionLevel().getBandOrder() : null)
                            .alreadyExists(exists)
                            .build();
                })
                .toList();
    }

    // =====================================================
    // HELPERS
    // =====================================================
    private String extractBand(String code) {
        return code.replaceAll("[0-9]", "");
    }

    private int extractLevel(String code) {
        try {
            return Integer.parseInt(code.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    // =====================================================
    // CONVERT RESPONSE
    // =====================================================
    public CareerPathResponse convertToResponse(CareerPath e) {
        CareerPathResponse r = new CareerPathResponse();

        r.setId(e.getId());
        r.setDepartmentId(e.getDepartment().getId());
        r.setDepartmentName(e.getDepartment().getName());
        r.setJobTitleId(e.getJobTitle().getId());
        r.setJobTitleName(e.getJobTitle().getNameVi());

        var pl = e.getJobTitle().getPositionLevel();
        if (pl != null) {
            r.setPositionLevelCode(pl.getCode());
            r.setBandOrder(pl.getBandOrder());
            r.setLevelNumber(extractLevel(pl.getCode()));
        }

        r.setJobStandard(e.getJobStandard());
        r.setTrainingRequirement(e.getTrainingRequirement());
        r.setEvaluationMethod(e.getEvaluationMethod());
        r.setRequiredTime(e.getRequiredTime());
        r.setTrainingOutcome(e.getTrainingOutcome());
        r.setPerformanceRequirement(e.getPerformanceRequirement());
        r.setSalaryNote(e.getSalaryNote());

        r.setStatus(e.getStatus());
        r.setActive(e.isActive());
        r.setCreatedAt(e.getCreatedAt());
        r.setUpdatedAt(e.getUpdatedAt());
        r.setCreatedBy(e.getCreatedBy());
        r.setUpdatedBy(e.getUpdatedBy());

        return r;
    }
}