package vn.system.app.modules.kpigroup.domain.response;

import java.time.Instant;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

import vn.system.app.modules.kpigroup.domain.enums.KpiOutputType;
import vn.system.app.modules.kpigroup.domain.enums.KpiTargetDirection;
import vn.system.app.modules.kpigroup.domain.enums.KpiType;

@Getter
@Setter
public class ResKpiGroupDTO {

    private Long id;
    private String name;
    private String description;
    private Long companyId;
    private String companyName;
    private KpiType kpiType;
    private KpiTargetDirection targetDirection;
    private KpiOutputType outputType;
    private Double scoringScale;
    private String defaultUnit;
    private List<ResKpiPriceTierDTO> priceTiers;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
