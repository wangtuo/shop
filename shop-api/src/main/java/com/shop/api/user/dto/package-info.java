/**
 * 用户域数据传输对象包：查询 DTO 与写操作 Command。
 *
 * <p>规则：
 * <ul>
 *   <li>全部 {@code implements Serializable}，Lombok {@code @Data @NoArgsConstructor @AllArgsConstructor @Builder}；</li>
 *   <li>金额一律 {@code Long}，单位：分（如 deductFen、amountFen），禁止 double/float；</li>
 *   <li>时间使用 {@code java.time.LocalDateTime}；</li>
 *   <li>请求体 Command 使用 jakarta validation 注解（{@code @NotNull}/{@code @NotBlank}），
 *       配合 Feign 方法上的 {@code @Valid} 触发校验；</li>
 *   <li>DTO 字段自包含，禁止引用其他 api 域包。</li>
 * </ul>
 *
 * <p>规则来源：design.md 2.1 用户体系、2.2 用户账户、2.3 收货地址；CONTRACTS.md §2 工程约定、§3 同步契约。
 */
package com.shop.api.user.dto;
