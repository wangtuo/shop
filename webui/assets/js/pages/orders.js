// 订单列表 + 订单详情
import { shell } from '../lib/shell.js';
import { apiOrder, ORDER_STATUS } from '../api.js';
import { fen2yuan, escapeHtml, fmtTime, confirm as uiConfirm, toastSuccess, toastError } from '../lib/ui.js';
import { go } from '../lib/router.js';
import { createPager } from '../lib/pager.js';

const FALLBACK = ['🎁', '🛍️', '👜', '👟', '🎧', '⌚'];
const TABS = [
  { key: '', label: '全部' },
  { key: '10', label: '待付款' },
  { key: '20', label: '待发货' },
  { key: '30', label: '待收货' },
  { key: '40', label: '已完成' },
];
const TAB_FROM_Q = { pay: '10', ship: '20', receive: '30', done: '40' };

function orderCard(o) {
  const st = ORDER_STATUS[o.status] || { label: o.status, tone: 'tag-gray' };
  return `
  <div class="order-card" data-order="${o.orderNo}">
    <div class="order-head row-between">
      <span>订单号 ${escapeHtml(o.orderNo)}</span>
      <span class="tag ${st.tone}">${st.label}</span>
    </div>
    ${(o.items || []).map((it) => `
      <div class="order-goods">
        <div class="order-thumb">${it.image ? `<img src="${escapeHtml(it.image)}">` : FALLBACK[Number(it.skuId) % FALLBACK.length]}</div>
        <div class="flex1">
          <div class="ellipsis-2" style="font-size:13px">${escapeHtml(it.skuName)}</div>
          <div class="muted tiny mt-6">${escapeHtml(it.specText || '')}${it.aftersaleStatus && it.aftersaleStatus !== 6 ? ' · <span style="color:var(--warning)">售后中</span>' : ''}</div>
        </div>
        <div style="text-align:right"><div class="price">${fen2yuan(it.priceFen)}</div><div class="muted tiny">x${it.qty}</div></div>
      </div>`).join('')}
    <div class="order-foot">
      <span class="muted tiny">${fmtTime(o.createTime)}</span>
      <span class="flex1"></span>
      ${actionBtns(o)}
    </div>
  </div>`;
}

function actionBtns(o) {
  const n = o.orderNo;
  const b = [];
  if (o.status === 10) {
    b.push(`<button class="btn btn-sm" data-a="cancel" data-n="${n}">取消订单</button>`);
    b.push(`<a class="btn btn-sm btn-primary" href="#/pay/${n}">去支付</a>`);
  } else if (o.status === 20) {
    b.push(`<button class="btn btn-sm" data-a="remind" data-n="${n}">提醒发货</button>`);
  } else if (o.status === 30) {
    b.push(`<a class="btn btn-sm" href="#/orders/${n}/logistics">订单进度</a>`);
    b.push(`<button class="btn btn-sm btn-primary" data-a="confirm" data-n="${n}">确认收货</button>`);
  } else if (o.status === 40) {
    b.push(`<button class="btn btn-sm" data-a="aftersale" data-n="${n}">申请售后</button>`);
    b.push(`<button class="btn btn-sm" data-a="rebuy" data-n="${n}">再次购买</button>`);
  } else if (o.status === 60 || o.status === 61 || o.status === 62) {
    b.push(`<a class="btn btn-sm" href="#/aftersale">售后详情</a>`);
  } else if (o.status === 50 || o.status === 70) {
    b.push(`<button class="btn btn-sm" data-a="rebuy" data-n="${n}">再次购买</button>`);
    b.push(`<button class="btn btn-sm" data-a="delete" data-n="${n}">删除</button>`);
  }
  return b.join('');
}

export async function renderOrders({ el, query }) {
  const initial = TAB_FROM_Q[query.tab] || query.status || '';
  el.innerHTML = shell({
    title: '我的订单',
    activeTab: '/orders',
    showBack: true,
    content: `
    <div class="cat-bar" id="o-tabs">
      ${TABS.map((t) => `<span class="cat ${t.key === String(initial) ? 'active' : ''}" data-s="${t.key}">${t.label}</span>`).join('')}
    </div>
    <div id="o-host" style="margin-top:10px"></div>`,
  });

  const host = el.querySelector('#o-host');
  const pager = createPager({
    container: host,
    pageSize: 10,
    fetchPage: (p, size, extra) => apiOrder.list(extra?.status, p, size),
    renderList: (list) => list.map(orderCard).join(''),
    emptyHtml: '<div class="card"><div class="empty"><span class="ico">📦</span>暂无相关订单</div></div>',
  });
  pager.reset({ status: initial });

  el.querySelector('#o-tabs').onclick = (e) => {
    const t = e.target.closest('.cat');
    if (!t) return;
    el.querySelectorAll('#o-tabs .cat').forEach((x) => x.classList.toggle('active', x === t));
    pager.reset({ status: t.dataset.s });
  };

  host.addEventListener('click', async (e) => {
    const btn = e.target.closest('[data-a]');
    const card = e.target.closest('[data-order]');
    if (btn) {
      e.preventDefault();
      const n = btn.dataset.n;
      const a = btn.dataset.a;
      try {
        if (a === 'cancel') {
          if (!(await uiConfirm({ content: '确定取消该订单吗？', okText: '取消订单', danger: true }))) return;
          await apiOrder.cancel(n); toastSuccess('订单已取消');
        } else if (a === 'confirm') {
          if (!(await uiConfirm({ content: '请确认已收到商品', okText: '已收货' }))) return;
          await apiOrder.confirm(n); toastSuccess('已确认收货');
        } else if (a === 'remind') {
          await apiOrder.remind(n); toastSuccess('已提醒商家发货');
        } else if (a === 'rebuy') {
          await apiOrder.rebuy(n); toastSuccess('商品已加入购物车');
          go('/cart'); return;
        } else if (a === 'delete') {
          if (!(await uiConfirm({ content: '删除后不可恢复', okText: '删除', danger: true }))) return;
          await apiOrder.remove(n); toastSuccess('已删除');
        } else if (a === 'aftersale') { go('/aftersale/apply/' + n); return; }
        pager.reset({ status: el.querySelector('#o-tabs .cat.active').dataset.s });
      } catch (err) { toastError(err.message); }
      return;
    }
    if (card) { go('/orders/' + card.dataset.order); }
  });
}

export async function renderOrderDetail({ el, params }) {
  const n = params.orderNo;
  let o;
  try { o = await apiOrder.detail(n); }
  catch (e) { el.innerHTML = shell({ title: '订单详情', showBack: true, content: `<div class="empty">${escapeHtml(e.message)}</div>` }); return; }

  const st = ORDER_STATUS[o.status] || { label: o.status, tone: 'tag-gray' };
  const steps = [
    { label: '已下单', done: true },
    { label: '已付款', done: o.status >= 20 && o.status !== 50 && o.status !== 70 },
    { label: '已发货', done: o.status >= 30 },
    { label: '已完成', done: o.status === 40 },
  ];
  const curIdx = steps.filter((s) => s.done).length - 1;

  el.innerHTML = shell({
    title: '订单详情',
    showBack: true,
    showTabbar: false,
    content: `
    <div class="card" style="margin-bottom:12px">
      <div class="row-between section-pad">
        <b style="font-size:16px">${st.label}</b>
        <span class="tag ${st.tone}">${st.label}</span>
      </div>
      ${o.status === 10 ? '<div class="section-pad" style="padding-top:0"><span class="muted tiny">请尽快完成支付，超时订单将自动关闭</span></div>' : ''}
      <div class="status-steps">
        ${steps.map((s, i) => `<div class="st ${s.done ? 'done' : ''} ${i === curIdx && o.status !== 40 && o.status < 50 ? 'cur' : ''}"><span class="dot"></span>${s.label}</div>`).join('')}
      </div>
    </div>

    ${o.receiver ? `
    <a class="confirm-row" id="receiver-row">
      <span style="font-size:22px">📍</span>
      <div class="flex1">
        <b>${escapeHtml(o.receiver.receiver)}</b> <span class="muted tiny">${escapeHtml(o.receiver.phone)}</span>
        <div class="tiny muted mt-6">${escapeHtml([o.receiver.province, o.receiver.city, o.receiver.district, o.receiver.detailAddress].filter(Boolean).join(' '))}</div>
      </div>
      ${o.status === 10 ? '<span class="muted">修改地址 ›</span>' : ''}
    </a>` : ''}

    <div class="confirm-card">
      ${(o.items || []).map((it) => `
      <a class="order-goods" href="#/product/${it.spuId}" style="padding:10px 0;border-bottom:1px solid #f5f6f8">
        <div class="order-thumb">${it.image ? `<img src="${escapeHtml(it.image)}">` : FALLBACK[Number(it.skuId) % FALLBACK.length]}</div>
        <div class="flex1">
          <div class="ellipsis-2" style="font-size:13px">${escapeHtml(it.skuName)}</div>
          <div class="muted tiny mt-6">${escapeHtml(it.specText || '')}</div>
          ${it.aftersaleStatus && it.aftersaleStatus !== 6 ? '<span class="tag tag-gold tiny mt-6">售后中</span>' : ''}
        </div>
        <div style="text-align:right"><div class="price">${fen2yuan(it.priceFen)}</div><div class="muted tiny">x${it.qty}</div></div>
      </a>`).join('')}
      ${o.remark ? `<div class="tiny mt-8"><span class="muted">备注：</span>${escapeHtml(o.remark)}</div>` : ''}
    </div>

    <div class="confirm-card">
      <div class="row-between tiny"><span class="muted">商品总额</span><span>¥${fen2yuan(o.productTotalFen)}</span></div>
      ${o.productDiscountFen ? `<div class="row-between tiny mt-6"><span class="muted">商品优惠</span><span class="price">-¥${fen2yuan(o.productDiscountFen)}</span></div>` : ''}
      ${o.shopDiscountFen ? `<div class="row-between tiny mt-6"><span class="muted">店铺优惠</span><span class="price">-¥${fen2yuan(o.shopDiscountFen)}</span></div>` : ''}
      ${o.platformDiscountFen ? `<div class="row-between tiny mt-6"><span class="muted">平台优惠</span><span class="price">-¥${fen2yuan(o.platformDiscountFen)}</span></div>` : ''}
      ${o.pointsDeductFen ? `<div class="row-between tiny mt-6"><span class="muted">积分抵扣</span><span class="price">-¥${fen2yuan(o.pointsDeductFen)}</span></div>` : ''}
      <div class="row-between tiny mt-6"><span class="muted">运费${o.hasFreightInsurance ? '（含运费险）' : ''}</span><span>¥${fen2yuan(o.freightFen + (o.insurancePremiumFen || 0))}</span></div>
      <div class="row-between mt-12"><b>实付</b><span class="price big">${fen2yuan(o.payFen)}</span></div>
    </div>

    <div class="confirm-card">
      <div class="row-between tiny"><span class="muted">订单编号</span><span>${escapeHtml(o.orderNo)}</span></div>
      <div class="row-between tiny mt-6"><span class="muted">下单时间</span><span>${fmtTime(o.createTime)}</span></div>
      ${o.payTime ? `<div class="row-between tiny mt-6"><span class="muted">支付时间</span><span>${fmtTime(o.payTime)}</span></div>` : ''}
      ${o.shipTime ? `<div class="row-between tiny mt-6"><span class="muted">发货时间</span><span>${fmtTime(o.shipTime)}</span></div>` : ''}
      ${o.confirmTime ? `<div class="row-between tiny mt-6"><span class="muted">完成时间</span><span>${fmtTime(o.confirmTime)}</span></div>` : ''}
      ${o.payTransactionNo ? `<div class="row-between tiny mt-6"><span class="muted">支付流水</span><span>${escapeHtml(o.payTransactionNo)}</span></div>` : ''}
    </div>
    <div style="height:12px"></div>

    <div class="pay-bar">
      <span class="flex1"></span>
      ${detailBtns(o)}
    </div>`,
  });

  if (o.status === 10) el.querySelector('#receiver-row')?.addEventListener('click', () => go('/me/addresses?from=order&n=' + n));
  bindDetailActions(o);
}

function detailBtns(o) {
  const n = o.orderNo;
  if (o.status === 10) return `<a class="btn" href="#/orders">返回列表</a><a class="btn btn-primary" href="#/pay/${n}">去支付</a><button class="btn" data-d="cancel">取消订单</button>`;
  if (o.status === 20) return `<button class="btn" data-d="remind">提醒发货</button>`;
  if (o.status === 30) return `<a class="btn" href="#/orders/${n}/logistics">订单进度</a><button class="btn btn-primary" data-d="confirm">确认收货</button>`;
  if (o.status === 40) return `<button class="btn" data-d="rebuy">再次购买</button><a class="btn btn-primary" href="#/aftersale/apply/${n}">申请售后</a>`;
  if (o.status === 50 || o.status === 70) return `<button class="btn" data-d="rebuy">再次购买</button><button class="btn" data-d="delete">删除订单</button>`;
  if (o.status >= 60 && o.status <= 62) return `<a class="btn btn-primary" href="#/aftersale">查看售后</a>`;
  return '';
}

function bindDetailActions(o) {
  el2().querySelectorAll('[data-d]').forEach((btn) => {
    btn.onclick = async () => {
      const d = btn.dataset.d;
      try {
        if (d === 'cancel') { if (!(await uiConfirm({ content: '确定取消该订单？', danger: true, okText: '取消订单' }))) return; await apiOrder.cancel(o.orderNo); toastSuccess('已取消'); }
        if (d === 'confirm') { if (!(await uiConfirm({ content: '请确认已收到商品', okText: '已收货' }))) return; await apiOrder.confirm(o.orderNo); toastSuccess('已确认收货'); }
        if (d === 'remind') { await apiOrder.remind(o.orderNo); toastSuccess('已提醒'); }
        if (d === 'rebuy') { await apiOrder.rebuy(o.orderNo); toastSuccess('已加入购物车'); go('/cart'); return; }
        if (d === 'delete') { if (!(await uiConfirm({ content: '删除后不可恢复', danger: true, okText: '删除' }))) return; await apiOrder.remove(o.orderNo); toastSuccess('已删除'); go('/orders'); return; }
        go('/orders/' + o.orderNo);
      } catch (e) { toastError(e.message); }
    };
  });
}
function el2() { return document.getElementById('app'); }
