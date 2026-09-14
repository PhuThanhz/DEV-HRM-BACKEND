package vn.system.app.modules.jobpositionnode.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.jobpositionchart.domain.JobPositionChart;
import vn.system.app.modules.jobpositionchart.repository.JobPositionChartRepository;
import vn.system.app.modules.jobpositionchart.service.JobPositionChartService;
import vn.system.app.modules.jd.jobdescription.domain.JobDescription;
import vn.system.app.modules.jd.jobdescription.repository.JobDescriptionRepository;
import vn.system.app.modules.jobpositionnode.domain.JobPositionNode;
import vn.system.app.modules.jobpositionnode.domain.request.ReqCreateNode;
import vn.system.app.modules.jobpositionnode.domain.request.ReqCreateNodeTree;
import vn.system.app.modules.jobpositionnode.domain.request.ReqUpdateNode;
import vn.system.app.modules.jobpositionnode.domain.request.ReqUpdateNodePosition;
import vn.system.app.modules.jobpositionnode.domain.response.ResJobPositionNodeDTO;
import vn.system.app.modules.jobpositionnode.repository.JobPositionNodeRepository;

@Service
public class JobPositionNodeService {

    private final JobPositionNodeRepository nodeRepository;
    private final JobPositionChartRepository chartRepository;
    private final JobPositionChartService chartService;
    private final JobDescriptionRepository jobDescriptionRepository;

    public JobPositionNodeService(
            JobPositionNodeRepository nodeRepository,
            JobPositionChartRepository chartRepository,
            JobPositionChartService chartService,
            JobDescriptionRepository jobDescriptionRepository) {

        this.nodeRepository = nodeRepository;
        this.chartRepository = chartRepository;
        this.chartService = chartService;
        this.jobDescriptionRepository = jobDescriptionRepository;
    }

    /*
     * ==================================
     * VALIDATE SCOPE (IDOR Protection)
     * Dùng chung logic với JobPositionChartService để xử lý đúng cả chart
     * DEPARTMENT-type (trước đây chỉ check companyId nên bỏ sót case này)
     * ==================================
     */
    private void validateScope(JobPositionChart chart) {
        chartService.validateScope(chart);
    }

    /*
     * ==================================
     * CREATE NODE (single)
     * ==================================
     */
    @Transactional
    public JobPositionNode handleCreateNode(ReqCreateNode req) {

        JobPositionChart chart = this.chartRepository.findById(req.getChartId())
                .orElseThrow(() -> new IdInvalidException(
                        "Chart với id = " + req.getChartId() + " không tồn tại"));

        validateScope(chart);

        JobPositionNode node = new JobPositionNode();

        node.setName(req.getName());
        node.setLevel(req.getLevel());
        node.setHolderName(req.getHolderName());
        node.setIsGoal(req.getIsGoal() != null ? req.getIsGoal() : false);
        node.setParentId(req.getParentId());
        node.setChart(chart);
        node.setPosX(req.getPosX());
        node.setPosY(req.getPosY());

        // Gắn JD nếu có
        if (req.getJobDescriptionId() != null) {
            node.setJobDescription(
                    resolvePublishedJD(req.getJobDescriptionId(), chart));
        }

        return this.nodeRepository.save(node);
    }

    /*
     * ==================================
     * CREATE NODE TREE (bulk)
     * ==================================
     */
    @Transactional
    public List<JobPositionNode> handleCreateNodeTree(List<ReqCreateNodeTree> reqs) {
        if (reqs == null || reqs.isEmpty()) {
            return List.of();
        }
        // Validate scope of the chart from the first tree element
        Long chartId = reqs.get(0).getChartId();
        if (chartId != null) {
            JobPositionChart chart = this.chartRepository.findById(chartId)
                    .orElseThrow(() -> new IdInvalidException("Chart không tồn tại"));
            validateScope(chart);
        }

        return reqs.stream()
                .map(req -> createRecursive(req, req.getParentId()))
                .collect(Collectors.toList());
    }

    /*
     * ==================================
     * DELETE NODE
     * ==================================
     */
    @Transactional
    public void handleDeleteNode(Long id) {
        JobPositionNode node = this.fetchNodeById(id);
        if (node == null) {
            return;
        }

        validateScope(node.getChart());

        // 1. Lấy toàn bộ node của Chart này để xử lý đệ quy trên RAM
        List<JobPositionNode> allNodes = this.nodeRepository.findByChartId(node.getChart().getId());

        List<Long> idsToDelete = new ArrayList<>();
        idsToDelete.add(id);
        collectDescendants(id, allNodes, idsToDelete);

        // 2. Xóa hàng loạt bằng in-batch delete
        this.nodeRepository.deleteAllByIdInBatch(idsToDelete);
    }

    private void collectDescendants(Long parentId, List<JobPositionNode> allNodes, List<Long> idsToDelete) {
        for (JobPositionNode child : allNodes) {
            if (parentId.equals(child.getParentId())) {
                idsToDelete.add(child.getId());
                collectDescendants(child.getId(), allNodes, idsToDelete);
            }
        }
    }

    /*
     * ==================================
     * FIND NODE BY ID
     * ==================================
     */
    @Transactional(readOnly = true)
    public JobPositionNode fetchNodeById(Long id) {

        Optional<JobPositionNode> nodeOptional = this.nodeRepository.findById(id);

        return nodeOptional.orElse(null);
    }

    /*
     * ==================================
     * UPDATE NODE
     * ==================================
     */
    @Transactional
    public JobPositionNode handleUpdateNode(ReqUpdateNode req) {

        JobPositionNode currentNode = this.fetchNodeById(req.getId());

        if (currentNode == null) {
            return null;
        }

        validateScope(currentNode.getChart());

        if (req.getName() != null) {
            currentNode.setName(req.getName());
        }
        if (req.getLevel() != null) {
            currentNode.setLevel(req.getLevel());
        }
        if (req.getHolderName() != null) {
            currentNode.setHolderName(req.getHolderName());
        }
        if (req.getIsGoal() != null) {
            currentNode.setIsGoal(req.getIsGoal());
        }
        if (req.getParentId() != null) {
            // ⭐ Chống vòng lặp (Cycle Detection)
            if (req.getParentId().equals(currentNode.getId())) {
                throw new IdInvalidException("Không thể gán node cha là chính nó");
            }

            // ⭐ Tối ưu: Lấy toàn bộ node của Chart này để kiểm tra vòng lặp trên RAM
            List<JobPositionNode> allNodes = this.nodeRepository.findByChartId(currentNode.getChart().getId());

            // Nếu node cha mới là con cháu của node hiện tại -> báo lỗi
            if (isDescendant(currentNode.getId(), req.getParentId(), allNodes)) {
                throw new IdInvalidException("Không thể gán node cha là node con/cháu của chính nó (gây vòng lặp)");
            }

            currentNode.setParentId(req.getParentId());
        }
        if (req.getPosX() != null) {
            currentNode.setPosX(req.getPosX());
        }
        if (req.getPosY() != null) {
            currentNode.setPosY(req.getPosY());
        }

        // jobDescriptionId:
        // null → giữ nguyên
        // 0 → gỡ JD khỏi node
        // id > 0 → gắn JD mới (validate PUBLISHED + cùng công ty)
        if (req.getJobDescriptionId() != null) {
            if (req.getJobDescriptionId() == 0L) {
                currentNode.setJobDescription(null);
            } else {
                currentNode.setJobDescription(
                        resolvePublishedJD(req.getJobDescriptionId(), currentNode.getChart()));
            }
        }

        return this.nodeRepository.save(currentNode);
    }

    /*
     * ==================================
     * UPDATE NODE POSITIONS (bulk)
     * ==================================
     */
    @Transactional
    public void handleUpdateNodePositions(List<ReqUpdateNodePosition> reqs) {
        if (reqs == null || reqs.isEmpty()) {
            return;
        }

        // Validate scope of the first node's chart (they all belong to the same chart/scope)
        JobPositionNode firstNode = this.fetchNodeById(reqs.get(0).getId());
        if (firstNode != null) {
            validateScope(firstNode.getChart());
        }

        for (ReqUpdateNodePosition req : reqs) {
            JobPositionNode node = this.fetchNodeById(req.getId());
            if (node != null) {
                if (req.getPosX() != null) {
                    node.setPosX(req.getPosX());
                }
                if (req.getPosY() != null) {
                    node.setPosY(req.getPosY());
                }
                this.nodeRepository.save(node);
            }
        }
    }

    /*
     * ==================================
     * GET NODES BY CHART
     * ==================================
     */
    @Transactional(readOnly = true)
    public List<ResJobPositionNodeDTO> fetchNodesByChart(Long chartId) {

        List<JobPositionNode> nodes = this.nodeRepository.findByChartId(chartId);

        return nodes.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /*
     * ==================================
     * CONVERT DTO
     * ==================================
     */
    public ResJobPositionNodeDTO convertToDTO(JobPositionNode node) {

        ResJobPositionNodeDTO res = new ResJobPositionNodeDTO();

        res.setId(node.getId());
        res.setName(node.getName());
        res.setLevel(node.getLevel());
        res.setHolderName(node.getHolderName());
        res.setIsGoal(node.getIsGoal());
        res.setParentId(node.getParentId());
        res.setPosX(node.getPosX());
        res.setPosY(node.getPosY());

        if (node.getJobDescription() != null) {
            res.setJobDescriptionId(node.getJobDescription().getId());
            res.setJobDescriptionCode(node.getJobDescription().getCode());
            res.setJobDescriptionStatus(node.getJobDescription().getStatus());
        }

        return res;
    }

    /*
     * ==================================
     * PRIVATE HELPER — đệ quy tạo cây
     * ==================================
     */

    /**
     * Tạo node hiện tại, sau đó đệ quy tạo các node con.
     * parentId được truyền từ node cha vừa lưu xuống DB.
     */
    private JobPositionNode createRecursive(ReqCreateNodeTree req, Long parentId) {

        // Resolve chart — ưu tiên chartId của node, fallback lên parentId không có
        Long chartId = req.getChartId();
        if (chartId == null) {
            throw new IdInvalidException("chartId không được để trống");
        }

        JobPositionChart chart = this.chartRepository.findById(chartId)
                .orElseThrow(() -> new IdInvalidException(
                        "Chart với id = " + chartId + " không tồn tại"));

        // Build node
        JobPositionNode node = new JobPositionNode();
        node.setName(req.getName());
        node.setLevel(req.getLevel());
        node.setHolderName(req.getHolderName());
        node.setIsGoal(req.getIsGoal() != null ? req.getIsGoal() : false);
        node.setParentId(parentId); // ⭐ gán parentId từ node cha đã lưu
        node.setChart(chart);
        node.setPosX(req.getPosX());
        node.setPosY(req.getPosY());

        // Gắn JD nếu có
        if (req.getJobDescriptionId() != null) {
            node.setJobDescription(
                    resolvePublishedJD(req.getJobDescriptionId(), chart));
        }

        JobPositionNode saved = this.nodeRepository.save(node);

        // ⭐ Đệ quy tạo các node con, truyền id của node vừa lưu làm parentId
        if (req.getChildren() != null && !req.getChildren().isEmpty()) {
            for (ReqCreateNodeTree child : req.getChildren()) {
                // Kế thừa chartId nếu node con không tự khai báo
                if (child.getChartId() == null) {
                    child.setChartId(chartId);
                }
                createRecursive(child, saved.getId());
            }
        }

        return saved;
    }

    /**
     * Tìm JD theo id, kiểm tra:
     * 1. JD tồn tại
     * 2. JD đã PUBLISHED
     * 3. JD thuộc cùng công ty với chart
     */
    private JobDescription resolvePublishedJD(Long jdId, JobPositionChart chart) {

        JobDescription jd = this.jobDescriptionRepository.findById(jdId)
                .orElseThrow(() -> new IdInvalidException(
                        "JobDescription với id = " + jdId + " không tồn tại"));

        if (!"PUBLISHED".equals(jd.getStatus())) {
            throw new IdInvalidException(
                    "Chỉ được gắn JD đã ban hành (PUBLISHED)");
        }

        // Validate cùng công ty
        Long chartCompanyId = chart.getCompanyId();
        Long jdCompanyId = (jd.getCompany() != null) ? jd.getCompany().getId() : null;

        if (chartCompanyId == null || !chartCompanyId.equals(jdCompanyId)) {
            throw new IdInvalidException(
                    "JD không thuộc cùng công ty với sơ đồ, không thể gắn");
        }

        return jd;
    }

    /**
     * Kiểm tra xem targetId có phải là hậu duệ của parentId không (RAM Traversal)
     */
    private boolean isDescendant(Long parentId, Long targetId, List<JobPositionNode> allNodes) {
        for (JobPositionNode child : allNodes) {
            if (parentId.equals(child.getParentId())) {
                if (child.getId().equals(targetId)) {
                    return true;
                }
                if (isDescendant(child.getId(), targetId, allNodes)) {
                    return true;
                }
            }
        }
        return false;
    }
}