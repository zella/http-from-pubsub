package org.zella

import cats.effect.{IO, Timer}
import com.typesafe.scalalogging.Logger
import fs2.Stream
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import org.apache.commons.codec.binary.Base64
import org.http4s.Headers

import scala.concurrent.duration._

package object frompubsub {
  def retry[A](ioa: IO[A], delay: FiniteDuration)(logger: Logger)(implicit timer: Timer[IO]): IO[A] = {
    ioa.handleErrorWith { error =>
      logger.error("Error", error)
      IO.sleep(delay) *> retry(ioa, delay)(logger)
    }
  }

  case class RequestMessage(uuid: String,
                            method: String,
                            uri: String,
                            headers: Map[String, Seq[String]],
                            base64Body: String)

  object RequestMessage {
    implicit val jsonDecoder: Decoder[RequestMessage] = deriveDecoder
  }

  case class ResponseMessage(uuid: String,
                             status: Int,
                             headers: Map[String, Seq[String]],
                             base64Body: String)

  object ResponseMessage {
    implicit val jsonEncoder: Encoder[ResponseMessage] = deriveEncoder

    def create(uuid: String, status: Int, headers: Headers, body: Stream[IO, Byte]): ResponseMessage = {

      val bytes = body.compile.toVector.unsafeRunSync().toArray
      val bytes64 = Base64.encodeBase64(bytes)
      val body64 = new String(bytes64)

      val headers_ : Map[String, Seq[String]] = headers.toList.map(_.toRaw).map(h => (h.name.value, h.value.split(",").toSeq)).toMap

      new ResponseMessage(uuid, status, headers_, body64)
    }
  }

}
