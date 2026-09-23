package com.shop.framework.tx;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 自调用代理获取器（规约 4）。
 *
 * <p>Spring 基于代理的 AOP 决定了同类内 {@code this.xxx()} 调用绕过代理，
 * {@code @Transactional(REQUIRES_NEW)}、{@code @Async}、{@code @Cacheable} 等注解全部失效。
 * 需要在同类中触发新事务/新线程时，必须调用"自己的代理对象"而不是 this。</p>
 *
 * <p>方式一（推荐，本组件）：</p>
 * <pre>{@code
 * @Service
 * public class ReconcileService {
 *     private final SelfInvoker selfInvoker;
 *     // 构造注入省略
 *
 *     public void scan() {
 *         // 走代理：handleOne 上的 REQUIRES_NEW 才真实生效
 *         selfInvoker.self(ReconcileService.class).handleOne(diff);
 *     }
 *
 *     @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
 *     public void handleOne(Diff diff) { ... }
 * }
 * }</pre>
 *
 * <p>方式二（等价）：构造器上使用 {@code @Lazy} 自注入，规避循环依赖初始化顺序问题：</p>
 * <pre>{@code
 * private final ReconcileService self;
 * public ReconcileService(@Lazy ReconcileService self) { this.self = self; }
 * }</pre>
 */
@Component
public class SelfInvoker {

    private final ApplicationContext applicationContext;

    public SelfInvoker(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 获取当前 Bean 的 Spring 代理（按类型）。
     *
     * @param beanType 调用方自身类型
     * @return 容器中的代理 Bean；调用其带 AOP 注解的 public 方法才会走切面
     */
    public <T> T self(Class<T> beanType) {
        return applicationContext.getBean(beanType);
    }
}
