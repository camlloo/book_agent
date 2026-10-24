package com.bookagent.backend.common.config;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.*;

@Configuration @EnableScheduling
public class AiConfig {
    @Bean public HttpClient aiHttpClient(){return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();}
    @Bean(destroyMethod="shutdownNow") public ExecutorService aiExecutor(){return Executors.newSingleThreadExecutor();}
    @Bean(destroyMethod="shutdownNow") public ExecutorService bookIndexExecutor(){return Executors.newSingleThreadExecutor();}
}
