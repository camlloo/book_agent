package com.bookagent.backend.service;

import com.bookagent.backend.common.exception.BusinessException;
import com.bookagent.backend.model.dto.TaxonomyDTO;
import com.bookagent.backend.model.vo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class TaxonomyService {
    private final JdbcTemplate jdbc;
    private final KnowledgeAccess access;
    private boolean publicTaxonomy(){var attrs=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();return attrs instanceof org.springframework.web.context.request.ServletRequestAttributes servlet&&servlet.getRequest().getRequestURI().startsWith("/api/blog/");}
    private String categoryScope(){return publicTaxonomy()?" EXISTS(SELECT 1 FROM book_publication p JOIN book b ON b.id=p.book_id WHERE p.category_id=c.id AND p.status='PUBLISHED' AND b.deleted=0)":"(o.user_id="+access.actor()+" OR o.user_id IS NULL)";}

    public List<CategoryVO> categories() {
        var flat = jdbc.query("SELECT c.id,COALESCE(o.label,c.name) AS name,c.parent_id,c.sort_order FROM category c LEFT JOIN category_owner o ON o.category_id=c.id WHERE "+categoryScope()+" ORDER BY c.sort_order,c.id",
            (rs,n) -> new CategoryVO(rs.getLong("id"),rs.getString("name"),rs.getObject("parent_id",Long.class),rs.getInt("sort_order"),new ArrayList<>()));
        Map<Long,CategoryVO> map = new LinkedHashMap<>();
        flat.forEach(c -> map.put(c.id(),c));
        List<CategoryVO> roots = new ArrayList<>();
        for (var c:flat) { if(c.parentId()==null || !map.containsKey(c.parentId())) roots.add(c); else map.get(c.parentId()).children().add(c); }
        return roots;
    }
    public List<TagVO> tags() {
        String scope=publicTaxonomy()?"EXISTS(SELECT 1 FROM book_tag bt JOIN book_publication p ON p.book_id=bt.book_id JOIN book b ON b.id=bt.book_id WHERE bt.tag_id=t.id AND p.status='PUBLISHED' AND b.deleted=0)":"(o.user_id="+access.actor()+" OR o.user_id IS NULL)";
        return jdbc.query("SELECT t.id,COALESCE(o.label,t.name) FROM tag t LEFT JOIN tag_owner o ON o.tag_id=t.id WHERE "+scope+" ORDER BY 2,t.id",(rs,n)->new TagVO(rs.getLong(1),rs.getString(2)));
    }
    private void requireOwned(String kind,long id){
        if(access.web()&&!access.admin()&&jdbc.queryForObject("SELECT COUNT(*) FROM "+kind+"_owner WHERE "+kind+"_id=? AND user_id=?",Long.class,id,access.actor())==0)throw new BusinessException(404,"分类或标签不存在");
    }
    @Transactional
    public long saveCategory(Long id, TaxonomyDTO dto) {
        jdbc.queryForList("SELECT id FROM category ORDER BY id FOR UPDATE");
        if(id!=null){requireCategory(id);requireOwned("category",id);}
        if(dto.parentId()!=null) {
            requireCategory(dto.parentId());requireOwned("category",dto.parentId());
            Long current=dto.parentId(); Set<Long> visited=new HashSet<>();
            while(current!=null) {
                if(Objects.equals(current,id) || !visited.add(current)) throw new BusinessException(400,"分类不能形成循环");
                current=jdbc.queryForObject("SELECT parent_id FROM category WHERE id=?",Long.class,current);
            }
        }
        String name=dto.name().trim();
        if(name.isEmpty()) throw new BusinessException(400,"名称不能为空");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM category_owner WHERE user_id=? AND label=? AND (? IS NULL OR category_id<>?)",Long.class,access.actor(),name,id,id)>0)
            throw new BusinessException(400,"分类名称已存在");
        int order=dto.sortOrder()==null?0:dto.sortOrder();
        if(id==null){long added=insert("INSERT INTO category(name,parent_id,sort_order) VALUES(?,?,?)","k_"+UUID.randomUUID(),dto.parentId(),order);jdbc.update("INSERT INTO category_owner(category_id,user_id,label) VALUES(?,?,?)",added,access.actor(),name);return added;}
        jdbc.update("UPDATE category SET parent_id=?,sort_order=? WHERE id=?",dto.parentId(),order,id);jdbc.update("UPDATE category_owner SET label=? WHERE category_id=?",name,id);return id;
    }
    @Transactional
    public long saveTag(Long id,TaxonomyDTO dto) {
        if(id!=null)requireOwned("tag",id);
        if(id!=null && jdbc.queryForObject("SELECT COUNT(*) FROM tag WHERE id=?",Long.class,id)==0) throw new BusinessException(404,"标签不存在");
        String name=dto.name().trim();
        if(name.isEmpty()) throw new BusinessException(400,"名称不能为空");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM tag_owner WHERE user_id=? AND label=? AND (? IS NULL OR tag_id<>?)",Long.class,access.actor(),name,id,id)>0) throw new BusinessException(400,"标签名称已存在");
        if(id==null){long added=insert("INSERT INTO tag(name) VALUES(?)","k_"+UUID.randomUUID());jdbc.update("INSERT INTO tag_owner(tag_id,user_id,label) VALUES(?,?,?)",added,access.actor(),name);return added;}
        jdbc.update("UPDATE tag_owner SET label=? WHERE tag_id=?",name,id);return id;
    }
    @Transactional
    public void deleteCategory(long id) {
        requireOwned("category",id);
        requireCategory(id);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM category WHERE parent_id=?",Long.class,id)>0) throw new BusinessException(400,"请先删除子分类");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM book_publication p JOIN book b ON b.id=p.book_id WHERE p.category_id=? AND b.deleted=0",Long.class,id)>0 || jdbc.queryForObject("SELECT COUNT(*) FROM article a WHERE category_id=? AND NOT EXISTS(SELECT 1 FROM legacy_article_book m WHERE m.article_id=a.id)",Long.class,id)>0) throw new BusinessException(400,"请先移除分类下的 Book");
        jdbc.update("DELETE FROM category WHERE id=?",id);
    }
    @Transactional
    public void deleteTag(long id) {
        requireOwned("tag",id);
        if(jdbc.queryForList("SELECT id FROM tag WHERE id=? FOR UPDATE",id).isEmpty()) throw new BusinessException(404,"标签不存在");
        jdbc.update("DELETE FROM article_tag WHERE tag_id=?",id); jdbc.update("DELETE FROM tag WHERE id=?",id);
    }
    public void requireCategory(Long id) {
        if(id!=null && jdbc.queryForList("SELECT id FROM category WHERE id=? FOR UPDATE",id).isEmpty()) throw new BusinessException(400,"分类不存在");
    }
    public void validate(Long categoryId,List<Long> tags) { validateFor(null,categoryId,tags); }
    public void validateFor(Long bookId,Long categoryId,List<Long> tags) {
        requireCategory(categoryId);
        if(access.web()&&!access.admin()) {
            Long owner=bookId==null?access.actor():jdbc.queryForObject("SELECT user_id FROM book_owner WHERE book_id=?",Long.class,bookId);
            if(categoryId!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM category_owner WHERE category_id=? AND user_id IN (?,?)",Long.class,categoryId,access.actor(),owner)==0)throw new BusinessException(400,"分类不属于当前知识空间");
            if(tags!=null)for(Long tag:tags)if(tag==null||jdbc.queryForObject("SELECT COUNT(*) FROM tag_owner WHERE tag_id=? AND user_id IN (?,?)",Long.class,tag,access.actor(),owner)==0)throw new BusinessException(400,"标签不属于当前知识空间");
        }
        if(tags==null) return;
        if(tags.size()>20) throw new BusinessException(400,"每本 Book 最多 20 个标签");
        if(tags.stream().anyMatch(Objects::isNull)) throw new BusinessException(400,"标签不存在");
        for(Long id:new java.util.TreeSet<>(tags)) if(id<=0 || jdbc.queryForList("SELECT id FROM tag WHERE id=? FOR UPDATE",id).isEmpty()) throw new BusinessException(400,"标签不存在");
    }
    public void replaceTags(long articleId,List<Long> tags) {
        jdbc.update("DELETE FROM article_tag WHERE article_id=?",articleId);
        if(tags!=null) for(Long id:new LinkedHashSet<>(tags)) jdbc.update("INSERT INTO article_tag(article_id,tag_id) VALUES(?,?)",articleId,id);
    }
    // One batch per page; never fetch content or issue one query per article.
    public void enrich(List<? extends ArticleVO> articles) {
        if(articles.isEmpty()) return;
        String ids=String.join(",",Collections.nCopies(articles.size(),"?"));
        Object[] args=articles.stream().map(ArticleVO::getId).toArray();
        Map<Long,ArticleVO> byId=new HashMap<>(); articles.forEach(a->{byId.put(a.getId(),a);a.setTags(new ArrayList<>());});
        jdbc.query("SELECT a.id,c.name FROM article a LEFT JOIN category c ON a.category_id=c.id WHERE a.id IN ("+ids+")",rs->{byId.get(rs.getLong(1)).setCategoryName(rs.getString(2));},args);
        jdbc.query("SELECT at.article_id,t.id,t.name FROM article_tag at JOIN tag t ON t.id=at.tag_id WHERE at.article_id IN ("+ids+") ORDER BY t.name",rs->{byId.get(rs.getLong(1)).getTags().add(new TagVO(rs.getLong(2),rs.getString(3)));},args);
    }
    public long insert(String sql,Object...args) {
        var key=new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con->{var stmt=con.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)stmt.setObject(i+1,args[i]);return stmt;},key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
}
