import { CONFIG } from '../config.js';

// ---------- token / 当前用户 sessionStorage ----------
export const auth = {
  get token() { return localStorage.getItem(TOKEN_KEY) || ''; },
  set token(v) { v ? localStorage.setItem(TOKEN_KEY, v) : localStorage.removeItem(TOKEN_KEY); },
  get user() {
    try { return JSON.parse(localStorage.getItem(USER_KEY) || 'null'); }
    catch (_) { return null; }
  },
  set user(v) { v ? localStorage.setItem(USER_KEY, JSON.stringify(v)) : localStorage.removeItem(USER_KEY); },
  get isLogin() { return !!this.token; },
  logout() { this.token = ''; this.user = null; },
};
const TOKEN_KEY = CONFIG.TOKEN_KEY;
const USER_KEY = CONFIG.USER_KEY;

// ---------- 统一 Result 包装的业务异常 ----------
export class ApiError extends Error {
  constructor(code, message, data) {
    super(message);
    this.code = code;
    this.data = data;
  }
}

/**
 * 大整数无损解析：后端雪花 ID 为 19 位 Long，超出 JS 安全整数。
 * 扫描原始 JSON，把字符串外、长度 >=16 的纯整数 token 加引号转为字符串，
 * 再交给原生 JSON.parse。状态/金额等小整数不受影响。
 */
export function parseBigJson(text) {
  let out = '';
  let i = 0;
  const n = text.length;
  while (i < n) {
    const ch = text[i];
    if (ch === '"') {
      // 原样复制字符串字面量（处理转义）
      let j = i + 1;
      while (j < n) {
        if (text[j] === '\\') { j += 2; continue; }
        if (text[j] === '"') { j += 1; break; }
        j += 1;
      }
      out += text.slice(i, j);
      i = j;
      continue;
    }
    if (ch === '-' || (ch >= '0' && ch <= '9')) {
      let j = i + 1;
      while (j < n && /[0-9.eE+\-]/.test(text[j])) j += 1;
      const tok = text.slice(i, j);
      if (/^-?\d{16,}$/.test(tok)) out += `"${tok}"`;
      else out += tok;
      i = j;
      continue;
    }
    out += ch;
    i += 1;
  }
  return JSON.parse(out);
}

let toastFn = null;
export function bindToast(fn) { toastFn = fn; }

/**
 * 统一请求：注入 JWT，解包 Result {code,message,data,success}
 * code===0 或 200 返回 data；其余抛 ApiError
 */
export async function http(method, url, body, opts = {}) {
  const headers = { ...(opts.headers || {}) };
  if (body && !(body instanceof FormData)) headers['Content-Type'] = 'application/json';
  if (auth.token) headers['Authorization'] = `Bearer ${auth.token}`;

  const resp = await fetch(CONFIG.BASE + url, {
    method,
    headers,
    body: body ? (body instanceof FormData ? body : JSON.stringify(body)) : undefined,
  });

  if (resp.status === 401 || resp.status === 403) {
    auth.logout();
    if (location.hash !== '#/login') location.hash = '/login';
    throw new ApiError(resp.status, '登录已过期，请重新登录');
  }

  let json;
  try { json = parseBigJson(await resp.text()); }
  catch (_) { throw new ApiError(resp.status, `服务暂不可用 (HTTP ${resp.status})`); }

  if (json && typeof json === 'object' && 'code' in json) {
    if (json.code === 0 || json.code === 200) return json.data;
    throw new ApiError(json.code, json.message || '请求失败', json.data);
  }
  if (resp.ok) return json;
  throw new ApiError(resp.status, json?.message || `请求失败 (HTTP ${resp.status})`);
}

export const get = (u, opts) => http('GET', u, null, opts);
export const post = (u, b, opts) => http('POST', u, b, opts);
export const put = (u, b, opts) => http('PUT', u, b, opts);
export const del = (u, b, opts) => http('DELETE', u, b, opts);
