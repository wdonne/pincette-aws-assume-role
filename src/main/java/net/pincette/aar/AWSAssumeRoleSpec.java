package net.pincette.aar;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.generator.annotation.Required;

public class AWSAssumeRoleSpec {
  @JsonProperty("durationSeconds")
  public int durationSeconds = 900;

  @JsonProperty("ecrRepositoryUrl")
  public String ecrRepositoryUrl;

  @JsonProperty("roleName")
  @Required
  public String roleName;

  @JsonProperty("secretName")
  @Required
  public String secretName;

  @JsonProperty("secretType")
  @Required
  public SecretType secretType;
}
