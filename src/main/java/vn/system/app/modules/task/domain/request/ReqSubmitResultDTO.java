package vn.system.app.modules.task.domain.request;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReqSubmitResultDTO {

    @NotBlank(message = "Báo cáo tóm tắt kết quả không được để trống")
    private String resultSummary;

    private String deliverables;

    private String issues;

    private String nextSteps;

    private List<AttachmentInput> attachments;

    /** Thực đạt (hoặc Thực đạt sản lượng) — chỉ có ý nghĩa khi task thuộc 1 Nhóm KPI. */
    private Double kpiActualValue;

    @Getter
    @Setter
    public static class AttachmentInput {
        @NotBlank(message = "Tên file không được để trống")
        private String fileName;
        private String folder = "documents";
        private Long fileSize = 0L;
    }
}
