// 购物车：店铺分组、选择/数量/删除、失效商品清理、去结算
import { shell } from '../lib/shell.js';
import { apiOrder } from '../api.js';
import { auth } from '../lib/http.js';
import { toastError, toastSuccess, fen2yuan, confirm as uiConfirm, escapeHtml } from '../lib/ui.js';
import { setCartCount, refreshCartCount } from '../lib/cart-store.js';
import { go } from '../lib/router.js';

const FALLBACK = ['🎁', '🛍️', '👜', '👟', '🎧', '⌚'];

export async function renderCart({ el }) {
  el.innerHTML = shell({ title: '购物车', activeTab: '/cart', content: '<div class="loading-more mt-20">加载中…</div>' });
  await draw();

  async function draw() {
    let view;
    try { view = await apiOrder.cart(); }
    catch (e) { el.querySelector('.site-content').innerHTML = `<div class="empty"><span class="ico">📡</span>${escapeHtml(e.message)}</div>`; return; }

    const groups = view?.shopGroups || [];
    setCartCount(view?.totalCount || 0);
    const allSel = !!view?.allSelected;
    const selectedFen = view?.selectedAmountFen || 0;
    const validGroups = groups.filter((g) => (g.items || []).some((it) => !it.invalid));
    const invalidItems = groups.flatMap((g) => g.items || []).filter((it) => it.invalid);

    const container = el.querySelector('.site-content');
    container.innerHTML = `
      ${view?.invalidCount ? `<div class="row-between cart-warn">
        <span>⚠️ ${view.invalidCount} 件失效商品（下架/售罄/删除）</span>
        <button class="btn btn-sm" id="clear-invalid">清空失效</button></div>` : ''}
      ${validGroups.length ? `
      <div class="cart-table-head">
        <div><span class="cart-check ${allSel ? 'on' : ''}" id="head-check" style="margin:0 auto">${allSel ? '✓' : ''}</span></div>
        <div>商品信息</div><div>单价</div><div>数量</div><div>小计</div><div>操作</div>
      </div>` : ''}
      <div id="groups">${validGroups.map((g, gi) => `
        <div class="cart-shop">
          <div class="shop-head">
            <label class="row gap-8"><span class="cart-check ${g.allSelected ? 'on' : ''}" data-shop-sel="${gi}">${g.allSelected ? '✓' : ''}</span><b>🏬 店铺 #${g.shopId}</b></label>
            <span class="muted tiny" style="margin-left:14px">已选 ${g.selectedCount || 0} 种${(g.selectedQty || 0) !== (g.selectedCount || 0) ? ` ${g.selectedQty || 0} 件` : ''}</span>
          </div>
          ${g.items.filter((it) => !it.invalid).map((it) => itemHtml(it)).join('')}
        </div>`).join('')}</div>
      ${!validGroups.length && !invalidItems.length ? `<div class="empty"><span class="ico">🛒</span>购物车还是空的<br><a class="btn btn-primary mt-16" href="#/" style="display:inline-flex">去逛逛</a></div>` : ''}
      ${(view?.totalCount || 0) > 0 ? `
      <div class="cart-foot">
        <span class="row gap-8" id="select-all" style="cursor:pointer">
          <span class="cart-check ${allSel ? 'on' : ''}">${allSel ? '✓' : ''}</span><span class="tiny">全选</span>
        </span>
        <button class="btn btn-sm" id="foot-clear-invalid" ${view?.invalidCount ? '' : 'hidden'}>清空失效商品</button>
        <div class="flex1" style="text-align:right">
          已选 <b id="sel-qty">${view?.selectedQty || 0}</b> 件　合计（不含运费）：
          <span class="price big" style="font-size:22px">${fen2yuan(selectedFen)}</span>
        </div>
        <button class="btn btn-primary" id="go-checkout" ${view?.selectedQty ? '' : 'disabled'}>
          去结算${view?.selectedQty ? `(${view.selectedQty})` : ''}
        </button>
      </div>` : ''}`;

    bind();
  }

  function itemHtml(it) {
    const ico = FALLBACK[Number(it.skuId) % FALLBACK.length];
    const sub = (it.currentPriceFen ?? it.addPriceFen ?? 0) * (it.qty || 1);
    return `
    <div class="cart-item" data-cart="${it.cartId}">
      <span class="cart-check ${it.selected ? 'on' : ''}" data-sel="${it.cartId}">${it.selected ? '✓' : ''}</span>
      <a class="row gap-12 ci-info" href="#/product/${it.spuId}">
        <span class="cart-thumb">${it.image ? `<img src="${escapeHtml(it.image)}">` : ico}</span>
        <span class="flex1" style="min-width:0">
          <span class="ellipsis-2" style="font-size:13px;display:block">${escapeHtml(it.skuName)}</span>
          <span class="muted tiny" style="display:block;margin-top:6px">${escapeHtml(it.specText || '')}</span>
          ${it.priceChanged ? '<span class="tag tag-gold tiny" style="margin-top:6px">价格已变动</span>' : ''}
        </span>
      </a>
      <div class="ci-price"><span class="price">${fen2yuan(it.currentPriceFen ?? it.addPriceFen)}</span></div>
      <div style="text-align:center">
        <span class="qty-stepper">
          <button type="button" data-q="minus" data-id="${it.cartId}">−</button>
          <input value="${it.qty}" readonly>
          <button type="button" data-q="plus" data-id="${it.cartId}">＋</button>
        </span>
      </div>
      <div class="ci-sub"><span class="sub-price price">${fen2yuan(sub)}</span></div>
      <span class="cart-del" data-del="${it.cartId}" style="color:var(--text-3);cursor:pointer" title="删除">🗑 删除</span>
    </div>`;
  }

  function bind() {
    const root = el.querySelector('#groups');
    if (!root) return;
    root.onclick = async (e) => {
      const sel = e.target.closest('[data-sel]');
      const del = e.target.closest('[data-del]');
      const q = e.target.closest('[data-q]');
      try {
        if (sel) {
          const id = sel.dataset.sel;
          await apiOrder.cartSelect([id], sel.classList.contains('on') ? 0 : 1);
          await draw();
        } else if (del) {
          if (!(await uiConfirm({ content: '删除该商品？', okText: '删除', danger: true }))) return;
          await apiOrder.cartRemove(del.dataset.del);
          toastSuccess('已删除');
          await draw(); await refreshCartCount();
        } else if (q) {
          const id = q.dataset.id;
          const input = q.parentElement.querySelector('input');
          let n = Number(input.value) + (q.dataset.q === 'plus' ? 1 : -1);
          if (n < 1) {
            if (!(await uiConfirm({ content: '数量为 1，确认删除？', okText: '删除', danger: true }))) return;
            await apiOrder.cartRemove(id); await draw(); await refreshCartCount(); return;
          }
          input.value = n;
          await apiOrder.cartUpdate(id, n);
          await draw();
        }
      } catch (err) { toastError(err.message); }
    };

    // 店铺全选
    root.querySelectorAll('[data-shop-sel]').forEach((b) => {
      b.onclick = async () => {
        const gi = Number(b.dataset.shopSel);
        const view = await apiOrder.cart();
        const g = view.shopGroups[gi];
        const turnOn = !g.allSelected;
        await apiOrder.cartSelect(g.items.filter((x) => !x.invalid).map((x) => x.cartId), turnOn ? 1 : 0, g.shopId);
        await draw();
      };
    });

    el.querySelector('#select-all')?.addEventListener('click', async () => {
      const view = await apiOrder.cart();
      await apiOrder.cartSelectAll(view.allSelected ? 0 : 1);
      await draw();
    });
    el.querySelector('#head-check')?.addEventListener('click', () => el.querySelector('#select-all')?.click());

    const clearInvalid = el.querySelector('#clear-invalid') || el.querySelector('#foot-clear-invalid');
    clearInvalid?.addEventListener('click', async () => {
      const n = await apiOrder.cartClearInvalid();
      toastSuccess(`已清空 ${n || 0} 件失效商品`);
      await draw(); await refreshCartCount();
    });

    el.querySelector('#go-checkout')?.addEventListener('click', async () => {
      const view = await apiOrder.cart();
      const picked = view.shopGroups
        .flatMap((g) => g.items)
        .filter((it) => it.selected && !it.invalid);
      if (!picked.length) return;
      sessionStorage.setItem('shop_checkout_ctx', JSON.stringify({
        items: picked.map((it) => ({ skuId: it.skuId, qty: it.qty })),
        fromCartIds: picked.map((it) => it.cartId),
        orderType: 1,
      }));
      go('/checkout');
    });
  }
}
