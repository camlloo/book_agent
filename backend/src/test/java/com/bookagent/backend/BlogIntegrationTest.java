package com.bookagent.backend;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class BlogIntegrationTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired BCryptPasswordEncoder encoder;
    @Autowired com.bookagent.backend.service.BookService books;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired com.bookagent.backend.service.BookMigration migration;
    @Autowired com.bookagent.backend.service.TaxonomyService keys;
    @org.springframework.test.context.bean.override.mockito.MockitoBean io.minio.MinioClient minio;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.bookagent.backend.service.AiClient ai;
    String token;
    @BeforeEach void login() throws Exception {token=call("POST","/api/auth/login",Map.of("username","admin","password","admin123"),null).path("data").path("tokenValue").asText();}
    JsonNode call(String method,String url,Object body,String auth) throws Exception {
        var request=request(org.springframework.http.HttpMethod.valueOf(method),url);
        if(auth!=null)request.header("Authorization",auth);
        if(body!=null)request.contentType("application/json").content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString());
    }
    JsonNode data(String method,String url,Object body) throws Exception {var result=call(method,url,body,token);assertEquals(200,result.path("code").asInt(),result.toString());return result.path("data");}
    @Test void knowledgeCatalogueContainsAllAccessibleMetadataAndOnlyVisibleFolderAncestors() throws Exception {
        String owner = member("catalogue_owner"), guest = member("catalogue_guest");
        long root = memberData("POST", "/api/categories", Map.of("name", "编程"), owner).asLong();
        long child = memberData("POST", "/api/categories", Map.of("name", "Python", "parentId", root), owner).asLong();
        long hidden = memberData("POST", "/api/categories", Map.of("name", "另一用户的私有分类"), guest).asLong();
        memberData("POST", "/api/books", Map.of("title", "另一用户的秘密", "content", "不得泄露", "categoryId", hidden), guest);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 55; i++) ids.add(memberData("POST", "/api/books", Map.of("title", "Python " + i,
            "content", "原文不应该进入选择器接口", "categoryId", child), owner).path("id").asLong());
        var catalogue = memberData("GET", "/api/knowledge/catalogue", null, owner);
        assertEquals(55, catalogue.path("books").size());
        assertFalse(catalogue.toString().contains("另一用户的私有分类"));
        assertFalse(catalogue.toString().contains("不得泄露"));
        assertFalse(catalogue.path("books").get(0).has("content"));
        assertEquals(401, call("GET", "/api/knowledge/catalogue", null, null).path("code").asInt());

        memberData("PUT", "/api/books/" + ids.getFirst() + "/publish", null, owner);
        var published = call("GET", "/api/blog/knowledge/catalogue", null, null).path("data");
        assertEquals(1, published.path("books").size());
        assertEquals(2, published.path("categories").size());
        assertTrue(published.toString().contains("编程"));
        assertFalse(published.toString().contains("另一用户"));

        memberData("PUT", "/api/books/" + ids.get(1) + "/shares", Map.of("username", "catalogue_guest", "accessRole", "VIEWER"), owner);
        var shared = memberData("GET", "/api/knowledge/catalogue", null, guest);
        assertEquals(2, shared.path("books").size());
        assertTrue(shared.toString().contains("编程"));
        assertTrue(shared.toString().contains("Python"));
    }

    @Test void anonymousMultiBookAskForcesPublishedScopeAndRejectsWithdrawnResults() throws Exception {
        String owner = member("public_scope_owner");
        long first = memberData("POST", "/api/books", Map.of("title", "Python 函数", "content", "使用 def"), owner).path("id").asLong();
        long second = memberData("POST", "/api/books", Map.of("title", "Python 文件", "content", "使用 with"), owner).path("id").asLong();
        long secret = memberData("POST", "/api/books", Map.of("title", "私人笔记", "content", "私密"), owner).path("id").asLong();
        jdbc.update("UPDATE book SET index_status='READY',indexed_revision=revision WHERE id IN (?,?,?)", first, second, secret);
        memberData("PUT", "/api/books/" + first + "/publish", null, owner);
        memberData("PUT", "/api/books/" + second + "/publish", null, owner);
        assertEquals(400, call("POST", "/api/blog/knowledge/ask", Map.of("question", "问题"), null).path("code").asInt());
        assertEquals(409, call("POST", "/api/blog/knowledge/ask", Map.of("question", "问题", "bookIds", List.of(secret), "publicOnly", false), null).path("code").asInt());

        org.mockito.Mockito.when(ai.call(org.mockito.ArgumentMatchers.eq("/internal/knowledge/ask"), org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            var payload = (Map<?, ?>) invocation.getArgument(1);
            var scopes = (List<?>) payload.get("scopes");
            assertEquals(2, scopes.size());
            assertEquals(Set.of(first, second), scopes.stream().map(row -> ((Number)((Map<?, ?>)row).get("book_id")).longValue()).collect(java.util.stream.Collectors.toSet()));
            return json.readTree("{\"answer\":\"结论：可以结合两本。论据：[1][2]\",\"sources\":[]}");
        });
        var body = Map.of("question", "如何结合函数和文件处理", "bookIds", List.of(first, second), "publicOnly", false);
        assertEquals(200, call("POST", "/api/blog/knowledge/ask", body, null).path("code").asInt());
        org.mockito.Mockito.when(ai.call(org.mockito.ArgumentMatchers.eq("/internal/knowledge/ask"), org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            jdbc.update("UPDATE book_publication SET status='OFFLINE' WHERE book_id=?", second);
            return json.readTree("{\"answer\":\"撤回后不应再出现的答案\",\"sources\":[]}");
        });
        var withdrawn = call("POST", "/api/blog/knowledge/ask", body, null);
        assertEquals(409, withdrawn.path("code").asInt());
        assertFalse(withdrawn.toString().contains("撤回后不应再出现的答案"));
    }

    @Test void articlePublicationFilteringAndClearing() throws Exception {
        long category=data("POST","/api/categories",Map.of("name","技术")).asLong();
        long tag=data("POST","/api/tags",Map.of("name","Java")).asLong();
        var body=new HashMap<String,Object>();body.put("title","测试文章");body.put("content","# 正文");body.put("categoryId",category);body.put("tagIds",List.of(tag));body.put("summary","摘要");
        long id=data("POST","/api/articles",body).path("id").asLong();
        assertEquals(404,call("GET","/api/blog/articles/"+id,null,null).path("code").asInt());
        var published=data("PUT","/api/articles/"+id+"/publish",null);
        assertEquals("技术",published.path("categoryName").asText());assertEquals(1,published.path("tags").size());
        var viewed=call("GET","/api/blog/articles/"+id,null,null).path("data");assertEquals(1,viewed.path("viewCount").asInt());
        var list=call("GET","/api/blog/articles?status=DRAFT&tagId="+tag,null,null).path("data").path("list");assertEquals(1,list.size());assertFalse(list.get(0).has("content"));
        assertEquals(400,call("DELETE","/api/categories/"+category,null,token).path("code").asInt());
        body.put("categoryId",null);body.put("summary",null);body.put("tagIds",List.of());data("PUT","/api/articles/"+id,body);
        var cleared=data("GET","/api/articles/"+id,null);assertTrue(cleared.path("categoryId").isNull());assertTrue(cleared.path("summary").isNull());assertEquals(0,cleared.path("tags").size());assertEquals(1,cleared.path("viewCount").asInt());
        data("PUT","/api/articles/"+id+"/offline",null);assertEquals(404,call("GET","/api/blog/articles/"+id,null,null).path("code").asInt());
        assertEquals(published.path("publishedAt"),data("PUT","/api/articles/"+id+"/publish",null).path("publishedAt"));
        data("DELETE","/api/articles/"+id,null);
    }
    @Test void authorizationAndInvalidRequests() throws Exception {
        assertEquals(401,call("GET","/api/articles",null,null).path("code").asInt());
        jdbc.update("INSERT INTO `user`(username,password,nickname,role,status) VALUES(?,?,?,?,1)","reader",encoder.encode("reader123"),"读者","READER");
        String reader=call("POST","/api/auth/login",Map.of("username","reader","password","reader123"),null).path("data").path("tokenValue").asText();
        assertEquals(403,call("POST","/api/articles",Map.of("title","x","content","y"),reader).path("code").asInt());
        assertEquals(400,call("POST","/api/articles",Map.of("title","","content","y"),token).path("code").asInt());
        assertEquals(400,call("GET","/api/articles?status=INVALID",null,token).path("code").asInt());
        assertEquals(403,call("POST","/internal/tasks/1/result",Map.of("runId","x","output","y"),null).path("code").asInt());
    }
    @Test void categoryCyclesAreRejected() throws Exception {
        long a=data("POST","/api/categories",Map.of("name","父分类")).asLong();
        long b=data("POST","/api/categories",Map.of("name","子分类","parentId",a)).asLong();
        assertEquals(400,call("PUT","/api/categories/"+a,Map.of("name","父分类","parentId",b),token).path("code").asInt());
        assertEquals(400,call("DELETE","/api/categories/"+a,null,token).path("code").asInt());
    }
    @Test void taskCheckpointsAndLateCallback() throws Exception {
        long id=data("POST","/api/tasks",Map.of("topic","AI 写作")).path("id").asLong();
        for(String stage:List.of("OUTLINE","DRAFT","POLISH")) {
            String run=UUID.randomUUID().toString();jdbc.update("UPDATE task SET status='RUNNING',run_id=? WHERE id=?",run,id);
            var response=mvc.perform(post("/internal/tasks/"+id+"/result").header("X-Internal-Token","integration-test-internal-token").contentType("application/json").content(json.writeValueAsString(Map.of("runId",run,"output","# 生成内容")))).andReturn().getResponse();
            assertEquals(200,json.readTree(response.getContentAsString()).path("code").asInt());
            assertEquals(409,call("POST","/api/tasks/"+id+"/review",Map.of("runId","stale","content","x"),token).path("code").asInt());
            var t=data("POST","/api/tasks/"+id+"/review",Map.of("runId",run,"content","# 作者确认 "+stage));
            assertEquals(stage.equals("POLISH")?"COMPLETED":"QUEUED",t.path("status").asText());
        }
        var task=data("GET","/api/tasks/"+id,null);var article=data("GET","/api/books/"+task.path("resultBookId").asLong(),null);assertEquals("DRAFT",article.path("status").asText());
        assertEquals(3,data("GET","/api/tasks/"+id+"/checkpoints",null).size());
        data("DELETE","/api/tasks/"+id,null);
        assertEquals("DRAFT",data("GET","/api/articles/"+article.path("id").asLong(),null).path("status").asText());
        long cancelled=data("POST","/api/tasks",Map.of("topic","取消测试")).path("id").asLong();
        jdbc.update("UPDATE task SET status='RUNNING',run_id='old' WHERE id=?",cancelled);data("POST","/api/tasks/"+cancelled+"/cancel",null);
        var late=mvc.perform(post("/internal/tasks/"+cancelled+"/result").header("X-Internal-Token","integration-test-internal-token").contentType("application/json").content("{\"runId\":\"old\",\"output\":\"late\"}")).andReturn().getResponse();assertEquals(409,json.readTree(late.getContentAsString()).path("code").asInt());
    }

    @Test void bookVersionsAndIndexCallbacks() throws Exception {
        assertEquals(401,call("GET","/api/books",null,null).path("code").asInt());
        var book=data("POST","/api/books",Map.of("title","资料 Book","content","手写正文"));
        long id=book.path("id").asLong();
        assertEquals("QUEUED",book.path("indexStatus").asText());
        assertEquals(409,call("POST","/api/books/"+id+"/ask",Map.of("question","正文是什么"),token).path("code").asInt());
        long oldJob=jdbc.queryForObject("SELECT id FROM book_index_job WHERE book_id=?",Long.class,id);
        jdbc.update("UPDATE book_index_job SET status='RUNNING',run_id='old' WHERE id=?",oldJob);
        var changed=data("PUT","/api/books/"+id,Map.of("title","资料 Book","content","新手写正文","revision",1));
        assertEquals(2,changed.path("revision").asInt());
        books.finishIndex(oldJob,"old",9,null);
        assertEquals("SUPERSEDED",jdbc.queryForObject("SELECT status FROM book_index_job WHERE id=?",String.class,oldJob));
        assertEquals("QUEUED",data("GET","/api/books/"+id,null).path("indexStatus").asText());
        assertEquals(409,call("PUT","/api/books/"+id,Map.of("title","资料 Book","content","覆盖","revision",1),token).path("code").asInt());
        long job=jdbc.queryForObject("SELECT id FROM book_index_job WHERE book_id=? AND revision=2",Long.class,id);
        jdbc.update("UPDATE book_index_job SET status='RUNNING',run_id='current' WHERE id=?",job);
        books.finishIndex(job,"wrong",1,null);
        assertEquals("QUEUED",data("GET","/api/books/"+id,null).path("indexStatus").asText());
        books.finishIndex(job,"current",3,null);
        assertEquals("READY",data("GET","/api/books/"+id,null).path("indexStatus").asText());
        data("PUT","/api/books/"+id,Map.of("title","资料 Book","content","新手写正文","revision",2));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM book_index_job WHERE book_id=?",Integer.class,id));
        data("DELETE","/api/books/"+id,null);
        assertEquals(404,call("GET","/api/books/"+id,null,token).path("code").asInt());
        assertEquals("DELETE",jdbc.queryForObject("SELECT operation FROM book_index_job WHERE book_id=? AND revision=3",String.class,id));
    }

    @Test void uploadedFileIsBookContentAndReplacementIsVersioned() throws Exception {
        var file=new org.springframework.mock.web.MockMultipartFile("file","资料.txt","text/plain","独立资料".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var upload=json.readTree(mvc.perform(multipart("/api/books/upload").file(file).header("Authorization",token)).andReturn().getResponse().getContentAsString());
        assertEquals(200,upload.path("code").asInt(),upload.toString());
        var book=upload.path("data");long id=book.path("id").asLong(),fileId=book.path("files").get(0).path("id").asLong();
        assertEquals("UPLOAD",book.path("sourceMode").asText());assertEquals("资料",book.path("title").asText());
        assertEquals("",book.path("content").asText());assertEquals(1,book.path("revision").asInt());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM book_index_job WHERE book_id=?",Integer.class,id));
        mvc.perform(multipart("/api/books/"+id+"/file").file(file).param("revision","1").header("Authorization",token));
        assertEquals(1,data("GET","/api/books/"+id,null).path("revision").asInt());
        var uploaded=org.mockito.ArgumentCaptor.forClass(io.minio.PutObjectArgs.class);
        org.mockito.Mockito.verify(minio,org.mockito.Mockito.times(1)).putObject(uploaded.capture());
        assertTrue(uploaded.getValue().object().startsWith("books/"+id+"/"));
        assertEquals("application/octet-stream",uploaded.getValue().contentType());
        assertEquals(401,call("GET","/api/books/"+id+"/files/"+fileId+"/download",null,null).path("code").asInt());
        assertEquals(400,call("PUT","/api/books/"+id,Map.of("title","资料","content","不能混入正文","revision",1),token).path("code").asInt());
        data("PUT","/api/books/"+id,Map.of("title","改名","content","","revision",1));
        var invalid=new org.springframework.mock.web.MockMultipartFile("file","bad.pdf","application/pdf","invalid".getBytes());
        assertEquals(400,json.readTree(mvc.perform(multipart("/api/books/upload").file(invalid).header("Authorization",token)).andReturn().getResponse().getContentAsString()).path("code").asInt());
        var replacement=new org.springframework.mock.web.MockMultipartFile("file","新版.md","text/plain","新内容".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(409,json.readTree(mvc.perform(multipart("/api/books/"+id+"/file").file(replacement).param("revision","1").header("Authorization",token)).andReturn().getResponse().getContentAsString()).path("code").asInt());
        var changed=json.readTree(mvc.perform(multipart("/api/books/"+id+"/file").file(replacement).param("revision","2").header("Authorization",token)).andReturn().getResponse().getContentAsString());
        assertEquals(200,changed.path("code").asInt());assertEquals(3,changed.path("data").path("revision").asInt());
        assertEquals(1,changed.path("data").path("files").size());
        assertEquals("新版.md",changed.path("data").path("files").get(0).path("originalName").asText());
        assertEquals(1,jdbc.queryForObject("SELECT deleted FROM book_file WHERE id=?",Integer.class,fileId));
        assertEquals(400,call("DELETE","/api/books/"+id+"/files/"+changed.path("data").path("files").get(0).path("id").asLong(),null,token).path("code").asInt());
        long online=data("POST","/api/books",Map.of("title","在线 Book","content","在线内容")).path("id").asLong();
        assertEquals("ONLINE",data("GET","/api/books/"+online,null).path("sourceMode").asText());
        assertEquals(400,json.readTree(mvc.perform(multipart("/api/books/"+online+"/file").file(file).param("revision","1").header("Authorization",token)).andReturn().getResponse().getContentAsString()).path("code").asInt());
    }

    @Test void uploadCreationRollsBackWhenStorageFails() throws Exception {
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM book",Long.class);
        var file=new org.springframework.mock.web.MockMultipartFile("file","失败.txt","text/plain","content".getBytes());
        assertEquals(401,json.readTree(mvc.perform(multipart("/api/books/upload").file(file)).andReturn().getResponse().getContentAsString()).path("code").asInt());
        org.mockito.Mockito.doThrow(new RuntimeException("storage unavailable")).when(minio).putObject(org.mockito.ArgumentMatchers.any());
        var transaction=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThrows(RuntimeException.class,()->transaction.execute(status -> {
            try { books.createUpload("失败 Book",null,file); return null; }
            catch (Exception e) { throw new RuntimeException(e); }
        }));
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM book",Long.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM book_file WHERE original_name='失败.txt'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM book_index_job j JOIN book b ON b.id=j.book_id WHERE b.title='失败 Book'",Integer.class));
    }

    @Test void bookWritingProducesNewIndexedBook() throws Exception {
        var original=books.createUpload("原始资料",null,new org.springframework.mock.web.MockMultipartFile("file","原书.txt","text/plain","保留的事实".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        long source=original.id();
        assertEquals(409,call("POST","/api/tasks",Map.of("topic","Book 生成","bookId",source),token).path("code").asInt());
        jdbc.update("UPDATE book SET index_status='READY',indexed_revision=revision WHERE id=?",source);
        long articles=jdbc.queryForObject("SELECT COUNT(*) FROM article",Long.class);
        long task=data("POST","/api/tasks",Map.of("topic","Book 生成","bookId",source)).path("id").asLong();
        for(String stage:List.of("OUTLINE","DRAFT","POLISH")) {
            String run=UUID.randomUUID().toString();
            jdbc.update("UPDATE task SET status='WAITING_REVIEW',output='模型输出',run_id=? WHERE id=?",run,task);
            data("POST","/api/tasks/"+task+"/review",Map.of("runId",run,"content","作者确认的 "+stage));
        }
        var completed=data("GET","/api/tasks/"+task,null);long result=completed.path("resultBookId").asLong();
        assertNotEquals(source,result);assertTrue(completed.path("articleId").isNull());
        assertEquals("QUEUED",data("GET","/api/books/"+result,null).path("indexStatus").asText());
        assertEquals("ONLINE",data("GET","/api/books/"+result,null).path("sourceMode").asText());
        assertEquals("UPLOAD",data("GET","/api/books/"+source,null).path("sourceMode").asText());
        assertEquals(original.files().getFirst().id().longValue(),data("GET","/api/books/"+source,null).path("files").get(0).path("id").asLong());
        assertEquals(articles,jdbc.queryForObject("SELECT COUNT(*) FROM article",Long.class));
    }

    @Test void readerScopeNeverIncludesDraftsOrOtherArticles() throws Exception {
        var first=data("POST","/api/articles",Map.of("title","公开原文","content","一号内容"));
        var second=data("POST","/api/articles",Map.of("title","其他原文","content","二号内容"));
        long a=first.path("id").asLong(), b=second.path("id").asLong();
        data("PUT","/api/articles/"+a+"/publish",null);data("PUT","/api/articles/"+b+"/publish",null);
        jdbc.update("UPDATE book SET index_status='READY',indexed_revision=revision WHERE id IN (?,?)",first.path("bookId").asLong(),second.path("bookId").asLong());
        org.mockito.Mockito.when(ai.call(org.mockito.ArgumentMatchers.eq("/internal/books/ask"),org.mockito.ArgumentMatchers.any())).thenReturn(json.readTree("{\"answer\":\"依据原文[1]\",\"sources\":[]}"));
        assertEquals(200,call("POST","/api/blog/ask",Map.of("question","解释内容","articleId",a,"history",List.of(Map.of("role","user","content","之前的问题"))),null).path("code").asInt());
        var capture=org.mockito.ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(ai).call(org.mockito.ArgumentMatchers.eq("/internal/books/ask"),capture.capture());
        var payload=(Map<?,?>)capture.getValue();assertEquals(first.path("bookId").asLong(),((Number)payload.get("book_id")).longValue());
        data("PUT","/api/articles/"+a+"/offline",null);
        assertEquals(404,call("POST","/api/blog/ask",Map.of("question","解释内容","articleId",a),null).path("code").asInt());
        assertEquals(400,call("POST","/api/blog/ask",Map.of("question","解释内容","history",List.of(Map.of("role","system","content","非法角色"))),null).path("code").asInt());
    }

    @Test void privateReaderParsesTheOriginalAndRejectsStaleVersions() throws Exception {
        var original=books.createUpload("私有原文",null,new org.springframework.mock.web.MockMultipartFile("file","书.txt","text/plain","原文内容".getBytes()));
        long id=original.id();
        assertEquals(401,call("GET","/api/books/"+id+"/read",null,null).path("code").asInt());
        org.mockito.Mockito.when(minio.getPresignedObjectUrl(org.mockito.ArgumentMatchers.any())).thenReturn("http://127.0.0.1:9000/book-agent/signed");
        org.mockito.Mockito.when(ai.call(org.mockito.ArgumentMatchers.eq("/internal/books/read"),org.mockito.ArgumentMatchers.any())).thenReturn(json.readTree("{\"sections\":[{\"text\":\"原文内容\",\"page\":1}]}"));
        var read=data("GET","/api/books/"+id+"/read",null);assertEquals("原文内容",read.path("sections").get(0).path("text").asText());
        org.mockito.Mockito.when(ai.call(org.mockito.ArgumentMatchers.eq("/internal/books/read"),org.mockito.ArgumentMatchers.any())).thenAnswer(invocation->{jdbc.update("UPDATE book SET revision=revision+1 WHERE id=?",id);return json.readTree("{\"sections\":[]}");});
        assertEquals(409,call("GET","/api/books/"+id+"/read",null,token).path("code").asInt());
    }
    @Test void legacyMigrationIsIdempotentAndPreservesContentAndPublication() throws Exception {
        long category=data("POST","/api/categories",Map.of("name","迁移分类")).asLong();
        long tag=data("POST","/api/tags",Map.of("name","迁移标签")).asLong();
        long legacy=keys.insert("INSERT INTO article(title,summary,content,category_id,status,view_count,published_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)","旧 Book","原摘要","# 原正文",category,"PUBLISHED",7);
        jdbc.update("INSERT INTO article_tag(article_id,tag_id) VALUES(?,?)",legacy,tag);
        assertEquals(1,migration.migrate());assertEquals(0,migration.migrate());
        long canonical=books.legacyId(legacy);var book=data("GET","/api/books/"+canonical,null);
        assertEquals("# 原正文",book.path("content").asText());assertEquals("PUBLISHED",book.path("status").asText());
        assertEquals(category,book.path("categoryId").asLong());assertEquals(tag,book.path("tags").get(0).path("id").asLong());assertEquals(7,book.path("viewCount").asInt());
        assertEquals("# 原正文",jdbc.queryForObject("SELECT content FROM article WHERE id=?",String.class,legacy));
        var changed=data("PUT","/api/articles/"+legacy,Map.of("title","新的标题","content","新的正文","categoryId",category,"tagIds",List.of(tag)));
        assertEquals(legacy,changed.path("id").asLong());assertEquals(canonical,changed.path("bookId").asLong());assertEquals("新的正文",data("GET","/api/books/"+canonical,null).path("content").asText());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM legacy_article_book WHERE article_id=?",Integer.class,legacy));
    }
    @Test void publicationControlsAnonymousFileReadingAndQuestionAccess() throws Exception {
        long id=books.createUpload("文件发布",null,new org.springframework.mock.web.MockMultipartFile("file","原文.txt","text/plain","原文".getBytes())).id();
        assertEquals(404,call("GET","/api/blog/books/"+id+"/read",null,null).path("code").asInt());
        assertEquals(409,call("PUT","/api/books/"+id+"/publish",null,token).path("code").asInt());
        jdbc.update("UPDATE book SET index_status='READY',indexed_revision=revision WHERE id=?",id);
        var published=data("PUT","/api/books/"+id+"/publish",null);
        org.mockito.Mockito.when(minio.getPresignedObjectUrl(org.mockito.ArgumentMatchers.any())).thenReturn("http://127.0.0.1:9000/signed");
        org.mockito.Mockito.when(ai.call(org.mockito.ArgumentMatchers.eq("/internal/books/read"),org.mockito.ArgumentMatchers.any())).thenReturn(json.readTree("{\"sections\":[{\"text\":\"原文\"}]}"));
        assertEquals(200,call("GET","/api/blog/books/"+id+"/read",null,null).path("code").asInt());
        long file=published.path("files").get(0).path("id").asLong();
        assertEquals(200,call("GET","/api/blog/books/"+id+"/files/"+file+"/download",null,null).path("code").asInt());
        org.mockito.Mockito.when(ai.call(org.mockito.ArgumentMatchers.eq("/internal/books/ask"),org.mockito.ArgumentMatchers.any())).thenAnswer(invocation->{jdbc.update("UPDATE book_publication SET status='OFFLINE' WHERE book_id=?",id);return json.readTree("{\"answer\":\"late\",\"sources\":[]}");});
        assertEquals(404,call("POST","/api/blog/books/"+id+"/ask",Map.of("question","原文是什么"),null).path("code").asInt());
        assertEquals(404,call("GET","/api/blog/books/"+id+"/read",null,null).path("code").asInt());
        assertEquals(404,call("GET","/api/blog/books/"+id+"/files/"+file+"/download",null,null).path("code").asInt());
        assertEquals(published.path("publishedAt"),data("PUT","/api/books/"+id+"/publish",null).path("publishedAt"));
    }

    @Test void legacyHandlesRoundTripWithoutCollidingWithCanonicalIds() throws Exception {
        var canonical=books.create(new com.bookagent.backend.model.dto.BookSaveDTO("当前 Book",null,"当前正文",null));
        var migrated=books.create(new com.bookagent.backend.model.dto.BookSaveDTO("旧文章映射 Book",null,"旧正文",null));
        long legacy=canonical.id();
        jdbc.update("INSERT INTO article(id,title,content,status) VALUES(?,?,?,'PUBLISHED')",legacy,"旧档案","档案原文");
        jdbc.update("INSERT INTO legacy_article_book(article_id,book_id) VALUES(?,?)",legacy,migrated.id());
        var list=data("GET","/api/articles?pageSize=50",null).path("list");
        long handle=0;
        for(var item:list){
            long returned=item.path("id").asLong();
            var detail=data("GET","/api/articles/"+returned,null);
            assertEquals(item.path("bookId"),detail.path("bookId"));
            assertEquals(item.path("title"),detail.path("title"));
            if(item.path("bookId").asLong()==canonical.id())handle=returned;
        }
        assertTrue(handle>=com.bookagent.backend.service.LegacyBookIds.BOOK_HANDLE_BASE);
        assertTrue(handle<=9007199254740991L);
        assertEquals(migrated.id().longValue(),data("GET","/api/articles/"+legacy,null).path("bookId").asLong());
        data("PUT","/api/articles/"+handle,Map.of("title","仅修改当前 Book","content","正确的新正文"));
        assertEquals("正确的新正文",books.detail(canonical.id()).content());
        assertEquals("旧正文",books.detail(migrated.id()).content());
        books.publish(canonical.id());books.publish(migrated.id());
        assertEquals(canonical.id().longValue(),call("GET","/api/blog/articles/"+handle+"/resolve",null,null).path("data").path("bookId").asLong());
        assertEquals(migrated.id().longValue(),call("GET","/api/blog/articles/"+legacy+"/resolve",null,null).path("data").path("bookId").asLong());
        data("DELETE","/api/articles/"+handle,null);
        assertEquals(404,call("GET","/api/books/"+canonical.id(),null,token).path("code").asInt());
        assertEquals("旧正文",books.detail(migrated.id()).content());
    }

    @Test void everyPublicAskEntryRechecksPublicationAfterAi() throws Exception {
        long id=books.create(new com.bookagent.backend.model.dto.BookSaveDTO("公开问答边界",null,"原文",null)).id();
        jdbc.update("UPDATE book SET index_status='READY',indexed_revision=revision WHERE id=?",id);
        org.mockito.Mockito.when(ai.call(org.mockito.ArgumentMatchers.eq("/internal/books/ask"),org.mockito.ArgumentMatchers.any()))
            .thenAnswer(invocation->{jdbc.update("UPDATE book_publication SET status='OFFLINE' WHERE book_id=?",id);return json.readTree("{\"answer\":\"private result\",\"sources\":[]}");});
        for(String endpoint:List.of("/api/blog/books/"+id+"/ask","/api/blog/ask","/api/ask")){
            books.publish(id);
            var response=call("POST",endpoint,Map.of("question","问题","bookId",id),endpoint.equals("/api/ask")?token:null);
            assertEquals(404,response.path("code").asInt());assertFalse(response.toString().contains("private result"));
        }
    }

    @Test void readAndSigningRejectPublicationChangesDuringRemoteWork() throws Exception {
        long id=books.createUpload("阅读边界",null,new org.springframework.mock.web.MockMultipartFile("file","边界.txt","text/plain","原文".getBytes())).id();
        jdbc.update("UPDATE book SET index_status='READY',indexed_revision=revision WHERE id=?",id);books.publish(id);
        org.mockito.Mockito.when(minio.getPresignedObjectUrl(org.mockito.ArgumentMatchers.any())).thenReturn("http://127.0.0.1:9000/signed");
        org.mockito.Mockito.when(ai.call(org.mockito.ArgumentMatchers.eq("/internal/books/read"),org.mockito.ArgumentMatchers.any()))
            .thenAnswer(invocation->{jdbc.update("UPDATE book_publication SET status='OFFLINE' WHERE book_id=?",id);return json.readTree("{\"sections\":[{\"text\":\"private text\"}]}");});
        var response=call("GET","/api/blog/books/"+id+"/read",null,null);
        assertEquals(404,response.path("code").asInt());assertFalse(response.toString().contains("private text"));
        books.publish(id);long fileId=books.files(id).getFirst().id();
        org.mockito.Mockito.when(minio.getPresignedObjectUrl(org.mockito.ArgumentMatchers.any()))
            .thenAnswer(invocation->{jdbc.update("UPDATE book_publication SET status='OFFLINE' WHERE book_id=?",id);return "http://127.0.0.1:9000/private-signed";});
        var signed=call("GET","/api/blog/books/"+id+"/files/"+fileId+"/download",null,null);
        assertEquals(404,signed.path("code").asInt());assertFalse(signed.toString().contains("private-signed"));
    }

    private String member(String name)throws Exception{
        assertEquals(200,call("POST","/api/auth/register",Map.of("username",name,"password","member123","nickname",name),null).path("code").asInt());
        return call("POST","/api/auth/login",Map.of("username",name,"password","member123"),null).path("data").path("tokenValue").asText();
    }
    private com.fasterxml.jackson.databind.JsonNode memberData(String method,String path,Object body,String auth)throws Exception{
        var result=call(method,path,body,auth);assertEquals(200,result.path("code").asInt(),result.toString());return result.path("data");
    }
    @Test void personalSpacesAndSharesEnforceReaderEditorOwnerBoundaries()throws Exception{
        String a=member("owner_a"),b=member("member_b");
        var book=memberData("POST","/api/books",Map.of("title","A私有知识","content","私有原文"),a);long id=book.path("id").asLong();
        assertEquals("OWNER",book.path("accessRole").asText());
        assertEquals(0,memberData("GET","/api/books",null,b).path("total").asInt());
        assertEquals(404,call("GET","/api/books/"+id,null,b).path("code").asInt());
        memberData("PUT","/api/books/"+id+"/shares",Map.of("username","member_b","accessRole","VIEWER"),a);
        assertEquals("VIEWER",memberData("GET","/api/books/"+id,null,b).path("accessRole").asText());
        assertEquals(404,call("PUT","/api/books/"+id,Map.of("title","wrong","content","wrong","revision",1),b).path("code").asInt());
        assertEquals(404,call("GET","/api/books/"+id+"/shares",null,b).path("code").asInt());
        memberData("PUT","/api/books/"+id+"/shares",Map.of("username","member_b","accessRole","EDITOR"),a);
        memberData("PUT","/api/books/"+id,Map.of("title","A私有知识","content","共同编辑","revision",1),b);
        assertEquals(404,call("PUT","/api/books/"+id+"/publish",null,b).path("code").asInt());
        long user=jdbc.queryForObject("SELECT id FROM `user` WHERE username='member_b'",Long.class);
        memberData("DELETE","/api/books/"+id+"/shares/"+user,null,a);
        assertEquals(404,call("GET","/api/books/"+id+"/read",null,b).path("code").asInt());
    }
    @Test void immutableVersionHistoryRestoresAsNewDraftRevision()throws Exception{
        String a=member("history_user");long id=memberData("POST","/api/books",Map.of("title","历史","content","第一稿"),a).path("id").asLong();
        memberData("PUT","/api/books/"+id,Map.of("title","新标题","content","第二稿","revision",1),a);
        assertEquals("第一稿",memberData("GET","/api/books/"+id+"/versions/1",null,a).path("content").asText());
        var restored=memberData("POST","/api/books/"+id+"/versions/1/restore",Map.of("expectedRevision",2),a);
        assertEquals(3,restored.path("revision").asInt());assertEquals("第一稿",restored.path("content").asText());assertEquals("DRAFT",restored.path("status").asText());
        assertEquals(409,call("POST","/api/books/"+id+"/versions/2/restore",Map.of("expectedRevision",2),a).path("code").asInt());
        assertEquals(3,memberData("GET","/api/books/"+id+"/versions",null,a).size());
    }
    @Test void taxonomyNamesArePersonalAndCannotBeAttachedAcrossSpaces()throws Exception{
        String a=member("tax_owner"),b=member("tax_other");
        long ca=memberData("POST","/api/categories",Map.of("name","学习"),a).asLong(),cb=memberData("POST","/api/categories",Map.of("name","学习"),b).asLong();assertNotEquals(ca,cb);
        assertEquals("学习",memberData("GET","/api/categories",null,a).get(0).path("name").asText());
        assertEquals(400,call("POST","/api/books",Map.of("title","x","content","y","categoryId",ca),b).path("code").asInt());
        assertEquals(404,call("DELETE","/api/categories/"+ca,null,b).path("code").asInt());
    }
    @Test void importsAreOwnedDeduplicatedAndVersioned()throws Exception{
        String a=member("import_user"),b=member("import_other");
        var request=Map.of("kind","WEB","url","https://example.com/guide","organize",false);
        long job=memberData("POST","/api/imports",request,a).path("id").asLong();
        assertEquals(409,call("POST","/api/imports",request,a).path("code").asInt());assertEquals(0,memberData("GET","/api/imports",null,b).size());
        long owner=jdbc.queryForObject("SELECT id FROM `user` WHERE username='import_user'",Long.class);
        var access=org.springframework.test.util.ReflectionTestUtils.getField(books,"access");
        var context=(com.bookagent.backend.service.KnowledgeAccess)access;
        var service=org.springframework.test.util.AopTestUtils.<com.bookagent.backend.service.KnowledgeImports>getUltimateTargetObject(importService);
        jdbc.update("UPDATE knowledge_import SET status='RUNNING',run_id='fixture' WHERE id=?",job);
        context.asUser(owner,()->{importService.apply(job,"fixture",json.readTree("{\"title\":\"网页导入\",\"content\":\"网页正文\",\"source_hash\":\""+"a".repeat(64)+"\",\"metadata\":{}}"),null);return null;});
        long id=jdbc.queryForObject("SELECT book_id FROM knowledge_import WHERE id=?",Long.class,job);
        assertEquals("网页正文",memberData("GET","/api/books/"+id,null,a).path("content").asText());
        long second=memberData("POST","/api/imports",request,a).path("id").asLong();jdbc.update("UPDATE knowledge_import SET status='RUNNING',run_id='fixture2' WHERE id=?",second);
        context.asUser(owner,()->{importService.apply(second,"fixture2",json.readTree("{\"title\":\"网页导入\",\"content\":\"网页正文\",\"source_hash\":\""+"a".repeat(64)+"\",\"metadata\":{}}"),null);return null;});
        assertEquals("UNCHANGED",jdbc.queryForObject("SELECT status FROM knowledge_import WHERE id=?",String.class,second));
        assertEquals(1,memberData("GET","/api/books/"+id,null,a).path("revision").asInt());
    }
    @org.springframework.beans.factory.annotation.Autowired com.bookagent.backend.service.KnowledgeImports importService;
    @Test void fullLibrarySearchExcludesPrivateBooksAndRejectsRevokedScopes()throws Exception{
        String a=member("search_a"),b=member("search_b");long aid=memberData("POST","/api/books",Map.of("title","允许","content","允许原文"),a).path("id").asLong();long bid=memberData("POST","/api/books",Map.of("title","秘密","content","秘密原文"),b).path("id").asLong();
        jdbc.update("UPDATE book SET index_status='READY',indexed_revision=revision WHERE id IN (?,?)",aid,bid);
        org.mockito.Mockito.when(ai.call(org.mockito.ArgumentMatchers.eq("/internal/knowledge/search"),org.mockito.ArgumentMatchers.any())).thenAnswer(invocation->{var payload=(Map<?,?>)invocation.getArgument(1);var scopes=(List<?>)payload.get("scopes");assertEquals(1,scopes.size());assertEquals(aid,((Number)((Map<?,?>)scopes.getFirst()).get("book_id")).longValue());return json.readTree("{\"results\":[],\"mode\":\"hybrid\"}");});
        memberData("POST","/api/knowledge/search",Map.of("question","原文"),a);
        memberData("PUT","/api/books/"+bid+"/shares",Map.of("username","search_a","accessRole","VIEWER"),b);
        long user=jdbc.queryForObject("SELECT id FROM `user` WHERE username='search_a'",Long.class);
        org.mockito.Mockito.when(ai.call(org.mockito.ArgumentMatchers.eq("/internal/knowledge/search"),org.mockito.ArgumentMatchers.any())).thenAnswer(invocation->{jdbc.update("DELETE FROM book_share WHERE book_id=? AND user_id=?",bid,user);return json.readTree("{\"results\":[{\"excerpt\":\"不应返回的秘密\"}]}");});
        var result=call("POST","/api/knowledge/search",Map.of("question","秘密","bookIds",List.of(bid)),a);assertEquals(409,result.path("code").asInt());assertFalse(result.toString().contains("不应返回的秘密"));
    }
    @Test void conversationPersistsAndRedactsEvidenceAfterShareRevocation()throws Exception{
        String a=member("chat_owner"),b=member("chat_guest");long id=memberData("POST","/api/books",Map.of("title","对话资料","content","秘密代号甲"),a).path("id").asLong();jdbc.update("UPDATE book SET index_status='READY',indexed_revision=revision WHERE id=?",id);
        memberData("PUT","/api/books/"+id+"/shares",Map.of("username","chat_guest","accessRole","VIEWER"),a);
        long conversation=memberData("POST","/api/conversations",Map.of("title","对话","bookIds",List.of(id)),b).path("id").asLong();
        org.mockito.Mockito.when(ai.call(org.mockito.ArgumentMatchers.eq("/internal/knowledge/ask"),org.mockito.ArgumentMatchers.any())).thenReturn(json.readTree("{\"answer\":\"秘密代号甲\",\"sources\":[{\"bookId\":"+id+",\"citation\":1}]}") );
        memberData("POST","/api/conversations/"+conversation+"/messages",Map.of("question","代号是什么"),b);
        assertEquals(2,memberData("GET","/api/conversations/"+conversation,null,b).path("messages").size());assertEquals(404,call("GET","/api/conversations/"+conversation,null,a).path("code").asInt());
        long user=jdbc.queryForObject("SELECT id FROM `user` WHERE username='chat_guest'",Long.class);memberData("DELETE","/api/books/"+id+"/shares/"+user,null,a);
        var detail=memberData("GET","/api/conversations/"+conversation,null,b);assertTrue(detail.path("messages").get(1).path("redacted").asBoolean());assertFalse(detail.toString().contains("秘密代号甲"));
    }
    private void seedContext(long conversation,long book) throws Exception {
        for(int i=0;i<6;i++){
            jdbc.update("INSERT INTO knowledge_message(conversation_id,role,content,sources_json,status) VALUES(?,'user',?,'[]','SUCCEEDED')",conversation,i==0?"项目名 Cedar，必须用中文":"问题"+i);
            jdbc.update("INSERT INTO knowledge_message(conversation_id,role,content,sources_json,status) VALUES(?,'assistant',?,?,'SUCCEEDED')",conversation,"历史回答"+i,json.writeValueAsString(List.of(Map.of("bookId",book,"citation",1))));
        }
    }
    @Test void conversationMemoryRollsForwardAndIsDeletedWithItsConversation()throws Exception{
        String owner=member("memory_owner");long book=memberData("POST","/api/books",Map.of("title","上下文知识","content","Python 函数"),owner).path("id").asLong();
        jdbc.update("UPDATE book SET index_status='READY',indexed_revision=revision WHERE id=?",book);
        long conversation=memberData("POST","/api/conversations",Map.of("bookIds",List.of(book)),owner).path("id").asLong();seedContext(conversation,book);
        org.mockito.Mockito.when(ai.call(org.mockito.ArgumentMatchers.eq("/internal/context/compact"),org.mockito.ArgumentMatchers.any())).thenReturn(json.readTree("{\"memory\":{\"focus\":\"Cedar\",\"constraints\":[\"用中文\"]}}"));
        org.mockito.Mockito.when(ai.call(org.mockito.ArgumentMatchers.eq("/internal/knowledge/ask"),org.mockito.ArgumentMatchers.any())).thenAnswer(invocation->{
            var body=(Map<?,?>)invocation.getArgument(1);assertEquals("Cedar",((JsonNode)body.get("memory")).path("focus").asText());assertTrue(((List<?>)body.get("history")).size()>=12);
            return json.readTree("{\"answer\":\"依据当前原文回答\",\"sources\":[{\"bookId\":"+book+",\"citation\":1}]}");
        });
        var first=memberData("POST","/api/conversations/"+conversation+"/messages",Map.of("question","这个项目的要求是什么？"),owner);
        assertTrue(first.path("context").path("summaryUpdated").asBoolean());long covered=first.path("context").path("summaryCoveredMessageId").asLong();assertTrue(covered>0);
        memberData("POST","/api/conversations/"+conversation+"/messages",Map.of("question","继续"),owner);
        org.mockito.Mockito.verify(ai,org.mockito.Mockito.times(1)).call(org.mockito.ArgumentMatchers.eq("/internal/context/compact"),org.mockito.ArgumentMatchers.any());
        var third=memberData("POST","/api/conversations/"+conversation+"/messages",Map.of("question","再继续"),owner);
        assertTrue(third.path("context").path("summaryCoveredMessageId").asLong()>covered);
        org.mockito.Mockito.verify(ai,org.mockito.Mockito.times(2)).call(org.mockito.ArgumentMatchers.eq("/internal/context/compact"),org.mockito.ArgumentMatchers.any());
        memberData("DELETE","/api/conversations/"+conversation,null,owner);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_conversation_memory WHERE conversation_id=?",Integer.class,conversation));
    }
    @Test void memoryCompressionFailureFallsBackWithoutFailingTheQuestion()throws Exception{
        String owner=member("memory_fallback");long book=memberData("POST","/api/books",Map.of("title","依据","content","原文"),owner).path("id").asLong();jdbc.update("UPDATE book SET index_status='READY',indexed_revision=revision WHERE id=?",book);
        long conversation=memberData("POST","/api/conversations",Map.of("bookIds",List.of(book)),owner).path("id").asLong();seedContext(conversation,book);
        org.mockito.Mockito.when(ai.call(org.mockito.ArgumentMatchers.eq("/internal/context/compact"),org.mockito.ArgumentMatchers.any())).thenThrow(new com.bookagent.backend.common.exception.BusinessException(503,"摘要不可用"));
        org.mockito.Mockito.when(ai.call(org.mockito.ArgumentMatchers.eq("/internal/knowledge/ask"),org.mockito.ArgumentMatchers.any())).thenAnswer(invocation->{assertFalse(((Map<?,?>)invocation.getArgument(1)).containsKey("memory"));return json.readTree("{\"answer\":\"仍然回答\",\"sources\":[]}");});
        var result=memberData("POST","/api/conversations/"+conversation+"/messages",Map.of("question","继续"),owner);
        assertEquals("仍然回答",result.path("answer").asText());assertTrue(result.path("context").path("summaryFallback").asBoolean());
    }
    @Test void cachedMemoryIsDiscardedAfterItsVisibleHistoryChanges()throws Exception{
        String owner=member("memory_invalidate");long conversation=memberData("POST","/api/conversations",Map.of(),owner).path("id").asLong();
        var service=new com.bookagent.backend.service.ConversationMemory(jdbc,json,ai);
        var visible=json.createObjectNode();var messages=visible.putArray("messages");
        for(int i=1;i<=12;i++)messages.addObject().put("id",i).put("role",i%2==1?"user":"assistant").put("status","SUCCEEDED").put("content","消息"+i);
        org.mockito.Mockito.when(ai.call(org.mockito.ArgumentMatchers.eq("/internal/context/compact"),org.mockito.ArgumentMatchers.any())).thenReturn(json.readTree("{\"memory\":{\"focus\":\"已撤销的信息\"}}"));
        var first=service.prepare(conversation,service.eligible(visible),"scope1");service.save(conversation,first);
        ((com.fasterxml.jackson.databind.node.ObjectNode)messages.get(1)).put("redacted",true);
        org.mockito.Mockito.when(ai.call(org.mockito.ArgumentMatchers.eq("/internal/context/compact"),org.mockito.ArgumentMatchers.any())).thenAnswer(invocation->{assertTrue(((JsonNode)((Map<?,?>)invocation.getArgument(1)).get("previous")).isEmpty());return json.readTree("{\"memory\":{\"focus\":\"可见的信息\"}}");});
        var rebuilt=service.prepare(conversation,service.eligible(visible),"scope2");
        // Only three old visible messages remain: discard the stale summary immediately;
        // the next batch will compact again when four eligible messages accumulate.
        assertTrue(rebuilt.memory().isEmpty());assertTrue(rebuilt.changed());
        service.save(conversation,rebuilt);
        assertFalse(jdbc.queryForObject("SELECT memory_json FROM knowledge_conversation_memory WHERE conversation_id=?",String.class,conversation).contains("已撤销"));
    }
    @Test void permissionRevocationDuringCompactionPreventsAnswerAndMemoryCommit()throws Exception{
        String owner=member("memory_share_owner"),guest=member("memory_share_guest");long book=memberData("POST","/api/books",Map.of("title","私有","content","敏感原文"),owner).path("id").asLong();jdbc.update("UPDATE book SET index_status='READY',indexed_revision=revision WHERE id=?",book);
        memberData("PUT","/api/books/"+book+"/shares",Map.of("username","memory_share_guest","accessRole","VIEWER"),owner);
        long conversation=memberData("POST","/api/conversations",Map.of("bookIds",List.of(book)),guest).path("id").asLong();seedContext(conversation,book);
        org.mockito.Mockito.when(ai.call(org.mockito.ArgumentMatchers.eq("/internal/context/compact"),org.mockito.ArgumentMatchers.any())).thenAnswer(invocation->{jdbc.update("DELETE FROM book_share WHERE book_id=?",book);return json.readTree("{\"memory\":{\"focus\":\"不应交付的摘要\"}}");});
        assertEquals(409,call("POST","/api/conversations/"+conversation+"/messages",Map.of("question","继续"),guest).path("code").asInt());
        org.mockito.Mockito.verify(ai,org.mockito.Mockito.never()).call(org.mockito.ArgumentMatchers.eq("/internal/knowledge/ask"),org.mockito.ArgumentMatchers.any());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_conversation_memory WHERE conversation_id=?",Integer.class,conversation));
    }
    @Test void backupRestoresNewDraftsWithOriginalVersionHistory()throws Exception{
        String a=member("backup_owner");long id=memberData("POST","/api/books",Map.of("title","备份作品","content","旧原文"),a).path("id").asLong();memberData("PUT","/api/books/"+id,Map.of("title","备份作品","content","新原文","revision",1),a);memberData("PUT","/api/books/"+id+"/publish",null,a);
        byte[] zip=mvc.perform(get("/api/workspace/backup").header("Authorization",a)).andReturn().getResponse().getContentAsByteArray();assertEquals('P',zip[0]);
        var file=new org.springframework.mock.web.MockMultipartFile("file","backup.zip","application/zip",zip);
        var result=json.readTree(mvc.perform(multipart("/api/workspace/restore").file(file).header("Authorization",a)).andReturn().getResponse().getContentAsString());assertEquals(200,result.path("code").asInt(),result.toString());long restored=result.path("data").path("bookIds").get(0).asLong();assertNotEquals(id,restored);
        var book=memberData("GET","/api/books/"+restored,null,a);assertEquals("DRAFT",book.path("status").asText());assertEquals("新原文",book.path("content").asText());assertEquals("旧原文",memberData("GET","/api/books/"+restored+"/versions/1",null,a).path("content").asText());
    }

    @Test void catalogueFiltersBySourceAndCreationDateWithoutCrossingOwnership()throws Exception{
        String a=member("filter_user");long id=memberData("POST","/api/books",Map.of("title","网页资料","content","正文"),a).path("id").asLong();
        memberData("POST","/api/books",Map.of("title","在线资料","content","正文"),a);
        jdbc.update("INSERT INTO knowledge_origin(book_id,kind,source_url,source_hash,metadata_json) VALUES(?,'WEB','https://example.com',?,'{}')",id,"a".repeat(64));
        var page=memberData("GET","/api/books?sourceKind=WEB",null,a);assertEquals(1,page.path("total").asInt());assertEquals("WEB",page.path("list").get(0).path("originKind").asText());
        assertEquals(0,memberData("GET","/api/books?createdFrom=2099-01-01",null,a).path("total").asInt());
        assertEquals(400,call("GET","/api/books?createdFrom=invalid",null,a).path("code").asInt());
        assertEquals(400,call("GET","/api/books?sourceKind=invalid",null,a).path("code").asInt());
    }
    @Test void restoreRejectsTraversalAndCorruptOriginalsBeforeWriting()throws Exception{
        String a=member("invalid_backup");
        for(boolean traversal:List.of(true,false)){
            var buffer=new java.io.ByteArrayOutputStream();try(var zip=new java.util.zip.ZipOutputStream(buffer)){
                zip.putNextEntry(new java.util.zip.ZipEntry(traversal?"../outside":"manifest.json"));
                zip.write((traversal?"x":"{\"format\":\"BookAgentBackup\",\"version\":1,\"books\":[{\"objects\":[{\"entry\":\"files/missing.txt\",\"size\":1,\"sha256\":\"bad\"}]}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
            }
            var file=new org.springframework.mock.web.MockMultipartFile("file","bad.zip","application/zip",buffer.toByteArray());
            var response=json.readTree(mvc.perform(multipart("/api/workspace/restore").file(file).header("Authorization",a)).andReturn().getResponse().getContentAsString());assertEquals(400,response.path("code").asInt());
        }
        assertEquals(0,memberData("GET","/api/books",null,a).path("total").asInt());
    }
}
