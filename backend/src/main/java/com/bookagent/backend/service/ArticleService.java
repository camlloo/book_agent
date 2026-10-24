package com.bookagent.backend.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bookagent.backend.common.result.PageResult;
import com.bookagent.backend.model.dto.ArticleSaveDTO;
import com.bookagent.backend.model.entity.Article;
import com.bookagent.backend.model.query.ArticleQuery;
import com.bookagent.backend.model.vo.ArticleDetailVO;
import com.bookagent.backend.model.vo.ArticleVO;

/**
* @author 12507
* @description 针对表【article(博客文章表)】的数据库操作Service
* @createDate 2026-10-01 23:18:18
*/
public interface ArticleService extends IService<Article> {
    /** 新增文章（默认草稿态），返回完整详情 */
    ArticleDetailVO createArticle(ArticleSaveDTO dto);

    /** 分页筛选（关键词/状态/分类），列表不含正文 */
    PageResult<ArticleVO> pageQuery(ArticleQuery query);

    /** 详情（含正文） */
    ArticleDetailVO getDetail(Long id);

    /** 编辑（只改内容字段，不动状态/浏览量/发布时间） */
    void updateArticle(Long id, ArticleSaveDTO dto);
    /** 删除 */
    void deleteArticle(Long id);
    /** 发布：DRAFT/OFFLINE → PUBLISHED */
    ArticleDetailVO publishArticle(Long id);
    /** 下线：PUBLISHED → OFFLINE */
    ArticleDetailVO offline(Long id);
}
