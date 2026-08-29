package vn.system.app.modules.document.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.hibernate.annotations.BatchSize;

import vn.system.app.common.util.SecurityUtil;
import vn.system.app.modules.documentcategory.domain.DocumentCategory;
import vn.system.app.modules.department.domain.Department;
import vn.system.app.modules.section.domain.Section;
import vn.system.app.modules.procedure.enums.ProcedureType;
import vn.system.app.modules.documentfolder.domain.DocumentFolder;
import vn.system.app.modules.user.domain.User;

@Entity
@Table(name = "document", indexes = {
        @Index(name = "idx_document_category_active", columnList = "category_id,active"),
        @Index(name = "idx_document_department_active", columnList = "department_id,active"),
        @Index(name = "idx_document_is_locked", columnList = "is_locked")
})
@Getter
@Setter
@JsonIgnoreProperties({ "hibernateLazyInitializer", "handler" })
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_code", length = 100, nullable = false)
    private String documentCode;

    @Column(name = "document_name", nullable = false)
    private String documentName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    @JsonIgnoreProperties({ "documents" })
    private DocumentCategory category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accounting_category_id")
    @JsonIgnoreProperties({ "hibernateLazyInitializer", "handler" })
    private AccountingDocumentCategory accountingCategory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    @JsonIgnoreProperties({ "documents" })
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "section_id")
    @JsonIgnoreProperties({ "documents" })
    private Section section;

    private String status;
    private Instant issuedDate;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String fileUrls;

    private String note;

    @Column(name = "is_locked")
    private Boolean isLocked = false;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "locked_by")
    private String lockedBy;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private Integer version = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "procedure_type")
    private ProcedureType procedureType;

    @Column(name = "procedure_id")
    private Long procedureId;

    @Column(name = "qr_token", length = 36, unique = true)
    private String qrToken;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id")
    @JsonIgnoreProperties({ "children", "parent" })
    private DocumentFolder folder;

    @Column(name = "qr_code", columnDefinition = "MEDIUMTEXT")
    private String qrCode;

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JsonIgnoreProperties({ "document" })
    private List<DocumentAccess> accessList = new ArrayList<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "document_departments_mapping", joinColumns = @JoinColumn(name = "document_id"), inverseJoinColumns = @JoinColumn(name = "department_id"))
    @JsonIgnoreProperties({ "documents" })
    @BatchSize(size = 50)
    private List<Department> departments = new ArrayList<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "document_excluded_departments_mapping", joinColumns = @JoinColumn(name = "document_id"), inverseJoinColumns = @JoinColumn(name = "department_id"))
    @JsonIgnoreProperties({ "documents" })
    @BatchSize(size = 50)
    private List<Department> excludedDepartments = new ArrayList<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "document_excluded_users_mapping", joinColumns = @JoinColumn(name = "document_id"), inverseJoinColumns = @JoinColumn(name = "user_id"))
    @BatchSize(size = 50)
    private List<User> excludedUsers = new ArrayList<>();

    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;

    @PrePersist
    public void beforeCreate() {
        this.createdAt = Instant.now();
        this.createdBy = SecurityUtil.getCurrentUserLogin().orElse("");
        this.active = true;
        if (this.version == null)
            this.version = 1;
    }

    @PreUpdate
    public void beforeUpdate() {
        this.updatedAt = Instant.now();
        this.updatedBy = SecurityUtil.getCurrentUserLogin().orElse("");
    }
}
