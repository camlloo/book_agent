package com.bookagent.backend.mapper;

import com.bookagent.backend.model.entity.Article;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
* @author 12507
* @description 针对表【article(博客文章表)】的数据库操作Mapper
* @createDate 2026-10-01 23:46:57
* @Entity com.bookagent.backend.model.entity.Article
*/
public interface ArticleMapper extends BaseMapper<Article> {
    @org.apache.ibatis.annotations.Update("UPDATE article SET view_count=view_count+1,updated_at=updated_at WHERE id=#{id} AND status='PUBLISHED'")
    int incrementPublishedView(@org.apache.ibatis.annotations.Param("id") long id);
}




