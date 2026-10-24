<script setup>
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { api } from '../api';
import { useAuth } from '../stores/auth';
const form=reactive({username:'',nickname:'',password:''}),busy=ref(false),error=ref('');
const router=useRouter(),auth=useAuth();
async function submit(){busy.value=true;error.value='';try{await api('/api/auth/register',{method:'POST',body:form});await auth.login({username:form.username,password:form.password});router.replace('/admin/books');}catch(e){error.value=e.message;}finally{busy.value=false;}}
</script>
<template><section class="login"><div class="login-note"><h1>建立你的<br>个人知识库。</h1><p>把文件、网页与项目整理成 Book，<br>在自己的知识中搜索、阅读和对话。</p></div><form class="login-form" @submit.prevent="submit"><h2>创建账号</h2><p>新建内容默认私有，由你决定分享与发布。</p><label for="register-user">用户名</label><el-input id="register-user" v-model="form.username" pattern="[A-Za-z0-9_]{3,32}" maxlength="32" autocomplete="username" required placeholder="3–32 位字母、数字或下划线"/><label for="register-name">昵称</label><el-input id="register-name" v-model="form.nickname" maxlength="32" autocomplete="nickname"/><label for="register-password">密码</label><el-input id="register-password" v-model="form.password" type="password" show-password minlength="8" maxlength="64" autocomplete="new-password" required placeholder="至少 8 位"/><p v-if="error" class="error" role="alert">{{error}}</p><el-button type="primary" native-type="submit" :loading="busy" class="full">创建并进入知识库</el-button><RouterLink to="/login" class="back-link">已有账号，去登录</RouterLink></form></section></template>
