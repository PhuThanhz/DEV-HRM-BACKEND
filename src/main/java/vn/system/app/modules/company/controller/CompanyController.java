package vn.system.app.modules.company.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.turkraft.springfilter.boot.Filter;

import jakarta.validation.Valid;
import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.ScopeSpec; // ← THÊM
import vn.system.app.common.util.annotation.ApiMessage;
import vn.system.app.modules.company.domain.Company;
import vn.system.app.modules.company.domain.request.ReqCreateCompanyDTO;
import vn.system.app.modules.company.domain.request.ReqUpdateCompanyDTO;
import vn.system.app.modules.company.domain.response.ResCompanyDTO;
import vn.system.app.modules.company.domain.response.ResCreateCompanyDTO;
import vn.system.app.modules.company.domain.response.ResUpdateCompanyDTO;
import vn.system.app.modules.company.service.CompanyService;

@RestController
@RequestMapping("/api/v1")
public class CompanyController {

    private final CompanyService companyService;

    public CompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    /* ================= CREATE ================= */

    @PostMapping("/companies")
    @ApiMessage("Create a new company")
    public ResponseEntity<ResCreateCompanyDTO> createCompany(
            @Valid @RequestBody ReqCreateCompanyDTO req) {

        Company company = companyService.convertCreateReqToEntity(req);
        Company savedCompany = companyService.handleCreateCompany(company);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(companyService.convertToResCreateCompanyDTO(savedCompany));
    }

    /* ================= GET ONE ================= */

    @GetMapping("/companies/{id}")
    @ApiMessage("Fetch company by id")
    public ResponseEntity<ResCompanyDTO> getCompanyById(
            @PathVariable("id") long id) {

        Company company = companyService.fetchEntityByIdWithScopeCheck(id);
        return ResponseEntity.ok(
                companyService.convertToResCompanyDTO(company));
    }

    /* ================= GET ALL ================= */

    @GetMapping("/companies")
    @ApiMessage("Fetch all companies")
    public ResponseEntity<ResultPaginationDTO> getAllCompanies(
            @Filter Specification<Company> spec,
            Pageable pageable) {

        spec = spec.and(ScopeSpec.byCompanyScope("id")); // ← THÊM

        return ResponseEntity.ok(
                companyService.fetchAllCompany(spec, pageable));
    }

    /* ================= UPDATE ================= */

    @PutMapping("/companies")
    @ApiMessage("Update a company")
    public ResponseEntity<ResUpdateCompanyDTO> updateCompany(
            @Valid @RequestBody ReqUpdateCompanyDTO req) {

        Company updatedCompany = companyService.handleUpdateCompany(req);

        return ResponseEntity.ok(
                companyService.convertToResUpdateCompanyDTO(updatedCompany));
    }

    /* ================= INACTIVE (SOFT DELETE) ================= */

    @PutMapping("/companies/{id}/inactive")
    @ApiMessage("Inactive a company")
    public ResponseEntity<Void> inactiveCompany(
            @PathVariable("id") long id) {

        companyService.handleInactiveCompany(id);
        return ResponseEntity.ok().build();
    }

    /* ================= ACTIVE ================= */

    @PutMapping("/companies/{id}/active")
    @ApiMessage("Active a company")
    public ResponseEntity<Void> activeCompany(
            @PathVariable("id") long id) {

        companyService.handleActiveCompany(id);
        return ResponseEntity.ok().build();
    }
}