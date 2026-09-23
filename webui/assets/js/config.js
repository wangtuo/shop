// 全局配置
export const CONFIG = {
  // nginx 同源反代到 shop-gateway:8080；如直连网关可改为 http://localhost:8080
  BASE: window.__API_BASE__ || '',
  TOKEN_KEY: 'shop_token',
  USER_KEY: 'shop_user',
  CART_KEY: 'shop_cart_local', // 未登录兜底购物车
};
