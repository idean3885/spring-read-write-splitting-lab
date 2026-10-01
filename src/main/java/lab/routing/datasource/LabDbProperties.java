package lab.routing.datasource;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lab.db")
public record LabDbProperties(Node source, Node replica, String username, String password,
                              String adminUsername, String adminPassword, int poolSize) {
  public record Node(String host, int port) {
    public String jdbcUrl(String schema) {
      return "jdbc:mysql://%s:%d/%s?allowPublicKeyRetrieval=true&useSSL=false".formatted(host, port, schema);
    }
  }
}
