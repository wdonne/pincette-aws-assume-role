package net.pincette.aar;

import static io.javaoperatorsdk.operator.api.reconciler.EventSourceInitializer.generateNameFor;
import static io.javaoperatorsdk.operator.api.reconciler.UpdateControl.noUpdate;
import static java.lang.Integer.MAX_VALUE;
import static java.lang.Math.max;
import static java.lang.Math.round;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Logger.getLogger;
import static net.pincette.aar.SecretDependentResource.SELECTOR;
import static net.pincette.util.Collections.map;
import static net.pincette.util.Pair.pair;
import static net.pincette.util.Util.tryToDo;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceInitializer;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependentResourceConfig;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.timer.TimerEventSource;
import io.javaoperatorsdk.operator.processing.retry.GradualRetry;
import java.util.Map;
import java.util.logging.Logger;

@io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
@GradualRetry(maxAttempts = MAX_VALUE)
public class AWSAssumeRoleReconciler
    implements Reconciler<AWSAssumeRole>, EventSourceInitializer<AWSAssumeRole> {
  static final Logger LOGGER = getLogger("net.pincette.aar");
  private final KubernetesDependentResource<Secret, AWSAssumeRole> secretDR =
      new SecretDependentResource();
  private final TimerEventSource<AWSAssumeRole> timerEventSource = new TimerEventSource<>();

  public AWSAssumeRoleReconciler(final KubernetesClient client) {
    secretDR.setKubernetesClient(client);
    secretDR.configureWith(
        new KubernetesDependentResourceConfig<Secret>().setLabelSelector(SELECTOR));
  }

  public Map<String, EventSource> prepareEventSources(
      final EventSourceContext<AWSAssumeRole> context) {
    final EventSource secretSource = secretDR.initEventSource(context);

    timerEventSource.start();

    return map(
        pair(generateNameFor(timerEventSource), timerEventSource),
        pair(generateNameFor(secretSource), secretSource));
  }

  private void renew(final AWSAssumeRole awsAssumeRole) {
    timerEventSource.scheduleOnce(
        awsAssumeRole, max(60, round(awsAssumeRole.getSpec().durationSeconds * 0.9)) * 1000);
  }

  public UpdateControl<AWSAssumeRole> reconcile(
      final AWSAssumeRole awsAssumeRole, final Context<AWSAssumeRole> context) {
    tryToDo(
        () -> {
          secretDR.reconcile(awsAssumeRole, context);
          renew(awsAssumeRole);
        },
        e -> {
          LOGGER.log(SEVERE, e, e::getMessage);
          timerEventSource.scheduleOnce(awsAssumeRole, 5000);
        });

    return noUpdate();
  }
}
