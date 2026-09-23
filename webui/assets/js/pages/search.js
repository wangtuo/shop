// 搜索页：历史词（localStorage）+ 关键词无限流
import { shell } from '../lib/shell.js';
import { apiProduct } from '../api.js';
import { goodsGrid, hydratePrices } from '../lib/goods-grid.js';
import { createPager } from '../lib/pager.js';

const HISTORY_KEY = 'shop_search_hist';
function hist() { try { return JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]'); } catch (_) { return []; } }
function saveHist(w) {
  const h = [w, ...hist().filter((x) => x !== w)].slice(0, 10);
  localStorage.setItem(HISTORY_KEY, JSON.stringify(h));
}

export async function renderSearch({ el, query }) {
  el.innerHTML = shell({
    title: '搜索',
    showBack: true,
    headerRight: `<a class="btn btn-sm" href="#/">首页</a>`,
    content: `
    <div class="card section-pad" style="display:flex;gap:10px;align-items:center;margin-bottom:12px">
      <span>🔍</span>
      <input id="kw" class="input" style="max-width:520px" placeholder="搜索商品名称" />
      <button class="btn btn-primary" id="do-search">搜索</button>
    </div>
    <div id="entry">
      <div class="card section-pad" id="hist-card">
        <div class="row-between"><b>搜索历史</b><span class="muted tiny" id="clear-hist" style="cursor:pointer">清空</span></div>
        <div style="display:flex;flex-wrap:wrap;gap:8px;margin-top:12px" id="hist-tags"></div>
      </div>
    </div>
    <div id="result-host"></div>`,
  });

  const kwInput = el.querySelector('#kw');
  const entry = el.querySelector('#entry');
  const histTags = el.querySelector('#hist-tags');

  function renderHist() {
    const h = hist();
    histTags.innerHTML = h.length
      ? h.map((w) => `<span class="tag tag-gray" style="padding:5px 12px;font-size:12px;cursor:pointer" data-w="${w}">${w}</span>`).join('')
      : '<span class="muted tiny">暂无搜索历史</span>';
  }
  renderHist();

  histTags.onclick = (e) => {
    const t = e.target.closest('[data-w]');
    if (!t) return;
    kwInput.value = t.dataset.w;
    doSearch(t.dataset.w);
  };
  el.querySelector('#clear-hist').onclick = () => {
    localStorage.removeItem(HISTORY_KEY); renderHist();
  };

  const pager = createPager({
    container: el.querySelector('#result-host'),
    fetchPage: (p, size, extra) => apiProduct.browse({ page: p, size, keyword: extra?.kw }),
    renderList: (items) => {
      queueMicrotask(() => hydratePrices(el.querySelector('#result-host')));
      return goodsGrid(items);
    },
    emptyHtml: '<div class="card"><div class="empty"><span class="ico">🔍</span>没有找到相关商品<br><span class="muted tiny">换个关键词试试吧</span></div></div>',
  });

  function doSearch(kw) {
    kw = (kw || kwInput.value).trim();
    if (!kw) return;
    kwInput.value = kw;
    saveHist(kw);
    entry.style.display = 'none';
    pager.reset({ kw });
  }
  el.querySelector('#do-search').onclick = () => doSearch();
  kwInput.onkeydown = (e) => { if (e.key === 'Enter') doSearch(); };

  if (query.kw) { kwInput.value = query.kw; doSearch(query.kw); }
}
