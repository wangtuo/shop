import { start, add, beforeEach, go } from './lib/router.js';
import { auth } from './lib/http.js';
import { refreshCartCount } from './lib/cart-store.js';

import { renderLogin } from './pages/login.js';
import { renderHome } from './pages/home.js';
import { renderCategory } from './pages/category.js';
import { renderSearch } from './pages/search.js';
import { renderProduct } from './pages/product.js';
import { renderCart } from './pages/cart.js';
import { renderCheckout } from './pages/checkout.js';
import { renderPay } from './pages/pay.js';
import { renderPayResult } from './pages/pay-result.js';
import { renderOrders, renderOrderDetail } from './pages/orders.js';
import { renderLogistics } from './pages/logistics.js';
import { renderCommentCreate } from './pages/comment-create.js';
import { renderMe } from './pages/me.js';
import { renderProfile } from './pages/profile.js';
import { renderAddresses, renderAddressEdit } from './pages/addresses.js';
import { renderCoupons } from './pages/coupons.js';
import { renderAftersaleList, renderAftersaleApply, renderAftersaleDetail } from './pages/aftersale.js';

// 登录守卫
const PUBLIC = ['/login', '/', '/category', '/search', '/product'];
beforeEach((pathPart) => {
  if (!auth.isLogin && !PUBLIC.some((p) => pathPart === p || (p !== '/' && pathPart.startsWith(p + '/')) || (p === '/' && pathPart === '/'))) {
    return `/login?redirect=${encodeURIComponent(pathPart)}`;
  }
  if (auth.isLogin) refreshCartCount();
  return true;
});

add('/login', renderLogin);
add('/', renderHome);
add('/category', renderCategory);
add('/search', renderSearch);
add('/product/:id', renderProduct);
add('/cart', renderCart);
add('/checkout', renderCheckout);
add('/pay/:orderNo', renderPay);
add('/pay-result/:payNo', renderPayResult);
add('/orders', renderOrders);
add('/orders/:orderNo', renderOrderDetail);
add('/orders/:orderNo/logistics', renderLogistics);
add('/orders/:orderNo/comment', renderCommentCreate);
add('/me', renderMe);
add('/me/profile', renderProfile);
add('/me/addresses', renderAddresses);
add('/me/addresses/edit', renderAddressEdit);
add('/me/addresses/edit/:id', renderAddressEdit);
add('/me/coupons', renderCoupons);
add('/aftersale', renderAftersaleList);
add('/aftersale/apply/:orderNo', renderAftersaleApply);
add('/aftersale/apply/:orderNo/:skuId', renderAftersaleApply);
add('/aftersale/:no', renderAftersaleDetail);

start();
