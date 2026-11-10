package com.ieee.evaluator.service;

import com.ieee.evaluator.controller.AnalysisProgressController;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

class ProgressEmitterTest {

    /** Clients start the work on onopen, so the stream must connect before any progress exists. */
    @Test
    void streamConnectsBeforeTheFirstEventAndCarriesLaterOnes() throws Exception {
        ProgressEmitter emitter = new ProgressEmitter();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AnalysisProgressController(emitter)).build();

        MockHttpServletResponse response = mvc.perform(get("/api/ai/progress/s1"))
            .andExpect(request().asyncStarted())
            .andReturn().getResponse();
        assertTrue(response.getContentAsString().contains(":connected"), "stream must commit on connect");

        emitter.emit("s1", "READING", "Reading the Diagram Analysis…", 30);
        emitter.complete("s1");

        String body = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("event:progress"));
        assertTrue(body.contains("\"percent\":30"));
        assertTrue(body.contains("event:done"));
    }
}
