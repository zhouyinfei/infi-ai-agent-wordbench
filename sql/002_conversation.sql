-- 在001_user.sql创建的数据库内执行，增量添加会话表，不修改既有用户数据。
CREATE TABLE IF NOT EXISTS ai_conversation (
  id CHAR(36) NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  title VARCHAR(80) NOT NULL,
  memory_version BIGINT NOT NULL DEFAULT 0,
  memory_json MEDIUMTEXT NOT NULL,
  lock_token VARCHAR(36),
  lock_deadline TIMESTAMP NULL,
  gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_conversation_user_modified (user_id, gmt_modified),
  CONSTRAINT fk_conversation_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ai_conversation_turn (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  conversation_id CHAR(36) NOT NULL,
  mode VARCHAR(20) NOT NULL,
  goal VARCHAR(2000) NOT NULL,
  answer MEDIUMTEXT,
  status VARCHAR(20) NOT NULL,
  result_json MEDIUMTEXT NOT NULL,
  gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_turn_conversation_id (conversation_id, id),
  CONSTRAINT fk_turn_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
