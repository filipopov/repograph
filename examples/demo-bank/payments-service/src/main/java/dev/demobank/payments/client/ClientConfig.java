package dev.demobank.payments.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
class ClientConfig {

  @Bean
  AccountsClient accountsClient(RestClient.Builder builder,
      @Value("${demobank.services.accounts-url}") String baseUrl) {
    return create(builder, baseUrl, AccountsClient.class);
  }

  @Bean
  FraudClient fraudClient(RestClient.Builder builder,
      @Value("${demobank.services.fraud-url}") String baseUrl) {
    return create(builder, baseUrl, FraudClient.class);
  }

  private static <T> T create(RestClient.Builder builder, String baseUrl, Class<T> type) {
    RestClient restClient = builder.clone().baseUrl(baseUrl).build();
    return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
        .build()
        .createClient(type);
  }
}
