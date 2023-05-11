package net.pincette.aar;

import static java.lang.System.getenv;
import static java.util.UUID.randomUUID;
import static net.pincette.aar.AWSAssumeRoleReconciler.LOGGER;
import static net.pincette.aar.Util.name;
import static net.pincette.util.Collections.map;
import static net.pincette.util.Pair.pair;
import static software.amazon.awssdk.services.sts.StsClient.builder;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;
import java.util.Map;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.Credentials;

@KubernetesDependent(labelSelector = SecretDependentResource.SELECTOR)
public class SecretDependentResource
    extends CRUDKubernetesDependentResource<Secret, AWSAssumeRole> {
  private static final String ACCOUNT_ID = getenv("AWS_ACCOUNT_ID");
  public static final String SELECTOR = "aws-assume-role";

  @SuppressWarnings({"java:S6241", "java:S6242"}) // Provided by the environment.
  private final StsClient stsClient = builder().build();

  public SecretDependentResource() {
    super(Secret.class);
  }

  private static String profile(final Credentials credentials) {
    return "[default]\naws_access_key_id="
        + credentials.accessKeyId()
        + "\naws_secret_access_key="
        + credentials.secretAccessKey()
        + "\naws_session_token="
        + credentials.sessionToken();
  }

  private static String roleArn(final String role) {
    return "arn:aws:iam::" + ACCOUNT_ID + ":role/" + role;
  }

  private Map<String, String> credentials(final AWSAssumeRole primary) {
    final Credentials credentials =
        stsClient
            .assumeRole(
                AssumeRoleRequest.builder()
                    .roleArn(roleArn(primary.getSpec().roleName))
                    .durationSeconds(primary.getSpec().durationSeconds)
                    .roleSessionName(randomUUID().toString())
                    .build())
            .credentials();

    return primary.getSpec().secretType == SecretType.Map
        ? map(
            pair("awsAccessKeyId", credentials.accessKeyId()),
            pair("awsSecretAccessKey", credentials.secretAccessKey()),
            pair("awsSessionToken", credentials.sessionToken()))
        : map(pair("credentials", profile(credentials)));
  }

  @Override
  protected Secret desired(final AWSAssumeRole primary, final Context<AWSAssumeRole> context) {
    LOGGER.info(() -> "Generate new secret for " + name(primary.getMetadata()));

    final Secret secret = new Secret();

    secret.setMetadata(
        new ObjectMetaBuilder()
            .withName(primary.getSpec().secretName)
            .withNamespace(primary.getMetadata().getNamespace())
            .withLabels(map(pair(SELECTOR, "true")))
            .build());

    secret.setStringData(credentials(primary));

    return secret;
  }
}
