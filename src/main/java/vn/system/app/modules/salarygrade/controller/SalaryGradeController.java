package vn.system.app.modules.salarygrade.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.turkraft.springfilter.boot.Filter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.annotation.ApiMessage;
import vn.system.app.modules.salarygrade.domain.SalaryGrade;
import vn.system.app.modules.salarygrade.domain.request.ReqCreateSalaryGradeDTO;
import vn.system.app.modules.salarygrade.domain.response.ResSalaryGradeDTO;
import vn.system.app.modules.salarygrade.service.SalaryGradeService;

@RestController
@RequestMapping("/api/v1/salary-grades")
@Validated
public class SalaryGradeController {

    private final SalaryGradeService salaryGradeService;

    public SalaryGradeController(SalaryGradeService salaryGradeService) {
        this.salaryGradeService = salaryGradeService;
    }

    // ==========================================
    // CREATE
    // ==========================================
    @PostMapping
    @ApiMessage("Tạo bậc lương cho chức danh")
    public ResponseEntity<ResSalaryGradeDTO> create(
            @Valid @RequestBody ReqCreateSalaryGradeDTO req) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(salaryGradeService.handleCreate(req));
    }

    // ==========================================
    // DELETE (SOFT)
    // ==========================================
    @DeleteMapping("/{id}")
    @ApiMessage("Xoá bậc lương")
    public ResponseEntity<Void> delete(
            @PathVariable @Min(1) Long id) {

        salaryGradeService.handleDelete(id);
        return ResponseEntity.noContent().build();
    }

    // ==========================================
    // GET BY CONTEXT (🔥 FRONTEND PHẢI DÙNG)
    // ==========================================
    @GetMapping("/by-context")
    @ApiMessage("Danh sách bậc lương theo chức danh")
    public ResponseEntity<ResultPaginationDTO> fetchByContext(
            @RequestParam @NotBlank String contextType,
            @RequestParam @NotNull Long contextId,
            Pageable pageable) {

        return ResponseEntity.ok(
                salaryGradeService.fetchByContext(contextType, contextId, pageable));
    }

    // ==========================================
    // GET ALL (ADMIN)
    // ==========================================
    @GetMapping
    @ApiMessage("Danh sách bậc lương (admin)")
    public ResponseEntity<ResultPaginationDTO> fetchAll(
            @Filter Specification<SalaryGrade> spec,
            Pageable pageable) {

        return ResponseEntity.ok(
                salaryGradeService.fetchAll(spec, pageable));
    }
}
