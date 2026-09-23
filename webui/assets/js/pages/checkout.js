// 确认订单：地址 + 商品 + 优惠券/积分试算 + 提交
import { shell } from '../lib/shell.js';
import { apiUser, apiProduct, apiMarketing, apiOrder, uuid } from '../api.js';
import { toastError, fen2yuan, escapeHtml } from '../lib/ui.js';
import { go } from '../lib/router.js';

export async function renderCheckout({ el }) {
  const ctxRaw = sessionStorage.getItem('shop_checkout_ctx');
  if (!ctxRaw) {
    el.innerHTML = shell({ title: '确认订单', showBack: true, content: '<div class="empty"><span class="ico">🛒</span>没有待结算商品<br><a class="btn btn-primary mt-16" href="#/" style="display:inline-flex">去购物</a></div>' });
    return;
  }
  const ctx = JSON.parse(ctxRaw);

  el.innerHTML = shell({ title: '确认订单', showBack: true, showTabbar: false, content: '<div class="loading-more mt-20">正在载入…</div>' });

  // ---- 并行基础数据 ----
  const [me, addrPage, pointsAcc] = await Promise.all([
    apiUser.me().catch(() => null),
    apiUser.addressList(1, 50).catch(() => ({ list: [] })),
    apiUser.points(),
  ]);
  const addresses = addrPage?.list || [];
  let address = addresses.find((a) => a.isDefault === 1) || addresses[0] || null;

  // ---- SKU 快照（价格/商户/类目）+ SPU 名称 ----
  const skuRefs = [];
  for (const it of ctx.items) {
    let snap = null;
    try { snap = await apiProduct.price(it.skuId, me?.level || 0); } catch (_) {}
    let detail = null;
    if (snap?.spuId) {
      try { detail = await apiProduct.detail(snap.spuId); } catch (_) {}
    }
    const skuDto = detail?.skus?.find((s) => String(s.skuId) === String(it.skuId));
    skuRefs.push({
      qty: it.qty,
      snap,
      spu: detail?.spu,
      sku: skuDto,
      priceFen: skuDto ? (skuDto.promotionPriceFen ?? skuDto.seckillPriceFen ?? skuDto.salePriceFen) : snap?.salePriceFen,
    });
  }

  // ---- 我的优惠券（未使用）+ 中心模板映射名称面值 ----
  const [myCoupons, centerTpls] = await Promise.all([
    apiMarketing.couponMy(0),
    apiMarketing.couponCenter(),
  ]);
  const tplMap = new Map((centerTpls || []).map((c) => [c.id, c]));
  const coupons = (myCoupons || []).map((uc) => ({ uc, tpl: tplMap.get(uc.couponId) || null }));

  const state = {
    categoryCouponId: null, shopCouponId: null, platformCouponId: null,
    usePointsFen: 0,
    insurance: false,
    remark: '',
    calc: null,
  };
  const pointsBalance = pointsAcc?.balance || 0;

  function addrBlock() {
    if (!address) {
      return `<a id="pick-addr" href="#/me/addresses/edit" class="confirm-row" style="display:flex">
        <span style="font-size:22px">📍</span>
        <div class="flex1" style="color:var(--primary)">请选择收货地址</div><span class="muted">›</span></a>`;
    }
    return `<a id="pick-addr" href="#/me/addresses?from=checkout" class="confirm-row" style="display:flex">
      <span style="font-size:22px">📍</span>
      <div class="flex1">
        <div><b>${escapeHtml(address.receiver)}</b> <span class="muted tiny">${escapeHtml(address.phone)}</span></div>
        <div class="tiny muted mt-6">${escapeHtml([address.province, address.city, address.district, address.detailAddress].filter(Boolean).join(' '))}</div>
        ${address.tag ? `<span class="tag tag-red tiny">${escapeHtml(address.tag)}</span>` : ''}
      </div><span class="muted">›</span></a>`;
  }

  function couponLine(label, key, type) {
    const rec = coupons.find((c) => c.uc.id === state[key]);
    let text = '不使用';
    if (rec) {
      text = rec.tpl
        ? `${couponFace(rec.tpl)}`
        : `优惠券 #${rec.uc.id}`;
    }
    const avail = coupons.filter((c) => !c.tpl || !type || c.tpl.type === type || type === 0);
    return `<div class="confirm-row" data-coupon="${key}">
      <span class="flex1 muted">${label}</span>
      <span class="${rec ? 'price' : 'muted'} tiny" style="font-weight:500">${text}</span>
      <span class="muted">›</span></div>`;
  }

  function couponFace(t) {
    if (t.type === 2) return `${(t.discountBp / 100).toFixed(t.discountBp % 100 === 0 ? 0 : 2).replace(/\.?0+$/, '')}折券${t.thresholdFen ? ` 满${fen2yuan(t.thresholdFen)}` : ''}`;
    if (t.type === 3) return `${fen2yuan(t.faceValueFen)} 无门槛`;
    if (t.type === 4) return '免运费券';
    return `减 ¥${fen2yuan(t.faceValueFen)}${t.thresholdFen ? ` 满${fen2yuan(t.thresholdFen)}` : ''}`;
  }

  function goodsBlock() {
    return `<div class="confirm-card">
      ${skuRefs.map((r) => `
      <div class="row gap-12" style="margin-bottom:12px">
        <div class="cart-thumb" style="width:64px;height:64px">${r.spu?.mainImage && !/example\.com/.test(r.spu.mainImage) ? `<img src="${escapeHtml(r.spu.mainImage)}" style="width:100%;height:100%;object-fit:cover;border-radius:8px">` : '📦'}</div>
        <div class="flex1">
          <div class="ellipsis-2" style="font-size:13px">${escapeHtml(r.sku?.skuName || r.spu?.name || `SKU ${r.snap?.skuId || ''}`)}</div>
          <div class="muted tiny mt-6">${escapeHtml(r.sku?.specText || '')}</div>
        </div>
        <div style="text-align:right">
          <div class="price">${fen2yuan(r.priceFen)}</div>
          <div class="muted tiny">x${r.qty}</div>
        </div>
      </div>`).join('')}
      <div class="row gap-8 mt-8">
        <input id="remark" class="input" style="height:36px;flex:1" maxlength="50" placeholder="订单备注（50字内）" value="${escapeHtml(state.remark)}">
      </div>
    </div>`;
  }

  function promoBlock() {
    const c = state.calc;
    return `
    ${couponLine('平台优惠券', 'platformCouponId', 0)}
    ${couponLine('店铺优惠券', 'shopCouponId', 0)}
    ${couponLine('类目优惠券', 'categoryCouponId', 0)}
    <div class="confirm-row">
      <span class="flex1 muted">积分抵扣${pointsBalance ? `（可用 ${pointsBalance} 积分）` : ''}</span>
      <span class="row gap-8">
        <span class="price tiny" id="points-fen">${state.usePointsFen ? `-¥${fen2yuan(state.usePointsFen)}` : ''}</span>
        <input type="range" id="points-range" min="0" max="${pointsBalance}" step="100" value="${state.usePointsFen}" ${pointsBalance ? '' : 'disabled'} style="width:110px">
      </span>
    </div>
    <div class="confirm-row">
      <span class="flex1 muted">运费险</span>
      <span class="row gap-8"><span class="tiny muted">${c?.insurancePremiumFen ? `¥${fen2yuan(c.insurancePremiumFen)}` : ''}</span>
        <input type="checkbox" id="ins" ${state.insurance ? 'checked' : ''}></span>
    </div>
    <div class="confirm-card" style="border-bottom:none">
      <div class="row-between tiny"><span class="muted">商品总额</span><span>¥${fen2yuan(c?.originalProductFen ?? skuRefs.reduce((a, r) => a + (r.priceFen || 0) * r.qty, 0))}</span></div>
      ${c && c.productPromoFen ? `<div class="row-between tiny mt-6"><span class="muted">商品活动</span><span class="price">-¥${fen2yuan(c.productPromoFen)}</span></div>` : ''}
      ${c && c.shopPromoFen ? `<div class="row-between tiny mt-6"><span class="muted">店铺活动</span><span class="price">-¥${fen2yuan(c.shopPromoFen)}</span></div>` : ''}
      ${c && (c.categoryCouponFen + c.shopCouponFen + c.platformCouponFen) ? `<div class="row-between tiny mt-6"><span class="muted">优惠券</span><span class="price">-¥${fen2yuan((c.categoryCouponFen||0)+(c.shopCouponFen||0)+(c.platformCouponFen||0))}</span></div>` : ''}
      ${c && c.pointsDeductFen ? `<div class="row-between tiny mt-6"><span class="muted">积分抵扣</span><span class="price">-¥${fen2yuan(c.pointsDeductFen)}</span></div>` : ''}
      <div class="row-between tiny mt-6"><span class="muted">运费</span><span>¥${fen2yuan(c?.freightFen ?? 0)}</span></div>
    </div>`;
  }

  function fullPage() {
    const c = state.calc;
    el.querySelector('.site-content').innerHTML = `
      <div class="page-bar">
        <button class="pb-back" id="hd-back" type="button">‹ 返回</button>
        <h1>确认订单</h1><span class="flex1"></span>
      </div>
      ${addrBlock()}
      ${goodsBlock()}
      <div>${promoBlock()}</div>
      <div class="pay-bar">
        <div class="flex1">
          应付总额：<span class="price big">${fen2yuan(c?.payFen ?? skuRefs.reduce((a, r) => a + (r.priceFen || 0) * r.qty, 0))}</span>
          <span class="muted tiny" style="margin-left:10px">运费 ¥${fen2yuan(c?.freightFen ?? 0)}</span>
        </div>
        <button class="btn btn-primary btn-lg" id="submit-order" style="min-width:150px">提交订单</button>
      </div>`;
    bindPage();
  }

  // ---- 试算 ----
  async function recalc() {
    const goodsFen = skuRefs.reduce((a, r) => a + (r.priceFen || 0) * r.qty, 0);
    const cmd = {
      userId: me?.userId,
      userLevel: me?.level || 0,
      orderType: 1,
      items: skuRefs.map((r) => ({
        skuId: r.snap?.skuId || ctx.items.find((x) => String(x.skuId) === String(r.sku?.skuId))?.skuId,
        spuId: r.snap?.spuId || r.sku?.spuId,
        merchantId: r.snap?.merchantId || r.sku?.merchantId,
        shopId: r.sku?.shopId,
        category3Id: r.sku?.category3Id,
        qty: r.qty,
        salePriceFen: r.priceFen,
      })),
      freightFen: 0,
      usePointsFen: state.usePointsFen,
      categoryCouponId: state.categoryCouponId || undefined,
      shopCouponId: state.shopCouponId || undefined,
      platformCouponId: state.platformCouponId || undefined,
    };
    try {
      state.calc = await apiMarketing.calculate(cmd);
      if (state.calc) state.usePointsFen = state.calc.pointsDeductFen || state.usePointsFen;
    } catch (e) {
      state.calc = null;
      toastError('优惠试算失败：' + e.message);
    }
    fullPage();
  }

  function couponSheet(layerKey) {
    const mask = document.createElement('div'); mask.className = 'sheet-mask';
    const sheet = document.createElement('div'); sheet.className = 'bottom-sheet';
    sheet.innerHTML = `
      <div class="bs-title">选择优惠券</div>
      <div style="padding:12px 14px">
        <div class="coupon" data-id="" style="border:1px solid var(--border)"><div class="cp-right"><b>不使用优惠券</b></div></div>
        ${coupons.map(({ uc, tpl }) => `
          <div class="coupon ${state[layerKey] === uc.id ? '' : ''}" data-id="${uc.id}">
            <div class="cp-left" style="padding-top:18px">
              ${tpl?.type === 2
                ? `<div style="font-size:20px;font-weight:800">${(tpl.discountBp / 100).toFixed(1).replace(/\.0$/, '')}<span style="font-size:13px">折</span></div>`
                : tpl?.type === 4 ? '<div style="font-size:18px;font-weight:800">免运费</div>'
                : `<div style="font-size:20px;font-weight:800">¥${fen2yuan(tpl?.faceValueFen ?? 0)}</div>`}
            </div>
            <div class="cp-right">
              <b>${escapeHtml(tpl?.name || `优惠券 #${uc.id}`)}</b>
              <span class="muted tiny mt-6">${tpl ? couponFace(tpl) : '平台/店铺优惠券'}</span>
              ${state[layerKey] === uc.id ? '<span class="tag tag-red tiny" style="margin-top:4px">已选</span>' : ''}
            </div>
          </div>`).join('')}
        ${!coupons.length ? '<div class="empty"><span class="ico">🎫</span>暂无可用优惠券</div>' : ''}
      </div>`;
    document.body.append(mask, sheet);
    const close = () => { mask.remove(); sheet.remove(); };
    mask.onclick = close;
    sheet.onclick = async (e) => {
      const c = e.target.closest('[data-id]');
      if (!c) return;
      state[layerKey] = c.dataset.id || null;
      close(); await recalc();
    };
  }

  function bindPage() {
    el.querySelectorAll('[data-coupon]').forEach((row) => {
      row.style.cursor = 'pointer';
      row.onclick = () => couponSheet(row.dataset.coupon);
    });
    const range = el.querySelector('#points-range');
    range?.addEventListener('input', () => {
      state.usePointsFen = Number(range.value);
      el.querySelector('#points-fen').textContent = state.usePointsFen ? `-¥${fen2yuan(state.usePointsFen)}` : '';
    });
    let t;
    range?.addEventListener('change', () => { clearTimeout(t); t = setTimeout(recalc, 100); });
    el.querySelector('#ins').onchange = async (e) => {
      state.insurance = e.target.checked;
      // 运费险保费由后端下单时计算，试算不体现；保留选择传给下单
    };
    el.querySelector('#remark').oninput = (e) => { state.remark = e.target.value; };
    el.querySelector('#submit-order').onclick = submit;
  }

  async function submit() {
    if (!address) { toastError('请先选择收货地址'); go('/me/addresses/edit?from=checkout'); return; }
    const btn = el.querySelector('#submit-order');
    btn.disabled = true; btn.textContent = '提交中…';
    try {
      const orderNo = await apiOrder.create({
        clientToken: uuid(),
        orderType: ctx.orderType || 1,
        source: 2,
        items: ctx.items,
        addressId: address.id,
        usePointsFen: state.usePointsFen || 0,
        categoryCouponId: state.categoryCouponId || undefined,
        shopCouponId: state.shopCouponId || undefined,
        platformCouponId: state.platformCouponId || undefined,
        fromCartIds: ctx.fromCartIds || undefined,
        buyFreightInsurance: state.insurance,
        remark: state.remark || undefined,
      });
      sessionStorage.removeItem('shop_checkout_ctx');
      go('/pay/' + orderNo);
    } catch (e) {
      toastError(e.message);
      btn.disabled = false; btn.textContent = '提交订单';
    }
  }

  fullPage();
  await recalc();
}
