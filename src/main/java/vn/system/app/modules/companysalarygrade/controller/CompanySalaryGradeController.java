package vn.system.app.modules.companysalarygrade.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import vn.system.app.common.util.annotation.ApiMessage;
import vn.system.app.modules.companysalarygrade.domain.request.*;
import vn.system.app.modules.companysalarygrade.domain.response.*;
import vn.system.app.modules.companysalarygrade.service.CompanySalaryGradeService;

import jakarta.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/v1/company-salary-grades")
@RequiredArgsConstructor
public class CompanySalaryGradeController {

    private final CompanySalaryGradeService service;

    // ================================
    // CREATE
    // ================================
    @PostMapping
    @ApiMessage("Tạo bậc lương")
    public ResponseEntity<ResCompanySalaryGradeDTO> create(
            @Valid @RequestBody ReqCreateCompanySalaryGradeDTO req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    // ================================
    // UPDATE
    // ================================
    @PutMapping("/{id}")
    @ApiMessage("Cập nhật bậc lương")
    public ResponseEntity<ResCompanySalaryGradeDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody ReqUpdateCompanySalaryGradeDTO req) {
        return ResponseEntity.ok(service.update(id, req));
    }

    // ================================
    // DELETE (SOFT DELETE)
    // ================================
    @DeleteMapping("/{id}")
    @ApiMessage("Xoá bậc lương (soft delete)")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ================================
    // RESTORE
    // ================================
    @PutMapping("/{id}/restore")
    @ApiMessage("Khôi phục bậc lương đã vô hiệu")
    public ResponseEntity<ResCompanySalaryGradeDTO> restore(@PathVariable Long id) {
        return ResponseEntity.ok(service.restore(id));
    }

    // ================================
    // FETCH ALL theo companyJobTitleId (admin)
    // ================================
    @GetMapping
    @ApiMessage("Danh sách bậc lương theo companyJobTitleId")
    public ResponseEntity<List<ResCompanySalaryGradeDTO>> fetch(
            @RequestParam Long companyJobTitleId) {
        return ResponseEntity.ok(service.fetch(companyJobTitleId));
    }

    // ================================
    // FETCH CÁ NHÂN — chỉ thấy của mình
    // ================================
    @GetMapping("/my")
    @ApiMessage("Khung lương của tôi (cấp công ty)")
    public ResponseEntity<List<ResCompanySalaryGradeDTO>> fetchMy() {
        return ResponseEntity.ok(service.fetchMy());
    }

    // ================================
    // FETCH THEO CÔNG TY — HR / trưởng phòng
    // ================================
    @GetMapping("/my-company")
    @ApiMessage("Khung lương toàn công ty của tôi")
    public ResponseEntity<List<ResCompanySalaryGradeDTO>> fetchByMyCompany() {
        return ResponseEntity.ok(service.fetchByMyCompany());
    }
}