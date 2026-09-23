package com.shop.framework.feign;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import feign.Capability;
import feign.InvocationHandlerFactory;
import feign.InvocationHandlerFactory.MethodHandler;
import feign.RetryableException;
import feign.codec.DecodeException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Resilience4j 熔断 + 信号量舱壁的 Feign Capability（C13），按 clientName 自动隔离。
 *
 * <p>包在 InvocationHandler 外层、feign Retryer 更外层：重试发生在熔断器内部（C13 要求）。
 * 业务码异常（{@link BizException} 中非依赖失败类）不计入失败率，避免业务失败打熔断；
 * IO/RetryableException 以原始异常先被熔断器记录，出熔断边界后再归一为 DEPENDENCY_FAIL。
 *
 * <p>纯信号量实现，无线程池/AOP 依赖，{@code shop.feign.circuit.enabled=false} 时整个 Bean 不注册。
 */
public class Resilience4jCapability implements Capability {

    /** BizException 中算作依赖故障、需要计入熔断失败率的错误码。 */
    private static final int DEPENDENCY_FAIL_CODE = ErrorCode.DEPENDENCY_FAIL.getCode();
    private static final int DEPENDENCY_TIMEOUT_CODE = ErrorCode.DEPENDENCY_TIMEOUT.getCode();

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final BulkheadRegistry bulkheadRegistry;

    public Resilience4jCapability(CircuitBreakerRegistry circuitBreakerRegistry,
                                  BulkheadRegistry bulkheadRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.bulkheadRegistry = bulkheadRegistry;
    }

    @Override
    public InvocationHandlerFactory enrich(InvocationHandlerFactory invocationHandlerFactory) {
        return new InvocationHandlerFactory() {
            @Override
            @SuppressWarnings({"rawtypes", "unchecked"})
            public InvocationHandler create(feign.Target target,
                                           Map<Method, MethodHandler> dispatch) {
                InvocationHandler delegate = invocationHandlerFactory.create(target, dispatch);
                String clientName = FeignClientNames.resolve(target);
                return (proxy, method, args) ->
                        invokeGuarded(clientName, () -> delegate.invoke(proxy, method, args));
            }
        };
    }

    private Object invokeGuarded(String clientName, ThrowingInvocation invocation) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(clientName);
        Bulkhead bulkhead = bulkheadRegistry.bulkhead(clientName);

        Callable<Object> callable = () -> {
            try {
                return invocation.invoke();
            } catch (DecodeException e) {
                // 2xx 解码路径中，ShopResultDecoder 对 Result{code!=0} 抛 BizException；
                // Feign SynchronousMethodHandler 会把解码器抛出的一切 RuntimeException
                // 包成 DecodeException。必须在熔断器统计【之前】拆包：
                // 否则业务码被当成依赖故障计入失败率（误熔断），出边界后又被归一/兜底成 10009，
                // 业务语义（如「订单不存在」）全部丢失（W7 CHAOS 恢复探针实证）。
                if (e.getCause() instanceof BizException bizException) {
                    throw bizException;
                }
                throw e;
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                // Feign 业务接口不抛受检异常，理论不可达；保守按依赖失败处理
                throw new BizException(ErrorCode.DEPENDENCY_FAIL,
                        "下游服务不可用: " + clientName, t instanceof Exception e ? e : null);
            }
        };
        callable = CircuitBreaker.decorateCallable(circuitBreaker, callable);
        callable = Bulkhead.decorateCallable(bulkhead, callable);

        try {
            return callable.call();
        } catch (BizException e) {
            throw e;
        } catch (CallNotPermittedException | BulkheadFullException
                 | RetryableException | IOException e) {
            // 熔断 open / 舱壁满 / 重试耗尽 / IO：统一可恢复依赖失败。
            // 必须保留 cause：W7 实证此处吞根因会导致运行期 10008 只剩客户端名，
            // 无法区分 LB「No servers available」、Nacos 订阅空与 NIO 死通道。
            throw new BizException(ErrorCode.DEPENDENCY_FAIL, "下游服务不可用: " + clientName, e);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new BizException(ErrorCode.DEPENDENCY_FAIL, "下游服务不可用: " + clientName,
                    t instanceof Exception e ? e : null);
        }
    }

    /**
     * 业务码（非依赖失败/超时）不打熔断；DEPENDENCY_FAIL/DEPENDENCY_TIMEOUT 计入失败率。
     */
    static boolean countsAsDependencyFailure(Throwable throwable) {
        if (!(throwable instanceof BizException bizException)) {
            return true;
        }
        int code = bizException.getCode();
        return code == DEPENDENCY_FAIL_CODE || code == DEPENDENCY_TIMEOUT_CODE;
    }

    @FunctionalInterface
    private interface ThrowingInvocation {
        Object invoke() throws Throwable;
    }
}
