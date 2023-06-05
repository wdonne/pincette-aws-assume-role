package net.pincette.aar;

@SuppressWarnings("java:S115") // This goes into the OpenAPI spec.
public enum SecretType {
  EcrDockerConfigJson,
  File,
  Map
}
