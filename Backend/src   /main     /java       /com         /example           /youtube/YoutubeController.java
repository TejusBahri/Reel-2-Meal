package com.example.youtube;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import org.json.JSONObject;

@RestController
@RequestMapping("/api")
public class YouTubeController {

    @Autowired
    private YouTubeService youTubeService;

    @PostMapping("/analyze")
    public String analyze(@RequestBody String youtubeUrl) {
        JSONObject result = youTubeService.analyzeFoodVideo(youtubeUrl);
        return result.toString();
    }
}
