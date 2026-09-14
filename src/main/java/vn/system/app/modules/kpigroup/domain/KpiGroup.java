package vn.system.app.modules.kpigroup.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import vn.system.app.common.util.SecurityUtil;
import vn.system.app.modules.company.domain.Company;
import vn.system.app.modules.kpigroup.domain.enums.KpiOutputType;
import vn.system.app.modules.kpigroup.domain.enums.KpiTargetDirection;
import vn.system.app.modules.kpigroup.domain.enums.KpiType;

@Entity
@Table(name = "kpi_groups", indexes = {
        @Index(name = "idx_kpi_groups_company_id", columnList = "company_id")
})
@Getter
@Setter
public class KpiGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    /**
     * Loại KPI cố định theo Nhóm KPI — quyết định các trường nhập liệu khi task thuộc
     * nhóm này (RATIO: Mục tiêu/Đơn vị đo/Thực đạt -> Tỉ lệ; VOLUME_PRICING: Mục tiêu
     * sản lượng/Thực đạt sản lượng + bảng đơn giá bậc thang bên dưới -> Tổng).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "kpi_type", nullable = false, length = 30)
    private KpiType kpiType = KpiType.RATIO;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_direction", length = 30)
    private KpiTargetDirection targetDirection = KpiTargetDirection.INCREASING;

    @Enumerated(EnumType.STRING)
    @Column(name = "output_type", length = 30)
    private KpiOutputType outputType = KpiOutputType.PERCENTAGE;

    @Column(name = "scoring_scale")
    private Double scoringScale = 100.0;

    @Column(name = "default_unit", length = 50)
    private String defaultUnit;

    @OneToMany(mappedBy = "kpiGroup", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("tierOrder ASC")
    @JsonIgnoreProperties({ "kpiGroup" })
    private List<KpiPriceTier> priceTiers = new ArrayList<>();

    @Column(name = "active", nullable = false)
    private boolean active = true;

    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;

    @PrePersist
    public void beforeCreate() {
        this.createdAt = Instant.now();
        this.createdBy = SecurityUtil.getCurrentUserLogin().orElse("");
        if (!this.active)
            this.active = true;
    }

    @PreUpdate
    public void beforeUpdate() {
        this.updatedAt = Instant.now();
        this.updatedBy = SecurityUtil.getCurrentUserLogin().orElse("");
    }
}
