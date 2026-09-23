// 售后：列表 / 申请 / 详情
import { shell } from '../lib/shell.js';
import { apiAftersale, apiOrder, AFTERSALE_TYPE } from '../api.js';
import { escapeHtml, fen2yuan, fmtTime, toastSuccess, toastError, confirm as uiConfirm } from '../lib/ui.js';
import { go } from '../lib/router.js';
import { createPager } from '../lib/pager.js';

const FALLBACK = ['🎁', '📦', '🔄', '📮', '🛡️'];
const AS_STATUS = {
  10: { label: '待商家审核', cls: 'tag-gold' },
  20: { label: '待买家退货', cls: 'tag-blue' },
  30: { label: '商家收货中', cls: 'tag-blue' },
  40: { label: '退款中', cls: 'tag-gold' },
  41: { label: '待换货发出', cls: 'tag-gold' },
  42: { label: '换货已发出', cls: 'tag-blue' },
  43: { label: '待收换货', cls: 'tag-red' },
  50: { label: '已完成', cls: 'tag-green' },
  55: { label: '已驳回', cls: 'tag-gray' },
  80: { label: '平台介入中', cls: 'tag-gold' },
  90: { label: '已取消', cls: 'tag-gray' },
};

// ---------------- 列表 ----------------
export async function renderAftersaleList({ el }) {
  el.innerHTML = shell({
    title: '我的售后',
    showBack: true,
    activeTab: '',
    content: '<div id="as-host"></div>',
  });
  const pager = createPager({
    container: el.querySelector('#as-host'),
    fetchPage: (p, size) => apiAftersale.page({ page: p, size }),
    renderList: (list) => list.map(card).join(''),
    emptyHtml: '<div class="card"><div class="empty"><span class="ico">🎫</span>暂无售后单<br><span class="muted tiny">商品签收后可在订单详情中申请售后</span></div></div>',
  });
  pager.reset({});

  function card(a) {
    const t = AFTERSALE_TYPE[a.type] || { label: `类型${a.type}`, icon: '🛠️' };
    const s = AS_STATUS[a.status] || { label: a.status, cls: 'tag-gray' };
    return `
    <div class="order-card" data-no="${a.aftersaleNo}" style="cursor:pointer">
      <div class="order-head row-between">
        <span>${t.icon} ${t.label} · ${escapeHtml(a.aftersaleNo)}</span>
        <span class="tag ${s.cls}">${s.label}</span>
      </div>
      <div class="section-pad tiny">
        <div class="muted">关联订单：${escapeHtml(a.orderNo)}</div>
        <div class="muted mt-6">申请时间：${fmtTime(a.applyTime)}</div>
        <div class="mt-8">${escapeHtml(a.reason || '')}</div>
        ${a.refundFen ? `<div class="mt-8">预计/实际退款 <span class="price">${fen2yuan(a.refundFen)}</span></div>` : ''}
        ${a.rejectReason ? `<div class="tag tag-gray tiny mt-8">驳回原因：${escapeHtml(a.rejectReason)}</div>` : ''}
      </div>
    </div>`;
  }
  el.querySelector('#as-host').addEventListener('click', (e) => {
    const c = e.target.closest('[data-no]');
    if (c) go('/aftersale/' + c.dataset.no);
  });
}

// ---------------- 申请 ----------------
export async function renderAftersaleApply({ el, params }) {
  const orderNo = params.orderNo;
  const o = await apiOrder.detail(orderNo).catch(() => null);
  if (!o) { el.innerHTML = shell({ title: '申请售后', showBack: true, content: '<div class="empty">订单不存在</div>' }); return; }

  // 可申请的商品（完成/售后中订单）
  const items = (o.items || []);
  const picked = new Map(); // orderItemId -> qty
  let type = 1;
  let responsibilitySide = 1;

  el.innerHTML = shell({
    title: '申请售后',
    showBack: true,
    content: `
    <div class="confirm-card">
      <b>选择售后类型</b>
      <div class="grid-3 mt-12" id="type-row">
        ${Object.entries(AFTERSALE_TYPE).map(([code, t]) => `
          <div class="radio-card ${Number(code) === type ? 'on' : ''}" data-t="${code}">
            <span class="ico">${t.icon}</span>${t.label}
          </div>`).join('')}
      </div>
    </div>
    <div class="confirm-card">
      <b>申请商品</b>
      <div id="item-row" class="mt-12">
        ${items.map((it) => `
        <label class="row gap-8" style="padding:10px 0;border-bottom:1px solid #f5f6f8;cursor:pointer">
          <input type="checkbox" data-iid="${it.orderItemId}" data-max="${it.qty}">
          <div class="order-thumb">${it.image ? `<img src="${escapeHtml(it.image)}">` : FALLBACK[Number(it.skuId) % FALLBACK.length]}</div>
          <div class="flex1"><div class="ellipsis-2" style="font-size:13px">${escapeHtml(it.skuName)}</div>
          <div class="muted tiny mt-6">x${it.qty}</div></div>
          <span class="qty-stepper" style="display:none" data-stepper="${it.orderItemId}">
            <button type="button" data-q="-1">−</button><input value="1" readonly><button type="button" data-q="1">＋</button>
          </span>
        </label>`).join('')}
      </div>
    </div>
    <div class="confirm-card">
      <div class="form-item">
        <label>责任方</label>
        <select class="select" id="resp">
          <option value="1">商家责任（质量/发错/漏发）</option>
          <option value="2">买家原因（多拍/不想要）</option>
          <option value="3">运费险理赔</option>
        </select>
      </div>
      <div class="form-item">
        <label>申请说明</label>
        <textarea id="reason" class="textarea" maxlength="300" placeholder="请描述问题，如“收到商品有破损”（5-300字）"></textarea>
      </div>
      <div class="field-error" id="as-err"></div>
      <button class="btn btn-primary btn-block btn-lg" id="as-submit">提交申请</button>
    </div>`,
  });

  el.querySelector('#type-row').onclick = (e) => {
    const t = e.target.closest('[data-t]');
    if (!t) return;
    type = Number(t.dataset.t);
    el.querySelectorAll('#type-row .radio-card').forEach((x) => x.classList.toggle('on', Number(x.dataset.t) === type));
  };
  el.querySelector('#resp').onchange = (e) => { responsibilitySide = Number(e.target.value); };

  el.querySelector('#item-row').onclick = (e) => {
    const cb = e.target.closest('input[type=checkbox]');
    const qbtn = e.target.closest('[data-q]');
    if (qbtn) {
      e.preventDefault();
      const stepper = qbtn.closest('[data-stepper]');
      const iid = stepper.dataset.stepper;
      const input = stepper.querySelector('input');
      const max = Number(stepper.parentElement.querySelector('input[type=checkbox]').dataset.max);
      let n = Number(input.value) + Number(qbtn.dataset.q);
      n = Math.max(1, Math.min(max, n));
      input.value = n; picked.set(iid, n);
      return;
    }
    if (cb) {
      const iid = cb.dataset.iid;
      const stepper = el.querySelector(`[data-stepper="${iid}"]`);
      if (cb.checked) { stepper.style.display = 'inline-flex'; picked.set(iid, 1); }
      else { stepper.style.display = 'none'; picked.delete(iid); }
    }
  };

  el.querySelector('#as-submit').onclick = async () => {
    const reason = el.querySelector('#reason').value.trim();
    if (!picked.size) { toastError('请选择申请售后的商品'); return; }
    if (reason.length < 5) { el.querySelector('#as-err').textContent = '请填写至少 5 个字的申请说明'; return; }
    const body = {
      orderNo,
      type,
      responsibilitySide,
      reason,
      items: [...picked.entries()].map(([orderItemId, qty]) => ({ orderItemId, qty })),
    };
    const btn = el.querySelector('#as-submit');
    btn.disabled = true;
    try {
      const no = await apiAftersale.apply(body);
      toastSuccess('售后申请已提交');
      go('/aftersale/' + no);
    } catch (e) { el.querySelector('#as-err').textContent = escapeHtml(e.message); btn.disabled = false; }
  };
}

// ---------------- 详情 ----------------
export async function renderAftersaleDetail({ el, params }) {
  const no = params.no;
  let d;
  try { d = await apiAftersale.detail(no); }
  catch (e) { el.innerHTML = shell({ title: '售后详情', showBack: true, content: `<div class="empty">${escapeHtml(e.message)}</div>` }); return; }

  const a = d.aftersale;
  const items = d.items || [];
  const t = AFTERSALE_TYPE[a.type] || { label: a.type, icon: '🛠️' };
  const s = AS_STATUS[a.status] || { label: a.status, cls: 'tag-gray' };

  el.innerHTML = shell({
    title: '售后详情',
    showBack: true,
    showTabbar: false,
    content: `
    <div class="card section-pad" style="margin-bottom:12px">
      <div class="row-between"><b>${t.icon} ${t.label}</b><span class="tag ${s.cls}">${s.label}</span></div>
      <div class="muted tiny mt-8">售后单号：${escapeHtml(a.aftersaleNo)}</div>
      <div class="muted tiny mt-6">关联订单：${escapeHtml(a.orderNo)}</div>
      <div class="mt-12">${escapeHtml(a.reason || '')}</div>
      ${a.rejectReason ? `<div class="tag tag-gray tiny mt-8">驳回原因：${escapeHtml(a.rejectReason)}</div>` : ''}
    </div>

    <div class="confirm-card">
      <b>售后商品</b>
      ${items.map((it) => `
      <div class="row gap-12" style="padding:10px 0;border-bottom:1px solid #f5f6f8">
        <div class="flex1"><div class="ellipsis-2" style="font-size:13px">${escapeHtml(it.productName)}</div>
        <div class="muted tiny mt-6">${escapeHtml(it.skuSpec || '')} · x${it.qty}</div></div>
        <div style="text-align:right"><div class="muted tiny">实付 ${fen2yuan(it.paidFen)}</div>
        ${it.refundFen ? `<div class="price tiny mt-6">退 ${fen2yuan(it.refundFen)}</div>` : ''}</div>
      </div>`).join('')}
      ${a.refundFen ? `<div class="row-between mt-12"><b>退款总额</b><span class="price big">${fen2yuan(a.refundFen)}</span></div>` : ''}
    </div>

    <div class="confirm-card">
      ${kv('申请时间', a.applyTime ? fmtTime(a.applyTime) : '')}
      ${kv('审核时间', a.auditTime ? fmtTime(a.auditTime) : '')}
      ${kv('买家退货', a.returnShipTime ? `${escapeHtml(a.returnCompany || '')} ${escapeHtml(a.returnLogisticsNo || '')} · ${fmtTime(a.returnShipTime)}` : '')}
      ${kv('商家签收', a.merchantReceiveTime ? fmtTime(a.merchantReceiveTime) : '')}
      ${kv('换货发出', a.exchangeShipTime ? `${escapeHtml(a.exchangeCompany || '')} ${escapeHtml(a.exchangeLogisticsNo || '')} · ${fmtTime(a.exchangeShipTime)}` : '')}
      ${kv('完成时间', a.finishTime ? fmtTime(a.finishTime) : '')}
      ${a.refundNo ? kv('退款单号', a.refundNo) : ''}
    </div>

    <div class="confirm-card">
      <b>补充凭证</b>
      <div class="form-item mt-12"><textarea id="ev-content" class="textarea" maxlength="300" placeholder="文字说明（选填）"></textarea></div>
      <div class="form-item"><input id="ev-urls" class="input" placeholder="图片 URL，多个用英文逗号分隔（选填）"></div>
      <button class="btn btn-sm" id="add-evidence">上传/补充凭证</button>
    </div>

    <div style="height:12px"></div>
    <div class="pay-bar" id="as-actions"></div>`,
  });

  // 状态驱动操作
  const actions = el.querySelector('#as-actions');
  const btns = [];
  if ([10, 20, 55].includes(a.status)) btns.push(`<button class="btn" data-a="cancel">撤销售后</button>`);
  if (a.status === 20) btns.push(`<button class="btn btn-primary" data-a="ship">填写退货物流</button>`);
  if (a.status === 43) btns.push(`<button class="btn btn-primary" data-a="exchange-ok">确认收到换货</button>`);
  if ([10, 20, 30, 41, 55].includes(a.status) && a.status !== 80) btns.push(`<button class="btn" data-a="intervene">申请平台介入</button>`);
  if (a.status === 55) btns.push(`<a class="btn btn-primary" href="#/aftersale/apply/${a.orderNo}">重新申请</a>`);
  actions.innerHTML = `<span class="flex1"></span>${btns.join('')}`;

  actions.onclick = async (e) => {
    const b = e.target.closest('[data-a]');
    if (!b) return;
    const act = b.dataset.a;
    try {
      if (act === 'cancel') {
        if (!(await uiConfirm({ content: '确定撤销售后申请？', danger: true, okText: '撤销' }))) return;
        await apiAftersale.cancel(no); toastSuccess('已撤销'); go('/aftersale');
      } else if (act === 'ship') {
        const company = prompt('物流公司（如：顺丰速运）');
        if (!company) return;
        const logisticsNo = prompt('物流单号');
        if (!logisticsNo) return;
        await apiAftersale.shipReturn(no, { company, logisticsNo });
        toastSuccess('退货物流已提交'); go('/aftersale/' + no);
      } else if (act === 'exchange-ok') {
        if (!(await uiConfirm({ content: '确认已收到换货/补发商品？', okText: '确认收货' }))) return;
        await apiAftersale.exchangeConfirm(no); toastSuccess('已确认'); go('/aftersale/' + no);
      } else if (act === 'intervene') {
        if (!(await uiConfirm({ content: '平台专员将介入处理，确认申请？', okText: '申请介入' }))) return;
        await apiAftersale.intervene(no); toastSuccess('已申请平台介入'); go('/aftersale/' + no);
      }
    } catch (err) { toastError(err.message); }
  };

  el.querySelector('#add-evidence').onclick = async () => {
    const content = el.querySelector('#ev-content').value.trim();
    const urls = el.querySelector('#ev-urls').value.trim();
    if (!content && !urls) { toastError('请填写说明或图片地址'); return; }
    try {
      await apiAftersale.evidence(no, { evidenceType: urls ? 1 : 3, content, mediaUrls: urls || null });
      toastSuccess('凭证已补充'); go('/aftersale/' + no);
    } catch (e) { toastError(e.message); }
  };
}

function kv(k, v) {
  if (!v) return '';
  return `<div class="row-between tiny" style="padding:5px 0"><span class="muted">${k}</span><span style="max-width:60%;text-align:right">${v}</span></div>`;
}
