package vn.system.app.modules.jobpositionchart.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.turkraft.springfilter.boot.Filter;

import vn.system.app.common.response.ResultPaginationDTO;
import vn.system.app.common.util.annotation.ApiMessage;
import vn.system.app.common.util.error.IdInvalidException;

import jakarta.validation.Valid;

import vn.system.app.modules.jobpositionchart.domain.JobPositionChart;
import vn.system.app.modules.jobpositionchart.domain.request.ReqCreateJobPositionChartDTO;
import vn.system.app.modules.jobpositionchart.domain.request.ReqUpdateJobPositionChartDTO;
import vn.system.app.modules.jobpositionchart.domain.response.ResJobPositionChartDTO;
import vn.system.app.modules.jobpositionchart.service.JobPositionChartService;

@RestController
@RequestMapping("/api/v1")
public class JobPositionChartController {

    private final JobPositionChartService chartService;

    public JobPositionChartController(JobPositionChartService chartService) {
        this.chartService = chartService;
    }

    /*
     * ==========================
     * CREATE CHART
     * ==========================
     */
    @PostMapping("/job-position-charts")
    @ApiMessage("Create job position chart")
    public ResponseEntity<ResJobPositionChartDTO> createChart(@Valid @RequestBody ReqCreateJobPositionChartDTO req) {

        JobPositionChart created = this.chartService.handleCreateChart(req);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(this.chartService.convertToDTO(created));
    }

    /*
     * ==========================
     * DELETE CHART
     * ==========================
     */
    @DeleteMapping("/job-position-charts/{id}")
    @ApiMessage("Delete job position chart")
    public ResponseEntity<Void> deleteChart(@PathVariable Long id) throws IdInvalidException {

        JobPositionChart current = this.chartService.fetchChartById(id);

        if (current == null) {
            throw new IdInvalidException("Chart với id = " + id + " không tồn tại");
        }

        this.chartService.handleDeleteChart(id);

        return ResponseEntity.ok(null);
    }

    /*
     * ==========================
     * GET CHART BY ID
     * ==========================
     */
    @GetMapping("/job-position-charts/{id}")
    @ApiMessage("Fetch chart by id")
    public ResponseEntity<ResJobPositionChartDTO> getChartById(@PathVariable Long id)
            throws IdInvalidException {

        JobPositionChart chart = this.chartService.fetchChartById(id);

        if (chart == null) {
            throw new IdInvalidException("Chart với id = " + id + " không tồn tại");
        }

        return ResponseEntity.ok(this.chartService.convertToDTO(chart));
    }

    /*
     * ==========================
     * GET ALL CHARTS
     * ==========================
     */
    @GetMapping("/job-position-charts")
    @ApiMessage("Fetch all job position charts")
    public ResponseEntity<ResultPaginationDTO> getAllCharts(
            @Filter Specification<JobPositionChart> spec,
            Pageable pageable) {

        return ResponseEntity.status(HttpStatus.OK)
                .body(this.chartService.fetchAllCharts(spec, pageable));
    }

    /*
     * ==========================
     * UPDATE CHART
     * ==========================
     */
    @PutMapping("/job-position-charts")
    @ApiMessage("Update job position chart")
    public ResponseEntity<ResJobPositionChartDTO> updateChart(@Valid @RequestBody ReqUpdateJobPositionChartDTO req)
            throws IdInvalidException {

        JobPositionChart updated = this.chartService.handleUpdateChart(req);

        if (updated == null) {
            throw new IdInvalidException("Chart với id = " + req.getId() + " không tồn tại");
        }

        return ResponseEntity.ok(this.chartService.convertToDTO(updated));
    }
}