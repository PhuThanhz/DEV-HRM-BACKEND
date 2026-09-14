package vn.system.app.modules.employeecareerpath.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

import vn.system.app.common.util.SecurityUtil;
import vn.system.app.modules.careerpathtemplate.domain.CareerPathTemplate;
import vn.system.app.modules.user.domain.User;

@Entity
@Table(name = "employee_career_paths")
@Getter
@Setter
public class EmployeeCareerPath {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Lộ trình template đang theo
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id", nullable = false)
    private CareerPathTemplate template;

    // Đang ở bước thứ mấy trong template (1, 2, 3...)
    @Column(name = "current_step_order", nullable = false)
    private Integer currentStepOrder;

    // Ngày bắt đầu bước hiện tại
    @Column(name = "step_started_at")
    private LocalDate stepStartedAt;

    /*
     * progressStatus:
     * 0 = IN_PROGRESS
     * 1 = COMPLETED (đã lên đỉnh)
     * 2 = ON_HOLD
     */
    @Column(name = "progress_status")
    private Integer progressStatus = 0;

    @Column(columnDefinition = "TEXT")
    private String note;

    private boolean active = true;

    @OneToMany(mappedBy = "employeeCareerPath", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("promotedAt DESC")
    @BatchSize(size = 20)
    private List<EmployeeCareerPathHistory> histories;

    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;

    @PrePersist
    public void beforeCreate() {
        this.createdAt = Instant.now();
        this.createdBy = SecurityUtil.getCurrentUserLogin().orElse("");
    }

    @PreUpdate
    public void beforeUpdate() {
        this.updatedAt = Instant.now();
        this.updatedBy = SecurityUtil.getCurrentUserLogin().orElse("");
    }
}