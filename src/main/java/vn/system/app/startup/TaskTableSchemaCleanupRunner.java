package vn.system.app.startup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TaskTableSchemaCleanupRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Cột "logged_hours" từng tồn tại trên bảng tasks ở một bản entity cũ nhưng đã bị bỏ
     * (không còn field tương ứng trong Task.java). Với spring.jpa.hibernate.ddl-auto=update,
     * Hibernate chỉ thêm cột mới chứ không tự xoá cột thừa, nên DB dev tạo trước thời điểm
     * bỏ field này vẫn còn "logged_hours" NOT NULL không default -> mọi INSERT vào tasks
     * (tạo tác vụ mới) lỗi 500 "Field 'logged_hours' doesn't have a default value".
     * Runner này tự dọn cột thừa đó (nếu còn) mỗi lần khởi động, để không dev nào phải tự
     * chạy ALTER TABLE thủ công như đã xảy ra.
     */
    @Override
    public void run(ApplicationArguments args) {
        Integer columnExists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                        "WHERE table_schema = DATABASE() AND table_name = 'tasks' AND column_name = 'logged_hours'",
                Integer.class);

        if (columnExists != null && columnExists > 0) {
            log.warn("[Task Schema Cleanup] Phát hiện cột thừa 'tasks.logged_hours' (không có trong entity Task.java) -> đang xoá để tránh lỗi tạo tác vụ");
            jdbcTemplate.execute("ALTER TABLE tasks DROP COLUMN logged_hours");
            log.warn("[Task Schema Cleanup] Đã xoá cột 'tasks.logged_hours'");
        } else {
            log.info("[Task Schema Cleanup] Không có cột thừa cần dọn trên bảng tasks");
        }
    }
}
