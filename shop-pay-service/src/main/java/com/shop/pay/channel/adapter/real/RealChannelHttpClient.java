package com.shop.pay.channel.adapter.real;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 真实渠道统一 HTTP 封装（B8 骨架）：超时、重试、请求/响应脱敏日志。
 *
 * <p>TODO(真实渠道联调) 边界：本类不引入真实 HTTP 客户端依赖，联调时替换 {@link #execute} 方法体
 * （RestClient/OkHttp），必须落实：connect/read 超时取 {@link RealChannelProperties}、
 * 5xx/IO 异常按 maxRetries 幂等重试（GET/带幂等键 POST 才可重试）、Authorization/签名/证书序列号
 * 头装配、日志对密钥/完整卡号/手机号脱敏、4xx 业务错误经 {@link RealResponseParser} 映射。
 * 当前任何调用一律抛 {@link ErrorCode#DEPENDENCY_FAIL}，禁止伪成功。</p>
 */
@Component
public class RealChannelHttpClient {

    private static final Logger log = LoggerFactory.getLogger(RealChannelHttpClient.class);

    private final RealChannelProperties properties;

    public RealChannelHttpClient(RealChannelProperties properties) {
        this.properties = properties;
    }

    /**
     * 执行渠道请求。
     *
     * @param channelCode 渠道码
     * @param path        接口路径（拼接在 endpoint 之后）
     * @param method      GET / POST
     * @param headers     已签名/鉴权头（不含密钥明文，密钥禁止出现在日志）
     * @param body        请求体（下单/退款 JSON）
     * @return 渠道原始响应字符串（由 {@link RealResponseParser} 解析）
     */
    public String execute(String channelCode, String path, String method,
                          Map<String, String> headers, String body) {
        RealChannelProperties.Endpoint endpoint = properties.endpoint(channelCode);
        log.warn("[真实渠道骨架] HTTP 未联调 channel={} {} {}{} connectMs={} readMs={}",
                channelCode, method, endpoint == null ? "<no-endpoint>" : endpoint.getEndpoint(), path,
                properties.getConnectTimeoutMs(), properties.getReadTimeoutMs());
        // TODO(真实渠道联调): 替换为真实 HTTP 调用 + 超时/重试/脱敏日志/证书双向认证
        throw new BizException(ErrorCode.DEPENDENCY_FAIL,
                "真实渠道 " + channelCode + " HTTP 能力未联调: " + method + " " + path);
    }

    public RealChannelProperties properties() {
        return properties;
    }
}
