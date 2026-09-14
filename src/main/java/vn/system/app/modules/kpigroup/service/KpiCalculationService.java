package vn.system.app.modules.kpigroup.service;

import org.springframework.stereotype.Service;

import vn.system.app.modules.kpigroup.domain.KpiGroup;
import vn.system.app.modules.kpigroup.domain.KpiPriceTier;
import vn.system.app.modules.kpigroup.domain.enums.KpiTargetDirection;

/**
 * Tính kết quả KPI khi Assignee nộp "Thực đạt":
 * - RATIO:
 *   - INCREASING (Càng nhiều càng tốt): Tỉ lệ (%) = Thực đạt / Mục tiêu x 100
 *   - DECREASING (Càng ít càng tốt): Tỉ lệ (%) = max(0, (1 - Thực đạt / Mục tiêu) x 100)
 * - SCORE_POINTS: Điểm = (Ratio / 100) x Thang điểm
 * - VOLUME_PRICING: Tổng = tính lũy tiến từng bậc trên bảng đơn giá của Nhóm KPI.
 */
@Service
public class KpiCalculationService {

    public Double calculateRatio(Double target, Double actual) {
        return calculateRatio(target, actual, KpiTargetDirection.INCREASING);
    }

    public Double calculateRatio(Double target, Double actual, KpiTargetDirection direction) {
        if (target == null || target == 0 || actual == null) {
            return null;
        }
        KpiTargetDirection dir = direction != null ? direction : KpiTargetDirection.INCREASING;
        double ratio;
        if (dir == KpiTargetDirection.DECREASING) {
            ratio = Math.max(0.0, (1.0 - (actual / target)) * 100.0);
        } else {
            ratio = (actual / target) * 100.0;
        }
        return Math.round(ratio * 100.0) / 100.0;
    }

    public Double calculatePoints(Double ratio, Double scoringScale) {
        if (ratio == null) {
            return null;
        }
        double scale = (scoringScale != null && scoringScale > 0) ? scoringScale : 100.0;
        double points = (ratio / 100.0) * scale;
        return Math.round(points * 100.0) / 100.0;
    }

    public Double calculateTieredTotal(KpiGroup kpiGroup, Double actualVolume) {
        if (kpiGroup == null || actualVolume == null || actualVolume <= 0) {
            return null;
        }
        if (kpiGroup.getPriceTiers() == null || kpiGroup.getPriceTiers().isEmpty()) {
            return null;
        }

        double total = 0;
        for (KpiPriceTier tier : kpiGroup.getPriceTiers()) {
            double tierFrom = tier.getFromQty();
            if (actualVolume <= tierFrom) {
                break;
            }
            double tierTo = tier.getToQty() != null ? tier.getToQty() : actualVolume;
            double qtyInTier = Math.min(actualVolume, tierTo) - tierFrom;
            if (qtyInTier > 0) {
                total += qtyInTier * tier.getUnitPrice();
            }
        }
        return Math.round(total * 100.0) / 100.0;
    }
}
