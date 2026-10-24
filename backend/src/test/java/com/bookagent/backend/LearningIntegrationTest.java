package com.bookagent.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bookagent.backend.service.AiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class LearningIntegrationTest {
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

    @Test void roadmapProgressWeakAreasAndReportPersistWithinTheSignedInSpace() throws Exception {
        long bookId = call("POST", "/api/books", Map.of("title", "Python 学习资料", "content", "Python 的函数可以接收参数并返回结果。"))
            .path("data").path("id").asLong();
        jdbc.update("UPDATE book SET index_status='READY',indexed_revision=revision WHERE id=?", bookId);
        String source = "{\"bookId\":" + bookId + ",\"bookTitle\":\"Python 学习资料\",\"sourceName\":\"正文\",\"page\":2,\"chunkId\":\"learning-test\",\"excerpt\":\"Python 的函数可以接收参数并返回结果。\"}";
        when(ai.call(eq("/internal/knowledge/search"), any())).thenReturn(json.readTree("{\"results\":[" + source + "]}"));
        when(ai.call(eq("/internal/assistant"), any()))
            .thenReturn(json.readTree("""
                {"title":"Python 函数入门","weeks":[{"focus":"函数基础","outcome":"能定义简单函数","sessions":[
                  {"title":"理解参数","activity":"阅读参数说明并口头复述","sourceNumbers":[1]},
                  {"title":"编写练习","activity":"写一个接收参数并返回结果的函数","sourceNumbers":[1]}
                ]}]}
                """))
            .thenReturn(json.readTree("""
                {"summary":"已完成一次函数学习。","estimatedLevel":"基础阶段，尚未经过正式测试。",
                 "strengths":["能复述参数用途"],"weakAreas":[{"topic":"函数调用","reason":"自评掌握度较低","practice":"增加独立练习"}],
                 "nextSteps":["复习函数调用"],"sources":[]}
                """));

        LocalDate start = LocalDate.of(2026, 10, 21);
        var created = call("POST", "/api/learning/plans", Map.of(
            "goal", "掌握 Python 函数", "bookIds", java.util.List.of(bookId), "currentLevel", "初学者", "selfConfidence", 2,
            "durationWeeks", 1, "sessionsPerWeek", 2, "minutesPerSession", 25, "startDate", start.toString(), "knownTopics", "了解变量"));
        assertEquals(200, created.path("code").asInt(), created.toString());
        long planId = created.path("data").path("id").asLong();
        assertEquals(2, created.path("data").path("weeks").get(0).path("sessions").size());
        assertEquals("Python 学习资料", created.path("data").path("weeks").get(0).path("sessions").get(0).path("sources").get(0).path("bookTitle").asText());
        assertEquals("ACTIVE", created.path("data").path("status").asText());

        String adminToken = token;
        call("POST", "/api/auth/register", Map.of("username", "learner_two", "password", "secure-pass-2", "nickname", "Other learner"));
        String otherToken = json.readTree(mvc.perform(post("/api/auth/login").contentType("application/json")
            .content("{\"username\":\"learner_two\",\"password\":\"secure-pass-2\"}"))
            .andReturn().getResponse().getContentAsString()).path("data").path("tokenValue").asText();
        token = otherToken;
        assertEquals(404, call("GET", "/api/learning/plans/" + planId, null).path("code").asInt());
        token = adminToken;

        long sessionId = created.path("data").path("weeks").get(0).path("sessions").get(0).path("id").asLong();
        var saved = call("PUT", "/api/learning/plans/" + planId + "/sessions/" + sessionId,
            Map.of("status", "COMPLETED", "confidence", 2, "reflection", "还不太熟悉函数调用"));
        assertEquals(200, saved.path("code").asInt(), saved.toString());
        assertEquals(50, saved.path("data").path("progress").path("percent").asInt());
        assertEquals(1, saved.path("data").path("weakAreas").size());
        assertEquals("函数基础", saved.path("data").path("weakAreas").get(0).path("topic").asText());

        var report = call("POST", "/api/learning/plans/" + planId + "/report", Map.of());
        assertEquals(200, report.path("code").asInt(), report.toString());
        assertEquals("已完成一次函数学习。", report.path("data").path("report").path("summary").asText());
        assertEquals(1, call("GET", "/api/learning/plans", null).path("data").size());
        var changedAfterReport = call("PUT", "/api/learning/plans/" + planId + "/sessions/" + sessionId,
            Map.of("status", "COMPLETED", "confidence", 4, "reflection", "已经可以解释函数参数"));
        assertTrue(changedAfterReport.path("data").path("report").isNull());
        verify(ai).call(eq("/internal/knowledge/search"), any());
        verify(ai, times(2)).call(eq("/internal/assistant"), any());
    }

    @Test void rejectsCheckInsWithoutAConfidenceScoreAndUnknownSessionIds() throws Exception {
        long bookId = call("POST", "/api/books", Map.of("title", "Confidence source", "content", "内容资料" )).path("data").path("id").asLong();
        jdbc.update("UPDATE book SET index_status='READY',indexed_revision=revision WHERE id=?", bookId);
        when(ai.call(eq("/internal/knowledge/search"), any())).thenReturn(json.readTree("{\"results\":[{\"bookId\":" + bookId + ",\"bookTitle\":\"Confidence source\",\"sourceName\":\"正文\",\"excerpt\":\"内容资料\"}]}"));
        when(ai.call(eq("/internal/assistant"), any())).thenReturn(json.readTree("""
            {"title":"学习计划","weeks":[{"focus":"入门","outcome":"了解内容","sessions":[{"title":"阅读资料","activity":"阅读并复述"}]}]}
            """));
        var created = call("POST", "/api/learning/plans", Map.of("goal", "熟悉知识", "bookIds", java.util.List.of(bookId),
            "currentLevel", "初学者", "selfConfidence", 3, "durationWeeks", 1, "sessionsPerWeek", 1, "minutesPerSession", 30));
        long planId = created.path("data").path("id").asLong();
        long sessionId = created.path("data").path("weeks").get(0).path("sessions").get(0).path("id").asLong();
        var missingConfidence = call("PUT", "/api/learning/plans/" + planId + "/sessions/" + sessionId,
            Map.of("status", "COMPLETED", "reflection", "完成"));
        assertEquals(400, missingConfidence.path("code").asInt());
        var missingSession = call("PUT", "/api/learning/plans/" + planId + "/sessions/999999",
            Map.of("status", "PLANNED", "reflection", ""));
        assertEquals(404, missingSession.path("code").asInt());
    }

    private JsonNode newLearningPlan() throws Exception {
        long bookId = call("POST", "/api/books", Map.of("title", "学习记录回归资料", "content", "Python 使用 return 返回函数结果。"))
            .path("data").path("id").asLong();
        jdbc.update("UPDATE book SET index_status='READY',indexed_revision=revision WHERE id=?", bookId);
        when(ai.call(eq("/internal/knowledge/search"), any())).thenReturn(json.readTree(
            "{\"results\":[{\"bookId\":" + bookId + ",\"bookTitle\":\"学习资料\",\"sourceName\":\"正文\",\"excerpt\":\"使用 return 返回函数结果\"}]}"));
        when(ai.call(eq("/internal/assistant"), any())).thenReturn(json.readTree("""
            {"title":"函数学习","weeks":[{"focus":"函数","outcome":"理解返回值","sessions":[
              {"title":"阅读","activity":"阅读函数资料","sourceNumbers":[1]}
            ]}]}
            """));
        var result = call("POST", "/api/learning/plans", Map.of("goal", "理解 Python 函数", "bookIds", java.util.List.of(bookId),
            "currentLevel", "初学者", "selfConfidence", 2, "durationWeeks", 1, "sessionsPerWeek", 1, "minutesPerSession", 20));
        assertEquals(200, result.path("code").asInt(), result.toString());
        return result.path("data");
    }

    @Test void reportRejectsLearningRecordsChangedWhileTheModelWasRunning() throws Exception {
        var plan = newLearningPlan();
        long id = plan.path("id").asLong(), sessionId = plan.path("weeks").get(0).path("sessions").get(0).path("id").asLong();
        call("PUT", "/api/learning/plans/" + id + "/sessions/" + sessionId,
            Map.of("status", "COMPLETED", "confidence", 2, "reflection", "最初还不熟练"));
        when(ai.call(eq("/internal/assistant"), any())).thenAnswer(invocation -> {
            // Another device saves a new check-in after the model received its snapshot.
            jdbc.update("UPDATE learning_session SET confidence=5,reflection='现在已经掌握' WHERE id=?", sessionId);
            jdbc.update("UPDATE learning_plan SET report_json=NULL WHERE id=?", id);
            return json.readTree("{\"summary\":\"基于旧的低掌握度生成的报告\",\"sources\":[]}");
        });
        var response = call("POST", "/api/learning/plans/" + id + "/report", Map.of());
        assertEquals(409, response.path("code").asInt(), response.toString());
        var latest = call("GET", "/api/learning/plans/" + id, null).path("data");
        assertTrue(latest.path("report").isNull());
        assertEquals(5, latest.path("weeks").get(0).path("sessions").get(0).path("confidence").asInt());
    }

    @Test void resetOrSkippedSessionsAreExcludedFromWeaknessAndReportEvidence() throws Exception {
        var plan = newLearningPlan();
        long id = plan.path("id").asLong(), sessionId = plan.path("weeks").get(0).path("sessions").get(0).path("id").asLong();
        call("PUT", "/api/learning/plans/" + id + "/sessions/" + sessionId,
            Map.of("status", "COMPLETED", "confidence", 2));
        for (String status : java.util.List.of("PLANNED", "SKIPPED")) {
            var changed = call("PUT", "/api/learning/plans/" + id + "/sessions/" + sessionId,
                Map.of("status", status, "confidence", 2)).path("data");
            assertEquals(0, changed.path("weakAreas").size(), changed.toString());
            assertEquals(0, changed.path("progress").path("needsReview").asInt());
            assertEquals(409, call("POST", "/api/learning/plans/" + id + "/report", Map.of()).path("code").asInt());
        }
    }
}
