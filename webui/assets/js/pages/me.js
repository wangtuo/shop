// 我的：用户卡 + 资产统计 + 订单入口 + 功能菜单 + 签到
import { shell } from '../lib/shell.js';
import { apiUser, apiMarketing } from '../api.js';
import { auth } from '../lib/http.js';
import { toastSuccess, toastError, escapeHtml, fen2yuan } from '../lib/ui.js';
import { go } from '../lib/router.js';

const LEVEL_NAMES = ['普通会员', '铜卡会员', '银卡会员', '金卡会员', '钻石会员'];

export async function renderMe({ el }) {
  if (!auth.isLogin) {
    el.innerHTML = shell({
      activeTab: '/me',
      content: `<div class="me-hero row gap-12" style="margin-bottom:14px">
        <div class="avatar">👤</div>
        <div class="flex1"><div style="font-size:18px;font-weight:700">未登录</div>
        <div class="tiny" style="opacity:.85;margin-top:4px">登录后查看订单与会员权益</div></div>
      </div>
      <div style="margin-top:18px;max-width:300px"><a class="btn btn-primary btn-block btn-lg" href="#/login">立即登录 / 注册</a></div>`,
    });
    return;
  }

  el.innerHTML = shell({
    activeTab: '/me',
    content: `<div id="me-body"><div class="loading-more mt-20">加载中…</div></div>`,
  });

  const [me, level, points, coupons] = await Promise.all([
    apiUser.me().catch(() => null),
    apiUser.level().catch(() => null),
    apiUser.points(),
    apiMarketing.couponMy(0).catch(() => []),
  ]);
  auth.user = { ...auth.user, ...(me || {}), level: me?.level ?? auth.user.level };

  const body = el.querySelector('#me-body');
  const lv = me?.level ?? 0;
  body.innerHTML = `
    <div class="me-hero row gap-12">
      <div class="avatar">🙂</div>
      <div class="flex1">
        <div style="font-size:19px;font-weight:700">${escapeHtml(me?.nickname || me?.username || '商城用户')}</div>
        <div class="tiny" style="opacity:.9;margin-top:4px">${LEVEL_NAMES[lv] || '会员'} · 成长值 ${me?.growth ?? 0}
          ${level?.discount && Number(level.discount) < 1 ? ` · 会员折扣 ${(Number(level.discount) * 10).toFixed(1)}折` : ''}</div>
      </div>
      <a href="#/me/profile" style="color:#fff;font-size:13px">设置 ›</a>
    </div>

    <div class="me-stats">
      <a href="#/me/profile?tab=points" style="color:inherit"><div class="v">${points?.balance ?? 0}</div><div class="k">积分</div></a>
      <a href="#/me/profile?tab=balance" style="color:inherit"><div class="v">${coupons.length}</div><div class="k">优惠券</div></a>
      <a href="#/aftersale" style="color:inherit"><div class="v">售后</div><div class="k">退换修</div></a>
      <a href="#/me/profile" style="color:inherit"><div class="v">Lv.${lv}</div><div class="k">会员等级</div></a>
    </div>

    <div class="order-quick">
      <a href="#/orders?tab=pay" style="color:inherit"><span class="ico">💳</span>待付款</a>
      <a href="#/orders?tab=ship" style="color:inherit"><span class="ico">📦</span>待发货</a>
      <a href="#/orders?tab=receive" style="color:inherit"><span class="ico">🚚</span>待收货</a>
      <a href="#/orders?tab=done" style="color:inherit"><span class="ico">✅</span>已完成</a>
      <a href="#/aftersale" style="color:inherit"><span class="ico">🛠️</span>售后</a>
    </div>

    <div class="me-menu">
      <div class="mi" data-go="/me/coupons"><span class="ico">🎟️</span><span class="flex1">我的优惠券</span><span class="muted tiny">${coupons.length} 张可用</span><span class="arrow">›</span></div>
      <div class="mi" id="sign-in"><span class="ico">📅</span><span class="flex1">每日签到领积分</span><span class="tag tag-red tiny">积分</span><span class="arrow">›</span></div>
      <div class="mi" data-go="/me/addresses"><span class="ico">📍</span><span class="flex1">收货地址</span><span class="arrow">›</span></div>
      <div class="mi" data-go="/me/profile?tab=points"><span class="ico">⭐</span><span class="flex1">积分明细</span><span class="arrow">›</span></div>
      <div class="mi" data-go="/me/profile?tab=balance"><span class="flex1" style="margin-left:31px">余额 / 赠金明细</span><span class="arrow">›</span></div>
      <div class="mi" data-go="/aftersale"><span class="ico">🛡️</span><span class="flex1">售后服务</span><span class="arrow">›</span></div>
    </div>

    <div style="padding:14px 0;max-width:260px">
      <button class="btn btn-block" id="logout">退出登录</button>
    </div>
    <p class="muted tiny">本地演示环境 · 数据为开发实例</p>`;

  body.querySelectorAll('[data-go]').forEach((row) => row.onclick = () => go(row.dataset.go));
  body.querySelector('#logout').onclick = () => {
    auth.logout(); toastSuccess('已退出登录'); go('/');
  };
  body.querySelector('#sign-in').onclick = async () => {
    try {
      const r = await apiUser.signIn();
      toastSuccess(`签到成功，获得 ${r?.pointsEarn ?? 0} 积分`);
    } catch (e) { toastError(e.message); }
  };
}
