package vn.system.app.modules.userposition.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import vn.system.app.common.util.annotation.ApiMessage;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.userposition.domain.request.ReqCreateUserPositionDTO;
import vn.system.app.modules.userposition.domain.response.ResUserPositionDTO;
import vn.system.app.modules.userposition.service.UserPositionService;

@RestController
@RequestMapping("/api/v1/users")
public class UserPositionController {

    private final UserPositionService service;

    public UserPositionController(UserPositionService service) {
        this.service = service;
    }

    // =====================================================
    // CREATE — Gán chức danh cho user
    // =====================================================
    @PostMapping("/{userId}/positions")
    @ApiMessage("Gán chức danh cho user")
    public ResponseEntity<ResUserPositionDTO> create(
            @PathVariable String userId,
            @Valid @RequestBody ReqCreateUserPositionDTO req) {

        ResUserPositionDTO res = service.handleCreate(userId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(res);
    }

    // =====================================================
    // DELETE — Xóa chức danh khỏi user (soft delete)
    // =====================================================
    @DeleteMapping("/positions/{id}")
    @ApiMessage("Xóa chức danh khỏi user")
    public ResponseEntity<Void> delete(@PathVariable Long id) {

        service.handleDelete(id);
        return ResponseEntity.noContent().build();
    }

    // =====================================================
    // GET ALL — Lấy danh sách chức danh của user
    // =====================================================
    @GetMapping("/{userId}/positions")
    @ApiMessage("Danh sách chức danh của user")
    public ResponseEntity<List<ResUserPositionDTO>> getByUser(
            @PathVariable String userId) {

        List<ResUserPositionDTO> res = service.fetchByUser(userId);
        return ResponseEntity.ok(res);
    }

    // =====================================================
    // GET USERS BY COMPANY — dùng cho dropdown CONFIDENTIAL
    // =====================================================
    @GetMapping("/by-company/{companyId}")
    @ApiMessage("Danh sách user theo công ty")
    public ResponseEntity<List<ResUserPositionDTO>> getByCompany(
            @PathVariable Long companyId) {
        return ResponseEntity.ok(service.fetchByCompany(companyId));
    }
}