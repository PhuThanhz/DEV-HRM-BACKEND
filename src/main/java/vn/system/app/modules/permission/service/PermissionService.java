package vn.system.app.modules.permission.service;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.modules.permission.domain.Permission;
import vn.system.app.modules.permission.domain.request.ReqCreatePermissionDTO;
import vn.system.app.modules.permission.domain.request.ReqUpdatePermissionDTO;
import vn.system.app.modules.permission.repository.PermissionRepository;

@Service
public class PermissionService {

    private final PermissionRepository permissionRepository;

    public PermissionService(PermissionRepository permissionRepository) {
        this.permissionRepository = permissionRepository;
    }

    public boolean isPermissionExist(String module, String apiPath, String method) {
        return permissionRepository.existsByModuleAndApiPathAndMethod(module, apiPath, method);
    }

    public Permission fetchById(long id) {
        Optional<Permission> permissionOptional = this.permissionRepository.findById(id);
        if (permissionOptional.isPresent())
            return permissionOptional.get();
        return null;
    }

    // Nhận DTO thay vì entity — tránh mass-assignment id (client không thể tự chọn
    // id để merge đè lên permission có sẵn khi tạo mới)
    public Permission create(ReqCreatePermissionDTO req) {
        Permission p = new Permission();
        p.setName(req.getName());
        p.setApiPath(req.getApiPath());
        p.setMethod(req.getMethod());
        p.setModule(req.getModule());
        return this.permissionRepository.save(p);
    }

    public Permission update(ReqUpdatePermissionDTO req) {
        Permission permissionDB = this.fetchById(req.getId());
        if (permissionDB != null) {
            permissionDB.setName(req.getName());
            permissionDB.setApiPath(req.getApiPath());
            permissionDB.setMethod(req.getMethod());
            permissionDB.setModule(req.getModule());

            // update
            permissionDB = this.permissionRepository.save(permissionDB);
            return permissionDB;
        }
        return null;
    }

    public void delete(long id) {
        // delete permission_role
        Optional<Permission> permissionOptional = this.permissionRepository.findById(id);
        Permission currentPermission = permissionOptional.get();
        currentPermission.getRoles().forEach(role -> role.getPermissions().remove(currentPermission));

        // delete permission
        this.permissionRepository.delete(currentPermission);
    }

    public ResultPaginationDTO getPermissions(Specification<Permission> spec, Pageable pageable) {
        Page<Permission> pPermissions = this.permissionRepository.findAll(spec, pageable);
        ResultPaginationDTO rs = new ResultPaginationDTO();
        ResultPaginationDTO.Meta mt = new ResultPaginationDTO.Meta();

        mt.setPage(pageable.getPageNumber() + 1);
        mt.setPageSize(pageable.getPageSize());

        mt.setPages(pPermissions.getTotalPages());
        mt.setTotal(pPermissions.getTotalElements());

        rs.setMeta(mt);
        rs.setResult(pPermissions.getContent());
        return rs;
    }

    public boolean isSameName(long id, String name) {
        Permission permissionDB = this.fetchById(id);
        if (permissionDB != null) {
            if (permissionDB.getName().equals(name))
                return true;
        }
        return false;
    }
}
