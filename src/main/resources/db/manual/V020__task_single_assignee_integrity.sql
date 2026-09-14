-- Enforce the task business rule that CREATOR and ASSIGNEE are single-valued
-- roles while COLLABORATOR and OBSERVER remain multi-valued.
--
-- Existing duplicate rows are recoverable from the backup table. The newest
-- participant row (highest id) is retained because that is the row most likely
-- to reflect the last assignment shown by the existing application.

CREATE TABLE IF NOT EXISTS task_participant_single_role_backup_20260904
LIKE task_participants;

DROP TEMPORARY TABLE IF EXISTS task_single_role_keep;

CREATE TEMPORARY TABLE task_single_role_keep AS
SELECT task_id, role, MAX(id) AS keep_id
FROM task_participants
WHERE role IN ('CREATOR', 'ASSIGNEE')
GROUP BY task_id, role
HAVING COUNT(*) > 1;

INSERT IGNORE INTO task_participant_single_role_backup_20260904
    (id, created_at, role, task_id, user_id)
SELECT tp.id, tp.created_at, tp.role, tp.task_id, tp.user_id
FROM task_participants tp
JOIN task_single_role_keep duplicate_role
  ON duplicate_role.task_id = tp.task_id
 AND duplicate_role.role = tp.role
WHERE tp.id <> duplicate_role.keep_id;

DELETE tp
FROM task_participants tp
JOIN task_single_role_keep duplicate_role
  ON duplicate_role.task_id = tp.task_id
 AND duplicate_role.role = tp.role
WHERE tp.id <> duplicate_role.keep_id;

DROP TEMPORARY TABLE task_single_role_keep;

SET @task_single_role_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'task_participants'
      AND column_name = 'single_role_task_id'
);

SET @task_single_role_column_sql = IF(
    @task_single_role_column_exists = 0,
    'ALTER TABLE task_participants ADD COLUMN single_role_task_id BIGINT GENERATED ALWAYS AS (CASE WHEN role IN (''CREATOR'', ''ASSIGNEE'') THEN task_id ELSE NULL END) STORED',
    'SELECT 1'
);

PREPARE task_single_role_column_stmt FROM @task_single_role_column_sql;
EXECUTE task_single_role_column_stmt;
DEALLOCATE PREPARE task_single_role_column_stmt;

SET @task_single_role_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'task_participants'
      AND index_name = 'uq_task_participant_single_role'
);

SET @task_single_role_index_sql = IF(
    @task_single_role_index_exists = 0,
    'CREATE UNIQUE INDEX uq_task_participant_single_role ON task_participants (single_role_task_id, role)',
    'SELECT 1'
);

PREPARE task_single_role_index_stmt FROM @task_single_role_index_sql;
EXECUTE task_single_role_index_stmt;
DEALLOCATE PREPARE task_single_role_index_stmt;
