package vn.system.app.modules.organization.service;

import java.util.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.department.service.DepartmentService;
import vn.system.app.modules.departmentjobtitle.domain.response.ResDepartmentJobTitleDTO;
import vn.system.app.modules.departmentjobtitle.service.DepartmentJobTitleQueryService;
import vn.system.app.modules.organization.domain.response.ResOrganizationChartDTO;
import vn.system.app.modules.organization.domain.response.ResOrganizationChartEdgeDTO;
import vn.system.app.modules.organization.domain.response.ResOrganizationChartNodeDTO;

@Service
public class DepartmentOrganizationService {

    private final DepartmentJobTitleQueryService queryService;
    private final DepartmentService departmentService;

    public DepartmentOrganizationService(
            DepartmentJobTitleQueryService queryService,
            DepartmentService departmentService) {

        this.queryService = queryService;
        this.departmentService = departmentService;
    }

    /*
     * =====================================================
     * BUILD SƠ ĐỒ TỔ CHỨC PHÒNG BAN
     * =====================================================
     */
    @Transactional(readOnly = true)
    public ResOrganizationChartDTO buildDepartmentOrganization(Long departmentId) {

        departmentService.checkDepartmentScope(departmentService.fetchEntityById(departmentId));

        List<ResDepartmentJobTitleDTO> list = queryService.fetchDeptAndSectionJobTitles(departmentId);

        if (list == null || list.isEmpty()) {
            throw new IdInvalidException("Phòng ban chưa có chức danh.");
        }

        // =====================================================
        // SORT THEO BAND + LEVEL (rank nhỏ = cấp cao hơn)
        // =====================================================
        list.sort(
                Comparator
                        .comparing((ResDepartmentJobTitleDTO dto) -> dto.getJobTitle().getBandOrder() != null
                                ? dto.getJobTitle().getBandOrder()
                                : Integer.MAX_VALUE)
                        .thenComparing(dto -> dto.getJobTitle().getLevelNumber() != null
                                ? dto.getJobTitle().getLevelNumber()
                                : Integer.MAX_VALUE));

        List<ResOrganizationChartNodeDTO> nodes = new ArrayList<>();
        List<ResOrganizationChartEdgeDTO> edges = new ArrayList<>();

        Map<Long, String> nodeMap = new HashMap<>();

        // =====================================================
        // BUILD NODES
        // =====================================================
        for (ResDepartmentJobTitleDTO dto : list) {

            String nodeId = "node-" + dto.getJobTitle().getId();

            ResOrganizationChartNodeDTO node = new ResOrganizationChartNodeDTO();

            node.setId(nodeId);
            node.setJobTitleId(dto.getJobTitle().getId());
            node.setNameVi(dto.getJobTitle().getNameVi());
            node.setLevelCode(dto.getJobTitle().getPositionCode());
            node.setBandOrder(dto.getJobTitle().getBandOrder());
            node.setLevelNumber(dto.getJobTitle().getLevelNumber());

            nodes.add(node);
            nodeMap.put(dto.getJobTitle().getId(), nodeId);
        }

        // =====================================================
        // BUILD EDGES (tự động xác định cha gần nhất)
        // =====================================================
        for (int i = 0; i < list.size(); i++) {

            ResDepartmentJobTitleDTO current = list.get(i);
            Integer currentRank = calculateRank(current);

            for (int j = i - 1; j >= 0; j--) {

                ResDepartmentJobTitleDTO candidate = list.get(j);
                Integer candidateRank = calculateRank(candidate);

                if (candidateRank < currentRank) {

                    ResOrganizationChartEdgeDTO edge = new ResOrganizationChartEdgeDTO();

                    edge.setSource(
                            nodeMap.get(candidate.getJobTitle().getId()));
                    edge.setTarget(
                            nodeMap.get(current.getJobTitle().getId()));

                    edges.add(edge);
                    break;
                }
            }
        }

        ResOrganizationChartDTO result = new ResOrganizationChartDTO();

        result.setNodes(nodes);
        result.setEdges(edges);

        return result;
    }

    /*
     * =====================================================
     * CALCULATE RANK
     *
     * rank = bandOrder * 1000 + levelNumber
     *
     * rank nhỏ hơn = cấp cao hơn
     * =====================================================
     */
    private Integer calculateRank(
            ResDepartmentJobTitleDTO dto) {

        Integer bandOrder = dto.getJobTitle().getBandOrder();
        Integer level = dto.getJobTitle().getLevelNumber();

        if (bandOrder == null || level == null) {
            return Integer.MAX_VALUE;
        }

        return bandOrder * 1000 + level;
    }
}