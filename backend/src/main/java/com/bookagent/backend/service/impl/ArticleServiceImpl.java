package com.bookagent.backend.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bookagent.backend.common.exception.BusinessException;
import com.bookagent.backend.common.result.PageResult;
import com.bookagent.backend.mapper.ArticleMapper;
import com.bookagent.backend.model.dto.ArticleSaveDTO;
import com.bookagent.backend.model.entity.Article;
import com.bookagent.backend.model.query.ArticleQuery;
import com.bookagent.backend.model.vo.ArticleDetailVO;
import com.bookagent.backend.model.vo.ArticleVO;
import com.bookagent.backend.service.ArticleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import com.bookagent.backend.service.TaxonomyService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.util.List;

import java.util.Date;

/**
* @author 12507
* @description 针对表【article(博客文章表)】的数据库操作Service实现
* @createDate 2026-10-01 23:18:18
*/
@Service
@Slf4j
@RequiredArgsConstructor
public class ArticleServiceImpl extends ServiceImpl<ArticleMapper, Article>
    implements ArticleService {
    private final TaxonomyService taxonomy;
    private static final String STATUS_DRAFT = "DRAFT"; //草稿
    private static final String STATUS_PUBLISHED = "PUBLISHED";//已发布
    private static final String STATUS_OFFLINE = "OFFLINE";//已下线
    @Override
    @Transactional
    public ArticleDetailVO createArticle(ArticleSaveDTO dto) {
        taxonomy.validate(dto.getCategoryId(),dto.getTagIds());
        Article article = BeanUtil.copyProperties(dto, Article.class);
        article.setStatus(STATUS_DRAFT);
        article.setViewCount(0);
        save(article);
        taxonomy.replaceTags(article.getId(),dto.getTagIds());
        return getDetail(article.getId());
    }

    @Override
    public PageResult<ArticleVO> pageQuery(ArticleQuery query) {
        if(StrUtil.isNotBlank(query.getStatus()) && !List.of(STATUS_DRAFT,STATUS_PUBLISHED,STATUS_OFFLINE).contains(query.getStatus()))
            throw new BusinessException(400,"文章状态不合法");
        // 1. 分页参数钳制：防御非法/越界值（ArticleQuery 里有默认值，这里只兜底）
        long pageNum = (query.getPageNum() == null || query.getPageNum() < 1) ? 1 : query.getPageNum();
        long pageSize = (query.getPageSize() == null || query.getPageSize() < 1) ? 10
                : Math.min(query.getPageSize(), 50);

        // 2. 组装条件：每个条件都带"成立才拼"的布尔开关，条件全空 = 查全部
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<Article>()
                // 排除正文大字段：列表页不需要 content，省流量也省内存（f.getProperty() 是属性名）
                .select(Article.class, f -> !"content".equals(f.getProperty()))
                .like(StrUtil.isNotBlank(query.getKeyword()), Article::getTitle, query.getKeyword())
                .eq(StrUtil.isNotBlank(query.getStatus()), Article::getStatus, query.getStatus())
                .eq(query.getCategoryId() != null, Article::getCategoryId, query.getCategoryId())
                .inSql(query.getTagId()!=null, Article::getId, "SELECT article_id FROM article_tag WHERE tag_id=" + (query.getTagId()==null?0:query.getTagId()))
                .orderByDesc(Article::getCreatedAt,Article::getId);

        // 3. 执行分页：MybatisPlusConfig 里注册的分页插件在此生效（自动拼 LIMIT、自动 COUNT）
        Page<Article> page = this.page(new Page<>(pageNum, pageSize), wrapper);

        // 4. entity → VO：列表 VO 本来就没有 content 字段，转换后天然不带出去（双保险）
        PageResult<ArticleVO> result=PageResult.of(page, entity -> BeanUtil.copyProperties(entity, ArticleVO.class));
        taxonomy.enrich(result.getList());
        return result;
    }

    @Override
    public ArticleDetailVO getDetail(Long id) {
        Article article = getOne(new LambdaQueryWrapper<Article>().eq(Article::getId, id));
        if (article == null) {
            throw new BusinessException("文章不存在");
        }
        ArticleDetailVO articleDetailVO = BeanUtil.copyProperties(article, ArticleDetailVO.class);
        articleDetailVO.setContent(article.getContent());
        articleDetailVO.setUpdatedAt(article.getUpdatedAt());
        taxonomy.enrich(List.of(articleDetailVO));
        return articleDetailVO;
    }

    @Override
    @Transactional
    public void updateArticle(Long id, ArticleSaveDTO dto) {
        taxonomy.validate(dto.getCategoryId(),dto.getTagIds());
        Article byId = getById(id);
        if (byId == null) {
            throw new BusinessException("文章不存在");
        }
        update(new LambdaUpdateWrapper<Article>().eq(Article::getId,id)
            .set(Article::getTitle,dto.getTitle()).set(Article::getSummary,dto.getSummary())
            .set(Article::getContent,dto.getContent()).set(Article::getCategoryId,dto.getCategoryId()));
        taxonomy.replaceTags(id,dto.getTagIds());
    }

    @Override
    @Transactional
    public void deleteArticle(Long id) {
        Article byId = getById(id);
        if (byId == null) {
            throw new BusinessException("文章不存在");
        }
        taxonomy.replaceTags(id,List.of());
        removeById(id);
    }

    @Override
    public ArticleDetailVO publishArticle(Long id) {
        Article byId = getById(id);
        if (byId == null) {
            throw new BusinessException("文章不存在");
        }
        if(STATUS_PUBLISHED.equals(byId.getStatus())){
            throw new BusinessException("当前状态不允许发布");
        }
        boolean changed=update(new LambdaUpdateWrapper<Article>().eq(Article::getId,id)
            .in(Article::getStatus,STATUS_DRAFT,STATUS_OFFLINE).set(Article::getStatus,STATUS_PUBLISHED)
            .setSql("published_at=COALESCE(published_at,CURRENT_TIMESTAMP)"));
        if(!changed) throw new BusinessException(400,"当前状态不允许发布");
      return getDetail(id);
    }

    @Override
    public ArticleDetailVO offline(Long id) {
        Article byId = getById(id);
        if (byId == null) {
            throw new BusinessException("文章不存在");
        }
        if(STATUS_OFFLINE.equals(byId.getStatus())||STATUS_DRAFT.equals(byId.getStatus())){
            throw new BusinessException("当前状态不允许下线");
        }
        boolean changed=update(new LambdaUpdateWrapper<Article>().eq(Article::getId,id)
            .eq(Article::getStatus,STATUS_PUBLISHED).set(Article::getStatus,STATUS_OFFLINE));
        if(!changed) throw new BusinessException(400,"当前状态不允许下线");
        return getDetail(id);
    }
}




