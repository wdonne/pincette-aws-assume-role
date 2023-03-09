package net.pincette.aar;

import static io.javaoperatorsdk.operator.api.reconciler.EventSourceInitializer.generateNameFor;
import static io.javaoperatorsdk.operator.api.reconciler.UpdateControl.noUpdate;
import static java.lang.Integer.MAX_VALUE;
import static java.lang.Math.max;
import static java.lang.Math.round;
import static net.pincette.util.Collections.map;
import static net.pincette.util.Pair.pair;

import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceInitializer;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.dependent.Dependent;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.timer.TimerEventSource;
import io.javaoperatorsdk.operator.processing.retry.GradualRetry;
import java.util.Map;

@io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration(
    dependents = {@Dependent(type = SecretDependentResource.class)})
@GradualRetry(maxAttempts = MAX_VALUE)
public class AWSAssumeRoleReconciler
    implements Reconciler<AWSAssumeRole>, EventSourceInitializer<AWSAssumeRole> {
  private final TimerEventSource<AWSAssumeRole> timerEventSource = new TimerEventSource<>();

  public Map<String, EventSource> prepareEventSources(
      final EventSourceContext<AWSAssumeRole> context) {
    timerEventSource.start();

    return map(pair(generateNameFor(timerEventSource), timerEventSource));
  }

  private void renew(final AWSAssumeRole awsAssumeRole) {
    timerEventSource.scheduleOnce(
        awsAssumeRole, max(60, round(awsAssumeRole.getSpec().durationSeconds * 0.9)) * 1000);
  }

  public UpdateControl<AWSAssumeRole> reconcile(
      final AWSAssumeRole awsAssumeRole, final Context<AWSAssumeRole> context) {
    renew(awsAssumeRole);

    return noUpdate();
  }
}
