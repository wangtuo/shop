package com.shop.aftersale.aftersale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.aftersale.aftersale.entity.AftersaleItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AftersaleItemMapper extends BaseMapper<AftersaleItem> {

    @Select("SELECT * FROM t_aftersale_item WHERE aftersale_no = #{aftersaleNo} AND deleted = 0")
    List<AftersaleItem> selectByAftersaleNo(@Param("aftersaleNo") String aftersaleNo);
}
