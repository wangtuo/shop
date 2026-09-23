// 无限分页加载器：在容器底部放哨兵元素，IntersectionObserver 触发下一页
export function createPager({ container, fetchPage, renderList, pageSize = 20, onTotal, emptyHtml = '' } = {}) {
  let page = 1;
  let totalPages = Infinity;
  let loading = false;
  let done = false;
  let extra = null; // 附加参数（关键词/类目）

  const sentinel = document.createElement('div');
  sentinel.className = 'loading-more';
  sentinel.textContent = '加载中…';
  const list = document.createElement('div');
  container.appendChild(list);
  container.appendChild(sentinel);

  async function load() {
    if (loading || done) return;
    loading = true;
    sentinel.textContent = '加载中…';
    try {
      const data = await fetchPage(page, pageSize, extra);
      const items = data?.list || [];
      const total = data?.total ?? items.length;
      onTotal?.(total);
      if (page === 1) list.innerHTML = '';
      if (items.length) list.insertAdjacentHTML('beforeend', renderList(items));
      if (page * pageSize >= total || items.length === 0) {
        done = true;
        if (page === 1 && items.length === 0) {
          sentinel.textContent = '';
          list.innerHTML = emptyHtml;
        } else {
          sentinel.textContent = '— 已经到底啦 —';
        }
        observer.disconnect();
      }
      page += 1;
    } catch (err) {
      sentinel.textContent = `加载失败：${err.message}（点击重试）`;
      sentinel.style.pointerEvents = 'auto';
      sentinel.onclick = () => { sentinel.onclick = null; load(); };
      done = false;
    } finally { loading = false; }
  }

  const observer = new IntersectionObserver((entries) => {
    if (entries[0].isIntersecting) load();
  }, { rootMargin: '240px' });
  observer.observe(sentinel);

  return {
    reset(params) {
      extra = params || null;
      page = 1; done = false; loading = false;
      list.innerHTML = '';
      observer.observe(sentinel);
      load();
    },
    listEl: list,
  };
}
