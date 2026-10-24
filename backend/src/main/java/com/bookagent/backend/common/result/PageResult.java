package com.bookagent.backend.common.result;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Getter;

import java.util.List;
import java.util.function.Function;

@Getter
public class PageResult<T> {

    private long total;      // 总条数
    private long pageNum;    // 当前页码
    private long pageSize;   // 每页条数
    private long pages;      // 总页数
    private List<T> list;    // 当前页数据

    /** 从 MP 的 IPage 转换；converter 负责 entity → VO */
    public static <R, T> PageResult<T> of(IPage<R> page, Function<R, T> converter) {
        PageResult<T> pr = new PageResult<>();
        pr.total = page.getTotal();
        pr.pageNum = page.getCurrent();
        pr.pageSize = page.getSize();
        pr.pages = page.getPages();
        pr.list = page.getRecords().stream().map(converter).toList();
        return pr;
    }
}