package org.zella.frompubsub

import cats.effect.IO
import com.google.cloud.pubsub.v1.Publisher
import com.google.protobuf.ByteString
import com.google.pubsub.v1.{PubsubMessage, TopicName}
import com.typesafe.scalalogging.LazyLogging
import io.circe.syntax.EncoderOps

class Sender(projectId: String, topicId: String) extends LazyLogging {

  private val topicName: TopicName = TopicName.of(projectId, topicId)
  private val publisher: Publisher = Publisher.newBuilder(topicName).build()

  def send(message: ResponseMessage): IO[Unit] = IO {
    val messageJson = message.asJson
    logger.debug(messageJson.toString())

    val pubsubMessage = PubsubMessage.newBuilder()
      .setData(ByteString.copyFromUtf8(messageJson.noSpaces))
      .build()

    val messageId = publisher.publish(pubsubMessage).get()
    logger.info(s"Message $messageId sended")
  }

}
