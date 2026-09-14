package vn.system.app.modules.permissionassignment.service;

import java.util.*;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.department.domain.Department;
import vn.system.app.modules.departmentjobtitle.domain.DepartmentJobTitle;
import vn.system.app.modules.departmentjobtitle.service.DepartmentJobTitleService;
import vn.system.app.modules.permissionassignment.domain.PermissionAssignment;
import vn.system.app.modules.permissionassignment.domain.request.ReqAssignPermissionDTO;
import vn.system.app.modules.permissionassignment.domain.response.ResPermissionMatrixDTO;
import vn.system.app.modules.permissionassignment.repository.PermissionAssignmentRepository;
import vn.system.app.modules.permissioncontent.domain.PermissionContent;
import vn.system.app.modules.permissioncontent.service.PermissionContentService;
import vn.system.app.modules.processaction.domain.ProcessAction;
import vn.system.app.modules.processaction.service.ProcessActionService;

@Service
public class PermissionAssignmentService {

        private final PermissionAssignmentRepository repository;
        private final PermissionContentService contentService;
        private final DepartmentJobTitleService djtService;
        private final ProcessActionService processActionService;

        public PermissionAssignmentService(
                        PermissionAssignmentRepository repository,
                        PermissionContentService contentService,
                        DepartmentJobTitleService djtService,
                        ProcessActionService processActionService) {

                this.repository = repository;
                this.contentService = contentService;
                this.djtService = djtService;
                this.processActionService = processActionService;
        }

        /*
         * =====================================================
         * BUILD PERMISSION MATRIX — GET
         * =====================================================
         */
        @Transactional(readOnly = true)
        public ResPermissionMatrixDTO buildMatrix(Long contentId) {

                // 1️⃣ LOAD CONTENT
                PermissionContent content = contentService.fetchPermissionContentById(contentId);

                // 2️⃣ LẤY PHÒNG BAN TỪ CATEGORY
                Department department = content.getCategory().getDepartment();
                if (department == null) {
                        throw new IdInvalidException("Category chưa được gán phòng ban");
                }

                // 3️⃣ LOAD JOB TITLE CỦA PHÒNG BAN
                List<DepartmentJobTitle> departmentJobTitles = djtService.fetchByDepartment(department.getId());

                // 4️⃣ LOAD ASSIGNMENTS
                List<PermissionAssignment> assignments = repository.findByPermissionContent_Id(contentId);

                // MAP: DepartmentJobTitleId → PermissionAssignment
                Map<Long, PermissionAssignment> assignmentMap = assignments.stream()
                                .collect(Collectors.toMap(
                                                a -> a.getDepartmentJobTitle().getId(),
                                                a -> a));

                // 5️⃣ BUILD RESPONSE ROOT
                ResPermissionMatrixDTO res = new ResPermissionMatrixDTO();
                res.setContentId(content.getId());
                res.setContentName(content.getName());

                // CATEGORY INFO
                ResPermissionMatrixDTO.Category cat = new ResPermissionMatrixDTO.Category();
                cat.setId(content.getCategory().getId());
                cat.setCode(content.getCategory().getCode());
                cat.setName(content.getCategory().getName());
                res.setCategory(cat);

                // 6️⃣ BUILD DUY NHẤT 1 DEPARTMENT
                ResPermissionMatrixDTO.DepartmentMatrix dep = new ResPermissionMatrixDTO.DepartmentMatrix();

                dep.setDepartmentId(department.getId());
                dep.setDepartmentName(department.getName());
                dep.setJobTitles(new ArrayList<>());

                for (DepartmentJobTitle djt : departmentJobTitles) {

                        ResPermissionMatrixDTO.JobTitleMatrix jt = new ResPermissionMatrixDTO.JobTitleMatrix();

                        jt.setDepartmentJobTitleId(djt.getId());
                        jt.setJobTitleId(djt.getJobTitle().getId());
                        jt.setJobTitleName(djt.getJobTitle().getNameVi());

                        // SET ACTION IF EXISTS
                        PermissionAssignment pa = assignmentMap.get(djt.getId());
                        if (pa != null && pa.getProcessAction() != null) {
                                jt.setProcessActionId(pa.getProcessAction().getId());
                                jt.setActionCode(pa.getProcessAction().getCode());
                        }

                        dep.getJobTitles().add(jt);
                }

                res.setDepartments(List.of(dep));
                return res;
        }

        /*
         * =====================================================
         * ASSIGN — UPDATE PERMISSION
         * =====================================================
         */
        @Transactional
        public void assign(Long contentId, ReqAssignPermissionDTO req) {

                PermissionContent content = contentService.fetchPermissionContentById(contentId);

                DepartmentJobTitle djt = djtService.fetchDepartmentJobTitleById(req.getDepartmentJobTitleId());

                if (!content.getCategory().getDepartment().getId().equals(djt.getDepartment().getId())) {
                        throw new IdInvalidException("Chức danh không thuộc phòng ban của danh mục quyền này");
                }

                ProcessAction action = processActionService.fetchEntityById(req.getProcessActionId());

                // DELETE OLD
                repository.deleteByPermissionContent_IdAndDepartmentJobTitle_Id(
                                contentId,
                                djt.getId());

                // CREATE NEW
                PermissionAssignment pa = new PermissionAssignment();
                pa.setPermissionContent(content);
                pa.setDepartmentJobTitle(djt);
                pa.setProcessAction(action);

                repository.save(pa);
        }
}
