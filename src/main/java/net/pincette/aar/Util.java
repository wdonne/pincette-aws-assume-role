package net.pincette.aar;

import io.fabric8.kubernetes.api.model.ObjectMeta;

class Util {
  private Util() {}

  static String name(final ObjectMeta metadata) {
    return "(name: " + metadata.getName() + ", namespace: " + metadata.getNamespace() + ")";
  }
}
