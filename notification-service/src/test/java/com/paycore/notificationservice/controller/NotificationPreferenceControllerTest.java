package com.paycore.notificationservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.notificationservice.domain.entity.NotificationPreference;
import com.paycore.notificationservice.domain.entity.NotificationPreferenceId;
import com.paycore.notificationservice.domain.enums.NotificationChannel;
import com.paycore.notificationservice.repository.NotificationPreferenceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationPreferenceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private NotificationPreferenceRepository preferenceRepository;

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    @DisplayName("Updating optional notification preference succeeds")
    void updatePreference_OptionalEvent_ReturnsOk() throws Exception {
        UUID userId = UUID.randomUUID();
        NotificationPreferenceController.UpdatePreferenceRequest request = new NotificationPreferenceController.UpdatePreferenceRequest();
        request.setEventType("TransactionCompleted");
        request.setChannel(NotificationChannel.EMAIL);
        request.setEnabled(false);

        when(preferenceRepository.findById(any())).thenReturn(Optional.empty());
        when(preferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/api/v1/notifications/preferences")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    @DisplayName("Attempting to disable non-optional security notification returns 400 Bad Request")
    void updatePreference_NonOptionalEvent_ReturnsBadRequest() throws Exception {
        UUID userId = UUID.randomUUID();
        NotificationPreferenceController.UpdatePreferenceRequest request = new NotificationPreferenceController.UpdatePreferenceRequest();
        request.setEventType("AccountFrozen");
        request.setChannel(NotificationChannel.EMAIL);
        request.setEnabled(false);

        mockMvc.perform(put("/api/v1/notifications/preferences")
                        .header("X-User-Id", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Security-critical")));
    }
}
