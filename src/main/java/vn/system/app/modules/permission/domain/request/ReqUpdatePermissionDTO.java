package vn.system.app.modules.permission.domain.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReqUpdatePermissionDTO {

    private long id;

    @NotBlank(message = "name không được để trống")
    private String name;

    @NotBlank(message = "apiPath không được để trống")
    private String apiPath;

    @NotBlank(message = "method không được để trống")
    private String method;

    @NotBlank(message = "module không được để trống")
    private String module;
}
