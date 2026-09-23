// 收银台：选渠道 → 下单 → 本地 mock 渠道 HMAC 签名回调驱动成交 → 轮询到成功
import { shell } from '../lib/shell.js';
import { apiOrder, apiPay, PAY_METHODS, MOCK_CHANNEL, uuid } from '../api.js';
import { toastError, fen2yuan, escapeHtml } from '../lib/ui.js';
import { go } from '../lib/router.js';

async function hmacHex(data, secret) {
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(secret),
    { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const sig = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(data));
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

export async function renderPay({ el, params }) {
  const orderNo = params.orderNo;
  let order;
  try { order = await apiOrder.detail(orderNo); }
  catch (e) {
    el.innerHTML = shell({ title: '收银台', showBack: true, showTabbar: false, content: `<div class="empty">${escapeHtml(e.message)}</div>` });
    return;
  }

  // 已支付则直接跳结果页
  if (order.status !== 10) {
    if (order.status === 20 || order.status === 30 || order.status === 40) { go('/pay-result/' + orderNo); return; }
    el.innerHTML = shell({ title: '收银台', showBack: true,
      content: `<div class="empty"><span class="ico">🚫</span>订单当前状态不可支付（${order.status}）<br><a class="btn mt-16" href="#/orders/${orderNo}">查看订单</a></div>` });
    return;
  }

  let method = 1;
  drawChoose();

  function drawChoose() {
    el.innerHTML = shell({
      title: '收银台',
      showBack: true,
      showTabbar: false,
      content: `
      <div class="confirm-card" style="margin-top:10px">
        <div class="muted tiny">订单号</div>
        <div class="tiny mt-6">${escapeHtml(orderNo)}</div>
        <div class="mt-12"><span class="muted tiny">应付金额</span></div>
        <div class="price big mt-6">${fen2yuan(order.payFen)}</div>
      </div>
      <div class="confirm-card" style="padding:4px 0">
        ${PAY_METHODS.map((m) => `
          <label class="pay-channel ${method === m.code ? 'on' : ''}" data-m="${m.code}">
            <span style="font-size:22px">${m.icon}</span>
            <span class="flex1">${m.name}${m.hint ? `<span class="tag tag-red tiny" style="margin-left:6px">${m.hint}</span>` : ''}</span>
            <span class="radio"></span>
          </label>`).join('')}
      </div>
      <div class="muted tiny" style="padding:12px 4px">💡 本地演示环境，6 种第三方渠道为 mock 渠道；点击「确认支付」后在模拟收银台完成即可。余额支付需要账户有余额。</div>
      <div class="pay-bar" style="margin-top:12px">
        <div class="flex1"><span class="muted tiny">合计</span> <span class="price big">${fen2yuan(order.payFen)}</span></div>
        <button class="btn btn-primary btn-lg" id="pay-ok" style="min-width:160px">确认支付 ¥${fen2yuan(order.payFen)}</button>
      </div>`,
    });
    el.querySelectorAll('[data-m]').forEach((row) => {
      row.onclick = () => { method = Number(row.dataset.m); el.querySelectorAll('.pay-channel').forEach((x) => x.classList.toggle('on', Number(x.dataset.m) === method)); };
    });
    el.querySelector('#pay-ok').onclick = doPay;
  }

  async function doPay() {
    const btn = el.querySelector('#pay-ok');
    btn.disabled = true; btn.textContent = '正在拉起…';
    let payment;
    try {
      payment = await apiPay.create({
        orderNo,
        payMethod: method,
        amountFen: order.payFen,
        subject: `商城订单-${orderNo}`,
        terminal: 2,
      });
    } catch (e) { toastError(e.message); btn.disabled = false; btn.textContent = '确认支付'; return; }

    // 余额支付：服务端直接扣余额，轮询状态
    if (method === 3) {
      btn.textContent = '正在确认收款…';
      const ok = await pollUntil(payment.payNo);
      if (ok) { go('/pay-result/' + orderNo); return; }
      toastError('余额不足或支付未完成，可稍后在订单里重试');
      drawChoose();
      return;
    }

    // mock 渠道：展示模拟收银台
    drawMockCashier(payment, method);
  }

  function drawMockCashier(payment, payMethod) {
    const ch = MOCK_CHANNEL[payMethod];
    el.innerHTML = shell({
      title: `${ch.name} · 模拟收银台`,
      showBack: true, showTabbar: false,
      content: `
      <div class="pay-result">
        <div class="ico">${PAY_METHODS.find((x) => x.code === payMethod)?.icon || '💳'}</div>
        <h2>¥${fen2yuan(payment.amountFen)}</h2>
        <p class="muted tiny">支付单 ${escapeHtml(payment.payNo)}</p>
        <p class="muted tiny mt-6">演示环境不会真实扣款。<br>点击下方按钮模拟渠道异步通知「支付成功」：</p>
        <div style="max-width:300px;margin:24px auto 0;display:flex;flex-direction:column;gap:10px">
          <button class="btn btn-primary btn-lg btn-block" id="mock-success">模拟支付成功</button>
          <button class="btn" id="mock-cancel">取消支付</button>
        </div>
      </div>`,
    });
    el.querySelector('#mock-cancel').onclick = () => { go('/orders/' + orderNo); };
    el.querySelector('#mock-success').onclick = async () => {
      const b = el.querySelector('#mock-success');
      b.disabled = true; b.textContent = '通知渠道中…';
      try {
        const notifyId = 'N' + Date.now() + Math.floor(Math.random() * 1000);
        const channelTxnNo = ch.code + '_T_' + uuid().slice(0, 16);
        const canonical = [
          `amountFen=${payment.amountFen}`,
          `channelCode=${ch.code}`,
          `channelTxnNo=${channelTxnNo}`,
          `notifyId=${notifyId}`,
          `payNo=${payment.payNo}`,
          'status=SUCCESS',
        ].join('&');
        const sign = await hmacHex(canonical, ch.secret);
        await apiPay.notifyPay(ch.code, {
          notifyId, payNo: payment.payNo, channelTxnNo,
          amountFen: payment.amountFen, status: 'SUCCESS', sign,
        });
        b.textContent = '正在确认…';
        const ok = await pollUntil(payment.payNo);
        if (ok) { go('/pay-result/' + orderNo); return; }
        toastError('支付结果同步中，请稍后在订单页查看');
        go('/orders/' + orderNo);
      } catch (e) {
        toastError('回调失败：' + e.message);
        b.disabled = false; b.textContent = '模拟支付成功';
      }
    };
  }

  // 轮询支付单至 status=30（success），最多 ~20s
  async function pollUntil(payNo) {
    for (let i = 0; i < 14; i++) {
      await new Promise((r) => setTimeout(r, 1500));
      try {
        const p = await apiPay.getByPayNo(payNo);
        if (p.status === 30) return true;
        if (p.status === 40 || p.status === 50) return false;
      } catch (_) {}
    }
    return false;
  }
}
