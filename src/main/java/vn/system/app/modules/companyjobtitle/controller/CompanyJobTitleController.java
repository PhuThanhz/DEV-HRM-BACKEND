package vn.system.app.modules.companyjobtitle.controller;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.turkraft.springfilter.boot.Filter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.annotation.ApiMessage;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.companyjobtitle.domain.CompanyJobTitle;
import vn.system.app.modules.companyjobtitle.domain.request.ReqCompanyJobTitleDTO;
import vn.system.app.modules.companyjobtitle.domain.response.ResCompanyJobTitleDTO;
import vn.system.app.modules.companyjobtitle.service.CompanyJobTitleService;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*") // Giữ nguyên, có thể thay bằng domain cụ thể sau
@Validated
public class CompanyJobTitleController {

    private final CompanyJobTitleService service;

    public CompanyJobTitleController(CompanyJobTitleService service) {
        this.service = service;
    }

    /*
     * =====================================================
     * CREATE
     * =====================================================
     */
    @PostMapping("/company-job-titles")
    @ApiMessage("Gán chức danh vào công ty")
    public ResponseEntity<ResCompanyJobTitleDTO> create(
            @Valid @RequestBody ReqCompanyJobTitleDTO req)
            throws IdInvalidException {

        CompanyJobTitle entity = service.handleCreate(req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.convertToResDTO(entity));
    }

    /*
     * =====================================================
     * SOFT DELETE
     * =====================================================
     */
    @DeleteMapping("/company-job-titles/{id}")
    @ApiMessage("Huỷ gán chức danh công ty")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> delete(
            @PathVariable @Min(value = 1, message = "ID phải lớn hơn 0") Long id)
            throws IdInvalidException {

        service.handleDelete(id);
        return ResponseEntity.noContent().build();
    }

    /*
     * =====================================================
     * RESTORE
     * =====================================================
     */
    @PatchMapping("/company-job-titles/{id}/restore")
    @ApiMessage("Khôi phục chức danh công ty")
    public ResponseEntity<ResCompanyJobTitleDTO> restore(
            @PathVariable @Min(value = 1, message = "ID phải lớn hơn 0") Long id)
            throws IdInvalidException {

        return ResponseEntity.ok(
                service.convertToResDTO(service.restore(id)));
    }

    /*
     * =====================================================
     * GET ONE
     * =====================================================
     */
    @GetMapping("/company-job-titles/{id}")
    @ApiMessage("Chi tiết gán chức danh công ty")
    public ResponseEntity<ResCompanyJobTitleDTO> fetchOne(
            @PathVariable @Min(value = 1, message = "ID phải lớn hơn 0") Long id)
            throws IdInvalidException {

        return ResponseEntity.ok(
                service.convertToResDTO(service.fetchEntityByIdWithScopeCheck(id)));
    }

    /*
     * =====================================================
     * GET ALL (PAGINATION – RIÊNG BẢNG COMPANY_JOB_TITLES)
     * =====================================================
     */
    @GetMapping("/company-job-titles")
    @ApiMessage("Danh sách gán chức danh công ty (riêng bảng)")
    public ResponseEntity<ResultPaginationDTO> fetchAll(
            @Filter Specification<CompanyJobTitle> spec,
            Pageable pageable) {

        return ResponseEntity.ok(service.fetchAll(spec, pageable));
    }

    /*
     * =====================================================
     * ⭐ GET JOB TITLES OF COMPANY (ĐỔ TỪ DƯỚI LÊN)
     * =====================================================
     */
    @GetMapping("/companies/{companyId}/job-titles")
    @ApiMessage("Danh sách chức danh của công ty (tổng hợp từ Company + Department + Section)")
    public ResponseEntity<List<ResCompanyJobTitleDTO>> getAllJobTitlesOfCompany(
            @PathVariable @NotNull(message = "Company ID không được để trống") @Min(value = 1, message = "Company ID phải lớn hơn 0") Long companyId) {

        return ResponseEntity.ok(
                service.fetchAllJobTitlesOfCompany(companyId));
    }

    /*
     * =====================================================
     * ⭐ GET ID CHỨC DANH ĐÃ GÁN Ở PHÒNG BAN CỦA CÔNG TY
     * =====================================================
     */
    @GetMapping("/companies/{companyId}/department-job-titles/ids")
    @ApiMessage("Danh sách ID chức danh đã gán active ở phòng ban thuộc công ty")
    public ResponseEntity<List<Long>> getDepartmentJobTitleIdsByCompany(
            @PathVariable @NotNull(message = "Company ID không được để trống") @Min(value = 1, message = "Company ID phải lớn hơn 0") Long companyId) {

        List<Long> jobIds = service.getActiveDepartmentJobTitleIdsByCompany(companyId);
        return ResponseEntity.ok(jobIds);
    }

    /*
     * =====================================================
     * ⭐ GET ID CHỨC DANH ĐÃ GÁN Ở BỘ PHẬN CỦA CÔNG TY
     * =====================================================
     */
    @GetMapping("/companies/{companyId}/section-job-titles/ids")
    @ApiMessage("Danh sách ID chức danh đã gán active ở bộ phận thuộc công ty")
    public ResponseEntity<List<Long>> getSectionJobTitleIdsByCompany(
            @PathVariable @NotNull(message = "Company ID không được để trống") @Min(value = 1, message = "Company ID phải lớn hơn 0") Long companyId) {

        List<Long> jobIds = service.getActiveSectionJobTitleIdsByCompany(companyId);
        return ResponseEntity.ok(jobIds);
    }
}