package net.pincette.aar;

import static io.javaoperatorsdk.operator.api.reconciler.EventSourceInitializer.generateNameFor;
import static io.javaoperatorsdk.operator.api.reconciler.UpdateControl.patchStatus;
import static java.lang.Integer.MAX_VALUE;
import static java.lang.Math.max;
import static java.lang.Math.round;
import static java.util.Optional.ofNullable;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Logger.getLogger;
import static net.pincette.aar.SecretDependentResource.SELECTOR;
import static net.pincette.aar.Util.name;
import static net.pincette.util.Collections.map;
import static net.pincette.util.Pair.pair;
import static net.pincette.util.Util.tryToDo;

import io.fabric8.kubernetes.api.model.Secret;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceInitializer;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependentResourceConfigBuilder;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.timer.TimerEventSource;
import io.javaoperatorsdk.operator.processing.retry.GradualRetry;
import java.util.Map;
import java.util.logging.Logger;
import net.pincette.operator.util.Status;
import net.pincette.operator.util.Status.Condition;

@io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
@GradualRetry(maxAttempts = MAX_VALUE)
public class AWSAssumeRoleReconciler
    implements Reconciler<AWSAssumeRole>, EventSourceInitializer<AWSAssumeRole> {
  static final Logger LOGGER = getLogger("net.pincette.aar");
  private final KubernetesDependentResource<Secret, AWSAssumeRole> secretDR =
      new SecretDependentResource();
  private final TimerEventSource<AWSAssumeRole> timerEventSource = new TimerEventSource<>();

  public AWSAssumeRoleReconciler() {
    secretDR.configureWith(
        KubernetesDependentResourceConfigBuilder.<Secret>aKubernetesDependentResourceConfig()
            .withLabelSelector(SELECTOR)
            .build());
  }

  private static Status status(final AWSAssumeRole resource) {
    return ofNullable(resource.getStatus()).orElseGet(Status::new);
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
          LOGGER.info(() -> "Reconciling " + name(awsAssumeRole.getMetadata()));
          secretDR.reconcile(awsAssumeRole, context);
          renew(awsAssumeRole);
          awsAssumeRole.setStatus(status(awsAssumeRole).withCondition(new Condition()));
        },
        e -> {
          LOGGER.log(SEVERE, e, e::getMessage);
          timerEventSource.scheduleOnce(awsAssumeRole, 5000);
          awsAssumeRole.setStatus(status(awsAssumeRole).withException(e));
        });

    return patchStatus(awsAssumeRole);
  }
}
