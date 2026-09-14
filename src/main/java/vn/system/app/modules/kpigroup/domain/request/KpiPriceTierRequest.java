package vn.system.app.modules.kpigroup.domain.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class KpiPriceTierRequest {

    @NotNull(message = "Sản lượng bắt đầu bậc không được để trống")
    @PositiveOrZero(message = "Sản lượng bắt đầu bậc phải lớn hơn hoặc bằng 0")
    private Double fromQty;

    /** null = bậc cuối, không giới hạn trên */
    private Double toQty;

    @NotNull(message = "Đơn giá không được để trống")
    @PositiveOrZero(message = "Đơn giá phải lớn hơn hoặc bằng 0")
    private Double unitPrice;
}
