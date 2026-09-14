package vn.system.app.modules.kpigroup.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 1 bậc trong bảng đơn giá lũy tiến của 1 Nhóm KPI (loại VOLUME_PRICING).
 * VD: fromQty=0, toQty=100, unitPrice=10000 -> 100 sản phẩm đầu giá 10.000đ/sp
 *     fromQty=100, toQty=null, unitPrice=12000 -> từ sản phẩm thứ 101 trở đi giá 12.000đ/sp (bậc cuối, không giới hạn trên)
 */
@Entity
@Table(name = "kpi_price_tiers", indexes = {
        @Index(name = "idx_kpi_price_tiers_group_id", columnList = "kpi_group_id")
})
@Getter
@Setter
@JsonIgnoreProperties({ "hibernateLazyInitializer", "handler" })
public class KpiPriceTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "kpi_group_id", nullable = false)
    @JsonIgnoreProperties({ "company", "priceTiers" })
    private KpiGroup kpiGroup;

    @Column(name = "tier_order", nullable = false)
    private Integer tierOrder;

    @Column(name = "from_qty", nullable = false)
    private Double fromQty;

    /** null = bậc cuối, không giới hạn trên */
    @Column(name = "to_qty")
    private Double toQty;

    @Column(name = "unit_price", nullable = false)
    private Double unitPrice;
}
