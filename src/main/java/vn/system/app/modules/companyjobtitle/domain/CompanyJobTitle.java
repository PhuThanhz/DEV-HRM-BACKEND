package vn.system.app.modules.companyjobtitle.domain;

import java.time.Instant;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import vn.system.app.common.util.SecurityUtil;
import vn.system.app.modules.company.domain.Company;
import vn.system.app.modules.jobtitle.domain.JobTitle;

@Entity
@Table(name = "company_job_titles", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "company_id", "job_title_id" })
})
@Getter
@Setter
public class CompanyJobTitle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /*
     * =========================
     * RELATION
     * =========================
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_title_id", nullable = false)
    private JobTitle jobTitle;

    /*
     * =========================
     * ACTIVE FLAG
     * =========================
     */
    @Column(nullable = false)
    private boolean active = true;

    /*
     * =========================
     * AUDIT
     * =========================
     */
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
