package vn.system.app.modules.role.domain.request;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReqCreateRoleDTO {

    @NotBlank(message = "name không được để trống")
    private String name;

    private String description;

    private boolean active;

    private List<Long> permissionIds;
}
