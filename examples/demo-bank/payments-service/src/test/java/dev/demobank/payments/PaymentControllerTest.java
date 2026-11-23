package dev.demobank.payments;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.demobank.contracts.Channel;
import dev.demobank.contracts.PaymentRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

  private static final String BODY = """
      {"sourceAccountId":"acc-alice","targetAccountId":"acc-bob","amount":100.00,\
      "currency":"CHF","reference":"rent"}""";
  private static final PaymentRequest REQUEST =
      new PaymentRequest("acc-alice", "acc-bob", new BigDecimal("100.00"), "CHF", "rent");

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private PaymentService service;

  @Test
  void missingChannelHeaderDefaultsToWeb() throws Exception {
    mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isCreated());

    verify(service).execute(REQUEST, Channel.WEB);
  }

  @Test
  void channelHeaderIsPassedToService() throws Exception {
    mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(BODY)
            .header("X-Channel", "MOBILE"))
        .andExpect(status().isCreated());

    verify(service).execute(REQUEST, Channel.MOBILE);
  }

  @Test
  void invalidChannelHeaderIsBadRequest() throws Exception {
    mockMvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(BODY)
            .header("X-Channel", "FAX"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(service);
  }
}
