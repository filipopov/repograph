package dev.demobank.gateway;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.demobank.contracts.AccountDto;
import dev.demobank.contracts.Channel;
import dev.demobank.contracts.PaymentDto;
import dev.demobank.contracts.PaymentStatus;
import dev.demobank.gateway.client.AccountsClient;
import dev.demobank.gateway.client.NotificationsClient;
import dev.demobank.gateway.client.PaymentsClient;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

@WebMvcTest(GatewayController.class)
class GatewayControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private AccountsClient accountsClient;
  @MockitoBean
  private PaymentsClient paymentsClient;
  @MockitoBean
  private NotificationsClient notificationsClient;

  @Test
  void routesAccountLookup() throws Exception {
    when(accountsClient.get("acc-alice"))
        .thenReturn(new AccountDto("acc-alice", "Alice", "CHF", new BigDecimal("10.00")));

    mockMvc.perform(get("/api/v1/accounts/acc-alice"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.owner").value("Alice"));
  }

  private static final String PAYMENT_BODY = """
      {"sourceAccountId":"acc-alice","targetAccountId":"acc-bob",
       "amount":5.00,"currency":"CHF","reference":"lunch"}
      """;

  @Test
  void routesPaymentCreation() throws Exception {
    when(paymentsClient.create(eq(Channel.MOBILE), any())).thenReturn(new PaymentDto("pay-1",
        "acc-alice", "acc-bob", new BigDecimal("5.00"), "CHF", "lunch", PaymentStatus.COMPLETED,
        null, Instant.now(), Channel.MOBILE));

    mockMvc.perform(post("/api/v1/payments")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Channel", "MOBILE")
            .content(PAYMENT_BODY))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.channel").value("MOBILE"));
  }

  @Test
  void missingChannelHeaderDefaultsToWeb() throws Exception {
    mockMvc.perform(post("/api/v1/payments")
            .contentType(MediaType.APPLICATION_JSON)
            .content(PAYMENT_BODY))
        .andExpect(status().isCreated());

    verify(paymentsClient).create(eq(Channel.WEB), any());
  }

  @Test
  void unknownChannelReturns400() throws Exception {
    mockMvc.perform(post("/api/v1/payments")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Channel", "TABLET")
            .content(PAYMENT_BODY))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(paymentsClient);
  }

  /** Channel names are case-sensitive, matching payments-service. */
  @Test
  void lowercaseChannelReturns400() throws Exception {
    mockMvc.perform(post("/api/v1/payments")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Channel", "mobile")
            .content(PAYMENT_BODY))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(paymentsClient);
  }

  @Test
  void passesDownstream404Through() throws Exception {
    when(accountsClient.get("acc-nobody")).thenThrow(HttpClientErrorException.create(
        HttpStatus.NOT_FOUND, "Not Found", null, new byte[0], StandardCharsets.UTF_8));

    mockMvc.perform(get("/api/v1/accounts/acc-nobody")).andExpect(status().isNotFound());
  }

  @Test
  void unreachableDownstreamReturns502() throws Exception {
    when(accountsClient.list()).thenThrow(new ResourceAccessException("Connection refused"));

    mockMvc.perform(get("/api/v1/accounts")).andExpect(status().isBadGateway());
  }
}
