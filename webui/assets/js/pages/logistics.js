// 订单进度（无买家物流轨迹接口，展示订单关键时间线）
import { shell } from '../lib/shell.js';
import { apiOrder } from '../api.js';
import { fmtTime, escapeHtml } from '../lib/ui.js';

export async function renderLogistics({ el, params }) {
  const n = params.orderNo;
  const o = await apiOrder.detail(n).catch(() => null);
  const nodes = [
    { t: '买家下单', time: o?.createTime, done: !!o?.createTime },
    { t: '支付成功', time: o?.payTime, done: !!o?.payTime },
    { t: '商家发货', time: o?.shipTime, done: !!o?.shipTime },
    { t: '确认收货 / 交易完成', time: o?.confirmTime || o?.completeTime, done: !!(o?.confirmTime || o?.completeTime) },
  ];
  el.innerHTML = shell({
    title: '订单进度',
    showBack: true,
    content: `
    <div class="card section-pad" style="max-width:860px">
      <b>物流与进度</b>
      ${o?.receiver ? `<div class="muted tiny mt-8">收货人 ${escapeHtml(o.receiver.receiver)} ${escapeHtml(o.receiver.phone)} · ${escapeHtml([o.receiver.province,o.receiver.city,o.receiver.district,o.receiver.detailAddress].filter(Boolean).join(' '))}</div>` : ''}
      ${o?.logisticsCompany || o?.logisticsNo ? `
      <div class="row gap-12 mt-12" style="padding:12px 14px;background:#fafafa;border-radius:6px;align-items:center">
        <span style="font-size:24px">🚚</span>
        <div>
          <div style="font-weight:600">${escapeHtml(o.logisticsCompany || '物流')} <span class="muted tiny">${escapeHtml(o.logisticsNo || '')}</span></div>
          <div class="muted tiny mt-6">${o.shipTime ? `已发货 · ${fmtTime(o.shipTime)}` : ''}</div>
        </div>
      </div>` : ''}
      <div style="margin-top:16px">
        ${nodes.map((x, i) => `
          <div style="display:flex;gap:12px;padding-bottom:18px;position:relative">
            ${i < nodes.length - 1 ? '<div style="position:absolute;left:7px;top:18px;bottom:0;width:2px;background:var(--border)"></div>' : ''}
            <span style="width:16px;height:16px;border-radius:50%;background:${x.done ? 'var(--success)' : '#fff'};border:2px solid ${x.done ? 'var(--success)' : 'var(--border)'};flex:none;z-index:1"></span>
            <div><div style="${x.done ? 'color:var(--success);font-weight:600' : 'color:var(--text-3)'}">${x.t}</div>
            <div class="muted tiny">${x.time ? fmtTime(x.time) : '待处理'}</div></div>
          </div>`).join('')}
      </div>
      <p class="muted tiny" style="margin-top:6px">快递单号将在商家发货后由商家填写并同步。</p>
    </div>`,
  });
}
