// 下单链路压测脚本（k6，Docker 运行，无需本地安装）。
//
// 前置：先执行 deploy/loadtest/seed.sh 灌入用户池与已上架压测商品。
//
// 容量模式（design 10.1，目标下单 ≥ 5000 TPS、p95 < 500ms、错误率 < 0.1%）：
//   docker run --rm --network host -i grafana/k6 run - \
//     -e MODE=capacity -e USER_POOL=10000 -e SKU_COUNT=1000 < deploy/loadtest/k6-order.js
//   单节点 kind 等资源受限环境用 CAP_START_RATE/CAP_PEAK_RATE/CAP_RAMP1_MIN/
//   CAP_RAMP2_MIN/CAP_HOLD_MIN/CAP_MAX_VUS 压有界曲线（默认值即上方生产档案，不变）。
//
// 冒烟模式（本机一把，60s 低速率）：
//   docker run --rm --network host -i grafana/k6 run -e MODE=smoke - < deploy/loadtest/k6-order.js
//
// 防超卖模式（N=RUSH_MULT×库存 并发抢同一秒杀 SKU；成交=支付必须恰好 = 库存，输家恰好 = 库存）：
//   前置 1：用户池由 seed.sh 灌入，USER_POOL 必须 >= RUSH_MULT*STOCK（默认 2*STOCK=40）：
//     USER_POOL=100 bash deploy/loadtest/seed.sh
//   前置 2：建超卖验证商品 + 秒杀活动（stdout 只有两行 eval，日志在 stderr，并落盘 /tmp/shop-seed/oversell.env）：
//     source <(STOCK=20 bash deploy/loadtest/seed-oversell.sh)
//   运行（CHANNEL_SECRET 为本机 mock 渠道默认密钥，服务端非默认时必须显式覆盖）：
//     docker run --rm --network host -i grafana/k6 run - \
//       -e MODE=oversell \
//       -e OVERSELL_SKU_ID=$OVERSELL_SKU_ID \
//       -e OVERSELL_ACTIVITY_ID=$OVERSELL_ACTIVITY_ID \
//       -e STOCK=20 -e USER_POOL=100 -e RUSH_MULT=2 \
//       -e CHANNEL_SECRET=mock_wechat_secret_2026 \
//       < deploy/loadtest/k6-order.js
//   退出码非 0（99）即超卖/漏卖/支付未闭环：阈值硬断言 attempts>=2*STOCK、
//   winners===losers===paidConfirmed===STOCK；handleSummary 另输出可读对账明细。
//
// 说明：地址由 VU 首次使用某用户时自助新增（幂等，每用户最多 20 条，压测轮询足够）。
// 签名：mock 渠道回调 HMAC-SHA256，规则与 shop-e2e World.signNotify 完全一致
//       （字段按字典序 amountFen,channelCode,channelTxnNo,notifyId,payNo,status 拼接 k=v&）。

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import exec from 'k6/execution';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
// HMAC-SHA256 回调签名依赖 WebCrypto。grafana/k6 v2（及 >= v0.58）已把 WebCrypto 毕业为
// 标准全局 crypto.subtle，旧的 `import { crypto } from 'k6/experimental/webcrypto'` 在 v2 会
// 直接报错（模块已移除），因此这里不写 import、直接使用全局 crypto。
// 若在很老的镜像上运行（无全局 crypto），请升级 grafana/k6:latest。

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const MODE = __ENV.MODE || 'smoke';
const USER_POOL = parseInt(__ENV.USER_POOL || '100');
const SKU_COUNT = parseInt(__ENV.SKU_COUNT || '100');
const KEYWORD = __ENV.SEED_KEYWORD || '压测商品';
const RUSH_MULT = parseInt(__ENV.RUSH_MULT || '2');
const CHANNEL_SECRET = __ENV.CHANNEL_SECRET || 'mock_wechat_secret_2026';
const CHANNEL_CODE = 'MOCK_WECHAT';
// 订单状态机：10 待付款 --支付成功--> 20 待发货（已支付）--> 30 待收货。
// 支付闭环以 status=20 为准（无需商户发货）。
const ORDER_STATUS_PAID = 20;

const orderCreateLatency = new Trend('order_create_latency_ms', true);
const calcLatency = new Trend('promotion_calc_latency_ms', true);
const successRate = new Rate('order_success_rate');
const soldOut = new Counter('stock_not_enough_count');
const orderCreated = new Counter('order_created_total');

// 超卖模式专用指标
const oversellWinners = new Counter('oversell_winners');
const oversellLosers = new Counter('oversell_losers');
const oversellPaidConfirmed = new Counter('oversell_paid_confirmed');
const oversellAttempts = new Counter('oversell_order_attempts');
const oversellExpected = new Rate('oversell_expected_outcome');
// 语义：每次迭代“没有系统错误”记 true；出现非预期业务码/非 200/支付失败记 false
const oversellSystemOk = new Rate('oversell_system_error_rate');

// VU 内 token 缓存：登录接口有 20 次/60s/IP 的防暴力破解限流（压测环境通过
// SHOP_RATELIMIT_AUTH_LOGIN_PERMITS 放宽），真实客户端也是一次登录长期复用，
// 压测不应每迭代登录。k6 每个 VU 独立 JS 运行时，模块级 Map 天然按 VU 隔离。
const tokenCache = new Map();

function loginToken(uid) {
  const cached = tokenCache.get(uid);
  if (cached) return cached;
  // R4-21：启动瞬间数百 VU 首次迭代同时登录（BCrypt 验密在过载窗可能 504），
  // 一次失败若不缓存就会让该 VU 之后每次迭代都重新验密——中毒 VU 会把登录风暴
  // 拖进整个测量窗。这里按 0.2/0.8/2/2s 退避在迭代内最多重试 4 次：真实客户端遇
  // 网关超时也会重试，成功即永久缓存；全败才判本次迭代失败（下次迭代可再登录，
  // 不留永久负缓存）。429/401（限流/密码错）不重试，避免对限流接口加压。
  const backoffs = [0, 0.2, 0.8, 2];
  let last = null;
  for (const wait of backoffs) {
    if (wait > 0) sleep(wait);
    const res = http.post(`${BASE_URL}/api/user/auth/login`,
      JSON.stringify({ account: `load_${uid}`, password: 'Load@12345' }),
      { headers: { 'Content-Type': 'application/json' } });
    const token = res.json('data.token');
    if (token) {
      tokenCache.set(uid, token);
      return token;
    }
    last = res;
    if (res.status === 401 || res.status === 429) break;
  }
  // 不静默：无 token 会在下游表现为一堆 401，根因（10007 限流/密码错/504）直接打出
  console.error(`login failed load_${uid} (retries exhausted): HTTP ${last.status} ${(last.body || '').slice(0, 160)}`);
  return null;
}

function ensureAddress(token) {
  const body = {
    receiver: '压测买家',
    phone: '13800000001',
    province: '江苏省',
    city: '南京市',
    district: '玄武区',
    detailAddress: '中山路 1 号压测仓',
    tag: '公司',
    isDefault: 1,
  };
  const res = http.post(`${BASE_URL}/api/user/users/addresses`, JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
  });
  return firstLong(res.body, 'data');
}

// 超卖用户每次迭代唯一：先尝试新建地址；若已达 20 条上限（重复跑压测）则回退取分页首条
function ensureAddressId(token) {
  let addressId = ensureAddress(token);
  if (addressId) {
    return addressId;
  }
  const res = http.get(`${BASE_URL}/api/user/users/addresses?pageNum=1&pageSize=1`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  return firstLong(res.body, 'id');
}

// 雪花 ID 安全读取：后端 Long（15+ 位数字）超过 JS Number.MAX_SAFE_INTEGER，
// res.json() 会把 2100191509982244866 圆整成 2100191509982244864 之类，随后按错 ID
// 查询必返回「商品不存在」（实测踩坑）。所有实体 ID 一律从原始 body 文本提取为字符串，
// 以字符串回传 Jackson 可自动绑定 Long。
function firstLong(text, key) {
  const m = new RegExp(`"${key}"\\s*:\\s*"?(\\d{15,})"?`).exec(text || '');
  return m ? m[1] : null;
}

// 从 {"<arrayKey>":[{...},{...}]} 中切出顶层对象文本（SPU/SKU 对象内无嵌套对象，
// 但扫描器仍做字符串感知与花括号深度计数，通用可靠）。
function arrayObjectBodies(body, arrayKey) {
  const marker = body.indexOf(`"${arrayKey}"`);
  if (marker < 0) return [];
  const lb = body.indexOf('[', marker);
  if (lb < 0) return [];
  const out = [];
  let depth = 0, inStr = false, esc = false, objStart = -1;
  for (let i = lb; i < body.length; i++) {
    const ch = body[i];
    if (inStr) {
      if (esc) esc = false;
      else if (ch === '\\') esc = true;
      else if (ch === '"') inStr = false;
      continue;
    }
    if (ch === '"') { inStr = true; continue; }
    if (ch === '{') { if (depth === 0) objStart = i; depth++; }
    else if (ch === '}') {
      depth--;
      if (depth === 0 && objStart >= 0) { out.push(body.slice(objStart, i + 1)); objStart = -1; }
    } else if (ch === ']' && depth === 0) break;
  }
  return out;
}

function fetchOnSaleSkus() {  // 公开浏览接口：按关键词取上架 SPU，再逐 SPU 取首个可售 SKU
  const list = http.get(`${BASE_URL}/api/product/products?keyword=${encodeURIComponent(KEYWORD)}`
    + `&pageNum=1&pageSize=${Math.min(SKU_COUNT, 200)}`);
  const skus = [];
  for (const spu of arrayObjectBodies(list.body, 'list')) {
    if (skus.length >= SKU_COUNT) break;
    const spuId = firstLong(spu, 'spuId');
    if (!spuId) continue;
    const r = http.get(`${BASE_URL}/api/product/products/${spuId}/skus`);
    const skuId = firstLong(r.body, 'skuId');
    if (!skuId) continue; // 含售罄/下架（无有效 SKU）
    skus.push({
      skuId,
      spuId,
      merchantId: firstLong(spu, 'merchantId') || '',
      shopId: firstLong(spu, 'shopId') || '',
      category3Id: firstLong(spu, 'category3Id') || '',
    });
  }
  return skus;
}

// 5xx/网关错误时响应可能不是 JSON，所有取值走安全路径
function jsonPath(res, path) {
  try {
    return res.json(path);
  } catch (e) {
    return null;
  }
}

async function hmacSha256Hex(secret, message) {
  const key = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  const sig = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(message));
  const bytes = new Uint8Array(sig);
  let hex = '';
  for (let i = 0; i < bytes.length; i++) {
    hex += bytes[i].toString(16).padStart(2, '0');
  }
  return hex;
}

export function setup() {
  const probeUid = parseInt(__ENV.SEED_UID || '1');
  const token = loginToken(probeUid);
  check(token, { 'seed user login ok': (t) => !!t });

  let skus;
  if (MODE === 'oversell') {
    // 雪花 ID 必须保持字符串（见 firstLong 注释），禁止 parseInt
    const skuId = __ENV.OVERSELL_SKU_ID || '';
    const activityId = __ENV.OVERSELL_ACTIVITY_ID || '';
    const stock = parseInt(__ENV.STOCK || '0');
    // fail fast：三者缺一不可，且用户池必须覆盖迭代数（秒杀按用户去重）
    if (!/^\d{15,}$/.test(skuId) || !/^\d{15,}$/.test(activityId) || !(stock > 0)) {
      throw new Error('oversell 模式必须提供 OVERSELL_SKU_ID、OVERSELL_ACTIVITY_ID、STOCK（ID 为 15+ 位数字串）；'
        + '请先 `source <(bash deploy/loadtest/seed-oversell.sh)`');
    }
    const iterations = stock * RUSH_MULT;
    if (iterations > USER_POOL) {
      throw new Error(`秒杀按用户去重：USER_POOL=${USER_POOL} 必须 >= stock*RUSH_MULT=${iterations}，`
        + '请先用 USER_POOL>=该值 执行 deploy/loadtest/seed.sh');
    }
    skus = [{ skuId }];
    return { skus, activityId, stock, iterations };
  }
  skus = fetchOnSaleSkus();
  check(skus, {
    'discovered on-sale seed SKUs': (s) => Array.isArray(s) && s.length > 0,
    'SKU coverage >= 80% requested': (s) => s.length >= Math.min(SKU_COUNT, 200) * 0.8,
  });
  const data = { skus };
  if (MODE === 'smoke') {
    // R4-21：压测开始前以【受控节奏】（每批 8 个、批间 300ms）为用户池预登录并预建
    // 收货地址，模拟「用户池已登录、随时可下单」的真实起点。若不预登录，constant-arrival
    // 启动瞬间所有 preAllocatedVUs 的首个迭代齐射 BCrypt 验密（CPU 密集），单节点
    // kind 的 user-service 2 核 CFS 被打满→504→重试→迭代变慢→k6 扩 VU→更多首登的
    // 正反馈雪崩（W7 实证 CB OPEN 573 次、p95 6.5s）。VU 数超过预登录池时才回退
    // loginToken（带退避重试），使恒速测量窗内登录调用趋近于零。
    const pool = Math.max(0, Math.min(USER_POOL,
      parseInt(__ENV.SMOKE_PRELOGIN_USERS || '160')));
    data.preTokens = new Array(pool).fill(null);
    data.preAddresses = new Array(pool).fill(null);
    const batchSize = 8;
    let okLogins = 0;
    let okAddr = 0;
    for (let start = 1; start <= pool; start += batchSize) {
      const end = Math.min(start + batchSize, pool + 1);
      // 第一批先与服务端热连接；每批 8 个请求并发（≈27 次/s 登录，远低于 CFS 拐点）
      const loginReqs = [];
      for (let uid = start; uid < end; uid++) {
        loginReqs.push(['POST', `${BASE_URL}/api/user/auth/login`,
          JSON.stringify({ account: `load_${uid}`, password: 'Load@12345' }),
          { headers: { 'Content-Type': 'application/json' } }]);
      }
      let loginResps = http.batch(loginReqs);
      // 整批失败（如启动瞬态）退避 1s 补一轮，不无限重试
      if (loginResps.some((r) => !r.json('data.token'))) {
        sleep(1);
        const retry = [];
        for (let uid = start; uid < end; uid++) {
          if (!loginResps[uid - start].json('data.token')) {
            retry.push(['POST', `${BASE_URL}/api/user/auth/login`,
              JSON.stringify({ account: `load_${uid}`, password: 'Load@12345' }),
              { headers: { 'Content-Type': 'application/json' } }]);
          }
        }
        if (retry.length) {
          const rr = http.batch(retry);
          let ri = 0;
          loginResps = loginResps.map((r, idx) =>
            r.json('data.token') ? r : retry[ri++] ? rr[ri - 1] : r);
        }
      }
      const addrReqs = [];
      const addrIndex = [];
      for (let uid = start; uid < end; uid++) {
        const t = loginResps[uid - start].json('data.token');
        if (t) {
          data.preTokens[uid - 1] = t;
          okLogins++;
          addrReqs.push(['POST', `${BASE_URL}/api/user/users/addresses`,
            JSON.stringify({
              receiver: '压测买家', phone: '13800000001',
              province: '江苏省', city: '南京市', district: '玄武区',
              detailAddress: `预热地址 ${uid}`, tag: '公司', isDefault: 0,
            }),
            { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${t}` } }]);
          addrIndex.push(uid - 1);
        }
      }
      if (addrReqs.length) {
        const addrResps = http.batch(addrReqs);
        addrResps.forEach((r, i) => {
          // 雪花 ID 必须从原始 body 文本提取（见 firstLong 注释）；已达 20 条上限时
          // data 为空，VU 运行时 ensureAddressId 会取地址列表首条兜底。
          const id = firstLong(r.body, 'data');
          if (r.status === 200 && id) {
            data.preAddresses[addrIndex[i]] = id;
            okAddr++;
          }
        });
      }
      sleep(0.3);
    }
    check(null, {
      [`pre-warm logins >=90% pool (${okLogins}/${pool})`]: () => okLogins >= pool * 0.9,
    });
    console.log(`R4-21 pre-warm: tokens=${okLogins}/${pool} addresses=${okAddr}`);
  }
  return data;
}

// R4-21：把 k6 时长字符串（90s/3m）解析为秒，供单场景预热/测量分段使用。
function parseDurationSeconds(v, fallback) {
  const m = /^(\d+)(s|m)$/.exec(String(v).trim());
  if (!m) return fallback;
  return parseInt(m[1], 10) * (m[2] === 'm' ? 60 : 1);
}

function smokeOptions() {
  // 默认 20 iters/s × 3min 冒烟；可用 SMOKE_RATE / SMOKE_DURATION / SMOKE_PREALLOC_VUS
  // /SMOKE_MAX_VUS 在资源受限环境压「恒定速率可持续吞吐」（不做爬升，不触发放大）。
  // SMOKE_WARMUP（默认 90s）：测量窗前的【不计阈值】预热段，与测量段同处一个场景、
  // 同一批 VU JS 运行时——预热段完成的 JIT/Druid 物理连接/Nacos 路由预热以及
  // 「每 VU 一次登录」拿到的 JWT 都能直接带到测量段（R4-21：旧实现 warmup/smoke 是
  // 两个独立场景，VU 运行时不共享，测量场景的每个 VU 仍要重新登录；且测量段内 uid
  // 按全局迭代号轮换，每次迭代都是新用户→恒定 20 次/s BCrypt 验密把 user-service
  // 打到 2 核 CFS 上限引发 504 雪崩。真实用户持 JWT 连续下单，登录是稀疏行为，
  // 稳态下单 TPS 不应把验密计入——currentUser() 改为 VU 生命周期内固定 uid）。
  // 阈值只统计 {phase:measure} 标签的请求；设 SMOKE_WARMUP=0s 可关闭预热。
  const rate = parseInt(__ENV.SMOKE_RATE || '20');
  const duration = __ENV.SMOKE_DURATION || '3m';
  const warmup = __ENV.SMOKE_WARMUP || '90s';
  const prealloc = parseInt(__ENV.SMOKE_PREALLOC_VUS || '100');
  const maxVus = parseInt(__ENV.SMOKE_MAX_VUS || '300');
  const measureSec = parseDurationSeconds(duration, 180);
  const warmSec = (warmup === '0s' || warmup === '0')
    ? 0 : parseDurationSeconds(warmup, 90);
  return {
    scenarios: {
      smoke: {
        executor: 'constant-arrival-rate',
        rate, timeUnit: '1s', duration: `${warmSec + measureSec}s`,
        preAllocatedVUs: prealloc,
        maxVUs: maxVus,
      },
    },
    thresholds: {
      'order_success_rate{phase:measure}': ['rate>0.99'],
      'order_create_latency_ms{phase:measure}': ['p(95)<800'],
    },
  };
}

function capacityOptions() {
  // 设计 10.1 基线（多节点 perf 集群）：2000→5000 TPS，8000 VU 上限。
  // 单节点 kind（12C/16G 同机跑 16 个 JVM + MySQL/Redis/RMQ）无法验证 5000 TPS，
  // 允许用 CAP_* 环境变量压一条「找拐点」的有界容量曲线（生产档案默认值不变）：
  //   CAP_START_RATE  起始 TPS（默认 200）
  //   CAP_PEAK_RATE   峰值 TPS（默认 5000）
  //   CAP_RAMP1_MIN   第一段爬升分钟数（默认 2）
  //   CAP_RAMP2_MIN   第二段爬升分钟数（默认 3）
  //   CAP_HOLD_MIN    峰值保持分钟数（默认 2）
  //   CAP_MAX_VUS     VU 上限（默认 8000；建议 ≥ 峰值 TPS × 单迭代秒数）
  const startRate = parseInt(__ENV.CAP_START_RATE || '200');
  const peakRate = parseInt(__ENV.CAP_PEAK_RATE || '5000');
  const ramp1 = __ENV.CAP_RAMP1_MIN || '2m';
  const ramp2 = __ENV.CAP_RAMP2_MIN || '3m';
  const hold = __ENV.CAP_HOLD_MIN || '2m';
  const preAlloc = parseInt(__ENV.CAP_PREALLOC_VUS || String(Math.min(2000, Math.max(200, startRate))));
  const maxVus = parseInt(__ENV.CAP_MAX_VUS || '8000');
  return {
    scenarios: {
      peak: {
        executor: 'ramping-arrival-rate',
        startRate: startRate, timeUnit: '1s',
        preAllocatedVUs: preAlloc, maxVUs: maxVus,
        stages: [
          { duration: ramp1, target: Math.round((startRate + peakRate) / 2) },
          { duration: ramp2, target: peakRate },
          { duration: hold, target: peakRate },
          { duration: '1m', target: 0 },
        ],
      },
    },
    thresholds: {
      order_success_rate: ['rate>0.999'],
      order_create_latency_ms: ['p(95)<500', 'p(99)<1000'],
    },
  };
}

function oversellOptions() {
  const stock = parseInt(__ENV.STOCK || '1');
  const iterations = stock * RUSH_MULT;
  return {
    scenarios: {
      rush: {
        // RUSH_MULT×库存 并发一次性涌入（sharedIterations 让总迭代数 = mult*stock，
        // vus 拉满到 min(迭代数,1000) 逼近同时发起；k6 无 barrier，这是官方推荐近似）
        executor: 'shared-iterations',
        iterations,
        vus: Math.min(iterations, 1000),
        maxDuration: '5m',
      },
    },
    thresholds: {
      // 只允许 code=0 / code=30001 两种预期结果，非预期（含 5xx）比例不得超 0.1%
      oversell_expected_outcome: ['rate>=0.999'],
      oversell_system_error_rate: ['rate>0.999'],
      // 防空转：实际下单次数必须打满，避免“零请求绿跑”
      oversell_order_attempts: [`count>=${iterations}`],
      // 精确恒等断言（k6 v2 起 handleSummary throw 不再影响退出码，阈值才是退出码 99 的来源）：
      // 成交=售罄拒绝=支付确认 都必须恰好等于库存，多一个少一个都失败
      oversell_winners: [`count===${stock}`],
      oversell_losers: [`count===${stock}`],
      oversell_paid_confirmed: [`count===${stock}`],
    },
  };
}

export const options = MODE === 'capacity' ? capacityOptions()
  : MODE === 'oversell' ? oversellOptions() : smokeOptions();

const addressCache = {};

function currentUser() {
  // R4-21：每个 VU 代表一个固定的真实用户——uid 在该 VU 生命周期内不变，
  // 首次迭代登录后 tokenCache 全命中（生产语义：用户持 JWT 连续下单，验密是
  // 稀疏行为；旧实现按场景全局迭代号轮换 uid，tokenCache 形同虚设，20 TPS 下
  // user-service 被每秒 20 次 BCrypt 打到 CFS 2 核上限）。恒定到达 20 TPS 时
  // 在飞 VU 约 100-200 个（≤ USER_POOL 即互不撞 uid），单 VU 约 10s 一单，
  // 也不撞「下单 5 次/秒/用户」限流。超卖模式不调用本函数（runOversell 必须按
  // 迭代号轮换用户以验证秒杀按用户去重）。
  const vuId = (exec && exec.vu && typeof exec.vu.idInTest === 'number')
    ? exec.vu.idInTest : __VU;
  return ((vuId - 1) % USER_POOL) + 1;
}

// R4-21：smoke 单场景内的预热/测量分段标签。首个迭代到达时起算预热窗
// （setup 阶段拉 SKU 列表不计在内），窗内 {phase:'warm'}，之后 {phase:'measure'}；
// 阈值只统计 measure。非 smoke 模式返回空标签（指标保持原名聚合）。
let smokeWarmEndMs = 0;
function phaseTags() {
  if (MODE !== 'smoke') {
    return {};
  }
  if (!smokeWarmEndMs) {
    const warmSec = parseDurationSeconds(__ENV.SMOKE_WARMUP || '90s', 90);
    smokeWarmEndMs = Date.now() + warmSec * 1000;
  }
  return { phase: Date.now() < smokeWarmEndMs ? 'warm' : 'measure' };
}

// 超卖单迭代：抢单 → 赢家创建支付 → HMAC 签名 mock 回调 → 轮询订单到已支付(20)
async function runOversell(data) {
  if (typeof crypto === 'undefined' || !crypto.subtle) {
    throw new Error('当前 k6 镜像不支持全局 WebCrypto（crypto.subtle），请升级 grafana/k6:latest');
  }
  // __ITER 是 VU 内计数，shared-iterations 下多数 VU 只跑 1 次会全部撞 0；
  // 用场景级全局迭代号保证每次迭代取到互不相同的用户（秒杀按用户去重）。
  const seq = exec && exec.scenario && typeof exec.scenario.iterationInInstance === 'number'
    ? exec.scenario.iterationInInstance
    : __ITER;
  const uid = (seq % USER_POOL) + 1;

  const token = loginToken(uid);
  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${token}`,
  };
  const addressId = ensureAddressId(token);
  const skuId = data.skus[0].skuId;

  let outcomeDecided = false;
  const mark = (ok) => {
    if (outcomeDecided) return;
    outcomeDecided = true;
    oversellExpected.add(ok);
    oversellSystemOk.add(ok);
  };

  // 1. 秒杀下单
  const res = http.post(`${BASE_URL}/api/order/orders`,
    JSON.stringify({
      clientToken: uuidv4(),
      orderType: 2,
      source: 1,
      seckillActivityId: data.activityId,
      addressId,
      items: [{ skuId, qty: 1 }],
      freightFen: 0,
    }), { headers });

  oversellAttempts.add(1);
  orderCreateLatency.add(res.timings.duration);
  const code = jsonPath(res, 'code');
  check(res, {
    'oversell http 200': (r) => r.status === 200,
    'oversell accepted or stock-out': (r) => r.status === 200 && (code === 0 || code === 30001),
  });

  if (res.status !== 200 || (code !== 0 && code !== 30001)) {
    // 非预期结果必须可见：打出真实 code/message，避免只能看到聚合比例无法归因
    console.error(`unexpected seckill response uid=${uid}: HTTP ${res.status} code=${code} body=${(res.body || '').slice(0, 220)}`);
    mark(false); // 5xx/网关失败/非预期业务码
    return;
  }

  // 2. 输家：库存不足/售罄
  if (code === 30001) {
    oversellLosers.add(1);
    soldOut.add(1);
    mark(true);
    return;
  }

  // 3. 赢家：data 即 orderNo，随后必须全款支付成功并在订单侧确认
  const orderNo = jsonPath(res, 'data');
  if (!orderNo) {
    mark(false);
    return;
  }
  oversellWinners.add(1);
  orderCreated.add(1);

  // 3.1 创建支付单（金额优先取服务端返回，缺失回退秒杀价 9900）
  const payRes = http.post(`${BASE_URL}/api/pay/pays`,
    JSON.stringify({
      orderNo,
      payMethod: 1,
      amountFen: 9900,
      subject: `超卖验证秒杀-${orderNo}`,
      terminal: 1,
    }), { headers });
  const payCode = jsonPath(payRes, 'code');
  const payNo = jsonPath(payRes, 'data.payNo');
  let amountFen = jsonPath(payRes, 'data.amountFen');
  if (!(amountFen > 0)) {
    amountFen = 9900;
  }
  if (payRes.status !== 200 || payCode !== 0 || !payNo) {
    console.error(`pay-create failed orderNo=${orderNo}: HTTP ${payRes.status} code=${payCode} body=${(payRes.body || '').slice(0, 220)}`);
    mark(false);
    return;
  }

  // 3.2 HMAC-SHA256 签名回调（canonical 与 World.signNotify 逐字节一致）
  const notifyId = `NFY${uuidv4().replace(/-/g, '')}`;
  const channelTxnNo = `TXN${uuidv4().replace(/-/g, '')}`;
  const canonical = `amountFen=${amountFen}`
    + `&channelCode=${CHANNEL_CODE}`
    + `&channelTxnNo=${channelTxnNo}`
    + `&notifyId=${notifyId}`
    + `&payNo=${payNo}`
    + `&status=SUCCESS`;
  const sign = await hmacSha256Hex(CHANNEL_SECRET, canonical);

  // 回调不是白名单路径，必须携带用户 JWT
  const cb = http.post(`${BASE_URL}/api/pay/notify/pay/${CHANNEL_CODE}`,
    JSON.stringify({
      notifyId,
      payNo,
      channelTxnNo,
      amountFen,
      status: 'SUCCESS',
      sign,
    }), { headers });
  if (cb.status !== 200 || jsonPath(cb, 'code') !== 0) {
    console.error(`pay-notify failed payNo=${payNo}: HTTP ${cb.status} body=${(cb.body || '').slice(0, 220)}`);
    mark(false);
    return;
  }

  // 3.3 轮询订单状态至 20 待发货（已支付），最多 10s
  let paid = false;
  for (let i = 0; i < 10; i++) {
    sleep(1);
    const detail = http.get(`${BASE_URL}/api/order/orders/${orderNo}`, { headers });
    if (detail.status === 200 && jsonPath(detail, 'data.status') === ORDER_STATUS_PAID) {
      paid = true;
      break;
    }
  }
  if (paid) {
    oversellPaidConfirmed.add(1);
  }
  mark(paid); // 支付未在 10s 内闭环按系统错误处理
}

export default async function (data) {
  if (MODE === 'oversell') {
    await runOversell(data);
    return;
  }

  const uid = currentUser();
  // R4-21：优先使用 setup 阶段受控预登录的 JWT / 预建地址（恒速测量窗内登录调用
  // 趋近于零）；VU 编号超出预热池时才回退到带退避重试的 loginToken。
  const token = (data.preTokens && data.preTokens[uid - 1]) || loginToken(uid);
  if (!token) return; // 预热池外且本轮登录重试耗尽：不制造无 token 的下游 401
  const tags = phaseTags();
  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${token}`,
  };
  // 每用户首次被【本 VU】命中时确保可用收货地址（k6 各 VU JS 运行时隔离，
  // addressCache 不跨 VU 共享；setup 预建地址优先，其次「新建失败则取分页首条」
  // 的 ensureAddressId——历史轮次可能已把地址打到 20 条上限）。
  let addressId = addressCache[uid] || (data.preAddresses && data.preAddresses[uid - 1]);
  if (!addressId) {
    addressId = ensureAddressId(token);
  }
  addressCache[uid] = addressId;

  const pick = data.skus[__ITER % data.skus.length];

  group('确认订单页试算', () => {
    const calc = http.post(`${BASE_URL}/api/marketing/h5/marketing/calculate`,
      JSON.stringify({
        userId: uid, userLevel: 0, orderType: 1, freightFen: 0,
        items: [{
          skuId: pick.skuId, spuId: pick.spuId, merchantId: pick.merchantId,
          shopId: pick.shopId, category3Id: pick.category3Id,
          qty: 1, salePriceFen: 9900,
        }],
      }), { headers });
    calcLatency.add(calc.timings.duration, tags);
    check(calc, { 'calc 200': (r) => r.status === 200 });
  });

  group('提交订单', () => {
    const res = http.post(`${BASE_URL}/api/order/orders`,
      JSON.stringify({
        clientToken: uuidv4(),
        orderType: 1, source: 1,
        addressId: addressId,
        items: [{ skuId: pick.skuId, qty: 1 }],
        freightFen: 0,
      }), { headers });

    orderCreateLatency.add(res.timings.duration, tags);
    const code = res.json('code');
    const ok = check(res, {
      'order http 200': (r) => r.status === 200,
      'order accepted or stock-out': (r) => r.status === 200 && (code === 0 || code === 30001),
    });
    if (code === 0) {
      orderCreated.add(1, tags);
    }
    if (code === 30001) {
      soldOut.add(1, tags);
    }
    if (code !== 0 && code !== 30001) {
      console.error(`unexpected order code uid=${uid}: HTTP ${res.status} code=${code} body=${(res.body || '').slice(0, 200)}`);
    }
    successRate.add(ok, tags);
  });

  if (MODE === 'smoke') {
    sleep(0.05);
  }
}

function metricCount(data, name) {
  return data.metrics[name]?.values?.count ?? 0;
}

export function handleSummary(data) {
  const lines = [];
  lines.push(`mode=${MODE}`);
  lines.push(`order_created_total=${metricCount(data, 'order_created_total')}`);
  lines.push(`stock_not_enough=${metricCount(data, 'stock_not_enough_count')}`);

  if (MODE === 'oversell') {
    const stock = parseInt(__ENV.STOCK || '0');
    const expectAttempts = stock * RUSH_MULT;
    const attempts = metricCount(data, 'oversell_order_attempts');
    const winners = metricCount(data, 'oversell_winners');
    const losers = metricCount(data, 'oversell_losers');
    const paidConfirmed = metricCount(data, 'oversell_paid_confirmed');
    lines.push(`oversell stock=${stock} expectedAttempts=${expectAttempts}`
      + ` attempts=${attempts} winners=${winners} losers=${losers}`
      + ` paidConfirmed=${paidConfirmed}`);

    // 非空转强断言：真正的非零退出由 options.thresholds 保证（k6 v2 中 handleSummary
    // throw 只把详细原因打到 stderr、退出码仍为 0）；这里 throw 仅用于输出人类可读的对账明细。
    // 注意：throw 时文件版 summary 不写盘、stdout 汇总也不输出（k6 自带阈值报告仍打印）。
    const problems = [];
    if (attempts < expectAttempts) {
      problems.push(`下单尝试 ${attempts} < 应有 ${expectAttempts}（疑似空转/提前终止）`);
    }
    if (attempts !== winners + losers) {
      problems.push(`存在非预期结果：attempts(${attempts}) != winners(${winners}) + losers(${losers})`);
    }
    if (winners !== stock) {
      problems.push(`成交 ${winners} != 库存 ${stock}（超卖或漏卖）`);
    }
    if (losers !== stock) {
      problems.push(`售罄拒绝 ${losers} != 库存 ${stock}`);
    }
    if (paidConfirmed !== stock) {
      problems.push(`支付确认 ${paidConfirmed} != 库存 ${stock}（赢家未全部全款支付闭环）`);
    }
    if (problems.length > 0) {
      throw new Error(`超卖断言失败（stock=${stock} attempts=${attempts} winners=${winners}`
        + ` losers=${losers} paidConfirmed=${paidConfirmed}）:\n  - ${problems.join('\n  - ')}`);
    }
    lines.push('oversell assertion PASSED: winners=losers=stock，全部赢家已支付');
  }

  const lat = data.metrics.order_create_latency_ms?.values;
  if (lat) {
    // Trend 默认只汇总 avg/med/min/max/p90/p95；p99 未开启时缺省，直接 toFixed 会让 summary 抛错
    const f = (v) => (typeof v === 'number' ? v.toFixed(1) : 'n/a');
    lines.push(`order_latency avg=${f(lat.avg)} p95=${f(lat['p(95)'])} p99=${f(lat['p(99)'])}ms`);
  }
  const txt = lines.join('\n') + '\n';
  return {
    stdout: txt,
    '/tmp/k6-order-summary.json': JSON.stringify(data, null, 2),
  };
}
