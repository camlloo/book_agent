package com.bookagent.backend.service;

import com.bookagent.backend.common.exception.BusinessException;
import com.bookagent.backend.model.dto.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.minio.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Portable owner-only snapshots. Restore always creates new drafts in the current space. */
@Service @RequiredArgsConstructor
public class KnowledgeArchive {
    private final JdbcTemplate jdbc;private final BookService books;private final BookVersions versions;
    private final KnowledgeAccess access;private final TaxonomyService keys;private final MinioClient minio;private final ObjectMapper json;
    @Value("${minio.bucket}")private String bucket;
    public static final int MAX_ZIP=100*1024*1024,MAX_EXPANDED=256*1024*1024;
    public byte[] backup()throws Exception {
        var ids=jdbc.query("SELECT b.id FROM book b JOIN book_owner o ON o.book_id=b.id WHERE o.user_id=? AND b.deleted=0 ORDER BY b.id LIMIT 501",(rs,n)->rs.getLong(1),access.actor());
        if(ids.size()>500)throw new BusinessException(400,"单次备份最多 500 本，请先分批导出");
        long textBudget=jdbc.queryForObject("SELECT COALESCE(SUM(OCTET_LENGTH(v.snapshot_json)),0) FROM book_revision v JOIN book b ON b.id=v.book_id JOIN book_owner o ON o.book_id=b.id WHERE o.user_id=? AND b.deleted=0",Long.class,access.actor());
        textBudget+=jdbc.queryForObject("SELECT COALESCE(SUM(OCTET_LENGTH(b.content)),0) FROM book b JOIN book_owner o ON o.book_id=b.id WHERE o.user_id=? AND b.deleted=0",Long.class,access.actor());
        if(textBudget>MAX_EXPANDED)throw new BusinessException(400,"历史与正文超过备份限制，请分批导出");
        ObjectNode manifest=json.createObjectNode();manifest.put("format","BookAgentBackup");manifest.put("version",1);manifest.put("createdAt",java.time.Instant.now().toString());
        manifest.set("categories",json.valueToTree(keys.categories()));manifest.set("tags",json.valueToTree(keys.tags()));
        var records=manifest.putArray("books");int total=0;
        try(var output=new ByteArrayOutputStream();var zip=new ZipOutputStream(output,StandardCharsets.UTF_8)){
            for(long id:ids){
                var book=books.detail(id);ObjectNode record=json.valueToTree(book);records.add(record);
                var history=record.putArray("history");for(var v:jdbc.query("SELECT revision FROM book_revision WHERE book_id=? ORDER BY revision",(rs,n)->rs.getLong(1),id))history.add(versions.snapshot(id,v));
                record.set("origin",json.valueToTree(jdbc.queryForList("SELECT kind,source_url,source_hash,metadata_json FROM knowledge_origin WHERE book_id=?",id)));
                var objects=record.putArray("objects");
                for(var file:jdbc.queryForList("SELECT id,object_key,original_name,extension,file_size,sha256 FROM book_file WHERE book_id=? AND purged=0 AND (deleted=0 OR EXISTS(SELECT 1 FROM book_revision_file v WHERE v.file_id=book_file.id))",id)){
                    int size=((Number)file.get("file_size")).intValue();total+=size;if(total+textBudget>MAX_EXPANDED)throw new BusinessException(400,"备份原文件超过 256MB，请分批导出");
                    String entry="files/"+id+"/"+file.get("id")+"."+file.get("extension");
                    try(var stream=minio.getObject(GetObjectArgs.builder().bucket(bucket).object((String)file.get("object_key")).build())){
                        byte[] data=stream.readNBytes(size+1);if(data.length!=size)throw new BusinessException(409,"原文件大小已变化，请重试备份");
                        if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)).equals(file.get("sha256")))throw new BusinessException(409,"原文件校验失败");
                        zip.putNextEntry(new ZipEntry(entry));zip.write(data);zip.closeEntry();
                    }
                    ObjectNode f=objects.addObject();f.put("id",((Number)file.get("id")).longValue());f.put("name",(String)file.get("original_name"));f.put("entry",entry);f.put("sha256",(String)file.get("sha256"));f.put("size",size);
                }
                access.book(id,"OWNER");if(books.detail(id).revision()!=book.revision())throw new BusinessException(409,"备份期间内容变化，请重试");
            }
            byte[] metadata=json.writeValueAsBytes(manifest);if(metadata.length+total>MAX_EXPANDED)throw new BusinessException(400,"备份内容过大");
            zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(metadata);zip.closeEntry();zip.finish();
            if(output.size()>MAX_ZIP)throw new BusinessException(400,"压缩备份超过 100MB");return output.toByteArray();
        }
    }
    @Transactional(rollbackFor=Exception.class)
    public List<Long> restore(byte[] archive)throws Exception {
        if(archive.length>MAX_ZIP)throw new BusinessException(400,"备份文件最多 100MB");
        Map<String,byte[]> entries=new HashMap<>();int size=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(archive),StandardCharsets.UTF_8)){
            ZipEntry entry;while((entry=zip.getNextEntry())!=null){
                String name=entry.getName();if(entry.isDirectory())continue;
                if(entries.size()>6000||name.contains("..")||name.contains("\\")||name.startsWith("/")||entries.containsKey(name))throw new BusinessException(400,"备份结构不合法");
                int entryLimit=name.equals("manifest.json")?MAX_EXPANDED:(int)BookService.MAX_FILE_SIZE;
                byte[] data=zip.readNBytes(Math.min(MAX_EXPANDED-size,entryLimit)+1);size+=data.length;
                if(size>MAX_EXPANDED||data.length>entryLimit)throw new BusinessException(400,"备份解压内容超限");entries.put(name,data);
            }
        }
        if(!entries.containsKey("manifest.json"))throw new BusinessException(400,"请选择 Book_Agent 导出的备份");
        var manifest=json.readTree(entries.get("manifest.json"));
        if(!manifest.path("format").asText().equals("BookAgentBackup")||manifest.path("version").asInt()!=1||!manifest.path("books").isArray()||manifest.path("books").size()>500)throw new BusinessException(400,"备份版本或内容不支持");
        // Validate all objects before creating any business records.
        for(var b:manifest.path("books"))for(var file:b.path("objects")){
            var bytes=entries.get(file.path("entry").asText());if(bytes==null||bytes.length!=file.path("size").asLong()||bytes.length>BookService.MAX_FILE_SIZE||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(file.path("sha256").asText()))throw new BusinessException(400,"备份原文件缺失或校验失败");
        }
        Map<Long,Long> categoryMap=new HashMap<>(),tagMap=new HashMap<>();
        restoreCategories(manifest.path("categories"),null,categoryMap);
        for(var tag:manifest.path("tags")){String label=tag.path("name").asText();long id=findOrCreate("tag",label,null);tagMap.put(tag.path("id").asLong(),id);}
        List<Long> created=new ArrayList<>();
        for(var source:manifest.path("books")){
            String title=source.path("title").asText(),body=source.path("content").asText("");
            if(title.isBlank()||title.length()>128||body.length()>1_000_000)throw new BusinessException(400,"备份 Book 字段超限");
            Long category=categoryMap.get(source.path("categoryId").asLong());var tags=new ArrayList<Long>();for(var tag:source.path("tags"))if(tagMap.containsKey(tag.path("id").asLong()))tags.add(tagMap.get(tag.path("id").asLong()));
            var files=new HashMap<Long,Long>();long id;
            if(source.path("sourceMode").asText().equals("UPLOAD")){
                if(source.path("files").size()!=1)throw new BusinessException(400,"上传型 Book 原文件不完整");
                long old=source.path("files").get(0).path("id").asLong();var object=findObject(source,old);
                var book=books.createUpload(title,source.path("summary").asText(null),new ByteMultipart(object.path("name").asText(),entries.get(object.path("entry").asText())),category,tags);id=book.id();files.put(old,book.files().getFirst().id());
            }else id=books.create(new BookSaveDTO(title,source.path("summary").asText(null),body,null,category,tags)).id();
            for(var object:source.path("objects")){long old=object.path("id").asLong();if(!files.containsKey(old))files.put(old,books.addArchivedFile(id,new ByteMultipart(object.path("name").asText(),entries.get(object.path("entry").asText()))));}
            for(var active:source.path("files")){Long mapped=files.get(active.path("id").asLong());if(mapped==null)throw new BusinessException(400,"当前原文件缺失");jdbc.update("UPDATE book_file SET deleted=0 WHERE id=?",mapped);}
            // Preserve historical content and file references without importing old users or sharing grants.
            jdbc.update("DELETE FROM book_revision_file WHERE book_id=?",id);jdbc.update("DELETE FROM book_revision WHERE book_id=?",id);
            for(var original:source.path("history")){
                ObjectNode snap=original.deepCopy();long revision=snap.path("revision").asLong();if(revision<1)throw new BusinessException(400,"备份修订号不合法");
                snap.put("id",id);Long mapped=categoryMap.get(snap.path("categoryId").asLong());if(mapped==null)snap.putNull("categoryId");else snap.put("categoryId",mapped);
                var newTags=json.createArrayNode();for(var tag:snap.path("tagIds"))if(tagMap.containsKey(tag.asLong()))newTags.add(tagMap.get(tag.asLong()));snap.set("tagIds",newTags);
                var newFiles=json.createArrayNode();for(var file:snap.path("files")){if(!files.containsKey(file.asLong()))throw new BusinessException(400,"历史版本原文件缺失");newFiles.add(files.get(file.asLong()));}snap.set("files",newFiles);
                jdbc.update("INSERT INTO book_revision(book_id,revision,snapshot_json,actor_id,reason) VALUES(?,?,?,?,?)",id,revision,json.writeValueAsString(snap),access.actor(),"备份恢复");
                for(var file:newFiles)jdbc.update("INSERT INTO book_revision_file(book_id,revision,file_id) VALUES(?,?,?)",id,revision,file.asLong());
            }
            long revision=source.path("revision").asLong(1);if(revision<1)throw new BusinessException(400,"备份修订号不合法");
            jdbc.update("UPDATE book SET revision=?,indexed_revision=0,index_status='QUEUED' WHERE id=?",revision,id);
            if(revision!=1){jdbc.update("UPDATE book_index_job SET status='SUPERSEDED' WHERE book_id=?",id);jdbc.update("INSERT INTO book_index_job(book_id,revision,operation) VALUES(?,?,'INDEX')",id,revision);}
            versions.capture(id,access.actor(),"备份恢复");
            for(var origin:source.path("origin"))jdbc.update("INSERT INTO knowledge_origin(book_id,kind,source_url,source_hash,metadata_json) VALUES(?,?,?,?,?)",id,origin.path("kind").asText(),origin.path("source_url").asText(),origin.path("source_hash").asText(),origin.path("metadata_json").asText("{}"));
            created.add(id);
        }
        return created;
    }
    private JsonNode findObject(JsonNode book,long id){for(var file:book.path("objects"))if(file.path("id").asLong()==id)return file;throw new BusinessException(400,"备份原文件缺失");}
    private long findOrCreate(String kind,String label,Long parent){
        if(label.isBlank()||label.length()>64)throw new BusinessException(400,"备份分类或标签不合法");
        var ids=jdbc.query("SELECT "+kind+"_id FROM "+kind+"_owner WHERE user_id=? AND label=?",(rs,n)->rs.getLong(1),access.actor(),label);
        return ids.isEmpty()?(kind.equals("category")?keys.saveCategory(null,new TaxonomyDTO(label,parent,0)):keys.saveTag(null,new TaxonomyDTO(label,null,null))):ids.getFirst();
    }
    private void restoreCategories(JsonNode nodes,Long parent,Map<Long,Long> map){for(var category:nodes){long id=findOrCreate("category",category.path("name").asText(),parent);map.put(category.path("id").asLong(),id);restoreCategories(category.path("children"),id,map);}}
}
