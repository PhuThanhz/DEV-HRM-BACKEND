package vn.system.app.modules.evaluation.controller;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.turkraft.springfilter.boot.Filter;

import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.annotation.ApiMessage;
import vn.system.app.modules.evaluation.domain.*;
import vn.system.app.modules.evaluation.domain.response.*;
import vn.system.app.modules.evaluation.domain.enums.TemplateType;
import java.time.Instant;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.evaluation.service.EvaluationMapper;
import vn.system.app.modules.evaluation.service.EvaluationPeriodService;

/**
 * Admin API — Quản lý Kỳ đánh giá HQCV.
 */
@RestController
@RequestMapping("/api/v1/evaluation")
public class EvaluationPeriodController {

    private final EvaluationPeriodService periodService;
    private final EvaluationMapper mapper;

    public EvaluationPeriodController(EvaluationPeriodService periodService, EvaluationMapper mapper) {
        this.periodService = periodService;
        this.mapper = mapper;
    }

    // ═══════════════ PERIOD CRUD ═══════════════

    @PostMapping("/periods")
    @ApiMessage("Tạo kỳ đánh giá")
    public ResponseEntity<ResPeriodDTO> createPeriod(@RequestBody EvaluationPeriod period) {
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toResPeriodDTO(periodService.createPeriod(period)));
    }

    @PutMapping("/periods/{id}")
    @ApiMessage("Cập nhật kỳ đánh giá")
    public ResponseEntity<ResPeriodDTO> updatePeriod(
            @PathVariable Long id, @RequestBody EvaluationPeriod period) {
        return ResponseEntity.ok(mapper.toResPeriodDTO(periodService.updatePeriod(id, period)));
    }

    @GetMapping("/periods/{id}")
    @ApiMessage("Chi tiết kỳ đánh giá")
    public ResponseEntity<ResPeriodDTO> fetchPeriod(@PathVariable Long id) {
        return ResponseEntity.ok(mapper.toResPeriodDTO(periodService.fetchPeriodById(id)));
    }

    @GetMapping("/periods/{id}/progress")
    @ApiMessage("Lấy thống kê tiến độ kỳ đánh giá")
    public ResponseEntity<ResPeriodProgressDTO> getPeriodProgress(@PathVariable Long id) {
        return ResponseEntity.ok(periodService.getPeriodProgress(id));
    }

    @GetMapping("/periods")
    @ApiMessage("Danh sách kỳ đánh giá")
    public ResponseEntity<ResultPaginationDTO> fetchAllPeriods(
            @Filter Specification<EvaluationPeriod> spec, Pageable pageable) {
        spec = spec.and(vn.system.app.common.util.ScopeSpec.byCompanyScope("company.id"));
        ResultPaginationDTO page = periodService.fetchAllPeriods(spec, pageable);
        @SuppressWarnings("unchecked")
        List<EvaluationPeriod> periods = (List<EvaluationPeriod>) page.getResult();
        return ResponseEntity.ok(mapper.mapPagination(
                page,
                periods.stream().map(mapper::toResPeriodDTO).toList()));
    }

    // ═══════════════ PERIOD TEMPLATES ═══════════════

    @PostMapping("/periods/{periodId}/templates")
    @ApiMessage("Gắn template vào kỳ đánh giá")
    public ResponseEntity<ResPeriodTemplateDTO> addTemplateToPeriod(
            @PathVariable Long periodId,
            @RequestBody Map<String, Object> body) {
        Object rawTemplateId = body.get("templateId");
        if (rawTemplateId == null) {
            throw new IdInvalidException("templateId không được để trống");
        }
        Long templateId;
        try {
            templateId = Long.valueOf(rawTemplateId.toString());
        } catch (NumberFormatException e) {
            throw new IdInvalidException("templateId không hợp lệ");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mapper.toResPeriodTemplateDTO(periodService.addTemplateToPeriod(periodId, templateId)));
    }

    @GetMapping("/periods/{periodId}/templates")
    @ApiMessage("Danh sách template trong kỳ")
    public ResponseEntity<List<ResPeriodTemplateDTO>> fetchTemplates(@PathVariable Long periodId) {
        return ResponseEntity.ok(periodService.fetchTemplatesByPeriod(periodId).stream().map(mapper::toResPeriodTemplateDTO).toList());
    }

    @DeleteMapping("/periods/{periodId}/templates/{templateId}")
    @ApiMessage("Gỡ template khỏi kỳ đánh giá")
    public ResponseEntity<Void> removeTemplate(
            @PathVariable Long periodId,
            @PathVariable Long templateId) {
        periodService.removeTemplateFromPeriod(periodId, templateId);
        return ResponseEntity.noContent().build();
    }

    // ═══════════════ PERIOD EMPLOYEES ═══════════════

    @PostMapping("/periods/{periodId}/employees")
    @ApiMessage("Thêm nhân viên vào kỳ đánh giá")
    public ResponseEntity<ResPeriodEmployeeDTO> addEmployee(
            @PathVariable Long periodId,
            @Valid @RequestBody vn.system.app.modules.evaluation.domain.request.AddPeriodEmployeeRequest req) {
        
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mapper.toResPeriodEmployeeDTO(periodService.addEmployeeToPeriod(periodId, req.getEmployeeId(), req.getDirectManagerId(), req.getTemplateId())));
    }

    @PatchMapping("/period-employees/{id}/cancel")
    @ApiMessage("Hủy bản đánh giá nhân viên nghỉ việc")
    public ResponseEntity<ResPeriodEmployeeDTO> cancelEmployee(@PathVariable Long id) {
        return ResponseEntity.ok(mapper.toResPeriodEmployeeDTO(periodService.cancelEmployee(id)));
    }

    @GetMapping("/periods/{periodId}/employees")
    @ApiMessage("Danh sách nhân viên trong kỳ")
    public ResponseEntity<List<ResPeriodEmployeeDTO>> fetchEmployees(@PathVariable Long periodId) {
        return ResponseEntity.ok(periodService.fetchEmployeesByPeriod(periodId).stream().map(mapper::toResPeriodEmployeeDTO).toList());
    }

    // ═══════════════ KÍCH HOẠT / ĐÓNG KỲ ═══════════════

    @PatchMapping("/periods/{id}/activate")
    @ApiMessage("Kích hoạt kỳ đánh giá")
    public ResponseEntity<ResPeriodDTO> activatePeriod(@PathVariable Long id) {
        return ResponseEntity.ok(mapper.toResPeriodDTO(periodService.activatePeriod(id)));
    }

    @PatchMapping("/periods/{id}/close")
    @ApiMessage("Đóng kỳ đánh giá")
    public ResponseEntity<ResPeriodDTO> closePeriod(@PathVariable Long id) {
        return ResponseEntity.ok(mapper.toResPeriodDTO(periodService.closePeriod(id)));
    }

    @GetMapping("/periods/{id}/unfinished-records")
    @ApiMessage("Danh sách bản đánh giá chưa hoàn thành trong kỳ")
    public ResponseEntity<List<ResEvaluationRecordDTO>> getUnfinishedRecords(@PathVariable Long id) {
        return ResponseEntity.ok(periodService.getUnfinishedRecords(id).stream()
                .map(mapper::toResEvaluationRecordDTO).toList());
    }

    @PatchMapping("/periods/{id}/adjust-start-date")
    @ApiMessage("Điều chỉnh ngày mở cổng tự đánh giá")
    public ResponseEntity<ResPeriodDTO> adjustStartDate(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String startDateStr = body.get("employeeStartDate");
        if (startDateStr == null || startDateStr.isBlank()) {
            throw new IdInvalidException("Ngày mở cổng không được để trống");
        }
        Instant newStartDate;
        try {
            newStartDate = Instant.parse(startDateStr);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IdInvalidException("Ngày mở cổng không đúng định dạng");
        }
        return ResponseEntity.ok(mapper.toResPeriodDTO(periodService.adjustStartDate(id, newStartDate)));
    }
}
