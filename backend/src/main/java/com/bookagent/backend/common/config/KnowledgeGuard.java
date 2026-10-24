package com.bookagent.backend.common.config;

import com.bookagent.backend.service.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import java.util.regex.*;

@Component @RequiredArgsConstructor
public class KnowledgeGuard implements HandlerInterceptor {
    private final KnowledgeAccess access;private final LegacyBookIds legacy;
    private static final Pattern BOOK=Pattern.compile("/api/(books|articles)/(\\d+)(/.*)?");
    private static final Pattern TASK=Pattern.compile("/api/tasks/(\\d+)(/.*)?");
    @Override public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler){
        access.active();String path=req.getRequestURI();
        if(cn.dev33.satoken.stp.StpUtil.hasRole("READER")&&!req.getMethod().equals("GET")&&!path.endsWith("/ask")&&!path.startsWith("/api/auth/"))throw new com.bookagent.backend.common.exception.BusinessException(403,"只读账号不能修改知识");
        var book=BOOK.matcher(path);
        if(book.matches()){
            long id=Long.parseLong(book.group(2));if(book.group(1).equals("articles"))id=legacy.resolve(id);
            String tail=book.group(3)==null?"":book.group(3),method=req.getMethod();
            String action=method.equals("GET")||tail.equals("/ask")||tail.equals("/suggest")||tail.equals("/assistant")?"READ":method.equals("DELETE")||tail.equals("/publish")||tail.equals("/offline")||tail.startsWith("/shares")?"OWNER":"EDIT";
            if(tail.startsWith("/shares"))action="OWNER";
            access.book(id,action);
        }
        var task=TASK.matcher(path);if(task.matches())access.task(Long.parseLong(task.group(1)));
        return true;
    }
}
