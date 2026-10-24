package com.bookagent.backend.service;

import com.bookagent.backend.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.*;

/** User-scoped learning programs built from authorized, indexed Book evidence. */
@Service
public class LearningService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final KnowledgeAccess access;
    private final KnowledgeSearch knowledge;
    private final AiClient ai;
    private final TransactionTemplate transactions;

    public LearningService(JdbcTemplate jdbc, ObjectMapper json, KnowledgeAccess access,
                           KnowledgeSearch knowledge, AiClient ai, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.access = access;
        this.knowledge = knowledge;
        this.ai = ai;
        this.transactions = new TransactionTemplate(manager);
    }

    public record CreateRequest(String goal, List<Long> bookIds, String currentLevel, int selfConfidence,
                                int durationWeeks, int sessionsPerWeek, int minutesPerSession,
                                LocalDate startDate, String knownTopics) {}
    public record CheckIn(String status, Integer confidence, String reflection) {}
    private record SessionSeed(int week, int session, LocalDate dueDate, String focus, String title,
                               String activity, int minutes, String sources) {}
    private record PlanRow(long id, long userId, String goal, String title, String currentLevel,
                           int selfConfidence, int durationWeeks, int sessionsPerWeek, int minutesPerSession,
                           LocalDate startDate, String bookIdsJson, String scopeJson, String outlineJson,
                           String reportJson, String createdAt, String updatedAt) {}
    private record SessionRow(long id, int week, int session, LocalDate dueDate, String focus, String title,
                              String activity, int minutes, String sourcesJson, String status,
                              Integer confidence, String reflection, String completedAt) {}

    public Map<String, Object> create(CreateRequest form) {
        access.active();
        validate(form);
        LocalDate start = form.startDate() == null ? LocalDate.now() : form.startDate();
        List<Long> bookIds = form.bookIds().stream().distinct().toList();
        List<Map<String, Object>> scopes = access.scopes(false, bookIds);
        if (scopes.size() != bookIds.size()) throw new BusinessException(409, "所选 Book 不可访问或索引尚未就绪");

        String searchText = (form.goal() + " " + Objects.toString(form.knownTopics(), "")).trim();
        JsonNode retrieval = knowledge.run(new KnowledgeSearch.Query(searchText.substring(0, Math.min(500, searchText.length())),
            bookIds, false, "hybrid", "summary", 30, true, List.of()), false, List.of());
        ArrayNode sources = boundedSources(retrieval.path("results"), bookIds);
        if (sources.isEmpty()) throw new BusinessException(409, "所选 Book 中没有检索到可用于制定学习路线的内容，请调整目标或知识范围");

        JsonNode roadmap = ai.call("/internal/assistant", Map.of(
            "operation", "learning_roadmap", "goal", form.goal().trim(), "level", form.currentLevel(),
            "known_topics", Objects.toString(form.knownTopics(), "").trim(), "weeks", form.durationWeeks(),
            "sessions_per_week", form.sessionsPerWeek(), "minutes_per_session", form.minutesPerSession(),
            "book_titles", scopes.stream().map(s -> Objects.toString(s.get("title"), "Book")).toList(),
            "sources", sources, "memory", preferences()));

        if (!sameScopes(scopes, access.scopes(false, bookIds)))
            throw new BusinessException(409, "Book 权限、索引或内容在制定路线时发生变化，请重新生成");

        String title = text(roadmap.path("title"), form.goal().trim(), 128);
        JsonNode weeks = roadmap.path("weeks");
        if (!weeks.isArray() || weeks.size() != form.durationWeeks()) throw new BusinessException(503, "学习路线周数不完整，请重试");
        ObjectNode outline = json.createObjectNode().put("title", title);
        ArrayNode outlineWeeks = outline.putArray("weeks");
        List<SessionSeed> sessions = new ArrayList<>();
        for (int weekIndex = 0; weekIndex < weeks.size(); weekIndex++) {
            JsonNode week = weeks.get(weekIndex);
            JsonNode items = week.path("sessions");
            if (!items.isArray() || items.size() != form.sessionsPerWeek()) throw new BusinessException(503, "每周学习安排数量不完整，请重试");
            String focus = text(week.path("focus"), "第 " + (weekIndex + 1) + " 周", 180);
            ObjectNode outlineWeek = outlineWeeks.addObject();
            outlineWeek.put("week", weekIndex + 1).put("focus", focus).put("outcome", text(week.path("outcome"), "", 500));
            for (int sessionIndex = 0; sessionIndex < items.size(); sessionIndex++) {
                JsonNode item = items.get(sessionIndex);
                String sessionTitle = text(item.path("title"), "", 120);
                String activity = text(item.path("activity"), "", 1000);
                if (sessionTitle.isBlank() || activity.isBlank()) throw new BusinessException(503, "学习任务缺少标题或具体内容，请重试");
                ArrayNode sessionSources = json.createArrayNode();
                JsonNode refs = item.path("sourceNumbers");
                if (refs.isArray()) {
                    Set<Integer> unique = new HashSet<>();
                    for (JsonNode ref : refs) {
                        int number = ref.asInt(-1);
                        if (number > 0 && number <= sources.size() && unique.add(number)) sessionSources.add(sources.get(number - 1));
                    }
                }
                int offset = (weekIndex * 7) + (int) Math.round((double) sessionIndex * 7 / form.sessionsPerWeek());
                sessions.add(new SessionSeed(weekIndex + 1, sessionIndex + 1, start.plusDays(offset), focus,
                    sessionTitle, activity, form.minutesPerSession(), write(sessionSources)));
            }
        }

        String scopeJson = write(json.valueToTree(scopes));
        String bookIdsJson = write(json.valueToTree(bookIds));
        String outlineJson = write(outline);
        long planId = Objects.requireNonNull(transactions.execute(status -> {
            long id = insert("INSERT INTO learning_plan(user_id,goal,title,current_level,self_confidence,duration_weeks,sessions_per_week,minutes_per_session,start_date,book_ids_json,scope_json,outline_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                access.actor(), form.goal().trim(), title, form.currentLevel(), form.selfConfidence(), form.durationWeeks(),
                form.sessionsPerWeek(), form.minutesPerSession(), java.sql.Date.valueOf(start), bookIdsJson, scopeJson, outlineJson);
            for (SessionSeed session : sessions) {
                insert("INSERT INTO learning_session(plan_id,week_no,session_no,due_date,focus,title,activity,expected_minutes,source_json) VALUES(?,?,?,?,?,?,?,?,?)",
                    id, session.week(), session.session(), java.sql.Date.valueOf(session.dueDate()), session.focus(), session.title(),
                    session.activity(), session.minutes(), session.sources());
            }
            return id;
        }));
        return detail(planId);
    }

    private void validate(CreateRequest form) {
        if (form == null || form.goal() == null || form.goal().isBlank() || form.goal().length() > 1000)
            throw new BusinessException(400, "请填写不超过 1000 字的学习目标");
        if (form.bookIds() == null || form.bookIds().isEmpty() || form.bookIds().size() > 50 || form.bookIds().stream().anyMatch(Objects::isNull))
            throw new BusinessException(400, "请选择 1–50 本知识范围内的 Book");
        if (form.bookIds().stream().distinct().count() != form.bookIds().size()) throw new BusinessException(400, "Book 不能重复选择");
        if (!Set.of("初学者", "有一些基础", "可以独立实践", "熟练").contains(form.currentLevel())) throw new BusinessException(400, "学习起点不合法");
        if (form.selfConfidence() < 1 || form.selfConfidence() > 5) throw new BusinessException(400, "自评信心应为 1–5 分");
        if (form.durationWeeks() < 1 || form.durationWeeks() > 8) throw new BusinessException(400, "学习周期应为 1–8 周");
        if (form.sessionsPerWeek() < 1 || form.sessionsPerWeek() > 7) throw new BusinessException(400, "每周学习次数应为 1–7 次");
        if (form.minutesPerSession() < 10 || form.minutesPerSession() > 180) throw new BusinessException(400, "每次学习时长应为 10–180 分钟");
        if (form.startDate() != null && form.startDate().isAfter(LocalDate.now().plusDays(365))) throw new BusinessException(400, "开始日期不能超过一年后");
        if (form.knownTopics() != null && form.knownTopics().length() > 1000) throw new BusinessException(400, "已掌握内容最多 1000 字");
    }

    public List<Map<String, Object>> list() {
        access.active();
        return jdbc.query("SELECT p.id,p.goal,p.title,p.current_level,p.self_confidence,p.duration_weeks,p.start_date,p.created_at,COUNT(s.id) AS total_count,SUM(CASE WHEN s.status='COMPLETED' THEN 1 ELSE 0 END) AS completed_count FROM learning_plan p LEFT JOIN learning_session s ON s.plan_id=p.id WHERE p.user_id=? GROUP BY p.id ORDER BY p.updated_at DESC,p.id DESC LIMIT 100",
            (rs, row) -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", rs.getLong("id")); item.put("title", rs.getString("title")); item.put("goal", rs.getString("goal"));
                item.put("currentLevel", rs.getString("current_level")); item.put("selfConfidence", rs.getInt("self_confidence"));
                item.put("durationWeeks", rs.getInt("duration_weeks")); item.put("startDate", rs.getDate("start_date").toLocalDate().toString());
                int total = rs.getInt("total_count"), complete = rs.getInt("completed_count");
                item.put("totalSessions", total); item.put("completedSessions", complete); item.put("progress", total == 0 ? 0 : Math.round(100f * complete / total));
                item.put("createdAt", Objects.toString(rs.getTimestamp("created_at"), "")); return item;
            }, access.actor());
    }

    public Map<String, Object> detail(long id) {
        access.active();
        PlanRow plan = planRow(id);
        List<SessionRow> sessions = sessions(id);
        return view(plan, sessions);
    }

    @Transactional
    public Map<String, Object> checkIn(long planId, long sessionId, CheckIn form) {
        access.active();
        if (form == null || !Set.of("PLANNED", "COMPLETED", "NEEDS_REVIEW", "SKIPPED").contains(form.status()))
            throw new BusinessException(400, "学习状态不合法");
        if (form.confidence() != null && (form.confidence() < 1 || form.confidence() > 5)) throw new BusinessException(400, "掌握程度应为 1–5 分");
        if (("COMPLETED".equals(form.status()) || "NEEDS_REVIEW".equals(form.status())) && form.confidence() == null)
            throw new BusinessException(400, "完成或标记复习时，请先记录 1–5 分的掌握程度");
        String reflection = Objects.toString(form.reflection(), "").trim();
        if (reflection.length() > 2000) throw new BusinessException(400, "学习反思最多 2000 字");
        lockPlan(planId);
        planRow(planId);
        int changed = jdbc.update("UPDATE learning_session SET status=?,confidence=?,reflection=?,completed_at=CASE WHEN ?='COMPLETED' THEN COALESCE(completed_at,CURRENT_TIMESTAMP) ELSE NULL END WHERE id=? AND plan_id=?",
            form.status(), form.confidence(), reflection, form.status(), sessionId, planId);
        if (changed != 1) throw new BusinessException(404, "学习任务不存在");
        jdbc.update("UPDATE learning_plan SET report_json=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?", planId, access.actor());
        return detail(planId);
    }

    public Map<String, Object> report(long id) {
        access.active();
        PlanRow plan = planRow(id);
        List<SessionRow> sessions = sessions(id);
        List<SessionRow> completed = sessions.stream().filter(s -> "COMPLETED".equals(s.status()) || "NEEDS_REVIEW".equals(s.status())).toList();
        if (completed.isEmpty()) throw new BusinessException(409, "请先完成学习或标记需要复习，并记录掌握程度，再生成阶段报告");
        List<Long> ids = parseLongs(plan.bookIdsJson());
        List<Map<String, Object>> currentScopes = access.scopes(false, ids);
        List<Map<String, Object>> originalScopes = parseScope(plan.scopeJson());
        if (!sameScopes(originalScopes, currentScopes)) throw new BusinessException(409, "学习范围中的 Book 内容或权限已变化，请检查路线后再生成报告");

        ArrayNode sources = json.createArrayNode();
        Set<String> unique = new HashSet<>();
        for (SessionRow session : completed) {
            JsonNode items = parse(session.sourcesJson()).path("items");
            if (!items.isArray()) items = parse(session.sourcesJson());
            if (items.isArray()) for (JsonNode source : items) {
                String key = source.path("bookId").asText() + "|" + source.path("sourceName").asText() + "|" + source.path("page").asText();
                if (source.path("bookId").asLong() > 0 && unique.add(key)) sources.add(source);
                if (sources.size() >= 30) break;
            }
            if (sources.size() >= 30) break;
        }

        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("plannedSessions", sessions.size()); activity.put("completedSessions", sessions.stream().filter(s -> "COMPLETED".equals(s.status())).count());
        activity.put("needsReviewSessions", sessions.stream().filter(this::needsReview).map(s -> Map.of(
            "week", s.week(), "focus", s.focus(), "title", s.title(), "confidence", Objects.toString(s.confidence(), "未评分"), "reflection", Objects.toString(s.reflection(), ""))).toList());
        activity.put("recentReflections", completed.stream().filter(s -> !Objects.toString(s.reflection(), "").isBlank()).limit(15).map(s -> Map.of("week", s.week(), "title", s.title(), "reflection", s.reflection(), "confidence", Objects.toString(s.confidence(), ""))).toList());
        JsonNode result = ai.call("/internal/assistant", Map.of("operation", "learning_report", "goal", plan.goal(), "level", plan.currentLevel() + "（起点信心 " + plan.selfConfidence() + "/5）",
            "content", write(json.valueToTree(activity)), "sources", sources, "memory", preferences()));
        if (!sameScopes(originalScopes, access.scopes(false, ids))) throw new BusinessException(409, "生成报告时学习范围发生变化，请重试");
        // Model calls stay outside the transaction. Serialize the final save with check-ins
        // so another device cannot make a report stale while it is being generated.
        transactions.executeWithoutResult(status -> {
            lockPlan(id);
            if (!sessions.equals(sessions(id))) throw new BusinessException(409, "学习记录在生成报告期间已变化，请重新生成");
            jdbc.update("UPDATE learning_plan SET report_json=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?", write(result), id, access.actor());
        });
        return detail(id);
    }

    public void delete(long id) {
        access.active();
        planRow(id);
        jdbc.update("DELETE FROM learning_plan WHERE id=? AND user_id=?", id, access.actor());
    }

    private Map<String, Object> view(PlanRow plan, List<SessionRow> sessions) {
        JsonNode outline = parse(plan.outlineJson());
        JsonNode outlineWeeks = outline.path("weeks");
        Map<Integer, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (SessionRow session : sessions) grouped.computeIfAbsent(session.week(), key -> new ArrayList<>()).add(sessionView(session));
        List<Map<String, Object>> weeks = new ArrayList<>();
        for (int index = 0; index < plan.durationWeeks(); index++) {
            JsonNode w = outlineWeeks.path(index);
            Map<String, Object> week = new LinkedHashMap<>();
            week.put("week", index + 1); week.put("focus", w.path("focus").asText("第 " + (index + 1) + " 周"));
            week.put("outcome", w.path("outcome").asText("")); week.put("sessions", grouped.getOrDefault(index + 1, List.of())); weeks.add(week);
        }
        long completed = sessions.stream().filter(s -> "COMPLETED".equals(s.status())).count();
        boolean done = !sessions.isEmpty() && sessions.stream().allMatch(s -> "COMPLETED".equals(s.status()));
        Map<String, Object> progress = Map.of("total", sessions.size(), "completed", completed,
            "needsReview", sessions.stream().filter(this::needsReview).count(),
            "percent", sessions.isEmpty() ? 0 : Math.round(100f * completed / sessions.size()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", plan.id()); result.put("title", plan.title()); result.put("goal", plan.goal());
        result.put("currentLevel", plan.currentLevel()); result.put("selfConfidence", plan.selfConfidence());
        result.put("durationWeeks", plan.durationWeeks()); result.put("sessionsPerWeek", plan.sessionsPerWeek());
        result.put("minutesPerSession", plan.minutesPerSession()); result.put("startDate", plan.startDate().toString());
        result.put("bookTitles", bookTitles(plan.scopeJson())); result.put("status", done ? "COMPLETED" : "ACTIVE");
        result.put("progress", progress); result.put("weakAreas", weakAreas(sessions)); result.put("weeks", weeks);
        result.put("report", plan.reportJson() == null ? null : parse(plan.reportJson()));
        result.put("createdAt", plan.createdAt()); result.put("updatedAt", plan.updatedAt());
        return result;
    }

    private Map<String, Object> sessionView(SessionRow session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", session.id()); result.put("sessionNo", session.session()); result.put("dueDate", session.dueDate().toString());
        result.put("focus", session.focus()); result.put("title", session.title()); result.put("activity", session.activity());
        result.put("minutes", session.minutes()); result.put("sources", parse(session.sourcesJson())); result.put("status", session.status());
        result.put("confidence", session.confidence()); result.put("reflection", session.reflection()); result.put("completedAt", session.completedAt());
        return result;
    }

    private List<Map<String, Object>> weakAreas(List<SessionRow> sessions) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (SessionRow session : sessions) {
            if (!needsReview(session)) continue;
            String key = session.focus().isBlank() ? session.title() : session.focus();
            Map<String, Object> area = grouped.computeIfAbsent(key, ignored -> {
                Map<String, Object> item = new LinkedHashMap<>(); item.put("topic", key); item.put("count", 0); item.put("sessions", new ArrayList<String>()); return item;
            });
            area.put("count", ((Number) area.get("count")).intValue() + 1);
            @SuppressWarnings("unchecked") List<String> titles = (List<String>) area.get("sessions");
            if (!titles.contains(session.title())) titles.add(session.title());
        }
        return grouped.values().stream().limit(12).toList();
    }

    private boolean needsReview(SessionRow session) {
        return "NEEDS_REVIEW".equals(session.status()) || ("COMPLETED".equals(session.status())
            && session.confidence() != null && session.confidence() <= 2);
    }

    private void lockPlan(long id) {
        if (jdbc.queryForList("SELECT id FROM learning_plan WHERE id=? AND user_id=? FOR UPDATE", id, access.actor()).isEmpty())
            throw new BusinessException(404, "学习计划不存在");
    }

    private List<SessionRow> sessions(long planId) {
        return jdbc.query("SELECT id,week_no,session_no,due_date,focus,title,activity,expected_minutes,source_json,status,confidence,reflection,completed_at FROM learning_session WHERE plan_id=? ORDER BY week_no,session_no",
            (rs, row) -> new SessionRow(rs.getLong("id"), rs.getInt("week_no"), rs.getInt("session_no"), rs.getDate("due_date").toLocalDate(),
                rs.getString("focus"), rs.getString("title"), rs.getString("activity"), rs.getInt("expected_minutes"), rs.getString("source_json"),
                rs.getString("status"), (Integer) rs.getObject("confidence"), rs.getString("reflection"), Objects.toString(rs.getTimestamp("completed_at"), null)), planId);
    }

    private PlanRow planRow(long id) {
        try {
            return jdbc.queryForObject("SELECT id,user_id,goal,title,current_level,self_confidence,duration_weeks,sessions_per_week,minutes_per_session,start_date,book_ids_json,scope_json,outline_json,report_json,created_at,updated_at FROM learning_plan WHERE id=? AND user_id=?",
                (rs, row) -> new PlanRow(rs.getLong("id"), rs.getLong("user_id"), rs.getString("goal"), rs.getString("title"), rs.getString("current_level"),
                    rs.getInt("self_confidence"), rs.getInt("duration_weeks"), rs.getInt("sessions_per_week"), rs.getInt("minutes_per_session"),
                    rs.getDate("start_date").toLocalDate(), rs.getString("book_ids_json"), rs.getString("scope_json"), rs.getString("outline_json"),
                    rs.getString("report_json"), Objects.toString(rs.getTimestamp("created_at"), ""), Objects.toString(rs.getTimestamp("updated_at"), "")), id, access.actor());
        } catch (org.springframework.dao.EmptyResultDataAccessException e) { throw new BusinessException(404, "学习计划不存在"); }
    }

    private List<Long> parseLongs(String raw) {
        JsonNode node = parse(raw); List<Long> ids = new ArrayList<>();
        if (node.isArray()) node.forEach(value -> { if (value.canConvertToLong() && value.asLong() > 0) ids.add(value.asLong()); });
        return ids;
    }

    private List<Map<String, Object>> parseScope(String raw) {
        try { return json.readerForListOf(Map.class).readValue(raw); }
        catch (Exception e) { throw new BusinessException(409, "学习计划知识范围无法读取"); }
    }

    private boolean sameScopes(List<Map<String, Object>> before, List<Map<String, Object>> after) {
        if (before.size() != after.size()) return false;
        for (int i = 0; i < before.size(); i++) {
            Map<String, Object> a = before.get(i), b = after.get(i);
            if (number(a.get("book_id")) != number(b.get("book_id")) || number(a.get("revision")) != number(b.get("revision"))
                || !Objects.equals(Objects.toString(a.get("title"), ""), Objects.toString(b.get("title"), ""))) return false;
        }
        return true;
    }

    private long number(Object value) { return value instanceof Number n ? n.longValue() : Long.parseLong(Objects.toString(value, "0")); }

    private ArrayNode boundedSources(JsonNode input, List<Long> bookIds) {
        ArrayNode out = json.createArrayNode(); Set<String> keys = new HashSet<>();
        if (!input.isArray()) return out;
        for (JsonNode source : input) {
            if (!bookIds.contains(source.path("bookId").asLong()) || source.path("excerpt").asText().isBlank()) continue;
            String key = source.path("bookId").asText() + "|" + source.path("sourceName").asText() + "|" + source.path("page").asText() + "|" + source.path("chunkId").asText();
            if (keys.add(key)) out.add(source);
            if (out.size() >= 30) break;
        }
        return out;
    }

    private List<String> bookTitles(String rawScope) {
        return parseScope(rawScope).stream().map(s -> Objects.toString(s.get("title"), "Book")).toList();
    }

    private String preferences() {
        List<String> rows = jdbc.query("SELECT preferences FROM assistant_memory WHERE user_id=?", (rs, row) -> rs.getString(1), access.actor());
        return rows.isEmpty() ? "" : rows.getFirst();
    }

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) return json.createObjectNode();
        try { return json.readTree(raw); } catch (Exception ignored) { return json.createObjectNode(); }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("学习计划序列化失败", e); }
    }

    private String text(JsonNode value, String fallback, int max) {
        String text = value == null || value.isNull() ? fallback : value.asText(fallback);
        text = Objects.toString(text, "").trim();
        return text.substring(0, Math.min(text.length(), max));
    }

    private long insert(String sql, Object... args) {
        GeneratedKeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            // Request only the primary key: MySQL otherwise labels it GENERATED_KEY,
            // while H2 may also return columns with generated defaults.
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) throw new IllegalStateException("学习记录未生成 ID");
        return key.longValue();
    }
}
