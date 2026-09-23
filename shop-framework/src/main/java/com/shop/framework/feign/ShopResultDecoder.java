package com.shop.framework.feign;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.Result;
import feign.FeignException;
import feign.Response;
import feign.codec.Decoder;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Set;

/**
 * 2xx 响应统一解码（C13）：
 * <ul>
 *   <li>空 body / 非 Result 结构 / 反序列化失败 → {@code DEPENDENCY_FAIL}；</li>
 *   <li>{@code Result{code!=0}} → 透传原 code/message 的 BizException；</li>
 *   <li>{@code Result{data=null}} 且泛型非 Void/基本类型包装类 →
 *       {@code DEPENDENCY_FAIL}：框架层永不返回 null（根治售后 r==null 当成功）；</li>
 *   <li>非 Result 返回类型按原样反序列化。</li>
 * </ul>
 */
public class ShopResultDecoder implements Decoder {

    /** data 允许为 null 的泛型：Void 与基本类型包装类。 */
    private static final Set<Class<?>> NULLABLE_DATA_TYPES = Set.of(
            Void.class, Boolean.class, Byte.class, Short.class, Integer.class,
            Long.class, Character.class, Float.class, Double.class);

    private final ObjectMapper objectMapper;

    public ShopResultDecoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Object decode(Response response, Type type) throws IOException, FeignException {
        if (isVoid(type)) {
            FeignBodies.readBody(response);
            return null;
        }

        String clientName = FeignClientNames.fromRequest(response.request());
        byte[] body = FeignBodies.readBody(response);
        if (body.length == 0) {
            throw dependencyFail(clientName);
        }

        boolean resultType = isResultType(type);
        JsonNode node = FeignBodies.parseTree(objectMapper, body);
        if (resultType && !FeignBodies.isResult(node)) {
            throw dependencyFail(clientName);
        }

        Object value;
        try {
            if (String.class.equals(type)) {
                value = new String(body, FeignBodies.charset(response));
            } else {
                JavaType javaType = objectMapper.getTypeFactory().constructType(type);
                value = node != null ? objectMapper.convertValue(node, javaType)
                        : objectMapper.readValue(body, javaType);
            }
        } catch (RuntimeException e) {
            throw dependencyFail(clientName);
        } catch (IOException e) {
            throw dependencyFail(clientName);
        }

        if (value instanceof Result<?> result) {
            if (!result.isSuccess()) {
                throw FeignResults.businessException(result.getCode(), result.getMessage());
            }
            if (result.getData() == null && !nullableDataAllowed(type)) {
                throw dependencyFail(clientName);
            }
        }
        return value;
    }

    private static BizException dependencyFail(String clientName) {
        return new BizException(ErrorCode.DEPENDENCY_FAIL, "下游服务不可用: " + clientName);
    }

    private static boolean isVoid(Type type) {
        return type == void.class || type == Void.class;
    }

    private static boolean isResultType(Type type) {
        if (type instanceof ParameterizedType parameterizedType) {
            return parameterizedType.getRawType() == Result.class;
        }
        return type == Result.class;
    }

    /** Result&lt;Void&gt; 与 Result&lt;包装类&gt; 允许 data=null，其余泛型（含集合）一律不允许。 */
    private static boolean nullableDataAllowed(Type type) {
        if (type instanceof ParameterizedType parameterizedType
                && parameterizedType.getRawType() == Result.class
                && parameterizedType.getActualTypeArguments().length == 1) {
            Type dataType = parameterizedType.getActualTypeArguments()[0];
            return dataType instanceof Class<?> dataClass && NULLABLE_DATA_TYPES.contains(dataClass);
        }
        return false;
    }
}
