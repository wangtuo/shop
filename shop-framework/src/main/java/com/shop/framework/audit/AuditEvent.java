package com.shop.framework.audit;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 审计事件 JSON 结构（O5/C56）。字段顺序即日志行字段顺序；null 值不输出。
 * 二阶段 C59 落库时字段与 t_sys_audit_log 列一一对应。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditEvent {

    /** 毫秒时间戳。 */
    private long ts;
    private String traceId;
    private String requestId;
    /** 操作人 ID；匿名/系统调用为 0。 */
    private long userId;
    private String userName;
    /** 用户类型字符串；匿名/系统调用固定 SYSTEM。 */
    private String userType;
    private Long merchantId;
    private String action;
    private String targetType;
    private String targetId;
    /** SUCCESS / FAIL。 */
    private String result;
    /** 失败异常类名（FAIL 路径）。 */
    private String errorClass;
    private String errorMessage;
    private String clientIp;
    private long costMs;
    /** 入参摘要（captureArgs=true）；敏感字段已脱敏。 */
    private Object args;

    public long getTs() {
        return ts;
    }

    public void setTs(long ts) {
        this.ts = ts;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUserType() {
        return userType;
    }

    public void setUserType(String userType) {
        this.userType = userType;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getErrorClass() {
        return errorClass;
    }

    public void setErrorClass(String errorClass) {
        this.errorClass = errorClass;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public long getCostMs() {
        return costMs;
    }

    public void setCostMs(long costMs) {
        this.costMs = costMs;
    }

    public Object getArgs() {
        return args;
    }

    public void setArgs(Object args) {
        this.args = args;
    }
}
