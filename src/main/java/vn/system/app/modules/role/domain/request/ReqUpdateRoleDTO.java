package vn.system.app.modules.role.domain.request;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReqUpdateRoleDTO {

    private long id;

    @NotBlank(message = "name không được để trống")
    private String name;

    private String description;

    private boolean active;

    // null = không đổi danh sách quyền hiện có
    private List<Long> permissionIds;
}
