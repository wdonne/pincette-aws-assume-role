package net.pincette.aar;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Base64.getDecoder;
import static java.util.Base64.getEncoder;
import static java.util.UUID.randomUUID;
import static net.pincette.aar.AWSAssumeRoleReconciler.LOGGER;
import static net.pincette.aar.SecretType.EcrDockerConfigJson;
import static net.pincette.aar.Util.name;
import static net.pincette.json.Factory.f;
import static net.pincette.json.Factory.o;
import static net.pincette.json.Factory.v;
import static net.pincette.json.JsonUtil.string;
import static net.pincette.util.Collections.map;
import static net.pincette.util.Pair.pair;
import static net.pincette.util.Util.tryToGetWith;
import static software.amazon.awssdk.profiles.ProfileFile.Type.CREDENTIALS;
import static software.amazon.awssdk.services.sts.StsClient.builder;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;
import java.io.ByteArrayInputStream;
import java.util.Base64.Encoder;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import net.pincette.util.Pair;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.AuthorizationData;
import software.amazon.awssdk.services.ecr.model.GetAuthorizationTokenResponse;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.Credentials;

@KubernetesDependent(labelSelector = SecretDependentResource.SELECTOR)
public class SecretDependentResource
    extends CRUDKubernetesDependentResource<Secret, AWSAssumeRole> {
  public static final String SELECTOR = "aws-assume-role";

  @SuppressWarnings({"java:S6241", "java:S6242"}) // Provided by the environment.
  private final StsClient stsClient = builder().build();

  private final String accountId = getAccountId(stsClient);

  public SecretDependentResource() {
    super(Secret.class);
  }

  private static AwsCredentialsProvider credentialsProvider(final String profile) {
    return ProfileCredentialsProvider.builder()
        .profileFile(
            builder ->
                builder
                    .type(CREDENTIALS)
                    .content(new ByteArrayInputStream(profile.getBytes(US_ASCII))))
        .build();
  }

  private static String decodeToken(final String token) {
    return new String(getDecoder().decode(token), US_ASCII);
  }

  private static Optional<String> ecrSecret(final String token, final String repositoryUrl) {
    return openEcrToken(token)
        .map(
            pair ->
                string(
                    o(
                        f(
                            "auths",
                            o(
                                f(
                                    repositoryUrl,
                                    o(
                                        f("username", v(pair.first)),
                                        f("password", v(pair.second)))))))));
  }

  private static String ecrToken(final String profile) {
    return tryToGetWith(() -> getEcrClient(profile), EcrClient::getAuthorizationToken)
        .map(GetAuthorizationTokenResponse::authorizationData)
        .filter(list -> list.size() == 1)
        .map(list -> list.get(0))
        .map(AuthorizationData::authorizationToken)
        .orElse(null);
  }

  private static Map<String, String> encodeMap(final Map<String, String> map) {
    final Encoder encoder = getEncoder();

    return map(
        map.entrySet().stream()
            .map(e -> pair(e.getKey(), encoder.encodeToString(e.getValue().getBytes(UTF_8)))));
  }

  private static String getAccountId(final StsClient client) {
    return client.getCallerIdentity().account();
  }

  @SuppressWarnings({"java:S6241", "java:S6242"}) // Provided by the environment.
  private static EcrClient getEcrClient(final String profile) {
    return EcrClient.builder().credentialsProvider(credentialsProvider(profile)).build();
  }

  private static Optional<Pair<String, String>> openEcrToken(final String token) {
    return Optional.of(decodeToken(token))
        .map(t -> t.split(":"))
        .filter(a -> a.length == 2)
        .map(a -> pair(a[0], a[1]));
  }

  private static String profile(final Credentials credentials) {
    return "[default]\naws_access_key_id="
        + credentials.accessKeyId()
        + "\naws_secret_access_key="
        + credentials.secretAccessKey()
        + "\naws_session_token="
        + credentials.sessionToken();
  }

  private Map<String, String> credentials(final AWSAssumeRole primary) {
    return credentials(
        stsClient
            .assumeRole(
                AssumeRoleRequest.builder()
                    .roleArn(roleArn(primary.getSpec().roleName))
                    .durationSeconds(primary.getSpec().durationSeconds)
                    .roleSessionName(randomUUID().toString())
                    .build())
            .credentials(),
        primary);
  }

  private Map<String, String> credentials(
      final Credentials credentials, final AWSAssumeRole primary) {
    return switch (primary.getSpec().secretType) {
      case EcrDockerConfigJson -> ecrSecret(
              ecrToken(profile(credentials)), primary.getSpec().ecrRepositoryUrl)
          .map(secret -> map(pair(".dockerconfigjson", secret)))
          .orElseGet(Collections::emptyMap);
      case File -> map(pair("credentials", profile(credentials)));
      case Map -> map(
          pair("awsAccessKeyId", credentials.accessKeyId()),
          pair("awsSecretAccessKey", credentials.secretAccessKey()),
          pair("awsSessionToken", credentials.sessionToken()));
    };
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

    if (primary.getSpec().secretType == EcrDockerConfigJson) {
      secret.setType("kubernetes.io/dockerconfigjson");
    }

    secret.setData(encodeMap(credentials(primary)));

    return secret;
  }

  private String roleArn(final String role) {
    return "arn:aws:iam::" + accountId + ":role/" + role;
  }
}
