package net.pincette.aar;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("pincette.net")
@Version("v1")
public class AWSAssumeRole extends CustomResource<AWSAssumeRoleSpec, Object>
    implements Namespaced {}
