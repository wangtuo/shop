// 购物车角标计数：GET /api/order/cart → CartViewVO.totalCount
import { get } from './http.js';

let count = 0;
const listeners = new Set();

export function getCartCount() { return count; }
export function setCartCount(n) {
  count = Math.max(0, Number(n) || 0);
  listeners.forEach((fn) => fn(count));
}
export function onChange(fn) { listeners.add(fn); return () => listeners.delete(fn); }

let inflight = null;
export function refreshCartCount() {
  if (inflight) return inflight;
  inflight = (async () => {
    try {
      const data = await get('/api/order/cart').catch(() => null);
      if (data && data.totalCount !== undefined) setCartCount(data.totalCount);
    } finally { inflight = null; }
  })();
  return inflight;
}
