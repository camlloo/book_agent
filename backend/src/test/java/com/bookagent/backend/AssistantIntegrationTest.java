package com.bookagent.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import com.bookagent.backend.service.AiClient;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class AssistantIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AiClient ai;
    private String token;

    private JsonNode call(String method, String path, Object body) throws Exception {
        if (token == null) token = json.readTree(mvc.perform(post("/api/auth/login").contentType("application/json")
            .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
            .andReturn().getResponse().getContentAsString()).path("data").path("tokenValue").asText();
        var request = request(org.springframework.http.HttpMethod.valueOf(method), path).header("Authorization", token);
        if (body != null) request.contentType("application/json").content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString());
    }

    @Test void preferencesAndAgentStepsPersistWithinTheSignedInSpace() throws Exception {
        assertEquals("", call("GET", "/api/assistant/memory", null).path("data").asText());
        assertEquals(200, call("PUT", "/api/assistant/memory", java.util.Map.of("preferences", "用简体中文，先讲结论" )).path("code").asInt());
        assertEquals("用简体中文，先讲结论", call("GET", "/api/assistant/memory", null).path("data").asText());

        long bookId = call("POST", "/api/books", java.util.Map.of("title", "Agent 测试知识", "content", "Python 使用 Decimal 进行金额计算。" )).path("data").path("id").asLong();
        jdbc.update("UPDATE book SET index_status='READY',indexed_revision=revision WHERE id=?", bookId);
        when(ai.call(eq("/internal/assistant"), any())).thenReturn(json.readTree("{\"title\":\"分析知识\",\"steps\":[{\"tool\":\"search\",\"title\":\"查找依据\",\"instruction\":\"Decimal 金额计算\"}]}"));
        var planned = call("POST", "/api/assistant/plans", java.util.Map.of("goal", "了解金额计算", "bookIds", java.util.List.of(bookId)));
        assertEquals(200, planned.path("code").asInt(), planned.toString());
        long planId = planned.path("data").path("id").asLong();
        long stepId = planned.path("data").path("steps").get(0).path("id").asLong();
        when(ai.call(eq("/internal/knowledge/search"), any())).thenReturn(json.readTree("{\"results\":[{\"bookId\":"+bookId+",\"bookTitle\":\"Agent 测试知识\",\"sourceName\":\"正文\",\"excerpt\":\"Decimal 金额计算\",\"chunkId\":\"test\"}],\"mode\":\"hybrid\",\"rerankMode\":\"rrf\"}"));
        var executed = call("POST", "/api/assistant/plans/" + planId + "/steps/" + stepId + "/run", java.util.Map.of());
        assertEquals(200, executed.path("code").asInt(), executed.toString());
        assertEquals("SUCCEEDED", executed.path("data").path("steps").get(0).path("status").asText());
        assertEquals("COMPLETED", executed.path("data").path("status").asText());
    }

    @Test void legacyCompareStepRoutesTranslationDraftToProofreadAndCannotRunOutOfOrder() throws Exception {
        long bookId = call("POST", "/api/books", java.util.Map.of("title", "Translation source", "content", "Python's simple syntax emphasizes readability." )).path("data").path("id").asLong();
        jdbc.update("UPDATE book SET index_status='READY',indexed_revision=revision WHERE id=?", bookId);
        when(ai.call(eq("/internal/assistant"), any()))
            .thenReturn(json.readTree("""
                {"title":"翻译校对计划","steps":[
                  {"tool":"search","title":"检索原文","instruction":"Python readability"},
                  {"tool":"draft","title":"翻译初稿","instruction":"翻译原文"},
                  {"tool":"compare","title":"校对译文","instruction":"对照原文与译文，修正漏译和误译"}
                ]}
                """))
            .thenReturn(json.readTree("""
                {"content":"Python 的语法简单，强调可读性。","findings":["将术语调整为更自然的中文"],"sources":[]}
                """));
        var planned = call("POST", "/api/assistant/plans", java.util.Map.of("goal", "翻译并校对 Python 介绍", "bookIds", java.util.List.of(bookId)));
        assertEquals(200, planned.path("code").asInt(), planned.toString());
        long planId = planned.path("data").path("id").asLong();
        var steps = planned.path("data").path("steps");
        long searchId = steps.get(0).path("id").asLong();
        long draftId = steps.get(1).path("id").asLong();
        long compareId = steps.get(2).path("id").asLong();

        var outOfOrder = call("POST", "/api/assistant/plans/" + planId + "/steps/" + compareId + "/run", java.util.Map.of());
        assertEquals(409, outOfOrder.path("code").asInt(), outOfOrder.toString());

        String source = json.writeValueAsString(java.util.Map.of("bookId", bookId, "bookTitle", "Translation source", "sourceName", "Book 正文",
            "fileId", 17, "page", 2, "excerpt", "Python's simple syntax emphasizes readability."));
        jdbc.update("UPDATE assistant_plan_step SET status='SUCCEEDED',result_json=? WHERE id=?", "{\"results\":[" + source + "]}", searchId);
        jdbc.update("UPDATE assistant_plan_step SET status='SUCCEEDED',result_json=? WHERE id=?", "{\"content\":\"Python 的语法简单，强调阅读性。\"}", draftId);

        var compared = call("POST", "/api/assistant/plans/" + planId + "/steps/" + compareId + "/run", java.util.Map.of());
        assertEquals(200, compared.path("code").asInt(), compared.toString());
        var result = compared.path("data").path("steps").get(2).path("result");
        assertEquals("Python 的语法简单，强调可读性。", result.path("content").asText());
        assertEquals("SUCCEEDED", compared.path("data").path("steps").get(2).path("status").asText());

        ArgumentCaptor<java.util.Map<String, Object>> payloads = ArgumentCaptor.forClass(java.util.Map.class);
        verify(ai, times(2)).call(eq("/internal/assistant"), payloads.capture());
        var comparison = payloads.getAllValues().get(1);
        assertEquals("proofread", comparison.get("operation"));
        assertEquals("Python 的语法简单，强调阅读性。", comparison.get("content"));
        assertTrue(comparison.get("sources").toString().contains("Python's simple syntax emphasizes readability."));
        assertTrue(comparison.get("sources").toString().contains("fileId"));
    }

    @Test void summarizeDraftAndProofreadReuseTheOriginalSearchEvidence() throws Exception {
        long bookId = call("POST", "/api/books", java.util.Map.of("title", "Python source", "content", "Python's simple syntax emphasizes readability." )).path("data").path("id").asLong();
        jdbc.update("UPDATE book SET index_status='READY',indexed_revision=revision WHERE id=?", bookId);
        String source = "{\"bookId\":"+bookId+",\"bookTitle\":\"Python source\",\"sourceName\":\"python.org\",\"fileId\":17,\"page\":2,\"excerpt\":\"Python's simple syntax emphasizes readability.\"}";
        when(ai.call(eq("/internal/assistant"), any()))
            .thenReturn(json.readTree("""
                {"title":"Python 翻译","steps":[
                  {"tool":"search","title":"检索原文","instruction":"Python simple syntax readability"},
                  {"tool":"summarize","title":"梳理语义","instruction":"提炼作者核心观点和关键术语"},
                  {"tool":"draft","title":"生成译稿","instruction":"翻译成简体中文，保留原文结构"},
                  {"tool":"proofread","title":"校对译稿","instruction":"逐句对照英文原文，修正漏译和误译"}
                ]}
                """))
            .thenReturn(json.readTree("{\"content\":\"核心观点：Python 注重可读性。\",\"sources\":["+source+"]}"))
            .thenReturn(json.readTree("{\"content\":\"Python 语法简洁，强调可读性。\",\"sources\":["+source+"]}"))
            .thenReturn(json.readTree("{\"content\":\"Python 语法简洁易读。\",\"findings\":[\"合并重复表达\"],\"sources\":["+source+"]}"));
        when(ai.call(eq("/internal/knowledge/search"), any())).thenReturn(json.readTree("{\"results\":["+source+"]}"));

        var planned = call("POST", "/api/assistant/plans", java.util.Map.of("goal", "将 Python.org 文章翻译成中文", "bookIds", java.util.List.of(bookId)));
        long planId = planned.path("data").path("id").asLong();
        var steps = planned.path("data").path("steps");
        for (int i = 0; i < steps.size(); i++) {
            long stepId = steps.get(i).path("id").asLong();
            var executed = call("POST", "/api/assistant/plans/" + planId + "/steps/" + stepId + "/run", java.util.Map.of());
            assertEquals(200, executed.path("code").asInt(), executed.toString());
            assertEquals("SUCCEEDED", executed.path("data").path("steps").get(i).path("status").asText());
        }

        ArgumentCaptor<java.util.Map<String, Object>> payloads = ArgumentCaptor.forClass(java.util.Map.class);
        verify(ai, times(4)).call(eq("/internal/assistant"), payloads.capture());
        var calls = payloads.getAllValues();
        assertEquals("plan", calls.get(0).get("operation"));
        assertEquals("summarize", calls.get(1).get("operation"));
        assertTrue(calls.get(1).get("sources").toString().contains("Python's simple syntax emphasizes readability."));
        assertEquals("compose", calls.get(2).get("operation"));
        assertEquals("核心观点：Python 注重可读性。", calls.get(2).get("content"));
        assertTrue(calls.get(2).get("sources").toString().contains("Python's simple syntax emphasizes readability."));
        assertEquals("proofread", calls.get(3).get("operation"));
        assertEquals("Python 语法简洁，强调可读性。", calls.get(3).get("content"));
        var stepMemory = (com.fasterxml.jackson.databind.node.ArrayNode) calls.get(3).get("step_memory");
        assertEquals(3, stepMemory.size());
        assertEquals("search", stepMemory.get(0).path("tool").asText());
        assertEquals("summarize", stepMemory.get(1).path("tool").asText());
        assertTrue(stepMemory.get(1).path("output").asText().contains("核心观点"));
        assertEquals("draft", stepMemory.get(2).path("tool").asText());
        assertTrue(calls.get(3).get("sources").toString().contains("Python's simple syntax emphasizes readability."));
        verify(ai, times(1)).call(eq("/internal/knowledge/search"), any());
    }

    @Test void editingAPlanStepInvalidatesThatStepAndLaterResultsButKeepsEarlierEvidence() throws Exception {
        long bookId = call("POST", "/api/books", java.util.Map.of("title", "Editable plan source", "content", "Source text." )).path("data").path("id").asLong();
        jdbc.update("UPDATE book SET index_status='READY',indexed_revision=revision WHERE id=?", bookId);
        when(ai.call(eq("/internal/assistant"), any())).thenReturn(json.readTree("""
            {"title":"可调整计划","steps":[
              {"tool":"search","title":"检索原文","instruction":"Source text"},
              {"tool":"summarize","title":"总结","instruction":"总结来源"},
              {"tool":"draft","title":"起草","instruction":"撰写草稿"}
            ]}
            """));
        var planned = call("POST", "/api/assistant/plans", java.util.Map.of("goal", "整理来源", "bookIds", java.util.List.of(bookId)));
        long planId = planned.path("data").path("id").asLong();
        var steps = planned.path("data").path("steps");
        long searchId = steps.get(0).path("id").asLong(), summarizeId = steps.get(1).path("id").asLong(), draftId = steps.get(2).path("id").asLong();
        jdbc.update("UPDATE assistant_plan_step SET status='SUCCEEDED',result_json=? WHERE id=?", "{\"results\":[]}", searchId);
        jdbc.update("UPDATE assistant_plan_step SET status='SUCCEEDED',result_json=? WHERE id=?", "{\"content\":\"旧摘要\"}", summarizeId);
        jdbc.update("UPDATE assistant_plan_step SET status='SUCCEEDED',result_json=? WHERE id=?", "{\"content\":\"旧草稿\"}", draftId);
        jdbc.update("UPDATE assistant_plan SET status='COMPLETED' WHERE id=?", planId);

        var updated = call("PUT", "/api/assistant/plans/" + planId + "/steps/" + summarizeId,
            java.util.Map.of("tool", "draft", "title", "重点摘要", "instruction", "重点提取定义和关键事实"));
        assertEquals(200, updated.path("code").asInt(), updated.toString());
        assertEquals("SUCCEEDED", updated.path("data").path("steps").get(0).path("status").asText());
        assertEquals("PLANNED", updated.path("data").path("steps").get(1).path("status").asText());
        assertEquals("draft", updated.path("data").path("steps").get(1).path("tool").asText());
        assertEquals("重点摘要", updated.path("data").path("steps").get(1).path("title").asText());
        assertEquals("重点提取定义和关键事实", updated.path("data").path("steps").get(1).path("instruction").asText());
        assertTrue(updated.path("data").path("steps").get(1).path("result").isMissingNode() || updated.path("data").path("steps").get(1).path("result").isNull());
        assertEquals("PLANNED", updated.path("data").path("steps").get(2).path("status").asText());
        assertEquals("PLANNED", updated.path("data").path("status").asText());

        var extended = call("POST", "/api/assistant/plans/" + planId + "/steps", java.util.Map.of(
            "tool", "proofread", "title", "对照原文校对", "instruction", "逐句核对译稿与来源"));
        assertEquals(200, extended.path("code").asInt(), extended.toString());
        assertEquals(4, extended.path("data").path("steps").size());
        assertEquals("proofread", extended.path("data").path("steps").get(3).path("tool").asText());
        assertEquals("PLANNED", extended.path("data").path("steps").get(3).path("status").asText());
    }

    @Test void customPlansCanInsertMoveAndRemoveStepsWithoutLosingEarlierResults() throws Exception {
        long bookId = call("POST", "/api/books", java.util.Map.of("title", "Custom plan source", "content", "Python source text." )).path("data").path("id").asLong();
        jdbc.update("UPDATE book SET index_status='READY',indexed_revision=revision WHERE id=?", bookId);
        var requestedSteps = java.util.List.of(
            java.util.Map.of("tool", "search", "title", "检索原文", "instruction", "Python source text"),
            java.util.Map.of("tool", "summarize", "title", "整理要点", "instruction", "提取核心要点"),
            java.util.Map.of("tool", "draft", "title", "生成译稿", "instruction", "翻译成中文"),
            java.util.Map.of("tool", "proofread", "title", "校对译稿", "instruction", "对照原文检查漏译")
        );
        var created = call("POST", "/api/assistant/plans", java.util.Map.of("goal", "翻译并校对 Python 文章", "bookIds", java.util.List.of(bookId), "steps", requestedSteps));
        assertEquals(200, created.path("code").asInt(), created.toString());
        verifyNoInteractions(ai);
        long planId = created.path("data").path("id").asLong();
        var initial = created.path("data").path("steps");
        long searchId = initial.get(0).path("id").asLong();
        long summarizeId = initial.get(1).path("id").asLong();
        long draftId = initial.get(2).path("id").asLong();
        long proofreadId = initial.get(3).path("id").asLong();
        for (var item : initial) jdbc.update("UPDATE assistant_plan_step SET status='SUCCEEDED',result_json=? WHERE id=?", "{\"content\":\"saved\"}", item.path("id").asLong());

        var invalidMove = call("PUT", "/api/assistant/plans/" + planId + "/steps/" + proofreadId + "/position", java.util.Map.of("position", 2));
        assertEquals(400, invalidMove.path("code").asInt(), invalidMove.toString());

        var inserted = call("POST", "/api/assistant/plans/" + planId + "/steps", java.util.Map.of(
            "tool", "summarize", "title", "翻译术语表", "instruction", "列出原文中的术语", "afterStepId", searchId));
        assertEquals(200, inserted.path("code").asInt(), inserted.toString());
        var withInsert = inserted.path("data").path("steps");
        assertEquals(5, withInsert.size());
        assertEquals(searchId, withInsert.get(0).path("id").asLong());
        assertEquals("SUCCEEDED", withInsert.get(0).path("status").asText());
        assertEquals("PLANNED", withInsert.get(1).path("status").asText());
        assertEquals(2, withInsert.get(1).path("stepno").asInt());
        long insertedId = withInsert.get(1).path("id").asLong();

        var moved = call("PUT", "/api/assistant/plans/" + planId + "/steps/" + summarizeId + "/position", java.util.Map.of("position", 2));
        assertEquals(200, moved.path("code").asInt(), moved.toString());
        var afterMove = moved.path("data").path("steps");
        assertEquals(summarizeId, afterMove.get(1).path("id").asLong());
        assertEquals("SUCCEEDED", afterMove.get(0).path("status").asText());
        assertEquals("PLANNED", afterMove.get(1).path("status").asText());

        var removed = call("DELETE", "/api/assistant/plans/" + planId + "/steps/" + insertedId, null);
        assertEquals(200, removed.path("code").asInt(), removed.toString());
        var finalSteps = removed.path("data").path("steps");
        assertEquals(4, finalSteps.size());
        assertEquals("search", finalSteps.get(0).path("tool").asText());
        assertEquals("summarize", finalSteps.get(1).path("tool").asText());
        assertEquals("draft", finalSteps.get(2).path("tool").asText());
        assertEquals("proofread", finalSteps.get(3).path("tool").asText());
        assertEquals("SUCCEEDED", finalSteps.get(0).path("status").asText());
        assertEquals("PLANNED", finalSteps.get(1).path("status").asText());
        assertEquals("PLANNED", finalSteps.get(2).path("status").asText());
        assertEquals("PLANNED", finalSteps.get(3).path("status").asText());

        var invalidCreate = call("POST", "/api/assistant/plans", java.util.Map.of("goal", "无效顺序", "bookIds", java.util.List.of(bookId),
            "steps", java.util.List.of(java.util.Map.of("tool", "draft", "title", "起草", "instruction", "写内容"))));
        assertEquals(400, invalidCreate.path("code").asInt(), invalidCreate.toString());
    }

    @Test void resettingFromAStaleStepKeepsEarlierSearchEvidenceForRerun() throws Exception {
        long bookId = call("POST", "/api/books", java.util.Map.of("title", "Replay source", "content", "Python source text." )).path("data").path("id").asLong();
        jdbc.update("UPDATE book SET index_status='READY',indexed_revision=revision WHERE id=?", bookId);
        when(ai.call(eq("/internal/assistant"), any())).thenReturn(json.readTree("""
            {"title":"翻译计划","steps":[
              {"tool":"search","title":"检索原文","instruction":"Python source text"},
              {"tool":"draft","title":"翻译草稿","instruction":"翻译成中文"},
              {"tool":"proofread","title":"校对","instruction":"逐句校对"}
            ]}
            """));
        var created = call("POST", "/api/assistant/plans", java.util.Map.of("goal", "翻译 Python 文章", "bookIds", java.util.List.of(bookId)));
        long planId = created.path("data").path("id").asLong();
        var steps = created.path("data").path("steps");
        long searchId = steps.get(0).path("id").asLong();
        long draftId = steps.get(1).path("id").asLong();
        long proofreadId = steps.get(2).path("id").asLong();
        String source = "{\"bookId\":" + bookId + ",\"bookTitle\":\"Replay source\",\"sourceName\":\"Book 正文\",\"excerpt\":\"Python source text.\"}";
        jdbc.update("UPDATE assistant_plan_step SET status='SUCCEEDED',result_json=? WHERE id=?", "{\"results\":[" + source + "]}", searchId);
        jdbc.update("UPDATE assistant_plan_step SET status='SUCCEEDED',result_json=? WHERE id=?", "{\"content\":\"旧错误译稿\"}", draftId);
        jdbc.update("UPDATE assistant_plan_step SET status='SUCCEEDED',result_json=? WHERE id=?", "{\"content\":\"旧错误校对稿\"}", proofreadId);
        jdbc.update("UPDATE assistant_plan SET status='COMPLETED' WHERE id=?", planId);

        var reset = call("POST", "/api/assistant/plans/" + planId + "/steps/" + draftId + "/reset", java.util.Map.of());
        assertEquals(200, reset.path("code").asInt(), reset.toString());
        var refreshedSteps = reset.path("data").path("steps");
        assertEquals("SUCCEEDED", refreshedSteps.get(0).path("status").asText());
        assertEquals("Python source text.", refreshedSteps.get(0).path("result").path("results").get(0).path("excerpt").asText());
        assertEquals("PLANNED", refreshedSteps.get(1).path("status").asText());
        assertEquals("PLANNED", refreshedSteps.get(2).path("status").asText());
        assertEquals("PLANNED", reset.path("data").path("status").asText());
    }
}
