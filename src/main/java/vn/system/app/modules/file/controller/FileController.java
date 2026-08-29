package vn.system.app.modules.file.controller;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import vn.system.app.common.util.annotation.ApiMessage;
import vn.system.app.common.util.error.StorageException;
import vn.system.app.modules.document.service.DocumentService;
import vn.system.app.modules.file.domain.response.ResUploadFileDTO;
import vn.system.app.modules.file.service.FileService;
import vn.system.app.modules.task.service.TaskAttachmentService;

@RestController
@RequestMapping("/api/v1")
public class FileController {

        private final FileService fileService;
        private final TaskAttachmentService taskAttachmentService;
        private final DocumentService documentService;

        public FileController(
                        FileService fileService,
                        TaskAttachmentService taskAttachmentService,
                        DocumentService documentService) {
                this.fileService = fileService;
                this.taskAttachmentService = taskAttachmentService;
                this.documentService = documentService;
        }

        /**
         * Task attachment và Document đều có cơ chế phân quyền riêng (canViewTask, DocumentAccess/exclude).
         * File không khớp nguồn nào (avatar, procedures...) giữ hành vi cũ: cho phép user đã đăng nhập.
         */
        private boolean canAccessFile(String fileName, String folder) {
                Boolean taskDecision = taskAttachmentService.checkAccessIfTaskAttachment(fileName, folder);
                if (taskDecision != null) {
                        return taskDecision;
                }
                Boolean documentDecision = documentService.checkAccessIfDocumentFile(fileName);
                if (documentDecision != null) {
                        return documentDecision;
                }
                return true;
        }

        // =========================
        // UPLOAD FILE
        // =========================
        @PostMapping("/files")
        @ApiMessage("Upload single file")
        public ResponseEntity<ResUploadFileDTO> upload(
                        @RequestParam("file") MultipartFile file,
                        @RequestParam("folder") String folder) throws IOException, StorageException {

                if (file == null || file.isEmpty()) {
                        throw new StorageException("File is empty. Please upload a file.");
                }

                String originalName = file.getOriginalFilename();
                if (originalName == null) {
                        throw new StorageException("Invalid file name.");
                }

                // ===== CHO PHÉP CẢ FILE + IMAGE =====
                List<String> allowedExtensions = Arrays.asList(
                                "pdf", "doc", "docx", "xls", "xlsx",
                                "jpg", "jpeg", "png", "webp");

                // ===== LẤY EXTENSION CHUẨN =====
                String ext = "";
                int lastDot = originalName.lastIndexOf(".");
                if (lastDot >= 0) {
                        ext = originalName.substring(lastDot + 1).toLowerCase().trim();
                }

                // ===== VALIDATE =====
                if (!allowedExtensions.contains(ext)) {
                        throw new StorageException(
                                        "Invalid file extension. Only allows: " + allowedExtensions);
                }

                // Tạo folder + lưu file
                fileService.createDirectory(folder);
                String storedFileName = fileService.store(file, folder);

                ResUploadFileDTO res = new ResUploadFileDTO(
                                storedFileName,
                                Instant.now());

                return ResponseEntity.ok(res);
        }

        // =========================
        // VIEW FILE PUBLIC (no auth — only whitelisted folders: avatar, procedures)
        // Used by Office Online Viewer and public procedure pages
        // =========================
        @GetMapping("/files/public")
        @ApiMessage("View a public file")
        public ResponseEntity<?> viewPublic(
                        @RequestParam("fileName") String fileName,
                        @RequestParam("folder") String folder,
                        @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader)
                        throws StorageException, IOException {

                List<String> allowedFolders = Arrays.asList("avatar", "procedures");
                boolean allowed = allowedFolders.stream().anyMatch(folder::equals);
                if (!allowed) {
                        return ResponseEntity.status(403).build();
                }

                long fileLength = fileService.getFileLength(fileName, folder);
                if (fileLength == 0) {
                        throw new StorageException("File with name = " + fileName + " not found.");
                }

                Path filePath = fileService.resolvePath(fileName, folder);
                FileSystemResource resource = new FileSystemResource(filePath);
                String contentType = detectContentType(fileName);
                MediaType mediaType = MediaType.parseMediaType(contentType);

                String encodedFileName = java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
                String contentDisposition = "inline; filename=\"" + encodedFileName + "\"; filename*=UTF-8''" + encodedFileName;

                if (rangeHeader == null || rangeHeader.isBlank()) {
                        return ResponseEntity.ok()
                                        .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                                        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                                        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                                        .contentLength(fileLength)
                                        .contentType(mediaType)
                                        .body(resource);
                }

                List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
                HttpRange range = ranges.get(0);
                long start = range.getRangeStart(fileLength);
                long end = range.getRangeEnd(fileLength);
                long rangeLength = end - start + 1;

                ResourceRegion region = new ResourceRegion(resource, start, rangeLength);

                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                                .contentType(mediaType)
                                .body(region);
        }

        // =========================
        // VIEW FILE (inline, auth-gated, Range-aware for fast PDF loading)
        // =========================
        @GetMapping("/files/view")
        @ApiMessage("View a file")
        public ResponseEntity<?> view(
                        @RequestParam("fileName") String fileName,
                        @RequestParam("folder") String folder,
                        @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader)
                        throws StorageException, IOException {

                if (!canAccessFile(fileName, folder)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }

                long fileLength = fileService.getFileLength(fileName, folder);
                if (fileLength == 0) {
                        throw new StorageException("File with name = " + fileName + " not found.");
                }

                Path filePath = fileService.resolvePath(fileName, folder);
                FileSystemResource resource = new FileSystemResource(filePath);
                String contentType = detectContentType(fileName);
                MediaType mediaType = MediaType.parseMediaType(contentType);

                String encodedFileName = java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
                String contentDisposition = "inline; filename=\"" + encodedFileName + "\"; filename*=UTF-8''" + encodedFileName;

                if (rangeHeader == null || rangeHeader.isBlank()) {
                        return ResponseEntity.ok()
                                        .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                                        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                                        .contentLength(fileLength)
                                        .contentType(mediaType)
                                        .body(resource);
                }

                List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
                HttpRange range = ranges.get(0);
                long start = range.getRangeStart(fileLength);
                long end = range.getRangeEnd(fileLength);
                long rangeLength = end - start + 1;

                ResourceRegion region = new ResourceRegion(resource, start, rangeLength);

                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                                .contentType(mediaType)
                                .body(region);
        }

        private String detectContentType(String fileName) {
                String lower = fileName.toLowerCase();
                if (lower.endsWith(".pdf"))  return "application/pdf";
                if (lower.endsWith(".png"))  return "image/png";
                if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
                if (lower.endsWith(".webp")) return "image/webp";
                if (lower.endsWith(".doc") || lower.endsWith(".docx")) return "application/msword";
                if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return "application/vnd.ms-excel";
                return "application/octet-stream";
        }

        // =========================
        // DOWNLOAD FILE
        // =========================
        @GetMapping("/files")
        @ApiMessage("Download a file")
        public ResponseEntity<Resource> download(
                        @RequestParam(value = "fileName", required = false) String fileName,
                        @RequestParam(value = "folder", required = false) String folder) throws StorageException, FileNotFoundException {

                if (fileName == null || fileName.trim().isEmpty() || "undefined".equalsIgnoreCase(fileName)) {
                        throw new StorageException("File name is missing or invalid.");
                }
                if (folder == null || folder.trim().isEmpty() || "undefined".equalsIgnoreCase(folder)) {
                        folder = "documents";
                }

                if (!canAccessFile(fileName, folder)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }

                long fileLength = fileService.getFileLength(fileName, folder);
                if (fileLength == 0) {
                        throw new StorageException(
                                        "File with name = " + fileName + " not found.");
                }

                InputStreamResource resource = fileService.getResource(fileName, folder);

                String encodedFileName = java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
                String contentDisposition = "attachment; filename=\"" + encodedFileName + "\"; filename*=UTF-8''" + encodedFileName;

                return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                                .contentLength(fileLength)
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .body(resource);
        }

}
