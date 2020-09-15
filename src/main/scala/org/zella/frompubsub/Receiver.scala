package org.zella.frompubsub

import java.util.concurrent.Executors

import cats.effect.{Blocker, IO}
import com.google.cloud.pubsub.v1.stub.{GrpcSubscriberStub, SubscriberStubSettings}
import com.google.pubsub.v1.{AcknowledgeRequest, ProjectSubscriptionName, PullRequest, ReceivedMessage}
import com.typesafe.scalalogging.LazyLogging
import io.circe.parser._
import org.apache.commons.codec.binary.Base64
import org.http4s.client._
import org.http4s._
import org.http4s.client.middleware.FollowRedirect
import org.threeten.bp.Duration

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class Receiver(projectId: String,
               subscriptionId: String,
               proxyEndpoint: String,
               targetEndpoint: String,
               maxMessageBytes: Int,
               receiveTimeoutSeconds: Int,
               pullPeriodMillis: Int,
               sender: Sender,
              ) extends LazyLogging {

  private val blockingEc = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  implicit val timer = IO.timer(ExecutionContext.global)
  implicit val cs = IO.contextShift(ExecutionContext.global)
  implicit val blocker = Blocker.liftExecutionContext(blockingEc)

  //TODO tuning default timeouts
  private val baseClient: Client[IO] = JavaNetClientBuilder[IO](blocker).create
  private val httpClient = FollowRedirect(8)(baseClient)

  private val subscriberStubSettingsBuilder = SubscriberStubSettings.newBuilder()
  subscriberStubSettingsBuilder
    .pullSettings()
    .setRetrySettings(
      subscriberStubSettingsBuilder.pullSettings().getRetrySettings().toBuilder()
        .setTotalTimeout(Duration.ofSeconds(receiveTimeoutSeconds))
        .build());
  private val subscriberStubSettings = subscriberStubSettingsBuilder.setTransportChannelProvider(
    SubscriberStubSettings.defaultGrpcTransportProviderBuilder()
      .setMaxInboundMessageSize(maxMessageBytes)
      .build()).build();

  private val subscriber = GrpcSubscriberStub.create(subscriberStubSettings)

  private val subscriptionName = ProjectSubscriptionName.format(projectId, subscriptionId);

  private def pullMessages: IO[Seq[ReceivedMessage]] = IO {
    val pullRequest = PullRequest.newBuilder.setMaxMessages(1).setSubscription(subscriptionName).build
    val pullResponse = subscriber.pullCallable.call(pullRequest)
    logger.info(s"Pulled ${pullResponse.getReceivedMessagesCount} messages")
    pullResponse.getReceivedMessagesList.asScala.toSeq
  }.handleErrorWith {
    e =>
      logger.error("Fail to pull messages", e)
      IO.pure(Seq.empty)
  } //TODO restart on multiple failures? no, healthcheck

  //TODO test failure restart or not on parsing
  private def messageToRequest(m: ReceivedMessage): IO[RequestMessage] = IO.fromEither(
    for {
      json <- parse(m.getMessage.getData.toStringUtf8)
      _ <- Right(logger.debug(json.toString()))
      m <- json.as[RequestMessage]
    } yield m)


  private def sendRequest(m: RequestMessage): IO[Unit] = IO.suspend {

    val req = Request[IO](
      method = Method.fromString(m.method.toUpperCase).getOrElse(throw new RuntimeException(s"Can't parse method ${m.method.toUpperCase}")),
      uri = Uri.unsafeFromString(targetEndpoint + m.uri.replace(proxyEndpoint, "")), //TODO slashes /
      headers = Headers.of(m.headers.map { case (k, vals) => Header.apply(k, vals.mkString(",")).parsed }.toList: _*),
      body = fs2.Stream.emits(Base64.decodeBase64(m.base64Body)),
      httpVersion = HttpVersion.`HTTP/1.1`
    )

    for {
      _ <- IO(logger.info(s"Request to target endpoint: ${req.uri}"))
      _ <- httpClient.run(req).use(r => for {
        messageOut <- IO.pure(ResponseMessage.create(
          uuid = m.uuid,
          status = r.status.code,
          headers = r.headers,
          body = r.body))
        _ <- sender.send(messageOut)
      } yield ()
      )
    } yield ()
  }

  private def sendAck(ackId: String): IO[Unit] = IO {
    val acknowledgeRequest = AcknowledgeRequest.newBuilder.setSubscription(subscriptionName).addAckIds(ackId).build
    subscriber.acknowledgeCallable().call(acknowledgeRequest);
  }

  //  def startPulling(): IO[Unit] = {
  //    fs2.Stream.eval(pullMessages)
  //      .delayBy(pullPeriodMillis.millis)
  //      .repeat
  //      .flatMap(msgs => fs2.Stream.emits(msgs))
  //      .mapAsync(1)(m => messageToRequest(m))
  //      .mapAsync(1)(r => sendRequest(r))
  //      .compile.drain
  //  }

  def startPulling(): IO[Unit] = for {
    _ <- IO(logger.info("Start pulling messages ..."))
    _ <- fs2.Stream.eval(pullMessages)
      .delayBy(pullPeriodMillis.millis)
      .repeat
      .flatMap(msgs => fs2.Stream.emits(msgs))
      .evalMap(m =>
        for {
          req <- messageToRequest(m)
          ok <- sendRequest(req)
          _ <- sendAck(m.getAckId)
        } yield ()
      )
      .compile.drain
  } yield ()
}
