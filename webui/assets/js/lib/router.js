// 极简 hash 路由
const routes = []; // {pattern: RegExp, keys: [], handler}
let notFoundHandler = null;
let beforeHooks = [];
let currentCleanup = null;

export function path() {
  return location.hash.slice(1) || '/';
}

export function go(url) {
  if (url.startsWith('http')) { location.href = url; return; }
  if (path() === url) { render(); return; }
  location.hash = url;
}

export function back() { history.back(); }

export function reload() { render(); }

export function add(pattern, handler) {
  const keys = [];
  const rx = new RegExp('^' + pattern.replace(/:[^/]+/g, (m) => {
    keys.push(m.slice(1));
    return '([^/]+)';
  }).replace(/\//g, '\\/') + '\\/?$');
  routes.push({ rx, keys, handler });
}

export function beforeEach(fn) { beforeHooks.push(fn); }

async function render() {
  const url = path();
  const [pathPart, queryPart] = url.split('?');
  const query = Object.fromEntries(new URLSearchParams(queryPart || ''));
  const app = document.getElementById('app');

  if (currentCleanup) { try { currentCleanup(); } catch (_) {} currentCleanup = null; }

  for (const hook of beforeHooks) {
    const r = await hook(pathPart, query);
    if (r === false) return;          // hook 自行处理了跳转
    if (typeof r === 'string') { go(r); return; }
  }

  for (const r of routes) {
    const m = pathPart.match(r.rx);
    if (m) {
      const params = {};
      r.keys.forEach((k, i) => { params[k] = decodeURIComponent(m[i + 1]); });
      window.scrollTo(0, 0);
      currentCleanup = await r.handler({ params, query, el: app });
      if (typeof currentCleanup !== 'function') currentCleanup = null;
      return;
    }
  }
  if (notFoundHandler) notFoundHandler(app);
}

export function notFound(fn) { notFoundHandler = fn; }

window.addEventListener('hashchange', render);

export function start() { render(); }
