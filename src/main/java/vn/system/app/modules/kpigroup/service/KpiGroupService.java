package vn.system.app.modules.kpigroup.service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.error.IdInvalidException;
import vn.system.app.modules.company.domain.Company;
import vn.system.app.modules.company.repository.CompanyRepository;
import vn.system.app.modules.kpigroup.domain.KpiGroup;
import vn.system.app.modules.kpigroup.domain.KpiPriceTier;
import vn.system.app.modules.kpigroup.domain.enums.KpiType;
import vn.system.app.modules.kpigroup.domain.request.KpiGroupRequest;
import vn.system.app.modules.kpigroup.domain.request.KpiPriceTierRequest;
import vn.system.app.modules.kpigroup.domain.response.ResKpiGroupDTO;
import vn.system.app.modules.kpigroup.domain.response.ResKpiPriceTierDTO;
import vn.system.app.modules.kpigroup.repository.KpiGroupRepository;
import vn.system.app.modules.task.repository.TaskRepository;

@Service
public class KpiGroupService {

    private final KpiGroupRepository repository;
    private final CompanyRepository companyRepository;
    private final TaskRepository taskRepository;

    public KpiGroupService(
            KpiGroupRepository repository,
            CompanyRepository companyRepository,
            TaskRepository taskRepository) {
        this.repository = repository;
        this.companyRepository = companyRepository;
        this.taskRepository = taskRepository;
    }

    // =====================================================
    // CREATE
    // =====================================================
    @Transactional
    public ResKpiGroupDTO handleCreate(KpiGroupRequest req) {
        String name = req.getName().trim();

        if (repository.existsByNameAndCompanyId(name, req.getCompanyId())) {
            throw new IdInvalidException("Nhóm KPI đã tồn tại trong công ty này: " + name);
        }

        Company company = companyRepository.findById(req.getCompanyId())
                .orElseThrow(() -> new IdInvalidException("Công ty không tồn tại"));

        KpiGroup entity = new KpiGroup();
        entity.setName(name);
        entity.setDescription(req.getDescription());
        entity.setCompany(company);
        entity.setKpiType(req.getKpiType());
        if (req.getTargetDirection() != null) entity.setTargetDirection(req.getTargetDirection());
        if (req.getOutputType() != null) entity.setOutputType(req.getOutputType());
        if (req.getScoringScale() != null) entity.setScoringScale(req.getScoringScale());
        entity.setDefaultUnit(req.getDefaultUnit());
        entity.setActive(req.isActive());
        applyPriceTiers(entity, req);

        return convertToDTO(repository.save(entity));
    }

    // =====================================================
    // UPDATE
    // =====================================================
    @Transactional
    public ResKpiGroupDTO handleUpdate(Long id, KpiGroupRequest req) {
        KpiGroup current = fetchById(id);
        String name = req.getName().trim();

        if (repository.existsByNameAndCompanyIdAndIdNot(name, req.getCompanyId(), id)) {
            throw new IdInvalidException("Nhóm KPI đã tồn tại trong công ty này: " + name);
        }

        Company company = companyRepository.findById(req.getCompanyId())
                .orElseThrow(() -> new IdInvalidException("Công ty không tồn tại"));

        current.setName(name);
        current.setDescription(req.getDescription());
        current.setCompany(company);
        current.setKpiType(req.getKpiType());
        if (req.getTargetDirection() != null) current.setTargetDirection(req.getTargetDirection());
        if (req.getOutputType() != null) current.setOutputType(req.getOutputType());
        if (req.getScoringScale() != null) current.setScoringScale(req.getScoringScale());
        current.setDefaultUnit(req.getDefaultUnit());
        current.setActive(req.isActive());
        applyPriceTiers(current, req);

        return convertToDTO(repository.save(current));
    }

    // =====================================================
    // TOGGLE ACTIVE
    // =====================================================
    @Transactional
    public void handleToggleActive(Long id) {
        KpiGroup current = fetchById(id);

        if (current.isActive() && taskRepository.existsByKpiGroupId(id)) {
            throw new IdInvalidException("Không thể vô hiệu hóa nhóm KPI này vì đang có tác vụ sử dụng");
        }

        current.setActive(!current.isActive());
        repository.save(current);
    }

    // =====================================================
    // DELETE
    // =====================================================
    @Transactional
    public void handleDelete(Long id) {
        KpiGroup current = fetchById(id);

        if (taskRepository.existsByKpiGroupId(id)) {
            throw new IdInvalidException("Không thể xóa nhóm KPI này vì đang có tác vụ sử dụng");
        }

        repository.delete(current);
    }

    // =====================================================
    // FETCH ONE
    // =====================================================
    public KpiGroup fetchById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IdInvalidException("Nhóm KPI không tồn tại"));
    }

    // =====================================================
    // FETCH ALL (paginated)
    // =====================================================
    public ResultPaginationDTO fetchAll(Specification<KpiGroup> spec, Pageable pageable) {
        Page<KpiGroup> page = repository.findAll(spec, pageable);

        ResultPaginationDTO rs = new ResultPaginationDTO();
        ResultPaginationDTO.Meta meta = new ResultPaginationDTO.Meta();
        meta.setPage(pageable.getPageNumber() + 1);
        meta.setPageSize(pageable.getPageSize());
        meta.setPages(page.getTotalPages());
        meta.setTotal(page.getTotalElements());
        rs.setMeta(meta);
        rs.setResult(page.getContent().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList()));

        return rs;
    }

    // =====================================================
    // FETCH ALL ACTIVE BY COMPANY
    // =====================================================
    public List<ResKpiGroupDTO> fetchActiveByCompany(Long companyId) {
        if (companyId == null) {
            throw new IdInvalidException("Vui lòng chọn công ty trước khi xem danh sách nhóm KPI");
        }
        return repository.findByCompanyIdAndActiveTrue(companyId)
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    public ResKpiGroupDTO convertToDTO(KpiGroup e) {
        ResKpiGroupDTO dto = new ResKpiGroupDTO();
        dto.setId(e.getId());
        dto.setName(e.getName());
        dto.setDescription(e.getDescription());
        dto.setKpiType(e.getKpiType());
        dto.setTargetDirection(e.getTargetDirection());
        dto.setOutputType(e.getOutputType());
        dto.setScoringScale(e.getScoringScale());
        dto.setDefaultUnit(e.getDefaultUnit());
        dto.setActive(e.isActive());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        dto.setCreatedBy(e.getCreatedBy());
        dto.setUpdatedBy(e.getUpdatedBy());
        if (e.getCompany() != null) {
            dto.setCompanyId(e.getCompany().getId());
            dto.setCompanyName(e.getCompany().getName());
        }
        dto.setPriceTiers(e.getPriceTiers().stream().map(t -> {
            ResKpiPriceTierDTO tierDto = new ResKpiPriceTierDTO();
            tierDto.setId(t.getId());
            tierDto.setTierOrder(t.getTierOrder());
            tierDto.setFromQty(t.getFromQty());
            tierDto.setToQty(t.getToQty());
            tierDto.setUnitPrice(t.getUnitPrice());
            return tierDto;
        }).collect(Collectors.toList()));
        return dto;
    }

    /**
     * Thay toàn bộ bảng bậc thang của Nhóm KPI theo request (chỉ áp dụng cho loại
     * VOLUME_PRICING). Nhờ cascade=ALL + orphanRemoval=true trên KpiGroup.priceTiers,
     * xóa/thêm phần tử trong list rồi save entity cha là JPA tự đồng bộ DB.
     */
    private void applyPriceTiers(KpiGroup entity, KpiGroupRequest req) {
        entity.getPriceTiers().clear();

        if (entity.getKpiType() != KpiType.VOLUME_PRICING) {
            return;
        }

        List<KpiPriceTierRequest> tiers = req.getPriceTiers();
        if (tiers == null || tiers.isEmpty()) {
            throw new IdInvalidException("Nhóm KPI loại Sản lượng x Đơn giá phải có ít nhất 1 bậc thang đơn giá");
        }

        List<KpiPriceTierRequest> sorted = tiers.stream()
                .sorted(Comparator.comparing(KpiPriceTierRequest::getFromQty))
                .collect(Collectors.toList());

        for (int i = 0; i < sorted.size(); i++) {
            KpiPriceTierRequest tierReq = sorted.get(i);
            boolean isLast = i == sorted.size() - 1;

            if (!isLast && tierReq.getToQty() == null) {
                throw new IdInvalidException("Chỉ bậc cuối cùng mới được để trống 'Đến' (không giới hạn trên)");
            }
            if (tierReq.getToQty() != null && tierReq.getToQty() <= tierReq.getFromQty()) {
                throw new IdInvalidException("Giá trị 'Đến' phải lớn hơn 'Từ' ở mỗi bậc thang");
            }
            if (i > 0) {
                Double prevTo = sorted.get(i - 1).getToQty();
                if (prevTo == null || !prevTo.equals(tierReq.getFromQty())) {
                    throw new IdInvalidException("Các bậc thang phải liền kề nhau (giá trị 'Đến' của bậc trước = 'Từ' của bậc sau)");
                }
            }

            KpiPriceTier tier = new KpiPriceTier();
            tier.setKpiGroup(entity);
            tier.setTierOrder(i + 1);
            tier.setFromQty(tierReq.getFromQty());
            tier.setToQty(tierReq.getToQty());
            tier.setUnitPrice(tierReq.getUnitPrice());
            entity.getPriceTiers().add(tier);
        }
    }
}
