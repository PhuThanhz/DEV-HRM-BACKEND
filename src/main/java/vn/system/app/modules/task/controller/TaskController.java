package vn.system.app.modules.task.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import com.turkraft.springfilter.boot.Filter;

import jakarta.validation.Valid;

import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.annotation.ApiMessage;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.task.domain.Task;
import vn.system.app.modules.task.domain.enums.TaskPriority;
import vn.system.app.modules.task.domain.enums.TaskStatus;
import vn.system.app.modules.task.domain.request.ReqApproveTaskDTO;
import vn.system.app.modules.task.domain.request.ReqCreateTaskDTO;
import vn.system.app.modules.task.domain.request.ReqSubmitResultDTO;
import vn.system.app.modules.task.domain.request.ReqUpdateTaskDTO;
import vn.system.app.modules.task.domain.response.ResTaskDTO;
import vn.system.app.modules.task.domain.response.ResTaskDetailDTO;
import vn.system.app.modules.task.domain.response.ResTaskSummaryReportDTO;
import vn.system.app.modules.task.service.TaskService;
import vn.system.app.modules.task.service.TaskSummaryReportService;

@RestController
@RequestMapping("/api/v1")
public class TaskController {

    private final TaskService taskService;
    private final TaskSummaryReportService reportService;

    public TaskController(TaskService taskService, TaskSummaryReportService reportService) {
        this.taskService = taskService;
        this.reportService = reportService;
    }

    /*
     * =====================================================
     * 1. GET ALL TASKS (PAGINATION + SPECIFICATION + TAB)
     * =====================================================
     */
    @GetMapping("/tasks")
    @ApiMessage("Danh sách tác vụ")
    public ResponseEntity<ResultPaginationDTO> fetchAll(
            @Filter Specification<Task> spec,
            Pageable pageable,
            @RequestParam(required = false) String tab) {

        return ResponseEntity.ok(this.taskService.fetchAll(spec, pageable, tab));
    }

    @GetMapping("/tasks/calendar")
    @ApiMessage("Tác vụ theo khoảng lịch")
    public ResponseEntity<List<ResTaskDTO>> fetchCalendar(
            @Filter Specification<Task> spec,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String tab) {

        return ResponseEntity.ok(this.taskService.fetchCalendar(spec, from, to, tab));
    }

    /*
     * =====================================================
     * 2. CREATE TASK
     * =====================================================
     */
    @PostMapping("/tasks")
    @ApiMessage("Tạo mới tác vụ thành công")
    public ResponseEntity<ResTaskDTO> create(
            @Valid @RequestBody ReqCreateTaskDTO req) {

        ResTaskDTO createdTask = this.taskService.handleCreate(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdTask);
    }

    /*
     * =====================================================
     * 3. GET TASK DETAIL BY ID
     * =====================================================
     */
    @GetMapping("/tasks/{id}")
    @ApiMessage("Chi tiết tác vụ")
    public ResponseEntity<ResTaskDetailDTO> fetchOne(
            @PathVariable("id") Long id) {

        return ResponseEntity.ok(this.taskService.fetchOne(id));
    }

    /*
     * =====================================================
     * 4. UPDATE TASK BY ID
     * =====================================================
     */
    @PutMapping("/tasks/{id}")
    @ApiMessage("Cập nhật tác vụ thành công")
    public ResponseEntity<ResTaskDTO> update(
            @PathVariable("id") Long id,
            @Valid @RequestBody ReqUpdateTaskDTO req) {

        return ResponseEntity.ok(this.taskService.handleUpdate(id, req));
    }

    /*
     * =====================================================
     * 5. PATCH TASK STATUS (TODO -> IN_PROGRESS ONLY)
     * =====================================================
     */
    @PatchMapping("/tasks/{id}/status")
    @ApiMessage("Cập nhật trạng thái tác vụ thành công")
    public ResponseEntity<ResTaskDTO> updateStatus(
            @PathVariable("id") Long id,
            @RequestParam(required = false) TaskStatus status,
            @RequestBody(required = false) Map<String, Object> body) {

        TaskStatus targetStatus = status;

        if (targetStatus == null && body != null && body.containsKey("status")) {
            Object rawStatus = body.get("status");
            if (rawStatus != null) {
                try {
                    targetStatus = TaskStatus.valueOf(rawStatus.toString().toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw new IdInvalidException("Trạng thái tác vụ không hợp lệ: " + rawStatus);
                }
            }
        }

        if (targetStatus == null) {
            throw new IdInvalidException("Trạng thái tác vụ 'status' không được để trống");
        }

        return ResponseEntity.ok(this.taskService.updateStatus(id, targetStatus));
    }

    /*
     * =====================================================
     * 5b. PATCH DUE DATE (KANBAN DEADLINE DRAG - CHỈ ĐỔI HẠN CHÓT)
     * =====================================================
     */
    @PatchMapping("/tasks/{id}/due-date")
    @ApiMessage("Cập nhật hạn chót tác vụ thành công")
    public ResponseEntity<ResTaskDTO> updateDueDate(
            @PathVariable("id") Long id,
            @RequestBody(required = false) Map<String, Object> body) {

        Instant newDueDate = null;
        if (body != null && body.containsKey("dueDate")) {
            Object raw = body.get("dueDate");
            if (raw != null) {
                try {
                    newDueDate = Instant.parse(raw.toString());
                } catch (Exception e) {
                    throw new IdInvalidException("Hạn chót không hợp lệ: " + raw);
                }
            }
        }

        return ResponseEntity.ok(this.taskService.updateDueDate(id, newDueDate));
    }

    /*
     * =====================================================
     * 6. CANCEL TASK (SOFT CANCEL)
     * =====================================================
     */
    @PostMapping("/tasks/{id}/cancel")
    @ApiMessage("Hủy tác vụ thành công")
    public ResponseEntity<Void> cancel(
            @PathVariable("id") Long id) {

        this.taskService.cancel(id);
        return ResponseEntity.ok().build();
    }

    /*
     * =====================================================
     * 7. DELETE TASK (HARD DELETE - ONLY TODO STATUS)
     * =====================================================
     */
    @DeleteMapping("/tasks/{id}")
    @ApiMessage("Xóa tác vụ thành công")
    public ResponseEntity<Void> delete(
            @PathVariable("id") Long id) {

        this.taskService.delete(id);
        return ResponseEntity.ok().build();
    }

    /*
     * =====================================================
     * 8. SUBMIT RESULT (BƯỚC 3: IN_PROGRESS / REWORK -> PENDING_REVIEW)
     * =====================================================
     */
    @PostMapping("/tasks/{id}/submit-result")
    @ApiMessage("Nộp báo cáo kết quả thành công")
    public ResponseEntity<ResTaskDTO> submitResult(
            @PathVariable("id") Long id,
            @Valid @RequestBody ReqSubmitResultDTO req) {

        return ResponseEntity.ok(this.taskService.submitResult(id, req));
    }

    /*
     * =====================================================
     * 9. APPROVE / REWORK (BƯỚC 4: PENDING_REVIEW -> COMPLETED / REWORK)
     * =====================================================
     */
    @PostMapping("/tasks/{id}/approve")
    @ApiMessage("Xử lý thẩm định tác vụ thành công")
    public ResponseEntity<ResTaskDTO> approve(
            @PathVariable("id") Long id,
            @Valid @RequestBody ReqApproveTaskDTO req) {

        return ResponseEntity.ok(this.taskService.approve(id, req));
    }

    /*
     * =====================================================
     * 10. SUMMARY REPORT (BÁO CÁO TỔNG KẾT KPI)
     * =====================================================
     */
    @GetMapping("/tasks/summary-report")
    @ApiMessage("Báo cáo tổng kết công việc theo kỳ")
    public ResponseEntity<ResTaskSummaryReportDTO> getSummaryReport(
            @RequestParam(value = "from", required = false) String fromStr,
            @RequestParam(value = "to", required = false) String toStr,
            @RequestParam(value = "departmentId", required = false) Long departmentId,
            @RequestParam(value = "companyId", required = false) Long companyId,
            @RequestParam(value = "assigneeId", required = false) String assigneeId,
            @RequestParam(value = "priority", required = false) TaskPriority priority,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "isOnTime", required = false) Boolean isOnTime,
            @RequestParam(value = "createdBy", required = false) String createdBy,
            @RequestParam(value = "isJdTask", required = false) Boolean isJdTask) {

        java.time.Instant from = parseDateParam(fromStr, false);
        java.time.Instant to = parseDateParam(toStr, true);

        return ResponseEntity.ok(
                this.reportService.generateReport(from, to, departmentId, companyId, assigneeId, priority, title, isOnTime, createdBy, isJdTask));
    }

    /*
     * =====================================================
     * 11. EXPORT SUMMARY REPORT TO EXCEL (.xlsx)
     * =====================================================
     */
    @GetMapping("/tasks/summary-report/export")
    @ApiMessage("Xuất báo cáo tổng kết công việc ra Excel")
    public ResponseEntity<byte[]> exportSummaryReportToExcel(
            @RequestParam(value = "from", required = false) String fromStr,
            @RequestParam(value = "to", required = false) String toStr,
            @RequestParam(value = "departmentId", required = false) Long departmentId,
            @RequestParam(value = "companyId", required = false) Long companyId,
            @RequestParam(value = "assigneeId", required = false) String assigneeId,
            @RequestParam(value = "priority", required = false) TaskPriority priority,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "isOnTime", required = false) Boolean isOnTime,
            @RequestParam(value = "createdBy", required = false) String createdBy,
            @RequestParam(value = "isJdTask", required = false) Boolean isJdTask) {

        java.time.Instant from = parseDateParam(fromStr, false);
        java.time.Instant to = parseDateParam(toStr, true);

        byte[] excelBytes = this.reportService.exportToExcel(from, to, departmentId, companyId, assigneeId, priority, title, isOnTime, createdBy, isJdTask);

        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=Bao_Cao_Tong_Ket_Cong_Viec.xlsx")
                .contentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excelBytes);
    }

    private java.time.Instant parseDateParam(String input, boolean endOfDay) {
        if (input == null || input.trim().isEmpty()) return null;
        try {
            if (input.contains("T")) {
                return java.time.Instant.parse(input);
            }
            java.time.LocalDate date = java.time.LocalDate.parse(input.trim());
            if (endOfDay) {
                return date.atTime(23, 59, 59).atZone(java.time.ZoneId.systemDefault()).toInstant();
            } else {
                return date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
            }
        } catch (Exception e) {
            throw new IdInvalidException("Tham số ngày không hợp lệ: " + input);
        }
    }
}
