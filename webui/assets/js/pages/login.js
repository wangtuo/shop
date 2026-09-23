// 登录 / 注册
import { post, auth } from '../lib/http.js';
import { toastError, toastSuccess, escapeHtml } from '../lib/ui.js';
import { go } from '../lib/router.js';

let mode = 'login';

export async function renderLogin({ el }) {
  el.innerHTML = `
  <div class="auth-wrap">
    <div class="auth-top">
      <div class="brand">🛍️ Shop 商城</div>
      <p>${mode === 'login' ? '欢迎回来，请登录账号' : '注册新账号，开启购物之旅'}</p>
    </div>
    <div class="auth-tabs">
      <span data-m="login" class="${mode === 'login' ? 'active' : ''}">登录</span>
      <span data-m="register" class="${mode === 'register' ? 'active' : ''}">注册</span>
    </div>
    <form id="auth-form" autocomplete="off">
      ${mode === 'register' ? `
      <div class="form-item">
        <input class="input" name="username" placeholder="用户名（4-20位）" maxlength="20" />
      </div>` : ''}
      <div class="form-item">
        <input class="input" name="account" placeholder="${mode === 'register' ? '手机号（作为登录账号）' : '用户名 / 手机号'}" maxlength="20" />
      </div>
      ${mode === 'register' ? `
      <div class="form-item">
        <input class="input" name="phone" placeholder="手机号" maxlength="11" />
      </div>
      <div class="form-item">
        <input class="input" name="nickname" placeholder="昵称（选填）" maxlength="20" />
      </div>` : ''}
      <div class="form-item">
        <input class="input" name="password" type="password" placeholder="密码（8-32位，含字母与数字）" maxlength="32" />
      </div>
      <div class="field-error" id="auth-err" style="min-height:18px"></div>
      <button class="btn btn-primary btn-block btn-lg" type="submit" id="auth-submit">
        ${mode === 'register' ? '注 册' : '登 录'}
      </button>
    </form>
    <p class="muted tiny" style="margin-top:20px;text-align:center">
      本地演示环境 · 登录即表示同意《用户协议》与《隐私政策》
    </p>
  </div>`;

  el.querySelectorAll('.auth-tabs span').forEach((s) => {
    s.onclick = () => { mode = s.dataset.m; renderLogin({ el }); };
  });

  const form = el.querySelector('#auth-form');
  form.onsubmit = async (e) => {
    e.preventDefault();
    const fd = new FormData(form);
    const v = Object.fromEntries(fd.entries());
    const errEl = el.querySelector('#auth-err');
    errEl.textContent = '';

    if (mode === 'login') {
      if (!v.account || !v.password) { errEl.textContent = '请输入账号和密码'; return; }
    } else {
      if (!v.username || !v.phone || !v.password) { errEl.textContent = '请完整填写注册信息'; return; }
      if (!/^1[3-9]\d{9}$/.test(v.phone)) { errEl.textContent = '手机号格式不正确'; return; }
      if (v.password.length < 8) { errEl.textContent = '密码至少 8 位'; return; }
    }

    const btn = el.querySelector('#auth-submit');
    btn.disabled = true; btn.textContent = '请稍候…';
    try {
      if (mode === 'register') {
        await post('/api/user/auth/register', {
          username: v.username.trim(),
          phone: v.phone.trim(),
          password: v.password,
          nickname: v.nickname?.trim() || v.username.trim(),
        });
        toastSuccess('注册成功，正在登录…');
        const data = await post('/api/user/auth/login', { account: v.phone, password: v.password });
        saveAndEnter(data, v.username);
      } else {
        const data = await post('/api/user/auth/login', { account: v.account.trim(), password: v.password });
        saveAndEnter(data);
      }
    } catch (err) {
      errEl.textContent = escapeHtml(err.message || '操作失败');
      btn.disabled = false;
      btn.textContent = mode === 'register' ? '注 册' : '登 录';
    }
  };
}

function saveAndEnter(data, fallbackName) {
  if (!data?.token) { toastError('登录返回缺少 token'); return; }
  auth.token = data.token;
  auth.user = {
    userId: data.userId ?? data.uid,
    username: data.name ?? data.username ?? fallbackName ?? '',
    nickname: data.nickname ?? '',
    utype: data.userType ?? data.utype ?? 0,
    merchantId: data.merchantId ?? null,
    level: data.level ?? 0,
  };
  toastSuccess('登录成功');
  const ret = new URLSearchParams(location.hash.split('?')[1] || '').get('redirect');
  go(ret || '/');
}
