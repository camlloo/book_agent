package com.bookagent.backend.service;

import com.bookagent.backend.common.exception.BusinessException;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Immutable snapshots; restoring is implemented by BookService as a new revision. */
@Service @RequiredArgsConstructor
public class BookVersions {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public void capture(long id,long actor,String reason) {
        var rows=jdbc.queryForList("SELECT id,title,summary,content,revision FROM book WHERE id=?",id);
        if(rows.isEmpty())return;
        long rev=((Number)rows.getFirst().get("revision")).longValue();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM book_revision WHERE book_id=? AND revision=?",Long.class,id,rev)>0)return;
        Map<String,Object> value=new LinkedHashMap<>(rows.getFirst());
        value.put("categoryId",jdbc.queryForObject("SELECT category_id FROM book_publication WHERE book_id=?",Long.class,id));
        value.put("tagIds",jdbc.query("SELECT tag_id FROM book_tag WHERE book_id=?",(rs,n)->rs.getLong(1),id));
        value.put("files",jdbc.query("SELECT id FROM book_file WHERE book_id=? AND deleted=0",(rs,n)->rs.getLong(1),id));
        try {jdbc.update("INSERT INTO book_revision(book_id,revision,snapshot_json,actor_id,reason) VALUES(?,?,?,?,?)",id,rev,json.writeValueAsString(value),actor,reason);}
        catch(java.io.IOException e){throw new IllegalStateException(e);}
        for(var file:(List<?>)value.get("files"))jdbc.update("INSERT INTO book_revision_file(book_id,revision,file_id) VALUES(?,?,?)",id,rev,file);
    }
    public JsonNode snapshot(long id,long revision) {
        var rows=jdbc.queryForList("SELECT snapshot_json FROM book_revision WHERE book_id=? AND revision=?",id,revision);
        if(rows.isEmpty())throw new BusinessException(404,"历史版本不存在");
        try{return json.readTree((String)rows.getFirst().get("snapshot_json"));}catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    public List<Map<String,Object>> list(long id){return jdbc.queryForList("SELECT v.revision,v.reason,v.created_at AS createdAt,u.nickname AS author FROM book_revision v LEFT JOIN `user` u ON u.id=v.actor_id WHERE v.book_id=? ORDER BY revision DESC",id);}
}
