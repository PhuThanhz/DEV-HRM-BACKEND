package vn.system.app.modules.kpigroup.domain.response;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ResKpiPriceTierDTO {
    private Long id;
    private Integer tierOrder;
    private Double fromQty;
    private Double toQty;
    private Double unitPrice;
}
