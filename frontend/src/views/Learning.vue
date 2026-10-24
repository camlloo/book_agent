<script setup>
import { computed, onMounted, onUnmounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "../api";
import KnowledgeScopePicker from "../components/KnowledgeScopePicker.vue";
import { sourceHref } from "../citations";

const loading = ref(true), busy = ref(false), reporting = ref(false), error = ref("");
const plans = ref([]), current = ref(null), savingSession = ref(0);
const selecting = ref(false);
let selectionGeneration = 0, active = true;
const form = ref({ goal: "", bookIds: [], currentLevel: "初学者", selfConfidence: 3, durationWeeks: 4,
  sessionsPerWeek: 3, minutesPerSession: 30, startDate: today(), knownTopics: "" });
const levelOptions = ["初学者", "有一些基础", "可以独立实践", "熟练"];
const canCreate = computed(() => form.value.goal.trim() && form.value.bookIds.length > 0 && !busy.value);
const hasLearningRecord = computed(() => current.value?.weeks.some((week) =>
  week.sessions.some((session) => ["COMPLETED", "NEEDS_REVIEW"].includes(session.status))));
const isCurrent = (generation) => active && generation === selectionGeneration;

function today() {
  const date = new Date();
  date.setMinutes(date.getMinutes() - date.getTimezoneOffset());
  return date.toISOString().slice(0, 10);
}
function statusLabel(status) {
  return ({ PLANNED: "待学习", COMPLETED: "已完成", NEEDS_REVIEW: "需要复习", SKIPPED: "已跳过" })[status] || status;
}
function sourceLabel(source) {
  const title = source.fileId ? source.sourceName : (source.bookTitle || source.sourceName || "Book");
  return `${title}${source.page ? ` · 第 ${source.page} 页` : ""}`;
}

async function load() {
  loading.value = true; error.value = "";
  try {
    const planData = await api("/api/learning/plans");
    plans.value = planData;
    if (!current.value && plans.value.length) await selectPlan(plans.value[0]);
  } catch (e) { error.value = e.message; }
  finally { loading.value = false; }
}
async function selectPlan(plan) {
  const generation = ++selectionGeneration;
  current.value = null; error.value = ""; selecting.value = true;
  try {
    const value = await api(`/api/learning/plans/${plan.id}`);
    if (isCurrent(generation)) current.value = value;
  }
  catch (e) { if (isCurrent(generation)) error.value = e.message; }
  finally { if (isCurrent(generation)) selecting.value = false; }
}
async function createPlan() {
  if (!canCreate.value) return;
  busy.value = true; error.value = "";
  const generation = ++selectionGeneration;
  try {
    const value = await api("/api/learning/plans", { method: "POST", body: { ...form.value } });
    if (isCurrent(generation)) { current.value = value; selecting.value = false; }
    plans.value = await api("/api/learning/plans");
    form.value.goal = ""; form.value.knownTopics = "";
    ElMessage.success("学习路线已保存，可以按自己的节奏完成每日任务。");
  } catch (e) { if (isCurrent(generation)) error.value = e.message; }
  finally { busy.value = false; }
}
async function saveSession(session, status) {
  if (["COMPLETED", "NEEDS_REVIEW"].includes(status) && !session.confidence) {
    ElMessage.warning("先记录这次学习的掌握程度（1–5 分）。"); return;
  }
  savingSession.value = session.id; error.value = "";
  const generation = selectionGeneration, planId = current.value.id;
  try {
    const value = await api(`/api/learning/plans/${planId}/sessions/${session.id}`, {
      method: "PUT", body: { status, confidence: session.confidence || null, reflection: session.reflection || "" },
    });
    if (isCurrent(generation)) current.value = value;
    plans.value = await api("/api/learning/plans");
  } catch (e) { if (isCurrent(generation)) error.value = e.message; }
  finally { savingSession.value = 0; }
}
async function generateReport() {
  if (!current.value || reporting.value) return;
  reporting.value = true; error.value = "";
  const generation = selectionGeneration, planId = current.value.id;
  try {
    const value = await api(`/api/learning/plans/${planId}/report`, { method: "POST", body: {} });
    if (isCurrent(generation)) current.value = value;
    ElMessage.success("阶段学习报告已生成。");
  } catch (e) { if (isCurrent(generation)) error.value = e.message; }
  finally { reporting.value = false; }
}
async function removePlan(plan) {
  try {
    await ElMessageBox.confirm("删除这条学习路线和它的进度记录？此操作无法撤销。", "删除学习路线", { confirmButtonText: "删除路线", cancelButtonText: "保留" });
    await api(`/api/learning/plans/${plan.id}`, { method: "DELETE" });
    const deletingCurrent = current.value?.id === plan.id;
    if (deletingCurrent) { ++selectionGeneration; current.value = null; }
    plans.value = await api("/api/learning/plans");
    if (deletingCurrent && !current.value && plans.value.length) await selectPlan(plans.value[0]);
    ElMessage.success("学习路线已删除。");
  } catch (e) { if (e !== "cancel" && e !== "close") error.value = e.message; }
}
onMounted(load);
onUnmounted(() => { active = false; ++selectionGeneration; });
</script>

<template>
  <section class="learning-page">
    <header class="page-heading"><div><h1>学习中心</h1><p>把学习目标拆成可调整的周计划。路线根据你选择的 Book 编排，完成后记录掌握度，逐步看清进展与薄弱点。</p></div></header>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="loading" class="knowledge-panel" role="status">正在读取你的学习记录与 Book…</div>
    <template v-else>
      <section class="knowledge-panel learning-create">
        <div class="assistant-section-heading"><div><h2>制定学习路线</h2><p class="muted">选择已完成索引的 Book，设定每周频率和单次时长。路线仅使用授权内容中的检索依据。</p></div></div>
        <label for="learning-goal">学习目标</label>
        <el-input id="learning-goal" v-model="form.goal" type="textarea" :rows="2" maxlength="1000" show-word-limit placeholder="例如：能用 Python 编写一个读取文件并处理异常的小工具" :disabled="busy" />
        <label for="learning-scope">学习资料</label>
        <KnowledgeScopePicker id="learning-scope" v-model="form.bookIds" :max-books="50" :disabled="busy" label="选择学习 Book 或文件夹" />
        <div class="learning-form-grid">
          <label>当前水平自评<el-select v-model="form.currentLevel" :disabled="busy"><el-option v-for="level in levelOptions" :key="level" :label="level" :value="level" /></el-select></label>
          <label>对当前自评的把握<el-rate v-model="form.selfConfidence" :max="5" :texts="['不确定', '比较不确定', '一般', '比较确定', '很确定']" show-text /></label>
          <label>计划周期<el-select v-model="form.durationWeeks" :disabled="busy"><el-option v-for="weeks in [1, 2, 4, 6, 8]" :key="weeks" :label="`${weeks} 周`" :value="weeks" /></el-select></label>
          <label>每周学习次数<el-input-number v-model="form.sessionsPerWeek" :min="1" :max="7" :disabled="busy" /></label>
          <label>每次时长（分钟）<el-input-number v-model="form.minutesPerSession" :min="10" :max="180" :step="5" :disabled="busy" /></label>
          <label>开始日期<el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" :disabled="busy" /></label>
        </div>
        <label for="learning-known">已掌握或熟悉的内容（选填）</label>
        <el-input id="learning-known" v-model="form.knownTopics" maxlength="1000" placeholder="例如：会使用变量和循环，还不熟悉函数" :disabled="busy" />
        <div class="assistant-form-actions"><span class="muted">完成任务时可以记录掌握度和反思；这些自评会用于后续的薄弱点分析。</span><el-button type="primary" :loading="busy" :disabled="!canCreate" @click="createPlan">生成学习路线</el-button></div>
      </section>

      <div class="learning-layout">
        <aside class="knowledge-panel learning-list">
          <div class="assistant-section-heading"><h2>我的路线</h2><span>{{ plans.length }}</span></div>
          <button v-for="plan in plans" :key="plan.id" class="learning-plan-item" :class="{ selected: current?.id === plan.id }" @click="selectPlan(plan)">
            <strong>{{ plan.title }}</strong><small>{{ plan.completedSessions }}/{{ plan.totalSessions }} 次 · {{ plan.progress }}% · {{ plan.currentLevel }}</small><el-progress :percentage="plan.progress" :show-text="false" :stroke-width="5" />
          </button>
          <p v-if="!plans.length" class="muted">生成的学习路线会保存在这里。</p>
        </aside>

        <main class="knowledge-panel learning-detail" :aria-busy="!!savingSession || reporting">
          <template v-if="current">
            <header class="learning-detail-heading"><div><p class="eyebrow">{{ current.status === 'COMPLETED' ? '路线已完成' : '进行中的路线' }}</p><h2>{{ current.title }}</h2><p class="muted">{{ current.goal }}</p><p class="muted">资料：{{ current.bookTitles.join('、') }}</p></div><el-button text type="danger" @click="removePlan(current)">删除路线</el-button></header>
            <div class="learning-progress"><div><strong>学习进度</strong><span>{{ current.progress.completed }} / {{ current.progress.total }} 次 · {{ current.progress.percent }}%</span></div><el-progress :percentage="current.progress.percent" :stroke-width="12" /></div>
            <div class="learning-meta"><span>起点：{{ current.currentLevel }}（自信 {{ current.selfConfidence }}/5）</span><span>{{ current.durationWeeks }} 周 · 每周 {{ current.sessionsPerWeek }} 次</span><span>每次约 {{ current.minutesPerSession }} 分钟</span></div>

            <section v-if="current.weakAreas.length" class="learning-weakness"><div><h3>需要复习</h3><p class="muted">根据低掌握度和“需要复习”的学习记录整理。</p></div><div class="learning-weakness-list"><article v-for="area in current.weakAreas" :key="area.topic"><strong>{{ area.topic }}</strong><small>{{ area.count }} 次记录</small><p>{{ area.sessions.join('、') }}</p></article></div></section>

            <section v-for="week in current.weeks" :key="week.week" class="learning-week">
              <header><div><p class="eyebrow">第 {{ week.week }} 周</p><h3>{{ week.focus }}</h3><p class="muted">{{ week.outcome }}</p></div><span>{{ week.sessions.filter((session) => session.status === 'COMPLETED').length }}/{{ week.sessions.length }} 完成</span></header>
              <article v-for="session in week.sessions" :key="session.id" class="learning-session" :class="`is-${session.status.toLowerCase()}`">
                <div class="learning-session-heading"><div><span class="learning-session-date">{{ session.dueDate }} · {{ session.minutes }} 分钟</span><h4>{{ session.title }}</h4></div><el-tag :type="session.status === 'COMPLETED' ? 'success' : session.status === 'NEEDS_REVIEW' ? 'warning' : 'info'" effect="plain">{{ statusLabel(session.status) }}</el-tag></div>
                <p>{{ session.activity }}</p>
                <div v-if="session.sources.length" class="learning-sources"><span>学习出处</span><RouterLink v-for="(source, index) in session.sources" :key="`${source.bookId}-${source.fileId}-${source.page}-${index}`" :to="sourceHref(source, source.excerpt || '')">{{ sourceLabel(source) }}</RouterLink></div>
                <div class="learning-checkin"><label>掌握程度<el-rate v-model="session.confidence" :max="5" :texts="['需要重新学习', '不太熟悉', '基本理解', '掌握较好', '可以讲解']" show-text :disabled="savingSession === session.id" /></label><label class="learning-reflection">学习反思（选填）<el-input v-model="session.reflection" type="textarea" :rows="2" maxlength="2000" placeholder="记下卡住的地方、练习结果或想追问的问题" :disabled="savingSession === session.id" /></label></div>
                <div class="learning-session-actions"><el-button type="primary" size="small" :loading="savingSession === session.id" :disabled="!!savingSession" @click="saveSession(session, 'COMPLETED')">保存为已完成</el-button><el-button size="small" :loading="savingSession === session.id" :disabled="!!savingSession" @click="saveSession(session, 'NEEDS_REVIEW')">需要复习</el-button><el-button v-if="session.status !== 'PLANNED'" text size="small" :disabled="!!savingSession" @click="saveSession(session, 'PLANNED')">恢复待学习</el-button><el-button v-else text size="small" :disabled="!!savingSession" @click="saveSession(session, 'SKIPPED')">跳过</el-button></div>
              </article>
            </section>

            <section class="learning-report-panel"><div class="assistant-section-heading"><div><h3>学习总结与评估</h3><p class="muted">根据完成记录、掌握度、反思和所选 Book 来源生成；水平仅为学习记录推测，不是正式考试。</p></div><el-button type="primary" plain :loading="reporting" :disabled="!hasLearningRecord || !!savingSession" @click="generateReport">{{ current.report ? '更新学习报告' : '生成学习报告' }}</el-button></div>
              <div v-if="current.report" class="learning-report"><p>{{ current.report.summary }}</p><p v-if="current.report.estimatedLevel"><strong>当前水平估计：</strong>{{ current.report.estimatedLevel }}</p><div v-if="current.report.strengths?.length"><strong>已有优势</strong><ul><li v-for="(item, i) in current.report.strengths" :key="i">{{ item }}</li></ul></div><div v-if="current.report.weakAreas?.length"><strong>薄弱点与练习建议</strong><ul><li v-for="(item, i) in current.report.weakAreas" :key="i"><strong>{{ item.topic }}</strong>：{{ item.reason }} <span>{{ item.practice }}</span></li></ul></div><div v-if="current.report.nextSteps?.length"><strong>下一步</strong><ul><li v-for="(item, i) in current.report.nextSteps" :key="i">{{ item }}</li></ul></div><div v-if="current.report.sources?.length" class="learning-sources"><span>报告依据</span><RouterLink v-for="(source, i) in current.report.sources" :key="i" :to="sourceHref(source, source.excerpt || '')">{{ sourceLabel(source) }}</RouterLink></div></div>
              <p v-else class="muted">完成学习或标记需要复习，并记录掌握度后，可以生成阶段性总结和下一步建议。</p>
            </section>
          </template>
          <p v-else-if="selecting" role="status">正在读取学习路线…</p>
          <div v-else class="assistant-empty"><h2>为学习留出自己的节奏</h2><p>先设定学习目标和时间安排。路线生成后，每次学习都可以完成、标记复习或跳过；系统按你的记录持续调整总结和建议。</p></div>
        </main>
      </div>
    </template>
  </section>
</template>
