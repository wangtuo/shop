// 商品详情：图/价/销量、SKU 选择、评价、加购、立即购买
import { shell } from '../lib/shell.js';
import { apiProduct } from '../api.js';
import { auth } from '../lib/http.js';
import { toastError, toastSuccess, fen2yuan, escapeHtml } from '../lib/ui.js';
import { apiOrder } from '../api.js';
import { refreshCartCount } from '../lib/cart-store.js';
import { go } from '../lib/router.js';

const FALLBACK = ['🎁', '🛍️', '👜', '👟', '🎧', '⌚', '🧴', '🍰'];

export async function renderProduct({ el, params }) {
  const spuId = params.id;
  let data;
  try { data = await apiProduct.detail(spuId); }
  catch (e) { el.innerHTML = shell({ title: '商品详情', showBack: true, content: `<div class="empty"><span class="ico">😵</span>${escapeHtml(e.message)}</div>` }); return; }

  const spu = data.spu;
  const skus = data.skus || [];
  const ico = FALLBACK[Number(spu.spuId) % FALLBACK.length];
  const minPrice = Math.min(...skus.map((s) => s.seckillPriceFen ?? s.promotionPriceFen ?? s.salePriceFen).filter((v) => v != null));
  const stock = skus.reduce((a, s) => a + (s.availableStock || 0), 0);

  el.innerHTML = shell({
    title: '商品详情',
    showBack: true,
    activeTab: '',
    content: `
    <div class="pd-wrap">
      <div class="pd-top">
        <div class="pd-gallery">${spu.mainImage && !/example\.com/.test(spu.mainImage)
          ? `<img src="${escapeHtml(spu.mainImage)}" alt="">` : ico}</div>
        <div class="pd-buy">
          <h1 class="pd-title">${escapeHtml(spu.name)}</h1>
          <div class="pd-sub">销量 ${spu.sales || 0} · 好评率 ${((spu.goodRate || 0) * 100).toFixed(0)}% · 评论 ${spu.totalCommentCount || 0} · 库存 ${stock} 件${stock <= 0 ? ' · <b style="color:var(--danger)">已售罄</b>' : ''}</div>
          <div class="pd-price-box">
            <span class="lbl">促销价</span>
            <span class="price big" id="head-price">${fen2yuan(minPrice)}</span>
          </div>
          <div class="pd-services">
            <span>✅ 7天无理由退货</span><span>✅ 正品保障</span><span>✅ 极速发货</span><span>✅ 售后无忧</span>
          </div>
          <div class="pd-sku-entry" id="sku-entry">
            <span class="muted">已选</span>
            <span id="sku-sel-text" class="flex1" style="margin:0 12px;color:var(--text-2)">${skus.length > 1 ? '请选择规格数量' : escapeHtml(skus[0]?.specText || '默认规格') + ' × 1'}</span>
            <span class="muted">点击选择 ›</span>
          </div>
          <div class="pd-actions">
            <a class="ab-icon" href="#/cart"><span class="ico">🛒</span>购物车</a>
            <a class="ab-icon" href="#/me/coupons"><span class="ico">🎟️</span>领券</a>
            <span class="flex1"></span>
            <button class="btn btn-plain" id="add-cart">加入购物车</button>
            <button class="btn btn-primary" id="buy-now">立即购买</button>
          </div>
        </div>
      </div>
      <div class="pd-bottom">
        <div class="pd-tabs" id="pd-tabs">
          <span data-t="goods" class="active">商品介绍</span>
          <span data-t="comments">评价 ${spu.totalCommentCount ? `(${spu.totalCommentCount})` : ''}</span>
          <span data-t="detail">规格参数</span>
        </div>
        <div id="pd-body" class="pd-body"></div>
      </div>
    </div>`,
  });

  // ---- Tab 内容 ----
  const body = el.querySelector('#pd-body');
  const tabs = el.querySelector('#pd-tabs');
  function parseSpecs(skus) {
    const groups = new Map(); // name -> Set(value)
    skus.forEach((s) => {
      (s.specText || '').split(';').filter(Boolean).forEach((kv) => {
        const [k, v] = kv.split(':');
        if (k && v) { if (!groups.has(k)) groups.set(k, []); const arr = groups.get(k); if (!arr.includes(v)) arr.push(v); }
      });
    });
    return [...groups.entries()].map(([name, vals]) => ({ name, vals }));
  }
  const specGroups = parseSpecs(skus);

  function goodsTab() {
    let detail = '';
    try { detail = JSON.parse(data.detailJson || '{}').desc || data.detailJson || ''; } catch (_) { detail = data.detailJson || ''; }
    body.innerHTML = `
      <div class="card section-pad">
        <b>商品介绍</b>
        <div class="mt-8" style="color:var(--text-2)">${escapeHtml(detail).replace(/\n/g, '<br>') || '暂无介绍'}</div>
      </div>
      <div class="card section-pad mt-12">
        <b>服务保障</b>
        <div class="mt-8 muted tiny">✅ 7天无理由退货 · ✅ 正品保障 · ✅ 极速发货 · ✅ 售后无忧</div>
      </div>`;
  }

  async function commentsTab() {
    body.innerHTML = '<div class="loading-more">评价加载中…</div>';
    try {
      const r = await apiProduct.comments(spuId, 1, 20);
      const list = r.list || [];
      body.innerHTML = list.length ? list.map((c) => `
        <div class="comment-card">
          <div class="row-between tiny muted">
            <span>用户 ${escapeHtml(String(c.userId).slice(-4))}</span><span>${new Date(c.createTime).toLocaleDateString()}</span>
          </div>
          <div class="comment-stars mt-6">${'★'.repeat(c.overallStar || 5)}${'☆'.repeat(5 - (c.overallStar || 5))}</div>
          <div class="mt-6">${escapeHtml(c.content || '')}</div>
          ${c.appendContent ? `<div class="tiny muted mt-8">追评：${escapeHtml(c.appendContent)}</div>` : ''}
          ${c.replyContent ? `<div class="tiny tag-blue tag mt-8" style="padding:6px 8px">商家回复：${escapeHtml(c.replyContent)}</div>` : ''}
        </div>`).join('')
        : '<div class="empty"><span class="ico">💬</span>暂无评价</div>';
    } catch (e) { body.innerHTML = `<div class="empty">${escapeHtml(e.message)}</div>`; }
  }

  function detailTab() {
    body.innerHTML = `
      <div class="card section-pad">
        <b>规格参数</b>
        ${specGroups.map((g) => `
          <div class="row-between mt-8 tiny"><span class="muted">${escapeHtml(g.name)}</span><span>${g.vals.map(escapeHtml).join(' / ')}</span></div>`).join('')}
        <div class="row-between mt-8 tiny"><span class="muted">商品编号</span><span>${spu.spuId}</span></div>
        <div class="row-between mt-8 tiny"><span class="muted">商品状态</span><span>${stock > 0 ? '在售' : '售罄'}</span></div>
      </div>`;
  }

  goodsTab();
  tabs.onclick = (e) => {
    const t = e.target.closest('span[data-t]');
    if (!t) return;
    tabs.querySelectorAll('span').forEach((x) => x.classList.toggle('active', x === t));
    ({ goods: goodsTab, comments: commentsTab, detail: detailTab })[t.dataset.t]();
  };

  // ---- SKU 选择弹层 ----
  let chosenSku = skus.length === 1 ? skus[0] : null;
  const chosen = {}; // groupName -> value
  if (chosenSku) {
    (chosenSku.specText || '').split(';').forEach((kv) => { const [k, v] = kv.split(':'); chosen[k] = v; });
  }
  let qty = 1;
  let sheetMode = 'cart'; // cart | buy

  function matchSku() {
    return skus.find((s) => {
      const specs = Object.fromEntries((s.specText || '').split(';').filter(Boolean).map((kv) => kv.split(':')));
      return Object.entries(chosen).every(([k, v]) => specs[k] === v);
    }) || null;
  }

  function openSku(buy) {
    sheetMode = buy ? 'buy' : 'cart';
    if (chosenSku) qty = 1;
    const mask = document.createElement('div');
    mask.className = 'mask';
    const sheet = document.createElement('div');
    sheet.className = 'sku-sheet';
    document.body.append(mask, sheet);
    mask.onclick = () => { mask.remove(); sheet.remove(); };

    function drawSheet(snapshotSku) {
      const view = snapshotSku || chosenSku;
      const price = view ? (view.seckillPriceFen ?? view.promotionPriceFen ?? view.salePriceFen) : minPrice;
      const avStock = view ? (view.availableStock || 0) : null;
      sheet.innerHTML = `
      <div class="sheet-head">
        <div class="sheet-thumb">${spu.mainImage && !/example\.com/.test(spu.mainImage)
          ? `<img src="${escapeHtml(spu.mainImage)}" style="width:100%;height:100%;object-fit:cover;border-radius:8px">` : ico}</div>
        <div class="flex1">
          <div class="price big">${fen2yuan(price)}</div>
          <div class="muted tiny mt-6">${view ? `库存 ${avStock} 件` : `库存 ${stock} 件（选规格后确认）`}</div>
          <div class="tiny mt-6" id="chosen-line">${Object.keys(chosen).length ? Object.entries(chosen).map(([k, v]) => `${escapeHtml(k)}:${escapeHtml(v)}`).join(' ') : '请选择规格'}</div>
        </div>
        <button class="sheet-close" id="sheet-x">✕</button>
      </div>
      ${specGroups.map((g) => `
        <div class="sku-group">
          <div class="gn">${escapeHtml(g.name)}</div>
          <div class="sku-options">
            ${g.vals.map((v) => `<span class="sku-opt ${chosen[g.name] === v ? 'selected' : ''}" data-k="${escapeHtml(g.name)}" data-v="${escapeHtml(v)}">${escapeHtml(v)}</span>`).join('')}
          </div>
        </div>`).join('')}
      <div class="sku-group row-between">
        <div class="gn" style="margin:0">数量</div>
        <div class="qty-stepper">
          <button type="button" id="q-minus">−</button>
          <input id="q-num" value="${qty}" inputmode="numeric">
          <button type="button" id="q-plus">＋</button>
        </div>
      </div>
      <button class="btn btn-primary btn-block btn-lg mt-16" id="sku-ok">${sheetMode === 'cart' ? '加入购物车' : '立即购买'}</button>`;

      sheet.querySelector('#sheet-x').onclick = () => { mask.remove(); sheet.remove(); };
      sheet.querySelectorAll('.sku-opt').forEach((o) => {
        o.onclick = () => {
          chosen[o.dataset.k] = o.dataset.v;
          chosenSku = matchSku();
          drawSheet();
        };
      });
      const num = sheet.querySelector('#q-num');
      sheet.querySelector('#q-minus').onclick = () => { qty = Math.max(1, qty - 1); num.value = qty; };
      sheet.querySelector('#q-plus').onclick = () => {
        const max = chosenSku?.availableStock ?? 99;
        qty = Math.min(max, qty + 1); num.value = qty;
      };
      num.oninput = () => {
        let n = parseInt(num.value) || 1;
        n = Math.max(1, Math.min(chosenSku?.availableStock ?? 99, n));
        qty = n; num.value = n;
      };
      sheet.querySelector('#sku-ok').onclick = async () => {
        if (!chosenSku) { toastError('请选择完整规格'); return; }
        if (chosenSku.availableStock <= 0) { toastError('该规格已售罄'); return; }
        if (qty > chosenSku.availableStock) { toastError(`库存仅剩 ${chosenSku.availableStock} 件`); return; }
        if (!auth.isLogin) { go('/login?redirect=' + encodeURIComponent(location.hash.slice(1))); return; }
        const btn = sheet.querySelector('#sku-ok');
        btn.disabled = true;
        try {
          if (sheetMode === 'cart') {
            await apiOrder.cartAdd(chosenSku.skuId, qty);
            toastSuccess('已加入购物车');
            await refreshCartCount();
            mask.remove(); sheet.remove();
          } else {
            sessionStorage.setItem('shop_checkout_ctx', JSON.stringify({
              items: [{ skuId: chosenSku.skuId, qty }],
              orderType: 1,
            }));
            mask.remove(); sheet.remove();
            go('/checkout');
          }
          const line = document.querySelector('#sku-sel-text');
          if (line && chosenSku) line.textContent = `${chosenSku.specText || '默认规格'} × ${qty}`;
        } catch (e) { toastError(e.message); btn.disabled = false; }
      };
    }
    drawSheet();
  }

  el.querySelector('#sku-entry').onclick = () => openSku(false);
  el.querySelector('#add-cart').onclick = () => {
    if (!auth.isLogin) { go('/login?redirect=' + encodeURIComponent(location.hash.slice(1))); return; }
    openSku(false);
  };
  el.querySelector('#buy-now').onclick = () => {
    if (!auth.isLogin) { go('/login?redirect=' + encodeURIComponent(location.hash.slice(1))); return; }
    openSku(true);
  };
}
