// 个人资料 + 积分 / 余额明细
import { shell } from '../lib/shell.js';
import { apiUser } from '../api.js';
import { escapeHtml, fmtTime, fen2yuan } from '../lib/ui.js';

const FLOW_TYPE_POINTS = { 1: '获取', 2: '使用', 3: '冻结', 4: '释放', 5: '退回', 6: '过期' };
const FLOW_TYPE_FUND = { 1: '收入', 2: '支出' };

export async function renderProfile({ el, query }) {
  let tab = query.tab || 'info';
  el.innerHTML = shell({ title: '我的资料', showBack: true, content: '<div class="loading-more mt-20">加载中…</div>' });

  const [me, level, points] = await Promise.all([
    apiUser.me().catch(() => null),
    apiUser.level().catch(() => null),
    apiUser.points(),
  ]);

  function tabs() {
    return `<div class="cat-bar" id="p-tabs" style="position:static;border-radius:6px;margin-bottom:12px">
      ${[['info', '资料'], ['points', '积分明细'], ['balance', '资金明细']].map(([k, l]) =>
        `<span class="cat ${tab === k ? 'active' : ''}" data-t="${k}">${l}</span>`).join('')}
    </div>`;
  }
  const bar = `<div class="page-bar"><button class="pb-back" id="hd-back" type="button">‹ 返回</button><h1>我的资料</h1><span class="flex1"></span></div>`;

  function infoPage() {
    return `
    <div class="card section-pad" style="margin-bottom:12px">
      <div class="row" style="gap:14px">
        <div class="avatar" style="width:56px;height:56px;border-radius:50%;background:var(--primary-light);display:flex;align-items:center;justify-content:center;font-size:28px">🙂</div>
        <div><b style="font-size:17px">${escapeHtml(me?.nickname || me?.username || '-')}</b>
          <div class="muted tiny mt-6">@${escapeHtml(me?.username || '')}</div></div>
      </div>
      <div class="mt-16">
        <div class="row-between tiny" style="padding:8px 0"><span class="muted">手机号</span><span>${escapeHtml(me?.phone || '-')}</span></div>
        <div class="row-between tiny" style="padding:8px 0"><span class="muted">用户类型</span><span>${me?.userType === 1 ? '商户' : '买家用户'}</span></div>
        <div class="row-between tiny" style="padding:8px 0"><span class="muted">会员等级</span><span>Lv.${me?.level ?? 0} ${escapeHtml(level?.levelName || '')}</span></div>
        <div class="row-between tiny" style="padding:8px 0"><span class="muted">成长值</span><span>${me?.growth ?? 0}</span></div>
        <div class="row-between tiny" style="padding:8px 0"><span class="muted">会员折扣</span><span>${level?.discount ? (Number(level.discount) * 10).toFixed(1) + ' 折' : '无'}</span></div>
        <div class="row-between tiny" style="padding:8px 0"><span class="muted">积分余额</span><span>${points?.balance ?? 0}（冻结 ${points?.frozen ?? 0}）</span></div>
      </div>
    </div>
    <p class="muted tiny">昵称/头像资料由账号体系管理，暂不支持自助修改。</p>`;
  }

  async function pointsPage() {
    const r = await apiUser.pointsFlows(1, 50);
    const list = r?.list || [];
    return flowList(list, (f) => ({
      title: `积分${FLOW_TYPE_POINTS[f.changeType] || '变动'}`,
      amt: `${[1, 4, 5].includes(f.changeType) ? '+' : '-'}${f.amount}`,
      plus: [1, 4, 5].includes(f.changeType),
      after: `余额 ${f.balanceAfter}`,
      remark: f.remark,
    }));
  }

  async function balancePage() {
    const [b1, b2] = await Promise.all([apiUser.balanceFlows(1, 1, 50), apiUser.balanceFlows(2, 1, 50)]);
    const list = [...(b1?.list || []).map((x) => ({ ...x, _t: '余额' })), ...(b2?.list || []).map((x) => ({ ...x, _t: '赠金' }))]
      .sort((a, b) => new Date(b.createTime) - new Date(a.createTime)).slice(0, 50);
    return flowList(list, (f) => ({
      title: `${f._t}${FLOW_TYPE_FUND[f.changeType] || '变动'}`,
      amt: `${f.changeType === 1 ? '+' : '-'}¥${fen2yuan(f.amount)}`,
      plus: f.changeType === 1,
      after: `余额 ¥${fen2yuan(f.balanceAfter)}`,
      remark: f.remark,
    }));
  }

  function flowList(list, mapFn) {
    if (!list.length) return '<div class="empty"><span class="ico">🧾</span>暂无明细记录</div>';
    return `<div class="card">${list.map((f) => {
      const m = mapFn(f);
      return `<div class="row-between" style="padding:13px 14px;border-bottom:1px solid #f5f6f8">
        <div><div style="font-size:13px">${m.title}${m.remark ? `<span class="muted tiny"> · ${escapeHtml(m.remark)}</span>` : ''}</div>
        <div class="muted tiny mt-6">${fmtTime(f.createTime)} · ${m.after}</div></div>
        <b style="color:${m.plus ? 'var(--success)' : 'var(--text-1)'}">${m.amt}</b>
      </div>`;
    }).join('')}</div>`;
  }

  async function paint() {
    let body = infoPage();
    if (tab === 'points') body = await pointsPage();
    if (tab === 'balance') body = await balancePage();
    el.querySelector('.site-content').innerHTML = bar + tabs() + body;
    el.querySelector('#p-tabs').onclick = (e) => {
      const t = e.target.closest('[data-t]');
      if (!t) return;
      tab = t.dataset.t; paint();
    };
  }
  await paint();
}
