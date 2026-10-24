package com.bookagent.backend.service;

import com.bookagent.backend.common.exception.BusinessException;
import com.bookagent.backend.model.dto.BookSaveDTO;
import com.bookagent.backend.model.vo.BookVO;
import com.bookagent.backend.model.vo.BookReadVO;
import com.bookagent.backend.model.vo.TagVO;
import com.bookagent.backend.model.query.BookQuery;
import com.fasterxml.jackson.databind.JsonNode;
import io.minio.*;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Semaphore;

@Service @RequiredArgsConstructor @Slf4j
public class BookService {
    public static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final TaxonomyService keys;
    private final MinioClient minio;
    private final AiClient ai;
    private final LegacyBookIds legacyIds;
    private final KnowledgeAccess access;
    private final BookVersions versions;
    private final Semaphore queries = new Semaphore(2);
    @Value("${minio.bucket}") private String bucket;

    @Transactional
    public BookVO create(BookSaveDTO dto) {
        keys.validate(dto.categoryId(),dto.tagIds());
        long id = keys.insert("INSERT INTO book(title,summary,content) VALUES(?,?,?)", dto.title().trim(), dto.summary(), dto.content());
        jdbc.update("INSERT INTO book_source(book_id,mode) VALUES(?,'ONLINE')", id);
        jdbc.update("INSERT INTO book_publication(book_id,category_id) VALUES(?,?)",id,dto.categoryId());
        replaceTags(id,dto.tagIds());
        access.own(id,access.actor());
        versions.capture(id,access.actor(),"创建");
        enqueue(id, 1, "INDEX");
        return detail(id);
    }

    public Map<String, Object> list(String keyword, int pageNum, int pageSize) {
        var q=new BookQuery();q.setKeyword(keyword);q.setPageNum(pageNum);q.setPageSize(pageSize);return list(q,false);
    }

    private static final String JOINS=" FROM book b LEFT JOIN book_source s ON s.book_id=b.id LEFT JOIN book_publication p ON p.book_id=b.id LEFT JOIN category c ON c.id=p.category_id LEFT JOIN category_owner co ON co.category_id=c.id LEFT JOIN book_owner o ON o.book_id=b.id LEFT JOIN knowledge_origin origin ON origin.book_id=b.id ";
    private String joins(){return JOINS+" LEFT JOIN book_share bs ON bs.book_id=b.id AND bs.user_id="+access.actor()+" ";}
    private static final String META=",COALESCE(s.mode,'ONLINE') AS source_mode,COALESCE(p.status,'DRAFT') AS status,p.category_id,COALESCE(co.label,c.name) AS category_name,COALESCE(p.view_count,0) AS view_count,p.published_at,o.user_id AS owner_id,bs.access_role AS share_role,COALESCE(origin.kind,s.mode,'ONLINE') AS origin_kind,origin.source_url";
    public Map<String,Object> list(BookQuery q,boolean publishedOnly) {
        int page=Math.max(1,q.getPageNum()==null?1:q.getPageNum()),size=Math.clamp(q.getPageSize()==null?10:q.getPageSize(),1,50);
        String status=publishedOnly?"PUBLISHED":q.getStatus();
        if(status!=null&&!status.isBlank()&&!List.of("DRAFT","PUBLISHED","OFFLINE").contains(status))throw new BusinessException(400,"Book 状态不合法");
        var args=new ArrayList<Object>();String where=" WHERE b.deleted=0 AND b.title LIKE ?";args.add("%"+Objects.toString(q.getKeyword(),"")+"%");
        if(!publishedOnly){where+=" AND "+access.visibleSql(false);}
        if(status!=null&&!status.isBlank()){where+=" AND COALESCE(p.status,'DRAFT')=?";args.add(status);}
        if(q.getCategoryId()!=null){where+=" AND p.category_id=?";args.add(q.getCategoryId());}
        if(q.getTagId()!=null){where+=" AND EXISTS(SELECT 1 FROM book_tag bt WHERE bt.book_id=b.id AND bt.tag_id=?)";args.add(q.getTagId());}
        if(q.getSourceKind()!=null&&!q.getSourceKind().isBlank()){if(!List.of("ONLINE","UPLOAD","WEB","REPO").contains(q.getSourceKind()))throw new BusinessException(400,"来源类型不合法");where+=" AND COALESCE(origin.kind,s.mode,'ONLINE')=?";args.add(q.getSourceKind());}
        try{
            if(q.getCreatedFrom()!=null&&!q.getCreatedFrom().isBlank()){where+=" AND b.created_at>=?";args.add(java.sql.Timestamp.valueOf(java.time.LocalDate.parse(q.getCreatedFrom()).atStartOfDay()));}
            if(q.getCreatedTo()!=null&&!q.getCreatedTo().isBlank()){where+=" AND b.created_at<?";args.add(java.sql.Timestamp.valueOf(java.time.LocalDate.parse(q.getCreatedTo()).plusDays(1).atStartOfDay()));}
        }catch(java.time.DateTimeException e){throw new BusinessException(400,"日期请使用 YYYY-MM-DD 格式");}
        long total=jdbc.queryForObject("SELECT COUNT(*)"+joins()+where,Long.class,args.toArray());args.add(size);args.add((long)(page-1)*size);
        var rows=jdbc.query("SELECT b.id,b.title,b.summary,b.revision,b.indexed_revision,b.index_status,b.index_error,b.chunk_count,b.created_at,b.updated_at"+META+joins()+where+" ORDER BY b.id DESC LIMIT ? OFFSET ?",(rs,n)->mapBook(rs,null,null),args.toArray());
        if(!rows.isEmpty()){
            var ids=rows.stream().map(BookVO::id).toArray();String placeholders=String.join(",",Collections.nCopies(ids.length,"?"));var byId=new HashMap<Long,List<TagVO>>();
            jdbc.query("SELECT bt.book_id,t.id,COALESCE(town.label,t.name) FROM book_tag bt JOIN tag t ON t.id=bt.tag_id LEFT JOIN tag_owner town ON town.tag_id=t.id WHERE bt.book_id IN ("+placeholders+") ORDER BY t.name",rs->{byId.computeIfAbsent(rs.getLong(1),key->new ArrayList<>()).add(new TagVO(rs.getLong(2),rs.getString(3)));},ids);
            rows=rows.stream().map(book->book.withTags(byId.getOrDefault(book.id(),List.of()))).toList();
        }
        return Map.of("list",rows,"total",total,"pageNum",page,"pageSize",size);
    }

    public BookVO detail(long id) {
        var rows = jdbc.query("SELECT b.*"+META+joins()+" WHERE b.id=? AND b.deleted=0", (rs, n) -> mapBook(rs, rs.getString("content"), files(id)), id);
        if (rows.isEmpty()) throw new BusinessException(404, "Book 不存在");
        return rows.getFirst();
    }

    private BookVO mapBook(ResultSet rs, String content, List<BookVO.FileVO> files) throws SQLException {
        return new BookVO(rs.getLong("id"), rs.getString("title"), rs.getString("summary"), content, rs.getString("source_mode"),
            rs.getLong("revision"), rs.getLong("indexed_revision"), rs.getString("index_status"), rs.getString("index_error"),
            rs.getInt("chunk_count"), rs.getTimestamp("created_at"), rs.getTimestamp("updated_at"), files,
            rs.getString("status"),rs.getObject("category_id",Long.class),rs.getString("category_name"),files==null?List.of():tags(rs.getLong("id")),rs.getInt("view_count"),rs.getTimestamp("published_at"),rs.getObject("owner_id",Long.class),access.role(rs.getLong("owner_id"),rs.getString("share_role")),rs.getString("origin_kind"),rs.getString("source_url"));
    }
    private List<TagVO> tags(long id){return jdbc.query("SELECT t.id,COALESCE(town.label,t.name) FROM book_tag bt JOIN tag t ON t.id=bt.tag_id LEFT JOIN tag_owner town ON town.tag_id=t.id WHERE bt.book_id=? ORDER BY t.name",(rs,n)->new TagVO(rs.getLong(1),rs.getString(2)),id);}
    private void replaceTags(long id,List<Long> tags){jdbc.update("DELETE FROM book_tag WHERE book_id=?",id);if(tags!=null)for(Long tag:new LinkedHashSet<>(tags))jdbc.update("INSERT INTO book_tag(book_id,tag_id) VALUES(?,?)",id,tag);}
    private void ensurePublication(long id){jdbc.update("INSERT INTO book_publication(book_id) SELECT ? WHERE NOT EXISTS(SELECT 1 FROM book_publication WHERE book_id=?)",id,id);}

    public List<BookVO.FileVO> files(long id) {
        return jdbc.query("SELECT id,original_name,extension,file_size,sha256,created_at FROM book_file WHERE book_id=? AND deleted=0 ORDER BY id",
            (rs, n) -> new BookVO.FileVO(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getTimestamp(6)), id);
    }

    private Map<String, Object> lock(long id) {
        var rows = jdbc.queryForList("SELECT * FROM book WHERE id=? AND deleted=0 FOR UPDATE", id);
        if (rows.isEmpty()) throw new BusinessException(404, "Book 不存在");
        return rows.getFirst();
    }

    @Transactional
    public BookVO update(long id, BookSaveDTO dto) {
        var previous = lock(id);
        versions.capture(id,access.actor(),"修改前");
        keys.validateFor(id,dto.categoryId(),dto.tagIds());
        if (dto.revision() == null || dto.revision() != ((Number)previous.get("revision")).longValue())
            throw new BusinessException(409, "Book 已被修改，请重新加载后保存；当前编辑内容仍保留在页面中");
        if ("UPLOAD".equals(sourceMode(id)) && !dto.content().isEmpty())
            throw new BusinessException(400, "文件型 Book 的内容来自原文件，请通过替换文件更新");
        var metadata=detail(id);
        boolean sameTaxonomy=Objects.equals(metadata.categoryId(),dto.categoryId())&&new HashSet<>(metadata.tags().stream().map(TagVO::id).toList()).equals(new HashSet<>(dto.tagIds()==null?List.of():dto.tagIds()));
        if (sameTaxonomy && Objects.equals(previous.get("title"), dto.title().trim()) && Objects.equals(previous.get("summary"), dto.summary()) && Objects.equals(previous.get("content"), dto.content())) return metadata;
        ensurePublication(id);jdbc.update("UPDATE book_publication SET category_id=? WHERE book_id=?",dto.categoryId(),id);replaceTags(id,dto.tagIds());
        jdbc.update("UPDATE book SET title=?,summary=?,content=? WHERE id=?", dto.title().trim(), dto.summary(), dto.content(), id);
        bump(id, "INDEX");
        return detail(id);
    }

    private void bump(long id, String operation) {
        jdbc.update("UPDATE book SET revision=revision+1,index_status=?,index_error=NULL WHERE id=?", "DELETE".equals(operation) ? "DELETING" : "QUEUED", id);
        enqueue(id, jdbc.queryForObject("SELECT revision FROM book WHERE id=?", Long.class, id), operation);
        if(!"DELETE".equals(operation))versions.capture(id,access.actor(),"内容修订");
    }

    private void enqueue(long id, long revision, String operation) {
        jdbc.update("UPDATE book_index_job SET status='SUPERSEDED' WHERE book_id=? AND status='QUEUED'", id);
        jdbc.update("INSERT INTO book_index_job(book_id,revision,operation) VALUES(?,?,?)", id, revision, operation);
    }

    @Transactional
    public void reindex(long id) { reindex(id,"semantic"); }
    @Transactional public void reindex(long id,String chunking){lock(id);if(!List.of("sentence","semantic").contains(chunking))throw new BusinessException(400,"切片策略不合法");jdbc.update("DELETE FROM book_index_profile WHERE book_id=?",id);jdbc.update("INSERT INTO book_index_profile(book_id,chunking) VALUES(?,?)",id,chunking);bump(id,"INDEX");}

    @Transactional
    public void delete(long id) {
        lock(id);
        jdbc.update("UPDATE book SET deleted=1 WHERE id=?", id);
        jdbc.update("UPDATE book_file SET deleted=1 WHERE book_id=?", id);
        bump(id, "DELETE");
    }

    private String sourceMode(long id) {
        var rows = jdbc.queryForList("SELECT mode FROM book_source WHERE book_id=?", id);
        return rows.isEmpty() ? "ONLINE" : (String)rows.getFirst().get("mode");
    }

    @Transactional(rollbackFor = Exception.class)
    public BookVO createUpload(String title, String summary, MultipartFile file) throws Exception {
        return createUpload(title,summary,file,null,List.of());
    }
    @Transactional(rollbackFor = Exception.class)
    public BookVO createUpload(String title, String summary, MultipartFile file,Long categoryId,List<Long> tagIds) throws Exception {
        var document = validateUpload(file);
        keys.validate(categoryId,tagIds);
        String name = title == null || title.isBlank() ? document.name().substring(0, document.name().lastIndexOf('.')) : title.trim();
        if (name.isBlank() || name.length() > 128) throw new BusinessException(400, "Book 标题不能为空且不能超过 128 字符");
        if (summary != null && summary.length() > 500) throw new BusinessException(400, "简介不能超过 500 字符");
        long id = keys.insert("INSERT INTO book(title,summary,content) VALUES(?,?,'')", name, summary);
        jdbc.update("INSERT INTO book_source(book_id,mode) VALUES(?,'UPLOAD')", id);
        jdbc.update("INSERT INTO book_publication(book_id,category_id) VALUES(?,?)",id,categoryId);replaceTags(id,tagIds);
        storeFile(id, document);
        access.own(id,access.actor());
        versions.capture(id,access.actor(),"创建");
        enqueue(id, 1, "INDEX");
        return detail(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public BookVO replaceUpload(long id, long revision, MultipartFile file) throws Exception {
        var previous = lock(id);
        versions.capture(id,access.actor(),"修改前");
        if (!"UPLOAD".equals(sourceMode(id))) throw new BusinessException(400, "在线编写的 Book 请直接编辑正文；上传文件请新建 Book");
        if (revision != ((Number)previous.get("revision")).longValue()) throw new BusinessException(409, "Book 已被修改，请重新加载后替换文件");
        var document = validateUpload(file);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM book_file WHERE book_id=? AND sha256=? AND deleted=0", Long.class, id, document.checksum()) > 0) return detail(id);
        jdbc.update("UPDATE book_file SET deleted=1 WHERE book_id=? AND deleted=0", id);
        storeFile(id, document);
        bump(id, "INDEX");
        return detail(id);
    }

    private record UploadDocument(String name, String extension, byte[] bytes, String checksum) {}
    private UploadDocument validateUpload(MultipartFile file) throws Exception {
        if (file.isEmpty()) throw new BusinessException(400, "请选择文件");
        if (file.getSize() > MAX_FILE_SIZE) throw new BusinessException(400, "Book 文件不能超过 20MB");
        String name = Objects.toString(file.getOriginalFilename(), "document").replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        if (name.length() > 255) throw new BusinessException(400, "文件名不能超过 255 字符");
        String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!List.of("pdf", "docx", "txt", "md").contains(extension)) throw new BusinessException(400, "Book 文件仅支持 PDF、DOCX、TXT 和 Markdown");
        byte[] bytes = file.getBytes();
        if (extension.equals("pdf") && (bytes.length < 5 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"))) throw new BusinessException(400, "PDF 文件内容与扩展名不符");
        if (extension.equals("docx") && (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K')) throw new BusinessException(400, "DOCX 文件内容与扩展名不符");
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new UploadDocument(name, extension, bytes, checksum);
    }

    private void storeFile(long id, UploadDocument document) throws Exception {
        String name=document.name(), extension=document.extension(), checksum=document.checksum(); byte[] bytes=document.bytes();
        String key = "books/" + id + "/" + UUID.randomUUID() + "." + extension;
        minio.putObject(PutObjectArgs.builder().bucket(bucket).object(key).contentType("application/octet-stream")
            .stream(new ByteArrayInputStream(bytes), bytes.length, -1).build());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) try { minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build()); } catch (Exception ignored) { log.warn("Failed to clean an uncommitted Book upload"); }
            }
        });
        keys.insert("INSERT INTO book_file(book_id,original_name,extension,object_key,file_size,sha256) VALUES(?,?,?,?,?,?)", id, name, extension, key, bytes.length, checksum);
    }

    @Transactional(rollbackFor=Exception.class)
    public long addArchivedFile(long id,MultipartFile file)throws Exception {
        var document=validateUpload(file);storeFile(id,document);
        long fileId=jdbc.queryForObject("SELECT MAX(id) FROM book_file WHERE book_id=?",Long.class,id);
        jdbc.update("UPDATE book_file SET deleted=1 WHERE id=?",fileId);return fileId;
    }

    @Transactional
    public BookVO removeFile(long id, long fileId) {
        lock(id);
        if ("UPLOAD".equals(sourceMode(id))) throw new BusinessException(400, "请替换原文件或删除整个 Book");
        if (jdbc.update("UPDATE book_file SET deleted=1 WHERE id=? AND book_id=? AND deleted=0", fileId, id) == 0) throw new BusinessException(404, "资料文件不存在");
        bump(id, "INDEX");
        return detail(id);
    }

    public String download(long id, long fileId) throws Exception { return download(id, fileId, ReadScope.ADMIN); }

    public String downloadPublished(long id, long fileId) throws Exception { return download(id, fileId, ReadScope.PUBLISHED); }

    private String download(long id, long fileId, ReadScope scope) throws Exception {
        var snapshot = accessSnapshot(id, scope);
        var rows = jdbc.queryForList("SELECT object_key,original_name,extension FROM book_file WHERE id=? AND book_id=? AND deleted=0", fileId, id);
        if (rows.isEmpty()) throw new BusinessException(404, "资料文件不存在");
        var row = rows.getFirst();
        String disposition = "attachment; filename=book-file." + row.get("extension") + "; filename*=UTF-8''" + URLEncoder.encode((String)row.get("original_name"), StandardCharsets.UTF_8).replace("+", "%20");
        String url = minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().bucket(bucket).object((String)row.get("object_key")).method(Method.GET).expiry(120).extraQueryParams(Map.of("response-content-disposition", disposition)).build());
        verifyCompletion(id, snapshot, scope, false);
        return url;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> manifest(long id, long revision) throws Exception {
        var row = jdbc.queryForList("SELECT * FROM book WHERE id=? AND revision=? AND deleted=0", id, revision);
        if (row.isEmpty()) throw new BusinessException(409, "索引版本已过期");
        var book = row.getFirst();
        List<Map<String, Object>> documents = new ArrayList<>();
        for (var file : jdbc.queryForList("SELECT * FROM book_file WHERE book_id=? AND deleted=0 ORDER BY id", id)) {
            String key = (String)file.get("object_key");
            String url = minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().bucket(bucket).object(key).method(Method.GET).expiry(900).build());
            documents.add(Map.of("id", file.get("id"), "name", file.get("original_name"), "extension", file.get("extension"), "size", file.get("file_size"), "sha256", file.get("sha256"), "url", url));
        }
        var profiles=jdbc.queryForList("SELECT chunking FROM book_index_profile WHERE book_id=?",id);
        return Map.of("book_id", id, "revision", revision, "title", book.get("title"), "content", book.get("content"), "files", documents,"chunking",profiles.isEmpty()?"semantic":profiles.getFirst().get("chunking"));
    }

    public JsonNode ask(long id, String question, int topK, List<com.bookagent.backend.model.dto.ChatMessageDTO> history) {
        return ask(id, question, topK, history, ReadScope.ADMIN);
    }

    public JsonNode askPublished(long id, String question, int topK, List<com.bookagent.backend.model.dto.ChatMessageDTO> history) {
        return ask(id, question, topK, history, ReadScope.PUBLISHED);
    }

    private JsonNode ask(long id, String question, int topK, List<com.bookagent.backend.model.dto.ChatMessageDTO> history, ReadScope scope) {
        var snapshot = accessSnapshot(id, scope);
        requireReady(snapshot);
        if (!queries.tryAcquire()) throw new BusinessException(429, "Book 问答繁忙，请稍后重试");
        try {
            JsonNode result = ai.call("/internal/books/ask", Map.of("book_id", id, "revision", snapshot.revision(), "question", question, "top_k", topK,"history",history==null?List.of():history));
            verifyCompletion(id, snapshot, scope, true);
            return result;
        } finally { queries.release(); }
    }

    public void requireReady(long id) {
        requireReady(accessSnapshot(id, ReadScope.ADMIN));
    }

    public BookReadVO read(long id) throws Exception { return read(id, ReadScope.ADMIN); }
    public BookReadVO readUnchecked(long id){try{return read(id);}catch(com.bookagent.backend.common.exception.BusinessException e){throw e;}catch(Exception e){throw new com.bookagent.backend.common.exception.BusinessException(503,"原文读取失败");}}

    private BookReadVO read(long id, ReadScope scope) throws Exception {
        var snapshot = accessSnapshot(id, scope);
        BookVO book = detail(id);
        if (book.revision() != snapshot.revision()) throw new BusinessException(409, "Book 内容已变化，请重新加载");
        JsonNode sections = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        if ("UPLOAD".equals(book.sourceMode())) {
            sections = ai.call("/internal/books/read", manifest(id, snapshot.revision())).path("sections");
            if (!sections.isArray()) throw new BusinessException(503, "AI 原文阅读结果格式不合法");
        }
        verifyCompletion(id, snapshot, scope, false);
        return new BookReadVO(book, sections);
    }

    private enum ReadScope { ADMIN, PUBLISHED }
    private record AccessSnapshot(long revision, long indexedRevision, String indexStatus, String status) {}

    private AccessSnapshot accessSnapshot(long id, ReadScope scope) {
        var rows = jdbc.query("SELECT b.revision,b.indexed_revision,b.index_status,COALESCE(p.status,'DRAFT') AS status FROM book b LEFT JOIN book_publication p ON p.book_id=b.id WHERE b.id=? AND b.deleted=0",
            (rs,n) -> new AccessSnapshot(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4)), id);
        if (rows.isEmpty() || scope == ReadScope.PUBLISHED && !"PUBLISHED".equals(rows.getFirst().status()))
            throw new BusinessException(404, "Book 不存在或未发布");
        return rows.getFirst();
    }

    private void requireReady(AccessSnapshot snapshot) {
        if (!"READY".equals(snapshot.indexStatus()) || snapshot.indexedRevision() != snapshot.revision())
            throw new BusinessException(409, "当前 Book 索引尚未就绪，请等待处理完成或重试索引");
    }

    private void verifyCompletion(long id, AccessSnapshot before, ReadScope scope, boolean ready) {
        if(scope==ReadScope.ADMIN && access.web())access.book(id,"READ");
        var current = accessSnapshot(id, scope);
        if (current.revision() != before.revision()) throw new BusinessException(409, "Book 内容在处理期间发生变化，请重新加载或提问");
        if (ready) requireReady(current);
    }

    public long legacyId(long id){return legacyIds.resolve(id);}
    public void requirePublished(long id){accessSnapshot(id, ReadScope.PUBLISHED);}
    public BookVO publishedDetail(long id) {
        var book = detail(id);
        if (!"PUBLISHED".equals(book.status())) throw new BusinessException(404, "Book 不存在或未发布");
        verifyCompletion(id, new AccessSnapshot(book.revision(),book.indexedRevision(),book.indexStatus(),book.status()), ReadScope.PUBLISHED, false);
        return book;
    }
    @Transactional public BookVO publish(long id){lock(id);var book=detail(id);if(book.content().isBlank()&&book.files().isEmpty())throw new BusinessException(400,"请先添加 Book 内容");if("UPLOAD".equals(book.sourceMode()))requireReady(id);ensurePublication(id);jdbc.update("UPDATE book_publication SET status='PUBLISHED',published_at=COALESCE(published_at,CURRENT_TIMESTAMP) WHERE book_id=?",id);return detail(id);}
    @Transactional public BookVO offline(long id){lock(id);ensurePublication(id);jdbc.update("UPDATE book_publication SET status='OFFLINE' WHERE book_id=?",id);return detail(id);}
    public BookReadVO readPublished(long id) throws Exception {
        if(jdbc.update("UPDATE book_publication p SET view_count=view_count+1 WHERE book_id=? AND status='PUBLISHED' AND EXISTS(SELECT 1 FROM book b WHERE b.id=p.book_id AND b.deleted=0)",id)==0)throw new BusinessException(404,"Book 不存在或未发布");
        return read(id, ReadScope.PUBLISHED);
    }

    @Transactional
    public void finishIndex(long jobId, String runId, int chunks, String error) {
        var rows = jdbc.queryForList("SELECT * FROM book_index_job WHERE id=? AND status='RUNNING' AND run_id=? FOR UPDATE", jobId, runId);
        if (rows.isEmpty()) return;
        var job = rows.getFirst(); long id = ((Number)job.get("book_id")).longValue(), revision = ((Number)job.get("revision")).longValue();
        boolean deleting = "DELETE".equals(job.get("operation"));
        int updated;
        if (error == null) updated = jdbc.update("UPDATE book SET indexed_revision=?,index_status=?,chunk_count=?,index_error=NULL,updated_at=updated_at WHERE id=? AND revision=?", revision, deleting ? "DELETED" : "READY", chunks, id, revision);
        else updated = jdbc.update("UPDATE book SET index_status='FAILED',index_error=?,updated_at=updated_at WHERE id=? AND revision=?", error.substring(0, Math.min(error.length(), 1000)), id, revision);
        jdbc.update("UPDATE book_index_job SET status=?,error=? WHERE id=?", updated == 0 ? "SUPERSEDED" : error == null ? "SUCCEEDED" : "FAILED", error, jobId);
    }

    @Transactional
    public BookVO restore(long id,long revision,long expectedRevision) {
        var current=lock(id);
        if(((Number)current.get("revision")).longValue()!=expectedRevision)throw new com.bookagent.backend.common.exception.BusinessException(409,"Book 已变化，请重新加载");
        versions.capture(id,access.actor(),"恢复前");
        var snap=versions.snapshot(id,revision);
        jdbc.update("UPDATE book SET title=?,summary=?,content=? WHERE id=?",snap.path("title").asText(),snap.path("summary").isNull()?null:snap.path("summary").asText(),snap.path("content").asText(),id);
        jdbc.update("UPDATE book_file SET deleted=1 WHERE book_id=?",id);
        for(var file:snap.path("files")){
            if(jdbc.update("UPDATE book_file SET deleted=0 WHERE id=? AND book_id=? AND purged=0",file.asLong(),id)==0)throw new com.bookagent.backend.common.exception.BusinessException(409,"该版本原文件不可用，无法恢复");
        }
        Long category=snap.path("categoryId").isNumber()?snap.path("categoryId").asLong():null;
        if(category!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM category WHERE id=?",Long.class,category)==0)category=null;
        jdbc.update("UPDATE book_publication SET category_id=? WHERE book_id=?",category,id);
        var tags=new java.util.ArrayList<Long>();for(var tag:snap.path("tagIds"))if(jdbc.queryForObject("SELECT COUNT(*) FROM tag WHERE id=?",Long.class,tag.asLong())>0)tags.add(tag.asLong());
        replaceTags(id,tags);bump(id,"INDEX");return detail(id);
    }

    public void cleanupFiles() {
        for (var file : jdbc.queryForList("SELECT id,object_key FROM book_file WHERE deleted=1 AND purged=0 AND (EXISTS(SELECT 1 FROM book b WHERE b.id=book_file.book_id AND b.deleted=1) OR NOT EXISTS(SELECT 1 FROM book_revision_file v WHERE v.file_id=book_file.id)) ORDER BY id LIMIT 20")) {
            try {
                minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object((String)file.get("object_key")).build());
                jdbc.update("UPDATE book_file SET purged=1 WHERE id=?", file.get("id"));
            } catch (Exception ignored) { log.warn("Book file cleanup will be retried: id={}", file.get("id")); }
        }
    }
}
