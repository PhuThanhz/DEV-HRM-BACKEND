package vn.system.app.modules.salarygradeincome.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import vn.system.app.common.util.annotation.ApiMessage;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.salarygradeincome.domain.PayType;
import vn.system.app.modules.salarygradeincome.domain.SalaryGradeIncome;
import vn.system.app.modules.salarygradeincome.domain.request.SalaryGradeIncomeRequest;
import vn.system.app.modules.salarygradeincome.domain.response.SalaryGradeIncomeResponse;
import vn.system.app.modules.salarygradeincome.service.SalaryGradeIncomeService;

@RestController
@RequestMapping("/api/v1")
public class SalaryGradeIncomeController {

    private final SalaryGradeIncomeService service;

    public SalaryGradeIncomeController(SalaryGradeIncomeService service) {
        this.service = service;
    }

    /*
     * ===============================
     * CREATE
     * ===============================
     */
    @PostMapping("/salary-grade-incomes")
    @ApiMessage("Create salary grade income")
    public ResponseEntity<SalaryGradeIncomeResponse> create(
            @RequestBody SalaryGradeIncomeRequest request) {

        SalaryGradeIncome entity = service.handleCreate(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.convertToResponse(entity));
    }

    /*
     * ===============================
     * GET BY ID
     * ===============================
     */
    @GetMapping("/salary-grade-incomes/{id}")
    @ApiMessage("Get salary grade income by id")
    public ResponseEntity<SalaryGradeIncomeResponse> getById(
            @PathVariable("id") Long id) {

        SalaryGradeIncome entity = service.fetchById(id);

        if (entity == null) {
            throw new IdInvalidException(
                    "Khung thu nhập với id = " + id + " không tồn tại");
        }

        return ResponseEntity.ok(service.convertToResponse(entity));
    }

    /*
     * ===============================
     * GET BY SALARY GRADE + PAY TYPE
     * ===============================
     */
    @GetMapping("/salary-grade-incomes/by-salary-grade")
    @ApiMessage("Get salary grade income by salary grade and pay type")
    public ResponseEntity<SalaryGradeIncomeResponse> getBySalaryGrade(
            @RequestParam("salaryGradeId") Long salaryGradeId,
            @RequestParam("payType") PayType payType) {

        SalaryGradeIncome entity = service.fetchBySalaryGradeAndPayType(salaryGradeId, payType);

        if (entity == null) {
            throw new IdInvalidException(
                    "Chưa có khung thu nhập cho bậc lương này");
        }

        return ResponseEntity.ok(service.convertToResponse(entity));
    }

    /*
     * ===============================
     * UPDATE
     * ===============================
     */
    @PutMapping("/salary-grade-incomes/{id}")
    @ApiMessage("Update salary grade income")
    public ResponseEntity<SalaryGradeIncomeResponse> update(
            @PathVariable("id") Long id,
            @RequestBody SalaryGradeIncomeRequest request) {

        SalaryGradeIncome entity = service.handleUpdate(id, request);

        if (entity == null) {
            throw new IdInvalidException(
                    "Khung thu nhập với id = " + id + " không tồn tại");
        }

        return ResponseEntity.ok(service.convertToResponse(entity));
    }

    /*
     * ===============================
     * DELETE
     * ===============================
     */
    @DeleteMapping("/salary-grade-incomes/{id}")
    @ApiMessage("Delete salary grade income")
    public ResponseEntity<Void> delete(
            @PathVariable("id") Long id) {

        SalaryGradeIncome entity = service.fetchById(id);

        if (entity == null) {
            throw new IdInvalidException(
                    "Khung thu nhập với id = " + id + " không tồn tại");
        }

        service.handleDelete(id);
        return ResponseEntity.noContent().build();
    }
}
