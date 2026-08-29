package vn.system.app.modules.userposition.domain;

import java.time.Instant;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import vn.system.app.common.util.SecurityUtil;
import vn.system.app.modules.companyjobtitle.domain.CompanyJobTitle;
import vn.system.app.modules.departmentjobtitle.domain.DepartmentJobTitle;
import vn.system.app.modules.sectionjobtitle.domain.SectionJobTitle;
import vn.system.app.modules.user.domain.User;

@Entity
@Table(name = "user_positions", indexes = {
        @Index(name = "idx_user_positions_user_active", columnList = "user_id, active")
})
@Getter
@Setter
public class UserPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 20)
    private String source; // COMPANY / DEPARTMENT / SECTION

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_job_title_id")
    private CompanyJobTitle companyJobTitle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_job_title_id")
    private DepartmentJobTitle departmentJobTitle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "section_job_title_id")
    private SectionJobTitle sectionJobTitle;

    @Column(nullable = false)
    private boolean active = true;

    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;

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