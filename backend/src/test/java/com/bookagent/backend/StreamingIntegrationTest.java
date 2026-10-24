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
import java.util.*;
import java.util.function.BiConsumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class StreamingIntegrationTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.bookagent.backend.service.AiClient ai;

    JsonNode call(String method,String url,Object body,String auth) throws Exception {
        var request=request(org.springframework.http.HttpMethod.valueOf(method),url);
        if(auth!=null)request.header("Authorization",auth);
        if(body!=null)request.contentType("application/json").content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString());
    }
    private String member(String name)throws Exception{
        assertEquals(200,call("POST","/api/auth/register",Map.of("username",name,"password","member123","nickname",name),null).path("code").asInt());
        return call("POST","/api/auth/login",Map.of("username",name,"password","member123"),null).path("data").path("tokenValue").asText();
    }

    /** member + one READY book + one conversation scoped to it. */
    private record Prepared(long book,long conversation){}
    private Prepared preparedConversation(String name) throws Exception {
        String token=member(name);
        long book=call("POST","/api/books",Map.of("title","流式测试书","content","答案就在这里"),token).path("data").path("id").asLong();
        jdbc.update("UPDATE book SET index_status='READY',indexed_revision=revision WHERE id=?",book);
        long conversation=call("POST","/api/conversations",Map.of("title","流式对话","bookIds",List.of(book),"mode","qa"),token)
            .path("data").path("id").asLong();
        return new Prepared(book,conversation);
    }
    private String stream(long id,String question) throws Exception {
        var response=mvc.perform(post("/api/conversations/"+id+"/messages/stream").contentType("application/json")
            .content(json.writeValueAsString(Map.of("question",question))).header("Authorization",memberTokenOf(id)).accept("text/event-stream"))
            .andReturn().getResponse();
        assertEquals(200,response.getStatus());
        assertTrue(response.getContentType().startsWith("text/event-stream"));
        return response.getContentAsString();
    }
    private String memberTokenOf(long conversationId) throws Exception {
        var row=jdbc.queryForMap("SELECT user_id FROM knowledge_conversation WHERE id=?",conversationId);
        var username=(String)jdbc.queryForObject("SELECT username FROM user WHERE id=?",String.class,row.get("user_id"));
        return call("POST","/api/auth/login",Map.of("username",username,"password","member123"),null).path("data").path("tokenValue").asText();
    }

    @Test void streamsPreviewThenPersistsValidatedResult() throws Exception {
        var prepared=preparedConversation("stream_happy");
        long id=prepared.conversation();
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked") var handler=(BiConsumer<String,JsonNode>)invocation.getArgument(2);
            handler.accept("delta",json.readTree("{\"text\":\"预览结\"}"));
            handler.accept("reset",json.createObjectNode());
            handler.accept("delta",json.readTree("{\"text\":\"论\"}"));
            handler.accept("done",json.readTree("{\"answer\":\"**结论：**答案就在这里。\",\"sources\":[{\"citation\":1,\"bookId\":"+prepared.book()+"}],\"mode\":\"hybrid\",\"rerankMode\":\"rrf\",\"context\":{}}"));
            return null;
        }).when(ai).stream(org.mockito.ArgumentMatchers.eq("/internal/knowledge/ask/stream"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());

        String body=stream(id,"答案在哪里");
        int start=body.indexOf("event: start"),first=body.indexOf("event: delta"),reset=body.indexOf("event: reset"),done=body.indexOf("event: done"),error=body.indexOf("event: error");
        assertTrue(start>=0&&first>start&&reset>first&&done>reset&&error<0,body);
        assertTrue(body.contains("\"answer\":\"**结论：**答案就在这里。\""));

        var detail=call("GET","/api/conversations/"+id,null,memberTokenOf(id)).path("data");
        assertEquals("SUCCEEDED",detail.path("messages").get(0).path("status").asText());
        assertEquals("assistant",detail.path("messages").get(1).path("role").asText());
        assertEquals("**结论：**答案就在这里。",detail.path("messages").get(1).path("content").asText());
        assertNull(jdbc.queryForObject("SELECT run_id FROM knowledge_conversation WHERE id=?",String.class,id));
    }

    @Test void midStreamFailureMarksMessageFailedWithoutAssistantRow() throws Exception {
        long id=preparedConversation("stream_error").conversation();
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked") var handler=(BiConsumer<String,JsonNode>)invocation.getArgument(2);
            handler.accept("delta",json.readTree("{\"text\":\"部分\"}"));
            handler.accept("error",json.readTree("{\"message\":\"回答流中断，请重新提问。\"}"));
            return null;
        }).when(ai).stream(org.mockito.ArgumentMatchers.eq("/internal/knowledge/ask/stream"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());

        String body=stream(id,"问题");
        assertTrue(body.contains("event: error")&&body.contains("回答流中断"),body);
        var detail=call("GET","/api/conversations/"+id,null,memberTokenOf(id)).path("data");
        assertEquals("FAILED",detail.path("messages").get(0).path("status").asText());
        assertEquals(1,detail.path("messages").size());
    }

    @Test void scopeChangeDuringStreamRejectsDoneFrameAndPersistsNothing() throws Exception {
        var prepared=preparedConversation("stream_scope");
        long id=prepared.conversation();
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked") var handler=(BiConsumer<String,JsonNode>)invocation.getArgument(2);
            handler.accept("delta",json.readTree("{\"text\":\"预览\"}"));
            jdbc.update("UPDATE book SET index_status='FAILED' WHERE id=?",prepared.book());
            handler.accept("done",json.readTree("{\"answer\":\"**结论：**过期结果。\",\"sources\":[],\"mode\":\"hybrid\",\"rerankMode\":\"rrf\",\"context\":{}}"));
            return null;
        }).when(ai).stream(org.mockito.ArgumentMatchers.eq("/internal/knowledge/ask/stream"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());

        String body=stream(id,"问题");
        assertTrue(body.contains("event: error")&&body.contains("变化"),body);
        assertFalse(body.contains("过期结果"),body);
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM knowledge_message WHERE conversation_id=? AND role='user'",String.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_message WHERE conversation_id=? AND role='assistant'",Integer.class,id));
    }

    @Test void busyConversationStillAnswersWithJsonEnvelope() throws Exception {
        long id=preparedConversation("stream_busy").conversation();
        jdbc.update("UPDATE knowledge_conversation SET run_id='running' WHERE id=?",id);
        var result=call("POST","/api/conversations/"+id+"/messages/stream",Map.of("question","问题"),memberTokenOf(id));
        assertEquals(409,result.path("code").asInt());
        assertEquals("该对话已有问题正在处理",result.path("message").asText());
    }
}
