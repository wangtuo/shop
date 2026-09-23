// 应用外壳（PC 版，参考淘宝 PC 站）：顶部迷你条 + Logo/大搜索/购物车 + 横向导航 + 页脚
import { escapeHtml } from './ui.js';
import { auth } from './http.js';
import { getCartCount, onChange as onCartChange } from './cart-store.js';

const NAV = [
  { path: '/', label: '首页' },
  { path: '/category', label: '全部商品分类' },
  { path: '/me/coupons', label: '领券中心' },
  { path: '/orders?tab=pay', label: '待付款' },
  { path: '/orders', label: '我的订单' },
  { path: '/aftersale', label: '售后服务' },
  { path: '/me', label: '会员中心' },
];

function miniBar() {
  const u = auth.user;
  const left = auth.isLogin
    ? `您好，<a href="#/me" class="ml6"><b>${escapeHtml(u?.nickname || u?.username || '会员')}</b></a>
       <a href="javascript:void(0)" id="hd-logout" class="ml16">退出</a>
       <a href="#/me" class="ml16">我的淘宝</a>`
    : `亲，欢迎来 Shop 商城！<a href="#/login" class="ml6">请登录</a>
       <a href="#/login" class="ml16">免费注册</a>`;
  return `
  <div class="site-minibar">
    <div class="site-container row-between">
      <div class="mb-left">${left}</div>
      <div class="mb-right">
        <a href="#/me" class="ico-pin">我的淘宝 ▾</a>
        <a href="#/cart">购物车</a>
        <a href="#/orders">我的订单</a>
        <a href="#/me/coupons">收藏夹</a>
        <a href="#/aftersale">联系客服</a>
        <a href="#/me">卖家中心</a>
      </div>
    </div>
  </div>`;
}

function mainHeader() {
  const count = getCartCount();
  return `
  <div class="site-mainheader">
    <div class="site-container mh-inner">
      <a class="site-logo" href="#/">
        <span class="logo-ico">🛍️</span><span class="logo-txt">Shop<em>商城</em></span>
      </a>
      <form class="site-search" id="hd-search-form" autocomplete="off">
        <div class="search-line">
          <input id="hd-search" type="text" placeholder="搜索商品名称，例如：耳机、运动鞋" />
          <button type="submit">搜 索</button>
        </div>
        <div class="search-hot">
          ${['耳机', '运动鞋', '保温杯', '机械键盘', '连衣裙'].map((w) => `<a href="#/search?kw=${encodeURIComponent(w)}">${w}</a>`).join('')}
        </div>
      </form>
      <a class="site-cart" href="#/cart">
        <span class="cart-ico">🛒<i id="hd-cart-badge" ${count ? '' : 'hidden'}>${count > 99 ? '99+' : count}</i></span>
        <span>购物车</span><b id="hd-cart-count" ${count ? '' : 'hidden'}>${count > 99 ? '99+' : count}</b>
      </a>
    </div>
  </div>`;
}

function navBar(active) {
  return `
  <nav class="site-nav">
    <div class="site-container nav-inner">
      <a href="#/category" class="nav-all">☰ 全部商品分类</a>
      <div class="nav-links">
        ${NAV.map((t) => `<a href="#${t.path}" class="${active === t.path ? 'active' : ''}">${t.label}</a>`).join('')}
      </div>
    </div>
  </nav>`;
}

function footer() {
  return `
  <footer class="site-footer">
    <div class="site-container">
      <div class="ft-promises">
        <span>✅ 正品保障</span><span>🚚 极速发货</span><span>↩️ 7天无理由退货</span>
        <span>🛡️ 售后无忧</span><span>⭐ 会员积分抵现</span>
      </div>
      <div class="ft-links">
        <a href="#/me/coupons">领券中心</a><i>|</i>
        <a href="#/orders">购物指南</a><i>|</i>
        <a href="#/aftersale">售后政策</a><i>|</i>
        <a href="#/me">会员中心</a><i>|</i>
        <a href="#/me/addresses">收货地址</a>
      </div>
      <p class="ft-copy">Shop 商城 · PC 版演示站点　|　本地开发环境，商品与支付数据均为 Mock　|　© 2026 Shop Mall</p>
    </div>
  </footer>`;
}

function pageBar({ title, showBack, headerRight = '' }) {
  if (!title) return '';
  return `
  <div class="page-bar">
    ${showBack ? '<button class="pb-back" id="hd-back" type="button">‹ 返回</button>' : ''}
    <h1>${escapeHtml(title)}</h1>
    <span class="flex1"></span>${headerRight}
  </div>`;
}

export function shell({ title, activeTab, showSearch = false, showBack = false, showTabbar = true, cartCount = 0, content = '', headerRight = '' }) {
  const active = activeTab || '';
  return `
  ${miniBar()}
  ${mainHeader()}
  ${navBar(active)}
  <main class="site-main">
    <div class="site-container site-content">
      ${pageBar({ title, showBack, headerRight })}
      ${content}
    </div>
  </main>
  ${footer()}`;
}

// ---- 全局事件（模块只加载一次）----
function paintBadge(n) {
  document.querySelectorAll('#hd-cart-badge,#hd-cart-count').forEach((el) => {
    el.hidden = !(n > 0);
    el.textContent = n > 99 ? '99+' : n;
  });
}
onCartChange(paintBadge);

document.addEventListener('click', (e) => {
  const back = e.target.closest('#hd-back');
  if (back) { e.preventDefault(); history.back(); return; }
  const logout = e.target.closest('#hd-logout');
  if (logout) {
    e.preventDefault();
    auth.logout();
    location.hash = '/';
    return;
  }
});

document.addEventListener('submit', (e) => {
  const form = e.target.closest('#hd-search-form');
  if (!form) return;
  e.preventDefault();
  const kw = form.querySelector('#hd-search').value.trim();
  location.hash = kw ? `/search?kw=${encodeURIComponent(kw)}` : '/search';
});
