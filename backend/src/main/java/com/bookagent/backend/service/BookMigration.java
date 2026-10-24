package com.bookagent.backend.service;
import com.bookagent.backend.model.dto.BookSaveDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Component @Order(20) @RequiredArgsConstructor
public class BookMigration implements ApplicationRunner {
    private final JdbcTemplate jdbc;private final BookService books;
    @Override @Transactional public void run(ApplicationArguments args){migrate();}
    @Transactional public int migrate(){
        jdbc.update("INSERT INTO book_publication(book_id) SELECT b.id FROM book b WHERE NOT EXISTS(SELECT 1 FROM book_publication p WHERE p.book_id=b.id)");
        var originals=jdbc.queryForList("SELECT * FROM article WHERE NOT EXISTS(SELECT 1 FROM legacy_article_book m WHERE m.article_id=article.id) ORDER BY id FOR UPDATE");
        for(var row:originals){
            long articleId=((Number)row.get("id")).longValue();
            var tags=jdbc.query("SELECT tag_id FROM article_tag WHERE article_id=? ORDER BY tag_id",(rs,n)->rs.getLong(1),articleId);
            Long category=row.get("category_id")==null?null:((Number)row.get("category_id")).longValue();
            long bookId=books.create(new BookSaveDTO((String)row.get("title"),(String)row.get("summary"),(String)row.get("content"),null,category,tags)).id();
            jdbc.update("UPDATE book SET created_at=?,updated_at=? WHERE id=?",row.get("created_at"),row.get("updated_at"),bookId);
            jdbc.update("UPDATE book_publication SET status=?,view_count=?,published_at=? WHERE book_id=?",row.get("status"),row.get("view_count"),row.get("published_at"),bookId);
            jdbc.update("INSERT INTO legacy_article_book(article_id,book_id) VALUES(?,?)",articleId,bookId);
        }
        return originals.size();
    }
}
