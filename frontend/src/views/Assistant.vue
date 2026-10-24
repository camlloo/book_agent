<script setup>
import { computed, onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useRouter } from "vue-router";
import { api, action, date } from "../api";
import { sourceHref } from "../citations";
import Markdown from "../components/Markdown.vue";
import CitedAnswer from "../components/CitedAnswer.vue";
import KnowledgeScopePicker from "../components/KnowledgeScopePicker.vue";

const selectedBooks = ref([]), mergeBooks = ref([]);
const router = useRouter();
const goal = ref(""), plans = ref([]), current = ref(null), recommendations = ref([]);
const loading = ref(true), loadingPlanId = ref(0), busy = ref(false), running = ref(0), error = ref("");
const planMode = ref("auto"), customSteps = ref([]);
const editingStep = ref(0), instructionDraft = ref(""), titleDraft = ref(""), toolDraft = ref("search"), savingInstruction = ref(false);
const addingStep = ref(false), newStepTool = ref("proofread"), newStepTitle = ref(""), newStepInstruction = ref(""), newAfterStepId = ref("end"), savingNewStep = ref(false);
const mergeTitle = ref(""), mergeInstruction = ref(""), mergeResult = ref(null), mergeBusy = ref(false);
const customPlanError = computed(() => stepOrderError(customSteps.value));
const canCreatePlan = computed(() => goal.value.trim() && selectedBooks.value.length && (planMode.value === "auto" || (customSteps.value.length > 0 && customSteps.value.every((step) => step.title.trim() && step.instruction.trim()) && !customPlanError.value)));
const staleResultStep = computed(() => {
  const steps = current.value?.steps || [];
  let sourceStep = null;
  for (const step of steps) {
    if (step.tool === "search" && step.status === "SUCCEEDED" && step.result?.results?.length) sourceStep = step;
    if (!sourceStep || step.stepNo <= sourceStep.stepNo || step.status !== "SUCCEEDED") continue;
    const answer = step.result?.answer || step.result?.content || "";
    if (/未检索到相关出处|没有检索到相关出处|未找到足以回答该问题|未找到与该问题相关/i.test(answer)) return { sourceStep, step };
  }
  return null;
});
let customStepSequence = 0;

function stepOrderError(steps) {
  let hasSearch = false, hasDraft = false;
  for (const step of steps) {
    if (step.tool !== "search" && !hasSearch) return "先添加检索步骤，才能总结、比较、起草或校对。";
    const proofreading = step.tool === "proofread" || (step.tool === "compare" && /校对|译文|译稿|翻译|漏译|误译|proofread|translation|translate/i.test(step.instruction || ""));
    if (proofreading && !hasDraft) return "校对步骤前需要先添加起草步骤。";
    if (step.tool === "search") hasSearch = true;
    if (step.tool === "draft") hasDraft = true;
  }
  return "";
}

async function load() {
  loading.value = true; error.value = "";
  try {
    const [planList, tips] = await Promise.all([api("/api/assistant/plans"), api("/api/assistant/recommendations")]);
    plans.value = planList; recommendations.value = tips;
  } catch (e) { error.value = e.message; }
  finally { loading.value = false; }
}
async function selectPlan(item) {
  if (busy.value || running.value || loadingPlanId.value) return;
  loadingPlanId.value = item.id; current.value = null; error.value = "";
  try { current.value = await api(`/api/assistant/plans/${item.id}`); }
  catch (e) { error.value = e.message; ElMessage.error(e.message); }
  finally { loadingPlanId.value = 0; }
}
async function createPlan() {
  if (!canCreatePlan.value || busy.value) return;
  busy.value = true; error.value = "";
  try {
    const body = { goal: goal.value.trim(), bookIds: selectedBooks.value };
    if (planMode.value === "custom") body.steps = customSteps.value.map(({ tool, title, instruction }) => ({ tool, title: title.trim(), instruction: instruction.trim() }));
    current.value = await api("/api/assistant/plans", { method: "POST", body });
    goal.value = ""; plans.value = await api("/api/assistant/plans");
    ElMessage.success("计划已保存，可以逐步执行每个知识工具。");
  } catch (e) { error.value = e.message; }
  finally { busy.value = false; }
}
function addCustomStep() {
  if (customSteps.value.length >= 6) return;
  const tool = customSteps.value.length ? "draft" : "search";
  const label = ({ search: "检索相关内容", summarize: "总结要点", compare: "比较资料", draft: "起草内容", proofread: "对照来源校对" })[tool];
  customSteps.value.push({ key: ++customStepSequence, tool, title: label, instruction: "" });
}
function moveCustomStep(index, delta) {
  const next = index + delta;
  if (next < 0 || next >= customSteps.value.length) return;
  const items = [...customSteps.value];
  [items[index], items[next]] = [items[next], items[index]];
  customSteps.value = items;
}
function removeCustomStep(index) { customSteps.value.splice(index, 1); }
async function runStep(step) {
  if (!current.value || running.value) return;
  running.value = step.id; error.value = "";
  try { current.value = await api(`/api/assistant/plans/${current.value.id}/steps/${step.id}/run`, { method: "POST" }); }
  catch (e) { error.value = e.message; }
  finally { running.value = 0; }
}
function editStep(step) {
  if (editingStep.value === step.id) { editingStep.value = 0; return; }
  editingStep.value = step.id;
  instructionDraft.value = step.instruction;
  titleDraft.value = step.title;
  toolDraft.value = step.tool;
}
async function saveStepInstruction(step) {
  if (!current.value || !instructionDraft.value.trim() || savingInstruction.value) return;
  const proposed = current.value.steps.map((item) => item.id === step.id ? { ...item, tool: toolDraft.value, title: titleDraft.value, instruction: instructionDraft.value } : item);
  const orderError = stepOrderError(proposed);
  if (orderError) { ElMessage.warning(orderError); return; }
  const invalidatesResults = current.value.steps.some((item) => item.stepNo >= step.stepNo && item.status === "SUCCEEDED");
  if (invalidatesResults) {
    try { await ElMessageBox.confirm("修改后会清除这一步及后续步骤的结果，前面的检索结果会保留。", "调整计划步骤", { confirmButtonText: "清除并保存", cancelButtonText: "继续查看" }); }
    catch { return; }
  }
  savingInstruction.value = true; error.value = "";
  try {
    current.value = await api(`/api/assistant/plans/${current.value.id}/steps/${step.id}`, { method: "PUT", body: { tool: toolDraft.value, title: titleDraft.value.trim(), instruction: instructionDraft.value.trim() } });
    plans.value = await api("/api/assistant/plans");
    editingStep.value = 0;
    ElMessage.success("步骤要求已更新，可从这一步继续执行。");
  } catch (e) { error.value = e.message; }
  finally { savingInstruction.value = false; }
}
async function addPlanStep() {
  if (!current.value || current.value.steps.length >= 6 || !newStepTitle.value.trim() || !newStepInstruction.value.trim() || savingNewStep.value) return;
  const afterStepNo = newAfterStepId.value === "end" ? current.value.steps.length : current.value.steps.find((item) => item.id === Number(newAfterStepId.value))?.stepNo || current.value.steps.length;
  const candidate = [...current.value.steps];
  candidate.splice(afterStepNo, 0, { tool: newStepTool.value });
  const orderError = stepOrderError(candidate);
  if (orderError) { ElMessage.warning(orderError); return; }
  const invalidatesResults = current.value.steps.some((item) => item.stepNo > afterStepNo && item.status === "SUCCEEDED");
  if (invalidatesResults) {
    try { await ElMessageBox.confirm("插入到中间会清除新步骤之后的结果，前面的检索结果会保留。", "插入计划步骤", { confirmButtonText: "插入并清除后续结果", cancelButtonText: "继续查看" }); }
    catch { return; }
  }
  savingNewStep.value = true; error.value = "";
  try {
    const afterStepId = newAfterStepId.value === "end" ? null : Number(newAfterStepId.value);
    current.value = await api(`/api/assistant/plans/${current.value.id}/steps`, { method: "POST", body: { tool: newStepTool.value, title: newStepTitle.value.trim(), instruction: newStepInstruction.value.trim(), afterStepId } });
    plans.value = await api("/api/assistant/plans");
    addingStep.value = false; newStepTitle.value = ""; newStepInstruction.value = ""; newAfterStepId.value = "end";
    ElMessage.success("新步骤已插入计划。");
  } catch (e) { error.value = e.message; }
  finally { savingNewStep.value = false; }
}
async function movePlanStep(step, delta) {
  if (!current.value || running.value || savingInstruction.value) return;
  const position = step.stepNo + delta;
  if (position < 1 || position > current.value.steps.length) return;
  const candidate = [...current.value.steps];
  const [moving] = candidate.splice(step.stepNo - 1, 1);
  candidate.splice(position - 1, 0, moving);
  const orderError = stepOrderError(candidate);
  if (orderError) { ElMessage.warning(orderError); return; }
  const invalidatesResults = current.value.steps.some((item) => item.stepNo >= Math.min(step.stepNo, position) && item.status === "SUCCEEDED");
  if (invalidatesResults) {
    try { await ElMessageBox.confirm("调整顺序会清除受影响步骤及后续结果，较早的检索结果会保留。", "调整计划顺序", { confirmButtonText: "调整并清除结果", cancelButtonText: "继续查看" }); }
    catch { return; }
  }
  await action(async () => {
    current.value = await api(`/api/assistant/plans/${current.value.id}/steps/${step.id}/position`, { method: "PUT", body: { position } });
    plans.value = await api("/api/assistant/plans");
  });
}
async function removePlanStep(step) {
  if (!current.value || current.value.steps.length <= 1 || running.value || savingInstruction.value) return;
  const orderError = stepOrderError(current.value.steps.filter((item) => item.id !== step.id));
  if (orderError) { ElMessage.warning(orderError); return; }
  const invalidatesResults = current.value.steps.some((item) => item.stepNo >= step.stepNo && item.status === "SUCCEEDED");
  try {
    await ElMessageBox.confirm(
      invalidatesResults ? "移除后将清除这一步及后续步骤的结果，前面的步骤会保留。" : "从计划中移除这一步？",
      "移除计划步骤", { confirmButtonText: "移除步骤", cancelButtonText: "保留步骤" });
  } catch { return; }
  await action(async () => {
    current.value = await api(`/api/assistant/plans/${current.value.id}/steps/${step.id}`, { method: "DELETE" });
    plans.value = await api("/api/assistant/plans");
  });
}
async function resetPlanFromStep(step) {
  if (!current.value || running.value) return;
  try { await ElMessageBox.confirm(`将保留前序来源，清除第 ${step.stepNo} 步及后续结果；你可以逐步重新执行。`, "重跑计划后续步骤", { confirmButtonText: "清除并重跑", cancelButtonText: "暂不处理" }); }
  catch { return; }
  await action(async () => {
    current.value = await api(`/api/assistant/plans/${current.value.id}/steps/${step.id}/reset`, { method: "POST", body: {} });
    plans.value = await api("/api/assistant/plans");
    ElMessage.success("旧结果已清除，之前的检索来源仍保留。现在可以重新执行后续步骤。");
  });
}
function canRunStep(step) {
  return current.value?.steps?.filter((item) => item.stepNo < step.stepNo).every((item) => item.status === "SUCCEEDED") ?? false;
}
async function removePlan(item) {
  try { await ElMessageBox.confirm("删除计划与步骤结果？已创建的 Book 草稿不会删除。", "删除 Agent 计划", { confirmButtonText: "删除计划", cancelButtonText: "保留" }); }
  catch { return; }
  await action(async () => {
    await api(`/api/assistant/plans/${item.id}`, { method: "DELETE" });
    plans.value = plans.value.filter((plan) => plan.id !== item.id);
    if (current.value?.id === item.id) current.value = null;
  });
}
async function previewMerge() {
  if (mergeBooks.value.length < 2 || !mergeTitle.value.trim() || !mergeInstruction.value.trim()) return;
  mergeBusy.value = true; mergeResult.value = null; error.value = "";
  try {
    mergeResult.value = await api("/api/assistant/merge/preview", { method: "POST", body: { title: mergeTitle.value.trim(), instructions: mergeInstruction.value.trim(), bookIds: mergeBooks.value } });
  } catch (e) { error.value = e.message; }
  finally { mergeBusy.value = false; }
}
async function saveDraft(result, title, ids) {
  const content = result?.content;
  if (!content?.trim()) return;
  try { await ElMessageBox.confirm("将当前草稿另存为一本新的私有 Book。它会进入索引队列，仍由你决定是否发布。", "保存为新 Book", { confirmButtonText: "创建私有草稿", cancelButtonText: "继续修改" }); }
  catch { return; }
  await action(async () => {
    const book = await api("/api/assistant/drafts", { method: "POST", body: { title: title || "整理后的知识", summary: "", content, sourceBookIds: ids } });
    ElMessage.success("新 Book 草稿已创建并开始建立索引。");
    await router.push(`/admin/books/${book.id}`);
  });
}
function stepLabel(tool) { return ({ search: "检索知识", summarize: "总结已有来源", compare: "比较已有来源", draft: "起草新 Book", proofread: "对照来源校对" })[tool] || tool; }
function sourceLink(source) { return sourceHref(source, source.excerpt || ""); }
function followCitation(source, quote) { router.push(sourceLink({ ...source, excerpt: quote })); }

onMounted(load);
</script>

<template>
  <section class="assistant-page">
    <div class="page-heading"><div><h1>笔记与 Agent</h1><p>先制定目标，再在你授权的 Book 范围内逐步检索、总结、对比和起草。</p></div></div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="loading" class="knowledge-panel">正在读取你的知识与计划…</div>
    <template v-else>
      <section class="knowledge-panel assistant-plan-create">
        <div><h2>从一个目标开始</h2><p class="muted">你可以按目标自动规划，也可以手动编排步骤；总结、起草和校对会接续已有来源。</p></div>
        <fieldset class="assistant-plan-mode"><legend>计划方式</legend><el-radio-group v-model="planMode"><el-radio value="auto">按目标自动安排</el-radio><el-radio value="custom">自己设计步骤</el-radio></el-radio-group></fieldset>
        <div v-if="planMode === 'custom'" class="assistant-custom-plan">
          <div class="assistant-custom-plan-heading"><div><strong>自定义执行步骤</strong><p class="muted">步骤会按顺序执行。总结、比较、起草和校对前都需要检索；校对前还需要有起草步骤。</p></div><el-button size="small" :disabled="customSteps.length >= 6" @click="addCustomStep">添加步骤</el-button></div>
          <article v-for="(step, index) in customSteps" :key="step.key" class="assistant-custom-step">
            <span class="assistant-step-number">{{ index + 1 }}</span>
            <div class="assistant-custom-step-fields"><label>步骤类型<el-select v-model="step.tool"><el-option label="检索知识" value="search" /><el-option label="总结已有来源" value="summarize" /><el-option label="比较已有来源" value="compare" /><el-option label="起草内容" value="draft" /><el-option label="对照来源校对" value="proofread" /></el-select></label><label>步骤名称<el-input v-model="step.title" maxlength="100" /></label><label class="assistant-custom-step-instruction">执行要求<el-input v-model="step.instruction" type="textarea" :rows="2" maxlength="500" show-word-limit /></label></div>
            <div class="assistant-custom-step-actions"><el-button text :disabled="index === 0" @click="moveCustomStep(index, -1)">上移</el-button><el-button text :disabled="index === customSteps.length - 1" @click="moveCustomStep(index, 1)">下移</el-button><el-button text type="danger" @click="removeCustomStep(index)">移除</el-button></div>
          </article>
          <p v-if="!customSteps.length" class="muted">添加步骤并填写每一步的执行要求。</p>
          <p v-if="customPlanError" class="error" role="alert">{{ customPlanError }}</p>
        </div>
        <label for="agent-scope">授权知识范围</label>
        <KnowledgeScopePicker id="agent-scope" v-model="selectedBooks" :max-books="50" :disabled="busy" label="选择 Agent 授权 Book 或文件夹" />
        <label for="agent-goal">你希望完成什么？</label>
        <el-input id="agent-goal" v-model="goal" type="textarea" :rows="3" maxlength="1000" placeholder="例如：比较这些资料对 Python 异常处理的建议，再起草一份适合初学者的实践指南。" />
        <div class="assistant-form-actions"><span class="muted">计划会保存在当前账号中。执行前会再次核对 Book 权限与版本。</span><el-button type="primary" :loading="busy" :disabled="!canCreatePlan" @click="createPlan">{{ planMode === 'auto' ? '生成并保存计划' : '保存自定义计划' }}</el-button></div>
      </section>

      <div class="assistant-layout">
        <aside class="knowledge-panel assistant-plan-list"><div class="assistant-section-heading"><h2>已保存计划</h2><span>{{ plans.length }}</span></div>
          <button v-for="plan in plans" :key="plan.id" class="assistant-plan-item" :class="{ selected: current?.id === plan.id || loadingPlanId === plan.id }" :disabled="!!loadingPlanId" @click="selectPlan(plan)"><strong>{{ plan.plan?.title || plan.goal }}</strong><small>{{ plan.status }} · {{ date(plan.updatedAt) }}</small></button>
          <p v-if="!plans.length" class="muted">创建后的计划会显示在这里。</p>
        </aside>
        <main class="knowledge-panel assistant-plan-detail" :aria-busy="!!loadingPlanId">
          <div v-if="loadingPlanId" class="assistant-plan-loading" role="status" aria-live="polite">正在载入计划步骤与结果…</div>
          <template v-else-if="current">
            <div class="assistant-section-heading"><div><h2>{{ current.plan?.title || current.goal }}</h2><p class="muted">目标：{{ current.goal }}</p></div><el-button text type="danger" :disabled="!!running" @click="removePlan(current)">删除计划</el-button></div>
            <div v-if="staleResultStep" class="assistant-stale-results" role="status"><div><strong>后续步骤保存了旧结果</strong><p>第 {{ staleResultStep.sourceStep.stepNo }} 步已检索到原文，第 {{ staleResultStep.step.stepNo }} 步却回答“未找到出处”。这份旧计划不会自动重算；你可以保留检索来源并重跑后续步骤。</p></div><el-button type="primary" :disabled="!!running" @click="resetPlanFromStep(staleResultStep.step)">保留来源，重跑第 {{ staleResultStep.step.stepNo }} 步及后续</el-button></div>
            <ol class="assistant-steps"><li v-for="step in current.steps" :key="step.id" class="assistant-step">
              <div class="assistant-step-heading"><span class="assistant-step-number">{{ step.stepNo }}</span><div><strong>{{ step.title }}</strong><small>{{ stepLabel(step.tool) }} · {{ step.status }}</small></div>
                <div class="assistant-step-actions"><el-button size="small" :disabled="!!running || savingInstruction || step.stepNo <= 1" @click="movePlanStep(step, -1)">上移</el-button><el-button size="small" :disabled="!!running || savingInstruction || step.stepNo >= current.steps.length" @click="movePlanStep(step, 1)">下移</el-button><el-button size="small" :disabled="!!running || savingInstruction || current.steps.length <= 1" @click="removePlanStep(step)">移除</el-button><el-button size="small" :disabled="!!running || savingInstruction" @click="editStep(step)">{{ editingStep === step.id ? '取消调整' : '调整要求' }}</el-button><el-button v-if="step.status !== 'SUCCEEDED'" size="small" :loading="running === step.id" :disabled="!!running || savingInstruction || editingStep === step.id || !canRunStep(step)" :title="canRunStep(step) ? '' : '请先完成前面的步骤'" @click="runStep(step)">{{ step.status === 'FAILED' ? '重试' : '执行' }}</el-button></div>
              </div>
              <div v-if="editingStep === step.id" class="assistant-instruction-editor"><div class="assistant-step-fields"><label>步骤名称<el-input v-model="titleDraft" maxlength="100" /></label><label>步骤类型<el-select v-model="toolDraft"><el-option label="检索知识" value="search" /><el-option label="总结已有来源" value="summarize" /><el-option label="比较已有来源" value="compare" /><el-option label="起草内容" value="draft" /><el-option label="对照来源校对" value="proofread" /></el-select></label></div><label>执行要求<el-input v-model="instructionDraft" type="textarea" :rows="3" maxlength="500" show-word-limit /></label><div><el-button size="small" :disabled="!instructionDraft.trim() || !titleDraft.trim()" :loading="savingInstruction" @click="saveStepInstruction(step)">保存步骤</el-button></div></div>
              <p v-else class="assistant-instruction">{{ step.instruction }}</p>
              <p v-if="step.error" class="error">{{ step.error }}</p>
              <div v-if="step.result?.answer" class="assistant-result"><CitedAnswer :content="step.result.answer" :sources="step.result.sources || []" @cite="followCitation" /></div>
              <div v-else-if="step.result?.content" class="assistant-result"><ul v-if="step.result.findings?.length" class="assistant-finding-list"><li v-for="(finding, i) in step.result.findings" :key="i">{{ finding }}</li></ul><Markdown :content="step.result.content" /><ul class="assistant-source-links"><li v-for="(source, i) in step.result.sources || []" :key="i"><RouterLink :to="sourceLink(source)">来源 {{ i + 1 }}：{{ source.bookTitle }} · {{ source.sourceName }}<span v-if="source.page"> · 第 {{ source.page }} 页</span></RouterLink></li></ul>
                <el-button type="primary" @click="saveDraft(step.result, step.title, current.bookIds)">另存为新的私有 Book 草稿</el-button>
              </div>
              <div v-else-if="step.result?.results" class="assistant-result"><p v-if="!step.result.results.length" class="muted">没有找到相关原文。</p><article v-for="source in step.result.results" :key="source.chunkId" class="assistant-hit"><RouterLink :to="sourceLink(source)">{{ source.bookTitle }} · {{ source.sourceName }}<span v-if="source.page"> · 第 {{ source.page }} 页</span></RouterLink><p>{{ source.excerpt }}</p></article></div>
            </li></ol>
            <div v-if="current.steps.length < 6" class="assistant-add-step"><el-button v-if="!addingStep" :disabled="!!running || savingInstruction" @click="addingStep = true">添加步骤</el-button><div v-else class="assistant-instruction-editor"><div class="assistant-step-fields"><label>插入位置<el-select v-model="newAfterStepId"><el-option label="计划末尾" value="end" /><el-option v-for="step in current.steps" :key="step.id" :label="`第 ${step.stepNo} 步「${step.title}」之后`" :value="String(step.id)" /></el-select></label><label>步骤类型<el-select v-model="newStepTool"><el-option label="检索知识" value="search" /><el-option label="总结已有来源" value="summarize" /><el-option label="比较已有来源" value="compare" /><el-option label="起草内容" value="draft" /><el-option label="对照来源校对" value="proofread" /></el-select></label><label>步骤名称<el-input v-model="newStepTitle" maxlength="100" /></label></div><label>执行要求<el-input v-model="newStepInstruction" type="textarea" :rows="3" maxlength="500" show-word-limit /></label><div><el-button :disabled="!!running" @click="addingStep = false">取消</el-button><el-button type="primary" :disabled="!newStepTitle.trim() || !newStepInstruction.trim()" :loading="savingNewStep" @click="addPlanStep">插入到计划</el-button></div></div></div>
          </template>
          <div v-else class="assistant-empty"><h2>让知识替你推进任务</h2><p>计划会明确指出要查哪些资料、做什么总结或对比。起草结果只会在你确认后另存为私有 Book。</p></div>
        </main>
      </div>

      <section class="knowledge-panel assistant-merge">
        <div><h2>合并多本 Book</h2><p class="muted">先预览带来源标记的合并草稿，再决定是否另存为新的私有 Book。</p></div>
        <label for="merge-scope">来源 Book（2–10 本）</label>
        <KnowledgeScopePicker id="merge-scope" v-model="mergeBooks" :max-books="10" :disabled="mergeBusy" label="选择合并来源 Book 或文件夹" />
        <div class="assistant-merge-fields"><div><label for="merge-title">新 Book 标题</label><el-input id="merge-title" v-model="mergeTitle" maxlength="128" placeholder="例如：Python 错误处理实践" /></div><div><label for="merge-instructions">合并要求</label><el-input id="merge-instructions" v-model="mergeInstruction" maxlength="1000" placeholder="整合共同点，保留差异，并标记每段出处" /></div></div>
        <el-button type="primary" :loading="mergeBusy" :disabled="mergeBooks.length < 2 || !mergeTitle.trim() || !mergeInstruction.trim()" @click="previewMerge">生成合并预览</el-button>
        <div v-if="mergeResult" class="assistant-merge-preview"><Markdown :content="mergeResult.content" /><ul class="assistant-source-links"><li v-for="(source, i) in mergeResult.sources || []" :key="i"><RouterLink :to="sourceLink(source)">来源 {{ i + 1 }}：{{ source.bookTitle }} · {{ source.sourceName }}<span v-if="source.page"> · 第 {{ source.page }} 页</span></RouterLink></li></ul><el-button type="primary" @click="saveDraft(mergeResult, mergeTitle, mergeBooks)">确认并另存为私有 Book 草稿</el-button></div>
      </section>

      <section class="knowledge-panel assistant-recommendations"><div class="assistant-section-heading"><div><h2>可以继续整理的知识</h2><p class="muted">根据你自己的 Book 状态给出可执行建议。</p></div><el-button text @click="action(async()=>recommendations=await api('/api/assistant/recommendations'))">刷新</el-button></div>
        <ul v-if="recommendations.length" class="assistant-tip-list"><li v-for="(tip, i) in recommendations" :key="`${tip.bookId}-${tip.kind}-${i}`"><div><strong>{{ tip.title }}</strong><p>{{ tip.message }}</p></div><RouterLink :to="`/admin/books/${tip.bookId}`">打开 Book</RouterLink></li></ul>
        <p v-else class="muted">当前没有需要优先处理的 Book。</p>
      </section>
    </template>
  </section>
</template>
