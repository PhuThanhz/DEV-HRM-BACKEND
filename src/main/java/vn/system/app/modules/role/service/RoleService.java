package vn.system.app.modules.role.service;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.modules.permission.domain.Permission;
import vn.system.app.modules.permission.repository.PermissionRepository;
import vn.system.app.modules.role.domain.Role;
import vn.system.app.modules.role.domain.request.ReqCreateRoleDTO;
import vn.system.app.modules.role.domain.request.ReqUpdateRoleDTO;
import vn.system.app.modules.role.repository.RoleRepository;

@Service
public class RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;

    public RoleService(
            RoleRepository roleRepository,
            PermissionRepository permissionRepository) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
    }

    public boolean existByName(String name) {
        return this.roleRepository.existsByName(name);
    }

    // Nhận DTO thay vì entity — tránh mass-assignment id (client không thể tự
    // chọn id để merge đè lên role có sẵn khi tạo mới)
    public Role create(ReqCreateRoleDTO req) {
        Role r = new Role();
        r.setName(req.getName());
        r.setDescription(req.getDescription());
        r.setActive(req.isActive());
        if (req.getPermissionIds() != null) {
            r.setPermissions(this.permissionRepository.findByIdIn(req.getPermissionIds()));
        }
        return this.roleRepository.save(r);
    }

    public Role fetchById(long id) {
        Optional<Role> roleOptional = this.roleRepository.findById(id);
        if (roleOptional.isPresent())
            return roleOptional.get();
        return null;
    }

    public Role update(ReqUpdateRoleDTO req) {
        Role roleDB = this.fetchById(req.getId());
        if (roleDB == null) {
            return null;
        }

        roleDB.setName(req.getName());
        roleDB.setDescription(req.getDescription());
        roleDB.setActive(req.isActive());
        if (req.getPermissionIds() != null) {
            roleDB.setPermissions(this.permissionRepository.findByIdIn(req.getPermissionIds()));
        }
        roleDB = this.roleRepository.save(roleDB);
        return roleDB;
    }

    public void delete(long id) {
        this.roleRepository.deleteById(id);
    }

    public ResultPaginationDTO getRoles(Specification<Role> spec, Pageable pageable) {
        Page<Role> pRole = this.roleRepository.findAll(spec, pageable);
        ResultPaginationDTO rs = new ResultPaginationDTO();
        ResultPaginationDTO.Meta mt = new ResultPaginationDTO.Meta();

        mt.setPage(pageable.getPageNumber() + 1);
        mt.setPageSize(pageable.getPageSize());

        mt.setPages(pRole.getTotalPages());
        mt.setTotal(pRole.getTotalElements());

        rs.setMeta(mt);
        rs.setResult(pRole.getContent());
        return rs;
    }

    public Role findByName(String name) {
        Role role = roleRepository.findByName(name);
        if (role == null) {
            throw new RuntimeException("Role " + name + " không tồn tại");
        }
        return role;
    }
}
