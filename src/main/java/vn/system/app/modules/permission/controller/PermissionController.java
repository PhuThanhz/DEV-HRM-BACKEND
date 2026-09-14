package vn.system.app.modules.permission.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.turkraft.springfilter.boot.Filter;
import jakarta.validation.Valid;
import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.annotation.ApiMessage;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.permission.domain.Permission;
import vn.system.app.modules.permission.domain.request.ReqCreatePermissionDTO;
import vn.system.app.modules.permission.domain.request.ReqUpdatePermissionDTO;
import vn.system.app.modules.permission.service.PermissionService;

@RestController
@RequestMapping("/api/v1")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @PostMapping("/permissions")
    @ApiMessage("Create a permission")
    public ResponseEntity<Permission> create(@Valid @RequestBody ReqCreatePermissionDTO req) throws IdInvalidException {
        // check exist
        if (this.permissionService.isPermissionExist(req.getModule(), req.getApiPath(), req.getMethod())) {
            throw new IdInvalidException("Permission đã tồn tại.");
        }

        // create new permission
        return ResponseEntity.status(HttpStatus.CREATED).body(this.permissionService.create(req));
    }

    @PutMapping("/permissions")
    @ApiMessage("Update a permission")
    public ResponseEntity<Permission> update(@Valid @RequestBody ReqUpdatePermissionDTO req) throws IdInvalidException {
        // check exist by id
        if (this.permissionService.fetchById(req.getId()) == null) {
            throw new IdInvalidException("Permission với id = " + req.getId() + " không tồn tại.");
        }

        // check exist by module, apiPath and method
        if (this.permissionService.isPermissionExist(req.getModule(), req.getApiPath(), req.getMethod())) {
            // check name
            if (this.permissionService.isSameName(req.getId(), req.getName())) {
                throw new IdInvalidException("Permission đã tồn tại.");
            }
        }

        // update permission
        return ResponseEntity.ok().body(this.permissionService.update(req));
    }

    @DeleteMapping("/permissions/{id}")
    @ApiMessage("delete a permission")
    public ResponseEntity<Void> delete(@PathVariable("id") long id) throws IdInvalidException {
        // check exist by id
        if (this.permissionService.fetchById(id) == null) {
            throw new IdInvalidException("Permission với id = " + id + " không tồn tại.");
        }
        this.permissionService.delete(id);
        return ResponseEntity.ok().body(null);
    }

    @GetMapping("/permissions")
    @ApiMessage("Fetch permissions")
    public ResponseEntity<ResultPaginationDTO> getPermissions(
            @Filter Specification<Permission> spec, Pageable pageable) {

        return ResponseEntity.ok(this.permissionService.getPermissions(spec, pageable));
    }
}
