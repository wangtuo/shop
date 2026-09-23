// API 聚合层：路径以控制器源码（非历史文档）为准
import { get, post, put, del } from './lib/http.js';

// ================= 用户 =================
export const apiUser = {
  register: (b) => post('/api/user/auth/register', b),
  login: (account, password) => post('/api/user/auth/login', { account, password }),
  me: () => get('/api/user/users/me'),
  level: () => get('/api/user/users/level'),
  points: () => get('/api/user/users/points').catch(() => null),
  pointsFlows: (p = 1, size = 20) => get(`/api/user/users/points/flows?pageNum=${p}&pageSize=${size}`).catch(() => null),
  balanceFlows: (accountType = 1, p = 1, size = 20) =>
    get(`/api/user/users/balance/flows?accountType=${accountType}&pageNum=${p}&pageSize=${size}`).catch(() => null),
  signIn: () => post('/api/user/users/sign-in'),

  addressList: (p = 1, size = 20) => get(`/api/user/users/addresses?pageNum=${p}&pageSize=${size}`),
  addressGet: (id) => get(`/api/user/users/addresses/${id}`),
  addressCreate: (b) => post('/api/user/users/addresses', b),
  addressUpdate: (id, b) => put(`/api/user/users/addresses/${id}`, b),
  addressDelete: (id) => del(`/api/user/users/addresses/${id}`),
};

// ================= 商品 =================
export const apiProduct = {
  browse: ({ page = 1, size = 20, keyword, category3Id } = {}) => {
    const q = new URLSearchParams({ pageNum: page, pageSize: size });
    if (keyword) q.set('keyword', keyword);
    if (category3Id) q.set('category3Id', category3Id);
    return get(`/api/product/products?${q}`);
  },
  detail: (spuId) => get(`/api/product/products/${spuId}`),
  skus: (spuId) => get(`/api/product/products/${spuId}/skus`),
  price: (skuId, userLevel = 0) => get(`/api/product/products/skus/${skuId}/price?userLevel=${userLevel}`),
  categoryTree: () => get('/api/product/categories/tree'),
  comments: (spuId, page = 1, size = 10) =>
    get(`/api/product/comments/products/${spuId}?pageNum=${page}&pageSize=${size}`),
  commentCreate: (b) => post('/api/product/comments', b),
};

// ================= 营销 =================
export const apiMarketing = {
  couponCenter: () => get('/api/marketing/coupons/center').catch(() => []),
  couponClaim: (couponId, requestNo) => post('/api/marketing/coupons/claim', { couponId, requestNo: requestNo || uuid() }),
  couponMy: (status) =>
    get(`/api/marketing/coupons/my${status === undefined || status === '' ? '' : `?status=${status}`}`).catch(() => []),
  // 六类促销叠算（下单预览）
  calculate: (cmd) => post('/api/marketing/h5/marketing/calculate', cmd),
};

// ================= 订单 / 购物车 =================
export const apiOrder = {
  cart: () => get('/api/order/cart'),
  cartAdd: (skuId, qty = 1) => post('/api/order/cart', { skuId, qty }),
  cartUpdate: (cartId, qty) => put(`/api/order/cart/${cartId}`, { qty }),
  cartRemove: (cartId) => del(`/api/order/cart/${cartId}`),
  cartSelect: (ids, selected, shopId) => put('/api/order/cart/select', { ids, selected, shopId }),
  cartSelectAll: (selected, shopId) =>
    put(`/api/order/cart/select-all?selected=${selected}${shopId ? `&shopId=${shopId}` : ''}`),
  cartInvert: () => put('/api/order/cart/invert'),
  cartClearInvalid: () => del('/api/order/cart/invalid'),

  create: (b) => post('/api/order/orders', b),
  list: (status, page = 1, size = 10) => {
    const q = new URLSearchParams({ pageNum: page, pageSize: size });
    if (status !== undefined && status !== '' && status !== null) q.set('status', status);
    return get(`/api/order/orders?${q}`);
  },
  detail: (orderNo) => get(`/api/order/orders/${orderNo}`),
  cancel: (orderNo) => post(`/api/order/orders/${orderNo}/cancel`),
  confirm: (orderNo) => post(`/api/order/orders/${orderNo}/confirm`),
  rebuy: (orderNo) => post(`/api/order/orders/${orderNo}/rebuy`),
  remove: (orderNo) => del(`/api/order/orders/${orderNo}`),
  remind: (orderNo) => post(`/api/order/orders/${orderNo}/remind`),
  updateAddress: (orderNo, addressId) => put(`/api/order/orders/${orderNo}/address`, { addressId }),
  invoiceUpdate: (orderNo, b) => put(`/api/order/orders/${orderNo}/invoice`, b),
  invoice: (orderNo) => get(`/api/order/orders/${orderNo}/invoice`),
};

// ================= 支付 =================
// payMethod: 1 微信 2 支付宝 3 余额 4 银行卡 5 云闪付 6 花呗分期 7 白条
export const PAY_METHODS = [
  { code: 1, name: '微信支付', icon: '💚', hint: '推荐' },
  { code: 2, name: '支付宝', icon: '🅰️' },
  { code: 3, name: '余额支付', icon: '💰' },
  { code: 4, name: '银行卡', icon: '💳' },
  { code: 5, name: '云闪付', icon: '🏦' },
  { code: 6, name: '花呗分期', icon: '🌸' },
  { code: 7, name: '白条', icon: '📄' },
];
export const apiPay = {
  create: ({ orderNo, payMethod, amountFen, subject, terminal = 2 }) =>
    post('/api/pay/pays', { orderNo, payMethod, amountFen, subject, terminal }),
  getByPayNo: (payNo) => get(`/api/pay/pays/${payNo}`),
  getByOrder: (orderNo) => get(`/api/pay/pays/order/${orderNo}`),
  // mock 渠道异步回调（本地环境用内置开发密钥签名；prod 无此路径场景）
  notifyPay: (channel, body) => post(`/api/pay/notify/pay/${channel}`, body),
};

export const MOCK_CHANNEL = {
  1: { code: 'MOCK_WECHAT', secret: 'mock_wechat_secret_2026', name: '微信支付' },
  2: { code: 'MOCK_ALIPAY', secret: 'mock_alipay_secret_2026', name: '支付宝' },
  4: { code: 'MOCK_BANK', secret: 'mock_bank_secret_2026', name: '银行卡' },
  5: { code: 'MOCK_UQR', secret: 'mock_uqr_secret_2026', name: '云闪付' },
  6: { code: 'MOCK_HUABEI', secret: 'mock_huabei_secret_2026', name: '花呗分期' },
  7: { code: 'MOCK_BAITIAO', secret: 'mock_baitiao_secret_2026', name: '白条' },
};

// ================= 售后 =================
export const apiAftersale = {
  apply: (b) => post('/api/aftersale/aftersales', b),
  page: ({ orderNo, type, status, page = 1, size = 10 } = {}) => {
    const q = new URLSearchParams({ pageNum: page, pageSize: size });
    if (orderNo) q.set('orderNo', orderNo);
    if (type !== undefined && type !== '') q.set('type', type);
    if (status !== undefined && status !== '') q.set('status', status);
    return get(`/api/aftersale/aftersales/page?${q}`);
  },
  detail: (no) => get(`/api/aftersale/aftersales/${no}`),
  cancel: (no) => post(`/api/aftersale/aftersales/${no}/cancel`),
  resubmit: (no, b) => post(`/api/aftersale/aftersales/${no}/resubmit`, b),
  shipReturn: (no, b) => post(`/api/aftersale/aftersales/${no}/return-logistics`, b),
  exchangeConfirm: (no) => post(`/api/aftersale/aftersales/${no}/exchange-confirm`),
  intervene: (no) => post(`/api/aftersale/aftersales/${no}/intervene`),
  evidence: (no, b) => post(`/api/aftersale/aftersales/${no}/evidence`, b),
  priceProtectTrial: (b) => post('/api/aftersale/aftersales/price-protect/trial', b),
};

// ================= 常量 =================
export const ORDER_STATUS = {
  10: { label: '待付款', tone: 'tag-red' },
  20: { label: '待发货', tone: 'tag-gold' },
  30: { label: '待收货', tone: 'tag-blue' },
  40: { label: '已完成', tone: 'tag-green' },
  50: { label: '已取消', tone: 'tag-gray' },
  60: { label: '退款中', tone: 'tag-gold' },
  61: { label: '退货退款中', tone: 'tag-gold' },
  62: { label: '换货中', tone: 'tag-gold' },
  70: { label: '已关闭', tone: 'tag-gray' },
};

export const AFTERSALE_TYPE = {
  1: { label: '仅退款', icon: '💸' },
  2: { label: '退货退款', icon: '📦' },
  3: { label: '换货', icon: '🔄' },
  4: { label: '补发', icon: '📮' },
  5: { label: '价保', icon: '🛡️' },
};

export function uuid() {
  return (crypto.randomUUID?.() || `r-${Date.now()}-${Math.random().toString(16).slice(2)}`);
}
