package com.example.abrstreaming.service;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;

@Service
public class VideoService {

    private static final Logger logger = LoggerFactory.getLogger(VideoService.class);
    private static final String OUTPUT_DIR = "output";
    private final Path outputPath;

    public VideoService() {
        try {
            // Initialize output directory in current working directory
            outputPath = Paths.get(System.getProperty("user.dir"), OUTPUT_DIR);
            Files.createDirectories(outputPath);
            logger.info("Created output directory at: {}", outputPath);
        } catch (IOException e) {
            logger.error("Failed to create output directory", e);
            throw new RuntimeException("Failed to create output directory", e);
        }
    }

    @Autowired
    private MinioClient minioClient;

    @Value("${minio.bucket}")
    private String bucket;

    private static final List<String> QUALITIES = Arrays.asList("144p", "240p", "480p", "720p", "1080p");

    // Add timing data structures
    private static class ProcessingTimes {
        long transcodeTime;
        long hlsTime;
        long uploadTime;
        
        public long getTotal() {
            return transcodeTime + hlsTime + uploadTime;
        }
    }
    private Map<String, ProcessingTimes> qualityTimings = new HashMap<>();
    private Instant processingStartTime;
    private long totalProcessingTime;

    public String uploadAndProcess(MultipartFile file) throws IOException {
        logger.info("Starting upload and processing for file: {}", file.getOriginalFilename());
        processingStartTime = Instant.now();
        qualityTimings.clear();
        
        String videoId = UUID.randomUUID().toString();
        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename.substring(originalFilename.lastIndexOf("."));

        logger.debug("Generated video ID: {}", videoId);

        // Create output directory if it doesn't exist
        Files.createDirectories(outputPath);
        
        // Save original file
        Path tempFile = outputPath.resolve(videoId + extension);
        logger.debug("logging all variables: {} {} {} {} {} {}", videoId, extension, outputPath, tempFile.toString(), file, originalFilename);
        file.transferTo(tempFile.toFile());
        logger.debug("Saved original file to temporary location: {}", tempFile);

        // Upload original file to Minio
        uploadToMinio(tempFile.toFile(), videoId + "/original" + extension);
        logger.info("Uploaded original file to Minio");

        // Process video
        processVideo(tempFile.toFile(), videoId);
        
        // Calculate total processing time
        totalProcessingTime = Duration.between(processingStartTime, Instant.now()).toMillis();
        
        // Generate and log processing report
        generateProcessingReport(videoId);
        
        logger.info("Completed video processing for videoId: {}", videoId);
        return videoId;
    }

    private void uploadToMinio(File file, String objectName) {
        logger.debug("Uploading file to Minio: {}", objectName);
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(Files.newInputStream(file.toPath()), file.length(), -1)
                    .build());
            logger.info("Successfully uploaded file to Minio: {}", objectName);
        } catch (Exception e) {
            logger.error("Error uploading file to Minio: {}", objectName, e);
            throw new RuntimeException("Error uploading file to Minio", e);
        }
    }

    private void processVideo(File inputFile, String videoId) {
        logger.info("Starting video processing for videoId: {}", videoId);
        for (String quality : QUALITIES) {
            logger.debug("Processing quality: {} for videoId: {}", quality, videoId);
            qualityTimings.put(quality, new ProcessingTimes());
            
            String outputFilename = outputPath.resolve(videoId + "_" + quality + ".mp4").toString();
            String hlsOutputPath = outputPath.resolve(Paths.get(videoId, "hls", quality)).toString();

            // Transcode video with timing
            Instant transcodeStart = Instant.now();
            transcodeVideo(inputFile.getAbsolutePath(), outputFilename, quality);
            qualityTimings.get(quality).transcodeTime = Duration.between(transcodeStart, Instant.now()).toMillis();
            logger.debug("Transcoded video to quality: {} for videoId: {}", quality, videoId);

            // Create HLS chunks with timing
            Instant hlsStart = Instant.now();
            createHlsChunks(outputFilename, hlsOutputPath);
            qualityTimings.get(quality).hlsTime = Duration.between(hlsStart, Instant.now()).toMillis();
            logger.debug("Created HLS chunks for quality: {} for videoId: {}", quality, videoId);

            // Upload transcoded video and HLS chunks to Minio with timing
            Instant uploadStart = Instant.now();
            uploadToMinio(new File(outputFilename), videoId + "/" + new File(outputFilename).getName());
            uploadHlsChunksToMinio(hlsOutputPath, videoId + "/hls/" + quality);
            qualityTimings.get(quality).uploadTime = Duration.between(uploadStart, Instant.now()).toMillis();
            logger.debug("Uploaded transcoded video and HLS chunks for quality: {} for videoId: {}", quality, videoId);
        }

        // Create master playlist
        createMasterPlaylist(videoId);
        logger.info("Completed video processing for videoId: {}", videoId);
    }

    private void transcodeVideo(String inputPath, String outputPath, String quality) {
        // Use FFmpeg to transcode video
        // This is a simplified example, you may need to adjust parameters based on your requirements
        String command = String.format("ffmpeg -i %s -vf scale=%s -c:a aac -b:a 128k %s",
                inputPath, getScaleForQuality(quality), outputPath);
        executeCommand(command);
    }

    private void createHlsChunks(String inputPath, String outputPath) {
        // Create output directory if it doesn't exist
        File outputDir = new File(outputPath);
        if (!outputDir.exists()) {
            logger.debug("Creating output directory: {}", outputPath);
            if (!outputDir.mkdirs()) {
                logger.error("Failed to create output directory: {}", outputPath);
                throw new RuntimeException("Failed to create output directory: " + outputPath);
            }
        }

        // Use FFmpeg to create HLS chunks
        String command = String.format("ffmpeg -i %s -hls_time 10 -hls_list_size 0 %s/playlist.m3u8",
                inputPath, outputPath);
        executeCommand(command);
    }

    private void createMasterPlaylist(String videoId) {
        logger.info("Creating master playlist for videoId: {}", videoId);
        StringBuilder masterPlaylist = new StringBuilder("#EXTM3U\n");
        for (String quality : QUALITIES) {
            logger.debug("Adding quality {} to master playlist", quality);
            masterPlaylist.append(String.format("#EXT-X-STREAM-INF:BANDWIDTH=%d,RESOLUTION=%s\n",
                    getBandwidthForQuality(quality), getResolutionForQuality(quality)));
            masterPlaylist.append(quality + "/playlist.m3u8\n");
        }

        try {
            String masterPlaylistPath = outputPath.resolve(videoId + "_master.m3u8").toString();
            logger.debug("Writing master playlist to file: {}", masterPlaylistPath);
            Files.write(Path.of(masterPlaylistPath), masterPlaylist.toString().getBytes());
            logger.debug("Uploading master playlist to Minio");
            uploadToMinio(new File(masterPlaylistPath), videoId + "/master.m3u8");
            logger.info("Successfully created and uploaded master playlist for videoId: {}", videoId);
        } catch (IOException e) {
            logger.error("Error creating master playlist for videoId: {}. Error: {}", videoId, e.getMessage(), e);
            throw new RuntimeException("Error creating master playlist", e);
        }
    }

    private void uploadHlsChunksToMinio(String hlsOutputPath, String minioPath) {
        logger.debug("Starting upload of HLS chunks from {} to Minio path {}", hlsOutputPath, minioPath);
        File hlsDir = new File(hlsOutputPath);
        File[] files = hlsDir.listFiles();
        if (files == null || files.length == 0) {
            logger.warn("No HLS chunks found in directory: {}", hlsOutputPath);
            return;
        }
        logger.info("Found {} HLS chunks to upload", files.length);
        for (File file : files) {
            logger.debug("Uploading HLS chunk: {}", file.getName());
            uploadToMinio(file, minioPath + "/" + file.getName());
        }
        logger.info("Completed uploading {} HLS chunks to {}", files.length, minioPath);
    }

    private void executeCommand(String command) {
        logger.debug("Executing command: {}", command);
        try {
            Process process = Runtime.getRuntime().exec(command);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.error("Command failed with exit code: {}. Command: {}", exitCode, command);
                throw new RuntimeException("Command failed with exit code: " + exitCode);
            }
            logger.debug("Command completed successfully with exit code: {}", exitCode);
        } catch (Exception e) {
            logger.error("Error executing command: {}. Error: {}", command, e.getMessage(), e);
            throw new RuntimeException("Error executing command: " + command, e);
        }
    }

    private String getScaleForQuality(String quality) {
        logger.trace("Getting scale for quality: {}", quality);
        try {
            switch (quality) {
                case "144p": return "256:144";
                case "240p": return "426:240";
                case "480p": return "854:480";
                case "720p": return "1280:720";
                case "1080p": return "1920:1080";
                default:
                    logger.error("Invalid quality requested: {}", quality);
                    throw new IllegalArgumentException("Invalid quality: " + quality);
            }
        } catch (Exception e) {
            logger.error("Error getting scale for quality: {}. Error: {}", quality, e.getMessage());
            throw e;
        }
    }

    private int getBandwidthForQuality(String quality) {
        switch (quality) {
            case "144p": return 300000;
            case "240p": return 700000;
            case "480p": return 1500000;
            case "720p": return 3000000;
            case "1080p": return 6000000;
            default: throw new IllegalArgumentException("Invalid quality: " + quality);
        }
    }

    private String getResolutionForQuality(String quality) {
        switch (quality) {
            case "144p": return "256x144";
            case "240p": return "426x240";
            case "480p": return "854x480";
            case "720p": return "1280x720";
            case "1080p": return "1920x1080";
            default: throw new IllegalArgumentException("Invalid quality: " + quality);
        }
    }

    private void generateProcessingReport(String videoId) {
        StringBuilder report = new StringBuilder();
        report.append("\n=== Video Processing Report ===\n");
        report.append("Video ID: ").append(videoId).append("\n");
        report.append("Total Processing Time: ").append(formatDuration(totalProcessingTime)).append("\n\n");
        report.append("Quality-wise Breakdown:\n");
        report.append(String.format("%-10s %-15s %-15s %-15s %-15s\n", 
                     "Quality", "Transcode", "HLS", "Upload", "Total"));
        report.append("----------------------------------------------------------\n");

        long totalTranscode = 0;
        long totalHls = 0;
        long totalUpload = 0;

        for (String quality : QUALITIES) {
            ProcessingTimes times = qualityTimings.get(quality);
            if (times != null) {
                report.append(String.format("%-10s %-15s %-15s %-15s %-15s\n",
                    quality,
                    formatDuration(times.transcodeTime),
                    formatDuration(times.hlsTime),
                    formatDuration(times.uploadTime),
                    formatDuration(times.getTotal())
                ));
                totalTranscode += times.transcodeTime;
                totalHls += times.hlsTime;
                totalUpload += times.uploadTime;
            }
        }

        report.append("\nTotals by Process Type:\n");
        report.append(String.format("Transcoding: %s\n", formatDuration(totalTranscode)));
        report.append(String.format("HLS Creation: %s\n", formatDuration(totalHls)));
        report.append(String.format("Uploading: %s\n", formatDuration(totalUpload)));
        report.append(String.format("Total Processing Time: %s\n", formatDuration(totalProcessingTime)));

        logger.info(report.toString());
    }

    private String formatDuration(long milliseconds) {
        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format("%02d:%02d.%03d", minutes, seconds, milliseconds % 1000);
    }
}

