// 首页（PC）：左侧全部类目菜单（悬浮二级/三级浮层）+ 大 Banner + 快捷入口 + 推荐商品楼层
import { shell } from '../lib/shell.js';
import { apiProduct } from '../api.js';
import { goodsGrid, hydratePrices } from '../lib/goods-grid.js';
import { createPager } from '../lib/pager.js';
import { escapeHtml } from '../lib/ui.js';

const CAT_ICONS = ['🔥', '👗', '📱', '🍎', '🏠', '🧴', '👶', '🏃', '📚', '🚗', '🎁', '🍰'];

export async function renderHome({ el }) {
  el.innerHTML = shell({
    activeTab: '/',
    content: `
      <div class="home-hero">
        <aside class="home-cats" id="home-cats">
          <div class="empty" style="padding:30px 10px;font-size:12px">类目加载中…</div>
        </aside>
        <div class="home-banner">
          <h2>品质好物 限时特惠</h2>
          <p>新人注册享优惠 · 全场商品极速发货 · 积分天天抵现</p>
          <div class="hb-tags"><span>正品保障</span><span>7 天无理由</span><span>满 99 包邮</span><span>售后无忧</span></div>
        </div>
      </div>
      <div class="card quick-nav">
        <a class="qn" href="#/me/coupons"><span class="ico">🎟️</span>领券中心</a>
        <a class="qn" href="#/orders?tab=pay"><span class="ico">💳</span>待付款</a>
        <a class="qn" href="#/orders?tab=ship"><span class="ico">📦</span>待发货</a>
        <a class="qn" href="#/aftersale"><span class="ico">🛠️</span>售后服务</a>
        <a class="qn" href="#/me"><span class="ico">⭐</span>会员中心</a>
      </div>
      <div class="home-floor">
        <div class="floor-head"><b>🔥 为你推荐</b><a class="more" href="#/category">查看全部分类 ›</a></div>
        <div class="cat-bar" id="cat-bar"><span class="cat active" data-id="">推荐</span></div>
        <div id="goods-host"></div>
      </div>`,
  });

  // 一级类目：左侧菜单（带浮层）+ 楼层顶栏
  let tree = [];
  try { tree = await apiProduct.categoryTree(); } catch (_) { tree = []; }

  const catsBox = el.querySelector('#home-cats');
  if (tree.length) {
    catsBox.innerHTML = tree.slice(0, 12).map((c, i) => `
      <div class="hc1" data-id="${c.id}">
        <span class="hc-name"><span>${CAT_ICONS[i] || '🏷️'}</span>${escapeHtml(c.name)}</span>
        <span>›</span>
        <div class="hc-panel">
          ${(c.children || []).map((c2) => `
            <div class="hcp-group">
              <div class="hcp-title">${escapeHtml(c2.name)}</div>
              <div class="hcp-items">
                ${(c2.children || []).map((c3) =>
                  `<a href="#/category?c1=${c.id}&c3=${c3.id}">${escapeHtml(c3.name)}</a>`).join('')}
              </div>
            </div>`).join('') || '<div class="muted tiny" style="padding:10px">该类目暂无子分类</div>'}
        </div>
      </div>`).join('');
  } else {
    catsBox.innerHTML = '<div class="empty" style="padding:30px 10px;font-size:12px">类目加载失败</div>';
  }

  const bar = el.querySelector('#cat-bar');
  tree.slice(0, 12).forEach((c) => {
    bar.insertAdjacentHTML('beforeend', `<span class="cat" data-id="${c.id}">${escapeHtml(c.name)}</span>`);
  });
  bar.addEventListener('click', (e) => {
    const t = e.target.closest('.cat');
    if (!t || !t.dataset.id) return; // “推荐”停留首页
    location.hash = `/category?c1=${t.dataset.id}`;
  });

  // 推荐商品流
  const host = el.querySelector('#goods-host');
  const pager = createPager({
    container: host,
    fetchPage: (p, size) => apiProduct.browse({ page: p, size }),
    renderList: (items) => {
      queueMicrotask(() => hydratePrices(host));
      return goodsGrid(items);
    },
  });
  pager.reset({});
}
