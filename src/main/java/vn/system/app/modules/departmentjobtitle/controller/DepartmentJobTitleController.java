package vn.system.app.modules.departmentjobtitle.controller;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import com.turkraft.springfilter.boot.Filter;

import jakarta.validation.Valid;
import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.annotation.ApiMessage;
import vn.system.app.common.util.error.IdInvalidException;

import vn.system.app.modules.departmentjobtitle.domain.DepartmentJobTitle;
import vn.system.app.modules.departmentjobtitle.domain.request.ReqDepartmentJobTitleDTO;
import vn.system.app.modules.departmentjobtitle.domain.response.ResDepartmentJobTitleDTO;
import vn.system.app.modules.departmentjobtitle.domain.response.ResJobTitleAssignStatusDTO;
import vn.system.app.modules.departmentjobtitle.service.DepartmentJobTitleService;
import vn.system.app.modules.departmentjobtitle.service.DepartmentJobTitleQueryService;
import vn.system.app.modules.departmentjobtitle.service.DepartmentJobTitleScopeService;

@RestController
@RequestMapping("/api/v1")
public class DepartmentJobTitleController {

    private final DepartmentJobTitleService service;
    private final DepartmentJobTitleQueryService queryService;
    private final DepartmentJobTitleScopeService scopeService;

    public DepartmentJobTitleController(
            DepartmentJobTitleService service,
            DepartmentJobTitleQueryService queryService,
            DepartmentJobTitleScopeService scopeService) {

        this.service = service;
        this.queryService = queryService;
        this.scopeService = scopeService;
    }

    /*
     * =====================================================
     * CREATE
     * =====================================================
     */
    @PostMapping("/department-job-titles")
    @ApiMessage("Gán chức danh vào phòng ban")
    public ResponseEntity<ResDepartmentJobTitleDTO> create(
            @Valid @RequestBody ReqDepartmentJobTitleDTO req)
            throws IdInvalidException {

        DepartmentJobTitle entity = this.service.handleCreate(req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(this.service.convertToResDTO(entity));
    }

    /*
     * =====================================================
     * SOFT DELETE
     * =====================================================
     */
    @DeleteMapping("/department-job-titles/{id}")
    @ApiMessage("Huỷ gán chức danh phòng ban")
    public ResponseEntity<Void> delete(@PathVariable Long id)
            throws IdInvalidException {

        this.service.handleDelete(id);
        return ResponseEntity.ok().build();
    }

    /*
     * =====================================================
     * RESTORE
     * =====================================================
     */
    @PatchMapping("/department-job-titles/{id}/restore")
    @ApiMessage("Kích hoạt lại chức danh phòng ban")
    public ResponseEntity<ResDepartmentJobTitleDTO> restore(@PathVariable Long id)
            throws IdInvalidException {

        DepartmentJobTitle entity = this.service.restore(id);
        return ResponseEntity.ok(this.service.convertToResDTO(entity));
    }

    /*
     * =====================================================
     * GET ONE
     * =====================================================
     */
    @GetMapping("/department-job-titles/{id}")
    @ApiMessage("Chi tiết gán chức danh phòng ban")
    public ResponseEntity<ResDepartmentJobTitleDTO> fetchOne(@PathVariable Long id)
            throws IdInvalidException {

        DepartmentJobTitle entity = this.service.fetchEntityByIdWithScopeCheck(id);
        return ResponseEntity.ok(this.service.convertToResDTO(entity));
    }

    /*
     * =====================================================
     * GET ALL (PAGINATION)
     * =====================================================
     */
    @GetMapping("/department-job-titles")
    @ApiMessage("Danh sách gán chức danh phòng ban")
    public ResponseEntity<ResultPaginationDTO> fetchAll(
            @Filter Specification<DepartmentJobTitle> spec,
            Pageable pageable) {

        return ResponseEntity.ok(this.service.fetchAll(spec, pageable));
    }

    /*
     * =====================================================
     * GET DEPARTMENT JOB TITLES (DIRECT)
     * =====================================================
     */
    @GetMapping("/departments/{departmentId}/job-titles")
    @ApiMessage("Danh sách chức danh gán trực tiếp ở phòng ban")
    public ResponseEntity<List<ResDepartmentJobTitleDTO>> fetchJobTitlesByDepartment(
            @PathVariable("departmentId") Long departmentId)
            throws IdInvalidException {

        List<DepartmentJobTitle> list = this.service.fetchByDepartment(departmentId);

        return ResponseEntity.ok(
                list.stream()
                        .map(this.service::convertToResDTO)
                        .collect(Collectors.toList()));
    }

    /*
     * =====================================================
     * API CŨ — COMPANY + DEPARTMENT + SECTION
     * =====================================================
     */
    @GetMapping("/departments/{departmentId}/company-job-titles")
    @ApiMessage("Danh sách CompanyJobTitle có hiệu lực tại phòng ban")
    public ResponseEntity<List<ResDepartmentJobTitleDTO>> fetchAllCompanyJobTitlesOfDepartment(
            @PathVariable("departmentId") Long departmentId)
            throws IdInvalidException {

        return ResponseEntity.ok(
                this.service.fetchAllCompanyJobTitlesOfDepartment(departmentId));
    }

    /*
     * =====================================================
     * API MỚI — DEPARTMENT + SECTION ONLY
     * =====================================================
     */
    @GetMapping("/departments/{departmentId}/job-titles/mixed")
    @ApiMessage("Danh sách chức danh từ phòng ban + bộ phận (không lấy công ty)")
    public ResponseEntity<List<ResDepartmentJobTitleDTO>> fetchDeptAndSection(
            @PathVariable Long departmentId) {

        return ResponseEntity.ok(
                queryService.fetchDeptAndSectionJobTitles(departmentId));
    }

    /*
     * =====================================================
     * API MỚI NHẤT — THEO PHẠM VI QUYỀN USER
     * =====================================================
     */
    @GetMapping("/departments/{departmentId}/job-titles/scope")
    @ApiMessage("Danh sách chức danh theo phạm vi quyền user")
    public ResponseEntity<List<ResDepartmentJobTitleDTO>> fetchByScope(
            @PathVariable Long departmentId) {

        return ResponseEntity.ok(
                scopeService.fetchByScope(departmentId));
    }

    /*
     * =====================================================
     * 🔰 DANH SÁCH CHỨC DANH KÈM TRẠNG THÁI GÁN — CÓ FILTER + PAGINATION
     * =====================================================
     */
    @GetMapping("/departments/{departmentId}/job-titles/assign-status")
    @ApiMessage("Danh sách chức danh kèm trạng thái gán vào phòng ban")
    public ResponseEntity<ResultPaginationDTO> fetchJobTitlesWithAssignStatus(
            @PathVariable Long departmentId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String band,
            @RequestParam(required = false) Integer level,
            Pageable pageable)
            throws IdInvalidException {

        return ResponseEntity.ok(
                this.service.fetchJobTitlesWithAssignStatus(
                        departmentId, search, status, band, level, pageable));
    }
}