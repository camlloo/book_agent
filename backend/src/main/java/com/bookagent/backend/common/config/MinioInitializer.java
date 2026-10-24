package com.bookagent.backend.common.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketPolicyArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * MinIO 初始化：确保 bucket 存在，并将 images/ 前缀设为匿名只读。
 * 博客图片需要永久直链（Markdown 内嵌后长期有效），因此不走有时效的预签名 URL，
 * 而是允许访客浏览器匿名 GET images/ 下的对象。
 */
@Slf4j
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="minio.enabled",havingValue="true",matchIfMissing=true)
@RequiredArgsConstructor
public class MinioInitializer implements CommandLineRunner {

    private final MinioClient minioClient;

    @Value("${minio.bucket}")
    private String bucket;

    @Override
    public void run(String... args) throws Exception {
        boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.info("已创建 bucket: {}", bucket);
        }

        // 只放开 images/ 前缀的匿名读取，bucket 其余路径保持私有
        String policy = """
                {
                  "Version": "2012-10-17",
                  "Statement": [ {
                    "Effect": "Allow",
                    "Principal": { "AWS": ["*"] },
                    "Action": ["s3:GetObject"],
                    "Resource": ["arn:aws:s3:::%s/images/*"]
                  } ]
                }
                """.formatted(bucket);
        minioClient.setBucketPolicy(
                SetBucketPolicyArgs.builder().bucket(bucket).config(policy).build());
        log.info("已确保 bucket[{}] 的 images/ 前缀匿名可读", bucket);
    }
}
