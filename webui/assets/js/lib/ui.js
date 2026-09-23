// 通用 UI 工具：toast / 确认框 / 格式化
import { bindToast } from './http.js';

// ---------- Toast ----------
function toast(msg, type = '', duration = 2200) {
  const root = document.getElementById('toast-root');
  const el = document.createElement('div');
  el.className = `toast ${type}`;
  el.textContent = msg;
  root.appendChild(el);
  setTimeout(() => { el.style.opacity = '0'; el.style.transition = 'opacity .3s'; setTimeout(() => el.remove(), 320); }, duration);
}
bindToast(toast);
export { toast };

export function toastSuccess(m) { toast(m, 'success'); }
export function toastError(m) { toast(m, 'error', 3000); }

// ---------- Confirm modal ----------
export function confirm({ title = '提示', content = '', okText = '确定', cancelText = '取消', danger = false } = {}) {
  return new Promise((resolve) => {
    const mask = document.createElement('div');
    mask.className = 'mask';
    mask.innerHTML = `<div class="modal-card">
      <h3>${escapeHtml(title)}</h3>
      <p style="color:var(--text-2);font-size:13px">${escapeHtml(content)}</p>
      <div class="modal-btns">
        <button class="btn" data-a="0">${escapeHtml(cancelText)}</button>
        <button class="btn ${danger ? 'btn-danger' : 'btn-primary'}" data-a="1">${escapeHtml(okText)}</button>
      </div>
    </div>`;
    document.body.appendChild(mask);
    mask.addEventListener('click', (e) => {
      if (e.target === mask || e.target.dataset.a === '0') { mask.remove(); resolve(false); }
      if (e.target.dataset.a === '1') { mask.remove(); resolve(true); }
    });
  });
}

export function escapeHtml(s) {
  if (s === null || s === undefined) return '';
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// ---------- 格式化 ----------
export function fen2yuan(fen) {
  if (fen === null || fen === undefined || fen === '') return '0.00';
  return (Number(fen) / 100).toFixed(2);
}
export function yuan2fen(y) { return Math.round(Number(y) * 100); }

export function fmtTime(t) {
  if (!t) return '';
  const d = new Date(typeof t === 'number' ? (t > 1e12 ? t : t * 1000) : String(t).replace(/-/g, '/'));
  if (isNaN(d.getTime())) return String(t);
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

export function fmtDate(t) {
  return fmtTime(t).slice(0, 10);
}

// 金额可能以元字符串/数字返回，统一展示（后端 Amount 序列化多为字符串元）
export function money(v) {
  if (v === null || v === undefined || v === '') return '0.00';
  const n = Number(v);
  return isNaN(n) ? String(v) : n.toFixed(2);
}

// ---------- DOM helper ----------
export function h(html) {
  const tpl = document.createElement('template');
  tpl.innerHTML = html.trim();
  return tpl.content.firstElementChild;
}

// 无限滚动
export function infiniteScroll(onLoad, { distance = 120 } = {}) {
  const fn = async () => {
    if (window.__loading || window.__noMore) return;
    if (window.innerHeight + window.scrollY >= document.body.offsetHeight - distance) {
      window.__loading = true;
      try { await onLoad(); } finally { window.__loading = false; }
    }
  };
  window.addEventListener('scroll', fn);
  return () => window.removeEventListener('scroll', fn);
}
