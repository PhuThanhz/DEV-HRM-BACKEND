package vn.system.app.modules.jobpositionchart.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReqUpdateJobPositionChartDTO {

    @NotNull(message = "id không được để trống")
    private Long id;

    @NotBlank(message = "name không được để trống")
    private String name;

    @NotBlank(message = "chartType không được để trống")
    private String chartType; // COMPANY / DEPARTMENT

    private Long companyId;

    private Long departmentId;
}
