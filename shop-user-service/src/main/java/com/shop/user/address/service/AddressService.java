package com.shop.user.address.service;

import com.shop.api.user.dto.AddressDTO;
import com.shop.common.result.PageQuery;
import com.shop.common.result.PageResult;
import com.shop.user.address.dto.AddressSaveRequest;
import com.shop.user.address.entity.UserAddress;

/**
 * 收货地址服务：上限 20 条/用户、仅 1 个默认地址（切换在同事务内完成）、归属校验。
 */
public interface AddressService {

    /** 新增地址，返回地址 ID；第一条地址强制为默认 */
    Long create(Long userId, AddressSaveRequest request);

    /** 修改地址（校验归属；设置默认时同事务清除旧默认） */
    void update(Long userId, Long addressId, AddressSaveRequest request);

    /** 删除地址（校验归属） */
    void delete(Long userId, Long addressId);

    /** 查询自己的地址（归属校验） */
    UserAddress requireOwned(Long userId, Long addressId);

    /** 内部接口按 ID 查询地址（不存在抛 NOT_FOUND），下单前获取收货信息 */
    UserAddress getByIdInternal(Long addressId);

    /** 分页查询用户地址（默认地址优先，次按更新时间倒序） */
    PageResult<UserAddress> page(Long userId, PageQuery query);

    /** 实体转对外契约 DTO */
    AddressDTO toDto(UserAddress address);
}
