package vn.system.app.modules.task.domain;

import java.time.Instant;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import vn.system.app.common.util.SecurityUtil;
import vn.system.app.modules.department.domain.Department;
import vn.system.app.modules.jd.jobdescriptiontask.domain.JobDescriptionTask;
import vn.system.app.modules.jd.jobdescriptiontask.domain.JobDescriptionTaskItem;
import vn.system.app.modules.task.domain.enums.TaskPriority;
import vn.system.app.modules.task.domain.enums.TaskStatus;

@Entity
@Table(name = "tasks", indexes = {
        @Index(name = "idx_tasks_department_status", columnList = "department_id, status"),
        @Index(name = "idx_tasks_due_date", columnList = "due_date"),
        @Index(name = "idx_tasks_job_description_task_id", columnList = "job_description_task_id")
})
@Getter
@Setter
@JsonIgnoreProperties({ "hibernateLazyInitializer", "handler" })
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Tên tác vụ không được để trống")
    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskPriority priority = TaskPriority.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status = TaskStatus.TODO;

    private Instant startDate;

    private Instant dueDate;

    private Instant completedAt;

    private Boolean isOnTime;

    @Column(nullable = false)
    private Double estimatedHours = 0.0;

    @Column(nullable = false)
    private Integer reworkCount = 0;

    @Column(columnDefinition = "TEXT")
    private String reworkReason;

    @Column(name = "job_description_task_id")
    private Long jobDescriptionTaskId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_description_task_id", insertable = false, updatable = false)
    @JsonIgnoreProperties({ "jobDescription" })
    private JobDescriptionTask jobDescriptionTask;

    /**
     * Mục con (1.1, 1.2...) trong Nhiệm vụ JD — optional, null nghĩa là Task chỉ
     * liên kết tới cả mục lớn ({@link #jobDescriptionTaskId}).
     */
    @Column(name = "job_description_task_item_id")
    private Long jobDescriptionTaskItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_description_task_item_id", insertable = false, updatable = false)
    @JsonIgnoreProperties({ "jobDescriptionTask" })
    private JobDescriptionTaskItem jobDescriptionTaskItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "department_id", nullable = false)
    @JsonIgnoreProperties({ "company" })
    private Department department;

    @Version
    @Column(nullable = false)
    private Integer version = 0;

    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;

    @PrePersist
    public void beforeCreate() {
        this.createdAt = Instant.now();
        this.createdBy = SecurityUtil.getCurrentUserLogin().orElse("");
        if (this.priority == null) {
            this.priority = TaskPriority.MEDIUM;
        }
        if (this.status == null) {
            this.status = TaskStatus.TODO;
        }
        if (this.estimatedHours == null) {
            this.estimatedHours = 0.0;
        }
        if (this.reworkCount == null) {
            this.reworkCount = 0;
        }
        if (this.version == null) {
            this.version = 0;
        }
    }

    @PreUpdate
    public void beforeUpdate() {
        this.updatedAt = Instant.now();
        this.updatedBy = SecurityUtil.getCurrentUserLogin().orElse("");
    }
}
