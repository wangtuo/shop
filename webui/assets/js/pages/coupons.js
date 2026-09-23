// 领券中心 + 我的优惠券
import { shell } from '../lib/shell.js';
import { apiMarketing } from '../api.js';
import { fen2yuan, escapeHtml, fmtDate, toastSuccess, toastError } from '../lib/ui.js';

const TABS = [['center', '领券中心'], ['0', '未使用'], ['1', '已使用'], ['2', '已过期']];
let tab = 'center';

export async function renderCoupons({ el }) {
  el.innerHTML = shell({
    title: '优惠券',
    showBack: true,
    activeTab: '/me',
    content: `
    <div class="cat-bar" id="cp-tabs" style="position:static;border-radius:6px;margin-bottom:12px">
      ${TABS.map(([k, l]) => `<span class="cat ${tab === k ? 'active' : ''}" data-t="${k}">${l}</span>`).join('')}
    </div>
    <div id="cp-host"><div class="loading-more">加载中…</div></div>`,
  });

  function face(t) {
    if (t.type === 2) {
      const d = (t.discountBp / 100).toFixed(1).replace(/\.0$/, '');
      return { big: `${d}`, unit: '折', cond: t.thresholdFen ? `满${fen2yuan(t.thresholdFen)}元可用` : '无门槛' };
    }
    if (t.type === 4) return { big: '免', unit: '运费', cond: '免运费券' };
    return { big: fen2yuan(t.faceValueFen), unit: '元', cond: t.thresholdFen ? `满${fen2yuan(t.thresholdFen)}元可用` : '无门槛' };
  }
  const SCOPE = { 1: '全场通用', 2: '指定商品', 3: '指定SPU', 4: '指定类目', 5: '指定店铺' };

  function tplCard(t, { claimed, claimable, ucId } = {}) {
    const f = face(t);
    return `
    <div class="coupon ${claimed ? 'disabled' : ''}">
      <div class="cp-left">
        <div style="line-height:1.1"><span style="font-size:24px;font-weight:800">${f.big}</span><span style="font-size:12px">${f.unit}</span></div>
        <div class="tiny" style="opacity:.9;margin-top:4px">${f.cond}</div>
      </div>
      <div class="cp-right">
        <div class="row-between"><b>${escapeHtml(t.name)}</b>
          ${claimable !== undefined ? (claimable
            ? `<button class="btn btn-sm btn-primary" data-claim="${t.id}">领取</button>`
            : claimed ? '<span class="tag tag-gray tiny">已领取</span>' : '<span class="tag tag-gray tiny">已领取</span>') : ''}
          ${ucId !== undefined ? statusChip(tab) : ''}
        </div>
        <div class="muted tiny mt-6">${SCOPE[t.scopeType] || ''}${t.validEndTime ? ` · ${fmtDate(t.validEndTime)} 到期` : ''}</div>
      </div>
    </div>`;
  }
  function statusChip(st) {
    return { 1: '<span class="tag tag-gray tiny">已使用</span>', 2: '<span class="tag tag-gray tiny">已过期</span>' }[st] || '<span class="tag tag-green tiny">可使用</span>';
  }

  async function paint() {
    const host = el.querySelector('#cp-host');
    el.querySelectorAll('#cp-tabs .cat').forEach((x) => x.classList.toggle('active', x.dataset.t === tab));
    try {
      if (tab === 'center') {
        const list = await apiMarketing.couponCenter();
        const mine = await apiMarketing.couponMy(0);
        const claimedIds = new Set((mine || []).map((u) => u.couponId));
        host.innerHTML = (list || []).length
          ? list.map((t) => tplCard(t, { claimable: !claimedIds.has(t.id), claimed: claimedIds.has(t.id) })).join('')
          : '<div class="empty"><span class="ico">🎫</span>暂无可领取的优惠券</div>';
      } else {
        const mine = await apiMarketing.couponMy(Number(tab));
        const center = await apiMarketing.couponCenter();
        const tplMap = new Map((center || []).map((t) => [t.id, t]));
        host.innerHTML = (mine || []).length
          ? mine.map((uc) => {
              const t = tplMap.get(uc.couponId) || {
                id: uc.couponId, name: `优惠券 #${uc.id}`, type: 1, faceValueFen: 0, thresholdFen: 0,
                scopeType: 0, validEndTime: uc.validEndTime,
              };
              return tplCard(t, { ucId: uc.id });
            }).join('')
          : '<div class="empty"><span class="ico">🎫</span>暂无优惠券</div>';
      }
    } catch (e) { host.innerHTML = `<div class="empty">${escapeHtml(e.message)}</div>`; }
  }
  await paint();

  el.querySelector('#cp-tabs').onclick = (e) => {
    const t = e.target.closest('[data-t]');
    if (!t) return;
    tab = t.dataset.t; paint();
  };
  el.querySelector('#cp-host').addEventListener('click', async (e) => {
    const b = e.target.closest('[data-claim]');
    if (!b) return;
    b.disabled = true; b.textContent = '领取中';
    try {
      await apiMarketing.couponClaim(b.dataset.claim);
      toastSuccess('领取成功'); paint();
    } catch (err) { toastError(err.message); b.disabled = false; b.textContent = '领取'; }
  });
}
