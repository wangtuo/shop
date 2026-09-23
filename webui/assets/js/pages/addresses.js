// 收货地址列表 + 新增/编辑
import { shell } from '../lib/shell.js';
import { apiUser } from '../api.js';
import { escapeHtml, confirm as uiConfirm, toastSuccess, toastError } from '../lib/ui.js';
import { go } from '../lib/router.js';

export async function renderAddresses({ el, query }) {
  el.innerHTML = shell({
    title: '收货地址',
    showBack: true,
    headerRight: `<a class="btn btn-sm btn-primary" href="#/me/addresses/edit${query.from ? `?from=${query.from}${query.n ? '&n=' + query.n : ''}` : ''}">＋ 新增地址</a>`,
    content: '<div class="loading-more mt-20">加载中…</div>',
  });

  async function draw() {
    const r = await apiUser.addressList(1, 50).catch(() => ({ list: [] }));
    const list = r.list || [];
    const newHref = `#/me/addresses/edit${query.from ? `?from=${query.from}${query.n ? '&n=' + query.n : ''}` : ''}`;
    el.querySelector('.site-content').innerHTML = `
      <div class="page-bar">
        <button class="pb-back" id="hd-back" type="button">‹ 返回</button>
        <h1>收货地址</h1><span class="flex1"></span>
        <a class="btn btn-sm btn-primary" href="${newHref}">＋ 新增地址</a>
      </div>
      <div id="addr-list">${list.map((a) => `
      <div class="card section-pad" style="margin-bottom:10px">
        <div class="row-between">
          <b>${escapeHtml(a.receiver)} <span class="muted tiny" style="font-weight:400">${escapeHtml(a.phone)}</span></b>
          <span class="row gap-6">
            ${a.tag ? `<span class="tag tag-blue tiny">${escapeHtml(a.tag)}</span>` : ''}
            ${a.isDefault === 1 ? '<span class="tag tag-red tiny">默认</span>' : ''}
          </span>
        </div>
        <div class="tiny muted mt-8">${escapeHtml([a.province, a.city, a.district, a.detailAddress].filter(Boolean).join(' '))}</div>
        <div class="row-between mt-12">
          <span class="tiny" style="color:${a.isDefault === 1 ? 'var(--text-4)' : 'var(--primary)'};cursor:pointer" data-def="${a.id}">${a.isDefault === 1 ? '' : '设为默认'}</span>
          <span class="row gap-12 tiny">
            <a href="#/me/addresses/edit/${a.id}" style="color:var(--text-2)">编辑</a>
            <span style="color:var(--danger);cursor:pointer" data-del="${a.id}">删除</span>
          </span>
        </div>
      </div>`).join('') || '<div class="empty"><span class="ico">📍</span>还没有收货地址<br>点击右上角「新增」添加</div>'}</div>`;

    el.querySelector('.site-content').onclick = async (e) => {
      const del = e.target.closest('[data-del]');
      const def = e.target.closest('[data-def]');
      try {
        if (del) {
          if (!(await uiConfirm({ content: '确定删除该地址？', danger: true, okText: '删除' }))) return;
          await apiUser.addressDelete(del.dataset.del); toastSuccess('已删除'); draw();
        } else if (def && def.dataset.def) {
          const a = list.find((x) => String(x.id) === String(def.dataset.def));
          await apiUser.addressUpdate(a.id, {
            receiver: a.receiver, phone: a.phone, province: a.province, city: a.city,
            district: a.district, detailAddress: a.detailAddress, zipCode: a.zipCode,
            tag: a.tag, isDefault: 1,
          });
          toastSuccess('已设为默认'); draw();
        }
      } catch (err) { toastError(err.message); }
    };
  }
  await draw();
}

export async function renderAddressEdit({ el, params, query }) {
  const id = params.id;
  const editing = !!id;
  let data = { receiver: '', phone: '', province: '', city: '', district: '', detailAddress: '', tag: '家', isDefault: 0 };
  if (editing) {
    try { Object.assign(data, await apiUser.addressGet(id)); }
    catch (e) { toastError(e.message); }
  }

  el.innerHTML = shell({
    title: editing ? '编辑地址' : '新增地址',
    showBack: true,
    content: `
    <form id="addr-form" class="card section-pad" style="max-width:720px;margin-top:12px">
      <div class="form-item"><label>收货人</label><input class="input" name="receiver" maxlength="64" value="${escapeHtml(data.receiver)}" required></div>
      <div class="form-item"><label>手机号</label><input class="input" name="phone" inputmode="numeric" maxlength="11" value="${escapeHtml(data.phone || '')}" required></div>
      <div class="form-item"><label>所在地区</label>
        <div class="row gap-8">
          <input class="input" name="province" placeholder="省" value="${escapeHtml(data.province || '')}" required style="flex:1">
          <input class="input" name="city" placeholder="市" value="${escapeHtml(data.city || '')}" required style="flex:1">
          <input class="input" name="district" placeholder="区/县" value="${escapeHtml(data.district || '')}" style="flex:1">
        </div>
      </div>
      <div class="form-item"><label>详细地址</label>
        <textarea class="textarea" name="detailAddress" maxlength="256" placeholder="街道、楼栋、门牌号" required>${escapeHtml(data.detailAddress || '')}</textarea></div>
      <div class="form-item"><label>地址标签</label>
        <div class="row gap-8" id="tag-row">
          ${['家', '公司', '学校', '其他'].map((t) => `<span class="sku-opt ${data.tag === t ? 'selected' : ''}" data-tag="${t}">${t}</span>`).join('')}
        </div>
      </div>
      <label class="row gap-8" style="margin:16px 0"><input type="checkbox" name="isDefault" ${data.isDefault === 1 ? 'checked' : ''}> 设为默认地址</label>
      <div class="field-error" id="addr-err"></div>
      <button class="btn btn-primary btn-block btn-lg" type="submit">保存地址</button>
    </form>`,
  });

  el.querySelector('#tag-row').onclick = (e) => {
    const t = e.target.closest('[data-tag]');
    if (!t) return;
    data.tag = t.dataset.tag;
    el.querySelectorAll('#tag-row .sku-opt').forEach((x) => x.classList.toggle('selected', x === t));
  };

  el.querySelector('#addr-form').onsubmit = async (e) => {
    e.preventDefault();
    const fd = new FormData(e.target);
    const v = Object.fromEntries(fd.entries());
    const errEl = el.querySelector('#addr-err');
    if (!/^1[3-9]\d{9}$/.test(v.phone)) { errEl.textContent = '手机号格式不正确'; return; }
    const body = {
      receiver: v.receiver.trim(), phone: v.phone.trim(),
      province: v.province.trim(), city: v.city.trim(), district: v.district.trim(),
      detailAddress: v.detailAddress.trim(), tag: data.tag,
      isDefault: fd.get('isDefault') ? 1 : 0,
    };
    try {
      if (editing) await apiUser.addressUpdate(id, body);
      else await apiUser.addressCreate(body);
      toastSuccess('地址已保存');
      go(query.from ? (query.n ? `/orders/${query.n}` : '/checkout') : '/me/addresses');
    } catch (err) { errEl.textContent = escapeHtml(err.message); }
  };
}
