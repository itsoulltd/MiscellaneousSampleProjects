package com.infoworks.fileprocessing.controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infoworks.fileprocessing.services.ExcelParsingService;
import com.infoworks.fileprocessing.services.LocalStorageService;
import com.infoworks.lab.rest.models.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StopWatch;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

@RestController
@RequestMapping("/api/files")
public class FileUploadController {

    private static Logger LOG = LoggerFactory.getLogger("FileUploadController");
    private LocalStorageService storageService;
    private ExcelParsingService contentService;

    @Autowired
    public FileUploadController(@Qualifier("local") LocalStorageService storageService
                            , ExcelParsingService contentService) {
        this.storageService = storageService;
        this.contentService = contentService;
    }

    @GetMapping("/rowCount")
    public ResponseEntity<String> getCounts(){
        return ResponseEntity.ok(String.format("{\"count\":\"%s\"}", storageService.size()));
    }

    @GetMapping
    public ResponseEntity<List<String>> getFileNames(){
        List<String> names = Arrays.asList(storageService.readKeys());
        return ResponseEntity.ok(names);
    }

    @PostMapping("/upload")
    public ResponseEntity<String> handleMultipartContent(
            @RequestParam("content") MultipartFile content,
            RedirectAttributes redirectAttributes) throws IOException {
        //
        storageService.put(content.getOriginalFilename(), content.getInputStream());
        //storageService.save(false);
        return ResponseEntity.ok("Content Received: " + content.getOriginalFilename());
    }

    @PostMapping("/read")
    public ResponseEntity<Map<String, Object>> handleReadContent(
            @RequestParam("contentName") String contentName
            , @RequestParam(value = "sheetAt", required = false) int sheetAt
            , @RequestParam(value = "rowStartIdx", required = false) int rowStartIdx
            , @RequestParam(value = "rowEndIdx", required = false) int rowEndIdx
            , @RequestParam(value = "colStartIdx", required = false) int colStartIdx
            , @RequestParam(value = "colEndIdx", required = false) int colEndIdx) {
        //
        Response response = new Response().setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value()).setMessage("");
        if (sheetAt < 0) sheetAt = 0;
        if (rowStartIdx < 0) rowStartIdx = 0;
        if (rowEndIdx <= 0) rowEndIdx = Integer.MAX_VALUE;
        if (colStartIdx < 0) colStartIdx = 0;
        if (colEndIdx <= 0) colEndIdx = 0;
        //
        final StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        //
        Map<String, Object> result = new HashMap<>();
        InputStream inputStream = storageService.read(contentName);
        ObjectMapper mapper = new ObjectMapper();
        if(inputStream != null) {
            try {
                InputStream fileInputStream = inputStream;
                Map<Integer, List<String>> data = contentService.read(fileInputStream, sheetAt, rowStartIdx, rowEndIdx);
                List<String> rows = new ArrayList<>();
                if (data.size() > 0){
                    int length = data.get(rowStartIdx).size();
                    if (colEndIdx <= 0 || colEndIdx > length)
                        colEndIdx = length;
                }
                final int colSIdx = colStartIdx, colEIdx = colEndIdx;
                data.forEach((key, value) -> {
                    try {
                        List<String> subItems = value.subList(colSIdx, colEIdx);
                        rows.add(mapper.writeValueAsString(subItems));
                    } catch (JsonProcessingException e) {}
                });
                result.put("rows", rows);
                response.setStatus(HttpStatus.OK.value());
            } catch (IOException e) {
                LOG.error(e.getMessage(), e);
                response.setError(e.getMessage());
            }
            //removing file once consume:
            storageService.remove(contentName);
        }
        //
        stopWatch.stop();
        //Log method execution time
        long executionTime = stopWatch.getTotalTimeMillis();
        result.put("executionTimeInMillis", executionTime);
        LOG.info("Execution time of " + ":: " + executionTime + " ms");
        //
        if (response.getStatus() == HttpStatus.OK.value()){
            return ResponseEntity.ok(result);
        }else{
            result.put("error", contentName + " not found!");
            return ResponseEntity.unprocessableEntity().body(result);
        }
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> downloadContent(@RequestParam("contentName") String contentName) {
        //
        String fileKey = contentName;
        InputStream inputStream = storageService.read(fileKey);
        //
        /*try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Map rows = new HashMap(); //TODO:Read Output Query:
            contentService.write(true, out, "output", rows);
            inputStream = new ByteArrayInputStream(out.toByteArray());
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
        }*/
        //Get The InputStream/OutputStream
        InputStreamResource file = new InputStreamResource(inputStream);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + contentName)
                .contentType(MediaType.parseMediaType("application/vnd.ms-excel"))
                .body(file);
    }

}
