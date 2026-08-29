package vn.system.app.modules.sharetoken.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.common.util.annotation.ApiMessage;

import vn.system.app.modules.companyprocedure.service.CompanyProcedureService;
import vn.system.app.modules.confidentialprocedure.service.ConfidentialProcedureService;
import vn.system.app.modules.departmentprocedure.service.DepartmentProcedureService;
import vn.system.app.modules.sharetoken.domain.ProcedureShareToken;
import vn.system.app.modules.sharetoken.domain.ShareTokenAccessLog;
import vn.system.app.modules.sharetoken.domain.request.CreateShareTokenRequest;
import vn.system.app.modules.sharetoken.domain.request.SendShareEmailRequest;
import vn.system.app.modules.sharetoken.domain.response.ResShareTokenDTO;
import vn.system.app.modules.sharetoken.service.ProcedureShareTokenService;

@RestController
@RequestMapping("/api/v1/procedures")
@RequiredArgsConstructor
public class ShareTokenController {

    private final ProcedureShareTokenService shareTokenService;
    private final CompanyProcedureService companyProcedureService;
    private final DepartmentProcedureService departmentProcedureService;
    private final ConfidentialProcedureService confidentialProcedureService;

    // =====================================================
    // TẠO SHARE TOKEN CHO 1 QUY TRÌNH
    // =====================================================
    @PostMapping("/{id}/share-tokens")
    @ApiMessage("Tạo link chia sẻ cho khách bên ngoài")
    public ResponseEntity<ResShareTokenDTO> createShareToken(
            @PathVariable Long id,
            @Valid @RequestBody CreateShareTokenRequest req) {

        assertProcedureAccess(id, req.getProcedureType());
        ResShareTokenDTO res = shareTokenService.handleCreate(id, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(res);
    }

    // =====================================================
    // DANH SÁCH SHARE TOKEN CỦA 1 QUY TRÌNH
    // =====================================================
    @GetMapping("/{id}/share-tokens")
    @ApiMessage("Danh sách link chia sẻ của quy trình")
    public ResponseEntity<List<ResShareTokenDTO>> getShareTokens(
            @PathVariable Long id,
            @RequestParam String procedureType) {

        assertProcedureAccess(id, procedureType);
        List<ResShareTokenDTO> res = shareTokenService.fetchByProcedure(id, procedureType);
        return ResponseEntity.ok(res);
    }

    // =====================================================
    // THU HỒI SHARE TOKEN
    // =====================================================
    @PatchMapping("/share-tokens/{tokenId}/revoke")
    @ApiMessage("Thu hồi link chia sẻ")
    public ResponseEntity<Void> revokeShareToken(@PathVariable Long tokenId) {
        assertTokenAccess(tokenId);
        shareTokenService.handleRevoke(tokenId);
        return ResponseEntity.ok().build();
    }

    // =====================================================
    // LỊCH SỬ TRUY CẬP CỦA 1 SHARE TOKEN
    // =====================================================
    @GetMapping("/share-tokens/{tokenId}/access-logs")
    @ApiMessage("Lịch sử truy cập của link chia sẻ")
    public ResponseEntity<List<ShareTokenAccessLog>> getAccessLogs(
            @PathVariable Long tokenId) {

        assertTokenAccess(tokenId);
        List<ShareTokenAccessLog> logs = shareTokenService.fetchAccessLogs(tokenId);
        return ResponseEntity.ok(logs);
    }

    // =====================================================
    // GỬI EMAIL CHIA SẺ TOKEN (PIN + QR)
    // =====================================================
    @PostMapping("/share-tokens/{tokenId}/send-email")
    @ApiMessage("Gửi email chia sẻ link + QR cho người dùng ngoài")
    public ResponseEntity<Void> sendShareEmail(
            @PathVariable Long tokenId,
            @Valid @RequestBody SendShareEmailRequest req) {

        assertTokenAccess(tokenId);
        shareTokenService.handleSendEmail(tokenId, req.getEmail());
        return ResponseEntity.ok().build();
    }

    /**
     * Chặn share-token của văn bản (dùng API riêng) và kiểm tra người gọi có quyền
     * đọc/quản lý đúng quy trình (company/department/confidential) trước khi cho
     * tạo/xem token — trước đây endpoint này chỉ chặn loại DOCUMENT, không hề kiểm
     * tra quyền sở hữu procedureId nên bất kỳ user đăng nhập nào cũng gọi được.
     */
    private void assertProcedureAccess(Long procedureId, String procedureType) {
        if ("DOCUMENT".equals(procedureType)) {
            throw new IdInvalidException("Vui lòng dùng API chia sẻ văn bản");
        }
        switch (procedureType) {
            case "COMPANY" ->
                companyProcedureService.validateReadAccess(companyProcedureService.fetchById(procedureId));
            case "DEPARTMENT" ->
                departmentProcedureService.validateReadAccess(departmentProcedureService.fetchById(procedureId));
            case "CONFIDENTIAL" ->
                confidentialProcedureService.validateReadAccess(confidentialProcedureService.fetchById(procedureId));
            default -> throw new IdInvalidException("Loại quy trình không hợp lệ: " + procedureType);
        }
    }

    private void assertTokenAccess(Long tokenId) {
        ProcedureShareToken token = shareTokenService.fetchById(tokenId);
        assertProcedureAccess(token.getProcedureId(), token.getProcedureType());
    }
}
