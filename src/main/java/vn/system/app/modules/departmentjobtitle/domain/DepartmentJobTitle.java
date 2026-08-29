package vn.system.app.modules.departmentjobtitle.domain;

import java.time.Instant;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import vn.system.app.common.util.SecurityUtil;
import vn.system.app.modules.department.domain.Department;
import vn.system.app.modules.jobtitle.domain.JobTitle;

@Entity
@Table(name = "department_job_titles", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "department_id", "job_title_id" })
})
@Getter
@Setter
public class DepartmentJobTitle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // =========================
    // RELATION
    // =========================
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_title_id", nullable = false)
    private JobTitle jobTitle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    // =========================
    // ACTIVE FLAG (BOOLEAN)
    // =========================
    @Column(nullable = false)
    private boolean active = true;

    // =========================
    // AUDIT FIELDS
    // =========================
    @Column(updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    private String createdBy;
    private String updatedBy;

    // =========================
    // LIFECYCLE
    // =========================
    @PrePersist
    protected void beforeCreate() {
        Instant now = Instant.now();
        this.active = true;
        this.createdAt = now;
        this.updatedAt = now;

        String user = SecurityUtil.getCurrentUserLogin().orElse("");
        this.createdBy = user;
        this.updatedBy = user;
    }

    @PreUpdate
    protected void beforeUpdate() {
        this.updatedAt = Instant.now();
        this.updatedBy = SecurityUtil.getCurrentUserLogin().orElse("");
    }
}
