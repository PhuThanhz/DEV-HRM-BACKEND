package vn.system.app.modules.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import vn.system.app.modules.task.domain.Task;
import vn.system.app.modules.task.domain.TaskSubmission;
import vn.system.app.modules.task.domain.response.ResTaskDTO;
import vn.system.app.modules.task.repository.TaskParticipantRepository;
import vn.system.app.modules.task.repository.TaskRepository;
import vn.system.app.modules.task.repository.TaskSubmissionRepository;

@ExtendWith(MockitoExtension.class)
class TaskSummaryReportServiceTest {

    @Mock
    private TaskRepository taskRepository;
    @Mock
    private TaskParticipantRepository participantRepository;
    @Mock
    private TaskSubmissionRepository submissionRepository;
    @Mock
    private TaskService taskService;

    private TaskSummaryReportService service;

    @BeforeEach
    void setUp() {
        service = new TaskSummaryReportService(
                taskRepository,
                participantRepository,
                submissionRepository,
                taskService);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void batchesLatestSubmissionsBeforeConvertingReportRows() {
        Task firstTask = task(1L);
        Task secondTask = task(2L);
        TaskSubmission latest = submission(firstTask, 2, "latest");
        TaskSubmission older = submission(firstTask, 1, "older");

        when(taskRepository.findAll(any(Specification.class)))
                .thenReturn(List.of(firstTask, secondTask));
        when(participantRepository.findByTaskIdIn(List.of(1L, 2L)))
                .thenReturn(List.of());
        when(submissionRepository.findByTaskIdInOrderByTaskIdAscSubmissionRoundDesc(List.of(1L, 2L)))
                .thenReturn(List.of(latest, older));
        when(taskService.convertToResDTO(any(Task.class), anyList(), any(Map.class)))
                .thenAnswer(invocation -> reportRow(invocation.<Task>getArgument(0).getId()));

        service.generateReport(
                Instant.now().minusSeconds(3600),
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        ArgumentCaptor<Map<Long, TaskSubmission>> submissionMapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(taskService, times(2)).convertToResDTO(
                any(Task.class),
                anyList(),
                submissionMapCaptor.capture());

        assertThat(submissionMapCaptor.getAllValues())
                .allSatisfy(map -> assertThat(map)
                        .containsEntry(1L, latest)
                        .doesNotContainValue(older));
        verify(submissionRepository, times(1))
                .findByTaskIdInOrderByTaskIdAscSubmissionRoundDesc(List.of(1L, 2L));
    }

    private Task task(Long id) {
        Task task = new Task();
        task.setId(id);
        return task;
    }

    private TaskSubmission submission(Task task, int round, String summary) {
        TaskSubmission submission = new TaskSubmission();
        submission.setTask(task);
        submission.setSubmissionRound(round);
        submission.setResultSummary(summary);
        return submission;
    }

    private ResTaskDTO reportRow(Long taskId) {
        ResTaskDTO dto = new ResTaskDTO();
        dto.setId(taskId);
        dto.setDepartmentId(10L);
        dto.setDepartmentName("Phòng thử nghiệm");
        dto.setCompanyName("Công ty thử nghiệm");
        dto.setAssigneeId("user-1");
        dto.setAssigneeName("Người thử nghiệm");
        dto.setIsOnTime(true);
        return dto;
    }
}
