package vn.system.app.modules.sharetoken.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.sharetoken.domain.ProcedureShareToken;
import vn.system.app.modules.sharetoken.domain.response.ResPublicProcedureDTO;

import vn.system.app.modules.companyprocedure.domain.CompanyProcedure;
import vn.system.app.modules.companyprocedure.repository.CompanyProcedureRepository;

import vn.system.app.modules.departmentprocedure.domain.DepartmentProcedure;
import vn.system.app.modules.departmentprocedure.repository.DepartmentProcedureRepository;

import vn.system.app.modules.confidentialprocedure.domain.ConfidentialProcedure;
import vn.system.app.modules.confidentialprocedure.repository.ConfidentialProcedureRepository;

import vn.system.app.modules.document.domain.Document;
import vn.system.app.modules.document.repository.DocumentRepository;

@Service
@RequiredArgsConstructor
public class PublicProcedureViewService {

    private final ProcedureShareTokenService shareTokenService;
    private final CompanyProcedureRepository companyProcedureRepository;
    private final DepartmentProcedureRepository departmentProcedureRepository;
    private final ConfidentialProcedureRepository confidentialProcedureRepository;
    private final DocumentRepository documentRepository;
    private final ObjectMapper objectMapper;

    private static final int MAX_PIN_ATTEMPTS = 5;
    private static final Duration PIN_ATTEMPT_WINDOW = Duration.ofMinutes(15);
    private final ConcurrentMap<String, PinAttempt> pinAttempts = new ConcurrentHashMap<>();

    // =====================================================
    // GET KHÔNG CẦN PIN
    // =====================================================
    public Object handleView(String token, String ip, String userAgent) {
        ProcedureShareToken shareToken = shareTokenService.validateToken(token);

        if (shareToken.getPin() != null) {
            return RequirePinResponse.of(true);
        }

        checkActiveStatus(shareToken);
        shareTokenService.recordAccess(shareToken, ip, userAgent);
        return buildPublicDTO(shareToken);
    }

    // =====================================================
    // POST VERIFY PIN
    // =====================================================
    public ResPublicProcedureDTO handleVerifyPin(String token, String pin,
            String ip, String userAgent) {
        ProcedureShareToken shareToken = shareTokenService.validateToken(token);

        String attemptKey = token + ":" + ip;
        ensurePinAttemptAllowed(attemptKey);

        if (pin == null || shareToken.getPin() == null || !constantTimeEquals(pin, shareToken.getPin())) {
            recordFailedPinAttempt(attemptKey);
            throw new IdInvalidException("Mã PIN không đúng");
        }

        pinAttempts.remove(attemptKey);
        checkActiveStatus(shareToken);
        shareTokenService.recordAccess(shareToken, ip, userAgent);
        return buildPublicDTO(shareToken);
    }

    // So sánh constant-time để tránh timing attack khi dò PIN
    private boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void checkActiveStatus(ProcedureShareToken shareToken) {
        String type = shareToken.getProcedureType();
        Long id = shareToken.getProcedureId();
        switch (type) {
            case "COMPANY" -> {
                CompanyProcedure p = companyProcedureRepository.findById(id)
                        .orElseThrow(() -> new IdInvalidException("Không tìm thấy quy trình"));
                if (!p.isActive()) {
                    throw new IdInvalidException("Quy trình đã bị vô hiệu hóa");
                }
            }
            case "DEPARTMENT" -> {
                DepartmentProcedure p = departmentProcedureRepository.findById(id)
                        .orElseThrow(() -> new IdInvalidException("Không tìm thấy quy trình"));
                if (!p.isActive()) {
                    throw new IdInvalidException("Quy trình đã bị vô hiệu hóa");
                }
            }
            case "CONFIDENTIAL" -> {
                ConfidentialProcedure p = confidentialProcedureRepository.findById(id)
                        .orElseThrow(() -> new IdInvalidException("Không tìm thấy quy trình"));
                if (!p.isActive()) {
                    throw new IdInvalidException("Quy trình đã bị vô hiệu hóa");
                }
            }
            case "DOCUMENT" -> {
                Document doc = documentRepository.findById(id)
                        .orElseThrow(() -> new IdInvalidException("Không tìm thấy văn bản"));
                if (!doc.isActive()) {
                    throw new IdInvalidException("Văn bản đã bị vô hiệu hóa");
                }
            }
            default -> throw new IdInvalidException("Loại không hợp lệ: " + type);
        }
    }

    // =====================================================
    // BUILD DTO
    // =====================================================
    public ResPublicProcedureDTO buildPublicDTO(ProcedureShareToken shareToken) {
        String type = shareToken.getProcedureType();
        Long id = shareToken.getProcedureId();

        return switch (type) {
            case "COMPANY" -> {
                CompanyProcedure p = companyProcedureRepository.findById(id)
                        .orElseThrow(() -> new IdInvalidException("Không tìm thấy quy trình"));

                if (!p.isActive())
                    throw new IdInvalidException("Quy trình đã bị vô hiệu hóa");

                yield ResPublicProcedureDTO.builder()
                        .procedureCode(p.getProcedureCode())
                        .procedureName(p.getProcedureName())
                        .status(p.getStatus())
                        .version(p.getVersion())
                        .issuedDate(p.getIssuedDate())
                        .departmentName(p.getDepartment() != null ? p.getDepartment().getName() : null)
                        .sectionName(p.getSection() != null ? p.getSection().getName() : null)
                        .note(p.getNote())
                        .fileUrls(parseFileUrls(p.getFileUrls()))
                        .allowDownload(true)
                        .accessCount(shareToken.getAccessCount())
                        .maxAccessCount(shareToken.getMaxAccessCount())
                        .expiresAt(shareToken.getExpiresAt())
                        .build();
            }

            case "DEPARTMENT" -> {
                DepartmentProcedure p = departmentProcedureRepository.findById(id)
                        .orElseThrow(() -> new IdInvalidException("Không tìm thấy quy trình"));

                if (!p.isActive())
                    throw new IdInvalidException("Quy trình đã bị vô hiệu hóa");

                String deptName = (p.getDepartments() != null && !p.getDepartments().isEmpty())
                        ? p.getDepartments().get(0).getName()
                        : null;

                yield ResPublicProcedureDTO.builder()
                        .procedureCode(p.getProcedureCode())
                        .procedureName(p.getProcedureName())
                        .status(p.getStatus())
                        .version(p.getVersion())
                        .issuedDate(p.getIssuedDate())
                        .departmentName(deptName)
                        .sectionName(p.getSection() != null ? p.getSection().getName() : null)
                        .note(p.getNote())
                        .fileUrls(parseFileUrls(p.getFileUrls()))
                        .allowDownload(true)
                        .accessCount(shareToken.getAccessCount())
                        .maxAccessCount(shareToken.getMaxAccessCount())
                        .expiresAt(shareToken.getExpiresAt())
                        .build();
            }

            case "CONFIDENTIAL" -> {
                ConfidentialProcedure p = confidentialProcedureRepository.findById(id)
                        .orElseThrow(() -> new IdInvalidException("Không tìm thấy quy trình"));

                if (!p.isActive())
                    throw new IdInvalidException("Quy trình đã bị vô hiệu hóa");

                yield ResPublicProcedureDTO.builder()
                        .procedureCode(p.getProcedureCode())
                        .procedureName(p.getProcedureName())
                        .status(p.getStatus())
                        .version(p.getVersion())
                        .issuedDate(p.getIssuedDate())
                        .departmentName(p.getDepartment() != null ? p.getDepartment().getName() : null)
                        .sectionName(p.getSection() != null ? p.getSection().getName() : null)
                        .note(p.getNote())
                        .fileUrls(parseFileUrls(p.getFileUrls()))
                        .allowDownload(true)
                        .accessCount(shareToken.getAccessCount())
                        .maxAccessCount(shareToken.getMaxAccessCount())
                        .expiresAt(shareToken.getExpiresAt())
                        .build();
            }

            case "DOCUMENT" -> {
                Document doc = documentRepository.findById(id)
                        .orElseThrow(() -> new IdInvalidException("Không tìm thấy văn bản"));

                if (!doc.isActive())
                    throw new IdInvalidException("Văn bản đã bị vô hiệu hóa");

                yield ResPublicProcedureDTO.builder()
                        .procedureCode(doc.getDocumentCode())
                        .procedureName(doc.getDocumentName())
                        .status(doc.getStatus())
                        .version(doc.getVersion())
                        .issuedDate(doc.getIssuedDate())
                        .departmentName(doc.getDepartment() != null ? doc.getDepartment().getName() : null)
                        .sectionName(doc.getSection() != null ? doc.getSection().getName() : null)
                        .note(doc.getNote())
                        .fileUrls(parseFileUrls(doc.getFileUrls()))
                        .allowDownload(true)
                        .accessCount(shareToken.getAccessCount())
                        .maxAccessCount(shareToken.getMaxAccessCount())
                        .expiresAt(shareToken.getExpiresAt())
                        .build();
            }

            default -> throw new IdInvalidException("Loại không hợp lệ: " + type);
        };
    }

    // =====================================================
    // HELPER: parse fileUrls từ String JSON → List<String>
    // =====================================================
    private List<String> parseFileUrls(String fileUrlsJson) {
        if (fileUrlsJson == null || fileUrlsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(fileUrlsJson, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private void ensurePinAttemptAllowed(String key) {
        PinAttempt attempt = pinAttempts.get(key);
        if (attempt == null) {
            return;
        }

        if (attempt.firstFailedAt.plus(PIN_ATTEMPT_WINDOW).isBefore(Instant.now())) {
            pinAttempts.remove(key);
            return;
        }

        if (attempt.count >= MAX_PIN_ATTEMPTS) {
            throw new IdInvalidException("Bạn đã nhập sai PIN quá nhiều lần, vui lòng thử lại sau");
        }
    }

    private void recordFailedPinAttempt(String key) {
        Instant now = Instant.now();
        pinAttempts.compute(key, (k, current) -> {
            if (current == null || current.firstFailedAt.plus(PIN_ATTEMPT_WINDOW).isBefore(now)) {
                return new PinAttempt(1, now);
            }
            return new PinAttempt(current.count + 1, current.firstFailedAt);
        });
    }

    // =====================================================
    // INNER CLASS: response khi yêu cầu PIN
    // =====================================================
    public record RequirePinResponse(boolean requirePin) {
        public static RequirePinResponse of(boolean requirePin) {
            return new RequirePinResponse(requirePin);
        }
    }

    private record PinAttempt(int count, Instant firstFailedAt) {
    }
}
