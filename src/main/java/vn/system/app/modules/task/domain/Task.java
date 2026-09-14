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
import vn.system.app.modules.kpigroup.domain.KpiGroup;
import vn.system.app.modules.kpigroup.domain.enums.KpiOutputType;
import vn.system.app.modules.kpigroup.domain.enums.KpiTargetDirection;
import vn.system.app.modules.task.domain.enums.KpiCycleType;
import vn.system.app.modules.task.domain.enums.TaskPriority;
import vn.system.app.modules.task.domain.enums.TaskStatus;

@Entity
@Table(name = "tasks", indexes = {
        @Index(name = "idx_tasks_department_status", columnList = "department_id, status"),
        @Index(name = "idx_tasks_due_date", columnList = "due_date"),
        @Index(name = "idx_tasks_job_description_task_id", columnList = "job_description_task_id"),
        @Index(name = "idx_tasks_status_completed_at", columnList = "status, completed_at"),
        @Index(name = "idx_tasks_kpi_group_id", columnList = "kpi_group_id")
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

    /**
     * Nhóm KPI/OKR mà tác vụ này đóng góp vào — độc lập với Nhiệm vụ JD (vốn gắn theo
     * mô tả công việc/vị trí). Optional: task không nhất thiết phải phục vụ đo lường KPI.
     */
    @Column(name = "kpi_group_id")
    private Long kpiGroupId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "kpi_group_id", insertable = false, updatable = false)
    @JsonIgnoreProperties({ "company" })
    private KpiGroup kpiGroup;

    /**
     * Chu kỳ đo lường KPI của riêng task này (không cố định theo Nhóm KPI, vì cùng 1 nhóm
     * có thể có nhiều task đo theo chu kỳ khác nhau).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "kpi_cycle_type", length = 20)
    private KpiCycleType kpiCycleType;

    /** Hướng mục tiêu KPI (INCREASING - Doanh số/Năng suất vs DECREASING - Số lỗi/Chi phí/Sự cố). */
    @Enumerated(EnumType.STRING)
    @Column(name = "kpi_target_direction", length = 30)
    private KpiTargetDirection kpiTargetDirection = KpiTargetDirection.INCREASING;

    /** Hệ thống quy chuẩn đầu ra (PERCENTAGE - % Hoàn thành vs SCORE_POINTS - Hệ điểm quy đổi). */
    @Enumerated(EnumType.STRING)
    @Column(name = "kpi_output_type", length = 30)
    private KpiOutputType kpiOutputType = KpiOutputType.PERCENTAGE;

    /** Mục tiêu (loại RATIO) hoặc Mục tiêu sản lượng (loại VOLUME_PRICING) — nhập khi tạo/sửa task. */
    @Column(name = "kpi_target_value")
    private Double kpiTargetValue;

    /** Đơn vị đo — chỉ dùng cho loại RATIO (vd: "hồ sơ", "%", "giờ", "lỗi", "triệu VNĐ"). */
    @Column(name = "kpi_target_unit", length = 50)
    private String kpiTargetUnit;

    /** Thực đạt (loại RATIO) hoặc Thực đạt sản lượng (loại VOLUME_PRICING) — nhập lúc nộp kết quả. */
    @Column(name = "kpi_actual_value")
    private Double kpiActualValue;

    /** Tỉ lệ (%) — tự tính khi nộp kết quả, dựa theo kpiTargetDirection (INCREASING vs DECREASING). */
    @Column(name = "kpi_result_ratio")
    private Double kpiResultRatio;

    /** Điểm quy đổi — tự tính theo thang điểm nếu kpiOutputType == SCORE_POINTS. */
    @Column(name = "kpi_score_points")
    private Double kpiScorePoints;

    /** Tổng tiền — tự tính lũy tiến theo bậc thang khi nộp kết quả, chỉ có giá trị với loại VOLUME_PRICING. */
    @Column(name = "kpi_result_total")
    private Double kpiResultTotal;

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
