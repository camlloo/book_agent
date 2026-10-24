package com.bookagent.backend.service;

import cn.dev33.satoken.stp.StpUtil;
import com.bookagent.backend.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import java.util.*;

/** Single permission boundary for personal, shared and published knowledge. */
@Service @RequiredArgsConstructor
public class KnowledgeAccess {
    private final JdbcTemplate jdbc;
    private final ThreadLocal<Long> acting=new ThreadLocal<>();
    public <T>T asUser(long user,java.util.concurrent.Callable<T> work)throws Exception {Long previous=acting.get();acting.set(user);try{return work.call();}finally{if(previous==null)acting.remove();else acting.set(previous);}}
    public boolean web() { if(RequestContextHolder.getRequestAttributes()==null)return false;try{cn.dev33.satoken.context.SaHolder.getStorage();return true;}catch(cn.dev33.satoken.exception.SaTokenContextException e){return false;} }
    public long defaultOwner() {
        Long id=jdbc.queryForObject("SELECT MIN(id) FROM `user` WHERE role='ADMIN'",Long.class);
        if(id==null)throw new IllegalStateException("Administrator must be initialized before migrating knowledge");
        return id;
    }
    public long actor() {
        if(acting.get()!=null)return acting.get();
        if(!web())return defaultOwner();
        return StpUtil.isLogin()?StpUtil.getLoginIdAsLong():0;
    }
    public boolean admin() {
        long actor=actor();
        return actor>0 && Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)>0 FROM `user` WHERE id=? AND role='ADMIN' AND status=1",Boolean.class,actor));
    }
    public void active() {
        if(actor()==0 || jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE id=? AND status=1",Long.class,actor())==0)
            throw new BusinessException(401,"登录已失效，请重新登录");
    }
    public void own(long bookId,long owner) {jdbc.update("INSERT INTO book_owner(book_id,user_id) VALUES(?,?)",bookId,owner);}
    public String role(long owner,String shared) {
        if(actor()==0)return "PUBLIC";
        return owner==actor()?"OWNER":admin()?"ADMIN":shared==null?"PUBLIC":shared;
    }
    public void book(long bookId,String action) { bookFor(bookId,actor(),admin(),action); }
    public void bookFor(long bookId,long actor,boolean administrator,String action) {
        var rows=jdbc.queryForList("SELECT o.user_id,s.access_role,COALESCE(p.status,'DRAFT') AS status FROM book b JOIN book_owner o ON o.book_id=b.id LEFT JOIN book_share s ON s.book_id=b.id AND s.user_id=? LEFT JOIN book_publication p ON p.book_id=b.id WHERE b.id=? AND b.deleted=0",actor,bookId);
        if(rows.isEmpty())throw new BusinessException(404,"Book 不存在或无权访问");
        var row=rows.getFirst();boolean owner=((Number)row.get("user_id")).longValue()==actor;
        String shared=(String)row.get("access_role");
        boolean allowed=administrator||owner||switch(action){
            case "READ" -> "VIEWER".equals(shared)||"EDITOR".equals(shared)||"PUBLISHED".equals(row.get("status"));
            case "EDIT" -> "EDITOR".equals(shared);
            default -> false;
        };
        if(!allowed)throw new BusinessException(404,"Book 不存在或无权执行该操作");
    }
    public Set<Long> readableIds(Collection<Long> ids) {
        if(ids.isEmpty())return Set.of();if(ids.size()>5000)throw new BusinessException(400,"引用范围过大");
        var args=new ArrayList<Object>(ids);String in=String.join(",",Collections.nCopies(ids.size(),"?"));
        String clause=admin()?"1=1":"(o.user_id="+actor()+" OR p.status='PUBLISHED' OR EXISTS(SELECT 1 FROM book_share s WHERE s.book_id=b.id AND s.user_id="+actor()+"))";
        return new HashSet<>(jdbc.query("SELECT b.id FROM book b JOIN book_owner o ON o.book_id=b.id LEFT JOIN book_publication p ON p.book_id=b.id WHERE b.deleted=0 AND b.id IN ("+in+") AND "+clause,(rs,n)->rs.getLong(1),args.toArray()));
    }
    public void task(long id) {
        if(!admin()&&jdbc.queryForObject("SELECT COUNT(*) FROM task_owner WHERE task_id=? AND user_id=?",Long.class,id,actor())==0)
            throw new BusinessException(404,"任务不存在");
    }
    public String visibleSql(boolean publicOnly) {
        return publicOnly?"p.status='PUBLISHED'":"(o.user_id="+actor()+" OR EXISTS(SELECT 1 FROM book_share a WHERE a.book_id=b.id AND a.user_id="+actor()+"))";
    }
    public List<Map<String,Object>> scopes(boolean publicOnly,List<Long> selected) {
        var args=new ArrayList<Object>();
        String filter="";
        if(selected!=null&&!selected.isEmpty()) {
            if(selected.size()>5000||selected.stream().anyMatch(id->id==null||id<1))throw new BusinessException(400,"Book 选择不合法");
            filter=" AND b.id IN ("+String.join(",",Collections.nCopies(selected.size(),"?"))+")";args.addAll(selected);
        }
        var result=jdbc.queryForList("SELECT b.id AS bookId,b.revision,b.title FROM book b JOIN book_owner o ON o.book_id=b.id LEFT JOIN book_publication p ON p.book_id=b.id WHERE b.deleted=0 AND b.index_status='READY' AND b.indexed_revision=b.revision AND "+visibleSql(publicOnly)+filter+" ORDER BY b.id LIMIT 5001",args.toArray());
        if(result.size()>5000)throw new BusinessException(400,"请缩小到 5000 本以内的检索范围");
        // JDBC labels differ by dialect; keep the internal HTTP schema explicit.
        return result.stream().map(r->{Map<String,Object> s=new LinkedHashMap<>();for(var e:r.entrySet())s.put(e.getKey().equalsIgnoreCase("bookId")?"book_id":e.getKey().toLowerCase(Locale.ROOT),e.getValue());return s;}).toList();
    }
}
