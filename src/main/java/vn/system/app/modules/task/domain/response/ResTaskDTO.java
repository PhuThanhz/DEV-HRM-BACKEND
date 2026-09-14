package vn.system.app.modules.task.domain.response;

import java.time.Instant;

import lombok.Getter;
import lombok.Setter;

import vn.system.app.modules.kpigroup.domain.enums.KpiOutputType;
import vn.system.app.modules.kpigroup.domain.enums.KpiTargetDirection;
import vn.system.app.modules.kpigroup.domain.enums.KpiType;
import vn.system.app.modules.task.domain.enums.KpiCycleType;
import vn.system.app.modules.task.domain.enums.TaskPriority;
import vn.system.app.modules.task.domain.enums.TaskStatus;

@Getter
@Setter
public class ResTaskDTO {

    private Long id;
    private String title;
    private String description;
    private TaskStatus status;
    private TaskPriority priority;
    private Instant startDate;
    private Instant dueDate;
    private Instant completedAt;
    private Boolean isOnTime;
    private Double estimatedHours;
    private Integer reworkCount;
    private String reworkReason;
    private Long jobDescriptionTaskId;
    private String jobDescriptionTaskTitle;
    private Long jobDescriptionTaskItemId;
    private String jobDescriptionTaskItemContent;
    private Long departmentId;
    private String departmentName;
    private Long companyId;
    private String companyName;
    private boolean overdue;

    private Long kpiGroupId;
    private String kpiGroupName;
    private KpiType kpiGroupType;
    private KpiTargetDirection kpiTargetDirection;
    private KpiOutputType kpiOutputType;
    private KpiCycleType kpiCycleType;
    private Double kpiTargetValue;
    private String kpiTargetUnit;
    private Double kpiActualValue;
    private Double kpiResultRatio;
    private Double kpiScorePoints;
    private Double kpiResultTotal;

    // Denormalized user fields for quick UI rendering without extra N+1 queries
    private String assigneeId;
    private String assigneeName;
    private String assigneeAvatar;
    private Boolean assigneeActive;

    private String creatorId;
    private String creatorName;
    private String creatorAvatar;

    private Integer collaboratorCount;
    private Integer observerCount;

    private Integer checklistDoneCount;
    private Integer checklistTotalCount;
    private Integer progress; // null if checklistTotalCount == 0
    private String latestResultSummary;

    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
