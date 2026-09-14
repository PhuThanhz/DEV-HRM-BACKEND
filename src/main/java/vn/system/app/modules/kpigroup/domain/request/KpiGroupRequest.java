package vn.system.app.modules.kpigroup.domain.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import vn.system.app.modules.kpigroup.domain.enums.KpiOutputType;
import vn.system.app.modules.kpigroup.domain.enums.KpiTargetDirection;
import vn.system.app.modules.kpigroup.domain.enums.KpiType;

@Getter
@Setter
public class KpiGroupRequest {

    @NotBlank(message = "Tên nhóm KPI không được để trống")
    @Size(max = 200, message = "Tên nhóm KPI không được vượt quá 200 ký tự")
    private String name;

    private String description;

    @NotNull(message = "Công ty không được để trống")
    private Long companyId;

    @NotNull(message = "Loại KPI không được để trống")
    private KpiType kpiType;

    private KpiTargetDirection targetDirection;

    private KpiOutputType outputType;

    private Double scoringScale;

    private String defaultUnit;

    /** Chỉ áp dụng khi kpiType = VOLUME_PRICING */
    @Valid
    private List<KpiPriceTierRequest> priceTiers;

    private boolean active = true;
}
