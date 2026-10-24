package com.bookagent.backend.service;

import com.bookagent.backend.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Lightweight, permission-filtered metadata for every knowledge scope picker. */
@Service @RequiredArgsConstructor
public class KnowledgeCatalogue {
    private final JdbcTemplate jdbc;
    private final KnowledgeAccess access;

    public record Choice(long id, String title, Long categoryId, String sourceMode,
                         long revision, long indexedRevision, String indexStatus) {}
    public record Folder(long id, String name, Long parentId) {}
    public record Catalogue(List<Choice> books, List<Folder> categories) {}

    public Catalogue list(boolean publicOnly) {
        if (!publicOnly) access.active();
        var books = jdbc.query("SELECT b.id,b.title,p.category_id,COALESCE(s.mode,'ONLINE') AS source_mode,b.revision,b.indexed_revision,b.index_status " +
            "FROM book b JOIN book_owner o ON o.book_id=b.id LEFT JOIN book_publication p ON p.book_id=b.id " +
            "LEFT JOIN book_source s ON s.book_id=b.id WHERE b.deleted=0 AND " + access.visibleSql(publicOnly) + " ORDER BY b.id DESC LIMIT 5001",
            (rs, row) -> new Choice(rs.getLong("id"), rs.getString("title"), rs.getObject("category_id", Long.class),
                rs.getString("source_mode"), rs.getLong("revision"), rs.getLong("indexed_revision"), rs.getString("index_status")));
        if (books.size() > 5000) throw new BusinessException(400, "当前知识范围超过 5000 本 Book，请先整理知识范围");

        Map<Long, Folder> folders = new LinkedHashMap<>();
        Set<Long> visible = new HashSet<>();
        jdbc.query("SELECT c.id,COALESCE(co.label,c.name) AS label,c.parent_id,co.user_id FROM category c LEFT JOIN category_owner co ON co.category_id=c.id ORDER BY c.sort_order,c.id", rs -> {
            long id = rs.getLong("id");
            folders.put(id, new Folder(id, rs.getString("label"), rs.getObject("parent_id", Long.class)));
            Long owner = rs.getObject("user_id", Long.class);
            if (!publicOnly && (owner == null || owner == access.actor())) visible.add(id);
        });
        books.stream().map(Choice::categoryId).filter(Objects::nonNull).forEach(visible::add);
        // Shared Books may belong to another owner's category. Include their ancestors,
        // while never returning unrelated private folders or Book content.
        for (Long id : List.copyOf(visible)) {
            Set<Long> visited = new HashSet<>();
            Folder folder = folders.get(id);
            while (folder != null && folder.parentId() != null && visited.add(folder.id())) {
                visible.add(folder.parentId());
                folder = folders.get(folder.parentId());
            }
        }
        return new Catalogue(books, folders.values().stream().filter(folder -> visible.contains(folder.id())).toList());
    }
}
