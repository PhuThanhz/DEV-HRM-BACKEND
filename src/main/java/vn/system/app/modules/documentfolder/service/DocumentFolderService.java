package vn.system.app.modules.documentfolder.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.common.util.SecurityUtil;
import vn.system.app.common.util.UserScopeContext;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.common.util.error.PermissionException;
import vn.system.app.modules.documentfolder.domain.DocumentFolder;
import vn.system.app.modules.documentfolder.domain.request.DocumentFolderRequest;
import vn.system.app.modules.documentfolder.domain.response.ResDocumentFolderDTO;
import vn.system.app.modules.documentfolder.repository.DocumentFolderRepository;
import vn.system.app.modules.documentcategory.domain.DocumentCategory;
import vn.system.app.modules.documentcategory.repository.DocumentCategoryRepository;
import vn.system.app.modules.user.domain.User;
import vn.system.app.modules.user.domain.response.ResUserDTO;
import vn.system.app.modules.user.repository.UserRepository;
import vn.system.app.modules.userposition.repository.UserPositionRepository;
import org.springframework.context.annotation.Lazy;

import vn.system.app.modules.document.domain.Document;
import vn.system.app.modules.document.domain.DocumentShortcut;
import vn.system.app.modules.document.domain.response.ResDocumentDTO;
import vn.system.app.modules.document.repository.DocumentRepository;
import vn.system.app.modules.document.repository.DocumentShortcutRepository;
import vn.system.app.modules.document.service.DocumentService;

@Service
public class DocumentFolderService {

    private static final String ACCOUNTING_CATEGORY_CODE = "ACCOUNTING_DOC";
    private static final Set<String> LEGACY_DEFAULT_FOLDER_NAMES = Set.of(
            "01_Hóa đơn & Chứng từ",
            "02_Lương & Thuế",
            "03_Hợp đồng & Quyết định",
            "04_Bằng cấp & Chứng chỉ",
            "05_Tài liệu khác");

    private final DocumentFolderRepository repository;
    private final DocumentCategoryRepository documentCategoryRepository;
    private final UserRepository userRepository;
    private final UserPositionRepository userPositionRepository;
    private final DocumentRepository documentRepository;
    private final DocumentShortcutRepository shortcutRepository;
    private final DocumentService documentService;

    public DocumentFolderService(
            DocumentFolderRepository repository,
            DocumentCategoryRepository documentCategoryRepository,
            UserRepository userRepository,
            UserPositionRepository userPositionRepository,
            DocumentRepository documentRepository,
            DocumentShortcutRepository shortcutRepository,
            @Lazy DocumentService documentService) {
        this.repository = repository;
        this.documentCategoryRepository = documentCategoryRepository;
        this.userRepository = userRepository;
        this.userPositionRepository = userPositionRepository;
        this.documentRepository = documentRepository;
        this.shortcutRepository = shortcutRepository;
        this.documentService = documentService;
    }

    /**
     * Validate scope of folder
     */
    public void validateFolderScope(DocumentFolder folder, boolean write) {
        String currentUserId = SecurityUtil.getCurrentUserId().orElse("");
        UserScopeContext.UserScope scope = UserScopeContext.get();

        if (scope != null && (scope.isSuperAdmin() || scope.isAdminLevel())) {
            return; // SuperAdmin & HR Admin have full access
        }

        if ("ACCOUNTING".equals(folder.getFolderType())) {
            // For accounting folders, check if user has permission
            boolean hasAccountingPerm = SecurityUtil.hasPermission(write ? "ACCOUNTING_DOCUMENTS:CREATE" : "ACCOUNTING_DOCUMENTS:VIEW");
            if (!hasAccountingPerm && !write) {
                hasAccountingPerm = SecurityUtil.hasPermission("ACCOUNTING_DOCUMENTS:GET_PAGINATE");
            }
            if (!hasAccountingPerm) {
                throw new PermissionException("Bạn không có quyền thao tác trên thư mục kế toán này");
            }
            
            if (scope != null && folder.getCompanyId() != null) {
                if (scope.companyIds() != null && scope.companyIds().contains(folder.getCompanyId())) {
                    return; // OK, belongs to their company
                } else {
                    throw new PermissionException("Thư mục kế toán này thuộc công ty khác");
                }
            }
            return; // System-wide accounting folder
        }

        if (folder.getOwnerId() != null && folder.getOwnerId().equals(currentUserId)) {
            return; // Owner has full access
        }

        // Check if current user has company admin access (Read-only)
        if (!write && scope != null && scope.isCompanyLevel()) {
            java.util.List<vn.system.app.modules.userposition.domain.UserPosition> positions = 
                userPositionRepository.findActiveFullByUserId(folder.getOwnerId());
            boolean companyMatches = positions.stream().anyMatch(pos -> {
                Long companyId = null;
                switch (pos.getSource().toUpperCase()) {
                    case "COMPANY" -> companyId = pos.getCompanyJobTitle().getCompany().getId();
                    case "DEPARTMENT" -> companyId = pos.getDepartmentJobTitle().getDepartment().getCompany().getId();
                    case "SECTION" -> companyId = pos.getSectionJobTitle().getSection().getDepartment().getCompany().getId();
                }
                return companyId != null && scope.companyIds() != null && scope.companyIds().contains(companyId);
            });
            if (companyMatches) {
                return;
            }
        }

        // Check if current user is the direct manager (Read-only)
        if (!write) {
            User owner = userRepository.findById(folder.getOwnerId()).orElse(null);
            if (owner != null && owner.getDirectManager() != null 
                    && owner.getDirectManager().getId().equals(currentUserId)) {
                return;
            }
        }

        throw new PermissionException("Bạn không có quyền thao tác trên thư mục này");
    }

    /**
     * Khởi tạo và đồng bộ thư mục năm hiện tại theo danh mục loại văn bản.
     *
     * Thư mục có documentCategoryId là thư mục do hệ thống quản lý. Thư mục do
     * người dùng tự tạo không bị đổi tên/xóa trong quá trình đồng bộ.
     */
    @Transactional
    public void initDefaultFoldersIfNecessary(String ownerId) {
        int currentYear = LocalDate.now().getYear();
        String yearFolderName = "Năm " + currentYear;

        DocumentFolder yearFolder = repository.findByOwnerIdAndParentIsNull(ownerId).stream()
                .filter(folder -> yearFolderName.equals(folder.getFolderName()))
                .findFirst()
                .orElseGet(() -> {
            DocumentFolder newYearFolder = new DocumentFolder();
            newYearFolder.setFolderName(yearFolderName);
            newYearFolder.setOwnerId(ownerId);
            newYearFolder.setFolderType("PERSONAL");
            return repository.save(newYearFolder);
        });

        List<DocumentCategory> activeCategories = documentCategoryRepository.findByActiveTrueOrderByIdAsc().stream()
                .filter(category -> !ACCOUNTING_CATEGORY_CODE.equals(category.getCategoryCode()))
                .toList();

        List<DocumentFolder> currentChildren = new ArrayList<>(yearFolder.getChildren());
        Map<Long, DocumentFolder> managedFoldersByCategoryId = currentChildren.stream()
                .filter(folder -> folder.getDocumentCategoryId() != null)
                .collect(Collectors.toMap(
                        DocumentFolder::getDocumentCategoryId,
                        folder -> folder,
                        (first, duplicate) -> first,
                        HashMap::new));

        Set<Long> activeCategoryIds = activeCategories.stream()
                .map(DocumentCategory::getId)
                .collect(Collectors.toSet());

        List<DocumentFolder> foldersToRemove = currentChildren.stream()
                .filter(folder -> shouldRemoveManagedFolder(folder, activeCategoryIds)
                        || shouldRemoveLegacyDefaultFolder(folder))
                .toList();
        if (!foldersToRemove.isEmpty()) {
            yearFolder.getChildren().removeAll(foldersToRemove);
            repository.deleteAll(foldersToRemove);
        }

        for (int index = 0; index < activeCategories.size(); index++) {
            DocumentCategory category = activeCategories.get(index);
            String expectedFolderName = buildCategoryFolderName(index, category.getCategoryName());
            DocumentFolder categoryFolder = managedFoldersByCategoryId.get(category.getId());

            if (categoryFolder == null || foldersToRemove.contains(categoryFolder)) {
                categoryFolder = new DocumentFolder();
                categoryFolder.setParent(yearFolder);
                categoryFolder.setOwnerId(ownerId);
                categoryFolder.setFolderType("PERSONAL");
                categoryFolder.setDocumentCategoryId(category.getId());
                yearFolder.getChildren().add(categoryFolder);
            }

            if (!Objects.equals(categoryFolder.getFolderName(), expectedFolderName)) {
                categoryFolder.setFolderName(expectedFolderName);
            }
            repository.save(categoryFolder);
        }
    }

    private boolean shouldRemoveManagedFolder(DocumentFolder folder, Set<Long> activeCategoryIds) {
        return folder.getDocumentCategoryId() != null
                && !activeCategoryIds.contains(folder.getDocumentCategoryId())
                && isFolderEmpty(folder);
    }

    private boolean shouldRemoveLegacyDefaultFolder(DocumentFolder folder) {
        return folder.getDocumentCategoryId() == null
                && LEGACY_DEFAULT_FOLDER_NAMES.contains(folder.getFolderName())
                && isFolderEmpty(folder);
    }

    private boolean isFolderEmpty(DocumentFolder folder) {
        return folder.getChildren().isEmpty()
                && documentRepository.findByFolder_Id(folder.getId()).isEmpty()
                && shortcutRepository.findByFolderId(folder.getId()).isEmpty();
    }

    private String buildCategoryFolderName(int index, String categoryName) {
        return String.format("%02d_%s", index + 1, categoryName.trim());
    }

    /**
     * Lấy cây thư mục
     */
    @Transactional
    public List<ResDocumentFolderDTO> getTreeForUser(String ownerId) {
        String currentUserId = SecurityUtil.getCurrentUserId().orElse("");
        UserScopeContext.UserScope scope = UserScopeContext.get();

        // Kiểm tra quyền truy cập cây thư mục của ownerId
        boolean hasAccess = ownerId.equals(currentUserId) 
                || (scope != null && (scope.isSuperAdmin() || scope.isAdminLevel()));

        if (!hasAccess && scope != null && scope.isCompanyLevel()) {
            java.util.List<vn.system.app.modules.userposition.domain.UserPosition> positions = 
                userPositionRepository.findActiveFullByUserId(ownerId);
            boolean companyMatches = positions.stream().anyMatch(pos -> {
                Long companyId = null;
                switch (pos.getSource().toUpperCase()) {
                    case "COMPANY" -> companyId = pos.getCompanyJobTitle().getCompany().getId();
                    case "DEPARTMENT" -> companyId = pos.getDepartmentJobTitle().getDepartment().getCompany().getId();
                    case "SECTION" -> companyId = pos.getSectionJobTitle().getSection().getDepartment().getCompany().getId();
                }
                return companyId != null && scope.companyIds() != null && scope.companyIds().contains(companyId);
            });
            if (companyMatches) {
                hasAccess = true;
            }
        }

        if (!hasAccess) {
            User owner = userRepository.findById(ownerId).orElse(null);
            if (owner != null && owner.getDirectManager() != null 
                    && owner.getDirectManager().getId().equals(currentUserId)) {
                hasAccess = true;
            }
        }

        if (!hasAccess) {
            throw new PermissionException("Bạn không có quyền xem tài liệu của người dùng này");
        }

        // Tự động khởi tạo thư mục năm hiện tại nếu cần
        initDefaultFoldersIfNecessary(ownerId);

        List<DocumentFolder> roots = repository.findByOwnerIdAndParentIsNull(ownerId);
        List<ResDocumentFolderDTO> tree = roots.stream().map(this::convertToDTO).collect(Collectors.toList());
        for (ResDocumentFolderDTO root : tree) {
            calculateRecursiveCount(root);
        }
        return tree;
    }

    /**
     * Lấy cây thư mục Kế toán
     */
    @Transactional
    public List<ResDocumentFolderDTO> getAccountingTree(Long companyId) {
        boolean hasAccountingPerm = SecurityUtil.hasPermission("ACCOUNTING_DOCUMENTS:GET_PAGINATE");
        if (!hasAccountingPerm) {
            throw new PermissionException("Bạn không có quyền xem thư mục kế toán");
        }

        UserScopeContext.UserScope scope = UserScopeContext.get();
        if (scope != null && !scope.isSuperAdmin() && !scope.isAdminLevel()) {
            if (scope.companyIds() == null || !scope.companyIds().contains(companyId)) {
                throw new PermissionException("Bạn không có quyền xem thư mục kế toán của công ty này");
            }
        }

        List<DocumentFolder> roots = repository.findByFolderTypeAndCompanyIdAndParentIsNull("ACCOUNTING", companyId);
        List<ResDocumentFolderDTO> tree = roots.stream().map(this::convertToDTO).collect(Collectors.toList());
        for (ResDocumentFolderDTO root : tree) {
            calculateRecursiveCount(root);
        }
        return tree;
    }

    private long calculateRecursiveCount(ResDocumentFolderDTO dto) {
        long sum = dto.getDocumentCount() != null ? dto.getDocumentCount() : 0L;
        if (dto.getChildren() != null) {
            for (ResDocumentFolderDTO child : dto.getChildren()) {
                sum += calculateRecursiveCount(child);
            }
        }
        dto.setDocumentCount(sum);
        return sum;
    }

    /**
     * Tạo thư mục mới
     */
    @Transactional
    public ResDocumentFolderDTO createFolder(DocumentFolderRequest req) {
        String currentUserId = SecurityUtil.getCurrentUserId().orElse("");
        String ownerId = req.getOwnerId() != null ? req.getOwnerId() : currentUserId;
        UserScopeContext.UserScope scope = UserScopeContext.get();

        if ("ACCOUNTING".equals(req.getFolderType())) {
            boolean hasAccountingPerm = SecurityUtil.hasPermission("ACCOUNTING_DOCUMENTS:CREATE");
            if (!hasAccountingPerm) {
                throw new PermissionException("Bạn không có quyền tạo thư mục kế toán");
            }
            if (req.getCompanyId() == null) {
                throw new IdInvalidException("Thiếu thông tin công ty cho thư mục kế toán");
            }
            if (scope != null && !scope.isSuperAdmin() && !scope.isAdminLevel()) {
                if (scope.companyIds() == null || !scope.companyIds().contains(req.getCompanyId())) {
                    throw new PermissionException("Không thể tạo thư mục kế toán cho công ty khác");
                }
            }

            DocumentFolder parent = null;
            if (req.getParentId() != null) {
                parent = repository.findById(req.getParentId())
                        .orElseThrow(() -> new IdInvalidException("Thư mục cha không tồn tại"));
                validateFolderScope(parent, true);
            }

            boolean exists = req.getParentId() != null 
                    ? repository.existsByFolderTypeAndCompanyIdAndParentIdAndFolderName("ACCOUNTING", req.getCompanyId(), req.getParentId(), req.getFolderName())
                    : repository.existsByFolderTypeAndCompanyIdAndParentIsNullAndFolderName("ACCOUNTING", req.getCompanyId(), req.getFolderName());
            if (exists) {
                throw new IdInvalidException("Thư mục cùng tên đã tồn tại");
            }

            DocumentFolder entity = new DocumentFolder();
            entity.setFolderName(req.getFolderName());
            entity.setParent(parent);
            entity.setFolderType("ACCOUNTING");
            entity.setCompanyId(req.getCompanyId());
            entity.setOwnerId(currentUserId);
            return convertToDTO(repository.save(entity));
        }

        // Chỉ cho phép chính chủ hoặc Admin tạo thư mục (PERSONAL)
        boolean canCreate = ownerId.equals(currentUserId) 
                || (scope != null && (scope.isSuperAdmin() || scope.isAdminLevel()));
        if (!canCreate) {
            throw new PermissionException("Bạn không có quyền tạo thư mục cho người dùng này");
        }

        DocumentFolder parent = null;
        if (req.getParentId() != null) {
            parent = repository.findById(req.getParentId())
                    .orElseThrow(() -> new IdInvalidException("Thư mục cha không tồn tại"));
            validateFolderScope(parent, true);
        }

        // Check trùng tên
        boolean exists = req.getParentId() != null 
                ? repository.existsByOwnerIdAndParentIdAndFolderName(ownerId, req.getParentId(), req.getFolderName())
                : repository.existsByOwnerIdAndParentIsNullAndFolderName(ownerId, req.getFolderName());
        if (exists) {
            throw new IdInvalidException("Thư mục cùng tên đã tồn tại");
        }

        DocumentFolder entity = new DocumentFolder();
        entity.setFolderName(req.getFolderName());
        entity.setParent(parent);
        entity.setOwnerId(ownerId);
        entity.setFolderType("PERSONAL");
        return convertToDTO(repository.save(entity));
    }

    /**
     * Cập nhật thư mục (Đổi tên / Di chuyển)
     */
    @Transactional
    public ResDocumentFolderDTO updateFolder(Long id, DocumentFolderRequest req) {
        DocumentFolder entity = repository.findById(id)
                .orElseThrow(() -> new IdInvalidException("Thư mục không tồn tại"));

        validateFolderScope(entity, true);

        if ("ACCOUNTING".equals(entity.getFolderType())) {
            boolean exists = entity.getParent() != null
                    ? repository.existsByFolderTypeAndCompanyIdAndParentIdAndFolderNameAndIdNot("ACCOUNTING", entity.getCompanyId(), entity.getParent().getId(), req.getFolderName(), id)
                    : repository.existsByFolderTypeAndCompanyIdAndParentIsNullAndFolderNameAndIdNot("ACCOUNTING", entity.getCompanyId(), req.getFolderName(), id);
            if (exists) {
                throw new IdInvalidException("Thư mục cùng tên đã tồn tại");
            }
        } else {
            boolean exists = entity.getParent() != null
                    ? repository.existsByOwnerIdAndParentIdAndFolderNameAndIdNot(entity.getOwnerId(), entity.getParent().getId(), req.getFolderName(), id)
                    : repository.existsByOwnerIdAndParentIsNullAndFolderNameAndIdNot(entity.getOwnerId(), req.getFolderName(), id);
            if (exists) {
                throw new IdInvalidException("Thư mục cùng tên đã tồn tại");
            }
        }

        // Di chuyển thư mục (nếu thay đổi parentId)
        Long targetParentId = req.getParentId();
        if (targetParentId != null) {
            if (targetParentId.equals(id)) {
                throw new IdInvalidException("Không thể di chuyển thư mục vào chính nó");
            }
            // Check không di chuyển vào thư mục con của chính nó
            DocumentFolder parent = repository.findById(targetParentId)
                    .orElseThrow(() -> new IdInvalidException("Thư mục đích không tồn tại"));
            validateFolderScope(parent, true);
            
            DocumentFolder checkParent = parent;
            while (checkParent != null) {
                if (checkParent.getId().equals(id)) {
                    throw new IdInvalidException("Không thể di chuyển thư mục vào thư mục con của chính nó");
                }
                checkParent = checkParent.getParent();
            }
            entity.setParent(parent);
        } else if (req.getParentId() == null && entity.getParent() != null) {
            entity.setParent(null);
        }

        entity.setFolderName(req.getFolderName());
        return convertToDTO(repository.save(entity));
    }

    /**
     * Xóa thư mục
     */
    @Transactional
    public void deleteFolder(Long id) {
        DocumentFolder current = repository.findById(id)
                .orElseThrow(() -> new IdInvalidException("Thư mục không tồn tại"));
        validateFolderScope(current, true);
        repository.delete(current);
    }

    /**
     * Lấy danh sách cấp dưới trực tiếp
     */
    @Transactional(readOnly = true)
    public List<ResUserDTO> getSubordinates() {
        String currentUserId = SecurityUtil.getCurrentUserId().orElse("");
        User manager = userRepository.findById(currentUserId).orElse(null);
        if (manager != null && manager.getSubordinates() != null) {
            return manager.getSubordinates().stream().map(u -> {
                ResUserDTO dto = new ResUserDTO();
                dto.setId(u.getId());
                dto.setName(u.getName());
                dto.setEmail(u.getEmail());
                dto.setAvatar(u.getAvatar());
                dto.setActive(u.isActive());
                return dto;
            }).collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    /**
     * Lấy danh sách tài liệu trong thư mục (bao gồm trực tiếp và lối tắt)
     */
    @Transactional(readOnly = true)
    public List<ResDocumentDTO> getFolderDocuments(Long folderId) {
        DocumentFolder folder = repository.findById(folderId)
                .orElseThrow(() -> new IdInvalidException("Thư mục không tồn tại"));
        validateFolderScope(folder, false);

        List<Document> directDocs = documentRepository.findByFolder_Id(folderId);
        List<ResDocumentDTO> rs = directDocs.stream()
                .filter(doc -> !documentService.isExcludedForCurrentUser(doc))
                .map(documentService::convertToDTO)
                .collect(Collectors.toList());

        List<DocumentShortcut> shortcuts = shortcutRepository.findByFolderId(folderId);
        List<ResDocumentDTO> shortcutDocs = shortcuts.stream()
                .filter(s -> !documentService.isExcludedForCurrentUser(s.getDocument()))
                .map(s -> {
                    ResDocumentDTO dto = documentService.convertToDTO(s.getDocument());
                    dto.setIsShortcut(true);
                    return dto;
                })
                .collect(Collectors.toList());

        rs.addAll(shortcutDocs);
        return rs;
    }

    /**
     * Convert to DTO
     */
    public ResDocumentFolderDTO convertToDTO(DocumentFolder e) {
        ResDocumentFolderDTO dto = new ResDocumentFolderDTO();
        dto.setId(e.getId());
        dto.setFolderName(e.getFolderName());
        dto.setParentId(e.getParent() != null ? e.getParent().getId() : null);
        dto.setOwnerId(e.getOwnerId());
        dto.setDocumentCount(e.getDocumentCount() != null ? e.getDocumentCount() : 0L);
        
        if (e.getChildren() != null && !e.getChildren().isEmpty()) {
            dto.setChildren(e.getChildren().stream()
                    .map(this::convertToDTO)
                    .collect(Collectors.toList()));
        } else {
            dto.setChildren(new ArrayList<>());
        }

        dto.setFolderType(e.getFolderType());
        dto.setCompanyId(e.getCompanyId());
        dto.setDocumentCategoryId(e.getDocumentCategoryId());

        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        dto.setCreatedBy(e.getCreatedBy());
        dto.setUpdatedBy(e.getUpdatedBy());
        return dto;
    }
}
