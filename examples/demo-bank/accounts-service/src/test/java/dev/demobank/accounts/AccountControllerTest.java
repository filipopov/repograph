package dev.demobank.accounts;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AccountController.class)
@Import({AccountService.class, AccountRepository.class})
class AccountControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Test
  void getsAccount() throws Exception {
    mockMvc.perform(get("/accounts/acc-alice"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.owner").value("Alice"))
        .andExpect(jsonPath("$.currency").value("CHF"));
  }

  @Test
  void unknownAccountReturns404() throws Exception {
    mockMvc.perform(get("/accounts/acc-nobody")).andExpect(status().isNotFound());
  }

  @Test
  void overdraftReturns409() throws Exception {
    mockMvc.perform(post("/accounts/acc-bob/debit")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"amount": 999999, "currency": "CHF", "reference": "test"}
                """))
        .andExpect(status().isConflict());
  }
}
