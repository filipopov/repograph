package dev.demobank.gateway.client;

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
  PaymentsClient paymentsClient(RestClient.Builder builder,
      @Value("${demobank.services.payments-url}") String baseUrl) {
    return create(builder, baseUrl, PaymentsClient.class);
  }

  @Bean
  NotificationsClient notificationsClient(RestClient.Builder builder,
      @Value("${demobank.services.notifications-url}") String baseUrl) {
    return create(builder, baseUrl, NotificationsClient.class);
  }

  private static <T> T create(RestClient.Builder builder, String baseUrl, Class<T> type) {
    RestClient restClient = builder.clone().baseUrl(baseUrl).build();
    return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
        .build()
        .createClient(type);
  }
}
