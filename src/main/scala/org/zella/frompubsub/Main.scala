package org.zella.frompubsub

import cats.effect.{ExitCode, IO, IOApp}
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration._

object Main extends IOApp with LazyLogging {
  override def run(args: List[String]): IO[ExitCode] = {
    retry(new Receiver(
      projectId = sys.env("PROJECT_ID"),
      subscriptionId = sys.env("SUBSCRIPTION_ID"),
      targetEndpoint = sys.env("TARGET_ENDPOINT"),
      proxyEndpoint = sys.env("PROXY_ENDPOINT"),
      maxMessageBytes = sys.env.getOrElse("MAX_MESSAGE_BYTES", (1024 * 1024 * 4).toString).toInt, //4MB default
      receiveTimeoutSeconds = sys.env.getOrElse("RECEIVE_TIMEOUT_SECONDS", 10.toString).toInt,
      pullPeriodMillis = sys.env.getOrElse("PULL_PERIOD_MILLIS", 1000.toString).toInt,
      sender = new Sender(sys.env("PROJECT_ID"), sys.env("TOPIC_ID"))
    ).startPulling().map(_ => ExitCode.Error), 4.seconds)(logger)
  }
}
