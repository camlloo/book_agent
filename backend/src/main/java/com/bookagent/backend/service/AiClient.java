package com.bookagent.backend.service;
import com.bookagent.backend.common.exception.BusinessException;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

@Service @RequiredArgsConstructor
public class AiClient {
    private final HttpClient http;
    private final ObjectMapper json;
    @Value("${book-agent.ai-url:http://127.0.0.1:8100}") private String url;
    @Value("${book-agent.internal-token:}") private String token;
    public byte[] binary(String path,Object payload) {
        try {
            var request=HttpRequest.newBuilder(URI.create(url+path)).timeout(Duration.ofMinutes(5)).header("Content-Type","application/json").header("X-Internal-Token",token).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
            var result=http.send(request,HttpResponse.BodyHandlers.ofByteArray());
            if(result.statusCode()!=200)throw new BusinessException(503,"导出服务不可用");return result.body();
        }catch(BusinessException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new BusinessException(503,"导出已中断");}catch(Exception e){throw new BusinessException(503,"无法连接导出服务");}
    }
    public JsonNode call(String path,Object payload) {
        if(token.isBlank()) throw new BusinessException(503,"请先配置 INTERNAL_TOKEN");
        try {
            var request=HttpRequest.newBuilder(URI.create(url+path)).timeout(Duration.ofMinutes(path.equals("/internal/books/index")?10:5))
                .header("Content-Type","application/json").header("X-Internal-Token",token)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200) {
                String message="AI 服务未就绪或模型调用失败，请检查 AI 服务配置与日志";
                try { var detail=json.readTree(response.body()).path("detail"); if(detail.isTextual()) message=detail.asText().substring(0,Math.min(400,detail.asText().length())); } catch(Exception ignored) {}
                throw new BusinessException(response.statusCode()==409?409:503,message);
            }
            return json.readTree(response.body());
        } catch(BusinessException e){throw e;} catch(InterruptedException e){Thread.currentThread().interrupt();throw new BusinessException(503,"AI 请求已中断");}
        catch(Exception e){throw new BusinessException(503,"无法连接 AI 服务，请检查服务和模型配置");}
    }
}
