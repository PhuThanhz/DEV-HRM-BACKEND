package vn.system.app.modules.kpigroup.controller;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.turkraft.springfilter.boot.Filter;

import jakarta.validation.Valid;

import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.annotation.ApiMessage;
import vn.system.app.modules.kpigroup.domain.KpiGroup;
import vn.system.app.modules.kpigroup.domain.request.KpiGroupRequest;
import vn.system.app.modules.kpigroup.domain.response.ResKpiGroupDTO;
import vn.system.app.modules.kpigroup.service.KpiGroupService;

@RestController
@RequestMapping("/api/v1/kpi-groups")
public class KpiGroupController {

    private final KpiGroupService service;

    public KpiGroupController(KpiGroupService service) {
        this.service = service;
    }

    @PostMapping
    @ApiMessage("Tạo nhóm KPI thành công")
    public ResponseEntity<ResKpiGroupDTO> create(@Valid @RequestBody KpiGroupRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.handleCreate(req));
    }

    @PutMapping("/{id}")
    @ApiMessage("Cập nhật nhóm KPI thành công")
    public ResponseEntity<ResKpiGroupDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody KpiGroupRequest req) {
        return ResponseEntity.ok(service.handleUpdate(id, req));
    }

    @PutMapping("/{id}/active")
    @ApiMessage("Thay đổi trạng thái kích hoạt nhóm KPI")
    public ResponseEntity<Void> toggleActive(@PathVariable Long id) {
        service.handleToggleActive(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}")
    @ApiMessage("Chi tiết nhóm KPI")
    public ResponseEntity<ResKpiGroupDTO> getOne(@PathVariable Long id) {
        return ResponseEntity.ok(service.convertToDTO(service.fetchById(id)));
    }

    @GetMapping
    @ApiMessage("Danh sách nhóm KPI")
    public ResponseEntity<ResultPaginationDTO> getAll(
            @Filter Specification<KpiGroup> spec,
            Pageable pageable) {
        return ResponseEntity.ok(service.fetchAll(spec, pageable));
    }

    @GetMapping("/active")
    @ApiMessage("Danh sách nhóm KPI đang hoạt động theo công ty")
    public ResponseEntity<List<ResKpiGroupDTO>> getActiveByCompany(
            @RequestParam(value = "companyId") Long companyId) {
        return ResponseEntity.ok(service.fetchActiveByCompany(companyId));
    }

    @DeleteMapping("/{id}")
    @ApiMessage("Xóa nhóm KPI thành công")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.handleDelete(id);
        return ResponseEntity.ok().build();
    }
}
