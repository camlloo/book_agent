package com.bookagent.backend.service;

import com.bookagent.backend.common.exception.BusinessException;
import com.bookagent.backend.model.vo.BookVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Owns stable numeric handles for deprecated Article APIs without mixing ID domains. */
@Service @RequiredArgsConstructor
public class LegacyBookIds {
    // The reserved range remains exact in JavaScript and needs no alias table.
    public static final long BOOK_HANDLE_BASE = 1L << 52;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public long resolve(long handle) {
        if (handle >= BOOK_HANDLE_BASE) {
            long id = handle - BOOK_HANDLE_BASE;
            if (id < 1 || id >= BOOK_HANDLE_BASE) throw new BusinessException(400, "兼容 Book 标识无效");
            return id;
        }
        var mapped = jdbc.queryForList("SELECT book_id FROM legacy_article_book WHERE article_id=?", handle);
        return mapped.isEmpty() ? handle : ((Number) mapped.getFirst().get("book_id")).longValue();
    }

    public ObjectNode present(BookVO book) {
        var mapped = jdbc.queryForList("SELECT article_id FROM legacy_article_book WHERE book_id=?", book.id());
        Long legacy = mapped.isEmpty() ? null : ((Number) mapped.getFirst().get("article_id")).longValue();
        return present(book, legacy);
    }

    public Map<String, Object> presentPage(Map<String, Object> page) {
        @SuppressWarnings("unchecked") var books = (List<BookVO>) page.get("list");
        var mapping = new HashMap<Long, Long>();
        if (!books.isEmpty()) {
            Object[] ids = books.stream().map(BookVO::id).toArray();
            String slots = String.join(",", Collections.nCopies(ids.length, "?"));
            jdbc.query("SELECT book_id,article_id FROM legacy_article_book WHERE book_id IN (" + slots + ")",
                rs -> { mapping.put(rs.getLong(1), rs.getLong(2)); }, ids);
        }
        var result = new LinkedHashMap<>(page);
        result.put("list", books.stream().map(book -> present(book, mapping.get(book.id()))).toList());
        return result;
    }

    private ObjectNode present(BookVO book, Long legacy) {
        if (book.id() < 1 || book.id() >= BOOK_HANDLE_BASE)
            throw new BusinessException(400, "该 Book 请通过 /api/books 访问");
        ObjectNode result = json.valueToTree(book);
        result.put("id", legacy == null ? BOOK_HANDLE_BASE + book.id() : legacy);
        result.put("bookId", book.id());
        result.put("readerUrl", "/books/" + book.id());
        return result;
    }
}
