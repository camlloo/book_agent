package com.bookagent.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component @Order(30) @RequiredArgsConstructor
public class KnowledgeMigration implements ApplicationRunner {
    private final JdbcTemplate jdbc;private final KnowledgeAccess access;private final BookVersions versions;
    @Override @Transactional public void run(ApplicationArguments args){
        long owner=access.defaultOwner();
        jdbc.update("INSERT INTO book_owner(book_id,user_id) SELECT id,? FROM book b WHERE NOT EXISTS(SELECT 1 FROM book_owner o WHERE o.book_id=b.id)",owner);
        jdbc.update("INSERT INTO task_owner(task_id,user_id) SELECT id,? FROM task t WHERE NOT EXISTS(SELECT 1 FROM task_owner o WHERE o.task_id=t.id)",owner);
        jdbc.update("INSERT INTO category_owner(category_id,user_id,label) SELECT id,?,name FROM category c WHERE NOT EXISTS(SELECT 1 FROM category_owner o WHERE o.category_id=c.id)",owner);
        jdbc.update("INSERT INTO tag_owner(tag_id,user_id,label) SELECT id,?,name FROM tag t WHERE NOT EXISTS(SELECT 1 FROM tag_owner o WHERE o.tag_id=t.id)",owner);
        for(long id:jdbc.query("SELECT id FROM book WHERE deleted=0 AND NOT EXISTS(SELECT 1 FROM book_revision v WHERE v.book_id=book.id)",(rs,n)->rs.getLong(1)))versions.capture(id,owner,"升级基线");
        // Jobs are bounded and idempotent; interrupted collection can be retried.
        jdbc.update("UPDATE knowledge_import SET status='FAILED',error='服务重启中断采集，请重试',run_id=NULL WHERE status='RUNNING'");
        jdbc.update("UPDATE knowledge_conversation SET run_id=NULL WHERE run_id IS NOT NULL");
        jdbc.update("UPDATE knowledge_message SET status='FAILED' WHERE status='RUNNING'");
    }
}
