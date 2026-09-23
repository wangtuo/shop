// 支付结果
import { shell } from '../lib/shell.js';
import { apiOrder } from '../api.js';
import { fen2yuan, escapeHtml, fmtTime } from '../lib/ui.js';

export async function renderPayResult({ el, params }) {
  const orderNo = params.payNo; // 路由参数名沿用 orderNo

  el.innerHTML = shell({
    title: '支付结果',
    showBack: false,
    content: `
    <div class="pay-result">
      <div class="ico">⏳</div>
      <h2>支付结果确认中</h2>
      <p class="muted tiny">如已扣款，请稍候…</p>
      <div class="loading-more mt-20">正在同步支付结果</div>
    </div>`,
  });

  // 支付单到账后，订单状态经 MQ 异步推进，轮询等待
  let order = await apiOrder.detail(orderNo).catch(() => null);
  for (let i = 0; i < 12 && !(order && [20, 30, 40].includes(order.status)); i++) {
    await new Promise((r) => setTimeout(r, 1000));
    order = await apiOrder.detail(orderNo).catch(() => null);
  }
  const paid = order && [20, 30, 40].includes(order.status);
  const failed = order && [50, 70].includes(order.status);

  el.innerHTML = shell({
    title: '支付结果',
    showBack: false,
    content: `
    <div class="pay-result">
      <div class="ico">${paid ? '🎉' : failed ? '⚠️' : '⏳'}</div>
      <h2>${paid ? '支付成功' : failed ? '订单已关闭' : '支付结果确认中'}</h2>
      <p class="muted tiny">${paid ? '商家将尽快为您发货' : failed ? '订单超时或已取消' : '如已扣款，请稍候在订单中查看状态'}</p>
      ${order ? `
      <div class="card section-pad" style="margin:24px 16px 0;text-align:left">
        <div class="row-between tiny"><span class="muted">订单号</span><span>${escapeHtml(order.orderNo)}</span></div>
        <div class="row-between tiny mt-8"><span class="muted">支付金额</span><span class="price">${fen2yuan(order.payFen)}</span></div>
        <div class="row-between tiny mt-8"><span class="muted">下单时间</span><span>${fmtTime(order.createTime)}</span></div>
        ${order.payTime ? `<div class="row-between tiny mt-8"><span class="muted">支付时间</span><span>${fmtTime(order.payTime)}</span></div>` : ''}
      </div>` : ''}
      <div style="max-width:320px;margin:28px auto 0;display:flex;gap:10px">
        <a class="btn flex1" href="#/">继续购物</a>
        <a class="btn btn-primary flex1" href="#/orders/${orderNo}">${paid ? '查看订单' : '刷新状态'}</a>
      </div>
    </div>`,
  });
}
