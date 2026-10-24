package com.bookagent.backend.service;

import com.bookagent.backend.common.exception.BusinessException;
import com.bookagent.backend.model.dto.BookSaveDTO;
import com.bookagent.backend.model.dto.TaxonomyDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** User-scoped note actions and a small, durable agent with an explicit tool allowlist. */
@Service @RequiredArgsConstructor
public class AssistantService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final KnowledgeAccess access;
    private final BookService books;
    private final KnowledgeSearch knowledge;
    private final AiClient ai;
    private final TaxonomyService taxonomy;

    public record BookAction(String operation, String instructions, String level, List<Long> bookIds) {}
    public record Apply(long expectedRevision, String operation, String title, String summary,
                        String content, String category, List<String> tags) {}
    public record PlanRequest(String goal, List<Long> bookIds, List<PlanStep> steps) {}
    public record PlanStep(String tool, String title, String instruction) {}
    public record NewStep(String tool, String title, String instruction, Long afterStepId) {}
    public record StepPosition(int position) {}
    public record StepInstruction(String tool, String title, String instruction) {}
    public record MergeRequest(String title, String instructions, List<Long> bookIds) {}
    public record NewDraft(String title, String summary, String content, List<Long> sourceBookIds) {}

    public String memory() {
        var rows = jdbc.queryForList("SELECT preferences FROM assistant_memory WHERE user_id=?", access.actor());
        return rows.isEmpty() ? "" : Objects.toString(rows.getFirst().get("preferences"), "");
    }

    public void saveMemory(String preferences) {
        access.active();
        if (preferences == null || preferences.length() > 4000) throw new BusinessException(400, "长期偏好最多 4000 字符");
        jdbc.update("INSERT INTO assistant_memory(user_id,preferences) VALUES(?,?) ON DUPLICATE KEY UPDATE preferences=VALUES(preferences)", access.actor(), preferences.trim());
    }

    public JsonNode bookAction(long id, BookAction request) {
        access.book(id, "READ");
        if (request == null || !Set.of("organize", "polish", "review", "explain", "related", "duplicates").contains(request.operation()))
            throw new BusinessException(400, "助手操作不合法");
        var book = books.detail(id);
        var content = content(id, book);
        String memory = memory();
        if (request.operation().equals("related") || request.operation().equals("duplicates")) {
            var evidence = searchRelated(book, content, request.bookIds());
            verifyBookStillReadable(id, book.revision());
            return evidence.path("results");
        }
        if (request.operation().equals("review")) {
            var evidence = searchRelated(book, content, request.bookIds());
            if (evidence.path("results").isEmpty()) return json.createObjectNode().set("findings", json.createArrayNode());
            JsonNode result = ai.call("/internal/assistant", Map.of("operation", "review", "title", book.title(), "content", content,
                "memory", memory, "sources", evidence.path("results")));
            verifyBookStillReadable(id, book.revision());
            ((com.fasterxml.jackson.databind.node.ObjectNode) result).set("sources", evidence.path("results"));
            return result;
        }
        String operation = request.operation();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("operation", operation); payload.put("title", book.title()); payload.put("content", content);
        payload.put("instructions", Objects.toString(request.instructions(), "")); payload.put("level", Objects.toString(request.level(), "初学者"));
        payload.put("goal", "explain".equals(operation) ? Objects.toString(request.instructions(), "") : "");
        payload.put("memory", memory);
        JsonNode output = ai.call("/internal/assistant", payload);
        verifyBookStillReadable(id, book.revision());
        ((com.fasterxml.jackson.databind.node.ObjectNode) output).put("revision", book.revision());
        return output;
    }

    private void verifyBookStillReadable(long id, long revision) {
        access.book(id, "READ");
        if (books.detail(id).revision() != revision) throw new BusinessException(409, "Book 内容在生成期间发生变化，请重新生成助手草稿");
    }

    @Transactional
    public Object apply(long id, Apply form) {
        access.book(id, "EDIT");
        if (form == null || form.expectedRevision() <= 0 || !Set.of("organize", "polish").contains(form.operation()))
            throw new BusinessException(400, "应用的助手结果不合法");
        var current = books.detail(id);
        if (current.revision() != form.expectedRevision()) throw new BusinessException(409, "Book 已变化，请重新生成建议后再应用");
        String title = valueOr(form.title(), current.title()).trim();
        String summary = valueOr(form.summary(), current.summary());
        String content = current.content() == null ? "" : current.content();
        if (form.operation().equals("polish")) {
            if ("UPLOAD".equals(current.sourceMode())) throw new BusinessException(400, "文件型 Book 不能直接改写原文件正文");
            if (form.content() == null || form.content().isBlank() || form.content().length() > 1_000_000) throw new BusinessException(400, "润色草稿为空或过长");
            content = form.content();
        }
        Long categoryId = current.categoryId();
        if (form.category() != null && !form.category().isBlank()) categoryId = label("category", form.category().trim());
        List<Long> tagIds = current.tags().stream().map(t -> t.id()).toList();
        if (form.tags() != null) {
            tagIds = form.tags().stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).distinct().limit(20).map(s -> label("tag", s)).toList();
        }
        return books.update(id, new BookSaveDTO(title, summary, content, form.expectedRevision(), categoryId, tagIds));
    }

    private long label(String kind, String value) {
        if (value.length() > 64) throw new BusinessException(400, "分类或标签不能超过 64 字符");
        var ids = jdbc.query("SELECT " + kind + "_id FROM " + kind + "_owner WHERE user_id=? AND label=?", (rs, n) -> rs.getLong(1), access.actor(), value);
        return ids.isEmpty() ? (kind.equals("category") ? taxonomy.saveCategory(null, new TaxonomyDTO(value, null, 0))
            : taxonomy.saveTag(null, new TaxonomyDTO(value, null, null))) : ids.getFirst();
    }

    private String content(long id, com.bookagent.backend.model.vo.BookVO book) {
        if (!"UPLOAD".equals(book.sourceMode())) return Objects.toString(book.content(), "");
        var sections = books.readUnchecked(id).sections();
        var out = new StringBuilder();
        for (var section : sections) {
            if (!out.isEmpty()) out.append("\n\n");
            out.append(section.path("text").asText());
        }
        return out.toString();
    }

    private JsonNode searchRelated(com.bookagent.backend.model.vo.BookVO book, String content, List<Long> requestedIds) {
        List<Long> ids;
        if (requestedIds == null || requestedIds.isEmpty()) {
            ids = access.scopes(false, null).stream().map(s -> ((Number) s.get("book_id")).longValue()).filter(x -> x != book.id()).toList();
        } else {
            ids = requestedIds.stream().filter(Objects::nonNull).filter(x -> x != book.id()).distinct().limit(100).toList();
            if (requestedIds.stream().anyMatch(x -> x == null || x <= 0)) throw new BusinessException(400, "Book 范围不合法");
        }
        if (ids.isEmpty()) return json.createObjectNode().set("results", json.createArrayNode());
        String query = (book.title() + " " + Objects.toString(book.summary(), "") + " " + content.substring(0, Math.min(content.length(), 700))).trim();
        var request = new KnowledgeSearch.Query(query.substring(0, Math.min(query.length(), 500)), ids, false, "hybrid", "qa", 12, true, List.of());
        return knowledge.run(request, false, List.of());
    }

    public List<Map<String, Object>> recommendations() {
        access.active();
        return jdbc.queryForList("SELECT b.id,b.title,b.summary,b.index_status,b.revision,p.category_id, (SELECT COUNT(*) FROM book_tag bt WHERE bt.book_id=b.id) AS tag_count FROM book b JOIN book_owner o ON o.book_id=b.id LEFT JOIN book_publication p ON p.book_id=b.id WHERE b.deleted=0 AND o.user_id=? ORDER BY b.updated_at DESC LIMIT 100", access.actor())
            .stream().flatMap(row -> {
                var items = new ArrayList<Map<String, Object>>(); long id = ((Number) row.get("id")).longValue(); String title = Objects.toString(row.get("title"), "Book");
                if (Objects.toString(row.get("summary"), "").isBlank()) items.add(Map.of("bookId", id, "title", title, "kind", "summary", "message", "补充简介，让书库更容易浏览"));
                if (row.get("category_id") == null || ((Number) row.get("tag_count")).intValue() == 0) items.add(Map.of("bookId", id, "title", title, "kind", "taxonomy", "message", "补充分类或标签，整理知识目录"));
                if (!"READY".equals(row.get("index_status"))) items.add(Map.of("bookId", id, "title", title, "kind", "index", "message", "索引尚未就绪，检查索引状态"));
                return items.stream();
            }).limit(100).toList();
    }

    public Map<String, Object> createPlan(PlanRequest form) {
        access.active();
        if (form == null || form.goal() == null || form.goal().isBlank() || form.goal().length() > 1000 || form.bookIds() == null || form.bookIds().isEmpty() || form.bookIds().size() > 50)
            throw new BusinessException(400, "请填写目标并选择 1–50 本可用 Book");
        var scopes = access.scopes(false, form.bookIds());
        if (scopes.size() != new HashSet<>(form.bookIds()).size()) throw new BusinessException(409, "所选 Book 不可访问或索引尚未就绪");
        JsonNode generated;
        List<PlanStep> steps;
        if (form.steps() != null && !form.steps().isEmpty()) {
            steps = form.steps();
            validatePlanSteps(steps);
            var planned = json.createObjectNode();
            planned.put("title", form.goal().trim());
            var planSteps = planned.putArray("steps");
            for (PlanStep step : steps) {
                var item = planSteps.addObject();
                item.put("tool", step.tool()); item.put("title", step.title().trim()); item.put("instruction", step.instruction().trim());
            }
            generated = planned;
        } else {
            generated = ai.call("/internal/assistant", Map.of("operation", "plan", "goal", form.goal(), "memory", memory(),
                "book_titles", scopes.stream().map(s -> Objects.toString(s.get("title"), "")).toList()));
            steps = planSteps(generated);
            validatePlanSteps(steps);
        }
        try {
            String scopesJson = json.writeValueAsString(scopes), planJson = json.writeValueAsString(generated);
            long id = insert("INSERT INTO assistant_plan(user_id,goal,scope_json,plan_json,status) VALUES(?,?,?,?,'PLANNED')", access.actor(), form.goal().trim(), scopesJson, planJson);
            int position = 0;
            for (PlanStep step : steps) {
                insert("INSERT INTO assistant_plan_step(plan_id,step_no,tool,title,instruction,status) VALUES(?,?,?,?,?,'PLANNED')", id, ++position,
                    step.tool(), step.title().trim(), step.instruction().trim());
            }
            return plan(id);
        } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
    }

    private List<PlanStep> planSteps(JsonNode generated) {
        var steps = new ArrayList<PlanStep>();
        for (JsonNode step : generated.path("steps")) {
            steps.add(new PlanStep(step.path("tool").asText(), step.path("title").asText(), step.path("instruction").asText()));
        }
        return steps;
    }

    private void validatePlanSteps(List<PlanStep> steps) {
        if (steps == null || steps.isEmpty() || steps.size() > 6) throw new BusinessException(400, "计划必须包含 1–6 个步骤");
        boolean searched = false, drafted = false;
        for (PlanStep step : steps) {
            if (step == null || step.tool() == null || !Set.of("search", "summarize", "compare", "draft", "proofread").contains(step.tool())
                || step.title() == null || step.title().isBlank() || step.title().length() > 100
                || step.instruction() == null || step.instruction().isBlank() || step.instruction().length() > 500)
                throw new BusinessException(400, "计划步骤类型、名称或要求不合法（每项要求最多 500 字符）");
            if (!"search".equals(step.tool()) && !searched)
                throw new BusinessException(400, "总结、比较、起草和校对步骤前必须先检索来源");
            boolean proofreading = "proofread".equals(step.tool()) || ("compare".equals(step.tool()) && looksLikeProofreading(step.instruction()));
            if (proofreading && !drafted)
                throw new BusinessException(400, "校对步骤前必须先有起草步骤");
            if ("search".equals(step.tool())) searched = true;
            if ("draft".equals(step.tool())) drafted = true;
        }
    }

    public JsonNode mergePreview(MergeRequest form) {
        access.active();
        if (form == null || form.bookIds() == null || form.bookIds().size() < 2 || form.bookIds().size() > 10)
            throw new BusinessException(400, "请选择 2–10 本 Book 进行合并");
        var scopes = access.scopes(false, form.bookIds());
        if (scopes.size() != new HashSet<>(form.bookIds()).size()) throw new BusinessException(409, "部分来源 Book 不可访问或索引尚未就绪");
        String requestText = Objects.toString(form.instructions(), "整理这些 Book 的共同主题、关键结论和差异").trim();
        if (requestText.isBlank() || requestText.length() > 1000) throw new BusinessException(400, "合并要求最多 1000 字符");
        var query = new KnowledgeSearch.Query(requestText, form.bookIds(), false, "hybrid", "summary", 25, true, List.of());
        JsonNode evidence = knowledge.run(query, false, List.of());
        if (evidence.path("results").isEmpty()) throw new BusinessException(409, "所选 Book 没有可用于合并的检索片段");
        JsonNode draft = ai.call("/internal/assistant", Map.of("operation", "merge", "title", Objects.toString(form.title(), "").trim(),
            "goal", requestText, "memory", memory(), "book_titles", scopes.stream().map(s -> Objects.toString(s.get("title"), "")).toList(),
            "sources", evidence.path("results")));
        if (!scopes.equals(access.scopes(false, form.bookIds()))) throw new BusinessException(409, "来源 Book 在生成期间发生变化，请重新生成");
        return draft;
    }

    public List<Map<String, Object>> plans() {
        return jdbc.queryForList("SELECT id,goal,plan_json AS plan,status,created_at AS createdAt,updated_at AS updatedAt FROM assistant_plan WHERE user_id=? ORDER BY id DESC LIMIT 30", access.actor())
            .stream().map(this::parsePlan).toList();
    }

    public void deletePlan(long id) {
        access.active();
        if (jdbc.queryForList("SELECT id FROM assistant_plan WHERE id=? AND user_id=?", id, access.actor()).isEmpty())
            throw new BusinessException(404, "助手计划不存在");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM assistant_plan_step WHERE plan_id=? AND status='RUNNING'", Long.class, id) > 0)
            throw new BusinessException(409, "步骤正在执行，请稍后删除");
        jdbc.update("DELETE FROM assistant_plan WHERE id=? AND user_id=?", id, access.actor());
    }

    public Map<String, Object> plan(long id) {
        var rows = jdbc.queryForList("SELECT id,user_id,goal,scope_json,plan_json AS plan,status,created_at AS createdAt,updated_at AS updatedAt FROM assistant_plan WHERE id=? AND user_id=?", id, access.actor());
        if (rows.isEmpty()) throw new BusinessException(404, "助手计划不存在");
        var result = parsePlan(rows.getFirst());
        result.put("steps", jdbc.queryForList("SELECT id,step_no AS stepNo,tool,title,instruction,status,result_json AS result,updated_at AS updatedAt FROM assistant_plan_step WHERE plan_id=? ORDER BY step_no", id).stream().map(this::parseStep).toList());
        return result;
    }

    @Transactional
    public Map<String, Object> updateStepInstruction(long planId, long stepId, StepInstruction form) {
        access.active();
        if (form == null || form.instruction() == null || form.instruction().isBlank() || form.instruction().length() > 500)
            throw new BusinessException(400, "步骤要求不能为空且不能超过 500 字符");
        if (form.tool() != null && !Set.of("search", "summarize", "compare", "draft", "proofread").contains(form.tool()))
            throw new BusinessException(400, "计划工具不受支持");
        if (form.title() != null && (form.title().isBlank() || form.title().length() > 100))
            throw new BusinessException(400, "步骤名称不能为空且不能超过 100 字符");
        var plan = jdbc.queryForList("SELECT id FROM assistant_plan WHERE id=? AND user_id=?", planId, access.actor());
        if (plan.isEmpty()) throw new BusinessException(404, "助手计划不存在");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM assistant_plan_step WHERE plan_id=? AND status='RUNNING'", Long.class, planId) > 0)
            throw new BusinessException(409, "步骤正在执行，请稍后调整");
        var rows = jdbc.queryForList("SELECT step_no,tool,title FROM assistant_plan_step WHERE id=? AND plan_id=?", stepId, planId);
        if (rows.isEmpty()) throw new BusinessException(404, "计划步骤不存在");
        long stepNo = ((Number) rows.getFirst().get("step_no")).longValue();
        String tool = form.tool() == null ? Objects.toString(rows.getFirst().get("tool"), "") : form.tool();
        String title = form.title() == null ? Objects.toString(rows.getFirst().get("title"), "") : form.title().trim();
        validatePlanSteps(orderedPlanSteps(planId, stepIds(planId), stepId, new PlanStep(tool, title, form.instruction().trim())));
        if (jdbc.update("UPDATE assistant_plan_step SET tool=?,title=?,instruction=?,status='PLANNED',result_json=NULL,error=NULL WHERE id=? AND plan_id=? AND status<>'RUNNING'",
                tool, title, form.instruction().trim(), stepId, planId) == 0)
            throw new BusinessException(409, "步骤状态已变化，请刷新计划后重试");
        jdbc.update("UPDATE assistant_plan_step SET status='PLANNED',result_json=NULL,error=NULL WHERE plan_id=? AND step_no>? AND status<>'RUNNING'", planId, stepNo);
        jdbc.update("UPDATE assistant_plan SET status='PLANNED' WHERE id=? AND user_id=?", planId, access.actor());
        refreshPlanJson(planId);
        return plan(planId);
    }

    @Transactional
    public Map<String, Object> movePlanStep(long planId, long stepId, StepPosition form) {
        access.active();
        if (form == null) throw new BusinessException(400, "缺少目标位置");
        ensurePlanEditable(planId);
        var currentIds = stepIds(planId);
        int oldIndex = currentIds.indexOf(stepId);
        if (oldIndex < 0) throw new BusinessException(404, "计划步骤不存在");
        if (form.position() < 1 || form.position() > currentIds.size()) throw new BusinessException(400, "目标位置超出计划范围");
        var nextIds = new ArrayList<>(currentIds);
        nextIds.remove(oldIndex);
        nextIds.add(form.position() - 1, stepId);
        if (nextIds.equals(currentIds)) return plan(planId);
        validatePlanSteps(orderedPlanSteps(planId, nextIds, null, null));
        reorderSteps(planId, nextIds);
        int changedFrom = firstChangedPosition(currentIds, nextIds);
        resetFrom(planId, changedFrom);
        refreshPlanJson(planId);
        return plan(planId);
    }

    @Transactional
    public Map<String, Object> removePlanStep(long planId, long stepId) {
        access.active();
        ensurePlanEditable(planId);
        var currentIds = stepIds(planId);
        int index = currentIds.indexOf(stepId);
        if (index < 0) throw new BusinessException(404, "计划步骤不存在");
        if (currentIds.size() <= 1) throw new BusinessException(409, "计划至少保留一个步骤");
        var nextIds = new ArrayList<>(currentIds);
        nextIds.remove(index);
        validatePlanSteps(orderedPlanSteps(planId, nextIds, null, null));
        jdbc.update("DELETE FROM assistant_plan_step WHERE id=? AND plan_id=?", stepId, planId);
        reorderSteps(planId, nextIds);
        resetFrom(planId, index + 1);
        refreshPlanJson(planId);
        return plan(planId);
    }

    public Map<String, Object> resetPlanFromStep(long planId, long stepId) {
        access.active();
        ensurePlanEditable(planId);
        var rows = jdbc.queryForList("SELECT step_no FROM assistant_plan_step WHERE id=? AND plan_id=?", stepId, planId);
        if (rows.isEmpty()) throw new BusinessException(404, "计划步骤不存在");
        int position = ((Number) rows.getFirst().get("step_no")).intValue();
        resetFrom(planId, position);
        return plan(planId);
    }

    @Transactional
    public Map<String, Object> addPlanStep(long planId, NewStep form) {
        access.active();
        if (form == null || form.tool() == null || !Set.of("search", "summarize", "compare", "draft", "proofread").contains(form.tool()))
            throw new BusinessException(400, "计划工具不受支持");
        if (form.title() == null || form.title().isBlank() || form.title().length() > 100 || form.instruction() == null || form.instruction().isBlank() || form.instruction().length() > 500)
            throw new BusinessException(400, "步骤名称或要求不合法");
        ensurePlanEditable(planId);
        var oldIds = stepIds(planId);
        long count = oldIds.size();
        if (count >= 6) throw new BusinessException(409, "每个计划最多包含 6 个步骤");
        int insertAt = oldIds.size();
        if (form.afterStepId() != null) {
            int afterIndex = oldIds.indexOf(form.afterStepId());
            if (afterIndex < 0) throw new BusinessException(404, "插入位置对应的步骤不存在");
            insertAt = afterIndex + 1;
        }
        var newStep = new PlanStep(form.tool(), form.title().trim(), form.instruction().trim());
        var candidateIds = new ArrayList<>(oldIds);
        candidateIds.add(insertAt, -1L);
        validatePlanSteps(orderedPlanSteps(planId, candidateIds, -1L, newStep));
        long newId = insert("INSERT INTO assistant_plan_step(plan_id,step_no,tool,title,instruction,status) VALUES(?,0,?,?,?,'PLANNED')",
            planId, form.tool(), form.title().trim(), form.instruction().trim());
        var nextIds = new ArrayList<>(oldIds);
        nextIds.add(insertAt, newId);
        reorderSteps(planId, nextIds);
        resetFrom(planId, insertAt + 1);
        refreshPlanJson(planId);
        return plan(planId);
    }

    private void ensurePlanEditable(long planId) {
        if (jdbc.queryForList("SELECT id FROM assistant_plan WHERE id=? AND user_id=?", planId, access.actor()).isEmpty())
            throw new BusinessException(404, "助手计划不存在");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM assistant_plan_step WHERE plan_id=? AND status='RUNNING'", Long.class, planId) > 0)
            throw new BusinessException(409, "步骤正在执行，请稍后调整计划");
    }

    private List<Long> stepIds(long planId) {
        return jdbc.queryForList("SELECT id FROM assistant_plan_step WHERE plan_id=? ORDER BY step_no", Long.class, planId);
    }

    private void reorderSteps(long planId, List<Long> orderedIds) {
        jdbc.update("UPDATE assistant_plan_step SET step_no=-step_no WHERE plan_id=?", planId);
        for (int i = 0; i < orderedIds.size(); i++)
            jdbc.update("UPDATE assistant_plan_step SET step_no=? WHERE id=? AND plan_id=?", i + 1, orderedIds.get(i), planId);
    }

    private int firstChangedPosition(List<Long> before, List<Long> after) {
        for (int i = 0; i < before.size(); i++) if (!Objects.equals(before.get(i), after.get(i))) return i + 1;
        return after.size() + 1;
    }

    private void resetFrom(long planId, int position) {
        jdbc.update("UPDATE assistant_plan_step SET status='PLANNED',result_json=NULL,error=NULL WHERE plan_id=? AND step_no>=? AND status<>'RUNNING'", planId, position);
        refreshPlanStatus(planId);
    }

    private void refreshPlanStatus(long planId) {
        jdbc.update("UPDATE assistant_plan SET status=CASE WHEN EXISTS(SELECT 1 FROM assistant_plan_step WHERE plan_id=? AND status='RUNNING') THEN 'RUNNING' " +
            "WHEN NOT EXISTS(SELECT 1 FROM assistant_plan_step WHERE plan_id=? AND status<>'SUCCEEDED') THEN 'COMPLETED' ELSE 'PLANNED' END WHERE id=? AND user_id=?",
            planId, planId, planId, access.actor());
    }

    private void refreshPlanJson(long planId) {
        var steps = plan(planId).get("steps");
        try {
            var planRow = jdbc.queryForMap("SELECT plan_json FROM assistant_plan WHERE id=? AND user_id=?", planId, access.actor());
            JsonNode data = json.readTree(Objects.toString(planRow.get("plan_json"), "{}"));
            var updated = data.isObject() ? (com.fasterxml.jackson.databind.node.ObjectNode) data : json.createObjectNode();
            var planSteps = updated.putArray("steps");
            for (Object value : (List<?>) steps) {
                var step = (Map<?, ?>) value;
                var item = planSteps.addObject();
                item.put("tool", Objects.toString(step.get("tool"), ""));
                item.put("title", Objects.toString(step.get("title"), ""));
                item.put("instruction", Objects.toString(step.get("instruction"), ""));
            }
            jdbc.update("UPDATE assistant_plan SET plan_json=? WHERE id=? AND user_id=?", json.writeValueAsString(updated), planId, access.actor());
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private List<PlanStep> orderedPlanSteps(long planId, List<Long> ids, Long replacementId, PlanStep replacement) {
        var steps = new ArrayList<PlanStep>();
        for (Long id : ids) {
            if (Objects.equals(id, replacementId)) { steps.add(replacement); continue; }
            var row = jdbc.queryForMap("SELECT tool,title,instruction FROM assistant_plan_step WHERE id=? AND plan_id=?", id, planId);
            steps.add(new PlanStep(Objects.toString(row.get("tool"), ""), Objects.toString(row.get("title"), ""), Objects.toString(row.get("instruction"), "")));
        }
        return steps;
    }

    public Map<String, Object> executeStep(long planId, long stepId) {
        var plan = jdbc.queryForList("SELECT * FROM assistant_plan WHERE id=? AND user_id=?", planId, access.actor());
        if (plan.isEmpty()) throw new BusinessException(404, "助手计划不存在");
        var stepRows = jdbc.queryForList("SELECT * FROM assistant_plan_step WHERE id=? AND plan_id=?", stepId, planId);
        if (stepRows.isEmpty()) throw new BusinessException(404, "计划步骤不存在");
        var step = stepRows.getFirst(); String status = (String) step.get("status");
        if ("SUCCEEDED".equals(status)) return plan(planId);
        long stepNo = jdbc.queryForObject("SELECT step_no FROM assistant_plan_step WHERE id=? AND plan_id=?", Long.class, stepId, planId);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM assistant_plan_step WHERE plan_id=? AND step_no<? AND status<>'SUCCEEDED'", Long.class, planId, stepNo) > 0)
            throw new BusinessException(409, "请先完成前面的计划步骤，再执行当前步骤");
        if (jdbc.update("UPDATE assistant_plan_step SET status='RUNNING',error=NULL WHERE id=? AND plan_id=? AND status IN ('PLANNED','FAILED')", stepId, planId) == 0)
            throw new BusinessException(409, "步骤正在执行或状态已变化");
        jdbc.update("UPDATE assistant_plan SET status='RUNNING' WHERE id=?", planId);
        try {
            @SuppressWarnings("unchecked") List<Map<String, Object>> scopes = json.readValue((String) plan.getFirst().get("scope_json"), List.class);
            var selected = scopes.stream().map(s -> ((Number) s.get("book_id")).longValue()).toList();
            if (!sameScopes(scopes, access.scopes(false, selected))) throw new BusinessException(409, "Book 权限或版本已变化，请重新生成计划");
            String tool = (String) step.get("tool"), instruction = (String) step.get("instruction");
            var previous = jdbc.queryForList("SELECT tool,title,instruction,result_json AS result FROM assistant_plan_step WHERE plan_id=? AND step_no<? AND status='SUCCEEDED' ORDER BY step_no", planId, stepNo);
            var context = executionContext(previous, selected);
            JsonNode output;
            if ("search".equals(tool)) {
                var query = new KnowledgeSearch.Query(instruction, selected, false, "hybrid", "qa", 12, true, List.of());
                output = knowledge.run(query, false, List.of());
            } else if ("summarize".equals(tool)) {
                requireSources(context, "总结");
                output = ai.call("/internal/assistant", Map.of("operation", "summarize", "title", step.get("title"),
                    "content", context.content(), "instructions", instruction, "goal", instruction, "memory", memory(), "sources", context.sources(), "step_memory", context.steps()));
            } else if ("compare".equals(tool)) {
                requireSources(context, "比较");
                String draft = latestContent(previous, "draft");
                boolean proofreading = !draft.isBlank() && looksLikeProofreading(instruction);
                output = ai.call("/internal/assistant", Map.of("operation", proofreading ? "proofread" : "compare", "title", step.get("title"),
                    "content", proofreading ? draft : context.content(), "instructions", instruction, "goal", instruction, "memory", memory(), "sources", context.sources(), "step_memory", context.steps()));
            } else if ("proofread".equals(tool)) {
                requireSources(context, "校对");
                String draft = latestContent(previous, "draft");
                if (draft.isBlank()) throw new BusinessException(409, "校对前没有已完成的草稿，请调整计划或重新执行起草步骤");
                output = ai.call("/internal/assistant", Map.of("operation", "proofread", "title", step.get("title"),
                    "content", draft, "instructions", instruction, "goal", instruction, "memory", memory(), "sources", context.sources(), "step_memory", context.steps()));
            } else if ("draft".equals(tool)) {
                requireSources(context, "起草");
                output = ai.call("/internal/assistant", Map.of("operation", "compose", "title", step.get("title"), "goal", instruction,
                    "content", context.content(), "memory", memory(), "sources", context.sources(), "step_memory", context.steps()));
            } else throw new BusinessException(400, "计划工具不受支持，请重新生成计划");
            access.active();
            if (!sameScopes(scopes, access.scopes(false, selected))) throw new BusinessException(409, "Book 权限或版本在处理期间变化，请重试");
            jdbc.update("UPDATE assistant_plan_step SET status='SUCCEEDED',result_json=?,error=NULL WHERE id=?", json.writeValueAsString(output), stepId);
            jdbc.update("UPDATE assistant_plan SET status=CASE WHEN NOT EXISTS(SELECT 1 FROM assistant_plan_step WHERE plan_id=? AND status<>'SUCCEEDED') THEN 'COMPLETED' ELSE 'RUNNING' END WHERE id=?", planId, planId);
            return plan(planId);
        } catch (BusinessException e) {
            jdbc.update("UPDATE assistant_plan_step SET status='FAILED',error=? WHERE id=?", e.getMessage().substring(0, Math.min(900, e.getMessage().length())), stepId);
            throw e;
        } catch (Exception e) {
            jdbc.update("UPDATE assistant_plan_step SET status='FAILED',error='执行失败，请检查服务后重试' WHERE id=?", stepId);
            throw new BusinessException(503, "Agent 步骤执行失败，请检查 AI 与检索服务后重试");
        }
    }

    private record ExecutionContext(String content, com.fasterxml.jackson.databind.node.ArrayNode sources, com.fasterxml.jackson.databind.node.ArrayNode steps) {}

    private ExecutionContext executionContext(List<Map<String, Object>> previous, List<Long> selected) {
        String content = previousText(previous);
        var unique = new LinkedHashMap<String, JsonNode>();
        var steps = json.createArrayNode();
        for (var row : previous) {
            JsonNode result = parseResult(row.get("result"));
            var memory = steps.addObject();
            memory.put("tool", Objects.toString(row.get("tool"), ""));
            memory.put("title", Objects.toString(row.get("title"), ""));
            memory.put("instruction", Objects.toString(row.get("instruction"), ""));
            String output = result.path("content").asText(result.path("answer").asText(""));
            if (output.length() > 12000) output = output.substring(0,6000)+"\n[…中间内容省略；完整产物仍在步骤记录中…]\n"+output.substring(output.length()-5900);
            memory.put("output", output);
            var findings = memory.putArray("findings");
            if (result.path("findings").isArray()) for (var finding : result.path("findings")) {
                if (findings.size() >= 12) break;
                findings.add(finding);
            }
            collectEvidence(result.path("results"), selected, unique);
            collectEvidence(result.path("sources"), selected, unique);
        }
        var sources = json.createArrayNode();
        unique.values().stream().limit(30).forEach(sources::add);
        return new ExecutionContext(content, sources, steps);
    }

    private void requireSources(ExecutionContext context, String operation) {
        if (context.sources().isEmpty()) throw new BusinessException(409,
            operation + "没有可用来源。请调整前面的检索要求并重新执行；本步骤不会把处理说明当成检索问题。");
    }

    private String latestContent(List<Map<String, Object>> previous, String tool) {
        for (int i = previous.size() - 1; i >= 0; i--) {
            var row = previous.get(i);
            if (!tool.equals(row.get("tool"))) continue;
            JsonNode result = parseResult(row.get("result"));
            String content = result.path("content").asText("");
            if (!content.isBlank()) return content.substring(0, Math.min(content.length(), 100_000));
        }
        return "";
    }

    private boolean looksLikeProofreading(String instruction) {
        String text = Objects.toString(instruction, "").toLowerCase(Locale.ROOT);
        return List.of("校对", "译文", "译稿", "翻译", "漏译", "误译", "proofread", "translation", "translate")
            .stream().anyMatch(text::contains);
    }

    private String previousText(List<Map<String, Object>> previous) {
        String content = "";
        for (var row : previous) {
            JsonNode result = parseResult(row.get("result"));
            String candidate = result.path("content").asText("");
            if (candidate.isBlank()) candidate = result.path("answer").asText("");
            if (!candidate.isBlank()) content = candidate.substring(0, Math.min(candidate.length(), 100_000));
        }
        return content;
    }

    private JsonNode parseResult(Object raw) {
        if (raw == null) return json.createObjectNode();
        try { return raw instanceof JsonNode node ? node : json.readTree(raw.toString()); }
        catch (Exception ignored) { return json.createObjectNode(); }
    }

    private void collectEvidence(JsonNode items, List<Long> selected, Map<String, JsonNode> unique) {
        if (!items.isArray()) return;
        for (JsonNode item : items) {
            long bookId = item.path("bookId").asLong();
            String excerpt = item.path("excerpt").asText("");
            if (bookId <= 0 || !selected.contains(bookId) || excerpt.isBlank()) continue;
            String sourceName = item.path("sourceName").asText("Book 正文");
            int page = item.path("page").asInt(0), fileId = item.path("fileId").asInt(0);
            String key = bookId + "|" + fileId + "|" + page + "|" + excerpt;
            if (unique.containsKey(key)) continue;
            var source = json.createObjectNode();
            source.put("bookId", bookId);
            String bookTitle = item.path("bookTitle").asText("");
            source.put("bookTitle", bookTitle.substring(0, Math.min(128, bookTitle.length())));
            source.put("sourceName", sourceName.substring(0, Math.min(255, sourceName.length())));
            if (fileId > 0) source.put("fileId", fileId);
            if (page > 0) source.put("page", page);
            source.put("excerpt", excerpt.substring(0, Math.min(5000, excerpt.length())));
            unique.put(key, source);
        }
    }

    @Transactional
    public com.bookagent.backend.model.vo.BookVO createDraft(NewDraft form) {
        access.active();
        if (form == null || form.title() == null || form.title().isBlank() || form.title().length() > 128 || form.content() == null || form.content().isBlank() || form.content().length() > 1_000_000)
            throw new BusinessException(400, "草稿标题或正文不合法");
        if (form.sourceBookIds() == null || form.sourceBookIds().isEmpty() || form.sourceBookIds().size() > 50) throw new BusinessException(400, "请指定草稿引用的 Book 范围");
        var scopes = access.scopes(false, form.sourceBookIds());
        if (scopes.size() != new HashSet<>(form.sourceBookIds()).size()) throw new BusinessException(409, "来源 Book 权限或索引已变化");
        return books.create(new BookSaveDTO(form.title().trim(), form.summary(), form.content(), null));
    }

    private Map<String, Object> parsePlan(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>(row);
        try { result.put("plan", json.readTree(Objects.toString(row.get("plan"), "{}"))); } catch (Exception e) { result.put("plan", json.createObjectNode()); }
        if (row.containsKey("scope_json")) {
            try {
                JsonNode scope = json.readTree(Objects.toString(row.get("scope_json"), "[]"));
                var ids = new ArrayList<Long>();
                for (JsonNode item : scope) ids.add(item.path("book_id").asLong());
                result.put("bookIds", ids);
            } catch (Exception ignored) { result.put("bookIds", List.of()); }
            result.remove("scope_json");
            result.remove("user_id");
        }
        return result;
    }
    private boolean sameScopes(List<Map<String,Object>> left, List<Map<String,Object>> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            var a = left.get(i); var b = right.get(i);
            try {
                if (((Number) a.get("book_id")).longValue() != ((Number) b.get("book_id")).longValue()
                    || ((Number) a.get("revision")).longValue() != ((Number) b.get("revision")).longValue()
                    || !Objects.equals(Objects.toString(a.get("title"), ""), Objects.toString(b.get("title"), ""))) return false;
            } catch (RuntimeException ex) { return false; }
        }
        return true;
    }
    private Map<String, Object> parseStep(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>(row);
        try { if (row.get("result") != null) result.put("result", json.readTree(Objects.toString(row.get("result"), "{}"))); } catch (Exception ignored) { result.put("result", json.createObjectNode()); }
        return result;
    }
    private long insert(String sql, Object... args) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, holder);
        Number key = null;
        if (!holder.getKeyList().isEmpty()) {
            var generated = holder.getKeyList().getFirst();
            for (var entry : generated.entrySet()) if ("id".equalsIgnoreCase(entry.getKey())) { key = (Number) entry.getValue(); break; }
            if (key == null && generated.size() == 1) key = (Number) generated.values().iterator().next();
        }
        if (key == null) throw new IllegalStateException("Generated plan id is missing");
        return key.longValue();
    }
    private static String valueOr(String value, String fallback) { return value == null ? Objects.toString(fallback, "") : value; }
}
