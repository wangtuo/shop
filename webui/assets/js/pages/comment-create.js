// 发表评价：每个订单 SKU 一条评价
import { shell } from '../lib/shell.js';
import { apiOrder, apiProduct } from '../api.js';
import { toastError, toastSuccess, escapeHtml } from '../lib/ui.js';
import { go } from '../lib/router.js';

const FALLBACK = ['🎁', '🛍️', '👜', '👟', '🎧'];

export async function renderCommentCreate({ el, params }) {
  const orderNo = params.orderNo;
  const o = await apiOrder.detail(orderNo).catch(() => null);
  if (!o) { el.innerHTML = shell({ title: '发表评价', showBack: true, content: '<div class="empty">订单不存在</div>' }); return; }

  let idx = 0;
  const items = o.items || [];

  function stars(name) {
    return `<div class="comment-stars" data-name="${name}">
      ${[1, 2, 3, 4, 5].map((s) => `<span data-s="${s}" style="cursor:pointer;font-size:26px;margin-right:4px">${s <= 5 ? '☆' : ''}</span>`).join('')}
    </div>`;
  }

  function draw() {
    if (idx >= items.length) {
      el.innerHTML = shell({
        title: '发表评价', showBack: true,
        content: `<div class="pay-result"><div class="ico">🙏</div><h2>评价已全部提交</h2>
          <div style="margin-top:24px;display:flex;gap:10px;justify-content:center">
            <a class="btn" href="#/">继续购物</a><a class="btn btn-primary" href="#/orders">查看订单</a></div></div>`,
      });
      return;
    }
    const it = items[idx];
    el.innerHTML = shell({
      title: `评价商品 (${idx + 1}/${items.length})`,
      showBack: true,
      content: `
      <div class="confirm-card">
        <div class="row gap-12">
          <div class="order-thumb">${it.image ? `<img src="${escapeHtml(it.image)}">` : FALLBACK[Number(it.skuId) % FALLBACK.length]}</div>
          <div class="flex1 ellipsis-2" style="font-size:13px">${escapeHtml(it.skuName)}</div>
        </div>
      </div>
      <form id="cmt-form" class="confirm-card" style="margin-top:10px">
        <div class="form-item"><label>商品质量</label><div id="r-quality"></div></div>
        <div class="form-item"><label>物流服务</label><div id="r-logistics"></div></div>
        <div class="form-item"><label>服务态度</label><div id="r-service"></div></div>
        <div class="form-item">
          <label>评价内容（10-500字）</label>
          <textarea name="content" class="textarea" maxlength="500" placeholder="分享您的购物体验吧~" required></textarea>
        </div>
        <div class="field-error" id="cmt-err"></div>
        <button class="btn btn-primary btn-block btn-lg" type="submit">${idx === items.length - 1 ? '提交评价' : '提交并评价下一件'}</button>
      </form>`,
    });

    const rating = { qualityStar: 5, logisticsStar: 5, serviceStar: 5 };
    [['r-quality', 'qualityStar'], ['r-logistics', 'logisticsStar'], ['r-service', 'serviceStar']].forEach(([boxId, key]) => {
      const box = el.querySelector('#' + boxId);
      box.innerHTML = [1,2,3,4,5].map((s) => `<span data-s="${s}" style="cursor:pointer;font-size:26px;color:var(--accent);margin-right:6px">${s <= rating[key] ? '★' : '☆'}</span>`).join('');
      const paint = () => { box.querySelectorAll('span').forEach((sp) => { sp.textContent = Number(sp.dataset.s) <= rating[key] ? '★' : '☆'; }); };
      box.onclick = (e) => { const sp = e.target.closest('[data-s]'); if (!sp) return; rating[key] = Number(sp.dataset.s); paint(); };
    });

    el.querySelector('#cmt-form').onsubmit = async (e) => {
      e.preventDefault();
      const fd = new FormData(e.target);
      const content = (fd.get('content') || '').trim();
      if (content.length < 10) { el.querySelector('#cmt-err').textContent = '评价内容至少 10 个字'; return; }
      try {
        await apiProduct.commentCreate({
          orderNo, spuId: it.spuId, skuId: it.skuId,
          qualityStar: rating.qualityStar, logisticsStar: rating.logisticsStar, serviceStar: rating.serviceStar,
          content, images: [],
        });
        toastSuccess('评价成功');
        idx += 1; draw();
      } catch (err) { el.querySelector('#cmt-err').textContent = escapeHtml(err.message); }
    };
  }
  draw();
}
