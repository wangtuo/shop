package com.shop.user.address.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.api.user.dto.AddressDTO;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.PageQuery;
import com.shop.common.result.PageResult;
import com.shop.framework.id.IdGenerator;
import com.shop.user.address.dto.AddressSaveRequest;
import com.shop.user.address.entity.UserAddress;
import com.shop.user.address.mapper.UserAddressMapper;
import com.shop.user.address.service.AddressService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AddressServiceImpl implements AddressService {

    /** design.md 2.3：每个用户最多 20 个收货地址 */
    public static final long ADDRESS_LIMIT = 20L;
    private static final int NOT_DEFAULT = 0;
    private static final int DEFAULT = 1;

    private final UserAddressMapper addressMapper;
    private final IdGenerator idGenerator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(Long userId, AddressSaveRequest req) {
        long count = addressMapper.selectCount(new LambdaQueryWrapper<UserAddress>()
                .eq(UserAddress::getUserId, userId));
        if (count >= ADDRESS_LIMIT) {
            throw new BizException(ErrorCode.PARAM_INVALID, "每个用户最多维护20个收货地址");
        }
        boolean asDefault = (req.getIsDefault() != null && req.getIsDefault() == DEFAULT) || count == 0;
        if (asDefault) {
            addressMapper.clearDefault(userId);
        }
        UserAddress address = new UserAddress();
        address.setId(idGenerator.nextId());
        address.setUserId(userId);
        fill(address, req);
        address.setIsDefault(asDefault ? DEFAULT : NOT_DEFAULT);
        addressMapper.insert(address);
        return address.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long userId, Long addressId, AddressSaveRequest req) {
        UserAddress address = requireOwned(userId, addressId);
        boolean wantDefault = req.getIsDefault() != null && req.getIsDefault() == DEFAULT;
        if (wantDefault && address.getIsDefault() != DEFAULT) {
            addressMapper.clearDefault(userId);
        }
        fill(address, req);
        if (wantDefault) {
            address.setIsDefault(DEFAULT);
        }
        addressMapper.updateById(address);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long addressId) {
        requireOwned(userId, addressId);
        addressMapper.deleteById(addressId);
    }

    @Override
    public UserAddress requireOwned(Long userId, Long addressId) {
        UserAddress address = getByIdInternal(addressId);
        if (!address.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问该收货地址");
        }
        return address;
    }

    @Override
    public UserAddress getByIdInternal(Long addressId) {
        if (addressId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "地址ID不能为空");
        }
        UserAddress address = addressMapper.selectById(addressId);
        if (address == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "收货地址不存在: " + addressId);
        }
        return address;
    }

    @Override
    public PageResult<UserAddress> page(Long userId, PageQuery query) {
        Page<UserAddress> page = new Page<>(query.safePageNum(), query.safePageSize());
        Page<UserAddress> result = addressMapper.selectPage(page, new LambdaQueryWrapper<UserAddress>()
                .eq(UserAddress::getUserId, userId)
                .orderByDesc(UserAddress::getIsDefault)
                .orderByDesc(UserAddress::getId));
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    @Override
    public AddressDTO toDto(UserAddress a) {
        return AddressDTO.builder()
                .addressId(a.getId())
                .userId(a.getUserId())
                .receiver(a.getReceiver())
                .phone(a.getPhone())
                .province(a.getProvince())
                .city(a.getCity())
                .district(a.getDistrict())
                .detailAddress(a.getDetailAddress())
                .zipCode(a.getZipCode())
                .tag(a.getTag())
                .isDefault(a.getIsDefault())
                .build();
    }

    private void fill(UserAddress address, AddressSaveRequest req) {
        address.setReceiver(req.getReceiver());
        address.setPhone(req.getPhone());
        address.setProvince(req.getProvince());
        address.setCity(req.getCity());
        address.setDistrict(req.getDistrict());
        address.setDetailAddress(req.getDetailAddress());
        address.setZipCode(req.getZipCode());
        address.setTag(req.getTag());
    }
}
