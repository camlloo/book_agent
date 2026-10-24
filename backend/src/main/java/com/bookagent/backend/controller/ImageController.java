package com.bookagent.backend.controller;
import com.bookagent.backend.common.exception.BusinessException;
import com.bookagent.backend.common.result.Result;
import io.minio.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.time.LocalDate;
import java.util.*;

@RestController @RequestMapping("/api/images") @RequiredArgsConstructor @Tag(name="图片上传")
public class ImageController {
    private final MinioClient minio;
    @Value("${minio.bucket}") private String bucket;
    @Value("${minio.public-url:${minio.endpoint}}") private String publicUrl;
    @PostMapping @Operation(summary="上传图片，校验格式与大小并返回永久直链")
    public Result<?> upload(@RequestParam("file") MultipartFile file) throws Exception {
        if(file.isEmpty()) throw new BusinessException(400,"请选择要上传的文件");
        if(file.getSize()>5*1024*1024) throw new BusinessException(400,"图片大小不能超过 5MB");
        byte[] bytes=file.getBytes(); String type=detect(bytes);
        String name=Optional.ofNullable(file.getOriginalFilename()).orElse("image");
        String ext=name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
        if(type==null || !(ext.equals(type) || type.equals("jpg") && ext.equals("jpeg"))) throw new BusinessException(400,"图片内容与格式不符，仅支持 jpg, jpeg, png, gif, webp");
        LocalDate now=LocalDate.now();
        String key="images/%d/%02d/%s.%s".formatted(now.getYear(),now.getMonthValue(),UUID.randomUUID(),type);
        minio.putObject(PutObjectArgs.builder().bucket(bucket).object(key).contentType("image/"+(type.equals("jpg")?"jpeg":type))
            .stream(new java.io.ByteArrayInputStream(bytes),bytes.length,-1).build());
        return Result.ok(Map.of("objectKey",key,"url",publicUrl.replaceAll("/$","")+"/"+bucket+"/"+key,"originalName",name,"size",file.getSize()));
    }
    static String detect(byte[] b){
        if(b.length<12)return null;
        if((b[0]&255)==255 && (b[1]&255)==216 && (b[2]&255)==255)return "jpg";
        if(Arrays.equals(Arrays.copyOf(b,8),new byte[]{(byte)137,80,78,71,13,10,26,10}))return "png";
        String head=new String(b,0,12,java.nio.charset.StandardCharsets.ISO_8859_1);
        if(head.startsWith("GIF87a")||head.startsWith("GIF89a"))return "gif";
        if(head.startsWith("RIFF")&&head.substring(8).equals("WEBP"))return "webp";
        return null;
    }
}
