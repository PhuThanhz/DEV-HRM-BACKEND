package vn.system.app.modules.jobpositionchart.domain.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReqCreateJobPositionChartDTO {

    @NotBlank(message = "name không được để trống")
    private String name;

    @NotBlank(message = "chartType không được để trống")
    private String chartType; // COMPANY / DEPARTMENT

    private Long companyId;

    private Long departmentId;
}
