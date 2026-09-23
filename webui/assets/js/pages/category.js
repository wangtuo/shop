// 分类页（PC）：左侧一级类目（190px），右侧二级分组 + 三级筛选 + 商品流
import { shell } from '../lib/shell.js';
import { apiProduct } from '../api.js';
import { goodsGrid, hydratePrices } from '../lib/goods-grid.js';
import { createPager } from '../lib/pager.js';
import { escapeHtml } from '../lib/ui.js';

export async function renderCategory({ el, query }) {
  el.innerHTML = shell({
    activeTab: '/category',
    content: `
    <div class="cat-layout">
      <aside class="cat-rail" id="c1-col"></aside>
      <section class="cat-main">
        <div class="cat-side" id="c-side"></div>
        <div class="cat-goods" id="c-goods"></div>
      </section>
    </div>`,
  });

  let tree = [];
  try { tree = await apiProduct.categoryTree(); }
  catch (_) { tree = []; }

  const c1Col = el.querySelector('#c1-col');
  const side = el.querySelector('#c-side');
  const goodsHost = el.querySelector('#c-goods');

  const pager = createPager({
    container: goodsHost,
    fetchPage: (p, size, extra) => apiProduct.browse({ page: p, size, category3Id: extra?.category3Id }),
    renderList: (items) => {
      queueMicrotask(() => hydratePrices(goodsHost));
      return goodsGrid(items);
    },
    emptyHtml: '<div class="empty" style="padding:60px 0"><span class="ico">📭</span>该类目下暂无商品</div>',
  });

  function selectLevel1(c1) {
    c1Col.querySelectorAll('.c1').forEach((x) => x.classList.toggle('on', x.dataset.id === String(c1.id)));
    const groups = (c1.children || []);
    side.innerHTML = groups.map((c2) => `
      <div class="row c2-group">
        <div class="c2-name">${escapeHtml(c2.name)}</div>
        <div class="c2-body">
          ${(c2.children || []).map((c3) =>
            `<span class="sku-opt c3" data-id="${c3.id}">${escapeHtml(c3.name)}</span>`).join('')}
        </div>
      </div>`).join('');
    if (!groups.length) side.innerHTML = `<div class="empty"><span class="ico">📭</span>该类目暂无子分类</div>`;
  }

  function selectLevel3(c3) {
    side.querySelectorAll('.c3').forEach((x) => x.classList.toggle('selected', x.dataset.id === String(c3.id)));
    pager.reset({ category3Id: c3.id });
  }

  side.addEventListener('click', (e) => {
    const chip = e.target.closest('.c3');
    if (!chip) return;
    const findC3 = (id) => {
      for (const c1 of tree) for (const c2 of c1.children || []) for (const c3 of c2.children || [])
        if (String(c3.id) === String(id)) return { c1, c3 };
      return null;
    };
    const hit = findC3(chip.dataset.id);
    if (hit) { selectLevel1(hit.c1); selectLevel3(hit.c3); }
  });

  c1Col.innerHTML = tree.map((c) =>
    `<div class="c1" data-id="${c.id}">${escapeHtml(c.name)}</div>`).join('');
  c1Col.addEventListener('click', (e) => {
    const t = e.target.closest('.c1');
    if (!t) return;
    const c1 = tree.find((c) => String(c.id) === String(t.dataset.id));
    if (!c1) return;
    selectLevel1(c1);
    const first3 = c1.children?.[0]?.children?.[0];
    if (first3) selectLevel3(first3); else pager.reset({ category3Id: 0 });
  });

  if (tree.length) {
    // 支持 ?c1=xx&c3=yy 直达
    let wantC1 = query.c1 ? tree.find((c) => String(c.id) === String(query.c1)) : tree[0];
    let wantC3 = null;
    if (query.c3) {
      outer:
      for (const c1 of tree) for (const c2 of c1.children || []) for (const c3 of c2.children || []) {
        if (String(c3.id) === String(query.c3)) { wantC1 = c1; wantC3 = c3; break outer; }
      }
    }
    selectLevel1(wantC1 || tree[0]);
    if (wantC3) selectLevel3(wantC3);
    else {
      const first3 = wantC1?.children?.[0]?.children?.[0];
      if (first3) selectLevel3(first3); else pager.reset({ category3Id: 0 });
    }
  } else {
    side.innerHTML = `<div class="empty"><span class="ico">📡</span>类目加载失败</div>`;
  }
}
