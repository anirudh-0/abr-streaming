package com.example.abrstreaming.controller;

import com.example.abrstreaming.service.VideoService;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

@RestController
@RequestMapping("/api/videos")
public class VideoController {

    private static final Logger logger = LoggerFactory.getLogger(VideoController.class);

    @Autowired
    private VideoService videoService;

    @Autowired
    private MinioClient minioClient;

    @Value("${minio.bucket}")
    private String bucket;

    @PostMapping("/upload")
    public ResponseEntity<String> uploadVideo(@RequestParam("file") MultipartFile file) {
        logger.info("Received video upload request for file: {}, size: {} bytes", 
            file.getOriginalFilename(), file.getSize());
        try {
            String videoId = videoService.uploadAndProcess(file);
            logger.info("Successfully processed video upload. VideoId: {}", videoId);
            return ResponseEntity.ok(videoId);
        } catch (Exception e) {
            logger.error("Failed to process video upload for file: {}. Error: {}", 
                file.getOriginalFilename(), e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Error processing video: " + e.getMessage());
        }
    }

    @GetMapping("/{videoId}/master.m3u8")
    public ResponseEntity<InputStreamResource> getMasterPlaylist(@PathVariable String videoId) {
        logger.debug("Fetching master playlist for videoId: {}", videoId);
        try {
            InputStream stream = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(videoId + "/master.m3u8")
                    .build());
            logger.debug("Successfully retrieved master playlist for videoId: {}", videoId);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"))
                    .body(new InputStreamResource(stream));
        } catch (Exception e) {
            logger.error("Failed to retrieve master playlist for videoId: {}. Error: {}", 
                videoId, e.getMessage(), e);
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{videoId}/hls/{quality}/{filename}")
    public ResponseEntity<InputStreamResource> getHlsChunk(
            @PathVariable String videoId,
            @PathVariable String quality,
            @PathVariable String filename) {
        logger.debug("Fetching HLS chunk - videoId: {}, quality: {}, filename: {}", 
            videoId, quality, filename);
        try {
            InputStream stream = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(videoId + "/hls/" + quality + "/" + filename)
                    .build());
            logger.debug("Successfully retrieved HLS chunk - videoId: {}, quality: {}, filename: {}", 
                videoId, quality, filename);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"))
                    .body(new InputStreamResource(stream));
        } catch (Exception e) {
            logger.error("Failed to retrieve HLS chunk - videoId: {}, quality: {}, filename: {}. Error: {}", 
                videoId, quality, filename, e.getMessage(), e);
            return ResponseEntity.notFound().build();
        }
    }
}

