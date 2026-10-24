-- =============================================================
-- Book_Agent 数据库结构（库名 book_agent，字符集 utf8mb4）
-- 约定：每个步骤新增/变更的表都追加到本文件，保证任何时候可一键重建
-- 变更记录：2026-09-29 项目定位重定为纯博客，literature 表废弃
--           （如已创建请执行：DROP TABLE IF EXISTS literature;）
-- =============================================================

-- S1 用户表（管理员；预留读者角色）
CREATE TABLE IF NOT EXISTS `user` (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  username    VARCHAR(32)  NOT NULL COMMENT '登录名',
  password    VARCHAR(64)  NOT NULL COMMENT 'BCrypt 散列（60 字符）',
  nickname    VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '昵称（博客署名用）',
  role        VARCHAR(16)  NOT NULL DEFAULT 'READER' COMMENT '角色：ADMIN / READER(预留)',
  status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 0 禁用',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- S2 博客文章表（正文为 Markdown）
CREATE TABLE IF NOT EXISTS `article` (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  title        VARCHAR(128) NOT NULL COMMENT '标题',
  summary      VARCHAR(500) NULL COMMENT '摘要/导语（列表页展示，空则前端截取正文开头）',
  content      MEDIUMTEXT   NOT NULL COMMENT '正文 Markdown',
  category_id  BIGINT       NULL COMMENT '分类ID（S4 接入）',
  status       VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT草稿 / PUBLISHED已发布 / OFFLINE已下线',
  view_count   INT          NOT NULL DEFAULT 0 COMMENT '浏览量',
  published_at DATETIME     NULL COMMENT '首次发布时间（下线再发布不覆盖）',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_status (status),
  KEY idx_category (category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='博客文章表';

-- S4 / S6: additive migration; existing articles and users are preserved.
CREATE TABLE IF NOT EXISTS category (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(64) NOT NULL UNIQUE,
 parent_id BIGINT NULL,
 sort_order INT NOT NULL DEFAULT 0,
 KEY idx_parent(parent_id),
 CONSTRAINT fk_category_parent FOREIGN KEY(parent_id) REFERENCES category(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Book workspace: documents stay private; content and index revisions are explicit.
CREATE TABLE IF NOT EXISTS book (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 title VARCHAR(128) NOT NULL,
 summary VARCHAR(500) NULL,
 content MEDIUMTEXT NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1,
 indexed_revision BIGINT NOT NULL DEFAULT 0,
 index_status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
 index_error VARCHAR(1000) NULL,
 chunk_count INT NOT NULL DEFAULT 0,
 deleted TINYINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 KEY idx_book_active(deleted,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS book_file (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 book_id BIGINT NOT NULL,
 original_name VARCHAR(255) NOT NULL,
 extension VARCHAR(8) NOT NULL,
 object_key VARCHAR(255) NOT NULL UNIQUE,
 file_size BIGINT NOT NULL,
 sha256 VARCHAR(64) NOT NULL,
 deleted TINYINT NOT NULL DEFAULT 0,
 purged TINYINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_book_file(book_id,deleted),
 CONSTRAINT fk_file_book FOREIGN KEY(book_id) REFERENCES book(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS book_index_job (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 book_id BIGINT NOT NULL,
 revision BIGINT NOT NULL,
 operation VARCHAR(16) NOT NULL DEFAULT 'INDEX',
 status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
 run_id VARCHAR(36) NULL,
 error VARCHAR(1000) NULL,
 started_at DATETIME NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_book_revision(book_id,revision),
 KEY idx_book_job(status,id),
 CONSTRAINT fk_index_book FOREIGN KEY(book_id) REFERENCES book(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tag (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(64) NOT NULL UNIQUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS article_tag (
 article_id BIGINT NOT NULL,
 tag_id BIGINT NOT NULL,
 PRIMARY KEY(article_id,tag_id),
 KEY idx_tag_article(tag_id,article_id),
 CONSTRAINT fk_article_tag_article FOREIGN KEY(article_id) REFERENCES article(id) ON DELETE CASCADE,
 CONSTRAINT fk_article_tag_tag FOREIGN KEY(tag_id) REFERENCES tag(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS task (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 topic VARCHAR(128) NOT NULL,
 stage VARCHAR(16) NOT NULL,
 status VARCHAR(24) NOT NULL,
 request_json MEDIUMTEXT NOT NULL,
 output MEDIUMTEXT NULL,
 approved_content MEDIUMTEXT NULL,
 article_id BIGINT NULL,
 error VARCHAR(1000) NULL,
 run_id VARCHAR(36) NULL,
 started_at DATETIME NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 KEY idx_task_queue(status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS task_checkpoint (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 task_id BIGINT NOT NULL,
 stage VARCHAR(16) NOT NULL,
 run_id VARCHAR(36) NOT NULL,
 generated_content MEDIUMTEXT NOT NULL,
 approved_content MEDIUMTEXT NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_checkpoint_run(task_id,run_id),
 CONSTRAINT fk_checkpoint_task FOREIGN KEY(task_id) REFERENCES task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS task_book (
 task_id BIGINT NOT NULL PRIMARY KEY,
 knowledge_book_id BIGINT NOT NULL,
 result_book_id BIGINT NULL,
 CONSTRAINT fk_task_book_task FOREIGN KEY(task_id) REFERENCES task(id) ON DELETE CASCADE,
 CONSTRAINT fk_task_book_source FOREIGN KEY(knowledge_book_id) REFERENCES book(id),
 CONSTRAINT fk_task_book_result FOREIGN KEY(result_book_id) REFERENCES book(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Additive origin metadata; existing Books remain ONLINE without rewriting rows.
CREATE TABLE IF NOT EXISTS book_source (
 book_id BIGINT NOT NULL PRIMARY KEY,
 mode VARCHAR(16) NOT NULL,
 CONSTRAINT fk_book_source FOREIGN KEY(book_id) REFERENCES book(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS book_publication (
 book_id BIGINT NOT NULL PRIMARY KEY,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
 category_id BIGINT NULL,
 view_count INT NOT NULL DEFAULT 0,
 published_at DATETIME NULL,
 FOREIGN KEY(book_id) REFERENCES book(id),
 FOREIGN KEY(category_id) REFERENCES category(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS book_tag (
 book_id BIGINT NOT NULL,tag_id BIGINT NOT NULL,
 PRIMARY KEY(book_id,tag_id),
 FOREIGN KEY(book_id) REFERENCES book(id),
 FOREIGN KEY(tag_id) REFERENCES tag(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS legacy_article_book (
 article_id BIGINT NOT NULL PRIMARY KEY,book_id BIGINT NOT NULL UNIQUE,
 FOREIGN KEY(book_id) REFERENCES book(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS task_book_result (
 task_id BIGINT NOT NULL PRIMARY KEY,result_book_id BIGINT NOT NULL,
 FOREIGN KEY(task_id) REFERENCES task(id) ON DELETE CASCADE,
 FOREIGN KEY(result_book_id) REFERENCES book(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Phase 1-3: additive personal knowledge boundaries and recoverable history.
CREATE TABLE IF NOT EXISTS book_owner (
 book_id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL,
 FOREIGN KEY(book_id) REFERENCES book(id) ON DELETE CASCADE,
 FOREIGN KEY(user_id) REFERENCES `user`(id), KEY idx_book_owner(user_id,book_id)
);
CREATE TABLE IF NOT EXISTS book_share (
 book_id BIGINT NOT NULL,user_id BIGINT NOT NULL,access_role VARCHAR(16) NOT NULL,
 PRIMARY KEY(book_id,user_id), FOREIGN KEY(book_id) REFERENCES book(id) ON DELETE CASCADE,
 FOREIGN KEY(user_id) REFERENCES `user`(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS task_owner (
 task_id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL,
 FOREIGN KEY(task_id) REFERENCES task(id) ON DELETE CASCADE,
 FOREIGN KEY(user_id) REFERENCES `user`(id)
);
CREATE TABLE IF NOT EXISTS category_owner (
 category_id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL,label VARCHAR(64) NOT NULL,
 UNIQUE KEY uk_category_label(user_id,label),
 FOREIGN KEY(category_id) REFERENCES category(id) ON DELETE CASCADE,
 FOREIGN KEY(user_id) REFERENCES `user`(id)
);
CREATE TABLE IF NOT EXISTS tag_owner (
 tag_id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL,label VARCHAR(64) NOT NULL,
 UNIQUE KEY uk_tag_label(user_id,label),
 FOREIGN KEY(tag_id) REFERENCES tag(id) ON DELETE CASCADE,
 FOREIGN KEY(user_id) REFERENCES `user`(id)
);
CREATE TABLE IF NOT EXISTS book_revision (
 book_id BIGINT NOT NULL,revision BIGINT NOT NULL,snapshot_json MEDIUMTEXT NOT NULL,
 actor_id BIGINT NOT NULL,reason VARCHAR(64) NOT NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(book_id,revision),FOREIGN KEY(book_id) REFERENCES book(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS book_revision_file (
 book_id BIGINT NOT NULL,revision BIGINT NOT NULL,file_id BIGINT NOT NULL,
 PRIMARY KEY(book_id,revision,file_id),FOREIGN KEY(book_id,revision) REFERENCES book_revision(book_id,revision) ON DELETE CASCADE,
 FOREIGN KEY(file_id) REFERENCES book_file(id)
);
CREATE TABLE IF NOT EXISTS knowledge_origin (
 book_id BIGINT PRIMARY KEY,kind VARCHAR(16) NOT NULL,source_url VARCHAR(2000) NOT NULL,
 source_hash VARCHAR(64) NOT NULL,metadata_json MEDIUMTEXT NOT NULL,
 fetched_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(book_id) REFERENCES book(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS knowledge_import (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NOT NULL,kind VARCHAR(16) NOT NULL,
 source_url VARCHAR(2000) NOT NULL,book_id BIGINT NULL,expected_revision BIGINT NULL,
 options_json MEDIUMTEXT NOT NULL,organize TINYINT NOT NULL DEFAULT 1,status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
 run_id VARCHAR(36) NULL,error VARCHAR(1000) NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 KEY idx_import_queue(status,id),FOREIGN KEY(user_id) REFERENCES `user`(id)
);
CREATE TABLE IF NOT EXISTS knowledge_conversation (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NOT NULL,title VARCHAR(128) NOT NULL,
 scope_json MEDIUMTEXT NOT NULL,mode VARCHAR(16) NOT NULL DEFAULT 'qa',run_id VARCHAR(36) NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 FOREIGN KEY(user_id) REFERENCES `user`(id), KEY idx_conversation_owner(user_id,id)
);
CREATE TABLE IF NOT EXISTS knowledge_message (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,conversation_id BIGINT NOT NULL,role VARCHAR(16) NOT NULL,
 content MEDIUMTEXT NOT NULL,sources_json MEDIUMTEXT NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'SUCCEEDED',
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(conversation_id) REFERENCES knowledge_conversation(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS knowledge_conversation_memory (
 conversation_id BIGINT PRIMARY KEY,covered_message_id BIGINT NOT NULL DEFAULT 0,
 memory_json MEDIUMTEXT NOT NULL,basis_hash VARCHAR(64) NOT NULL,
 FOREIGN KEY(conversation_id) REFERENCES knowledge_conversation(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS book_index_profile(book_id BIGINT PRIMARY KEY,chunking VARCHAR(16) NOT NULL DEFAULT 'semantic',FOREIGN KEY(book_id) REFERENCES book(id) ON DELETE CASCADE);

-- Phase 4: user-owned assistant preferences and durable, reviewable agent plans.
CREATE TABLE IF NOT EXISTS assistant_memory(
 user_id BIGINT NOT NULL PRIMARY KEY,preferences TEXT NOT NULL,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 FOREIGN KEY(user_id) REFERENCES `user`(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS assistant_plan(
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NOT NULL,goal VARCHAR(1000) NOT NULL,
 scope_json MEDIUMTEXT NOT NULL,plan_json MEDIUMTEXT NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'PLANNED',
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 KEY idx_assistant_plan_owner(user_id,id),FOREIGN KEY(user_id) REFERENCES `user`(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS assistant_plan_step(
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,plan_id BIGINT NOT NULL,step_no INT NOT NULL,tool VARCHAR(16) NOT NULL,
 title VARCHAR(100) NOT NULL,instruction VARCHAR(500) NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'PLANNED',
 result_json MEDIUMTEXT NULL,error VARCHAR(1000) NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_assistant_plan_step(plan_id,step_no),KEY idx_assistant_plan_step(plan_id,status),
 FOREIGN KEY(plan_id) REFERENCES assistant_plan(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Phase 6: user-owned learning roadmaps, scheduled sessions and progress reports.
CREATE TABLE IF NOT EXISTS learning_plan(
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NOT NULL,goal VARCHAR(1000) NOT NULL,title VARCHAR(128) NOT NULL,
 current_level VARCHAR(40) NOT NULL,self_confidence INT NOT NULL,duration_weeks INT NOT NULL,sessions_per_week INT NOT NULL,
 minutes_per_session INT NOT NULL,start_date DATE NOT NULL,book_ids_json MEDIUMTEXT NOT NULL,scope_json MEDIUMTEXT NOT NULL,
 outline_json MEDIUMTEXT NOT NULL,report_json MEDIUMTEXT NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,KEY idx_learning_plan_owner(user_id,id),
 FOREIGN KEY(user_id) REFERENCES `user`(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS learning_session(
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,plan_id BIGINT NOT NULL,week_no INT NOT NULL,session_no INT NOT NULL,
 due_date DATE NOT NULL,focus VARCHAR(180) NOT NULL,title VARCHAR(120) NOT NULL,activity TEXT NOT NULL,expected_minutes INT NOT NULL,
 source_json MEDIUMTEXT NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'PLANNED',confidence INT NULL,reflection VARCHAR(2000) NULL,
 completed_at DATETIME NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_learning_session(plan_id,week_no,session_no),KEY idx_learning_session_plan(plan_id,status,due_date),
 FOREIGN KEY(plan_id) REFERENCES learning_plan(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

