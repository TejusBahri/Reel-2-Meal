package com.example.youtube;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.json.JSONObject;
import org.json.JSONArray;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class YouTubeService {

    @Value("${search.api.key}")
    private String searchApiKey;

    @Value("${custom.llm.url}")
    private String customLlmUrl;

    @Value("${custom.llm.api.key}")
    private String customLlmApiKey;

    private static final List<String> LANGUAGES = List.of("en", "hi", "te");

    public String extractVideoId(String url) {
        String regex = "^.*((youtu.be\\/)|(v\\/)|(\\/u\\/\\w\\/)|(embed\\/)|(watch\\?))\\??v?=?([^#&?]*).*";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(url);

        if (matcher.matches() && matcher.group(7).length() == 11) {
            return matcher.group(7);
        }

        regex = "^.*((youtube\\.com\\/shorts\\/)([^#&?]*)).*";
        pattern = Pattern.compile(regex);
        matcher = pattern.matcher(url);

        if (matcher.matches() && matcher.group(3).length() == 11) {
            return matcher.group(3);
        }

        return null;
    }

    public JSONObject fetchTranscript(String videoId, String language) {
        RestTemplate restTemplate = new RestTemplate();
        String url = "https://www.searchapi.io/api/v1/search?engine=youtube_transcripts&video_id=" + videoId + "&api_key=" + searchApiKey + "&lang=" + language;
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

        JSONObject jsonResponse = new JSONObject(response.getBody());
        if (jsonResponse.has("transcripts") && jsonResponse.getJSONArray("transcripts").length() > 0) {
            return jsonResponse;
        } else {
            return null;
        }
    }

    public JSONObject getTranscriptInAnyLanguage(String videoId) throws Exception {
        for (String lang : LANGUAGES) {
            JSONObject result = fetchTranscript(videoId, lang);
            if (result != null) {
                return result;
            }
        }
        throw new Exception("Could not find transcript in any of the specified languages");
    }

    public JSONObject analyzeTranscriptWithAI(JSONArray transcript, String videoTitle) throws Exception {
        StringBuilder fullText = new StringBuilder();
        for (int i = 0; i < transcript.length(); i++) {
            fullText.append(transcript.getJSONObject(i).getString("text")).append(" ");
        }

        String prompt = "Analyze the transcript and determine the type of content to provide a tailored response based on our business lines:\n" +
                "1. Cooking Reel:\n" +
                "Identify the transcript as a cooking reel if it focuses on preparing food at home.\n" +
                "Extract a list of ingredients mentioned in the transcript.\n" +
                "Format the response to redirect to Instamart with a list of ingredients.\n" +
                "2. Restaurant/Food Related Reel:\n" +
                "Identify the transcript as a restaurant or food-related reel if it discusses dining out or specific restaurants.\n" +
                "Extract and structure the information as follows:\n" +
                "- A list of all restaurant names mentioned.\n" +
                "- A list of all food items mentioned.\n" +
                "- A mapping showing which food items belong to which restaurants.\n" +
                "- Format the response as a JSON object with these exact fields:\n" +
                "restaurants: An array of restaurant names.\n" +
                "items: An array of food items.\n" +
                "mapping: An object where keys are restaurant names and values are arrays of food items available at that restaurant.\n" +
                "If a food item's restaurant isn't specified, include it in a special \"unknown\" category in the mapping.\n" +
                "If information isn't available, use empty arrays.\n" +
                "3. Healthy Content:\n" +
                "Identify the transcript as healthy-related content if it focuses on health and wellness.\n" +
                "Provide healthy suggestions based on the content.\n\n" +
                "Transcript: " + fullText.toString();

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("Authorization", "Bearer " + customLlmApiKey);

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", "bedrock-claude-3-sonnet");
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "user").put("content", "You are a food review analyzer. Extract structured information from food video transcripts and format it exactly as requested."));
        messages.put(new JSONObject().put("role", "user").put("content", prompt));
        requestBody.put("messages", messages);
        requestBody.put("response_format", new JSONObject().put("type", "json_object"));

        HttpEntity<String> entity = new HttpEntity<>(requestBody.toString(), headers);
        ResponseEntity<String> response = restTemplate.exchange(customLlmUrl + "/chat/completions", HttpMethod.POST, entity, String.class);

        JSONObject jsonResponse = new JSONObject(response.getBody());
        try {
            JSONObject llmAnalysis = new JSONObject(jsonResponse.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"));
            return new JSONObject()
                    .put("restaurants", llmAnalysis.optJSONArray("restaurants"))
                    .put("items", llmAnalysis.optJSONArray("items"))
                    .put("mapping", llmAnalysis.optJSONObject("mapping"))
                    .put("source_url", JSONObject.NULL)
                    .put("title", videoTitle);
        } catch (Exception e) {
            throw new Exception("Error parsing LLM response: " + e.getMessage());
        }
    }

    public JSONObject analyzeFoodVideo(String youtubeUrl) {
        try {
            String videoId = extractVideoId(youtubeUrl);
            if (videoId == null) {
                throw new Exception("Invalid YouTube URL");
            }

            JSONObject transcriptResult = getTranscriptInAnyLanguage(videoId);
            JSONArray transcripts = transcriptResult.getJSONArray("transcripts");

            JSONObject analysis = analyzeTranscriptWithAI(transcripts, null);

            return new JSONObject()
                    .put("video_id", videoId)
                    .put("video_url", youtubeUrl)
                    .put("transcript_language", transcriptResult.getString("language"))
                    .put("analysis_timestamp", java.time.Instant.now().toString())
                    .put("food_analysis", analysis);
        } catch (Exception e) {
            return new JSONObject()
                    .put("success", false)
                    .put("error", e.getMessage());
        }
    }
}
