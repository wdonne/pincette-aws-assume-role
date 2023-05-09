package net.pincette.aar;

import static java.lang.System.getProperty;
import static java.util.logging.LogManager.getLogManager;
import static net.pincette.util.Util.tryToDoRethrow;

import io.javaoperatorsdk.operator.Operator;

public class Application {
  private static void initLogging() {
    if (getProperty("java.util.logging.config.class") == null
        && getProperty("java.util.logging.config.file") == null) {
      tryToDoRethrow(
          () ->
              getLogManager()
                  .readConfiguration(Application.class.getResourceAsStream("/logging.properties")));
    }
  }

  public static void main(final String[] args) {
    final Operator operator = new Operator();

    initLogging();
    operator.register(new AWSAssumeRoleReconciler(operator.getKubernetesClient()));
    operator.start();
  }
}
