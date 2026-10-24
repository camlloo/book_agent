package com.bookagent.backend.common.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Idempotent runtime schema bootstrap for phase 6; existing learning data is preserved. */
@Component @Order(25) @RequiredArgsConstructor
public class LearningSchema implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    @Override public void run(ApplicationArguments args) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS learning_plan(" +
            "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NOT NULL,goal VARCHAR(1000) NOT NULL,title VARCHAR(128) NOT NULL," +
            "current_level VARCHAR(40) NOT NULL,self_confidence INT NOT NULL,duration_weeks INT NOT NULL,sessions_per_week INT NOT NULL," +
            "minutes_per_session INT NOT NULL,start_date DATE NOT NULL,book_ids_json MEDIUMTEXT NOT NULL,scope_json MEDIUMTEXT NOT NULL," +
            "outline_json MEDIUMTEXT NOT NULL,report_json MEDIUMTEXT NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP," +
            "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,KEY idx_learning_plan_owner(user_id,id)," +
            "FOREIGN KEY(user_id) REFERENCES `user`(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS learning_session(" +
            "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,plan_id BIGINT NOT NULL,week_no INT NOT NULL,session_no INT NOT NULL," +
            "due_date DATE NOT NULL,focus VARCHAR(180) NOT NULL,title VARCHAR(120) NOT NULL,activity TEXT NOT NULL," +
            "expected_minutes INT NOT NULL,source_json MEDIUMTEXT NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'PLANNED'," +
            "confidence INT NULL,reflection VARCHAR(2000) NULL,completed_at DATETIME NULL," +
            "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
            "UNIQUE KEY uk_learning_session(plan_id,week_no,session_no),KEY idx_learning_session_plan(plan_id,status,due_date)," +
            "FOREIGN KEY(plan_id) REFERENCES learning_plan(id) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }
}
