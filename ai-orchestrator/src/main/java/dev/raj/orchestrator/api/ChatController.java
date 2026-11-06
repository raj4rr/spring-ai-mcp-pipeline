package dev.raj.orchestrator.api;

import dev.raj.orchestrator.service.ChatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ChatController {
 private final ChatService chatService;
 public ChatController(ChatService chatService) { this.chatService = chatService; }

 @PostMapping(value="/chat", consumes=MediaType.APPLICATION_JSON_VALUE, produces=MediaType.APPLICATION_JSON_VALUE)
 public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
  return new ChatResponse(chatService.chat(request.message()));
 }
 public record ChatRequest(@NotBlank @Size(max=12000) String message) {}
 public record ChatResponse(String answer) {}
}
