package com.bookagent.backend.service;

import org.springframework.web.multipart.MultipartFile;
import java.io.*;
public record ByteMultipart(String filename,byte[] bytes) implements MultipartFile {
    public String getName(){return "file";} public String getOriginalFilename(){return filename;}
    public String getContentType(){return "application/octet-stream";}public boolean isEmpty(){return bytes.length==0;}
    public long getSize(){return bytes.length;}public byte[] getBytes(){return bytes;}
    public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}
    public void transferTo(File destination)throws IOException{java.nio.file.Files.write(destination.toPath(),bytes);}
}
