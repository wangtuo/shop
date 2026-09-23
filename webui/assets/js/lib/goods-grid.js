// 商品瀑布网格：SPU 列表不带价，按卡片懒加载详情最低价（详情走 Redis 版本缓存）
import { apiProduct } from '../api.js';
import { fen2yuan, escapeHtml } from './ui.js';

const priceCache = new Map(); // spuId -> {min:fen, market:fen}
const inflight = new Set();
let queue = [];
let runner = null;

function enqueue(spuId, cb) {
  if (priceCache.has(spuId)) { cb(priceCache.get(spuId)); return; }
  queue.push({ spuId, cb });
  pump();
}

async function pump() {
  if (runner) return;
  runner = (async () => {
    while (queue.length) {
      const batch = queue.splice(0, 4);
      await Promise.allSettled(batch.map(async ({ spuId, cb }) => {
        if (inflight.has(spuId)) { queue.push({ spuId, cb }); return; }
        inflight.add(spuId);
        try {
          const d = await apiProduct.detail(spuId);
          const skus = d?.skus || [];
          const prices = skus.map((s) => s.promotionPriceFen ?? s.seckillPriceFen ?? s.salePriceFen).filter((v) => v != null);
          const markets = skus.map((s) => s.marketPriceFen).filter((v) => v != null);
          const snap = {
            min: prices.length ? Math.min(...prices) : null,
            market: markets.length ? Math.min(...markets) : null,
          };
          priceCache.set(spuId, snap);
          cb(snap);
        } catch (_) {
          cb({ min: null, market: null });
        } finally { inflight.delete(spuId); }
      }));
    }
    runner = null;
  })();
}

const FALLBACK_THUMBS = ['🎁', '🛍️', '👜', '👟', '🎧', '⌚', '🧴', '🍰'];
function thumb(spu) {
  const img = spu.mainImage && !/example\.com/.test(spu.mainImage) ? spu.mainImage : '';
  const ico = FALLBACK_THUMBS[Number(spu.spuId) % FALLBACK_THUMBS.length];
  return img
    ? `<img src="${escapeHtml(img)}" alt="" onerror="this.replaceWith(Object.assign(document.createElement('span'),{textContent:'${ico}'}))" />`
    : ico;
}

function statusTag(spu) {
  // status: 3=在售（常见约定）；售罄由库存为 0 判定在价格快照里做不了，这里只展示销量
  return '';
}

export function goodsGrid(list) {
  return `<div class="goods-grid">${list.map((spu) => `
    <a class="goods-card" href="#/product/${spu.spuId}" data-spu="${spu.spuId}">
      <div class="goods-thumb">${thumb(spu)}</div>
      <div class="goods-body">
        <div class="goods-name ellipsis-2">${escapeHtml(spu.name)}</div>
        <div class="row-between mt-8">
          <span class="price js-price" data-spu="${spu.spuId}"></span>
          <span class="sales">${spu.sales ? `已售${spu.sales}` : ''}</span>
        </div>
      </div>
    </a>`).join('')}</div>`;
}

export function hydratePrices(scope = document) {
  scope.querySelectorAll('.js-price').forEach((el) => {
    const spuId = el.dataset.spu;
    enqueue(spuId, (snap) => {
      if (el.dataset.done) return;
      el.dataset.done = '1';
      el.innerHTML = snap.min != null
        ? fen2yuan(snap.min) + (snap.market && snap.market > snap.min ? ` <span class="price origin tiny">${fen2yuan(snap.market)}</span>` : '')
        : '';
    });
  });
}

export function clearPriceCache() { priceCache.clear(); }
