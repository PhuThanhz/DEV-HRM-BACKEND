package vn.system.app.modules.task.domain.request;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import vn.system.app.modules.kpigroup.domain.enums.KpiOutputType;
import vn.system.app.modules.kpigroup.domain.enums.KpiTargetDirection;
import vn.system.app.modules.task.domain.enums.KpiCycleType;
import vn.system.app.modules.task.domain.enums.TaskPriority;

@Getter
@Setter
public class ReqCreateTaskDTO {

    @NotBlank(message = "Tên tác vụ không được để trống")
    private String title;

    @Size(max = 5000, message = "Mô tả không được vượt quá 5000 ký tự")
    private String description;

    private TaskPriority priority;

    private Instant startDate;

    private Instant dueDate;

    @PositiveOrZero(message = "Thời gian ước tính phải lớn hơn hoặc bằng 0")
    private Double estimatedHours;

    @NotBlank(message = "Người thực hiện chính không được để trống")
    private String assigneeId;

    private List<String> collaboratorIds;

    private List<String> observerIds;

    private Long jobDescriptionTaskId;

    private Long jobDescriptionTaskItemId;

    private Long departmentId;

    private Long kpiGroupId;

    private KpiCycleType kpiCycleType;

    private KpiTargetDirection kpiTargetDirection;

    private KpiOutputType kpiOutputType;

    private Double kpiTargetValue;

    private String kpiTargetUnit;
}
