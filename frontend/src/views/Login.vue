<script setup>
import { ref, reactive } from "vue";
import { useRouter, useRoute } from "vue-router";
import { useAuth } from "../stores/auth";
const credentials = reactive({ username: "", password: "" }),
  busy = ref(false),
  error = ref("");
const router = useRouter(),
  route = useRoute(),
  auth = useAuth();
async function submit() {
  busy.value = true;
  error.value = "";
  try {
    await auth.login(credentials);
    const dest = String(route.query.redirect || "/admin/books");
    await router.replace(
      dest.startsWith("/admin/") || /^\/books(?:\/\d+)?$/.test(dest)
        ? dest
        : "/admin/books",
    );
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <section class="login">
    <div class="login-note">
      <h1>给思考一个<br />安静的地方。</h1>
      <p>从一个想法，到一本 Book。<br />记录、整理，再与你的读者分享。</p>
    </div>
    <form class="login-form" @submit.prevent="submit">
      <h2>进入写作台</h2>
      <p>登录后管理个人 Book、采集来源与知识对话。</p>
      <label for="username">用户名</label
      ><el-input
        id="username"
        v-model="credentials.username"
        autocomplete="username"
        required
        maxlength="32"
      /><label for="password">密码</label
      ><el-input
        id="password"
        v-model="credentials.password"
        type="password"
        show-password
        autocomplete="current-password"
        required
        minlength="6"
        maxlength="64"
      />
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <el-button
        type="primary"
        native-type="submit"
        :loading="busy"
        class="full"
        >登录</el-button
      ><RouterLink to="/register" class="back-link">创建个人账号</RouterLink><RouterLink to="/" class="back-link">返回书库</RouterLink>
    </form>
  </section>
</template>
