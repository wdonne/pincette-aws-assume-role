package net.pincette.aar;

import static net.pincette.aar.Phase.Pending;
import static net.pincette.aar.Phase.Ready;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.generator.annotation.Required;

public class AWSAssumeRoleStatus {
  @JsonProperty("error")
  public final String error;

  @JsonProperty("phase")
  @Required
  public final Phase phase;

  @JsonCreator
  public AWSAssumeRoleStatus() {
    this(Ready, null);
  }

  AWSAssumeRoleStatus(final String error) {
    this(Pending, error);
  }

  private AWSAssumeRoleStatus(final Phase phase, final String error) {
    this.phase = phase;
    this.error = error;
  }
}
