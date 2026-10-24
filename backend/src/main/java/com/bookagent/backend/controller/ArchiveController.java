package com.bookagent.backend.controller;

import com.bookagent.backend.common.result.Result;
import com.bookagent.backend.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController @RequiredArgsConstructor
public class ArchiveController {
    private final KnowledgeArchive archive;private final BookService books;private final AiClient ai;private final KnowledgeAccess access;
    private final java.util.concurrent.Semaphore archiveGate=new java.util.concurrent.Semaphore(1);
    private <T>T archiveOperation(java.util.concurrent.Callable<T> work)throws Exception{if(!archiveGate.tryAcquire())throw new com.bookagent.backend.common.exception.BusinessException(429,"已有备份或恢复任务，请稍后重试");try{return work.call();}finally{archiveGate.release();}}
    private ResponseEntity<byte[]> file(byte[] bytes,String name,String type){return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(name,StandardCharsets.UTF_8).build().toString()).header(HttpHeaders.CACHE_CONTROL,"no-store").body(bytes);}
    @GetMapping("/api/workspace/backup") public ResponseEntity<byte[]> backup()throws Exception{return archiveOperation(()->file(archive.backup(),"book-agent-backup.zip","application/zip"));}
    @PostMapping("/api/workspace/restore") public Result<?> restore(@RequestParam("file") MultipartFile file)throws Exception{if(file.getSize()>KnowledgeArchive.MAX_ZIP)throw new com.bookagent.backend.common.exception.BusinessException(400,"备份最多 100MB");return archiveOperation(()->Result.ok(Map.of("bookIds",archive.restore(file.getBytes()))));}
    @GetMapping("/api/books/{id}/export") public ResponseEntity<byte[]> export(@PathVariable long id,@RequestParam(defaultValue="md") String format)throws Exception{
        var data=books.read(id);var book=data.book();StringBuilder content=new StringBuilder("# "+book.title()+"\n\n").append(book.content()==null?"":book.content());
        for(var section:data.sections()){content.append("\n\n## ").append(section.path("sourceName").asText());if(section.path("page").isNumber())content.append(" · 第 ").append(section.path("page").asInt()).append(" 页");content.append("\n\n").append(section.path("text").asText());}
        byte[] result;String type;
        if(format.equals("md")){result=content.toString().getBytes(StandardCharsets.UTF_8);type="text/markdown;charset=UTF-8";}
        else if(format.equals("pdf")){result=ai.binary("/internal/export/pdf",Map.of("title",book.title(),"content",content.toString()));type="application/pdf";}
        else throw new com.bookagent.backend.common.exception.BusinessException(400,"导出格式仅支持 md/pdf");
        access.book(id,"READ");if(books.detail(id).revision()!=book.revision())throw new com.bookagent.backend.common.exception.BusinessException(409,"内容已变化，请重新导出");
        return file(result,book.title().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_")+"."+format,type);
    }
}
